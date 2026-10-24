package practice.iso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.jpos.iso.ISOMsg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end scenarios over a real TCP connection: a fresh issuer host on a
 * free port for every test, and a terminal that signs on first.
 */
class IssuerScenariosTest {

    private static final String VISA = "4111111111111111";       // PIN 1234, INR 25,000.00, limit 10,000.00
    private static final String VISA_LOW = "4000000000000002";   // PIN 4321, INR 500.00
    private static final String MC_EXPIRED = "5555555555554444"; // expiry 2401
    private static final String MC_BLOCKED = "5105105105105100";
    private static final String RUPAY_LOST = "6521000000000017";
    private static final String RUPAY_STOLEN = "6521000000000025";
    private static final String EXPIRY = "2812";
    private static final long TIMEOUT_MS = 2_000;

    private IssuerHost host;
    private int port;
    private TerminalClient client;
    private final MessageFactory factory = new MessageFactory();

    @BeforeEach
    void startHostAndSignOn() throws Exception {
        host = new IssuerHost(false);
        host.engine().setSlowResponse(777_00, 1_500);
        port = host.start(0);
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
    @DisplayName("0800 echo test is answered with 0810 and 00")
    void echoTest() throws Exception {
        ISOMsg resp = client.send(factory.network("301"), TIMEOUT_MS);
        assertEquals("0810", resp.getMTI());
        assertEquals("00", code(resp));
    }

    @Test
    @DisplayName("A terminal that has not signed on gets 91")
    void notSignedOn() throws Exception {
        try (TerminalClient other = new TerminalClient("localhost", port, false)) {
            other.connect();
            assertEquals("91", code(other.send(factory.purchase(VISA, EXPIRY, 100_00, null), TIMEOUT_MS)));
        }
    }

    @Test
    @DisplayName("Approved purchase places a hold: available drops, ledger does not")
    void purchaseApprovedPlacesHold() throws Exception {
        ISOMsg resp = client.send(factory.purchase(VISA, EXPIRY, 1_500_00, "1234"), TIMEOUT_MS);
        assertEquals("00", code(resp));
        assertEquals(6, resp.getString(38).length(), "DE 38 auth code");
        assertEquals(25_000_00, balance(VISA, "1234", "01"));
        assertEquals(23_500_00, balance(VISA, "1234", "02"));
    }

    @Test
    @DisplayName("Cash withdrawal posts at once: ledger and available both drop")
    void cashWithdrawalPosts() throws Exception {
        assertEquals("00", code(client.send(factory.cashWithdrawal(VISA, EXPIRY, 2_000_00, "1234"), TIMEOUT_MS)));
        assertEquals(23_000_00, balance(VISA, "1234", "01"));
        assertEquals(23_000_00, balance(VISA, "1234", "02"));
    }

    @Test
    @DisplayName("Declines: 51, 54, 62, 41, 43, 14, 61")
    void declines() throws Exception {
        assertEquals("51", code(client.send(factory.purchase(VISA_LOW, EXPIRY, 1_000_00, null), TIMEOUT_MS)));
        assertEquals("54", code(client.send(factory.purchase(MC_EXPIRED, "2401", 100_00, null), TIMEOUT_MS)));
        assertEquals("54", code(client.send(factory.purchase(VISA, "2711", 100_00, null), TIMEOUT_MS)));
        assertEquals("62", code(client.send(factory.purchase(MC_BLOCKED, EXPIRY, 100_00, null), TIMEOUT_MS)));
        assertEquals("41", code(client.send(factory.purchase(RUPAY_LOST, EXPIRY, 100_00, null), TIMEOUT_MS)));
        assertEquals("43", code(client.send(factory.purchase(RUPAY_STOLEN, EXPIRY, 100_00, null), TIMEOUT_MS)));
        assertEquals("14", code(client.send(factory.purchase("4999999999999999", EXPIRY, 100_00, null), TIMEOUT_MS)));
        assertEquals("61", code(client.send(factory.purchase(VISA, EXPIRY, 15_000_00, null), TIMEOUT_MS)));
    }

    @Test
    @DisplayName("Wrong PIN gives 55 twice, then 75, and the correct PIN is refused after that")
    void pinTries() throws Exception {
        assertEquals("55", code(client.send(factory.cashWithdrawal(VISA, EXPIRY, 100_00, "9999"), TIMEOUT_MS)));
        assertEquals("55", code(client.send(factory.cashWithdrawal(VISA, EXPIRY, 100_00, "9999"), TIMEOUT_MS)));
        assertEquals("75", code(client.send(factory.cashWithdrawal(VISA, EXPIRY, 100_00, "9999"), TIMEOUT_MS)));
        assertEquals("75", code(client.send(factory.cashWithdrawal(VISA, EXPIRY, 100_00, "1234"), TIMEOUT_MS)));
    }

    @Test
    @DisplayName("Cash withdrawal without a PIN block gets 55")
    void cashWithoutPin() throws Exception {
        assertEquals("55", code(client.send(factory.cashWithdrawal(VISA, EXPIRY, 100_00, null), TIMEOUT_MS)));
    }

    @Test
    @DisplayName("Same terminal, STAN and DE 7 sent twice: the second gets 94")
    void duplicate() throws Exception {
        ISOMsg req = factory.purchase(VISA, EXPIRY, 100_00, null);
        assertEquals("00", code(client.send(req, TIMEOUT_MS)));
        assertEquals("94", code(client.send(req, TIMEOUT_MS)));
        assertEquals(24_900_00, balance(VISA, "1234", "02"), "the duplicate must not place a second hold");
    }

    @Test
    @DisplayName("Missing DE 4 gives 30; unsupported processing code gives 12")
    void formatAndProcessingCode() throws Exception {
        ISOMsg noAmount = factory.purchase(VISA, EXPIRY, 100_00, null);
        noAmount.unset(4);
        assertEquals("30", code(client.send(noAmount, TIMEOUT_MS)));

        ISOMsg refund = factory.purchase(VISA, EXPIRY, 100_00, null);
        refund.set(3, "200000");
        assertEquals("12", code(client.send(refund, TIMEOUT_MS)));
    }

    @Test
    @DisplayName("Full reversal releases the hold; a second reversal changes nothing")
    void fullReversal() throws Exception {
        ISOMsg purchase = factory.purchase(VISA, EXPIRY, 1_000_00, null);
        assertEquals("00", code(client.send(purchase, TIMEOUT_MS)));
        ISOMsg rev = client.send(factory.reversal(purchase, null, "17"), TIMEOUT_MS);
        assertEquals("0430", rev.getMTI());
        assertEquals("00", code(rev));
        assertEquals(25_000_00, balance(VISA, "1234", "02"));

        assertEquals("00", code(client.send(factory.reversal(purchase, null, "17"), TIMEOUT_MS)));
        assertEquals(25_000_00, balance(VISA, "1234", "02"));
    }

    @Test
    @DisplayName("Partial reversal (DE 95) keeps only the replacement amount on hold")
    void partialReversal() throws Exception {
        ISOMsg purchase = factory.purchase(VISA, EXPIRY, 1_000_00, null);
        assertEquals("00", code(client.send(purchase, TIMEOUT_MS)));
        assertEquals("00", code(client.send(factory.reversal(purchase, 600_00L, "17"), TIMEOUT_MS)));
        assertEquals(24_400_00, balance(VISA, "1234", "02"));
    }

    @Test
    @DisplayName("Reversal of a cash withdrawal credits the money back")
    void reverseCashWithdrawal() throws Exception {
        ISOMsg cash = factory.cashWithdrawal(VISA, EXPIRY, 2_000_00, "1234");
        assertEquals("00", code(client.send(cash, TIMEOUT_MS)));
        assertEquals("00", code(client.send(factory.reversal(cash, null, "17"), TIMEOUT_MS)));
        assertEquals(25_000_00, balance(VISA, "1234", "01"));
    }

    @Test
    @DisplayName("Reversal with no matching original gets 25")
    void reversalWithoutOriginal() throws Exception {
        ISOMsg ghost = factory.purchase(VISA, EXPIRY, 100_00, null);
        ghost.set(11, "999999");
        assertEquals("25", code(client.send(factory.reversal(ghost, null, "17"), TIMEOUT_MS)));
    }

    @Test
    @DisplayName("Timeout: no response in time, terminal reverses, late 0110 is discarded, hold is released")
    void timeoutThenReversal() throws Exception {
        ISOMsg slow = factory.purchase(VISA, EXPIRY, 777_00, null);
        assertNull(client.send(slow, 500), "the host answers 777.00 late");
        ISOMsg rev = client.send(factory.reversal(slow, null, "68"), 4_000);
        assertEquals("0430", rev.getMTI());
        assertEquals("00", code(rev));
        assertEquals(25_000_00, balance(VISA, "1234", "02"));
    }

    @Test
    @DisplayName("PIN block format 0 round trip")
    void pinBlock() {
        byte[] block = PinBlock.encode("1234", VISA);
        assertEquals("041234FFFFFFFFFF", org.jpos.iso.ISOUtil.hexString(PinBlock.encode("1234", "0000000000000000")));
        assertEquals("1234", PinBlock.decode(block, VISA));
        assertNotEquals("1234", PinBlock.decode(block, VISA_LOW), "the block is tied to the PAN it was built with");
    }

    private static String code(ISOMsg resp) {
        assertNotNull(resp, "no response from the host");
        return resp.getString(39);
    }

    /** Reads one balance from DE 54 of a balance inquiry: "01" ledger, "02" available. */
    private long balance(String pan, String pin, String amountType) throws Exception {
        ISOMsg resp = client.send(factory.balanceInquiry(pan, EXPIRY, pin), TIMEOUT_MS);
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
