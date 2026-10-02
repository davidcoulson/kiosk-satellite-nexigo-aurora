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
        + "temps=AT+Temperature#NtcRedLaser1:28,NtcGreenLaser1:25,NtcBlueLaser1:55,NtcCw1:33,NtcDmd1:37,NtcEnv1:23\nleds=0\n"
        + "fan=Thermal speed:40\nboot=5\ncec=true\nsleep=0\nnosignal=0\nwdt=40\n";

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
        final Map<String, Object> texts = new LinkedHashMap<>();
        final Map<String, String> tiles = new LinkedHashMap<>();
        @Override public void publishStatusTile(String key, String title, String level, String text) { tiles.put(key, level + "|" + text); }
        @Override public void publishTextSensor(String key, String name, String state) { texts.put(key, state); }
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
        heat();
        restart();
        cec();
        staleLightSource();
        cpu();
        guard();
        framework();
        arcAudio();
        vendor();
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
        assert s.fanPercent == 40 : "fan";
        // Every script the loopback ADB channel runs must survive the exit-code marker appended to it.
        String chain = "      CEC: logical_address: 0x05 device_type: 5 vendor_id: 1 display_name: JBL Bar1300M2 power_status: 0 physical_address: 0x1000 port_id: 1\n"
            + "      CEC: logical_address: 0x04 device_type: 4 vendor_id: 4346 display_name: Apple TV power_status: 0 physical_address: 0x1100 port_id: 1\n";
        assert "HDMI 1 \u00b7 Apple TV".equals(Projector.showing(Projector.TV_APP, "HDMI 1", Projector.cecDevices(chain))) : "behind the bar: " + Projector.cecDevices(chain);
        assert Projector.shellQuote("it's").equals("'it'\\''s'") : Projector.shellQuote("it's");
        assert !Adb.shellService(Projector.CEC_LOG_START_SCRIPT).contains(";;") && !Adb.shellService(Projector.cecLogAppendScript(java.util.Arrays.asList("a"))).contains(";;") : "log scripts";
        assert Adb.shellService("echo a;").equals("shell:echo a; echo " + Adb.MARK + "$?") : Adb.shellService("echo a;");
        for (String script : new String[] {Projector.POLL_SCRIPT, Projector.TEMPS_SCRIPT, Projector.CEC_SCRIPT,
                Projector.SHOWING_SCRIPT, Projector.LIVE_INPUT_SCRIPT, Projector.FOREGROUND_SCRIPT, Projector.STAY_ON_SCRIPT, Projector.SHIZUKU_START_SCRIPT}) {
            assert !Adb.shellService(script).contains(";;") : "';;' in the ADB line for: " + script;
        }
        long[] c0 = Projector.cpuTimes("cpu  100 0 50 800 50 0 0 0 0 0"), c1 = Projector.cpuTimes("cpu  130 0 70 1040 60 0 0 0 0 0");
        assert c0[0] == 150 && c0[1] == 1000 : "cpu times";
        assert Math.abs(Projector.cpuPercent(c0[0], c0[1], c1[0], c1[1]) - 50.0 / 3) < 1e-9 : "cpu busy: " + Projector.cpuPercent(c0[0], c0[1], c1[0], c1[1]);
        assert Projector.cpuPercent(0, 0, c1[0], c1[1]) == null : "no first reading, no share";
        assert Projector.cpuTimes("intr 1 2 3") == null : "only the cpu line";
        assert Double.valueOf(55).equals(Projector.hottestLaser(s)) : "hottest laser";
        assert Projector.hottestLaser(Projector.parse("light=true\n")) == null : "no lasers, no reading";
        assert Double.valueOf(43).equals(Projector.hottestLaser(Projector.parse("temps=AT+Temperature#NtcRedLaser1:43,NtcGreenLaser1:41,NtcBlueLaser1:40\n"))) : "red can be the warmest";
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
            "setprop cur.appo.light.enabled false && /vendor/bin/hw/projector-test setLightSourceOnOff false && setprop cur.prj.screenOff true; setprop cur.djc.picture_off true") : "off recipe order";
        assert Projector.pictureScript(true).endsWith("setprop cur.prj.screenOff false; setprop cur.djc.picture_off false") : "on recipe";
        assert Boolean.TRUE.equals(Projector.parse("held=true\n").heldOff) && Projector.parse("held=\n").heldOff == null : "held note";
        assert Boolean.TRUE.equals(Projector.parse("ls=1790500677085 On\n").lightSource) && Boolean.FALSE.equals(Projector.parse("ls=1790500677085 Off\n").lightSource)
            && Projector.parse("ls=\n").lightSource == null : "light-source command";
        assert Projector.parse("ls=1790500677085 On\n").lightSourceAt == 1790500677085L : "light-source time";
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
        assert Double.valueOf(40).equals(host.sensors.get("fan_speed")) : "fan speed published: " + host.sensors.get("fan_speed");
        assert Double.valueOf(55).equals(host.sensors.get("laser_temp")) : "hottest laser (blue 55 over red 28, green 25): " + host.sensors.get("laser_temp");
        assert !host.sensors.containsKey("temp_red_laser") && !host.sensors.containsKey("temp_blue_laser") && !host.sensors.containsKey("temp_green_laser") : "no per-laser sensors: " + host.sensors.keySet();
        assert Double.valueOf(37).equals(host.sensors.get("temp_dmd")) : "the other NTCs still publish";
        assert Boolean.TRUE.equals(host.switches.get("picture")) : "switch from light flag";
        assert "HDMI 2".equals(host.selects.get("input")) : "input select";
        assert "Cinema Pro".equals(host.selects.get("picture_mode")) : "mode select";
        assert Boolean.TRUE.equals(host.binary.get("laser")) : "laser status on";
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
        assert Boolean.TRUE.equals(shell.scripts.contains(Projector.REFLAG_SCREEN_OFF_SCRIPT)) && Boolean.FALSE.equals(host.binary.get("laser")) : "laser status off while dark";
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

    /** A poll answer with the flags dark and the blue laser / ambient at these temperatures. */
    static String darkAt(int blue, int ambient) {
        return POLL_ANSWER.replace("light=true", "light=false").replace("screenoff=false", "screenoff=true")
            .replace("NtcBlueLaser1:55", "NtcBlueLaser1:" + blue).replace("NtcEnv1:23", "NtcEnv1:" + ambient);
    }

    /** The flags say dark but the laser is warming: it was lit behind their back. The counter,
     *  which climbs with the laser cold, decides nothing. */
    static void heat() throws Exception {
        Projector.State cold = parseWith(27, 23), hot = parseWith(53, 23), cooling = parseWith(45, 23);
        assert Projector.laserLit(cold, 28.0) == null : "the heat never says dark";
        assert Boolean.TRUE.equals(Projector.laserLit(hot, 40.0)) : "hot and heating is lit";
        assert Projector.laserLit(hot, 53.0) == null : "hot and steady defers to the flag";
        assert Projector.laserLit(parseWith(41, 23), 41.0) == null : "a cooling plateau is not lit";
        assert Projector.laserLit(parseWith(42, 23), 41.0) == null : "one degree of noise is not heating";
        assert Projector.laserLit(hot, null) == null : "no trend on the first read";
        assert Projector.laserLit(cooling, 53.0) == null : "hot and cooling defers to the flag";
        assert Projector.laserLit(parseWith(33, 23), 30.0) == null : "warming from cold is not yet lit";
        // 2026-10-01 20:10: lit at 65, 59 thirty seconds later as an input came up dark. Still lit.
        assert Projector.laserLit(parseWith(59, 24), 65.0) == null : "a dimming laser is not dark";
        assert Projector.laserLit(Projector.parse("light=false\nscreenoff=true\n"), 30.0) == null : "no log, no say";

        FakeShell shell = new FakeShell();
        shell.pollAnswer = darkAt(27, 23);
        FakeHost host = new FakeHost();
        AuroraPlugin plugin = new AuroraPlugin(shell, null);
        plugin.start(host, settings("Direct", 30));
        waitFor(host, "picture");
        assert Boolean.FALSE.equals(host.switches.get("picture")) : "dark and cold";
        refreshAnswer(plugin, shell, darkAt(27, 23).replace("wdt=40", "wdt=41"));
        refreshAnswer(plugin, shell, darkAt(27, 23).replace("wdt=40", "wdt=42"));
        assert Boolean.FALSE.equals(host.switches.get("picture")) : "a climbing counter is not the laser";
        refreshAnswer(plugin, shell, darkAt(40, 23));
        assert Boolean.TRUE.equals(host.switches.get("picture")) : "warming well over ambient means lit: " + host.switches.get("picture");
        assert shell.scripts.contains(Projector.reflagLightScript(true)) : "flag brought in line";
        assert shell.scripts.contains(Projector.ledsScript(Projector.LED_OFF)) : "bar off when the laser is found on";
        plugin.stop();

        // Picture off from Home Assistant while the laser is still hot and the log line is old.
        FakeShell after = new FakeShell();
        after.pollAnswer = POLL_ANSWER.replace("NtcBlueLaser1:55", "NtcBlueLaser1:53");
        FakeHost host2 = new FakeHost();
        AuroraPlugin dark = new AuroraPlugin(after, null);
        dark.start(host2, settings("Direct", 30));
        waitFor(host2, "picture");
        Map<String, Object> off = new HashMap<>();
        off.put("on", false);
        dark.onEvent("switch.picture", off);
        waitScripts(after, after.scripts.size() + 1);
        refreshAnswer(dark, after, darkAt(53, 23));
        refreshAnswer(dark, after, darkAt(53, 23));
        assert Boolean.FALSE.equals(host2.switches.get("picture")) : "a stale hot reading after the command does not re-light it";
        assert !after.scripts.contains(Projector.reflagLightScript(true)) : "never re-flagged on";
        dark.stop();
    }

    static final String CEC_OLD =
        "    [S] time=2026-09-27 02:54:20 message=<Give Device Power Status> src: 0, dst: 4\n"
        + "    [R] time=2026-09-27 02:54:20 message=<Report Power Status> src: 4, dst: 0, params: 00\n"
        + "    [R] time=2026-09-27 02:54:29 message=<Standby> src: 4, dst: 15\n";
    static final String CEC_WAKE =
        "    [R] time=2026-09-27 02:58:36 message=<Image View On> src: 4, dst: 0\n"
        + "    [R] time=2026-09-27 02:58:36 message=<Active Source> src: 4, dst: 15, params: 20 00\n";
    static final String CEC_SLEEP = "    [R] time=2026-09-27 03:10:02 message=<Standby> src: 4, dst: 15\n";
    static final String TV_FRONT = "    mResumedActivity: ActivityRecord{5d0fb2b u0 com.mediatek.wwtv.tvcenter/.nav.TurnkeyUiMainActivity t633}\n";
    static final String APP_FRONT = "    mResumedActivity: ActivityRecord{51cb830 u0 com.spocky.projengmenu/.ui.home.MainActivity t631}\n";

    /** The projector's adbd answering the CEC history and the app in front. */
    static final class CecAdb extends FakeAdb {
        volatile String history = CEC_OLD;
        volatile String foreground = TV_FRONT;
        volatile String previousBoot = "boot=1234\n"
            + "    [R] time=2026-09-27 16:55:58 message=<Report Power Status> src: 5, dst: 0, params: 00\n"
            + "    [R] time=2026-09-27 16:56:01 message=<Set System Audio Mode> src: 5, dst: 15, params: 00\n";
        final List<String> appended = Collections.synchronizedList(new ArrayList<String>());
        /** The HDMI port the TV app holds (Android's TV input service), 0 for none. */
        volatile int live = 0;
        @Override public Shell.Result run(String script, int timeoutMs) {
            if (script.equals(Projector.LIVE_INPUT_SCRIPT)) { scripts.add(script); return new Shell.Result(0, live == 0 ? ""
                : "    " + (live + 4) + ": Connection{ mHardwareInfo: TvInputHardwareInfo {id=" + (live + 4) + ", type=9, hdmi_port=" + live + ", cable_connection_status=0}, mInfo: null, mCallback: x, mConfigs: [], mCallingUid: 1000, mResolvedUserId: 0 }\n", "", false); }
            if (script.equals(Projector.CEC_SCRIPT)) { scripts.add(script); return new Shell.Result(0, history, "", false); }
            if (script.equals(Projector.FOREGROUND_SCRIPT)) { scripts.add(script); return new Shell.Result(0, foreground, "", false); }
            if (script.equals(Projector.CEC_LOG_START_SCRIPT)) { scripts.add(script); return new Shell.Result(0, previousBoot, "", false); }
            if (script.startsWith("F=" + Projector.CEC_LOG + "; printf")) { scripts.add(script); appended.add(script); return new Shell.Result(0, "", "", false); }
            if (script.equals(Projector.SHOWING_SCRIPT)) { scripts.add(script); return new Shell.Result(0, foreground
                + "      CEC: logical_address: 0x04 device_type: 4 vendor_id: 4346 display_name: Apple TV power_status: 0 physical_address: 0x2000 port_id: 2\n", "", false); }
            return super.run(script, timeoutMs);
        }
    }

    /** HDMI-CEC: the history parses, only new messages count, and the picture follows the source. */
    static void cec() throws Exception {
        // The input on the wall, from Android's TV input service (2026-10-01, the Apple TV on HDMI 1).
        String held = "    5: Connection{ mHardwareInfo: TvInputHardwareInfo {id=5, type=9, audio_type=-2147483616, audio_addr=, hdmi_port=1, cable_connection_status=0}, mInfo: TvInputInfo{id=com.mediatek.tvinput/.hdmi.HDMIInputService/HDMI110004, pkg=com.mediatek.tvinput, service=com.mediatek.tvinput.hdmi.HDMIInputService}, mCallback: android.media.tv.ITvInputHardwareCallback$Stub$Proxy@f99cf2c, mConfigs: [TvStreamConfig {mStreamId=0;mType=1;mGeneration=0}], mCallingUid: 1000, mResolvedUserId: 0 }\n";
        assert Integer.valueOf(1).equals(Projector.livePort(held)) : "live port: " + Projector.livePort(held);
        assert Projector.livePort("") == null : "nothing held";
        List<Projector.Cec> old = Projector.parseCec(CEC_OLD);
        assert old.size() == 3 && !old.get(0).received && old.get(2).sleeps() : "parse: " + old.size();
        List<Projector.Cec> woke = Projector.parseCec(CEC_OLD + CEC_WAKE);
        List<Projector.Cec> fresh = Projector.newCec(old, woke);
        assert fresh.size() == 2 && fresh.get(0).wakes() && fresh.get(1).wakes() && fresh.get(1).port() == 2 : "new: " + fresh.size();
        assert "Active Source from Playback 1 to all".equals(fresh.get(1).summary(null)) : fresh.get(1).summary(null);
        Map<Integer, String> names = Projector.cecNames("      CEC: logical_address: 0x04 device_type: 4 vendor_id: 4346 display_name: Apple TV power_status: 0 physical_address: 0x1000 port_id: 1\n");
        assert "Image View On from Apple TV".equals(fresh.get(0).summary(names)) : fresh.get(0).summary(names);
        assert !old.get(1).notable() && old.get(2).notable() : "power polling is not notable, Standby is";
        // The ring drops its oldest lines: the overlap is still found.
        List<Projector.Cec> rolled = Projector.parseCec(CEC_OLD.substring(CEC_OLD.indexOf('\n') + 1) + CEC_WAKE);
        assert Projector.newCec(old, rolled).isEmpty() == false && Projector.newCec(woke, rolled).isEmpty() : "rolled ring";
        assert Projector.newCec(woke, woke).isEmpty() : "nothing new";

        FakeShell direct = new FakeShell();
        direct.pollAnswer = POLL_ANSWER.replace("light=true", "light=false").replace("screenoff=false", "screenoff=true");
        final CecAdb adb = new CecAdb();
        FakeHost host = new FakeHost();
        AuroraPlugin plugin = new AuroraPlugin(direct, null, new AuroraPlugin.AdbFactory() { @Override public Shell.Runner create(int port) { return adb; } });
        plugin.start(host, settings("Auto", 30));
        waitFor(host, "picture");
        waitUntil(new Check() { public boolean ok() { return host.texts.containsKey("cec"); } }, "baseline sensor");
        assert "Standby from Apple TV to all".equals(host.texts.get("cec")) : "baseline shows the newest notable, named: " + host.texts.get("cec");
        assert ("16:55:58 Report Power Status from Audio system \u00b7 16:56:01 Set System Audio Mode from Audio system to all").equals(host.texts.get("cec_before_boot"))
            : "before last boot: " + host.texts.get("cec_before_boot");
        assert !adb.appended.isEmpty() && adb.appended.get(0).contains("message=<Standby>") : "the baseline goes to the log";
        assert !direct.scripts.contains(Projector.pictureScript(false)) && !direct.scripts.contains(Projector.pictureScript(true)) : "the baseline acts on nothing";

        adb.history = CEC_OLD + CEC_WAKE;
        waitUntil(new Check() { public boolean ok() { return direct.scripts.contains(Projector.pictureScript(true)); } }, "picture on after Image View On");
        waitUntil(new Check() { public boolean ok() { return adb.appended.size() >= 2 && adb.appended.get(adb.appended.size() - 1).contains("Active Source"); } }, "new messages logged");
        waitUntil(new Check() { public boolean ok() { return "Active Source from Apple TV to all".equals(host.texts.get("cec")); } }, "sensor follows");
        assert !direct.scripts.toString().contains("HW6") && !direct.scripts.toString().contains("HW5") : "already on the source's input: no input switch";

        direct.pollAnswer = POLL_ANSWER;   // the picture now reads on
        plugin.execute("refresh", Collections.<String, Object>emptyMap());
        Thread.sleep(300);
        adb.history = CEC_OLD + CEC_WAKE + CEC_SLEEP;
        waitUntil(new Check() { public boolean ok() { return direct.scripts.contains(Projector.pictureScript(false)); } }, "picture off after Standby");

        // An app in front keeps its picture through the source's Standby.
        int offs = Collections.frequency(direct.scripts, Projector.pictureScript(false));
        direct.pollAnswer = POLL_ANSWER;
        plugin.execute("refresh", Collections.<String, Object>emptyMap());
        Thread.sleep(300);
        adb.foreground = APP_FRONT;
        adb.history = CEC_OLD + CEC_WAKE + CEC_SLEEP + CEC_SLEEP.replace("03:10:02", "03:20:02");
        Thread.sleep(3000);   // one read of the CEC history (every 2 s)
        Thread.sleep(200);
        assert Collections.frequency(direct.scripts, Projector.pictureScript(false)) == offs : "no dark while Projectivy is in front";
        plugin.execute("refresh", Collections.<String, Object>emptyMap());
        waitUntil(new Check() { public boolean ok() { return "Projectivy".equals(host.texts.get("showing")); } }, "showing names the app");

        // Another input on the wall (the Unraid VM on HDMI 3): the Apple TV (HDMI 2) sleeping
        // leaves it alone, and the Apple TV waking takes the projector to HDMI 2.
        // The vendor's property still says HDMI 2; Android's TV input service says HDMI 3 and wins.
        adb.foreground = TV_FRONT;
        adb.live = 3;
        plugin.execute("refresh", Collections.<String, Object>emptyMap());
        waitUntil(new Check() { public boolean ok() { return "HDMI 3".equals(host.texts.get("showing")); } }, "showing HDMI 3: " + host.texts.get("showing"));
        assert "HDMI 3".equals(host.selects.get("input")) : "the input select follows the projector: " + host.selects.get("input");
        int offsVm = Collections.frequency(direct.scripts, Projector.pictureScript(false));
        adb.history = adb.history + CEC_SLEEP.replace("03:10:02", "03:25:02");
        waitUntil(new Check() { public boolean ok() { return adb.appended.toString().contains("picture left on"); } }, "noted: " + adb.appended);
        assert Collections.frequency(direct.scripts, Projector.pictureScript(false)) == offsVm : "the VM's picture stays on";
        adb.history = adb.history + CEC_WAKE.replace("02:58:36", "03:26:36");
        waitUntil(new Check() { public boolean ok() { return direct.scripts.toString().contains("HW6"); } }, "to the Apple TV's input on its wake");
        adb.live = 2;
        direct.pollAnswer = POLL_ANSWER;
        plugin.execute("refresh", Collections.<String, Object>emptyMap());
        Thread.sleep(300);

        // A source that sleeps without Standby: its power report goes from on to standby.
        adb.foreground = TV_FRONT;
        direct.pollAnswer = POLL_ANSWER;
        plugin.execute("refresh", Collections.<String, Object>emptyMap());
        waitUntil(new Check() { public boolean ok() { return "HDMI 2 \u00b7 Apple TV".equals(host.texts.get("showing")); } }, "showing names the input and its device");
        assert ("on|HDMI 2 \u00b7 Apple TV").equals(host.tiles.get("showing")) : "showing tile: " + host.tiles.get("showing");
        assert ("on|Laser on \u00b7 55 \u00b0C \u00b7 fan 40%").equals(host.tiles.get("projector")) : "projector tile: " + host.tiles.get("projector");
        // The soundbar going to standby (its own idle timer) leaves the picture alone.
        int offsBar = Collections.frequency(direct.scripts, Projector.pictureScript(false));
        adb.history = adb.history + "    [R] time=2026-09-27 03:28:29 message=<Report Power Status> src: 5, dst: 0, params: 00\n";
        Thread.sleep(2500);
        adb.history = adb.history + "    [R] time=2026-09-27 03:29:29 message=<Report Power Status> src: 5, dst: 0, params: 01\n";
        waitUntil(new Check() { public boolean ok() { return String.valueOf(host.texts.get("cec")).endsWith("went to standby"); } }, "bar standby named: " + host.texts.get("cec"));
        Thread.sleep(300);
        assert Collections.frequency(direct.scripts, Projector.pictureScript(false)) == offsBar : "no dark when only the soundbar sleeps";
        int offs2 = Collections.frequency(direct.scripts, Projector.pictureScript(false));
        String base = adb.history;
        adb.history = base + "    [R] time=2026-09-27 03:30:29 message=<Report Power Status> src: 4, dst: 0, params: 00\n";
        Thread.sleep(2500);
        adb.history = adb.history + "    [R] time=2026-09-27 03:31:29 message=<Report Power Status> src: 4, dst: 0, params: 01\n";
        waitUntil(new Check() { public boolean ok() { return Collections.frequency(direct.scripts, Projector.pictureScript(false)) > offs2; } }, "picture off when the source reports standby");
        waitUntil(new Check() { public boolean ok() { return "Apple TV went to standby".equals(host.texts.get("cec")); } }, "power change named: " + host.texts.get("cec"));
        plugin.stop();
    }

    /** CPU busy comes from two reads of /proc/stat and shows on the Projector tile. */
    static void cpu() throws Exception {
        FakeShell shell = new FakeShell();
        shell.pollAnswer = POLL_ANSWER + "cpu=cpu  100 0 50 800 50 0 0 0 0 0\n";
        FakeHost host = new FakeHost();
        AuroraPlugin plugin = new AuroraPlugin(shell, null);
        plugin.start(host, settings("Direct", 30));
        waitFor(host, "picture");
        assert !host.sensors.containsKey("cpu_busy") : "one read gives no share";
        refreshAnswer(plugin, shell, POLL_ANSWER + "cpu=cpu  130 0 70 1040 60 0 0 0 0 0\n");
        assert Math.abs((Double) host.sensors.get("cpu_busy") - 50.0 / 3) < 1e-9 : "cpu busy: " + host.sensors.get("cpu_busy");
        assert host.tiles.get("projector").endsWith("\u00b7 CPU 17%") : "tile: " + host.tiles.get("projector");
        plugin.stop();
    }

    /** A light-source command older than the plugin's own last picture command is ignored: the
     *  newer one was pruned from the log. */
    static void staleLightSource() throws Exception {
        FakeShell shell = new FakeShell();
        FakeHost host = new FakeHost();
        AuroraPlugin plugin = new AuroraPlugin(shell, null);
        plugin.start(host, settings("Direct", 30));
        waitFor(host, "picture");
        Map<String, Object> off = new HashMap<>();
        off.put("on", false);
        plugin.onEvent("switch.picture", off);
        waitScripts(shell, shell.scripts.size() + 1);
        long old = System.currentTimeMillis() - 60_000L;
        refreshAnswer(plugin, shell, darkAt(27, 23).replace("light=false", "light=false\nheld=true\nls=" + old + " On"));
        assert Boolean.FALSE.equals(host.switches.get("picture")) : "an old On does not re-light it";
        assert !shell.scripts.contains(Projector.reflagLightScript(true)) : "no re-flag from a stale line";
        assert Boolean.FALSE.equals(host.binary.get("laser")) : "laser status off";
        plugin.stop();
    }

    interface Check { boolean ok(); }

    static void waitUntil(Check check, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 6000;
        while (!check.ok() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assert check.ok() : "timed out waiting for: " + what;
    }

    /** A Kiosk Satellite restart: a fresh plugin, the display woken (screen-off flag cleared, light
     *  still off) and the projector's own note that the picture was turned off from here. */
    static void restart() throws Exception {
        FakeShell shell = new FakeShell();
        shell.pollAnswer = darkAt(27, 23).replace("screenoff=true", "screenoff=false").replace("light=false", "light=false\nheld=true");
        FakeHost host = new FakeHost();
        AuroraPlugin plugin = new AuroraPlugin(shell, null);
        plugin.start(host, settings("Direct", 30));
        waitFor(host, "picture");
        assert shell.scripts.contains(Projector.REFLAG_SCREEN_OFF_SCRIPT) : "deliberate dark put back after a restart: " + shell.scripts;
        assert Boolean.FALSE.equals(host.binary.get("laser")) : "laser status off";
        plugin.stop();

        // Then the vendor lights the laser for Kiosk Satellite's activity, flags untouched: with the
        // picture held off from here it goes straight back off, and the note stays.
        FakeShell relit = new FakeShell();
        relit.pollAnswer = darkAt(27, 23).replace("light=false", "light=false\nheld=true");
        FakeHost host3 = new FakeHost();
        AuroraPlugin plugin3 = new AuroraPlugin(relit, null);
        plugin3.start(host3, settings("Direct", 30));
        waitFor(host3, "picture");
        refreshAnswer(plugin3, relit, darkAt(40, 23).replace("light=false", "light=false\nheld=true"));
        assert relit.scripts.contains(Projector.pictureScript(false)) : "re-darkened after the start lit it: " + relit.scripts;
        assert !relit.scripts.contains(Projector.reflagLightScript(true)) : "not reported as lit";
        assert Boolean.FALSE.equals(host3.switches.get("picture")) : "picture stays off";
        plugin3.stop();

        // Relit while still hot: the heat barely moves, but the HAL's own command gives it away.
        FakeShell hot = new FakeShell();
        hot.pollAnswer = darkAt(60, 23).replace("light=false", "light=false\nheld=true\nls=" + System.currentTimeMillis() + " On");
        FakeHost host4 = new FakeHost();
        AuroraPlugin plugin4 = new AuroraPlugin(hot, null);
        plugin4.start(host4, settings("Direct", 30));
        waitFor(host4, "picture");
        assert hot.scripts.contains(Projector.pictureScript(false)) : "re-darkened on the HAL's On: " + hot.scripts;
        assert Boolean.FALSE.equals(host4.switches.get("picture")) : "picture stays off";
        plugin4.stop();

        // Lit from the remote long after a start (no guard): the On is reported, the note cleared.
        FakeShell remote = new FakeShell();
        remote.pollAnswer = darkAt(27, 23).replace("light=false", "light=false\nls=" + System.currentTimeMillis() + " On");
        FakeHost host5 = new FakeHost();
        AuroraPlugin plugin5 = new AuroraPlugin(remote, null);
        plugin5.start(host5, settings("Direct", 30));
        waitFor(host5, "picture");
        assert Boolean.TRUE.equals(host5.switches.get("picture")) : "a lit laser nobody here held off is reported on";
        assert remote.scripts.contains(Projector.reflagLightScript(true)) : "flags brought in line";
        plugin5.stop();

        // An On from hours ago, the flags dark: old news, the flags stand.
        FakeShell stale = new FakeShell();
        stale.pollAnswer = darkAt(33, 23) + "ls=1790500677085 On\n";
        FakeHost host6 = new FakeHost();
        AuroraPlugin plugin6 = new AuroraPlugin(stale, null);
        plugin6.start(host6, settings("Direct", 30));
        waitFor(host6, "picture");
        assert Boolean.FALSE.equals(host6.switches.get("picture")) : "an old On does not outvote the flags: " + host6.switches.get("picture");
        assert !stale.scripts.contains(Projector.reflagLightScript(true)) : "flags left alone";
        plugin6.stop();

        // A lit picture whose laser cools fast (a dark input coming up) stays lit.
        FakeShell dim = new FakeShell();
        dim.pollAnswer = POLL_ANSWER.replace("NtcBlueLaser1:55", "NtcBlueLaser1:65");
        FakeHost host7 = new FakeHost();
        AuroraPlugin plugin7 = new AuroraPlugin(dim, null);
        plugin7.start(host7, settings("Direct", 30));
        waitFor(host7, "picture");
        refreshAnswer(plugin7, dim, POLL_ANSWER.replace("NtcBlueLaser1:55", "NtcBlueLaser1:59"));
        refreshAnswer(plugin7, dim, POLL_ANSWER.replace("NtcBlueLaser1:55", "NtcBlueLaser1:49"));
        assert Boolean.TRUE.equals(host7.switches.get("picture")) && Boolean.TRUE.equals(host7.binary.get("laser")) : "dimming is not dark";
        assert !dim.scripts.contains(Projector.reflagLightScript(false)) : "flags left alone";
        plugin7.stop();

        // The same dark without the note (the power menu, the sleep timer) is only recorded.
        FakeShell other = new FakeShell();
        other.pollAnswer = darkAt(27, 23).replace("screenoff=true", "screenoff=false");
        FakeHost host2 = new FakeHost();
        AuroraPlugin plugin2 = new AuroraPlugin(other, null);
        plugin2.start(host2, settings("Direct", 30));
        waitFor(host2, "picture");
        assert !other.scripts.contains(Projector.REFLAG_SCREEN_OFF_SCRIPT) : "a dark this plugin did not ask for is not promoted";
        plugin2.stop();
    }

    static Projector.State parseWith(int blue, int ambient) { return Projector.parse(darkAt(blue, ambient)); }

    static void refreshAnswer(AuroraPlugin plugin, FakeShell shell, String answer) throws Exception {
        shell.pollAnswer = answer;
        refreshWith(plugin, shell, "wdt=40");
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

    /** The vendor's own API through the helper: parsing, the format line, the options, and the
     *  plugin publishing what it reads and carrying settings out. */
    static void vendor() throws Exception {
        Map<String, String> v = Projector.parseVendor("fans=1040,1180\nbrightness=0\nerror.res=boom\nsignal=true\nhdr=1\ncolor_depth=10\nres=3840x2160,24,p\n");
        assert "1040,1180".equals(v.get("fans")) && !v.containsKey("error.res") : "parsed: " + v;
        assert "3840\u00d72160p 24 Hz \u00b7 HDR10 \u00b7 10-bit".equals(Projector.videoFormat(v)) : "format: " + Projector.videoFormat(v);
        v.put("res", "3840x2160,23976,p"); v.put("hdr", "3");
        assert "3840\u00d72160p 23.976 Hz \u00b7 Dolby Vision \u00b7 10-bit".equals(Projector.videoFormat(v)) : "format: " + Projector.videoFormat(v);
        v.put("signal", "false");
        assert "No signal".equals(Projector.videoFormat(v)) : "no signal";
        assert Projector.videoFormat(Projector.parseVendor("fans=1\n")) == null : "no signal read, nothing to say";

        java.util.Random random = new java.util.Random(1);
        for (int n : new int[] {0, 1, 2, 3, 999, 1000}) {
            byte[] data = new byte[n];
            random.nextBytes(data);
            assert AuroraPlugin.base64(data).equals(java.util.Base64.getEncoder().encodeToString(data)) : "base64 of " + n;
        }

        Map<String, Object> eco = new HashMap<>(); eco.put("option", "ECO");
        assert AuroraPlugin.vendorValue("select.brightness_mode", eco) == 2 : "ECO is 2";
        Map<String, Object> on = new HashMap<>(); on.put("on", true);
        assert AuroraPlugin.vendorValue("switch.cinema_24p", on) == Projector.P24_ON : "24p on is 2";
        assert AuroraPlugin.vendorValue("switch.low_latency", on) == 1 : "a switch is 1";

        final String path = "/data/local/tmp/aurora-vendor-test.dex";
        final String quick = "fans=1040,1180,1237,1040\nbrightness=0\ndb=2\np24=0\ndhdr=1\nlow_latency=false\nbody_detect=true\ncolor_depth=8\n";
        final FakeAdb adb = new FakeAdb() {
            volatile int brightness = 0;
            @Override public Shell.Result run(String script, int timeoutMs) {
                if (script.startsWith("CLASSPATH=" + path + " ")) {
                    scripts.add(script);
                    if (script.contains(" set brightness ")) brightness = Integer.parseInt(script.replaceAll(".* set brightness (\\d+).*", "$1"));
                    String q = quick.replace("brightness=0", "brightness=" + brightness);
                    return new Shell.Result(0, script.contains(" read") ? q + "signal=true\nhdr=0\nres=1920x1080,60,p\n" : q, "", false);
                }
                return super.run(script, timeoutMs);
            }
        };
        FakeShell direct = new FakeShell();
        direct.pollAnswer = POLL_ANSWER.replace("light=true", "light=true\neye=false");
        FakeHost host = new FakeHost();
        AuroraPlugin plugin = new AuroraPlugin(direct, null, new AuroraPlugin.AdbFactory() { @Override public Shell.Runner create(int port) { return adb; } });
        plugin.useVendor(path);
        plugin.start(host, settings("Auto", 30));
        waitUntil(new Check() { public boolean ok() { return "Standard".equals(host.selects.get("brightness_mode")); } }, "brightness read: " + host.selects);
        assert "Level 2".equals(host.selects.get("dynamic_black")) : "dynamic black: " + host.selects.get("dynamic_black");
        assert Boolean.FALSE.equals(host.switches.get("cinema_24p")) && Boolean.TRUE.equals(host.switches.get("dynamic_tone_mapping")) && Boolean.FALSE.equals(host.switches.get("low_latency")) : "switches: " + host.switches;
        assert Double.valueOf(1237).equals(host.sensors.get("fan3_rpm")) : "fan 3: " + host.sensors.get("fan3_rpm");
        assert Boolean.TRUE.equals(host.binary.get("signal")) : "signal";
        assert "1920\u00d71080p 60 Hz \u00b7 SDR".equals(host.texts.get("video_format")) : "format: " + host.texts.get("video_format");
        assert Boolean.FALSE.equals(host.binary.get("eye_protect")) : "eye protection read";

        plugin.onEvent("select.brightness_mode", eco);
        waitUntil(new Check() { public boolean ok() { return "ECO".equals(host.selects.get("brightness_mode")); } }, "brightness set and read back: " + adb.scripts);
        assert adb.scripts.toString().contains(" set brightness 2") : "through the vendor's setter";
        assert "1920\u00d71080p 60 Hz \u00b7 SDR".equals(host.texts.get("video_format")) : "a quick read keeps the signal";

        direct.pollAnswer = POLL_ANSWER.replace("light=true", "light=true\neye=true");
        plugin.execute("refresh", Collections.<String, Object>emptyMap());
        waitUntil(new Check() { public boolean ok() { return Boolean.TRUE.equals(host.binary.get("eye_protect")); } }, "eye protection tripped");
        waitUntil(new Check() { public boolean ok() { return adb.scripts.toString().contains("eye protection tripped"); } }, "noted");
        plugin.stop();
    }

    /** ARC audio to the soundbar: set at start, and put back when a read finds it off. */
    static void arcAudio() throws Exception {
        FakeShell shell = new FakeShell();
        FakeHost host = new FakeHost();
        AuroraPlugin plugin = new AuroraPlugin(shell, null);
        final Map<String, String> globals = new HashMap<>();
        globals.put("hdmi_system_audio_control_enabled", "1");
        globals.put("hdmi_arc_control_enabled", "1");
        plugin.useGlobals(new AuroraPlugin.Globals() { @Override public String get(String key) { return globals.get(key); } });
        Map<String, Object> on = settings("Direct", 30);
        on.put("arcAudio", true);
        plugin.start(host, on);
        waitFor(host, "picture");
        int applied = Collections.frequency(shell.scripts, Projector.ARC_AUDIO_SCRIPT);
        assert applied == 1 : "set at start: " + applied;
        globals.put("hdmi_arc_control_enabled", "0");
        refreshAnswer(plugin, shell, POLL_ANSWER);
        assert Collections.frequency(shell.scripts, Projector.ARC_AUDIO_SCRIPT) == 2 : "put back when found off";
        assert shell.scripts.toString().contains("ARC audio settings found off") : "noted";
        plugin.stop();

        FakeShell off = new FakeShell();
        AuroraPlugin plugin2 = new AuroraPlugin(off, null);
        plugin2.useGlobals(new AuroraPlugin.Globals() { @Override public String get(String key) { return "0"; } });
        FakeHost host2 = new FakeHost();
        plugin2.start(host2, settings("Direct", 30));
        waitFor(host2, "picture");
        assert !off.scripts.contains(Projector.ARC_AUDIO_SCRIPT) : "left alone with the setting off";
        plugin2.stop();
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
