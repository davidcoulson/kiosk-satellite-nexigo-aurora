// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.aurora;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The smallest ADB client that will do: a shell command over TCP to the projector's own adbd.
 *
 * <p>The Aurora Pro's firmware runs adbd on port 5555 with {@code ro.adb.secure=0}, so a
 * connection from the device itself needs no key: CNXN, OPEN {@code shell:...}, collect the
 * WRTE packets, done. That gives the kiosk process the shell user's rights (the log, the settings
 * command) without Shizuku, and lets it start Shizuku for anything else after a reboot.
 *
 * <p>Wire format (all little-endian): six 32-bit words - command, arg0, arg1, data length, data
 * checksum (the bytes summed), magic (command XOR 0xffffffff) - then the data.
 */
final class Adb {
    private Adb() {}

    static final int A_CNXN = 0x4e584e43;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;
    static final int A_AUTH = 0x48545541;
    static final int VERSION = 0x01000000;
    static final int MAX_DATA = 256 * 1024;
    /** The shell service carries no exit code of its own, so the script reports it on the last line. */
    static final String MARK = "__aurora_rc=";

    /** The shell service line: the script, then the exit-code marker. A script that already ends
     *  in ';' would make ';;', which the projector's shell rejects as a syntax error (0.3.3 lost
     *  its temperatures to exactly that), so trailing separators are dropped first. */
    static String shellService(String script) {
        return "shell:" + script.replaceAll("[;\\s]+$", "") + "; echo " + MARK + "$?";
    }
    private static final int OUTPUT_CAP = 262144;
    /** Our end of the one stream each session opens. */
    private static final int LOCAL_ID = 1;

    /** Sockets in use, so stopping the plugin can cut every exchange short (a blocked read does
     *  not notice a thread interrupt; a closed socket ends it at once). */
    private static final Set<Socket> OPEN = new HashSet<>();
    /** Bumped by cancelAll: an exchange that began before it may not open a socket after it. */
    private static long epoch;

    /** Closes every exchange in progress, and refuses any that began before this call but had
     *  not opened its socket yet; each returns a failure. */
    static void cancelAll() {
        Socket[] all;
        synchronized (OPEN) { epoch++; all = OPEN.toArray(new Socket[0]); }
        for (Socket s : all) { try { s.close(); } catch (IOException ignored) { /* closing */ } }
    }

    static long epoch() { synchronized (OPEN) { return epoch; } }

    /** Closes a socket at the deadline: SO_TIMEOUT bounds reads only, and a peer that stops
     *  reading can leave a write blocked for good. */
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "nexigo-aurora-adb-deadline"); t.setDaemon(true); return t; }
    });

    /** timeoutMs is a deadline for the whole exchange, not for each read: a helper that keeps
     *  printing, or a peer that trickles bytes, still ends on time. */
    static Shell.Result run(String host, int port, String script, int timeoutMs) { return run(host, port, script, timeoutMs, epoch()); }

    /** began is cancelAll's epoch when the caller's work began (Shell.adb passes it). */
    static Shell.Result run(String host, int port, String script, int timeoutMs, long began) {
        long deadline = System.nanoTime() + Math.max(1, timeoutMs) * 1_000_000L;
        final Socket socket = new Socket();
        synchronized (OPEN) {
            if (epoch != began) return Shell.Result.failure("stopped");
            OPEN.add(socket);
        }
        final AtomicBoolean expired = new AtomicBoolean();
        ScheduledFuture<?> guard = TIMER.schedule(new Runnable() { @Override public void run() {
            expired.set(true);
            try { socket.close(); } catch (IOException ignored) { /* closing */ }
        } }, Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        try {
            socket.connect(new InetSocketAddress(host, port), Math.max(1, Math.min(timeoutMs, 3000)));
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            send(out, A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
            Packet p = read(in, socket, deadline, true);
            if (p.cmd == A_AUTH) return Shell.Result.failure("adbd on " + host + ":" + port + " wants a key (ro.adb.secure is on)");
            if (p.cmd != A_CNXN) return Shell.Result.failure("adbd on " + host + ":" + port + " did not connect");
            // Checksums are optional only once both ends speak 0x01000001; this client offers
            // 0x01000000, so a real adbd always sends them and every packet is checked.
            boolean checksums = Math.min(VERSION, p.arg0) < 0x01000001;
            send(out, A_OPEN, LOCAL_ID, 0, (shellService(script) + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int remote = 0;
            while (true) {
                p = read(in, socket, deadline, checksums);
                // Only our stream: OKAY then WRTEs from the one remote id it names, CLSE from it
                // (or, before any OKAY, the CLSE that refuses the open).
                if (p.arg1 != LOCAL_ID) continue;
                if (remote == 0) {
                    if (p.cmd == A_OKAY) { remote = p.arg0; continue; }
                    if (p.cmd == A_CLSE) return Shell.Result.failure("adbd on " + host + ":" + port + " refused the shell");
                    continue;
                }
                if (p.arg0 != remote) continue;
                if (p.cmd == A_OKAY) continue;
                if (p.cmd == A_WRTE) {
                    if (buffer.size() < OUTPUT_CAP) buffer.write(p.data, 0, Math.min(p.data.length, OUTPUT_CAP - buffer.size()));
                    send(out, A_OKAY, LOCAL_ID, remote, null);
                    continue;
                }
                if (p.cmd == A_CLSE) break;
            }
            String text = new String(buffer.toByteArray(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            int at = text.lastIndexOf(MARK);
            int code = -1;
            if (at >= 0) {
                String tail = text.substring(at + MARK.length()).trim();
                try { code = Integer.parseInt(tail); } catch (NumberFormatException ignored) { /* stays -1 */ }
                text = text.substring(0, at);
            }
            return new Shell.Result(code, text, "", false);
        } catch (SocketTimeoutException e) {
            return new Shell.Result(-1, "", "timed out", true);
        } catch (IOException e) {
            if (expired.get()) return new Shell.Result(-1, "", "timed out", true);
            return Shell.Result.failure("adbd on " + host + ":" + port + ": " + e.getMessage());
        } finally {
            guard.cancel(false);
            synchronized (OPEN) { OPEN.remove(socket); }
            try { socket.close(); } catch (IOException ignored) { /* closing */ }
        }
    }

    static final class Packet {
        int cmd; int arg0; int arg1; byte[] data;
    }

    static void send(DataOutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        byte[] body = data == null ? new byte[0] : data;
        long sum = 0;
        for (byte b : body) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(body.length).putInt((int) (sum & 0xffffffffL)).putInt(cmd ^ 0xffffffff);
        out.write(h.array());
        out.write(body);
        out.flush();
    }

    /** One packet, with what is left of the deadline as the socket's timeout before each piece. */
    static Packet read(DataInputStream in, Socket socket, long deadline, boolean checksums) throws IOException {
        byte[] head = new byte[24];
        readFully(in, head, socket, deadline);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Packet p = new Packet();
        p.cmd = h.getInt(); p.arg0 = h.getInt(); p.arg1 = h.getInt();
        int length = h.getInt();
        int checksum = h.getInt();
        int magic = h.getInt();
        if (magic != (p.cmd ^ 0xffffffff)) throw new IOException("bad packet magic");
        if (length < 0 || length > MAX_DATA) throw new IOException("bad packet length " + length);
        p.data = new byte[length];
        readFully(in, p.data, socket, deadline);
        if (checksums) {
            long sum = 0;
            for (byte b : p.data) sum += b & 0xff;
            if ((int) (sum & 0xffffffffL) != checksum) throw new IOException("bad packet checksum");
        }
        return p;
    }

    /** For tests and simple callers: no deadline beyond the socket's own timeout. */
    static Packet read(DataInputStream in) throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Packet p = new Packet();
        p.cmd = h.getInt(); p.arg0 = h.getInt(); p.arg1 = h.getInt();
        int length = h.getInt();
        if (length < 0 || length > MAX_DATA) throw new IOException("bad packet length " + length);
        p.data = new byte[length];
        in.readFully(p.data);
        return p;
    }

    private static void readFully(DataInputStream in, byte[] buf, Socket socket, long deadline) throws IOException {
        for (int off = 0; off < buf.length; ) {
            long left = (deadline - System.nanoTime()) / 1_000_000L;
            if (left <= 0) throw new SocketTimeoutException("deadline passed");
            socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, left));
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new java.io.EOFException("adbd closed the connection");
            off += n;
        }
    }
}
