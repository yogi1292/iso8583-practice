package practice.iso;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;

/**
 * The issuer's decision logic: takes a request, returns the response and a
 * one-line reason. It knows nothing about sockets, so it is easy to read.
 *
 * Checks run in the order a real issuer host typically runs them: message
 * format, duplicates, card lookup, card status, expiry, processing code, PIN,
 * international use, then amount, limit and funds.
 */
final class AuthEngine {

    /** What the host should send back, why, and how long to wait before sending it. */
    record Outcome(ISOMsg response, String reason, long delayMs) {
    }

    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyMM");

    private final CardStore cards;
    private final Path outputDir;
    /** Approved transactions keyed by original MTI + STAN + DE 7, the same values DE 90 carries. */
    private final Map<String, Transaction> approved = new LinkedHashMap<>();
    /** Terminal + STAN + DE 7 of every financial request seen, for duplicate detection. */
    private final Set<String> seen = new HashSet<>();
    /** Every request and decision since the last cutover; written to the AUTHLOG file. */
    private final List<SchemeFiles.LogRow> log = new ArrayList<>();
    private final Map<LocalDate, Integer> cutoversByDate = new HashMap<>();
    private final Random random = new Random();

    /** Billing amount and fee of the request being handled, for the log. */
    private long currentBilled;
    private long currentFee;

    /** A purchase or withdrawal of exactly this amount is answered late, to simulate a timeout. */
    private long slowAmount = 777_00;
    private long slowDelayMs = 5_000;

    AuthEngine(CardStore cards, Path outputDir) {
        this.cards = cards;
        this.outputDir = outputDir;
    }

    CardStore cards() {
        return cards;
    }

    Path outputDir() {
        return outputDir;
    }

    synchronized void setSlowResponse(long amount, long delayMs) {
        this.slowAmount = amount;
        this.slowDelayMs = delayMs;
    }

    /** Returns null for an MTI this host does not support; the host then sends nothing back. */
    synchronized Outcome handle(ISOMsg req) throws ISOException {
        currentBilled = 0;
        currentFee = 0;
        Outcome out = dispatch(req);
        if (out != null) {
            log.add(SchemeFiles.LogRow.of(req, out, currentBilled, currentFee));
        }
        return out;
    }

    synchronized Outcome reject(ISOMsg req, String code, String reason) throws ISOException {
        Outcome out = decline(req, code, reason);
        log.add(SchemeFiles.LogRow.of(req, out, 0, 0));
        return out;
    }

    private Outcome dispatch(ISOMsg req) throws ISOException {
        switch (req.getMTI()) {
            case "0800":
                return networkManagement(req);
            case "0100":
                return financial(req, false);
            case "0200":
                return financial(req, true);
            case "0400":
            case "0420":
                return reversal(req);
            default:
                return null;
        }
    }

    private Outcome networkManagement(ISOMsg req) throws ISOException {
        String code = req.hasField(70) ? req.getString(70) : "";
        switch (code) {
            case "001":
                return new Outcome(respond(req, "00"), "sign-on", 0);
            case "002":
                return new Outcome(respond(req, "00"), "sign-off", 0);
            case "301":
                return new Outcome(respond(req, "00"), "echo test", 0);
            case "201":
                return cutover(req);
            default:
                return decline(req, "12", "unknown network management code DE 70 = " + code);
        }
    }

    private Outcome financial(ISOMsg req, boolean posts) throws ISOException {
        for (int field : new int[] {2, 3, 4, 7, 11, 41}) {
            if (!req.hasField(field)) {
                return decline(req, "30", "format error: DE " + field + " is missing");
            }
        }
        String dupKey = req.getString(41) + "|" + req.getString(11) + "|" + req.getString(7);
        if (!seen.add(dupKey)) {
            return decline(req, "94", "duplicate: this terminal already sent STAN " + req.getString(11) + " with the same DE 7");
        }

        Card card = cards.find(req.getString(2));
        if (card == null) {
            return decline(req, "14", "no card with this PAN");
        }
        switch (card.status) {
            case LOST:
                return decline(req, "41", "card is reported lost");
            case STOLEN:
                return decline(req, "43", "card is reported stolen");
            case BLOCKED:
                return decline(req, "62", "card is blocked");
            default:
                break;
        }
        if (card.expiry.compareTo(LocalDate.now().format(YYMM)) < 0) {
            return decline(req, "54", "card expired at the end of " + card.expiry + " (YYMM)");
        }
        if (req.hasField(14) && !req.getString(14).equals(card.expiry)) {
            return decline(req, "54", "expiry in DE 14 (" + req.getString(14) + ") does not match the card");
        }

        String type = req.getString(3).substring(0, 2);
        if (!type.equals("00") && !type.equals("01") && !type.equals("31")) {
            return decline(req, "12", "processing code " + type + " is not supported by this host");
        }

        if (card.wrongPinTries >= 3) {
            return decline(req, "75", "PIN is locked after 3 wrong tries");
        }
        if (req.hasField(52)) {
            String pin = PinBlock.decode(req.getBytes(52), card.pan);
            if (!card.pin.equals(pin)) {
                card.wrongPinTries++;
                if (card.wrongPinTries >= 3) {
                    return decline(req, "75", "wrong PIN, 3rd try: PIN is now locked");
                }
                return decline(req, "55", "wrong PIN (try " + card.wrongPinTries + " of 3)");
            }
            card.wrongPinTries = 0;
        } else if (!type.equals("00")) {
            return decline(req, "55", "this transaction needs a PIN but DE 52 is missing");
        }

        // International: a foreign currency in DE 49, or a merchant outside India in DE 19.
        // A DCC transaction is in INR but still cross-border, so it counts too.
        String currency = req.hasField(49) ? req.getString(49) : Iso.INR;
        boolean foreignCurrency = !Iso.INR.equals(currency);
        boolean crossBorder = req.hasField(19) && !Iso.INDIA.equals(req.getString(19));
        boolean dcc = req.hasField(48) && req.getString(48).startsWith("DCC");
        if ((foreignCurrency || crossBorder) && !card.internationalEnabled) {
            return decline(req, "57", "international use is turned off for this card");
        }

        if (type.equals("31")) {
            ISOMsg resp = respond(req, "00");
            resp.set(38, authCode());
            resp.set(54, balances(card));
            return new Outcome(resp, "balance inquiry: available INR " + Iso.inr(card.available()), 0);
        }

        long amount = Long.parseLong(req.getString(4));
        if (amount <= 0) {
            return decline(req, "13", "amount must be more than zero");
        }

        long billed;
        long fee;
        String pricing;
        if (foreignCurrency) {
            Iso.Currency ccy = Iso.currency(currency);
            if (ccy == null) {
                return decline(req, "13", "currency " + currency + " in DE 49 is not supported");
            }
            if (!req.hasField(6) || !req.hasField(10)) {
                return decline(req, "30", "format error: a foreign currency transaction needs DE 6 (billing amount) and DE 10 (conversion rate)");
            }
            billed = Long.parseLong(req.getString(6));
            fee = Iso.percent(billed, Iso.FOREX_MARKUP_BPS);
            pricing = ccy.alpha() + " " + Iso.inr(amount) + " x " + Iso.rateText(req.getString(10)) + " = INR " + Iso.inr(billed)
                    + ", plus forex markup INR " + Iso.inr(fee) + " (" + bpsText(Iso.FOREX_MARKUP_BPS) + ")";
        } else {
            billed = amount;
            fee = crossBorder ? Iso.percent(billed, Iso.CROSS_BORDER_FEE_BPS) : 0;
            pricing = "INR " + Iso.inr(billed) + (crossBorder ? ", plus cross-border fee INR " + Iso.inr(fee) + " ("
                    + bpsText(Iso.CROSS_BORDER_FEE_BPS) + ", INR transaction at a foreign merchant" + (dcc ? ", DCC" : "") + ")" : "");
        }
        long total = billed + fee;
        currentBilled = billed;
        currentFee = fee;

        if (billed > card.perTxnLimit) {
            return decline(req, "61", "INR " + Iso.inr(billed) + " is over the per-transaction limit of INR " + Iso.inr(card.perTxnLimit));
        }
        if (total > card.available()) {
            return decline(req, "51", "insufficient funds: needs INR " + Iso.inr(total) + " [" + pricing + "], available INR "
                    + Iso.inr(card.available()));
        }

        if (posts) {
            card.ledger -= total;
        } else {
            card.holds += total;
        }
        String authCode = authCode();
        approved.put(req.getMTI() + req.getString(11) + req.getString(7), new Transaction(req, authCode, posts, billed, fee));

        ISOMsg resp = respond(req, "00");
        resp.set(38, authCode);
        resp.set(54, balances(card));
        String reason = (posts ? "approved, ledger debited" : "approved, hold placed") + " INR " + Iso.inr(total)
                + " [" + pricing + "]; available now INR " + Iso.inr(card.available());
        long delay = amount == slowAmount ? slowDelayMs : 0;
        if (delay > 0) {
            reason += "; replying " + delay + " ms late (timeout test)";
        }
        return new Outcome(resp, reason, delay);
    }

    /**
     * DE 90 (original data elements) = original MTI (4) + original STAN (6) +
     * original DE 7 (10) + acquirer ID (11) + forwarding ID (11).
     * DE 95 (replacement amounts), when present, holds the amount in the
     * transaction currency that should stay charged; everything above it is
     * released, including the same share of the issuer's fee.
     */
    private Outcome reversal(ISOMsg req) throws ISOException {
        if (!req.hasField(90) || req.getString(90).length() < 20) {
            return decline(req, "30", "format error: DE 90 (original data elements) is missing or short");
        }
        String de90 = req.getString(90);
        Transaction txn = approved.get(de90.substring(0, 20));
        if (txn == null) {
            return decline(req, "25", "no approved original matches DE 90 (MTI " + de90.substring(0, 4)
                    + ", STAN " + de90.substring(4, 10) + ", DE 7 " + de90.substring(10, 20) + ")");
        }
        if (txn.reversed) {
            return new Outcome(respond(req, "00"), "original is already reversed, nothing changed (duplicate reversal)", 0);
        }
        if (txn.settled) {
            return decline(req, "12", "original was settled at the end-of-day cutover; a reversal is no longer possible, use a refund or a chargeback");
        }
        long keep = req.hasField(95) ? Long.parseLong(req.getString(95).substring(0, 12)) : 0;
        if (keep > txn.txnAmount) {
            return decline(req, "13", "replacement amount in DE 95 is more than the original amount");
        }

        Card card = cards.find(txn.pan);
        long before = txn.total();
        long newBilled = scale(txn.billed, keep, txn.txnAmount);
        long newFee = scale(txn.fee, keep, txn.txnAmount);
        long release = before - (newBilled + newFee);
        if (txn.posted) {
            card.ledger += release;
        } else {
            card.holds -= release;
        }
        txn.txnAmount = keep;
        txn.billed = newBilled;
        txn.fee = newFee;
        txn.reversed = keep == 0;
        String reason = (keep == 0 ? "full reversal" : "partial reversal, " + Iso.inr(keep) + " in the transaction currency stays charged")
                + "; " + (txn.posted ? "credited back" : "hold released") + " INR " + Iso.inr(release)
                + "; available now INR " + Iso.inr(card.available());
        return new Outcome(respond(req, "00"), reason, 0);
    }

    /**
     * End-of-day cutover (0800, DE 70 = 201): closes the business day and
     * writes the authorization log plus a clearing file and a settlement
     * report for each scheme. Transactions in those files are settled, so
     * they can no longer be reversed.
     */
    private Outcome cutover(ISOMsg req) throws ISOException {
        LocalDate date = LocalDate.now();
        int sequence = cutoversByDate.merge(date, 1, Integer::sum);
        List<Transaction> open = new ArrayList<>();
        for (Transaction t : approved.values()) {
            if (!t.settled && !t.reversed && t.txnAmount > 0) {
                open.add(t);
            }
        }
        List<Path> files;
        try {
            files = SchemeFiles.write(outputDir, date, sequence, open, log);
        } catch (IOException e) {
            return decline(req, "96", "could not write the end-of-day files: " + e.getMessage());
        }
        open.forEach(t -> t.settled = true);
        log.clear();
        StringBuilder reason = new StringBuilder("end-of-day cutover: business date " + date + " closed, "
                + open.size() + " transactions settled, files written to " + outputDir.toAbsolutePath() + ":");
        files.forEach(f -> reason.append("\n        ").append(f.getFileName()));
        return new Outcome(respond(req, "00"), reason.toString(), 0);
    }

    private Outcome decline(ISOMsg req, String code, String reason) throws ISOException {
        return new Outcome(respond(req, code), reason, 0);
    }

    /** Builds the response from the request: response MTI, sensitive fields removed, DE 39 set. */
    private static ISOMsg respond(ISOMsg req, String code) throws ISOException {
        ISOMsg resp = (ISOMsg) req.clone();
        resp.setResponseMTI();
        resp.unset(new int[] {14, 35, 52, 55});
        resp.set(39, code);
        return resp;
    }

    /**
     * DE 54 additional amounts, one 20-character entry per balance:
     * account type (2) + amount type (2: 01 ledger, 02 available) + currency (3) + C/D + amount (12).
     */
    private static String balances(Card card) {
        return entry("01", card.ledger) + entry("02", card.available());
    }

    private static String entry(String amountType, long amount) {
        return String.format("00%s%s%s%012d", amountType, Iso.INR, amount < 0 ? "D" : "C", Math.abs(amount));
    }

    /** value x part / whole, rounding half up. */
    private static long scale(long value, long part, long whole) {
        if (whole == 0) {
            return 0;
        }
        return BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(part))
                .divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_UP).longValueExact();
    }

    private static String bpsText(int bps) {
        return BigDecimal.valueOf(bps).movePointLeft(2).stripTrailingZeros().toPlainString() + "%";
    }

    private String authCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}
