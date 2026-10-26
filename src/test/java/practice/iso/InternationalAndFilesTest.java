package practice.iso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.jpos.iso.ISOMsg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** International transactions, DCC and the end-of-day scheme files. */
class InternationalAndFilesTest {

    private static final String VISA = "4111111111111111";        // PIN 1234, INR 25,000.00, limit 10,000.00
    private static final String VISA_LOW = "4000000000000002";    // INR 500.00
    private static final String RUPAY = "6521000000000033";       // INR 15,000.00
    private static final String AMEX = "378282246310005";         // INR 50,000.00
    private static final String MC_INTL_OFF = "5200828282828210"; // international use turned off
    private static final String EXPIRY = "2812";
    private static final long TIMEOUT_MS = 2_000;
    private static final Iso.Currency USD = Iso.currency("USD");  // 84.0000
    private static final Iso.Currency AED = Iso.currency("AED");  // 22.8700

    @TempDir
    Path output;

    private IssuerHost host;
    private TerminalClient client;
    private final MessageFactory factory = new MessageFactory();

    @BeforeEach
    void startHostAndSignOn() throws Exception {
        host = new IssuerHost(new AuthEngine(CardStore.withTestCards(), output), false);
        int port = host.start(0);
        client = new TerminalClient("localhost", port, false);
        client.connect();
        assertEquals("00", code(client.send(factory.network("001"), TIMEOUT_MS)));
    }

    @AfterEach
    void stop() throws Exception {
        client.close();
        host.close();
    }

    @Test
    @DisplayName("USD 100.00 purchase: DE 6 = INR 8,400.00, plus 3.5% forex markup, hold INR 8,694.00")
    void usdPurchase() throws Exception {
        ISOMsg req = factory.internationalPurchase(VISA, EXPIRY, USD, 100_00, null);
        assertEquals("840", req.getString(49));
        assertEquals("000000840000", req.getString(6));
        assertEquals("40840000", req.getString(10));
        assertEquals("356", req.getString(51));
        assertEquals("840", req.getString(19));

        ISOMsg resp = client.send(req, TIMEOUT_MS);
        assertEquals("00", code(resp));
        assertEquals("000000840000", resp.getString(6), "the response echoes DE 6");
        assertEquals(25_000_00 - 8_694_00, available(VISA));
    }

    @Test
    @DisplayName("International ATM withdrawal in AED posts DE 6 plus markup to the ledger")
    void aedCashWithdrawal() throws Exception {
        // AED 200.00 x 22.87 = INR 4,574.00; markup 3.5% = INR 160.09
        assertEquals("00", code(client.send(factory.internationalCashWithdrawal(VISA, EXPIRY, AED, 200_00, "1234"), TIMEOUT_MS)));
        assertEquals(25_000_00 - 4_574_00 - 160_09, ledger(VISA));
    }

    @Test
    @DisplayName("DCC: INR at a US merchant, no forex markup but a 1% cross-border fee")
    void dccPurchase() throws Exception {
        // DCC rate 84.00 + 4% = 87.36, so USD 100.00 = INR 8,736.00; cross-border fee 1% = INR 87.36
        ISOMsg req = factory.dccPurchase(VISA, EXPIRY, USD, 100_00, null);
        assertEquals("356", req.getString(49), "DCC is billed in the cardholder's currency");
        assertEquals("000000873600", req.getString(4));
        assertEquals("840", req.getString(19), "but the merchant is still abroad");
        assertTrue(req.getString(48).startsWith("DCC840000000010000"));

        assertEquals("00", code(client.send(req, TIMEOUT_MS)));
        assertEquals(25_000_00 - 8_736_00 - 87_36, available(VISA));
    }

    @Test
    @DisplayName("International use turned off: foreign currency and DCC both get 57, domestic is fine")
    void internationalOff() throws Exception {
        assertEquals("57", code(client.send(factory.internationalPurchase(MC_INTL_OFF, EXPIRY, USD, 10_00, null), TIMEOUT_MS)));
        assertEquals("57", code(client.send(factory.dccPurchase(MC_INTL_OFF, EXPIRY, USD, 10_00, null), TIMEOUT_MS)));
        assertEquals("00", code(client.send(factory.purchase(MC_INTL_OFF, EXPIRY, 500_00, null), TIMEOUT_MS)));
    }

    @Test
    @DisplayName("Funds are checked including the markup: AED 21.50 fits without it, not with it")
    void markupCausesInsufficientFunds() throws Exception {
        // AED 21.50 = INR 491.71; plus 3.5% = INR 508.92 > INR 500.00
        assertEquals("51", code(client.send(factory.internationalPurchase(VISA_LOW, EXPIRY, AED, 21_50, null), TIMEOUT_MS)));
        // AED 20.00 = INR 457.40; plus 3.5% = INR 473.41
        assertEquals("00", code(client.send(factory.internationalPurchase(VISA_LOW, EXPIRY, AED, 20_00, null), TIMEOUT_MS)));
    }

    @Test
    @DisplayName("Limit is checked on the INR billing amount: USD 150.00 = INR 12,600.00 gets 61")
    void foreignOverLimit() throws Exception {
        assertEquals("61", code(client.send(factory.internationalPurchase(VISA, EXPIRY, USD, 150_00, null), TIMEOUT_MS)));
    }

    @Test
    @DisplayName("Foreign currency without DE 6 gets 30; an unsupported currency gets 13")
    void foreignFormatErrors() throws Exception {
        ISOMsg noDe6 = factory.internationalPurchase(VISA, EXPIRY, USD, 10_00, null);
        noDe6.unset(6);
        assertEquals("30", code(client.send(noDe6, TIMEOUT_MS)));

        ISOMsg unknown = factory.internationalPurchase(VISA, EXPIRY, USD, 10_00, null);
        unknown.set(49, "999");
        assertEquals("13", code(client.send(unknown, TIMEOUT_MS)));
    }

    @Test
    @DisplayName("Reversals of a foreign purchase release the markup too, in proportion for partials")
    void internationalReversals() throws Exception {
        ISOMsg full = factory.internationalPurchase(VISA, EXPIRY, USD, 100_00, null);
        assertEquals("00", code(client.send(full, TIMEOUT_MS)));
        assertEquals("00", code(client.send(factory.reversal(full, null, "17"), TIMEOUT_MS)));
        assertEquals(25_000_00, available(VISA));

        // Keep USD 60.00: INR 5,040.00 + 3.5% INR 176.40 stays on hold
        ISOMsg partial = factory.internationalPurchase(VISA, EXPIRY, USD, 100_00, null);
        assertEquals("00", code(client.send(partial, TIMEOUT_MS)));
        assertEquals("00", code(client.send(factory.reversal(partial, 60_00L, "17"), TIMEOUT_MS)));
        assertEquals(25_000_00 - 5_040_00 - 176_40, available(VISA));
    }

    @Test
    @DisplayName("Cutover writes the auth log, plus a clearing file and settlement report per scheme")
    void cutoverWritesSchemeFiles() throws Exception {
        ISOMsg visaDomestic = factory.purchase(VISA, EXPIRY, 1_000_00, null);
        assertEquals("00", code(client.send(visaDomestic, TIMEOUT_MS)));
        assertEquals("00", code(client.send(factory.internationalPurchase(VISA, EXPIRY, USD, 100_00, null), TIMEOUT_MS)));
        assertEquals("00", code(client.send(factory.cashWithdrawal(VISA, EXPIRY, 2_000_00, "1234"), TIMEOUT_MS)));
        ISOMsg mcReversed = factory.purchase(MC_INTL_OFF, EXPIRY, 500_00, null);
        assertEquals("00", code(client.send(mcReversed, TIMEOUT_MS)));
        assertEquals("00", code(client.send(factory.reversal(mcReversed, null, "17"), TIMEOUT_MS)));
        assertEquals("00", code(client.send(factory.purchase(RUPAY, EXPIRY, 300_00, null), TIMEOUT_MS)));
        assertEquals("00", code(client.send(factory.purchase(AMEX, EXPIRY, 1_200_00, null), TIMEOUT_MS)));

        assertEquals("00", code(client.send(factory.network("201"), TIMEOUT_MS)));

        String d = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        assertEquals(2, detailRecords("VISA_BASEII_CLEARING_" + d + "_01.txt"), "Visa: two purchases; the 0200 cash is not cleared");
        assertEquals(0, detailRecords("MC_IPM_T112_CLEARING_" + d + "_01.txt"), "Mastercard: the only purchase was reversed");
        assertEquals(1, detailRecords("RUPAY_CLEARING_" + d + "_01.txt"));
        assertEquals(1, detailRecords("AMEX_SUBMISSION_" + d + "_01.txt"));
        assertTrue(read("VISA_BASEII_CLEARING_" + d + "_01.txt").contains("T|DETAIL_RECORDS=2|TOTAL_BILLING_AMOUNT=9400.00"));

        // Purchases 9,400.00 + cash 2,000.00 + ATM fee 19.00 - interchange 1.5% of 9,400.00 (141.00) = 11,278.00
        String visaSettlement = read("VISA_VSS_SETTLEMENT_" + d + "_01.txt");
        assertTrue(visaSettlement.contains("11,278.00"), visaSettlement);
        assertTrue(Files.exists(output.resolve("MC_SETTLEMENT_REPORT_" + d + "_01.txt")));
        assertTrue(Files.exists(output.resolve("RUPAY_NTSL_" + d + "_01.txt")));
        assertTrue(Files.exists(output.resolve("AMEX_SETTLEMENT_" + d + "_01.txt")));

        List<String> authLog = Files.readAllLines(output.resolve("AUTHLOG_" + d + "_01.csv"));
        assertEquals(1 + 1 + 6 + 1, authLog.size(), "header + sign-on + 6 transactions + 1 reversal");

        // After settlement the purchase can no longer be reversed, and the next cutover is file 02 with nothing in it
        assertEquals("12", code(client.send(factory.reversal(visaDomestic, null, "17"), TIMEOUT_MS)));
        assertEquals("00", code(client.send(factory.network("201"), TIMEOUT_MS)));
        assertEquals(0, detailRecords("VISA_BASEII_CLEARING_" + d + "_02.txt"));
    }

    @Test
    @DisplayName("ARN is 23 digits with a valid Luhn check digit")
    void arnFormat() {
        String arn = SchemeFiles.arn(LocalDate.of(2026, 10, 26), 1);
        assertEquals(23, arn.length());
        assertEquals("24000016299" + "00000000001", arn.substring(0, 22));
        int sum = 0;
        for (int i = 0; i < arn.length(); i++) {
            int n = arn.charAt(arn.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                n = n * 2 > 9 ? n * 2 - 9 : n * 2;
            }
            sum += n;
        }
        assertEquals(0, sum % 10);
    }

    @Test
    @DisplayName("Scheme from the PAN's leading digits")
    void schemeFromPan() {
        assertEquals(Scheme.VISA, Scheme.fromPan(VISA));
        assertEquals(Scheme.MASTERCARD, Scheme.fromPan("5555555555554444"));
        assertEquals(Scheme.MASTERCARD, Scheme.fromPan("2221000000000009"));
        assertEquals(Scheme.RUPAY, Scheme.fromPan(RUPAY));
        assertEquals(Scheme.AMEX, Scheme.fromPan(AMEX));
    }

    private long detailRecords(String file) throws Exception {
        return Files.readAllLines(output.resolve(file)).stream().filter(l -> l.startsWith("D|")).count();
    }

    private String read(String file) throws Exception {
        return Files.readString(output.resolve(file));
    }

    private static String code(ISOMsg resp) {
        assertNotNull(resp, "no response from the host");
        return resp.getString(39);
    }

    private long available(String pan) throws Exception {
        return balance(pan, "02");
    }

    private long ledger(String pan) throws Exception {
        return balance(pan, "01");
    }

    /** Balance inquiry with PIN 1234 (all balance checks in this class use the Visa card). */
    private long balance(String pan, String amountType) throws Exception {
        ISOMsg resp = client.send(factory.balanceInquiry(pan, EXPIRY, "1234"), TIMEOUT_MS);
        assertEquals("00", code(resp));
        String de54 = resp.getString(54);
        for (int i = 0; i + 20 <= de54.length(); i += 20) {
            if (de54.substring(i + 2, i + 4).equals(amountType)) {
                return Long.parseLong(de54.substring(i + 8, i + 20));
            }
        }
        throw new AssertionError("DE 54 has no amount type " + amountType + ": " + de54);
    }
}
