import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Mirrors the reflected field names of the native account-scoped cache (Laugy;). */
public final class augy {
    /** Reflected as "m": native eviction order. */
    public final LinkedHashSet<String> m = new LinkedHashSet<>();
    /** Reflected as "A": auxiliary (video/stream) state; must stay empty for retention. */
    public final Map<String, Object> A = new LinkedHashMap<>();
    /** Reflected as "n": decoded key to entry map. */
    public final Map<String, aufs> n = new LinkedHashMap<>();

    /** Adds one audio-only entry for {@code key} using every supported audio itag. */
    public augy audio(String key, int... itags) {
        Map<aufr, Object> formats = new LinkedHashMap<>();
        for (int itag : itags) formats.put(new aufr(itag), key + "/" + itag);
        n.put(key, new aufs(key, formats));
        m.add(key);
        return this;
    }

    /** Adds one entry whose only representation is a non-audio format. */
    public augy video(String key, int itag) {
        Map<aufr, Object> formats = new LinkedHashMap<>();
        formats.put(new aufr(itag), key + "/video");
        n.put(key, new aufs(key, formats));
        m.add(key);
        return this;
    }
}
