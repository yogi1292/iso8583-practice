package practice.iso;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jpos.iso.ISOPackager;
import org.jpos.iso.channel.NACChannel;
import org.jpos.iso.packager.ISO87APackager;

/**
 * Settings shared by the issuer host and the terminal.
 *
 * Messages use the ISO 8583:1987 ASCII layout (jPOS ISO87APackager) and travel
 * over TCP with a 2-byte binary length header in front of each message
 * (jPOS NACChannel, no TPDU). Real networks add their own headers on top.
 */
final class Iso {

    static final ISOPackager PACKAGER = new ISO87APackager();
    static final int DEFAULT_PORT = 8583;

    /** ISO 4217 code for INR, and ISO 3166 numeric code for India. */
    static final String INR = "356";
    static final String INDIA = "356";

    /** Practice fees in basis points (100 bps = 1%). Real values differ by bank and card. */
    static final int FOREX_MARKUP_BPS = 350;      // issuer's markup on foreign currency transactions
    static final int CROSS_BORDER_FEE_BPS = 100;  // issuer's fee on INR transactions at a foreign merchant (DCC)
    static final int DCC_MARGIN_BPS = 400;        // merchant / DCC provider margin built into the DCC rate

    /**
     * A foreign currency with a practice exchange rate to INR. rate4 is the
     * rate times 10,000, so 84.0000 is 840000. All five currencies use 2 decimals.
     */
    record Currency(String code, String alpha, long rate4, String country, String city, String countryAlpha) {
    }

    private static final Map<String, Currency> CURRENCIES = new LinkedHashMap<>();

    static {
        add(new Currency("840", "USD", 84_0000, "840", "NEW YORK", "US"));
        add(new Currency("978", "EUR", 91_5000, "276", "BERLIN", "DE"));
        add(new Currency("826", "GBP", 108_2000, "826", "LONDON", "GB"));
        add(new Currency("784", "AED", 22_8700, "784", "DUBAI", "AE"));
        add(new Currency("702", "SGD", 63_1000, "702", "SINGAPORE", "SG"));
    }

    private static void add(Currency c) {
        CURRENCIES.put(c.code(), c);
    }

    private static final Map<String, String> RESPONSE_CODES = Map.ofEntries(
            Map.entry("00", "Approved"),
            Map.entry("05", "Do not honour"),
            Map.entry("12", "Invalid transaction"),
            Map.entry("13", "Invalid amount"),
            Map.entry("14", "Invalid card number"),
            Map.entry("25", "Unable to locate original transaction"),
            Map.entry("30", "Format error"),
            Map.entry("41", "Lost card"),
            Map.entry("43", "Stolen card"),
            Map.entry("51", "Insufficient funds"),
            Map.entry("54", "Expired card"),
            Map.entry("55", "Incorrect PIN"),
            Map.entry("57", "Transaction not permitted to cardholder"),
            Map.entry("61", "Exceeds amount limit"),
            Map.entry("62", "Restricted card"),
            Map.entry("68", "Response received too late"),
            Map.entry("75", "PIN tries exceeded"),
            Map.entry("91", "Issuer or switch inoperative"),
            Map.entry("94", "Duplicate transmission"),
            Map.entry("96", "System malfunction"));

    private Iso() {
    }

    static NACChannel clientChannel(String host, int port) {
        return new NACChannel(host, port, PACKAGER, null);
    }

    static NACChannel serverChannel() throws IOException {
        return new NACChannel(PACKAGER, null);
    }

    /** The MTI a reply to this request carries: 0100 -> 0110, 0420 -> 0430. */
    static String responseMti(String mti) {
        return mti.substring(0, 2) + (char) (mti.charAt(2) + 1) + mti.charAt(3);
    }

    static String describeCode(String code) {
        return RESPONSE_CODES.getOrDefault(code, "Unknown code");
    }

    /** Formats an amount held in minor units (paise, cents) with 2 decimals, e.g. 250000 -> "2,500.00". */
    static String inr(long minor) {
        return String.format("%,d.%02d", minor / 100, Math.abs(minor % 100));
    }

    static String maskPan(String pan) {
        return pan.length() > 10 ? pan.substring(0, 6) + "*".repeat(pan.length() - 10) + pan.substring(pan.length() - 4) : pan;
    }

    static List<Currency> currencies() {
        return List.copyOf(CURRENCIES.values());
    }

    /** Looks a currency up by ISO numeric code (840) or alpha code (USD). Null if unknown. */
    static Currency currency(String codeOrAlpha) {
        Currency byCode = CURRENCIES.get(codeOrAlpha);
        if (byCode != null) {
            return byCode;
        }
        return CURRENCIES.values().stream().filter(c -> c.alpha().equalsIgnoreCase(codeOrAlpha)).findFirst().orElse(null);
    }

    /** Converts a foreign amount in minor units to paise at rate4 (rate x 10,000), rounding half up. */
    static long toInr(long foreignMinor, long rate4) {
        return BigDecimal.valueOf(foreignMinor).multiply(BigDecimal.valueOf(rate4))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact();
    }

    /** amount x bps / 10,000, rounding half up. */
    static long percent(long amount, int bps) {
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(bps))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact();
    }

    /** The DCC rate: the network rate plus the DCC margin. */
    static long dccRate4(Currency c) {
        return c.rate4() + percent(c.rate4(), DCC_MARGIN_BPS);
    }

    /**
     * DE 10 (conversion rate, cardholder billing), 8 digits: the first digit is
     * the number of decimal places, the other 7 are the rate. 84.0000 -> 40840000.
     */
    static String rateField(long rate4) {
        return "4" + String.format("%07d", rate4);
    }

    /** Reads DE 10 back as a plain number, e.g. 40840000 -> "84.0000". */
    static String rateText(String de10) {
        int decimals = Character.digit(de10.charAt(0), 10);
        return new BigDecimal(de10.substring(1)).movePointLeft(decimals).toPlainString();
    }
}
