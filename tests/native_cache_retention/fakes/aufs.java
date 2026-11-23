import java.util.Map;

/** Mirrors the reflected entry fields of the native cache (Laufs;). */
public final class aufs {
    /** Reflected as "c": the decoded document id. */
    public final String c;
    /** Reflected as "f": representation to format map. */
    public final Map<aufr, Object> f;

    public aufs(String id, Map<aufr, Object> formats) {
        this.c = id;
        this.f = formats;
    }
}
