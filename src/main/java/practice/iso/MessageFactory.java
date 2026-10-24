package practice.iso;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;

/** Builds the requests a terminal or acquirer sends. Each message gets the next STAN (DE 11). */
final class MessageFactory {

    static final String ACQUIRER_ID = "400001";
    static final String TERMINAL_ID = "TERM0001";
    static final String MERCHANT_ID = "MERCHANT0000001";
    static final String MERCHANT_NAME_LOCATION = String.format("%-25s%-13s%2s", "PRACTICE MART", "MUMBAI", "IN");
    static final String CURRENCY_INR = "356";

    private static final DateTimeFormatter TRANSMISSION = DateTimeFormatter.ofPattern("MMddHHmmss");
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter LOCAL_DATE = DateTimeFormatter.ofPattern("MMdd");

    private int stan;

    /** 0800 network management. DE 70: 001 sign-on, 002 sign-off, 301 echo test. */
    ISOMsg network(String code) throws ISOException {
        ISOMsg m = base("0800");
        m.set(70, code);
        return m;
    }

    /** 0100 purchase authorization (dual message: places a hold, money moves at clearing). */
    ISOMsg purchase(String pan, String expiry, long amount, String pin) throws ISOException {
        return cardTransaction("0100", "000000", "5411", pan, expiry, amount, pin);
    }

    /** 0200 ATM cash withdrawal (single message: authorizes and posts in one go). */
    ISOMsg cashWithdrawal(String pan, String expiry, long amount, String pin) throws ISOException {
        return cardTransaction("0200", "010000", "6011", pan, expiry, amount, pin);
    }

    /** 0200 balance inquiry. The balances come back in DE 54. */
    ISOMsg balanceInquiry(String pan, String expiry, String pin) throws ISOException {
        return cardTransaction("0200", "310000", "6011", pan, expiry, 0, pin);
    }

    /**
     * 0420 reversal advice for an earlier request.
     *
     * @param keepAmount null for a full reversal, or the amount that should stay
     *                   charged (sent in DE 95) for a partial reversal
     * @param reason     sent in DE 39, e.g. 68 (response too late) or 17 (customer cancelled)
     */
    ISOMsg reversal(ISOMsg original, Long keepAmount, String reason) throws ISOException {
        ISOMsg m = base("0420");
        for (int field : new int[] {2, 3, 4, 12, 13, 18, 22, 25, 32, 37, 41, 42, 43, 49}) {
            if (original.hasField(field)) {
                m.set(field, original.getString(field));
            }
        }
        m.set(39, reason);
        m.set(90, original.getMTI() + original.getString(11) + original.getString(7)
                + String.format("%011d", Long.parseLong(original.getString(32))) + "00000000000");
        if (keepAmount != null) {
            m.set(95, String.format("%012d%012dC%08dC%08d", keepAmount, keepAmount, 0, 0));
        }
        return m;
    }

    private ISOMsg cardTransaction(String mti, String processingCode, String mcc, String pan, String expiry,
            long amount, String pin) throws ISOException {
        ISOMsg m = base(mti);
        LocalDateTime local = LocalDateTime.now();
        m.set(2, pan);
        m.set(3, processingCode);
        m.set(4, String.format("%012d", amount));
        m.set(12, local.format(LOCAL_TIME));
        m.set(13, local.format(LOCAL_DATE));
        if (expiry != null && !expiry.isEmpty()) {
            m.set(14, expiry);
        }
        m.set(18, mcc);
        m.set(22, pin != null ? "051" : "071"); // 05x chip, 07x contactless; third digit 1 = terminal can take a PIN
        m.set(25, "00");
        m.set(32, ACQUIRER_ID);
        m.set(37, rrn(local, m.getString(11)));
        m.set(41, TERMINAL_ID);
        m.set(42, MERCHANT_ID);
        m.set(43, MERCHANT_NAME_LOCATION);
        m.set(49, CURRENCY_INR);
        if (pin != null) {
            m.set(52, PinBlock.encode(pin, pan));
        }
        return m;
    }

    private ISOMsg base(String mti) throws ISOException {
        ISOMsg m = new ISOMsg(mti);
        m.setPackager(Iso.PACKAGER);
        m.set(7, ZonedDateTime.now(ZoneOffset.UTC).format(TRANSMISSION)); // DE 7 is always GMT
        m.set(11, nextStan());
        return m;
    }

    private synchronized String nextStan() {
        stan = stan % 999_999 + 1;
        return String.format("%06d", stan);
    }

    /** RRN (DE 37), 12 characters: last digit of the year + day of year (3) + hour (2) + STAN (6). */
    private static String rrn(LocalDateTime local, String stan) {
        return String.format("%d%03d%02d%s", local.getYear() % 10, local.getDayOfYear(), local.getHour(), stan);
    }
}
