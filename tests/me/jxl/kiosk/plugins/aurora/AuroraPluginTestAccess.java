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
        + "boot=5\ncec=true\nnosignal=0\n";

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
        framework();
        fallback();
    }

    static void parsing() {
        Projector.State s = Projector.parse(POLL_ANSWER);
        assert Boolean.TRUE.equals(s.light) && Boolean.FALSE.equals(s.screenOff) : "flags";
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
        Projector.State booted = Projector.parse("source=\nboot=5\ncec=false\nnosignal=4\n");
        assert "HDMI 1".equals(booted.input()) && Boolean.FALSE.equals(booted.staysOn()) : "boot source and guards";
        assert Boolean.TRUE.equals(s.staysOn()) : "stays on";
        Projector.State odd = Projector.parse("source=99\nmode=6\n");
        assert odd.sourceId == 99 && odd.input() == null && odd.pictureMode == 6 && odd.pictureModeLabel() == null : "unmapped ids stay unknown";
        assert Projector.pictureScript(false).equals(
            "setprop cur.appo.light.enabled false && /vendor/bin/hw/projector-test setLightSourceOnOff false && setprop cur.prj.screenOff true") : "off recipe order";
        assert Projector.pictureScript(true).endsWith("setprop cur.prj.screenOff false") : "on recipe";
        assert Projector.inputScript(7).endsWith("HW7") : "input uri";
        assert Projector.ledsScript(false).endsWith("setAppoLeds 2 6") && Projector.ledsScript(true).endsWith("setAppoLeds 2 0") : "leds";
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
        waitScripts(shell, before + 2);
        assert shell.scripts.get(before).equals(Projector.pictureScript(false)) : "switch off script";
        assert shell.scripts.get(before + 1).equals(Projector.POLL_SCRIPT) : "a read follows every command";

        // The flag lost while the light stays off: put back, and the sensor says so.
        shell.pollAnswer = POLL_ANSWER.replace("light=true", "light=false").replace("screenoff=false", "screenoff=false");
        plugin.execute("refresh", Collections.<String, Object>emptyMap());
        waitScripts(shell, shell.scripts.size() + 2);
        Thread.sleep(100);
        assert shell.scripts.contains(Projector.REFLAG_SCREEN_OFF_SCRIPT) : "screen-off flag re-asserted";
        assert Boolean.TRUE.equals(host.binary.get("screen_off")) : "sensor reflects the re-asserted flag";
        shell.pollAnswer = POLL_ANSWER;

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
        assert shell.scripts.get(before).equals("am start -n com.xming.xmprojectorsettings/.ProjectorSettingActivity") : "settings app";

        before = shell.scripts.size();
        plugin.execute("ledsOff", Collections.<String, Object>emptyMap());
        waitScripts(shell, before + 2);
        assert shell.scripts.get(before).endsWith("setAppoLeds 2 6") : "leds off";

        plugin.stop();
        int after = shell.scripts.size();
        Thread.sleep(150);
        assert shell.scripts.size() == after : "nothing runs after stop";
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
        return m;
    }

    static void waitFor(FakeHost host, String switchKey) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (!host.switches.containsKey(switchKey) && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assert host.switches.containsKey(switchKey) : "the first read never published " + switchKey + "; status: " + host.status;
    }

    static void waitScripts(FakeShell shell, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (shell.scripts.size() < count && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assert shell.scripts.size() >= count : "expected " + count + " scripts, saw " + shell.scripts;
    }
}
