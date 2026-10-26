package practice.iso;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;

/**
 * Writes the end-of-day files for a cutover:
 *
 *   AUTHLOG_<date>_<seq>.csv                 the issuer's own log of every request and decision
 *   <scheme clearing prefix>_<date>_<seq>.txt  dual message purchases the scheme clears today
 *   <scheme settlement prefix>_<date>_<seq>.txt net amount the issuer owes the scheme
 *
 * The names follow what bank teams call these files; the contents use a
 * simple pipe-delimited practice layout, not the networks' real formats.
 */
final class SchemeFiles {

    /** Practice interchange the issuer earns on purchases, in basis points. */
    static final int PURCHASE_INTERCHANGE_BPS = 150;
    /** Practice ATM interchange the issuer pays the acquirer per cash withdrawal, in paise. */
    static final long ATM_INTERCHANGE_PER_TXN = 19_00;

    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** One line of the authorization log. */
    record LogRow(LocalDateTime time, String mti, String stan, String rrn, String pan, String scheme,
            String processingCode, String currency, String amount, long billed, long fee, String responseCode,
            String authCode, String reason) {

        static LogRow of(ISOMsg req, AuthEngine.Outcome out, long billed, long fee) throws ISOException {
            ISOMsg resp = out.response();
            String pan = req.hasField(2) ? req.getString(2) : "";
            Scheme scheme = Scheme.fromPan(pan);
            return new LogRow(LocalDateTime.now(), req.getMTI(), req.getString(11), field(req, 37), Iso.maskPan(pan),
                    scheme == null ? "" : scheme.displayName, field(req, 3), field(req, 49),
                    req.hasField(4) ? Iso.inr(Long.parseLong(req.getString(4))).replace(",", "") : "",
                    billed, fee, resp.getString(39), field(resp, 38), out.reason().replace('\n', ' '));
        }

        private static String field(ISOMsg m, int f) {
            return m.hasField(f) ? m.getString(f) : "";
        }
    }

    private SchemeFiles() {
    }

    static List<Path> write(Path dir, LocalDate date, int sequence, List<Transaction> txns, List<LogRow> log)
            throws IOException {
        Files.createDirectories(dir);
        String d = date.format(DATE);
        String seq = String.format("%02d", sequence);
        List<Path> written = new ArrayList<>();
        written.add(writeAuthLog(dir.resolve("AUTHLOG_" + d + "_" + seq + ".csv"), log));
        long arnSequence = 1;
        for (Scheme scheme : Scheme.values()) {
            List<Transaction> mine = txns.stream().filter(t -> t.scheme == scheme).toList();
            written.add(writeClearing(dir.resolve(scheme.clearingPrefix + "_" + d + "_" + seq + ".txt"),
                    scheme, date, seq, mine, arnSequence));
            arnSequence += mine.size();
            written.add(writeSettlement(dir.resolve(scheme.settlementPrefix + "_" + d + "_" + seq + ".txt"),
                    scheme, date, seq, mine));
        }
        return written;
    }

    private static Path writeAuthLog(Path file, List<LogRow> log) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("time,mti,stan,rrn,pan,scheme,processing_code,txn_currency,txn_amount,billing_amount_inr,"
                + "issuer_fee_inr,response_code,auth_code,reason");
        for (LogRow r : log) {
            lines.add(String.join(",", r.time().format(DATE_TIME), r.mti(), r.stan(), r.rrn(), r.pan(), r.scheme(),
                    r.processingCode(), r.currency(), r.amount(), plain(r.billed()), plain(r.fee()), r.responseCode(),
                    r.authCode(), csv(r.reason())));
        }
        return Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /**
     * Clearing: only dual message (0100) purchases. Single message (0200) cash
     * withdrawals were posted when authorized, so they appear in the settlement
     * report but not here.
     */
    private static Path writeClearing(Path file, Scheme scheme, LocalDate date, String seq, List<Transaction> txns,
            long firstArn) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("H|" + scheme.displayName.toUpperCase() + "|" + scheme.clearingTitle + " (practice layout)"
                + "|BUSINESS_DATE=" + date + "|FILE_SEQ=" + seq);
        lines.add("# D|TXN_CODE|ARN|PAN|AUTH_CODE|RRN|AUTH_DATE_TIME|MCC|MERCHANT|ACQ_COUNTRY|TXN_CCY|TXN_AMOUNT"
                + "|BILLING_CCY|BILLING_AMOUNT|CONVERSION_RATE|DCC");
        long arn = firstArn;
        long count = 0;
        long totalBilled = 0;
        for (Transaction t : txns) {
            if (t.posted) {
                continue;
            }
            lines.add(String.join("|", "D", scheme.purchaseCode, arn(date, arn++), Iso.maskPan(t.pan), t.authCode, t.rrn,
                    t.approvedAt.format(DATE_TIME), t.mcc, t.merchant, t.acquirerCountry, t.txnCurrency, plain(t.txnAmount),
                    Iso.INR, plain(t.billed), t.conversionRate.isEmpty() ? "" : Iso.rateText(t.conversionRate),
                    t.dcc ? "Y" : "N"));
            count++;
            totalBilled += t.billed;
        }
        lines.add("T|DETAIL_RECORDS=" + count + "|TOTAL_BILLING_AMOUNT=" + plain(totalBilled));
        return Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static Path writeSettlement(Path file, Scheme scheme, LocalDate date, String seq, List<Transaction> txns)
            throws IOException {
        long purchases = 0;
        long purchaseCount = 0;
        long cash = 0;
        long cashCount = 0;
        for (Transaction t : txns) {
            if (t.posted) {
                cash += t.billed;
                cashCount++;
            } else {
                purchases += t.billed;
                purchaseCount++;
            }
        }
        long interchange = Iso.percent(purchases, PURCHASE_INTERCHANGE_BPS);
        long atmFees = cashCount * ATM_INTERCHANGE_PER_TXN;
        long net = purchases + cash + atmFees - interchange;

        String rule = "-".repeat(78);
        List<String> lines = new ArrayList<>();
        lines.add(scheme.settlementTitle.toUpperCase() + " (practice layout)");
        lines.add("Business date: " + date + "    File sequence: " + seq + "    Settlement currency: INR");
        lines.add(rule);
        lines.add(String.format("%-52s %6s %16s %3s", "Category", "Count", "Amount INR", ""));
        lines.add(rule);
        lines.add(row("Purchases cleared (dual message, 0100)", purchaseCount, purchases, "DR"));
        lines.add(row("Cash withdrawals (single message, 0200)", cashCount, cash, "DR"));
        lines.add(row("Interchange receivable on purchases (" + pct(PURCHASE_INTERCHANGE_BPS) + ")", -1, interchange, "CR"));
        lines.add(row("ATM interchange payable (INR " + Iso.inr(ATM_INTERCHANGE_PER_TXN) + " per withdrawal)", -1, atmFees, "DR"));
        lines.add(rule);
        lines.add(row("Net settlement: issuer pays " + scheme.displayName, -1, net, net >= 0 ? "DR" : "CR"));
        lines.add(rule);
        lines.add("DR = issuer pays, CR = issuer receives.");
        lines.add("Forex markup and cross-border fees are the issuer's own income from the cardholder;");
        lines.add("they never pass through scheme settlement, so they are not in this report.");
        lines.add("Practice rates only. Real interchange and ATM fees depend on the scheme, card type and MCC,");
        lines.add("and international settlement is often in USD rather than INR.");
        return Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static String row(String label, long count, long amount, String drCr) {
        return String.format("%-52s %6s %16s %3s", label, count < 0 ? "" : String.valueOf(count), Iso.inr(amount), drCr);
    }

    /**
     * A 23-digit ARN-style reference: format digit (2) + acquirer BIN (6) +
     * date as YDDD (4) + sequence (11) + check digit (1, Luhn / mod 10).
     */
    static String arn(LocalDate date, long sequence) {
        String body = "2" + MessageFactory.ACQUIRER_ID
                + String.format("%d%03d", date.getYear() % 10, date.getDayOfYear())
                + String.format("%011d", sequence);
        return body + luhnCheckDigit(body);
    }

    private static int luhnCheckDigit(String digits) {
        int sum = 0;
        boolean dbl = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (dbl) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            dbl = !dbl;
        }
        return (10 - sum % 10) % 10;
    }

    /** Minor units to a plain decimal for files: 840000 -> "8400.00". */
    private static String plain(long minor) {
        return Iso.inr(minor).replace(",", "");
    }

    private static String pct(int bps) {
        return new java.math.BigDecimal(bps).movePointLeft(2).stripTrailingZeros().toPlainString() + "%";
    }

    private static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
