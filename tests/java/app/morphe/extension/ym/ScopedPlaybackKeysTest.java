package app.morphe.extension.ym;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import app.morphe.extension.ym.ScopedPlaybackKeys.Keys;

public final class ScopedPlaybackKeysTest {
    /** Installation secret 0x00..0x1f; the derived vectors below are locked to this secret. */
    private static final byte[] SECRET = secret(0);
    /** A second installation secret 0x01..0x20. */
    private static final byte[] OTHER_SECRET = secret(1);

    private static byte[] secret(int first) {
        byte[] bytes = new byte[ScopedPlaybackKeys.SECRET_BYTES];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (first + i);
        return bytes;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Keys derive(byte[] secret, String account, String track, String session) {
        return ScopedPlaybackKeys.derive(secret, account, track, session);
    }

    private static void equal(String actual, String expected, String message) {
        check(expected.equals(actual), message + ": " + actual + " != " + expected);
    }

    private static void different(String left, String right, String message) {
        check(!left.equals(right), message + ": both are " + left);
    }

    private static void checkShape(String key, String message) {
        check(key != null, message + ": key is null");
        check(key.length() == 64, message + ": key length is " + key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            check((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'),
                    message + ": key is not lower-case hex at " + i + ": " + key);
        }
    }

    private static void checkKeys(Keys keys, String message) {
        check(keys != null, message + ": keys are null");
        checkShape(keys.account, message + " account key");
        checkShape(keys.track, message + " track key");
        checkShape(keys.session, message + " session key");
    }

    public static void main(String[] args) {
        deterministicAndDomainSeparated();
        knownVectors();
        installationIsBound();
        accountAndTrackAreScoped();
        identifierSequenceIsUnambiguous();
        utf8IsEncodedExactly();
        invalidInputIsRejected();
        reservedPrefixIsRejected();
        nothingMutableOrRawIsRetained();
        partialDerivationsMatchDerive();

        // HmacSHA256 must exist on every supported JVM; skip nothing silently.
        check(derive(SECRET, "a", "t", "s") != null, "HmacSHA256 must be available");
        System.out.println("ScopedPlaybackKeys tests passed");
    }

    private static void deterministicAndDomainSeparated() {
        Keys first = derive(SECRET, "acct", "track", "session");
        Keys second = derive(SECRET.clone(), "acct", "track", "session");
        checkKeys(first, "base");
        checkKeys(second, "repeat");
        equal(first.account, second.account, "account key is deterministic");
        equal(first.track, second.track, "track key is deterministic");
        equal(first.session, second.session, "session key is deterministic");

        Set<String> distinct = new HashSet<>(Arrays.asList(
                first.account, first.track, first.session));
        check(distinct.size() == 3, "account/track/session keys must differ by domain");
    }

    private static void knownVectors() {
        Keys base = derive(SECRET, "acct", "track", "session");
        equal(base.account,
                "213a0fd061d670e2e5ca5c8c81a0b79e1c3cd6f3b88dd517d9a2319ebea3ba3a",
                "account key vector");
        equal(base.track,
                "94f363655c9dd5c146fe3d5c94cbab312fe47d92d562033f6a5b64cc0377dfb8",
                "track key vector");
        equal(base.session,
                "52f4ab1b1a76f365945c4129494aaf3fb6025019326b664793c34c504bea6465",
                "session key vector");

        Keys other = derive(OTHER_SECRET, "acct", "track", "session");
        equal(other.account,
                "f3248b93c6a5c4e759d91bc9e15f5e028b114f9a0ed89910e15b1ed037b9fd70",
                "other installation account key vector");
        equal(other.track,
                "1b444239d38be9a4183f2004a49fdcc47924cd2713ddb30475ff7b378d9a2c78",
                "other installation track key vector");
        equal(other.session,
                "2fd3513b3d9baef795c8be5baba1a593311a135838c5fa0176e465e033950f86",
                "other installation session key vector");

        Keys utf8 = derive(SECRET, "\u65e5\u672c\u8a9e", "\u66f2 \ud83c\udfb5", "\uc138\uc158");
        equal(utf8.account,
                "72716fd37c889331857cd80385914cc3d24679852a87176d2d13054ea085bf9b",
                "UTF-8 account key vector");
        equal(utf8.track,
                "c54317069e6cf1caeacf8acb88c0b28846e7c92281ff725806c18a795dd86f48",
                "UTF-8 track key vector");
        equal(utf8.session,
                "3a77705f5c625d239124e22b503e46eff99c8e161f395c8280b7305ffecfde34",
                "UTF-8 session key vector");
    }

    private static void installationIsBound() {
        String[][] ids = {
            {"acct", "track", "session"},
            {"\u65e5\u672c\u8a9e", "\u66f2 \ud83c\udfb5", "\uc138\uc158"},
            {"a", "b", "c"},
        };
        for (String[] id : ids) {
            Keys first = derive(SECRET, id[0], id[1], id[2]);
            Keys second = derive(OTHER_SECRET, id[0], id[1], id[2]);
            checkKeys(first, "first installation " + Arrays.toString(id));
            checkKeys(second, "second installation " + Arrays.toString(id));
            different(first.account, second.account, "account key differs per installation");
            different(first.track, second.track, "track key differs per installation");
            different(first.session, second.session, "session key differs per installation");
        }
    }

    private static void accountAndTrackAreScoped() {
        Keys base = derive(SECRET, "acct", "track", "session");
        Keys sameTrackOtherSession = derive(SECRET, "acct", "track", "session2");
        Keys otherTrack = derive(SECRET, "acct", "track2", "session");
        Keys otherAccount = derive(SECRET, "acct2", "track", "session");

        // The account key depends on the account only.
        equal(base.account, sameTrackOtherSession.account, "account key ignores session");
        equal(base.account, otherTrack.account, "account key ignores track");
        different(base.account, otherAccount.account, "account key depends on account");

        // The track key depends on account and track, not on the session.
        equal(base.track, sameTrackOtherSession.track, "track key ignores session");
        different(base.track, otherTrack.track, "track key depends on track");
        different(base.track, otherAccount.track, "track key depends on account");

        // The session key depends on all three values.
        different(base.session, sameTrackOtherSession.session, "session key depends on session");
        different(base.session, otherTrack.session, "session key depends on track");
        different(base.session, otherAccount.session, "session key depends on account");
    }

    private static void identifierSequenceIsUnambiguous() {
        // Same concatenated characters, different identifier split: length prefixes separate them.
        Keys abCd = derive(SECRET, "ab", "c", "d");
        Keys aBcD = derive(SECRET, "a", "bc", "d");
        Keys abCd2 = derive(SECRET, "a", "b", "cd");
        checkKeys(abCd, "ab/c/d");
        checkKeys(aBcD, "a/bc/d");
        checkKeys(abCd2, "a/b/cd");
        different(abCd.account, aBcD.account, "account boundary is unambiguous");
        different(abCd.account, abCd2.account, "account boundary is unambiguous 2");
        different(abCd.track, aBcD.track, "track boundary is unambiguous");
        different(abCd.session, aBcD.session, "session boundary is unambiguous");
        different(abCd.session, abCd2.session, "session boundary is unambiguous 2");
        different(abCd.track, abCd2.track, "track boundary is unambiguous 2");

        // A literal separator inside an identifier must not collide with the length framing.
        Keys withSeparator = derive(SECRET, "a:b", "c", "d");
        Keys shifted = derive(SECRET, "a", "b:c", "d");
        different(withSeparator.account, shifted.account, "separator is not a framing character");
        different(withSeparator.track, shifted.track, "separator is not a framing character 2");

        // Domain separation is part of the message prefix.
        equal(derive(SECRET, "acct", "acct", "acct").account,
                derive(SECRET, "acct", "other", "other").account,
                "account-only message is independent of later values");
    }

    private static void utf8IsEncodedExactly() {
        Keys utf8 = derive(SECRET, "\u3042", "\u3044", "\u3046");
        Keys ascii = derive(SECRET, "a", "i", "u");
        checkKeys(utf8, "multibyte");
        different(utf8.account, ascii.account, "multibyte account is not folded to ASCII");
        different(utf8.track, ascii.track, "multibyte track is not folded to ASCII");
        different(utf8.session, ascii.session, "multibyte session is not folded to ASCII");

        // A NUL inside an identifier is data, not a terminator, and is length frames away from
        // the same characters in another field.
        Keys withNul = derive(SECRET, "a\u0000b", "t", "s");
        checkKeys(withNul, "NUL in account");
        different(withNul.account, derive(SECRET, "a", "b", "t").account,
                "NUL does not truncate the identifier");
        // Four-byte UTF-8 characters (two UTF-16 units) are accepted inside any identifier.
        checkKeys(derive(SECRET, "\ud83c\udfb5", "t", "s"), "supplementary account");
        checkKeys(derive(SECRET, "a", "\ud83c\udfb5", "s"), "supplementary track");
        checkKeys(derive(SECRET, "a", "t", "\ud83c\udfb5"), "supplementary session");
    }

    private static void invalidInputIsRejected() {
        check(derive(null, "a", "t", "s") == null, "null secret is rejected");
        check(derive(new byte[0], "a", "t", "s") == null, "empty secret is rejected");
        check(derive(new byte[31], "a", "t", "s") == null, "short secret is rejected");
        check(derive(new byte[33], "a", "t", "s") == null, "long secret is rejected");

        check(derive(SECRET, null, "t", "s") == null, "null account is rejected");
        check(derive(SECRET, "a", null, "s") == null, "null track is rejected");
        check(derive(SECRET, "a", "t", null) == null, "null session is rejected");
        check(derive(SECRET, "", "t", "s") == null, "empty account is rejected");
        check(derive(SECRET, "a", "  ", "s") == null, "blank track is rejected");
        check(derive(SECRET, "a", "t", "\t") == null, "blank session is rejected");

        String max = "x".repeat(QualifiedPlayCounter.MAX_ID_BYTES);
        checkKeys(derive(SECRET, max, max, max), "maximum length ids are accepted");
        String tooLong = max + "x";
        check(derive(SECRET, tooLong, "t", "s") == null, "oversized account is rejected");
        check(derive(SECRET, "a", tooLong, "s") == null, "oversized track is rejected");
        check(derive(SECRET, "a", "t", tooLong) == null, "oversized session is rejected");

        String multibyte = "\u3042".repeat(QualifiedPlayCounter.MAX_ID_BYTES / 3 + 1);
        check(multibyte.length() <= QualifiedPlayCounter.MAX_ID_BYTES,
                "precondition: multibyte id is short in UTF-16");
        check(derive(SECRET, multibyte, "t", "s") == null,
                "id over the UTF-8 byte bound is rejected");

        // Unpaired surrogates cannot be encoded as UTF-8 and must not be silently replaced.
        check(derive(SECRET, "\ud800", "t", "s") == null, "lone high surrogate is rejected");
        check(derive(SECRET, "a", "\udc00", "s") == null, "lone low surrogate is rejected");
        check(derive(SECRET, "a", "t", "b\ud800c") == null, "embedded lone surrogate is rejected");

        // A rejected call must not throw and must not consume or cache anything.
        checkKeys(derive(SECRET, "a", "t", "s"), "valid call after rejected calls");
    }

    private static void reservedPrefixIsRejected() {
        check(derive(SECRET, "PRIMORDIAL-", "t", "s") == null,
                "reserved prefix alone is rejected");
        check(derive(SECRET, "PRIMORDIAL-acct", "t", "s") == null,
                "reserved prefix account is rejected");
        check(derive(SECRET, "PRIMORDIAL-acct", "t", "s") == null,
                "reserved prefix account is rejected on repeat");
        // Only the exact upper-case prefix is reserved, matching the literal contract.
        checkKeys(derive(SECRET, "primordial-acct", "t", "s"),
                "lower-case prefix is a normal account");
        checkKeys(derive(SECRET, "xPRIMORDIAL-acct", "t", "s"),
                "prefix must start the account");
        checkKeys(derive(SECRET, "a", "PRIMORDIAL-track", "PRIMORDIAL-session"),
                "prefix is reserved for accounts only");
    }

    private static void nothingMutableOrRawIsRetained() {
        check(Modifier.isFinal(ScopedPlaybackKeys.class.getModifiers()),
                "ScopedPlaybackKeys is final");
        check(Modifier.isFinal(ScopedPlaybackKeys.Keys.class.getModifiers()), "Keys is final");
        check(Modifier.isPrivate(ScopedPlaybackKeys.class.getDeclaredConstructors()[0]
                .getModifiers()), "utility constructor is private");

        Keys keys = derive(SECRET, "acct", "track", "session");
        for (Field field : ScopedPlaybackKeys.Keys.class.getDeclaredFields()) {
            check(Modifier.isFinal(field.getModifiers()), "Keys field is final: " + field.getName());
            check(field.getType() == String.class, "Keys field is a String: " + field.getName());
            check(!Modifier.isPublic(field.getModifiers()),
                    "Keys field is internal: " + field.getName());
        }

        // No derived key may carry a raw identifier, and the secret is never echoed.
        String secretHex = hex(SECRET);
        for (String key : new String[] {keys.account, keys.track, keys.session}) {
            check(!key.contains("acct"), "account key does not embed the raw account");
            check(!key.contains("track"), "track key does not embed the raw track");
            check(!key.contains("session"), "session key does not embed the raw session");
            check(!key.equals(secretHex), "derived key is not the raw secret");
        }

        // The secret is used as supplied: mutating the caller array changes later derivations,
        // which shows no copy of it is kept anywhere by this class.
        byte[] mutable = SECRET.clone();
        Keys before = derive(mutable, "acct", "track", "session");
        Arrays.fill(mutable, (byte) 0);
        Keys after = derive(mutable, "acct", "track", "session");
        checkKeys(after, "zeroed secret");
        different(before.account, after.account, "derivation follows the supplied secret bytes");
        equal(before.session, keys.session, "earlier result is unaffected by later mutation");
        check(Arrays.equals(SECRET, secret(0)), "test secret constant is unchanged");
    }

    /** The partial derivations used for hydration must match the full derivation exactly. */
    private static void partialDerivationsMatchDerive() {
        String[][] ids = {
            {"acct", "track", "session"},
            {"a", "b", "c"},
            {"\u65e5\u672c\u8a9e", "\u66f2 \ud83c\udfb5", "\uc138\uc158"},
            {"x".repeat(QualifiedPlayCounter.MAX_ID_BYTES),
                "y".repeat(QualifiedPlayCounter.MAX_ID_BYTES), "s"},
        };
        for (String[] id : ids) {
            Keys full = derive(SECRET, id[0], id[1], id[2]);
            equal(ScopedPlaybackKeys.deriveAccountKey(SECRET, id[0]), full.account,
                    "deriveAccountKey matches derive for " + Arrays.toString(id));
            equal(ScopedPlaybackKeys.deriveTrackKey(SECRET, id[0], id[1]), full.track,
                    "deriveTrackKey matches derive for " + Arrays.toString(id));
            // The account key is independent of track/session, the track key of session.
            equal(ScopedPlaybackKeys.deriveAccountKey(SECRET, id[0]), full.account,
                    "account key ignores track and session");
            equal(ScopedPlaybackKeys.deriveTrackKey(SECRET, id[0], id[1]), full.track,
                    "track key ignores session");
        }
        // A different installation secret yields different partial keys.
        different(ScopedPlaybackKeys.deriveAccountKey(SECRET, "acct"),
                ScopedPlaybackKeys.deriveAccountKey(OTHER_SECRET, "acct"),
                "account key is installation-bound");
        different(ScopedPlaybackKeys.deriveTrackKey(SECRET, "acct", "track"),
                ScopedPlaybackKeys.deriveTrackKey(OTHER_SECRET, "acct", "track"),
                "track key is installation-bound");

        // Invalid input is rejected by the partial helpers under the same rules.
        check(ScopedPlaybackKeys.deriveAccountKey(null, "acct") == null, "null secret account");
        check(ScopedPlaybackKeys.deriveAccountKey(new byte[31], "acct") == null, "short secret account");
        check(ScopedPlaybackKeys.deriveAccountKey(new byte[33], "acct") == null, "long secret account");
        check(ScopedPlaybackKeys.deriveAccountKey(SECRET, null) == null, "null account");
        check(ScopedPlaybackKeys.deriveAccountKey(SECRET, "") == null, "empty account");
        check(ScopedPlaybackKeys.deriveAccountKey(SECRET, "PRIMORDIAL-acct") == null,
                "reserved prefix account");

        check(ScopedPlaybackKeys.deriveTrackKey(null, "acct", "track") == null, "null secret track");
        check(ScopedPlaybackKeys.deriveTrackKey(new byte[31], "acct", "track") == null,
                "short secret track");
        check(ScopedPlaybackKeys.deriveTrackKey(SECRET, null, "track") == null, "null track account");
        check(ScopedPlaybackKeys.deriveTrackKey(SECRET, "acct", null) == null, "null track");
        check(ScopedPlaybackKeys.deriveTrackKey(SECRET, "acct", "  ") == null, "blank track");
        check(ScopedPlaybackKeys.deriveTrackKey(SECRET, "PRIMORDIAL-acct", "track") == null,
                "reserved prefix track account");
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            builder.append(Character.forDigit(value & 0x0f, 16));
        }
        return builder.toString();
    }
}
