// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.aurora;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * The two ways a script can reach the projector's Android from inside Kiosk Satellite.
 *
 * <p><b>Direct</b> runs {@code /system/bin/sh -c} from the kiosk's own process. On the Aurora
 * Pro's stock firmware SELinux is permissive and the vendor's {@code projector-test} is
 * world-executable, so the light engine, the {@code cur.*} properties and {@code am start} all
 * work from an ordinary app. What does not: {@code settings put} without
 * {@code WRITE_SECURE_SETTINGS} (one {@code adb shell pm grant} fixes that for good) and
 * {@code logcat} (needs the shell user), which is only the temperature readings.
 *
 * <p><b>Shizuku</b> runs the same script through the host as the shell user. It covers
 * everything, but a Shizuku started over ADB does not survive a power cycle, and this projector
 * cold-boots from standby, so it is the optional channel here rather than the required one.
 *
 * <p><b>ADB</b> is the projector's own adbd over the loopback address ({@link Adb}): the stock
 * firmware leaves port 5555 open with no key, so this is the shell user too, and it survives a
 * reboot. In Auto it is what reads the log behind a direct channel, and what starts Shizuku.
 *
 * <p>All return the same {@link Result}; the plugin never cares which one answered except to
 * say so in its status line.
 */
final class Shell {
    private Shell() {}

    /** Past the command's own timeout, so a host that never calls back releases the thread. */
    static final long HOST_CALLBACK_GRACE_MS = 5000L;
    /** Enough for a poll that runs the tool plus a logcat read; commands are far quicker. */
    static final int DEFAULT_TIMEOUT_MS = 8000;

    /** How a script is run. Tests substitute their own. */
    interface Runner {
        Result run(String script, int timeoutMs);

        /** The same for work that began at cancel epoch began (see {@link #cancelAll}): refused
         *  once a cancel has come since, even if it has not started a process or socket yet. */
        default Result run(String script, int timeoutMs, long began) { return run(script, timeoutMs); }
    }

    /** The cancel epoch now; a session records it when it starts. */
    static long epoch() { return Adb.epoch(); }

    static final class Result {
        final int exitCode;
        final String stdout;
        final String stderr;
        final boolean timedOut;

        Result(int exitCode, String stdout, String stderr, boolean timedOut) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.timedOut = timedOut;
        }

        boolean ok() { return exitCode == 0 && !timedOut; }

        /** One line for a status message: the first line of stderr, else stdout, else the code. */
        String why() {
            if (timedOut) return "timed out";
            String text = stderr.trim().isEmpty() ? stdout.trim() : stderr.trim();
            int nl = text.indexOf('\n');
            if (nl >= 0) text = text.substring(0, nl);
            return text.isEmpty() ? "exit code " + exitCode : text;
        }

        static Result failure(String why) { return new Result(-1, "", why, false); }
    }

    /** The first line a direct script prints: its shell's pid, for cleaning up after a timeout. */
    static final String PID_MARK = "__aurora_pid=";

    /** Direct scripts running now, by the pid their shell reported, so stop() can end them. */
    private static final Map<Process, Drain> RUNNING = Collections.synchronizedMap(new HashMap<Process, Drain>());

    /** {@code /system/bin/sh -c script} in this process. A script that runs past its time is
     *  killed with everything it started: destroying the shell alone left a projector-test blocked
     *  in the HAL behind it. */
    static final Runner DIRECT = new Runner() {
        @Override public Result run(String script, int timeoutMs) { return run(script, timeoutMs, epoch()); }

        @Override public Result run(String script, int timeoutMs, long began) {
            if (epoch() != began) return Result.failure("stopped");
            Process p;
            try {
                String body = "echo " + PID_MARK + "$$; " + script;
                // Its own session, so the shell's pid is the process group of everything it starts
                // (the HAL call in a poll is a grandchild, inside a pipeline in a $( )), and a
                // timeout can end the lot. A Java child is never a group leader, so setsid execs in
                // place rather than forking away from the Process this waits on.
                p = (SETSID ? new ProcessBuilder(SETSID_PATH, "/system/bin/sh", "-c", body) : new ProcessBuilder("/system/bin/sh", "-c", body)).start();
            } catch (IOException e) {
                return Result.failure("could not start a shell: " + e.getMessage());
            }
            Drain out = new Drain(p.getInputStream());
            Drain err = new Drain(p.getErrorStream());
            RUNNING.put(p, out);
            // A cancel that came while this was starting has not seen it: end it here.
            if (epoch() != began) { RUNNING.remove(p); kill(p, out); return Result.failure("stopped"); }
            try {
                try { p.getOutputStream().close(); } catch (IOException ignored) { /* nothing to write */ }
                out.start();
                err.start();
                boolean finished;
                try {
                    finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    kill(p, out);
                    return Result.failure("interrupted");
                }
                if (!finished) kill(p, out);
                try {
                    out.join(1000);
                    err.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new Result(finished ? p.exitValue() : -1, stripPid(out.text()), err.text(), !finished);
            } finally {
                RUNNING.remove(p);
                try { p.getInputStream().close(); } catch (IOException ignored) { /* closing */ }
                try { p.getErrorStream().close(); } catch (IOException ignored) { /* closing */ }
            }
        }
    };

    /** The script's output without the pid line it starts with. */
    static String stripPid(String text) {
        if (!text.startsWith(PID_MARK)) return text;
        int nl = text.indexOf('\n');
        return nl < 0 ? "" : text.substring(nl + 1);
    }

    /** The shell's pid from its first line, or 0. */
    static int pidOf(String text) {
        if (!text.startsWith(PID_MARK)) return 0;
        int nl = text.indexOf('\n');
        try { return Integer.parseInt(text.substring(PID_MARK.length(), nl < 0 ? text.length() : nl).trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    static final String SETSID_PATH = "/system/bin/setsid";
    private static final boolean SETSID = new java.io.File(SETSID_PATH).canExecute();

    /** What ends a timed-out script: its whole process group when it has its own session, else
     *  (no setsid) its direct children; then the shell itself. */
    static String killScript(int pid, boolean group) {
        return (group ? "kill -9 -- -" + pid + " 2>/dev/null; " : "") + "pkill -9 -P " + pid + " 2>/dev/null; true";
    }

    private static void kill(Process p, Drain out) {
        int pid = pidOf(out.text());
        if (pid > 0) {
            try {
                Process k = new ProcessBuilder("/system/bin/sh", "-c", killScript(pid, SETSID)).start();
                k.waitFor(2000, TimeUnit.MILLISECONDS);
                k.destroyForcibly();
            } catch (IOException | InterruptedException ignored) { /* the shell goes below regardless */ }
        }
        p.destroyForcibly();
    }

    /** Ends every direct script and ADB exchange in progress and refuses any that began before
     *  this call (the plugin is stopping). The epoch moves first, so a script registering as this
     *  runs either is in the snapshot or sees the new epoch and ends itself. */
    static void cancelAll() {
        Adb.cancelAll();
        Map.Entry<?, ?>[] all;
        synchronized (RUNNING) { all = RUNNING.entrySet().toArray(new Map.Entry<?, ?>[0]); }
        for (Map.Entry<?, ?> e : all) kill((Process) e.getKey(), (Drain) e.getValue());
    }

    /** Kiosk Satellite runs one Shizuku command per plugin at a time and wants 250 ms between
     *  starts (PluginShizuku.kt); a second one is refused, not queued. Every thread here (the
     *  worker, the vendor helper's) goes through this lock, one at a time, spaced out. */
    private static final java.util.concurrent.locks.ReentrantLock SHIZUKU = new java.util.concurrent.locks.ReentrantLock(true);
    private static long shizukuLastStartNanos;
    private static boolean shizukuStarted;
    /** The host refuses a start within 250 ms of the last one (System.nanoTime); 300 leaves room
     *  for the hop between this thread and its check (a CI runner once landed at 249 ms). */
    static final long SHIZUKU_SPACING_MS = 300;
    /** How long a command may wait its turn: behind the longest one there is (the helper's read). */
    static final long SHIZUKU_QUEUE_MS = 35000;

    /** The same script through Kiosk Satellite's Shizuku connection, as the shell user. */
    static Runner shizuku(final PluginHost host) {
        return new Runner() {
            @Override public Result run(String script, int timeoutMs) { return run(script, timeoutMs, epoch()); }

            @Override public Result run(String script, int timeoutMs, long began) {
                try {
                    if (!SHIZUKU.tryLock(SHIZUKU_QUEUE_MS, TimeUnit.MILLISECONDS)) return Result.failure("Shizuku busy: another command is still running");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Result.failure("interrupted");
                }
                try {
                    if (shizukuStarted) {
                        long waitMs = (shizukuLastStartNanos + SHIZUKU_SPACING_MS * 1_000_000L - System.nanoTime() + 999_999L) / 1_000_000L;
                        if (waitMs > 0) Thread.sleep(waitMs);
                    }
                    if (epoch() != began) return Result.failure("stopped");
                    shizukuLastStartNanos = System.nanoTime();
                    shizukuStarted = true;
                    return once(script, timeoutMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Result.failure("interrupted");
                } finally {
                    SHIZUKU.unlock();
                }
            }

            private Result once(String script, int timeoutMs) {
                final AtomicReference<Result> result = new AtomicReference<>();
                final CountDownLatch done = new CountDownLatch(1);
                try {
                    host.executeShizuku(new String[] {"/system/bin/sh", "-c", script}, timeoutMs,
                        new PluginHost.CommandCallback() {
                            @Override public void onResult(boolean ok, Object data, String error) {
                                try {
                                    if (ok && data instanceof Map) {
                                        Map<?, ?> value = (Map<?, ?>) data;
                                        Object exit = value.get("exitCode");
                                        int code = exit instanceof Number ? ((Number) exit).intValue() : -1;
                                        result.set(new Result(code, String.valueOf(value.get("stdout")),
                                            String.valueOf(value.get("stderr")), Boolean.TRUE.equals(value.get("timedOut"))));
                                    } else {
                                        result.set(Result.failure(error == null ? "Shizuku did not answer" : error));
                                    }
                                } finally {
                                    done.countDown();
                                }
                            }
                        });
                } catch (Throwable t) {
                    return Result.failure("Shizuku refused the command: " + t.getMessage());
                }
                try {
                    if (!done.await(timeoutMs + HOST_CALLBACK_GRACE_MS, TimeUnit.MILLISECONDS)) {
                        return Result.failure("Shizuku did not answer in time");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Result.failure("interrupted");
                }
                Result r = result.get();
                return r == null ? Result.failure("Shizuku did not answer") : r;
            }
        };
    }

    /** The script through the projector's own adbd on the loopback address, as the shell user. */
    static Runner adb(final int port) {
        return new Runner() {
            @Override public Result run(String script, int timeoutMs) { return Adb.run("127.0.0.1", port, script, timeoutMs, Adb.epoch()); }
            @Override public Result run(String script, int timeoutMs, long began) { return Adb.run("127.0.0.1", port, script, timeoutMs, began); }
        };
    }

    /** Whether the host has an authorized Shizuku backend right now. Never prompts. */
    static boolean shizukuGranted(PluginHost host) {
        try {
            Map<String, Object> state = host.shizukuState();
            return state != null && Boolean.TRUE.equals(state.get("granted"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Reads a stream to its end, keeping the first 64 KiB and discarding the rest, so a chatty
     *  child never blocks on a full pipe. */
    private static final class Drain extends Thread {
        private static final int CAP = 65536;
        private final InputStream in;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        Drain(InputStream in) {
            this.in = in;
            setDaemon(true);
        }

        @Override public void run() {
            byte[] chunk = new byte[4096];
            try {
                int n;
                while ((n = in.read(chunk)) >= 0) {
                    synchronized (buffer) { if (buffer.size() < CAP) buffer.write(chunk, 0, Math.min(n, CAP - buffer.size())); }
                }
            } catch (IOException ignored) {
                // the process ended or was destroyed; whatever was read is what we return
            }
        }

        String text() { synchronized (buffer) { return new String(buffer.toByteArray(), StandardCharsets.UTF_8); } }
    }
}
