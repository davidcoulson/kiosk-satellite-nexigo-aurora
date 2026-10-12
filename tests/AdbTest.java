// SPDX-License-Identifier: Apache-2.0
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import me.jxl.kiosk.plugins.aurora.AdbTestAccess;

/** A fake adbd on the loopback address, speaking just enough of the protocol to answer a shell. */
public final class AdbTest {
    public static void main(String[] args) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            final int port = server.getLocalPort();
            final String[] opened = new String[1];
            Thread fake = new Thread(new Runnable() { @Override public void run() {
                try (Socket s = server.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    AdbTestAccess.Packet p = AdbTestAccess.read(in);
                    assert p.cmd == AdbTestAccess.A_CNXN : "first packet is CNXN";
                    AdbTestAccess.send(out, AdbTestAccess.A_CNXN, AdbTestAccess.VERSION, 4096, "device::\0".getBytes(StandardCharsets.US_ASCII));
                    p = AdbTestAccess.read(in);
                    assert p.cmd == AdbTestAccess.A_OPEN : "then OPEN";
                    opened[0] = new String(p.data, StandardCharsets.UTF_8);
                    AdbTestAccess.send(out, AdbTestAccess.A_OKAY, 7, p.arg0, null);
                    AdbTestAccess.send(out, AdbTestAccess.A_WRTE, 7, p.arg0, "uid=2000(shell) gid=2000(shell)\r\n".getBytes(StandardCharsets.UTF_8));
                    p = AdbTestAccess.read(in);
                    assert p.cmd == AdbTestAccess.A_OKAY : "each WRTE is acknowledged";
                    AdbTestAccess.send(out, AdbTestAccess.A_WRTE, 7, 1, (AdbTestAccess.MARK + "3\r\n").getBytes(StandardCharsets.UTF_8));
                    AdbTestAccess.read(in);
                    AdbTestAccess.send(out, AdbTestAccess.A_CLSE, 7, 1, null);
                } catch (Exception e) { throw new RuntimeException(e); }
            } });
            fake.setDaemon(true);
            fake.start();
            me.jxl.kiosk.plugins.aurora.AdbTestAccess.Result r = AdbTestAccess.run(port, "id", 4000);
            fake.join(5000);
            assert opened[0].startsWith("shell:id; echo " + AdbTestAccess.MARK) : "the shell service with the exit-code mark: " + opened[0];
            assert r.exitCode == 3 : "exit code read from the mark, got " + r.exitCode;
            assert r.stdout.equals("uid=2000(shell) gid=2000(shell)\n") : "output with CRLF folded: " + r.stdout;
        }
        // The deadline covers the whole exchange: a peer that keeps trickling bytes still ends on
        // time (each read used to get the full timeout again, so this never ended).
        {
            long t0 = System.currentTimeMillis();
            AdbTestAccess.Result r = serve(new Peer() { public void talk(DataInputStream in, DataOutputStream out) throws Exception {
                handshake(in, out);
                byte[] w = packet(AdbTestAccess.A_WRTE, 7, 1, "x".getBytes(StandardCharsets.UTF_8));
                for (int i = 0; i < 400; i++) { for (byte b : w) { out.write(b); out.flush(); Thread.sleep(5); } AdbTestAccess.read(in); }
            } }, 1500);
            long took = System.currentTimeMillis() - t0;
            assert r.timedOut && took < 3500 : "a trickling peer is cut off at the deadline: " + took + " ms, timedOut " + r.timedOut;
        }
        // AUTH (a secured adbd) is a clear refusal, not a hang.
        {
            AdbTestAccess.Result r = serve(new Peer() { public void talk(DataInputStream in, DataOutputStream out) throws Exception {
                AdbTestAccess.read(in);
                AdbTestAccess.send(out, AdbTestAccess.A_AUTH, 1, 0, new byte[20]);
                Thread.sleep(500);
            } }, 3000);
            assert !r.ok() && r.stderr.contains("wants a key") : "AUTH: " + r.stderr;
        }
        // A corrupt header (bad magic) is an error, not a read of garbage.
        {
            AdbTestAccess.Result r = serve(new Peer() { public void talk(DataInputStream in, DataOutputStream out) throws Exception {
                AdbTestAccess.read(in);
                byte[] bad = packet(AdbTestAccess.A_CNXN, AdbTestAccess.VERSION, 4096, "device::\0".getBytes(StandardCharsets.US_ASCII));
                bad[20] ^= 0x55;
                out.write(bad); out.flush();
                Thread.sleep(500);
            } }, 3000);
            assert !r.ok() && r.stderr.contains("magic") : "bad magic: " + r.stderr;
        }
        // Packets split across many small writes, and a stray stream's output ignored.
        {
            AdbTestAccess.Result r = serve(new Peer() { public void talk(DataInputStream in, DataOutputStream out) throws Exception {
                handshake(in, out);
                AdbTestAccess.send(out, AdbTestAccess.A_WRTE, 9, 5, "not ours\n".getBytes(StandardCharsets.UTF_8));
                byte[] w = packet(AdbTestAccess.A_WRTE, 7, 1, ("fragmented\n" + AdbTestAccess.MARK + "0\n").getBytes(StandardCharsets.UTF_8));
                for (int i = 0; i < w.length; i += 3) { out.write(w, i, Math.min(3, w.length - i)); out.flush(); }
                AdbTestAccess.read(in);
                AdbTestAccess.send(out, AdbTestAccess.A_CLSE, 7, 1, null);
            } }, 3000);
            assert r.ok() && r.stdout.equals("fragmented\n") : "fragments joined, the stray stream ignored: " + r.stdout + " / " + r.stderr;
        }
        // Stopping the plugin cuts an exchange short at once.
        {
            final AdbTestAccess.Result[] out = new AdbTestAccess.Result[1];
            final long[] took = new long[1];
            try (final ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
                final int port = server.getLocalPort();
                Thread fake = new Thread(new Runnable() { public void run() {
                    try (Socket s = server.accept()) { handshake(new DataInputStream(s.getInputStream()), new DataOutputStream(s.getOutputStream())); Thread.sleep(10000); }
                    catch (Exception ignored) { /* cut off */ }
                } });
                fake.setDaemon(true);
                fake.start();
                Thread client = new Thread(new Runnable() { public void run() {
                    long t0 = System.currentTimeMillis();
                    out[0] = AdbTestAccess.run(port, "sleep 60", 30000);
                    took[0] = System.currentTimeMillis() - t0;
                } });
                client.start();
                Thread.sleep(500);
                AdbTestAccess.cancelAll();
                client.join(3000);
                assert out[0] != null && !out[0].ok() && took[0] < 2500 : "cancelled: " + (out[0] == null ? "still running" : out[0].stderr + " after " + took[0] + " ms");
            }
        }
        // Nothing listening: a failure, not a hang.
        me.jxl.kiosk.plugins.aurora.AdbTestAccess.Result none = AdbTestAccess.run(1, "id", 1500);
        assert !none.ok() && !none.stderr.isEmpty() : "a closed port fails with a reason";
        System.out.println("PASS: loopback ADB client - connect, open shell, collect output, exit code, closed port, whole-exchange deadline, AUTH, bad magic, fragments, stray streams, cancellation.");
    }

    interface Peer { void talk(DataInputStream in, DataOutputStream out) throws Exception; }

    /** One exchange against a fake adbd that behaves as told. */
    static AdbTestAccess.Result serve(final Peer peer, int timeoutMs) throws Exception {
        try (final ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            Thread fake = new Thread(new Runnable() { public void run() {
                try (Socket s = server.accept()) { peer.talk(new DataInputStream(s.getInputStream()), new DataOutputStream(s.getOutputStream())); }
                catch (Exception ignored) { /* the client hung up */ }
            } });
            fake.setDaemon(true);
            fake.start();
            return AdbTestAccess.run(server.getLocalPort(), "id", timeoutMs);
        }
    }

    /** CNXN both ways, then OPEN answered with OKAY from remote stream 7. */
    static void handshake(DataInputStream in, DataOutputStream out) throws Exception {
        AdbTestAccess.read(in);
        AdbTestAccess.send(out, AdbTestAccess.A_CNXN, AdbTestAccess.VERSION, 4096, "device::\0".getBytes(StandardCharsets.US_ASCII));
        AdbTestAccess.Packet p = AdbTestAccess.read(in);
        AdbTestAccess.send(out, AdbTestAccess.A_OKAY, 7, p.arg0, null);
    }

    /** A packet's bytes, as send() would write them. */
    static byte[] packet(int cmd, int arg0, int arg1, byte[] data) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        AdbTestAccess.send(new DataOutputStream(b), cmd, arg0, arg1, data);
        return b.toByteArray();
    }
}
