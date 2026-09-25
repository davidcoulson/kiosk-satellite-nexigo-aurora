// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.aurora;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    }

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

    /** {@code /system/bin/sh -c script} in this process. */
    static final Runner DIRECT = new Runner() {
        @Override public Result run(String script, int timeoutMs) {
            Process p;
            try {
                p = new ProcessBuilder("/system/bin/sh", "-c", script).start();
            } catch (IOException e) {
                return Result.failure("could not start a shell: " + e.getMessage());
            }
            try {
                p.getOutputStream().close();
            } catch (IOException ignored) { /* nothing to write */ }
            Drain out = new Drain(p.getInputStream());
            Drain err = new Drain(p.getErrorStream());
            out.start();
            err.start();
            boolean finished;
            try {
                finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
                return Result.failure("interrupted");
            }
            if (!finished) p.destroyForcibly();
            try {
                out.join(1000);
                err.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Result(finished ? p.exitValue() : -1, out.text(), err.text(), !finished);
        }
    };

    /** The same script through Kiosk Satellite's Shizuku connection, as the shell user. */
    static Runner shizuku(final PluginHost host) {
        return new Runner() {
            @Override public Result run(String script, int timeoutMs) {
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
            @Override public Result run(String script, int timeoutMs) { return Adb.run("127.0.0.1", port, script, timeoutMs); }
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

    private static final class Drain extends Thread {
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
                while ((n = in.read(chunk)) >= 0 && buffer.size() < 65536) buffer.write(chunk, 0, n);
            } catch (IOException ignored) {
                // the process ended or was destroyed; whatever was read is what we return
            }
        }

        String text() { return new String(buffer.toByteArray(), StandardCharsets.UTF_8); }
    }
}
