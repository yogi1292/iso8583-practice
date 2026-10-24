package practice.iso;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
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
 * then amount, limit and funds.
 */
final class AuthEngine {

    /** What the host should send back, why, and how long to wait before sending it. */
    record Outcome(ISOMsg response, String reason, long delayMs) {
    }

    /** An approved transaction the issuer remembers so that a reversal can find it. */
    private static final class Txn {
        final String pan;
        final boolean posted; // true for 0200 (money moved), false for 0100 (hold only)
        long amount;
        boolean reversed;

        Txn(String pan, boolean posted, long amount) {
            this.pan = pan;
            this.posted = posted;
            this.amount = amount;
        }
    }

    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyMM");

    private final CardStore cards;
    /** Approved transactions keyed by original MTI + STAN + DE 7, the same values DE 90 carries. */
    private final Map<String, Txn> approved = new HashMap<>();
    /** Terminal + STAN + DE 7 of every financial request seen, for duplicate detection. */
    private final Set<String> seen = new HashSet<>();
    private final Random random = new Random();

    /** A purchase or withdrawal of exactly this amount is answered late, to simulate a timeout. */
    private long slowAmount = 777_00;
    private long slowDelayMs = 5_000;

    AuthEngine(CardStore cards) {
        this.cards = cards;
    }

    CardStore cards() {
        return cards;
    }

    synchronized void setSlowResponse(long amount, long delayMs) {
        this.slowAmount = amount;
        this.slowDelayMs = delayMs;
    }

    /** Returns null for an MTI this host does not support; the host then sends nothing back. */
    synchronized Outcome handle(ISOMsg req) throws ISOException {
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

    synchronized Outcome reject(ISOMsg req, String code, String reason) throws ISOException {
        return decline(req, code, reason);
    }

    private Outcome networkManagement(ISOMsg req) throws ISOException {
        String code = req.hasField(70) ? req.getString(70) : "";
        String what;
        switch (code) {
            case "001":
                what = "sign-on";
                break;
            case "002":
                what = "sign-off";
                break;
            case "301":
                what = "echo test";
                break;
            default:
                return decline(req, "12", "unknown network management code DE 70 = " + code);
        }
        return new Outcome(respond(req, "00"), what, 0);
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
        if (amount > card.perTxnLimit) {
            return decline(req, "61", "amount is over the per-transaction limit of INR " + Iso.inr(card.perTxnLimit));
        }
        if (amount > card.available()) {
            return decline(req, "51", "insufficient funds: available INR " + Iso.inr(card.available()));
        }

        if (posts) {
            card.ledger -= amount;
        } else {
            card.holds += amount;
        }
        approved.put(req.getMTI() + req.getString(11) + req.getString(7), new Txn(card.pan, posts, amount));

        ISOMsg resp = respond(req, "00");
        resp.set(38, authCode());
        resp.set(54, balances(card));
        String reason = (posts ? "approved, ledger debited" : "approved, hold placed") + " INR " + Iso.inr(amount)
                + "; available now INR " + Iso.inr(card.available());
        long delay = amount == slowAmount ? slowDelayMs : 0;
        if (delay > 0) {
            reason += "; replying " + delay + " ms late (timeout test)";
        }
        return new Outcome(resp, reason, delay);
    }

    /**
     * DE 90 (original data elements) = original MTI (4) + original STAN (6) +
     * original DE 7 (10) + acquirer ID (11) + forwarding ID (11).
     * DE 95 (replacement amounts), when present, holds the amount that should
     * stay charged; everything above it is released.
     */
    private Outcome reversal(ISOMsg req) throws ISOException {
        if (!req.hasField(90) || req.getString(90).length() < 20) {
            return decline(req, "30", "format error: DE 90 (original data elements) is missing or short");
        }
        String de90 = req.getString(90);
        Txn txn = approved.get(de90.substring(0, 20));
        if (txn == null) {
            return decline(req, "25", "no approved original matches DE 90 (MTI " + de90.substring(0, 4)
                    + ", STAN " + de90.substring(4, 10) + ", DE 7 " + de90.substring(10, 20) + ")");
        }
        if (txn.reversed) {
            return new Outcome(respond(req, "00"), "original is already reversed, nothing changed (duplicate reversal)", 0);
        }
        long keep = req.hasField(95) ? Long.parseLong(req.getString(95).substring(0, 12)) : 0;
        if (keep > txn.amount) {
            return decline(req, "13", "replacement amount in DE 95 is more than the original amount");
        }

        Card card = cards.find(txn.pan);
        long release = txn.amount - keep;
        if (txn.posted) {
            card.ledger += release;
        } else {
            card.holds -= release;
        }
        txn.amount = keep;
        txn.reversed = keep == 0;
        String reason = (keep == 0 ? "full reversal" : "partial reversal, INR " + Iso.inr(keep) + " stays charged")
                + "; " + (txn.posted ? "credited back" : "hold released") + " INR " + Iso.inr(release)
                + "; available now INR " + Iso.inr(card.available());
        return new Outcome(respond(req, "00"), reason, 0);
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
        return String.format("00%s356%s%012d", amountType, amount < 0 ? "D" : "C", Math.abs(amount));
    }

    private String authCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}
