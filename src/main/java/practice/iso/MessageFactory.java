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
     * 0100 purchase at a foreign merchant in the merchant's currency.
     * DE 4 and DE 49 are in that currency; the network converts and adds
     * DE 6 (cardholder billing amount, INR), DE 10 (conversion rate) and
     * DE 51 (billing currency). Here the terminal fills those in, playing
     * the network's part.
     */
    ISOMsg internationalPurchase(String pan, String expiry, Iso.Currency ccy, long foreignAmount, String pin)
            throws ISOException {
        return foreignCurrency(cardTransaction("0100", "000000", "5411", pan, expiry, foreignAmount, pin), ccy, foreignAmount);
    }

    /** 0200 ATM cash withdrawal abroad, in the local currency. */
    ISOMsg internationalCashWithdrawal(String pan, String expiry, Iso.Currency ccy, long foreignAmount, String pin)
            throws ISOException {
        return foreignCurrency(cardTransaction("0200", "010000", "6011", pan, expiry, foreignAmount, pin), ccy, foreignAmount);
    }

    /**
     * 0100 DCC purchase: the cardholder is abroad but chose to pay in INR at
     * the terminal. The merchant's DCC provider converted at its own rate, so
     * DE 4 is in INR and DE 49 = 356, while DE 19 and DE 43 still show a
     * foreign merchant.
     *
     * DE 48 carries the DCC details in this practice layout:
     * "DCC" + original currency (3) + original amount (12) + DCC rate (8, DE 10 format) + margin in bps (4).
     * Real networks carry DCC indicators in their own network-specific fields.
     */
    ISOMsg dccPurchase(String pan, String expiry, Iso.Currency ccy, long foreignAmount, String pin) throws ISOException {
        long dccRate = Iso.dccRate4(ccy);
        ISOMsg m = cardTransaction("0100", "000000", "5411", pan, expiry, Iso.toInr(foreignAmount, dccRate), pin);
        abroad(m, ccy);
        m.set(48, "DCC" + ccy.code() + String.format("%012d", foreignAmount) + Iso.rateField(dccRate)
                + String.format("%04d", Iso.DCC_MARGIN_BPS));
        return m;
    }

    private static ISOMsg foreignCurrency(ISOMsg m, Iso.Currency ccy, long foreignAmount) throws ISOException {
        abroad(m, ccy);
        m.set(49, ccy.code());
        m.set(6, String.format("%012d", Iso.toInr(foreignAmount, ccy.rate4())));
        m.set(10, Iso.rateField(ccy.rate4()));
        m.set(51, Iso.INR);
        return m;
    }

    /** DE 19 acquiring institution country and DE 43 merchant location for a merchant abroad. */
    private static void abroad(ISOMsg m, Iso.Currency ccy) throws ISOException {
        m.set(19, ccy.country());
        m.set(43, String.format("%-25s%-13s%2s", "PRACTICE STORE", ccy.city(), ccy.countryAlpha()));
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
        for (int field : new int[] {2, 3, 4, 6, 10, 12, 13, 18, 19, 22, 25, 32, 37, 41, 42, 43, 48, 49, 51}) {
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
        m.set(49, Iso.INR);
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
