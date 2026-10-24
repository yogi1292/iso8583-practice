package practice.iso;

import org.jpos.iso.ISOUtil;

/**
 * ISO 9564 format 0 PIN block (DE 52).
 *
 * PIN field:  "0" + PIN length + PIN, padded with F to 16 hex digits.
 * PAN field:  "0000" + the 12 rightmost PAN digits, excluding the check digit.
 * PIN block = PIN field XOR PAN field.
 *
 * Real terminals then encrypt this block under the PIN key (TPK/ZPK) and the
 * issuer's HSM decrypts and verifies it. This practice tool sends the block
 * unencrypted so you can see how it is built.
 */
final class PinBlock {

    private PinBlock() {
    }

    static byte[] encode(String pin, String pan) {
        String pinField = ("0" + Integer.toHexString(pin.length()).toUpperCase() + pin + "FFFFFFFFFFFFFFFF").substring(0, 16);
        return xor(ISOUtil.hex2byte(pinField), panField(pan));
    }

    /** Returns the clear PIN, or an empty string if the block is not a valid format 0 block. */
    static String decode(byte[] block, String pan) {
        if (block == null || block.length != 8) {
            return "";
        }
        String clear = ISOUtil.hexString(xor(block, panField(pan)));
        int length = Character.digit(clear.charAt(1), 16);
        if (clear.charAt(0) != '0' || length < 4 || length > 12) {
            return "";
        }
        return clear.substring(2, 2 + length);
    }

    private static byte[] panField(String pan) {
        return ISOUtil.hex2byte("0000" + pan.substring(pan.length() - 13, pan.length() - 1));
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }
}
