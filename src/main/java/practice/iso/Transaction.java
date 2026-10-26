package practice.iso;

import java.time.LocalDateTime;

import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;

/** An approved purchase or withdrawal, kept for reversals and for the end-of-day files. */
final class Transaction {

    final String mti;
    final String stan;
    final String transmissionDateTime; // DE 7
    final String rrn;
    final String authCode;
    final String pan;
    final String mcc;
    final String merchant;
    final String acquirerCountry;      // DE 19, or India when absent
    final String txnCurrency;          // DE 49
    final String conversionRate;       // DE 10, empty for INR
    final boolean dcc;
    final boolean posted;              // true for 0200 (money moved), false for 0100 (hold only)
    final Scheme scheme;
    final LocalDateTime approvedAt = LocalDateTime.now();

    /** Amount in the transaction currency (DE 4), reduced by partial reversals. */
    long txnAmount;
    /** Amount in INR the cardholder is billed before issuer fees (DE 6, or DE 4 when the currency is INR). */
    long billed;
    /** Issuer fee in INR: forex markup or cross-border fee. */
    long fee;
    boolean reversed;
    /** True once the end-of-day cutover has put this transaction into the scheme files. */
    boolean settled;

    Transaction(ISOMsg req, String authCode, boolean posted, long billed, long fee) throws ISOException {
        this.mti = req.getMTI();
        this.stan = req.getString(11);
        this.transmissionDateTime = req.getString(7);
        this.rrn = req.hasField(37) ? req.getString(37) : "";
        this.authCode = authCode;
        this.pan = req.getString(2);
        this.mcc = req.hasField(18) ? req.getString(18) : "";
        this.merchant = req.hasField(43) ? req.getString(43).substring(0, Math.min(25, req.getString(43).length())).trim() : "";
        this.acquirerCountry = req.hasField(19) ? req.getString(19) : Iso.INDIA;
        this.txnCurrency = req.hasField(49) ? req.getString(49) : Iso.INR;
        this.conversionRate = req.hasField(10) ? req.getString(10) : "";
        this.dcc = req.hasField(48) && req.getString(48).startsWith("DCC");
        this.posted = posted;
        this.scheme = Scheme.fromPan(pan);
        this.txnAmount = Long.parseLong(req.getString(4));
        this.billed = billed;
        this.fee = fee;
    }

    long total() {
        return billed + fee;
    }
}
