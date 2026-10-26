package practice.iso;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;

import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.ISOUtil;

/** Prints a message field by field: MTI, bitmap, each data element, and optionally the raw bytes. */
final class MessagePrinter {

    private static final Map<String, String> MTI_NAMES = Map.of(
            "0100", "Authorization request",
            "0110", "Authorization response",
            "0200", "Financial request",
            "0210", "Financial response",
            "0400", "Reversal request",
            "0410", "Reversal response",
            "0420", "Reversal advice",
            "0430", "Reversal advice response",
            "0800", "Network management request",
            "0810", "Network management response");

    private MessagePrinter() {
    }

    static synchronized void print(String heading, ISOMsg msg, boolean showRaw) {
        try {
            ISOMsg m = (ISOMsg) msg.clone();
            m.setPackager(Iso.PACKAGER);
            byte[] raw = m.pack();
            String mti = m.getMTI();

            StringBuilder sb = new StringBuilder();
            sb.append('\n').append(heading).append("  ").append(mti).append("  ")
                    .append(MTI_NAMES.getOrDefault(mti, "")).append('\n');
            sb.append("    Bitmap  ").append(bitmap(raw)).append("  -> DEs ").append(fieldList(m)).append('\n');
            for (int i = 2; i <= m.getMaxField(); i++) {
                if (m.hasField(i)) {
                    sb.append(String.format("    DE %-3d %-34s %s%n", i, describe(m, i), display(m, i)));
                }
            }
            if (m.hasField(39)) {
                sb.append("    Response code ").append(m.getString(39)).append(" = ")
                        .append(Iso.describeCode(m.getString(39))).append('\n');
            }
            if (showRaw) {
                sb.append("    Raw message (the 2-byte length header in front is not shown):\n");
                for (String line : ISOUtil.hexdump(raw).split("\n")) {
                    sb.append("      ").append(line).append('\n');
                }
            }
            System.out.print(sb);
        } catch (ISOException e) {
            System.out.println(heading + " (could not pack the message: " + e.getMessage() + ")");
        }
    }

    /** The bitmap follows the 4-character MTI: 16 hex digits, or 32 when bit 1 says a secondary bitmap follows. */
    private static String bitmap(byte[] raw) {
        String primary = new String(raw, 4, 16, StandardCharsets.US_ASCII);
        boolean secondary = (Character.digit(primary.charAt(0), 16) & 0x8) != 0;
        return secondary ? new String(raw, 4, 32, StandardCharsets.US_ASCII) : primary;
    }

    private static String fieldList(ISOMsg m) {
        StringJoiner joiner = new StringJoiner(",");
        for (int i = 2; i <= m.getMaxField(); i++) {
            if (m.hasField(i)) {
                joiner.add(String.valueOf(i));
            }
        }
        return joiner.toString();
    }

    private static String describe(ISOMsg m, int field) {
        String d = Iso.PACKAGER.getFieldDescription(m, field);
        d = d == null ? "" : d.trim();
        return d.length() > 34 ? d.substring(0, 34) : d;
    }

    private static String display(ISOMsg m, int field) {
        if (field == 2) {
            return Iso.maskPan(m.getString(2));
        }
        if (field == 10) {
            return "[" + m.getString(10) + "]  (rate " + Iso.rateText(m.getString(10)) + ")";
        }
        if (field == 52) {
            return ISOUtil.hexString(m.getBytes(52)) + "  (format 0 PIN block, not encrypted here)";
        }
        Object value = m.getValue(field);
        return value instanceof byte[] ? ISOUtil.hexString((byte[]) value) : "[" + m.getString(field) + "]";
    }
}
