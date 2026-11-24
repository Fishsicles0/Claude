package net.evadeclient.util;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Module;
import net.evadeclient.module.ModuleManager;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Guarded hooks: a mixin body that belongs to one module runs through here, so an exception in it switches that
 * module off ({@link ModuleManager#crashed}, with a "Turned off after an error" toast) instead of crashing the game.
 * <pre>
 *   Guard.run(NoFog.class, "fog", () -> { ...the hook's body... });
 *   boolean x = Guard.test(Zoom.class, "fov", () -> ..., false);
 * </pre>
 * The module is looked up only when something went wrong, so the normal path costs one lambda call. Errors that
 * mean the JVM itself is in trouble ({@link VirtualMachineError}: out of memory, stack overflow) are not caught.
 * A module that already is off and still throws (a body that runs whatever the module's state) is logged once and
 * kept quiet after that, never toasted every frame.
 */
public final class Guard {
    private Guard() {}

    /** "Module (where)" for every failure caught here since launch (tests read it; the suspect for Safe Start). */
    public static final Set<String> CAUGHT = ConcurrentHashMap.newKeySet();
    /** Guards that failed while their module was already off: logged once each. */
    private static final Set<String> QUIET = ConcurrentHashMap.newKeySet();

    /** Runs {@code body}; on an exception, switches {@code owner} off. */
    public static void run(Class<? extends Module> owner, String where, Runnable body) {
        try {
            body.run();
        } catch (Exception | LinkageError e) {
            failed(owner, where, e);
        }
    }

    /** {@code body}'s answer, or {@code fallback} after switching {@code owner} off when it throws. */
    public static boolean test(Class<? extends Module> owner, String where, BooleanSupplier body, boolean fallback) {
        try {
            return body.getAsBoolean();
        } catch (Exception | LinkageError e) {
            failed(owner, where, e);
            return fallback;
        }
    }

    /** {@code body}'s value, or {@code fallback} after switching {@code owner} off when it throws. */
    public static <T> T get(Class<? extends Module> owner, String where, Supplier<T> body, T fallback) {
        try {
            return body.get();
        } catch (Exception | LinkageError e) {
            failed(owner, where, e);
            return fallback;
        }
    }

    /** A float-returning body (no boxing on hot render paths). */
    @FunctionalInterface public interface FloatBody { float get(); }

    /** An int-returning body (no boxing on hot render paths). */
    @FunctionalInterface public interface IntBody { int get(); }

    /** {@code body}'s value, or {@code fallback} after switching {@code owner} off when it throws. */
    public static float getFloat(Class<? extends Module> owner, String where, FloatBody body, float fallback) {
        try {
            return body.get();
        } catch (Exception | LinkageError e) {
            failed(owner, where, e);
            return fallback;
        }
    }

    /** {@code body}'s value, or {@code fallback} after switching {@code owner} off when it throws. */
    public static int getInt(Class<? extends Module> owner, String where, IntBody body, int fallback) {
        try {
            return body.get();
        } catch (Exception | LinkageError e) {
            failed(owner, where, e);
            return fallback;
        }
    }

    /** What a guard does with a failure (public for tests and for guards written by hand). */
    public static void failed(Class<? extends Module> owner, String where, Throwable e) {
        Module m = null;
        try {
            if (EvadeClient.modules != null) m = EvadeClient.modules.get(owner);
        } catch (RuntimeException ignored) { }
        String name = m != null ? m.getName() : owner.getSimpleName();
        String tag = name + " (" + where + ")";
        CAUGHT.add(tag);
        if (m != null && m.isEnabled()) {
            ModuleManager.crashed(m, e, where);
            return;
        }
        if (QUIET.add(tag)) EvadeClient.LOG.warn("Evade hook {} failed while {} is off; ignoring it from now on", where, name, e);
    }
}
