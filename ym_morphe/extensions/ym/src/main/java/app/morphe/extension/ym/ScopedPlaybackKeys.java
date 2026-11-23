package app.morphe.extension.ym;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Derives scoped, installation-bound keys for an account, track, and session identifier.
 *
 * <p>Pure Java on purpose: the only dependency is the platform {@code HmacSHA256} provider, so the
 * derivation can be exercised on the JVM. The class performs no I/O, keeps no state, and logs
 * nothing. Raw identifiers and the secret are never stored; callers own them and only the derived
 * hex digests are returned.
 *
 * <p>Derivation for a 32-byte {@code secret} is
 * {@code HmacSHA256(secret, domain || value*)} where {@code domain} is a fixed single byte:
 * {@code 1} for the account key, {@code 2} for the account plus track key, and {@code 3} for the
 * account plus track plus session key. Every UTF-8 value is prefixed with its byte length as a
 * four-byte big-endian integer, so distinct identifier sequences can never encode to the same
 * bytes. The result of each MAC is a 64-character lower-case hex string.
 *
 * <p>Input rules: the secret must be exactly {@value #SECRET_BYTES} bytes and each identifier must
 * satisfy {@link QualifiedPlayCounter#isValidId(String)}. Accounts starting with the literal
 * {@value #PRIMORDIAL_PREFIX} prefix are reserved and rejected. Invalid input, or an unavailable
 * {@code HmacSHA256} implementation, yields {@code null} instead of an exception or a fallback
 * digest.
 */
final class ScopedPlaybackKeys {
    /** Required secret length in bytes; a shorter or longer secret is rejected. */
    static final int SECRET_BYTES = 32;

    private static final String ALGORITHM = "HmacSHA256";
    private static final byte DOMAIN_ACCOUNT = 1;
    private static final byte DOMAIN_TRACK = 2;
    private static final byte DOMAIN_SESSION = 3;
    private static final String PRIMORDIAL_PREFIX = "PRIMORDIAL-";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ScopedPlaybackKeys() {
    }

    /**
     * Immutable derived keys. The fields hold lower-case hex digests only; none of the raw
     * account, track, or session values is retained.
     */
    static final class Keys {
        /** Key scoped to the account alone. */
        final String account;
        /** Key scoped to the account and track. */
        final String track;
        /** Key scoped to the account, track, and session. */
        final String session;

        private Keys(String account, String track, String session) {
            this.account = account;
            this.track = track;
            this.session = session;
        }
    }

    /**
     * Derives the three scoped keys.
     *
     * @param secret installation secret, exactly {@value #SECRET_BYTES} bytes; never retained
     * @param account opaque account identifier
     * @param track opaque track identifier
     * @param session opaque session identifier
     * @return immutable derived keys, or {@code null} when the secret is missing or the wrong
     *         length, an identifier is invalid, the account uses the reserved prefix, or the
     *         {@code HmacSHA256} implementation is unavailable
     */
    static Keys derive(byte[] secret, String account, String track, String session) {
        if (secret == null || secret.length != SECRET_BYTES) return null;
        if (!QualifiedPlayCounter.isValidId(account)
                || !QualifiedPlayCounter.isValidId(track)
                || !QualifiedPlayCounter.isValidId(session)) {
            return null;
        }
        if (account.startsWith(PRIMORDIAL_PREFIX)) return null;

        Mac mac;
        try {
            mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // No provider, no algorithm, or a rejected key: fail closed without a fallback.
            return null;
        }

        String accountKey = hex(mac(mac, DOMAIN_ACCOUNT, account));
        String trackKey = hex(mac(mac, DOMAIN_TRACK, account, track));
        String sessionKey = hex(mac(mac, DOMAIN_SESSION, account, track, session));
        return new Keys(accountKey, trackKey, sessionKey);
    }

    private static byte[] mac(Mac mac, byte domain, String... values) {
        mac.reset();
        mac.update(domain);
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            int length = bytes.length;
            mac.update((byte) (length >>> 24));
            mac.update((byte) (length >>> 16));
            mac.update((byte) (length >>> 8));
            mac.update((byte) length);
            mac.update(bytes);
        }
        return mac.doFinal();
    }

    private static String hex(byte[] digest) {
        char[] chars = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int value = digest[i] & 0xff;
            chars[2 * i] = HEX[value >>> 4];
            chars[2 * i + 1] = HEX[value & 0x0f];
        }
        return new String(chars);
    }
}
