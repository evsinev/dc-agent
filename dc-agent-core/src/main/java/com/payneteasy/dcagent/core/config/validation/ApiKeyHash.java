package com.payneteasy.dcagent.core.config.validation;

import com.payneteasy.dcagent.core.util.Hashes;

import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * An api key is stored in a command config as {@code sha256:<64 lowercase hex>} of its UTF-8 bytes,
 * never as itself: a config read by someone else (a misconfigured web server, a backup) then gives
 * no working key. The keys are machine-made (UUIDs, 64-character strings), so a plain SHA-256 cannot
 * be reversed; a slow hash would only cost CPU on every request.
 * <p>
 * The {@code sha256:} prefix is reserved, in any letter case: an entry starting with it is either a
 * well-formed hash or a mistake — never a plaintext key to hash (that would hash a hash).
 */
public final class ApiKeyHash {

    public static final String PREFIX         = "sha256:";
    /** A new key (the control plane, {@code hash-key}) is at least this long. */
    public static final int    MIN_KEY_LENGTH = 32;

    private static final Pattern HASHED = Pattern.compile("sha256:[0-9a-f]{64}");

    private ApiKeyHash() {
    }

    public static String hash(String aKey) {
        return PREFIX + HexFormat.of().formatHex(Hashes.sha256(aKey.getBytes(UTF_8)));
    }

    /** {@code sha256:} + 64 lowercase hex, exactly. */
    public static boolean isHashed(String aEntry) {
        return aEntry != null && HASHED.matcher(aEntry).matches();
    }

    /** Starts with {@code sha256:} in any letter case. */
    public static boolean hasReservedPrefix(String aEntry) {
        return aEntry != null && aEntry.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }

    /** Neither hashed nor reserved: a key still written as itself. */
    public static boolean isPlaintext(String aEntry) {
        return !hasReservedPrefix(aEntry);
    }

    /**
     * Whether {@code aPresented} is one of the keys: its hash is compared with every hashed entry.
     * The presented value is never compared with an entry as it is — sending the stored hash itself
     * (from a stolen config) does not match.
     */
    public static boolean matches(String aPresented, Collection<String> aEntries) {
        byte[]  presented = hash(aPresented).getBytes(UTF_8);
        boolean found     = false;
        for (String entry : aEntries) {
            if (isHashed(entry) && MessageDigest.isEqual(presented, entry.getBytes(UTF_8))) {
                found = true;
            }
        }
        return found;
    }

    /**
     * {@code ****} + the first 8 hex of the hash — for a hashed entry taken from the entry, for a
     * plaintext one computed: the same secret keeps the same id before and after it is hashed, so a
     * {@code keep} from an earlier answer to the operator stays valid.
     */
    public static String maskedId(String aEntry) {
        String hash = isHashed(aEntry) ? aEntry : hash(aEntry);
        return "****" + hash.substring(PREFIX.length(), PREFIX.length() + 8);
    }
}
