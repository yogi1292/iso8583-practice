package practice.iso;

/**
 * The card scheme (network), worked out from the PAN's leading digits, and
 * the names of the end-of-day files this practice host writes for it.
 *
 * The file names follow the real-world names bank teams use for these files,
 * but the contents are a simplified text layout (see the README).
 */
enum Scheme {

    VISA("Visa",
            "VISA_BASEII_CLEARING", "Visa BASE II incoming clearing", "TC05",
            "VISA_VSS_SETTLEMENT", "Visa VSS settlement summary"),
    MASTERCARD("Mastercard",
            "MC_IPM_T112_CLEARING", "Mastercard IPM incoming clearing (T112)", "1240-200",
            "MC_SETTLEMENT_REPORT", "Mastercard settlement position report"),
    RUPAY("RuPay",
            "RUPAY_CLEARING", "RuPay (NPCI) incoming clearing", "PURCHASE",
            "RUPAY_NTSL", "RuPay NTSL net settlement report"),
    AMEX("Amex",
            "AMEX_SUBMISSION", "Amex submission / clearing detail", "SUBMISSION",
            "AMEX_SETTLEMENT", "Amex settlement summary");

    final String displayName;
    final String clearingPrefix;
    final String clearingTitle;
    /** Shown on each purchase record: Visa transaction code, Mastercard MTI-function code, and so on. */
    final String purchaseCode;
    final String settlementPrefix;
    final String settlementTitle;

    Scheme(String displayName, String clearingPrefix, String clearingTitle, String purchaseCode,
            String settlementPrefix, String settlementTitle) {
        this.displayName = displayName;
        this.clearingPrefix = clearingPrefix;
        this.clearingTitle = clearingTitle;
        this.purchaseCode = purchaseCode;
        this.settlementPrefix = settlementPrefix;
        this.settlementTitle = settlementTitle;
    }

    /**
     * Visa 4; Amex 34, 37; Mastercard 51-55 and 2221-2720; RuPay 60, 65, 81,
     * 82, 508. (65 is also used by Discover outside India.) Null when unknown.
     */
    static Scheme fromPan(String pan) {
        if (pan == null || pan.length() < 4) {
            return null;
        }
        int two = Integer.parseInt(pan.substring(0, 2));
        int four = Integer.parseInt(pan.substring(0, 4));
        if (pan.startsWith("4")) {
            return VISA;
        }
        if (two == 34 || two == 37) {
            return AMEX;
        }
        if ((two >= 51 && two <= 55) || (four >= 2221 && four <= 2720)) {
            return MASTERCARD;
        }
        if (two == 60 || two == 65 || two == 81 || two == 82 || pan.startsWith("508")) {
            return RUPAY;
        }
        return null;
    }
}
