package com.payneteasy.dcagent.core.modules.zipversion;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The content digest of a version — what a retry of the same tag is compared by, so a rebuilt ZIP
 * of the same files (other timestamps, entry order, directory entries) matches: sha256 over the
 * lines {@code <path>\0<sha256 of contents hex>\n}, sorted by the UTF-8 bytes of the path
 * (unsigned). The digest file holds {@code <64 lowercase hex>\n}.
 */
public final class Digests {

    private static final HexFormat HEX         = HexFormat.of();
    private static final Pattern   DIGEST_TEXT = Pattern.compile("^[0-9a-f]{64}$");

    private Digests() {
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("No SHA-256", e);
        }
    }

    public static String hex(byte[] aBytes) {
        return HEX.formatHex(aBytes);
    }

    /** @param aFiles normalised path → lowercase hex sha256 of its contents */
    public static String treeDigest(Map<String, String> aFiles) {
        List<byte[][]> lines = new ArrayList<>();
        for (Map.Entry<String, String> file : aFiles.entrySet()) {
            lines.add(new byte[][]{file.getKey().getBytes(UTF_8), file.getValue().getBytes(US_ASCII)});
        }
        lines.sort((left, right) -> Arrays.compareUnsigned(left[0], right[0]));

        MessageDigest digest = sha256();
        for (byte[][] line : lines) {
            digest.update(line[0]);
            digest.update((byte) 0);
            digest.update(line[1]);
            digest.update((byte) '\n');
        }
        return hex(digest.digest());
    }

    /** Contents of the digest file. */
    public static byte[] fileContents(String aDigest) {
        return (aDigest + "\n").getBytes(US_ASCII);
    }

    /** The digest from the file's contents (one trailing newline stripped), or null when it is not one. */
    public static String parseFile(byte[] aContents) {
        String text = new String(aContents, US_ASCII);
        if (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        return DIGEST_TEXT.matcher(text).matches() ? text : null;
    }
}
