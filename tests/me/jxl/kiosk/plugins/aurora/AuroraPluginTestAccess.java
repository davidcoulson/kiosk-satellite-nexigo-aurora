// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.aurora;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.jxl.kiosk.plugins.PluginHost;

/** The package-private half of the test: fakes for the host and the shell, and the checks. */
public final class AuroraPluginTestAccess {
    private AuroraPluginTestAccess() {}

    static final String POLL_ANSWER =
        "light=true\nscreenoff=false\nsource=6\nmode=8\nminutes=1530\n"
        + "temps=AT+Temperature#NtcRedLaser1:28,NtcGreenLaser1:25,NtcBlueLaser1:34,NtcCw1:33,NtcDmd1:37,NtcEnv1:23\nleds=0\n"
        + "boot=5\ncec=true\nsleep=0\nnosignal=0\nwdt=40\n";

    /** Records every script and answers the poll with a canned projector. */
    static final class FakeShell implements Shell.Runner {
        final List<String> scripts = Collections.synchronizedList(new ArrayList<String>());
        volatile String pollAnswer = POLL_ANSWER;
        volatile boolean probeOk = true;

        @Override public Shell.Result run(String script, int timeoutMs) {
            scripts.add(script);
            if (script.equals(Projector.PROBE_SCRIPT)) return probeOk ? new Shell.Result(0, "AURORA PRO\n", "", false) : new Shell.Result(1, "", "denied", false);
            if (script.equals(Projector.POLL_SCRIPT)) return new Shell.Result(0, pollAnswer, "", false);
            return new Shell.Result(0, "", "", false);
        }
    }

    /** Remembers the last state published per entity key, plus the status line. */
    static final class FakeHost implements PluginHost {
        final Map<String, Object> switches = new LinkedHashMap<>();
        final Map<String, Object> selects = new LinkedHashMap<>();
        final Map<String, Object> sensors = new LinkedHashMap<>();
        final Map<String, Object> binary = new LinkedHashMap<>();
        volatile String status = "";
        volatile boolean statusError;
        volatile boolean shizukuGranted;

        @Override public void showWindow(String title, String message, String buttonLabel) {}
        @Override public void hideWindow() {}
        @Override public void log(String message) {}
        @Override public void status(String message, boolean error) { status = message; statusError = error; }
        @Override public void publishSwitch(String key, String name, boolean state) { switches.put(key, state); }
        @Override public void publishSelect(String key, String name, String[] options, String state) { selects.put(key, state); }
        @Override public void publishSensor(String key, String name, Map<String, Object> metadata, Double state) { sensors.put(key, state); }
        @Override public void publishBinarySensor(String key, String name, String deviceClass, Boolean state) { binary.put(key, state); }
        @Override public void publishTextSensor(String key, String name, String state) {}
        @Override public Map<String, Object> shizukuState() {
            Map<String, Object> m = new HashMap<>();
            m.put("granted", shizukuGranted);
            m.put("uid", 2000);
            return m;
        }
    }

    public static void run() throws Exception {
        parsing();
        publication();
        commands();
        observed();
        counter();
        guard();
        framework();
        fallback();
        loopback();
    }

    /** Answers like the projector's own adbd: the shell user, and the log line. */
    static class FakeAdb implements Shell.Runner {
        final List<String> scripts = Collections.synchronizedList(new ArrayList<String>());
        volatile boolean up = true;
        @Override public Shell.Result run(String script, int timeoutMs) {
            scripts.add(script);
            if (!up) return Shell.Result.failure("adbd on 127.0.0.1:5555: Connection refused");
            if (script.equals(Projector.ADB_PROBE_SCRIPT)) return new Shell.Result(0, "uid=2000(shell) gid=2000(shell)\n", "", false);
            if (script.equals(Projector.TEMPS_SCRIPT)) return new Shell.Result(0, "temps=AT+Temperature#NtcRedLaser1:41,NtcDmd1:44\n", "", false);
            return new Shell.Result(0, "", "", false);
        }
    }

    /** Direct for the commands, the projector's own adbd for the log, and Shizuku started through it. */
    static void loopback() throws Exception {
        FakeShell shell = new FakeShell();
        shell.pollAnswer = POLL_ANSWER.replaceAll("temps=[^\n]*\n", "temps=\n");
        final FakeAdb adb = new FakeAdb();
        FakeHost host = new FakeHost();
        AuroraPlugin plugin = new AuroraPlugin(shell, null, new AuroraPlugin.AdbFactory() { @Override public Shell.Runner create(int port) { assert port == 5555 : "default port"; return adb; } });
        Map<String, Object> s = settings("Auto", 30);
        s.put("startShizuku", true);
        plugin.start(host, s);
        waitFor(host, "picture");
        Thread.sleep(150);
        assert shell.scripts.contains(Projector.POLL_SCRIPT) : "the poll still runs direct";
        assert adb.scripts.contains(Projector.ADB_PROBE_SCRIPT) : "adbd probed";
        assert adb.scripts.contains(Projector.SHIZUKU_START_SCRIPT) : "Shizuku started through adbd";
        assert adb.scripts.contains(Projector.TEMPS_SCRIPT) : "the log read through adbd";
        assert Math.abs((Double) host.sensors.get("temp_dmd") - 44) < 1e-9 : "DMD temperature from the loopback read: " + host.sensors.get("temp_dmd");
        assert host.status.contains("via direct (+ADB for the log)") : "status names both: " + host.status;
        plugin.stop();

        // Direct refused and adbd up: ADB carries everything.
        FakeShell down = new FakeShell();
        down.probeOk = false;
        FakeAdb adb2 = new FakeAdb() { @Override public Shell.Result run(String script, int timeoutMs) {
            if (script.equals(Projector.POLL_SCRIPT)) { scripts.add(script); return new Shell.Result(0, POLL_ANSWER, "", false); }
            return super.run(script, timeoutMs);
        } };
        FakeHost host2 = new FakeHost();
        final FakeAdb a2 = adb2;
        AuroraPlugin plugin2 = new AuroraPlugin(down, null, new AuroraPlugin.AdbFactory() { @Override public Shell.Runner create(int port) { return a2; } });
        plugin2.start(host2, settings("Auto", 30));
        waitFor(host2, "picture");
        assert host2.status.contains("via ADB") : "ADB is the channel when direct is refused: " + host2.status;
        assert Math.abs((Double) host2.sensors.get("temp_dmd") - 37) < 1e-9 : "temperatures come with the poll over ADB";
        plugin2.stop();
    }

    static void parsing() {
        Projector.State s = Projector.parse(POLL_ANSWER);
        assert Boolean.TRUE.equals(s.light) && Boolean.FALSE.equals(s.screenOff) : "flags";
        assert s.laserWdt == 40 : "wdt";
        assert "HDMI 2".equals(s.input()) : "input " + s.input();
        assert "Cinema Pro".equals(s.pictureModeLabel()) : "mode";
        assert s.laserMinutes == 1530L : "minutes";
        assert s.temperatures.get("NtcDmd1") == 37.0 && s.temperatures.size() == 6 : "temps";
        assert s.ledPwm == 0 : "leds";
        Projector.State empty = Projector.parse("light=\nsource=\nmode=\nminutes=\ntemps=\n");
        assert Boolean.TRUE.equals(empty.light) && Boolean.FALSE.equals(empty.screenOff) : "a fresh boot reads as picture on";
        assert empty.input() == null && empty.pictureModeLabel() == null && empty.laserMinutes == null && empty.temperatures.isEmpty() && empty.staysOn() == null : "unknowns";
        Projector.State dark = Projector.parse("source=-1\nboot=6\n");
        assert dark.sourceId == null && "HDMI 2".equals(dark.input()) : "-1 while the picture is off is not an input";
        Projector.State booted = Projector.parse("source=\nboot=5\ncec=false\nsleep=0\nnosignal=4\n");
        assert "HDMI 1".equals(booted.input()) && Boolean.FALSE.equals(booted.staysOn()) : "boot source and guards";
        assert Boolean.TRUE.equals(s.staysOn()) : "stays on";
        Projector.State sleepy = Projector.parse(POLL_ANSWER.replace("sleep=0", "sleep=4"));
        assert sleepy.sleepMode == 4 && Boolean.FALSE.equals(sleepy.staysOn()) : "the sleep timer breaks stays-on";
        assert Projector.STAY_ON_SCRIPT.contains("setprop persist.prj.sleepMode 0") : "the guard turns the sleep timer off";
        Projector.State odd = Projector.parse("source=99\nmode=6\n");
        assert odd.sourceId == 99 && odd.input() == null && odd.pictureMode == 6 && odd.pictureModeLabel() == null : "unmapped ids stay unknown";
        assert Projector.pictureScript(false).equals(
            "setprop cur.appo.light.enabled false && /vendor/bin/hw/projector-test setLightSourceOnOff false && setprop cur.prj.screenOff true") : "off recipe order";
        assert Projector.pictureScript(true).endsWith("setprop cur.prj.screenOff false") : "on recipe";
        assert Projector.inputScript(7).endsWith("HW7") : "input uri";
        assert Projector.ledsScript(Projector.LED_OFF).endsWith("setAppoLeds 2 6") && Projector.ledsScript(Projector.LED_STANDBY).endsWith("setAppoLeds 2 2") : "leds";
        assert Projector.idFor(Projector.LEDS, Projector.LED_IDS, "Bluetooth") == 4 : "led table";
    }

    static void publication() throws Exception {
        FakeShell shell = new FakeShell();
        FakeHost host = new FakeHost();
        AuroraPlugin plugin = new AuroraPlugin(shell, new AuroraPlugin.ShizukuFactory() {
            @Override public Shell.Runner create(PluginHost h) { throw new AssertionError("Shizuku not wanted"); }
        });
        plugin.start(host, settings("Auto", 30));
        waitFor(host, "picture");
        assert shell.scripts.contains(Projector.STAY_ON_SCRIPT) : "stay-on guards applied at start";
        assert Boolean.TRUE.equals(host.binary.get("stays_on")) : "stays on";
        assert Boolean.TRUE.equals(host.switches.get("picture")) : "switch from light flag";
        assert "HDMI 2".equals(host.selects.get("input")) : "input select";
        assert "Cinema Pro".equals(host.selects.get("picture_mode")) : "mode select";
        assert Boolean.FALSE.equals(host.binary.get("screen_off")) : "screen off";
        assert Math.abs((Double) host.sensors.get("laser_hours") - 25.5) < 1e-9 : "hours " + host.sensors.get("laser_hours");
        assert host.sensors.get("temp_dmd").equals(37.0) : "dmd";
        assert !host.sensors.containsKey("temp_xpr") : "unreported temperatures are not published";
        assert host.status.startsWith("Picture on · HDMI 2 · Cinema Pro · 25.5 laser hours · DMD 37 °C · via direct") : host.status;
        assert !host.statusError;
        plugin.stop();
    }

    static void commands() throws Exception {
        FakeShell shell = new FakeShell();
        FakeHost host = new FakeHost();
        AuroraPlugin plugin = new AuroraPlugin(shell, null);
        plugin.start(host, settings("Direct", 30));
        waitFor(host, "picture");
        int before = shell.scripts.size();
        // Off the projector there is no framework, so the plugin falls through to the channel.

        plugin.onEvent("switch.picture", Collections.<String, Object>singletonMap("on", false));
        waitScripts(shell, before + 3);
        assert shell.scripts.get(before).equals(Projector.ledsScript(Projector.LED_STANDBY)) : "the bar follows the picture off: " + shell.scripts.get(before);
        assert shell.scripts.get(before + 1).equals(Projector.pictureScript(false)) : "switch off script";
        assert shell.scripts.get(before + 2).equals(Projector.POLL_SCRIPT) : "a read follows every command";
        Thread.sleep(100);
        assert "Standby".equals(host.selects.get("front_leds")) : "leds select shows what was commanded";

        // The flag lost while the light stays off: put back, and the sensor says so.
        shell.pollAnswer = POLL_ANSWER.replace("light=true", "light=false").replace("screenoff=false", "screenoff=false");
        plugin.execute("refresh", Collections.<String, Object>emptyMap());
        waitScripts(shell, shell.scripts.size() + 2);
        Thread.sleep(100);
        assert shell.scripts.contains(Projector.REFLAG_SCREEN_OFF_SCRIPT) : "screen-off flag re-asserted";
        assert Boolean.TRUE.equals(host.binary.get("screen_off")) : "sensor reflects the re-asserted flag";
        shell.pollAnswer = POLL_ANSWER;

        // The toggle goes by the light as last read: dark after that refresh, so it lights, and
        // the poll that follows reads it lit, so the next press darkens.
        before = shell.scripts.size();
        plugin.execute("pictureToggle", Collections.<String, Object>emptyMap());
        waitScripts(shell, before + 3);
        assert shell.scripts.subList(before, before + 3).contains(Projector.pictureScript(true)) : "toggle from dark lights: " + shell.scripts.subList(before, shell.scripts.size());
        Thread.sleep(100);
        before = shell.scripts.size();
        plugin.execute("pictureToggle", Collections.<String, Object>emptyMap());
        waitScripts(shell, before + 3);
        assert shell.scripts.subList(before, before + 3).contains(Projector.pictureScript(false)) : "toggle from lit darkens: " + shell.scripts.subList(before, shell.scripts.size());
        Thread.sleep(100);

        before = shell.scripts.size();
        plugin.onEvent("select.input", Collections.<String, Object>singletonMap("option", "HDMI 3"));
        waitScripts(shell, before + 2);
        assert shell.scripts.get(before).endsWith("HW7") : "input script";
        assert "HDMI 3".equals(host.selects.get("input")) : "input shows what was commanded, since the projector does not report it";
        shell.pollAnswer = POLL_ANSWER.replace("source=6", "source=7");
        plugin.execute("refresh", Collections.<String, Object>emptyMap());
        waitScripts(shell, shell.scripts.size() + 1);
        Thread.sleep(100);
        assert "HDMI 3".equals(host.selects.get("input")) : "the projector's menu changing the source wins: " + host.selects.get("input");
        shell.pollAnswer = POLL_ANSWER;

        before = shell.scripts.size();
        plugin.onEvent("select.picture_mode", Collections.<String, Object>singletonMap("option", "Game"));
        waitScripts(shell, before + 2);
        assert shell.scripts.get(before).equals("settings put global picture_mode 10") : "mode script";

        before = shell.scripts.size();
        boolean rejected = false;
        try { plugin.onEvent("select.input", Collections.<String, Object>singletonMap("option", "HDMI 9")); }
        catch (IllegalArgumentException e) { rejected = true; }
        assert rejected && shell.scripts.size() == before : "unknown option runs nothing";

        plugin.execute("settings", Collections.<String, Object>emptyMap());
        waitScripts(shell, before + 2);
        assert shell.scripts.get(before).equals("am start --user 0 -n com.xming.xmprojectorsettings/.ProjectorSettingActivity") : "settings app";

        before = shell.scripts.size();
        plugin.execute("ledsOff", Collections.<String, Object>emptyMap());
        waitScripts(shell, before + 1);
        assert shell.scripts.get(before).endsWith("setAppoLeds 2 6") : "leds off";
        before = shell.scripts.size();
        plugin.onEvent("select.front_leds", Collections.<String, Object>singletonMap("option", "Bluetooth"));
        waitScripts(shell, before + 1);
        assert shell.scripts.get(before).endsWith("setAppoLeds 2 4") : "leds select";

        plugin.stop();
        int after = shell.scripts.size();
        Thread.sleep(150);
        assert shell.scripts.size() == after : "nothing runs after stop";
    }

    static void observed() throws Exception {
        FakeShell shell = new FakeShell();
        FakeHost host = new FakeHost();
        AuroraPlugin plugin = new AuroraPlugin(shell, null);
        plugin.start(host, settings("Direct", 30));
        waitFor(host, "picture");
        Thread.sleep(150);
        assert shell.scripts.contains(Projector.ledsScript(Projector.LED_OFF)) : "a lit projector at start gets the bar turned off";
        int before = shell.scripts.size();
        // The projector goes dark on its own (power menu): the next read moves the bar to standby.
        shell.pollAnswer = POLL_ANSWER.replace("light=true", "light=false").replace("screenoff=false", "screenoff=true");
        plugin.execute("refresh", Collections.<String, Object>emptyMap());
        waitScripts(shell, before + 2);
        Thread.sleep(150);
        assert shell.scripts.contains(Projector.ledsScript(Projector.LED_STANDBY)) : "bar follows an observed dark: " + shell.scripts.subList(before, shell.scripts.size());
        before = shell.scripts.size();
        // A remote key re-lights it: the bar goes off again.
        shell.pollAnswer = POLL_ANSWER;
        plugin.execute("refresh", Collections.<String, Object>emptyMap());
        waitScripts(shell, before + 2);
        Thread.sleep(150);
        assert shell.scripts.subList(before, shell.scripts.size()).contains(Projector.ledsScript(Projector.LED_OFF)) : "bar follows an observed relight";
        before = shell.scripts.size();
        // Unchanged state: nothing is sent to the bar.
        plugin.execute("refresh", Collections.<String, Object>emptyMap());
        waitScripts(shell, before + 1);
        Thread.sleep(150);
        assert !shell.scripts.subList(before, shell.scripts.size()).toString().contains("setAppoLeds") : "no LED command without a change";
        plugin.stop();
    }

    /** The flag says dark but the HAL's minute counter keeps moving: the laser is on. */
    static void counter() throws Exception {
        FakeShell shell = new FakeShell();
        shell.pollAnswer = POLL_ANSWER.replace("light=true", "light=false").replace("screenoff=false", "screenoff=true").replace("wdt=40", "wdt=40");
        FakeHost host = new FakeHost();
        AuroraPlugin plugin = new AuroraPlugin(shell, null);
        plugin.start(host, settings("Direct", 30));
        waitFor(host, "picture");
        assert Boolean.FALSE.equals(host.switches.get("picture")) : "flag trusted at first";
        refreshWith(plugin, shell, "wdt=41");
        assert Boolean.FALSE.equals(host.switches.get("picture")) : "one tick is the minute folded in after a dark, not the laser";
        assert !shell.scripts.contains(Projector.reflagLightScript(true)) : "no re-flag on one tick";
        refreshWith(plugin, shell, "wdt=42");
        assert Boolean.TRUE.equals(host.switches.get("picture")) : "a climbing counter means on: " + host.switches.get("picture");
        assert shell.scripts.contains(Projector.reflagLightScript(true)) : "flag brought in line";
        assert shell.scripts.contains(Projector.ledsScript(Projector.LED_OFF)) : "bar off when the laser is found on";
        plugin.stop();

        // Picture off from Home Assistant: the trailing tick and the save's reset to 0 leave it dark.
        FakeShell after = new FakeShell();
        FakeHost host2 = new FakeHost();
        AuroraPlugin dark = new AuroraPlugin(after, null);
        dark.start(host2, settings("Direct", 30));
        waitFor(host2, "picture");
        Map<String, Object> off = new HashMap<>();
        off.put("on", false);
        dark.onEvent("switch.picture", off);
        waitScripts(after, after.scripts.size() + 1);
        after.pollAnswer = POLL_ANSWER.replace("light=true", "light=false").replace("screenoff=false", "screenoff=true");
        refreshWith(dark, after, "wdt=40");
        refreshWith(dark, after, "wdt=41");
        refreshWith(dark, after, "wdt=0");
        assert Boolean.FALSE.equals(host2.switches.get("picture")) : "stays dark through the tick and the reset";
        assert !after.scripts.contains(Projector.reflagLightScript(true)) : "never re-lit by the counter";
        dark.stop();
    }

    /** Sets the counter in the canned answer, refreshes, and waits for the read to land. */
    static void refreshWith(AuroraPlugin plugin, FakeShell shell, String wdt) throws Exception {
        shell.pollAnswer = shell.pollAnswer.replaceAll("wdt=\\d+", wdt);
        int polls = pollCount(shell);
        plugin.execute("refresh", Collections.<String, Object>emptyMap());
        long deadline = System.currentTimeMillis() + 3000;
        while (pollCount(shell) <= polls && System.currentTimeMillis() < deadline) Thread.sleep(20);
        Thread.sleep(150);
    }

    static int pollCount(FakeShell shell) {
        synchronized (shell.scripts) { return Collections.frequency(shell.scripts, Projector.POLL_SCRIPT); }
    }

    /** A sleep timer turned back on from the projector's menu is turned off again on the next read. */
    static void guard() throws Exception {
        FakeShell shell = new FakeShell();
        FakeHost host = new FakeHost();
        AuroraPlugin plugin = new AuroraPlugin(shell, null);
        plugin.start(host, settings("Direct", 30));
        waitFor(host, "picture");
        assert Boolean.TRUE.equals(host.binary.get("stays_on")) : "guarded";
        int guards = Collections.frequency(shell.scripts, Projector.STAY_ON_SCRIPT);
        shell.pollAnswer = POLL_ANSWER.replace("sleep=0", "sleep=4");
        refreshWith(plugin, shell, "wdt=40");
        assert Boolean.FALSE.equals(host.binary.get("stays_on")) : "slipped guard shows";
        assert host.status.contains("sleep timer still on") : host.status;
        assert Collections.frequency(shell.scripts, Projector.STAY_ON_SCRIPT) == guards + 1 : "guard re-applied on the read";
        plugin.stop();
    }

    static void framework() throws Exception {
        FakeShell shell = new FakeShell();
        shell.pollAnswer = "light=true\nscreenoff=false\nsource=\nmode=\nminutes=60\n";
        FakeHost host = new FakeHost();
        AuroraPlugin plugin = new AuroraPlugin(shell, null);
        final Map<String, String> globals = new HashMap<>();
        globals.put("picture_mode", "9");
        globals.put("boot_source_id", "6");
        globals.put("no_signal_auto_power_off", "0");
        plugin.useGlobals(new AuroraPlugin.Globals() { @Override public String get(String key) { return globals.get(key); } });
        plugin.start(host, settings("Direct", 30));
        waitFor(host, "picture");
        assert "Standard".equals(host.selects.get("picture_mode")) : "picture mode from the framework: " + host.selects.get("picture_mode");
        assert "HDMI 2".equals(host.selects.get("input")) : "boot source from the framework: " + host.selects.get("input");
        assert host.binary.get("stays_on") == null : "cec unknown keeps stays-on unknown";
        plugin.stop();
    }

    static void fallback() throws Exception {
        final FakeShell direct = new FakeShell();
        direct.probeOk = false;
        final FakeShell viaShizuku = new FakeShell();
        FakeHost host = new FakeHost();
        host.shizukuGranted = true;
        AuroraPlugin plugin = new AuroraPlugin(direct, new AuroraPlugin.ShizukuFactory() {
            @Override public Shell.Runner create(PluginHost h) { return viaShizuku; }
        });
        plugin.start(host, settings("Auto", 30));
        waitFor(host, "picture");
        assert host.status.contains("via Shizuku") : host.status;
        assert viaShizuku.scripts.contains(Projector.POLL_SCRIPT) : "poll went through Shizuku";
        assert !direct.scripts.contains(Projector.POLL_SCRIPT) : "not through the refused channel";
        plugin.stop();

        FakeHost nobody = new FakeHost();
        AuroraPlugin stranded = new AuroraPlugin(direct, new AuroraPlugin.ShizukuFactory() {
            @Override public Shell.Runner create(PluginHost h) { throw new AssertionError("not granted"); }
        });
        stranded.start(nobody, settings("Auto", 30));
        long deadline = System.currentTimeMillis() + 3000;
        while (nobody.status.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assert nobody.statusError && nobody.status.contains("No way to reach the projector") : nobody.status;
        assert !nobody.switches.containsKey("picture") : "no switch without a reading";
        stranded.stop();
    }

    static Map<String, Object> settings(String channel, int poll) {
        Map<String, Object> m = new HashMap<>();
        m.put("channel", channel);
        m.put("pollSeconds", poll);
        m.put("stayOn", true);
        m.put("ledsFollowPicture", true);
        return m;
    }

    /** publish() writes the switch first and the status line last, so wait for the status too. */
    static void waitFor(FakeHost host, String switchKey) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while ((!host.switches.containsKey(switchKey) || host.status.isEmpty()) && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assert host.switches.containsKey(switchKey) && !host.status.isEmpty() : "the first read never published " + switchKey + "; status: " + host.status;
    }

    static void waitScripts(FakeShell shell, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (shell.scripts.size() < count && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assert shell.scripts.size() >= count : "expected " + count + " scripts, saw " + shell.scripts;
    }
}
