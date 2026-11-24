package app.morphe.test.prefetch;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Emulator smoke test for the playback-priority prefetch admission hook.
 *
 * <p>Runs inside the patched application process. Only the documented host entry
 * points are reached through reflection on the target class loader; the admission
 * method is invoked twice (suppressed and admitted) and the opt-in preference is
 * always restored in a finally block.
 */
public final class SmokeInstrumentation extends Instrumentation {
    private static final String PREFS = "ym_morphe_settings";
    private static final String ENABLED = "playback_network_priority";
    private static final String PASS = "PREFETCH_SMOKE_PASS";
    private static final String FAIL = "PREFETCH_SMOKE_FAIL";
    private static final String POLICY = "app.morphe.extension.ym.PlaybackNetworkPolicy";
    private static final String FUTURE = "com.google.common.util.concurrent.ListenableFuture";

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        int resultCode = Activity.RESULT_CANCELED;
        String stream;
        try {
            runSmoke();
            resultCode = Activity.RESULT_OK;
            stream = PASS + ": differing outer indices suppressed (handler calls 0); "
                    + "terminal owner admitted (handler calls 1)";
        } catch (Throwable failure) {
            stream = FAIL + "\n" + stackTrace(failure);
        }
        Bundle results = new Bundle();
        results.putString(REPORT_KEY_STREAMRESULT, stream);
        finish(resultCode, results);
    }

    private void runSmoke() throws Exception {
        Context target = getTargetContext();
        ClassLoader loader = target.getClassLoader();
        SharedPreferences preferences = target.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean hadValue = preferences.contains(ENABLED);
        boolean previous = preferences.getBoolean(ENABLED, false);

        Class<?> policy = load(loader, POLICY);
        Method setEnabled = access(policy.getDeclaredMethod("setEnabled", Context.class, boolean.class));
        Method onUrgent = access(policy.getDeclaredMethod("onUrgent", Object.class));
        Method onTerminal = access(policy.getDeclaredMethod("onTerminal", Object.class));

        Class<?> bbio = load(loader, "bbio");
        Class<?> bbip = load(loader, "bbip");
        Class<?> bbir = load(loader, "bbir");
        Class<?> bbil = load(loader, "bbil");
        Class<?> bbiq = load(loader, "bbiq");
        Class<?> futureInterface = Class.forName(FUTURE, false, loader);

        AtomicInteger handled = new AtomicInteger();
        Object futureProxy = Proxy.newProxyInstance(loader, new Class<?>[]{futureInterface},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
        Object transportProxy = Proxy.newProxyInstance(loader, new Class<?>[]{bbio},
                (proxy, method, args) -> "d".equals(method.getName())
                        ? Object.class
                        : defaultValue(method.getReturnType()));
        Object handlerProxy = Proxy.newProxyInstance(loader, new Class<?>[]{bbip},
                (proxy, method, args) -> {
                    if ("a".equals(method.getName())
                            && method.getReturnType().isAssignableFrom(futureInterface)) {
                        handled.incrementAndGet();
                        return futureProxy;
                    }
                    return defaultValue(method.getReturnType());
                });

        HashMap<Object, Object> handlers = new HashMap<>();
        handlers.put(Object.class, handlerProxy);
        Object owner = newAdmission(bbir, loader, handlers);
        Method admit = access(bbir.getDeclaredMethod("a", bbiq, bbil, bbio, boolean.class));

        Object current = newOuterIndex(bbil, 1);
        Object candidate = newCandidate(bbiq, bbil, bbio, loader, transportProxy, 2);

        try {
            setEnabled.invoke(null, target, true);
            onUrgent.invoke(null, owner);

            Object suppressed = admit.invoke(owner, candidate, current, transportProxy, false);
            check(!isPresent(suppressed), "differing outer indices must return an empty Optional");
            check(handled.get() == 0, "handler must not run while suppressed, calls=" + handled.get());

            onTerminal.invoke(null, owner);
            Object admitted = admit.invoke(owner, candidate, current, transportProxy, false);
            check(isPresent(admitted), "terminal owner must admit the prefetch again");
            check(handled.get() == 1, "handler must run once after terminal, calls=" + handled.get());
        } finally {
            try {
                onTerminal.invoke(null, owner);
            } catch (Throwable ignored) {
                // Best-effort release; the preference restore below still runs.
            }
            SharedPreferences.Editor editor = preferences.edit();
            if (hadValue) {
                editor.putBoolean(ENABLED, previous);
            } else {
                editor.remove(ENABLED);
            }
            editor.commit();
        }
    }

    private static Object newAdmission(Class<?> type, ClassLoader loader,
                                       Map<Object, Object> handlers) throws Exception {
        Throwable last = null;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            Object[] arguments = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                Class<?> parameter = parameters[i];
                if (Map.class.isAssignableFrom(parameter) || parameter == Object.class) {
                    arguments[i] = handlers;
                } else if (parameter == int.class || parameter == Integer.class) {
                    arguments[i] = 0;
                } else if (parameter == boolean.class || parameter == Boolean.class) {
                    arguments[i] = false;
                } else if (parameter.isInterface()) {
                    arguments[i] = proxyFor(parameter, loader);
                } else {
                    arguments[i] = null;
                }
            }
            try {
                constructor.setAccessible(true);
                return constructor.newInstance(arguments);
            } catch (Throwable failure) {
                last = failure;
            }
        }
        throw new IllegalStateException("No usable constructor for " + type.getName(), last);
    }

    private static Object newOuterIndex(Class<?> type, int index) throws Exception {
        Throwable last = null;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            Object[] arguments = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                arguments[i] = parameters[i].isPrimitive()
                        ? (parameters[i] == int.class ? (Object) index : defaultValue(parameters[i]))
                        : null;
            }
            try {
                constructor.setAccessible(true);
                return constructor.newInstance(arguments);
            } catch (Throwable failure) {
                last = failure;
            }
        }
        throw new IllegalStateException("No usable constructor for " + type.getName(), last);
    }

    private static Object newCandidate(Class<?> type, Class<?> outerType, Class<?> transportType,
                                       ClassLoader loader, Object transport, int index)
            throws Exception {
        Throwable last = null;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            Object[] arguments = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                Class<?> parameter = parameters[i];
                if (parameter == outerType) {
                    arguments[i] = newOuterIndex(outerType, index);
                } else if (parameter == transportType) {
                    arguments[i] = transport;
                } else if (parameter == int.class || parameter == Integer.class) {
                    arguments[i] = index;
                } else if (parameter == boolean.class || parameter == Boolean.class) {
                    arguments[i] = false;
                } else if (parameter.isInterface()) {
                    arguments[i] = proxyFor(parameter, loader);
                } else {
                    arguments[i] = null;
                }
            }
            try {
                constructor.setAccessible(true);
                return constructor.newInstance(arguments);
            } catch (Throwable failure) {
                last = failure;
            }
        }
        throw new IllegalStateException("No usable constructor for " + type.getName(), last);
    }

    private static Object proxyFor(Class<?> type, ClassLoader loader) {
        return Proxy.newProxyInstance(loader, new Class<?>[]{type},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return Boolean.FALSE;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    private static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static Method access(Method method) {
        method.setAccessible(true);
        return method;
    }

    private static boolean isPresent(Object optional) throws Exception {
        return (Boolean) optional.getClass().getMethod("isPresent").invoke(optional);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String stackTrace(Throwable failure) {
        StringWriter writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
