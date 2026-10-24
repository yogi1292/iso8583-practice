package practice.iso;

import java.io.IOException;
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
            Map.entry("61", "Exceeds amount limit"),
            Map.entry("62", "Restricted card"),
            Map.entry("68", "Response received too late"),
            Map.entry("75", "PIN tries exceeded"),
            Map.entry("91", "Issuer or switch inoperative"),
            Map.entry("94", "Duplicate transmission"));

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

    /** Formats an amount held in paise as rupees, e.g. 250000 -> "2,500.00". */
    static String inr(long paise) {
        return String.format("%,d.%02d", paise / 100, Math.abs(paise % 100));
    }
}
