package app.morphe.extension.ym;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Narrow adapter for verified YouTube Music 9.30.53 Stats2 fields. Never reads credentials. */
public final class ScopedPlayHost {
    private static final Object LOCK = new Object();
    private static WeakReference<Object> pending = new WeakReference<>(null);
    private static Fields fields;
    private static Field managerClient, directorMain, directorState, controller;
    private static Method getManager;
    private static WeakReference<Object> admitted = new WeakReference<>(null);
    private static final ThreadLocal<Object> localSample = new ThreadLocal<>();

    private ScopedPlayHost() {}

    public static void fresh(Object client) {
        synchronized (LOCK) {
            ScopedPlaybackRecordingBridge.release(null);
            admitted.clear();
            pending = new WeakReference<>(client);
        }
    }

    public static void local(Object director, Object source) {
        localSample.remove();
        synchronized (LOCK) {
            if (director == null || source == null) return;
            try {
                if (directorMain == null) directorMain = director.getClass().getField("s");
                if (directorState == null) directorState = director.getClass().getField("t");
                Object state = directorState.get(director);
                if (!(state instanceof Enum<?>) || !"VIDEO_PLAYING".equals(((Enum<?>) state).name())) return;
                Object main = directorMain.get(director);
                if (main == null) return;
                if (controller == null) controller = main.getClass().getField("a");
                if (controller.get(main) != source) return;
                if (getManager == null) getManager = Class.forName("bcre", false,
                        director.getClass().getClassLoader()).getMethod("q");
                Object manager = getManager.invoke(source);
                if (manager == null || !"bbwy".equals(manager.getClass().getName())) return;
                if (managerClient == null) managerClient = manager.getClass().getField("b");
                Object client = managerClient.get(manager);
                if (client == null) return;
                if (admitted.get() == client) {
                    localSample.set(client);
                    return;
                }
                Object fresh = pending.get();
                if (client != fresh) return;
                pending.clear();
                if (fields == null) fields = new Fields(fresh.getClass());
                Object identity = fields.account.get(fresh);
                if (identity == null || !"agan".equals(identity.getClass().getName())) return;
                Method incognito = identity.getClass().getMethod("g");
                if (!Boolean.FALSE.equals(incognito.invoke(identity))) return;
                String account = (String) identity.getClass().getMethod("d").invoke(identity);
                if (!QualifiedPlayCounter.isValidId(account) || account.startsWith("PRIMORDIAL-")) return;
                ScopedPlaybackRecordingBridge.admit(fresh, account,
                        (String) fields.track.get(fresh), (String) fields.cpn.get(fresh));
                admitted = new WeakReference<>(fresh);
                localSample.set(fresh);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // Unsupported layouts or inaccessible fields never select an account.
            }
        }
    }

    public static void endLocal() {
        localSample.remove();
    }

    public static void sample(Object client) {
        if (client == null || localSample.get() != client) return;
        localSample.remove();
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
            if (admitted.get() == client) admitted.clear();
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
