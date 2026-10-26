package practice.iso;

/** One test card held by the issuer. Amounts are in paise (INR 1 = 100 paise). */
final class Card {

    enum Status { ACTIVE, BLOCKED, LOST, STOLEN }

    final String label;
    final String pan;
    final String expiry; // YYMM, as sent in DE 14
    final String pin;
    final Status status;
    final long perTxnLimit;
    /** False means the cardholder has turned off use abroad and in foreign currency. */
    final boolean internationalEnabled;

    /** Posted balance. Changes only when money actually moves. */
    long ledger;
    /** Money blocked by approved authorizations that are not yet cleared. */
    long holds;
    int wrongPinTries;

    Card(String label, String pan, String expiry, String pin, Status status, long ledger, long perTxnLimit,
            boolean internationalEnabled) {
        this.label = label;
        this.pan = pan;
        this.expiry = expiry;
        this.pin = pin;
        this.status = status;
        this.ledger = ledger;
        this.perTxnLimit = perTxnLimit;
        this.internationalEnabled = internationalEnabled;
    }

    long available() {
        return ledger - holds;
    }
}
