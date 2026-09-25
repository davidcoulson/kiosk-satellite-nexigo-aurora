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
        // Nothing listening: a failure, not a hang.
        me.jxl.kiosk.plugins.aurora.AdbTestAccess.Result none = AdbTestAccess.run(1, "id", 1500);
        assert !none.ok() && !none.stderr.isEmpty() : "a closed port fails with a reason";
        System.out.println("PASS: loopback ADB client - connect, open shell, collect output, exit code, closed port.");
    }
}
