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
    private static final int OUTPUT_CAP = 262144;

    static Shell.Result run(String host, int port, String script, int timeoutMs) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), Math.min(timeoutMs, 3000));
            socket.setSoTimeout(Math.max(1000, timeoutMs));
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            send(out, A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
            Packet p = read(in);
            if (p.cmd == A_AUTH) return Shell.Result.failure("adbd on " + host + ":" + port + " wants a key (ro.adb.secure is on)");
            if (p.cmd != A_CNXN) return Shell.Result.failure("adbd on " + host + ":" + port + " did not connect");
            send(out, A_OPEN, 1, 0, ("shell:" + script + "; echo " + MARK + "$?\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            while (true) {
                p = read(in);
                if (p.cmd == A_OKAY) continue;
                if (p.cmd == A_WRTE) {
                    if (buffer.size() < OUTPUT_CAP) buffer.write(p.data, 0, Math.min(p.data.length, OUTPUT_CAP - buffer.size()));
                    send(out, A_OKAY, 1, p.arg0, null);
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
}
