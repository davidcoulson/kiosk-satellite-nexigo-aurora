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
    private static final Set<Socket> OPEN = Collections.synchronizedSet(new HashSet<Socket>());

    /** Closes every exchange in progress; each returns a failure. */
    static void cancelAll() {
        Socket[] all;
        synchronized (OPEN) { all = OPEN.toArray(new Socket[0]); }
        for (Socket s : all) { try { s.close(); } catch (IOException ignored) { /* closing */ } }
    }

    /** timeoutMs is a deadline for the whole exchange, not for each read: a helper that keeps
     *  printing, or a peer that trickles bytes, still ends on time. */
    static Shell.Result run(String host, int port, String script, int timeoutMs) {
        long deadline = System.nanoTime() + Math.max(1, timeoutMs) * 1_000_000L;
        Socket socket = new Socket();
        OPEN.add(socket);
        try {
            socket.connect(new InetSocketAddress(host, port), Math.max(1, Math.min(timeoutMs, 3000)));
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            send(out, A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
            Packet p = read(in, socket, deadline);
            if (p.cmd == A_AUTH) return Shell.Result.failure("adbd on " + host + ":" + port + " wants a key (ro.adb.secure is on)");
            if (p.cmd != A_CNXN) return Shell.Result.failure("adbd on " + host + ":" + port + " did not connect");
            send(out, A_OPEN, LOCAL_ID, 0, (shellService(script) + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int remote = 0;
            while (true) {
                p = read(in, socket, deadline);
                // Only our stream counts; the remote id is learnt from its first OKAY.
                if (p.arg1 != LOCAL_ID) continue;
                if (p.cmd == A_OKAY) { if (remote == 0) remote = p.arg0; continue; }
                if (p.cmd == A_WRTE) {
                    if (remote != 0 && p.arg0 != remote) continue;
                    if (buffer.size() < OUTPUT_CAP) buffer.write(p.data, 0, Math.min(p.data.length, OUTPUT_CAP - buffer.size()));
                    send(out, A_OKAY, LOCAL_ID, p.arg0, null);
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
            return Shell.Result.failure("adbd on " + host + ":" + port + ": " + e.getMessage());
        } finally {
            OPEN.remove(socket);
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
    static Packet read(DataInputStream in, Socket socket, long deadline) throws IOException {
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
        // Newer adbd sends 0 (checksums are optional since protocol version 0x01000001).
        if (checksum != 0) {
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
