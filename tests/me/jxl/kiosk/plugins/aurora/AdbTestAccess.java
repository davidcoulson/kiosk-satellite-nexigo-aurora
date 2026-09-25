// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.aurora;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Package-private doors into Adb for the test in the default package. */
public final class AdbTestAccess {
    private AdbTestAccess() {}
    public static final int A_CNXN = Adb.A_CNXN, A_OPEN = Adb.A_OPEN, A_OKAY = Adb.A_OKAY, A_CLSE = Adb.A_CLSE, A_WRTE = Adb.A_WRTE, VERSION = Adb.VERSION;
    public static final String MARK = Adb.MARK;
    public static final class Packet { public int cmd, arg0, arg1; public byte[] data; }
    public static final class Result { public final int exitCode; public final String stdout; public final String stderr; public final boolean timedOut;
        Result(Shell.Result r) { exitCode = r.exitCode; stdout = r.stdout; stderr = r.stderr; timedOut = r.timedOut; } public boolean ok() { return exitCode == 0 && !timedOut; } }
    public static Packet read(DataInputStream in) throws IOException { Adb.Packet p = Adb.read(in); Packet q = new Packet(); q.cmd = p.cmd; q.arg0 = p.arg0; q.arg1 = p.arg1; q.data = p.data; return q; }
    public static void send(DataOutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException { Adb.send(out, cmd, arg0, arg1, data); }
    public static Result run(int port, String script, int timeoutMs) { return new Result(Adb.run("127.0.0.1", port, script, timeoutMs)); }
}
