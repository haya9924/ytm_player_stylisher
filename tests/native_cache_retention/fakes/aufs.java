import java.util.Map;

/** Mirrors the reflected entry fields of the native cache (Laufs;). */
public final class aufs {
    /** Reflected as "c": the decoded document id. */
    public final String c;
    /** Reflected as "f": representation to format map. */
    public final Map<aufr, Object> f;
    /** Reflected as "a": stored size in bytes; 0 when the fake does not model sizes. */
    public long size;

    /** Reflected as "a": the stored size in bytes. */
    public long a() {
        return size;
    }

    public aufs(String id, Map<aufr, Object> formats) {
        this.c = id;
        this.f = formats;
    }
}
