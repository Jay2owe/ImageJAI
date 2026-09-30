package imagejai.engine;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds and stops the processes an owned child process started.
 *
 * <p>The plugin is compiled for Java 8 so it runs on older Fiji installs.
 * Java 9+ exposes the process tree through {@code ProcessHandle}, which is
 * reached here by reflection. Descendant handles are opaque {@code Object}s.
 * On Java 8 there are no handles; callers use
 * {@link #killDescendantsWithoutHandles(Process)} instead, which asks the
 * operating system to stop the tree by PID.
 */
final class ProcessTrees {
    private static final long LEGACY_KILL_WAIT_MS = 2000L;

    private static final Method TO_HANDLE = method(Process.class, "toHandle");
    private static final Method PROCESS_PID = method(Process.class, "pid");
    private static final Class<?> HANDLE = handleClass();
    private static final Method DESCENDANTS = method(HANDLE, "descendants");
    private static final Method PARENT = method(HANDLE, "parent");
    private static final Method IS_ALIVE = method(HANDLE, "isAlive");
    private static final Method DESTROY = method(HANDLE, "destroy");
    private static final Method DESTROY_FORCIBLY = method(HANDLE, "destroyForcibly");

    private ProcessTrees() {}

    /** True on Java 9+, where descendant processes can be listed directly. */
    static boolean handlesAvailable() {
        return TO_HANDLE != null && DESCENDANTS != null;
    }

    /** Live descendant handles of {@code process}; empty on Java 8 or failure. */
    static List<Object> descendants(Process process) {
        if (process == null || !handlesAvailable()) return new ArrayList<Object>();
        try {
            Object handle = TO_HANDLE.invoke(process);
            Stream<?> stream = (Stream<?>) DESCENDANTS.invoke(handle);
            return new ArrayList<Object>(stream.collect(Collectors.toList()));
        } catch (Throwable unavailable) {
            return new ArrayList<Object>();
        }
    }

    static boolean isAlive(Object handle) {
        return Boolean.TRUE.equals(call(IS_ALIVE, handle));
    }

    static void destroy(Object handle) {
        call(DESTROY, handle);
    }

    static void destroyForcibly(Object handle) {
        call(DESTROY_FORCIBLY, handle);
    }

    /** Number of ancestors above {@code handle}, so children can be stopped before parents. */
    static int ancestryDepth(Object handle) {
        int depth = 0;
        Object current = handle;
        // A defensive cap protects against a pathological platform implementation.
        while (depth < 1024 && current != null) {
            Object parent = call(PARENT, current);
            if (!(parent instanceof Optional) || !((Optional<?>) parent).isPresent()) break;
            current = ((Optional<?>) parent).get();
            depth++;
        }
        return depth;
    }

    /** Operating-system PID of {@code process}, or -1 when it cannot be read. */
    static long pid(Process process) {
        if (process == null) return -1L;
        Object pid = call(PROCESS_PID, process);
        if (pid instanceof Long) return ((Long) pid).longValue();
        // Java 8 on Unix: java.lang.UNIXProcess keeps the PID in a field.
        Object unixPid = field(process, "pid");
        if (unixPid instanceof Integer) return ((Integer) unixPid).longValue();
        // Java 8 on Windows: java.lang.ProcessImpl keeps a native handle.
        Object winHandle = field(process, "handle");
        if (winHandle instanceof Long) return windowsPid(((Long) winHandle).longValue());
        return -1L;
    }

    /**
     * Java 8 fallback: stop the processes {@code process} started, by PID.
     * On Windows this also stops {@code process} itself. Does nothing on Java
     * 9+, where callers stop each descendant handle instead.
     */
    static void killDescendantsWithoutHandles(Process process) {
        if (handlesAvailable() || process == null || !process.isAlive()) return;
        long pid = pid(process);
        if (pid <= 0L) return;
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win");
        List<String> command = windows
                ? java.util.Arrays.asList("taskkill", "/PID", Long.toString(pid), "/T", "/F")
                : java.util.Arrays.asList("pkill", "-KILL", "-P", Long.toString(pid));
        File sink = new File(windows ? "NUL" : "/dev/null");
        try {
            Process killer = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(sink))
                    .start();
            if (!killer.waitFor(LEGACY_KILL_WAIT_MS, TimeUnit.MILLISECONDS)) {
                killer.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
            // Best effort: the caller still stops the owned process itself.
        }
    }

    private static long windowsPid(long nativeHandle) {
        try {
            com.sun.jna.platform.win32.WinNT.HANDLE handle =
                    new com.sun.jna.platform.win32.WinNT.HANDLE(
                            com.sun.jna.Pointer.createConstant(nativeHandle));
            int pid = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetProcessId(handle);
            return pid > 0 ? pid : -1L;
        } catch (Throwable unavailable) {
            return -1L;
        }
    }

    private static Class<?> handleClass() {
        try {
            return Class.forName("java.lang.ProcessHandle");
        } catch (Throwable java8) {
            return null;
        }
    }

    private static Method method(Class<?> owner, String name) {
        if (owner == null) return null;
        try {
            return owner.getMethod(name);
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private static Object call(Method method, Object target) {
        if (method == null || target == null) return null;
        try {
            return method.invoke(target);
        } catch (Throwable failed) {
            return null;
        }
    }

    private static Object field(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException next) {
                // Try the superclass.
            } catch (Throwable inaccessible) {
                return null;
            }
        }
        return null;
    }

    /** Order handles so the deepest descendants come first. */
    static void sortDeepestFirst(List<Object> handles) {
        final List<Object> copy = new ArrayList<Object>(handles);
        final int[] depths = new int[copy.size()];
        for (int i = 0; i < copy.size(); i++) depths[i] = ancestryDepth(copy.get(i));
        List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < copy.size(); i++) order.add(i);
        Collections.sort(order, (a, b) -> Integer.compare(depths[b], depths[a]));
        handles.clear();
        for (Integer index : order) handles.add(copy.get(index));
    }
}
