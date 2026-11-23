package app.morphe.extension.ym;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Narrow adapter for verified YouTube Music 9.30.53 Stats2 fields. Never reads credentials. */
public final class ScopedPlayHost {
    private static final Object LOCK = new Object();
    private static WeakReference<Object> pending = new WeakReference<>(null);
    private static Fields fields;
    private static Field managerClient;

    private ScopedPlayHost() {}

    public static void fresh(Object client) {
        synchronized (LOCK) {
            ScopedPlaybackRecordingBridge.release(null);
            pending = new WeakReference<>(client);
        }
    }

    public static void local(Object manager) {
        synchronized (LOCK) {
            Object fresh = pending.get();
            pending.clear();
            if (fresh == null || manager == null) return;
            try {
                if (!"bbwy".equals(manager.getClass().getName())) return;
                if (managerClient == null) managerClient = manager.getClass().getField("b");
                if (managerClient.get(manager) != fresh) return;
                if (fields == null) fields = new Fields(fresh.getClass());
                Object identity = fields.account.get(fresh);
                if (identity == null || !"agan".equals(identity.getClass().getName())) return;
                Method incognito = identity.getClass().getMethod("g");
                if (!Boolean.FALSE.equals(incognito.invoke(identity))) return;
                String account = (String) identity.getClass().getMethod("d").invoke(identity);
                if (!QualifiedPlayCounter.isValidId(account) || account.startsWith("PRIMORDIAL-")) return;
                ScopedPlaybackRecordingBridge.admit(fresh, account,
                        (String) fields.track.get(fresh), (String) fields.cpn.get(fresh));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // Unsupported layouts or inaccessible fields never select an account.
            }
        }
    }

    public static void sample(Object client) {
        synchronized (LOCK) {
            if (fields == null || client == null || fields.owner != client.getClass()) return;
            try {
                ScopedPlaybackRecordingBridge.sample(client, fields.duration.getLong(client),
                        fields.listened.getLong(client));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // The original player callback still runs.
            }
        }
    }

    public static void release(Object client) {
        synchronized (LOCK) {
            if (pending.get() == client) pending.clear();
            ScopedPlaybackRecordingBridge.release(client);
        }
    }

    private static final class Fields {
        final Class<?> owner;
        final Field account, track, cpn, duration, listened;

        Fields(Class<?> owner) throws NoSuchFieldException {
            this.owner = owner;
            account = owner.getField("M");
            track = owner.getField("f");
            cpn = owner.getField("h");
            duration = owner.getField("F");
            listened = owner.getField("u");
        }
    }
}
