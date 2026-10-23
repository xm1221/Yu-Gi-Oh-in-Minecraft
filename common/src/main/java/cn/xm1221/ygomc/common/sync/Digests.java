package cn.xm1221.ygomc.common.sync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** sha256 小写十六进制。同步用它核对 pin，不把卡文带进仓库。 */
public final class Digests {

    private Digests() {
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                digest.update(buf, 0, n);
            }
        }
        return hex(digest.digest());
    }

    public static String sha256(byte[] bytes) {
        return hex(newDigest().digest(bytes));
    }

    public static boolean hexEquals(String expected, String actual) {
        return expected != null && actual != null && expected.equalsIgnoreCase(actual);
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] raw) {
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
