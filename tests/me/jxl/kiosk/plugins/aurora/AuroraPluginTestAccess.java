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
    static class FakeShell implements Shell.Runner {
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
    static class FakeHost implements PluginHost {
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
        /** Every error status, since a later read replaces the line itself. */
        final List<String> errors = Collections.synchronizedList(new ArrayList<String>());
        @Override public void status(String message, boolean error) { status = message; statusError = error; if (error) errors.add(message); }
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
        steady();
        noSignal();
        refusedPicture();
        review();
        secondReview();
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
            "prev=$(getprop cur.appo.light.enabled); setprop cur.appo.light.enabled false"
            + " && { /vendor/bin/hw/projector-test setLightSourceOnOff false && echo " + Projector.HAL_DONE + " || { setprop cur.appo.light.enabled \"$prev\"; false; }; }"
            + " && setprop cur.prj.screenOff true && setprop cur.djc.picture_off true") : "off recipe: in order, the note last, only a refused HAL call undone (to the flag's own previous value)";
        assert Projector.pictureScript(true).endsWith("setprop cur.prj.screenOff false && setprop cur.djc.picture_off false") : "on recipe";
        assert !Projector.pictureScript(true).contains("|| { setprop cur.appo.light.enabled false") : "no rollback to an assumed value";
        assert Projector.pictureScript(false, Projector.HELD_SILENCE).contains("setprop cur.djc.picture_off silence") : "a dark for silence is held as that";
        Projector.State quiet = Projector.parse("held=silence\n");
        assert Boolean.TRUE.equals(quiet.heldOff) && quiet.heldForSilence : "held=silence: held, for silence";
        assert !Projector.parse("held=true\n").heldForSilence : "held=true: someone asked";
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
        // The picture first; the bar follows only once that worked, then the read.
        assert shell.scripts.get(before).equals(Projector.pictureScript(false)) : "switch off script: " + shell.scripts.get(before);
        assert shell.scripts.get(before + 1).equals(Projector.POLL_SCRIPT) : "a read follows every command";
        assert shell.scripts.get(before + 2).equals(Projector.ledsScript(Projector.LED_STANDBY)) : "the bar follows the picture off: " + shell.scripts.subList(before, shell.scripts.size());
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
    static class CecAdb extends FakeAdb {
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
        adb.live = 2;                      // the Apple TV's input (port 2) is the one held
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
        assert Double.valueOf(17).equals(host.sensors.get("cpu_busy")) : "cpu busy, whole percent (16.7 read): " + host.sensors.get("cpu_busy");
        assert host.tiles.get("projector").endsWith("\u00b7 CPU 17%") : "tile: " + host.tiles.get("projector");
        plugin.stop();
    }

    /** A light-source command older than the plugin's own last picture command is ignored: the
     *  newer one was pruned from the log. */
    /** A projector that keeps its laser, light flag, screen-off flag and hold note. Picture and
     *  flag scripts are run for real, in bash, against stand-ins for getprop, setprop and the HAL
     *  tool, so the scripts' own logic (order, rollback, the HAL-done mark) is what is tested;
     *  polls report the properties. The HAL can be made to refuse, and the screen-off write to
     *  fail. Answers as adbd, so it is every channel. */
    static class Lamp extends CecAdb {
        volatile boolean light = true;        // the laser itself
        volatile boolean flag = true;         // cur.appo.light.enabled
        volatile String held = "";
        volatile boolean halFails, screenOffFails;
        /** Extra poll lines (an AT+LightSource from the HAL's log). */
        volatile String extraPoll = "";
        @Override public Shell.Result run(String script, int timeoutMs) {
            if (script.equals(Projector.POLL_SCRIPT)) {
                scripts.add(script);
                return new Shell.Result(0, POLL_ANSWER.replace("light=true", "light=" + flag + "\nheld=" + held).replace("screenoff=false", "screenoff=" + !flag) + extraPoll, "", false);
            }
            if (script.contains("cur.appo.light.enabled") || script.contains(Projector.HELD_PROP) || script.contains("cur.prj.screenOff")) {
                scripts.add(script);
                return bash(script);
            }
            return super.run(script, timeoutMs);
        }

        private synchronized Shell.Result bash(String script) {
            String sh = "LASER=" + light + "; LIGHT=" + flag + "; SO=" + !flag + "; HELD='" + held + "'\n"
                + "getprop() { case $1 in cur.appo.light.enabled) echo \"$LIGHT\";; cur.prj.screenOff) echo \"$SO\";; " + Projector.HELD_PROP + ") echo \"$HELD\";; esac; }\n"
                + "setprop() { case $1 in cur.appo.light.enabled) LIGHT=$2;; cur.prj.screenOff) [ " + (screenOffFails ? 1 : 0) + " = 1 ] && return 1; SO=$2;; " + Projector.HELD_PROP + ") HELD=$2;; esac; }\n"
                + "tool() { [ " + (halFails ? 1 : 0) + " = 1 ] && { echo 'HAL refused' >&2; return 1; }; LASER=$2; }\n"
                + script.replace(Projector.TOOL, "tool") + "\n"
                + "rc=$?; echo \"__lamp $rc $LASER $LIGHT $HELD\"\n";
            try {
                Process p = new ProcessBuilder("bash", "-c", sh).start();
                String out = new String(readAll(p.getInputStream()), java.nio.charset.StandardCharsets.UTF_8);
                String err = new String(readAll(p.getErrorStream()), java.nio.charset.StandardCharsets.UTF_8);
                p.waitFor();
                int at = out.lastIndexOf("__lamp ");
                String[] st = out.substring(at).trim().split(" ", -1);
                light = Boolean.parseBoolean(st[2]);
                flag = Boolean.parseBoolean(st[3]);
                held = st.length > 4 ? st[4] : "";
                return new Shell.Result(Integer.parseInt(st[1]), out.substring(0, at), err, false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    static AuroraPlugin lampPlugin(final Lamp lamp) {
        AuroraPlugin plugin = new AuroraPlugin(new FakeShell(), null, new AuroraPlugin.AdbFactory() { @Override public Shell.Runner create(int port) { return lamp; } });
        plugin.silenceMinuteMs = 100;
        return plugin;
    }

    static Map<String, Object> silenceSettings() {
        Map<String, Object> st = settings("ADB", 30);
        st.put("darkWithoutSignal", 1);
        st.put("followSource", false);
        return st;
    }

    /** Silence past the (100 ms) minute: the first reading starts the clock, the next acts. */
    static void silentPastTheMinute(AuroraPlugin plugin) throws InterruptedException {
        plugin.watchSignal(false);
        Thread.sleep(150);
        plugin.watchSignal(false);
    }

    /** A lit picture on a silent HDMI input goes dark after the set time and comes back with the
     *  signal; anything someone did to the picture since wins, and so does a changed context. */
    static void noSignal() throws Exception {
        final Lamp lamp = new Lamp();
        lamp.live = 3;
        final FakeHost host = new FakeHost();
        final AuroraPlugin plugin = lampPlugin(lamp);
        plugin.start(host, silenceSettings());
        waitFor(host, "picture");
        waitUntil(new Check() { public boolean ok() { return "HDMI 3".equals(host.texts.get("showing")); } }, "showing HDMI 3: " + host.texts);

        plugin.watchSignal(false);
        Thread.sleep(50);
        plugin.watchSignal(false);
        Thread.sleep(150);
        assert lamp.light : "half the time is not enough";
        plugin.watchSignal(false);
        waitUntil(new Check() { public boolean ok() { return !lamp.light && "silence".equals(lamp.held); } }, "dark, held for silence: light " + lamp.light + " held " + lamp.held);

        plugin.watchSignal(true);
        waitUntil(new Check() { public boolean ok() { return lamp.light && "false".equals(lamp.held); } }, "back with the signal");

        // A restore the HAL refuses keeps its claim and is tried again on the next reading.
        silentPastTheMinute(plugin);
        waitUntil(new Check() { public boolean ok() { return !lamp.light; } }, "dark again");
        lamp.halFails = true;
        plugin.watchSignal(true);
        Thread.sleep(300);
        assert !lamp.light && "silence".equals(lamp.held) : "a refused restore changes nothing";
        assert host.errors.toString().contains("HAL refused") : "and says so: " + host.errors;
        lamp.halFails = false;
        plugin.watchSignal(true);
        waitUntil(new Check() { public boolean ok() { return lamp.light; } }, "the next reading restores it");

        // A manual Off as the signal returns: the hand wins, whichever came first.
        silentPastTheMinute(plugin);
        waitUntil(new Check() { public boolean ok() { return !lamp.light; } }, "dark for silence");
        plugin.onEvent("switch.picture", Collections.<String, Object>singletonMap("on", false));
        plugin.watchSignal(true);
        Thread.sleep(400);
        assert !lamp.light && "true".equals(lamp.held) : "switched off by hand: stays off, held as theirs (" + lamp.held + ")";
        plugin.onEvent("switch.picture", Collections.<String, Object>singletonMap("on", true));
        waitUntil(new Check() { public boolean ok() { return lamp.light; } }, "on by hand");

        // A manual On restarts the clock: silence before it does not count after it.
        plugin.watchSignal(false);
        Thread.sleep(80);
        plugin.onEvent("switch.picture", Collections.<String, Object>singletonMap("on", true));
        Thread.sleep(100);
        plugin.watchSignal(false);
        Thread.sleep(40);
        plugin.watchSignal(false);
        Thread.sleep(200);
        assert lamp.light : "silence from before the manual On does not count";

        // An app in front when the time comes: nothing.
        plugin.watchSignal(true);
        plugin.watchSignal(false);
        Thread.sleep(150);
        lamp.foreground = APP_FRONT;
        plugin.watchSignal(false);
        Thread.sleep(300);
        assert lamp.light : "an app in front is not darkened";
        lamp.foreground = TV_FRONT;

        // The input changed under the clock: it starts over.
        plugin.watchSignal(true);
        plugin.watchSignal(false);
        Thread.sleep(150);
        lamp.live = 1;
        plugin.watchSignal(false);
        Thread.sleep(300);
        assert lamp.light : "silence on HDMI 3 does not darken HDMI 1";
        lamp.live = 3;
        plugin.stop();

        // A restart keeps the reason: a dark chosen for silence still comes back with the signal...
        lamp.light = false;
        lamp.flag = false;
        lamp.held = "silence";
        FakeHost host2 = new FakeHost();
        AuroraPlugin again = lampPlugin(lamp);
        again.start(host2, silenceSettings());
        waitFor(host2, "picture");
        again.watchSignal(true);
        waitUntil(new Check() { public boolean ok() { return lamp.light; } }, "restored after a restart");
        again.stop();
        // ...and one someone asked for does not.
        lamp.light = false;
        lamp.flag = false;
        lamp.held = "true";
        FakeHost host3 = new FakeHost();
        AuroraPlugin third = lampPlugin(lamp);
        third.start(host3, silenceSettings());
        waitFor(host3, "picture");
        third.watchSignal(true);
        Thread.sleep(400);
        assert !lamp.light : "a dark someone asked for survives a restart and the signal";
        third.stop();
    }

    /** A picture command the HAL refuses is not recorded as done: no hold, no LEDs, a status. */
    static void refusedPicture() throws Exception {
        final Lamp lamp = new Lamp();
        lamp.live = 3;
        FakeHost host = new FakeHost();
        AuroraPlugin plugin = lampPlugin(lamp);
        Map<String, Object> st = silenceSettings();
        st.put("ledsFollowPicture", true);
        plugin.start(host, st);
        waitFor(host, "picture");
        Thread.sleep(200);
        int leds = 0;
        for (String x : lamp.scripts) if (x.startsWith(Projector.ledsScript(Projector.LED_STANDBY))) leds++;
        lamp.halFails = true;
        plugin.onEvent("switch.picture", Collections.<String, Object>singletonMap("on", false));
        Thread.sleep(400);
        assert lamp.light && "".equals(lamp.held) : "nothing changed on the projector";
        assert Boolean.TRUE.equals(host.switches.get("picture")) : "the switch still says on";
        int after = 0;
        for (String x : lamp.scripts) if (x.startsWith(Projector.ledsScript(Projector.LED_STANDBY))) after++;
        assert after == leds : "the bar did not go to standby for a picture that stayed on";
        assert host.errors.toString().contains("HAL refused") : "the refusal is reported: " + host.errors;
        plugin.stop();
    }

    /** The 2026-10-12 review's findings, one check each. */
    static void review() throws Exception {
        // Partial helper output (an API call that failed is left out) parses without throwing.
        assert "Signal".equals(Projector.videoFormat(Projector.parseVendor("signal=true\n"))) : "signal alone";
        assert Projector.videoFormat(Projector.parseVendor("signal=true\nres=1920x1080,60,p\n")).startsWith("1920\u00d71080p 60 Hz") : "no HDR or depth";

        // The direct shell reports its pid first (for killing what it started on a timeout), and
        // that line never reaches the caller.
        assert Shell.pidOf(Shell.PID_MARK + "4242\nlight=true\n") == 4242 && Shell.pidOf("light=true\n") == 0 : "pid line";
        assert Shell.stripPid(Shell.PID_MARK + "4242\nlight=true\n").equals("light=true\n") && Shell.stripPid("x\n").equals("x\n") : "pid line stripped";

        // Every helper piece fits one Shizuku argument, and every helper run its timeout cap.
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < AuroraPlugin.VENDOR_CHUNK; i++) big.append('A');
        String piece = Projector.vendorChunkScript(Projector.vendorDexPath("0123456789ab"), "0123456789abcdef", big.toString(), false);
        assert piece.length() <= 4096 : "a helper piece is " + piece.length() + " characters, over Shizuku's 4096";
        assert AuroraPlugin.VENDOR_TIMEOUT_MS <= 30000 : "helper runs within Shizuku's 30 s";
        String finish = Projector.vendorFinishScript(Projector.vendorDexPath("0123456789ab"), "tok", "deadbeef");
        assert finish.contains("= deadbeef ]") && finish.contains("mv -f") && finish.contains("-mmin +60") : "checked, moved in one step, only stale builds removed: " + finish;
        assert "abc123".equals(Projector.vendorHash("sha1=abc123\n")) && Projector.vendorHash("sha1=\n") == null : "hash line";

        // A fan that could not be read keeps its slot: fans 3 and 4 stay fans 3 and 4.
        {
            FakeShell shell = new FakeShell();
            FakeHost host = new FakeHost();
            AuroraPlugin plugin = new AuroraPlugin(shell, null);
            plugin.start(host, settings("Direct", 30));
            waitFor(host, "picture");
            Map<String, String> v = new HashMap<>();
            v.put("fans", "1040,1180,1237,1040");
            plugin.publishVendor(v);
            v.put("fans", ",1180,1300,1040");
            plugin.publishVendor(v);
            assert host.sensors.get("fan1_rpm") == null && host.sensors.containsKey("fan1_rpm") : "fan 1 unknown: " + host.sensors;
            assert Double.valueOf(1300).equals(host.sensors.get("fan3_rpm")) : "fan 3 is still fan 3: " + host.sensors;
            plugin.stop();
        }

        // A temperature that stops reporting turns unknown instead of keeping its value for good.
        {
            FakeShell shell = new FakeShell();
            FakeHost host = new FakeHost();
            AuroraPlugin plugin = new AuroraPlugin(shell, null);
            plugin.start(host, settings("Direct", 30));
            waitFor(host, "picture");
            assert Double.valueOf(37).equals(host.sensors.get("temp_dmd")) : "dmd read";
            shell.pollAnswer = POLL_ANSWER.replace(",NtcDmd1:37", "");
            refreshAnswer(plugin, shell, shell.pollAnswer);
            assert host.sensors.containsKey("temp_dmd") && host.sensors.get("temp_dmd") == null : "dmd gone: unknown, " + host.sensors.get("temp_dmd");
            plugin.stop();
        }

        // Picture mode without the framework goes through the shell user, not the kiosk process.
        {
            FakeShell direct = new FakeShell();
            final FakeAdb adb = new FakeAdb();
            FakeHost host = new FakeHost();
            AuroraPlugin plugin = new AuroraPlugin(direct, null, new AuroraPlugin.AdbFactory() { @Override public Shell.Runner create(int port) { return adb; } });
            plugin.start(host, settings("Auto", 30));
            waitFor(host, "picture");
            plugin.onEvent("select.picture_mode", Collections.<String, Object>singletonMap("option", "Standard"));
            waitUntil(new Check() { public boolean ok() { return adb.scripts.toString().contains("settings put global picture_mode"); } }, "mode via the shell user: " + adb.scripts);
            assert !direct.scripts.toString().contains("settings put global picture_mode") : "not from the kiosk process";
            plugin.stop();
        }

        // A source's Standby checks the input on the wall now, not as of the last poll.
        {
            final Lamp lamp = new Lamp();
            lamp.live = 2;
            final FakeHost host = new FakeHost();
            AuroraPlugin plugin = lampPlugin(lamp);
            Map<String, Object> st = silenceSettings();
            st.put("followSource", true);
            plugin.start(host, st);
            waitFor(host, "picture");
            Thread.sleep(2500);                       // the CEC baseline read
            lamp.live = 3;                            // switched to the VM since the last poll
            lamp.history = lamp.history + CEC_SLEEP;  // the Apple TV (HDMI 2) sleeps
            waitUntil(new Check() { public boolean ok() { return lamp.appended.toString().contains("picture left on"); } }, "noted: " + lamp.appended);
            assert lamp.light : "the VM on HDMI 3 stays lit";
            plugin.stop();
        }

        // A channel that was not there at start is found later without a settings change.
        {
            FakeShell shell = new FakeShell();
            shell.probeOk = false;
            FakeHost host = new FakeHost();
            AuroraPlugin plugin = new AuroraPlugin(shell, null);
            plugin.redetectFirstSeconds = 1;
            plugin.start(host, settings("Direct", 30));
            Thread.sleep(500);
            assert !host.switches.containsKey("picture") : "no channel yet";
            shell.probeOk = true;
            waitFor(host, "picture");
            plugin.stop();
        }

        // stop() waits for work in flight, and nothing from the session runs after it returns.
        {
            final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicBoolean blocking = new java.util.concurrent.atomic.AtomicBoolean();
            final FakeShell shell = new FakeShell() {
                @Override public Shell.Result run(String script, int timeoutMs) {
                    if (blocking.get() && script.equals(Projector.POLL_SCRIPT)) {
                        scripts.add(script);
                        try { release.await(); } catch (InterruptedException e) { return Shell.Result.failure("interrupted"); }
                        return new Shell.Result(0, POLL_ANSWER.replace("sleep=0", "sleep=4"), "", false);
                    }
                    return super.run(script, timeoutMs);
                }
            };
            FakeHost host = new FakeHost();
            AuroraPlugin plugin = new AuroraPlugin(shell, null);
            plugin.start(host, settings("Direct", 30));
            waitFor(host, "picture");
            blocking.set(true);
            plugin.execute("refresh", Collections.<String, Object>emptyMap());
            Thread.sleep(200);                        // the poll is now blocked in the shell
            plugin.stop();
            int after = shell.scripts.size();
            release.countDown();
            Thread.sleep(300);
            assert shell.scripts.size() == after : "nothing ran after stop returned: " + shell.scripts.subList(after, shell.scripts.size());
        }
    }

    /** The second review (of 0.4.4): one check per finding that a fake can show. */
    static void secondReview() throws Exception {
        // 5: the HAL changed the light, then the screen-off write failed: the change still counts
        // (settled, held), the flags are tried again, and the next read does not undo it.
        {
            final Lamp lamp = new Lamp();
            lamp.live = 3;
            FakeHost host = new FakeHost();
            AuroraPlugin plugin = lampPlugin(lamp);
            plugin.start(host, silenceSettings());
            waitFor(host, "picture");
            lamp.screenOffFails = true;
            plugin.onEvent("switch.picture", Collections.<String, Object>singletonMap("on", false));
            waitUntil(new Check() { public boolean ok() { return !lamp.light; } }, "laser off");
            Thread.sleep(300);
            int flagScripts = 0;
            for (String x : lamp.scripts) if (x.equals(Projector.pictureFlagsScript(false, "true"))) flagScripts++;
            assert flagScripts == 1 : "the flags tried once more: " + flagScripts;
            assert host.errors.toString().contains("its flags could not be set") : "said so: " + host.errors;
            plugin.execute("refresh", Collections.<String, Object>emptyMap());
            Thread.sleep(300);
            assert !lamp.light && !lamp.flag : "still dark after the next read (the flag matches the laser)";
            assert Boolean.FALSE.equals(host.switches.get("picture")) : "and the switch says off";
            plugin.stop();
        }

        // 3: after a restart the HAL relit a held-off laser; the corrective Off is refused. It is
        // retried on later reads until it works, not forgotten once the log line is consumed.
        {
            final Lamp lamp = new Lamp();
            lamp.live = 3;
            lamp.light = true;
            lamp.flag = false;
            lamp.held = "true";
            lamp.halFails = true;
            lamp.extraPoll = "ls=" + System.currentTimeMillis() + " On\n";
            final FakeHost host = new FakeHost();
            AuroraPlugin plugin = lampPlugin(lamp);
            plugin.start(host, silenceSettings());
            waitUntil(new Check() { public boolean ok() { return Boolean.TRUE.equals(host.switches.get("picture")); } }, "a refused correction reports the light as lit: " + host.switches);
            plugin.execute("refresh", Collections.<String, Object>emptyMap());
            Thread.sleep(300);
            assert Boolean.TRUE.equals(host.switches.get("picture")) : "still owed, still lit on the next read (not 'off' from the flags)";
            lamp.halFails = false;
            plugin.execute("refresh", Collections.<String, Object>emptyMap());
            waitUntil(new Check() { public boolean ok() { return !lamp.light; } }, "the owed Off goes through once the HAL takes it");
            plugin.stop();
        }

        // 4: no telling what is in front (the foreground read fails): no automatic dark.
        {
            final Lamp lamp = new Lamp() {
                @Override public Shell.Result run(String script, int timeoutMs) {
                    if (script.equals(Projector.FOREGROUND_SCRIPT)) { scripts.add(script); return new Shell.Result(1, "", "dumpsys failed", false); }
                    return super.run(script, timeoutMs);
                }
            };
            lamp.live = 3;
            FakeHost host = new FakeHost();
            AuroraPlugin plugin = lampPlugin(lamp);
            plugin.start(host, silenceSettings());
            waitFor(host, "picture");
            plugin.watchSignal(false, "HDMI 3");
            Thread.sleep(150);
            plugin.watchSignal(false, "HDMI 3");
            Thread.sleep(300);
            assert lamp.light : "an unverified context is not darkened";
            // ...and no HDMI input held right now (an app took over): none either.
            plugin.stop();
        }
        {
            final Lamp lamp = new Lamp();
            lamp.live = 0;
            FakeHost host = new FakeHost();
            AuroraPlugin plugin = lampPlugin(lamp);
            plugin.start(host, silenceSettings());
            waitFor(host, "picture");
            plugin.watchSignal(false, "HDMI 3");
            Thread.sleep(150);
            plugin.watchSignal(false, "HDMI 3");
            Thread.sleep(300);
            assert lamp.light : "no input held now: not darkened";
            plugin.stop();
        }

        // 6: only a fresh full read drives it; a quick read's kept signal value does not.
        {
            final Lamp lamp = new Lamp();
            lamp.live = 3;
            FakeHost host = new FakeHost();
            AuroraPlugin plugin = lampPlugin(lamp);
            plugin.start(host, silenceSettings());
            waitFor(host, "picture");
            Map<String, String> quick = new HashMap<>();
            quick.put("signal", "false");
            quick.put("brightness", "0");
            plugin.publishVendor(quick);
            Thread.sleep(150);
            plugin.publishVendor(quick);
            Thread.sleep(300);
            assert lamp.light : "a stale signal value does not darken";
            plugin.stop();
        }

        // 2: Shizuku commands from two threads are one at a time and 250 ms apart, as the host
        // requires (it refuses, not queues, anything else).
        {
            final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.atomic.AtomicLong lastStart = new java.util.concurrent.atomic.AtomicLong();
            final List<String> refused = Collections.synchronizedList(new ArrayList<String>());
            FakeHost strict = new FakeHost() {
                @Override public void executeShizuku(String[] command, int timeoutMs, final CommandCallback callback) {
                    long now = System.currentTimeMillis();
                    if (pending.get() > 0) { refused.add("busy"); throw new IllegalStateException("one at a time"); }
                    if (now - lastStart.get() < 250) { refused.add("too soon " + (now - lastStart.get())); throw new IllegalStateException("too soon"); }
                    lastStart.set(now);
                    pending.incrementAndGet();
                    new Thread(new Runnable() { public void run() {
                        try { Thread.sleep(100); } catch (InterruptedException ignored) { /* done */ }
                        pending.decrementAndGet();
                        Map<String, Object> r = new HashMap<>();
                        r.put("exitCode", 0); r.put("stdout", "ok"); r.put("stderr", ""); r.put("timedOut", false);
                        callback.onResult(true, r, null);
                    } }).start();
                }
            };
            final Shell.Runner shz = Shell.shizuku(strict);
            Thread[] ts = new Thread[3];
            for (int i = 0; i < ts.length; i++) {
                ts[i] = new Thread(new Runnable() { public void run() { for (int k = 0; k < 3; k++) assert shz.run("true", 4000).ok() : "a Shizuku command failed"; } });
                ts[i].start();
            }
            for (Thread t : ts) t.join(20000);
            assert refused.isEmpty() : "the host refused: " + refused;
        }

        // 10: a helper run that prints only errors counts as a failure; three drop the helper.
        {
            final java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
            final FakeAdb adb = new FakeAdb() {
                @Override public Shell.Result run(String script, int timeoutMs) {
                    if (script.startsWith("CLASSPATH=/data/local/tmp/aurora-vendor-test.dex ")) { scripts.add(script); runs.incrementAndGet(); return new Shell.Result(0, "error.brightness=no method\n", "", false); }
                    return super.run(script, timeoutMs);
                }
            };
            FakeShell direct = new FakeShell();
            FakeHost host = new FakeHost();
            final AuroraPlugin plugin = new AuroraPlugin(direct, null, new AuroraPlugin.AdbFactory() { @Override public Shell.Runner create(int port) { return adb; } });
            plugin.useVendor("/data/local/tmp/aurora-vendor-test.dex");
            Map<String, Object> st = settings("Auto", 30);
            st.put("vendorSeconds", 30);
            plugin.start(host, st);
            waitFor(host, "picture");
            for (int i = 0; i < 3; i++) plugin.onEvent("switch.low_latency", Collections.<String, Object>singletonMap("on", true));
            waitUntil(new Check() { public boolean ok() { return plugin.vendorPathForTests() == null; } }, "three runs with nothing usable drop the helper");
            assert runs.get() == 3 : "dropped after exactly three: " + runs.get();
            plugin.stop();
        }

        // 7: a timed-out direct script's whole process group goes, not only its children.
        assert Shell.killScript(4242, true).startsWith("kill -9 -- -4242") && Shell.killScript(4242, false).equals("pkill -9 -P 4242 2>/dev/null; true") : "kill scripts";

        // 1: stop() returns inside the host's deadline even with a call that ignores interrupts,
        // and the session's channels refuse anything after.
        {
            final java.util.concurrent.atomic.AtomicBoolean hold = new java.util.concurrent.atomic.AtomicBoolean();
            final FakeShell shell = new FakeShell() {
                @Override public Shell.Result run(String script, int timeoutMs) {
                    if (hold.get() && script.equals(Projector.POLL_SCRIPT)) {
                        scripts.add(script);
                        long until = System.currentTimeMillis() + 5000;
                        while (hold.get() && System.currentTimeMillis() < until) { /* deaf to interrupts */ }
                        return new Shell.Result(0, POLL_ANSWER.replace("sleep=0", "sleep=4"), "", false);
                    }
                    return super.run(script, timeoutMs);
                }
            };
            FakeHost host = new FakeHost();
            AuroraPlugin plugin = new AuroraPlugin(shell, null);
            plugin.start(host, settings("Direct", 30));
            waitFor(host, "picture");
            hold.set(true);
            plugin.execute("refresh", Collections.<String, Object>emptyMap());
            Thread.sleep(200);
            long t0 = System.currentTimeMillis();
            plugin.stop();
            long took = System.currentTimeMillis() - t0;
            assert took < 3000 : "stop() took " + took + " ms, over the host's 3 s";
            int after = shell.scripts.size();
            hold.set(false);
            Thread.sleep(400);
            assert shell.scripts.size() == after : "nothing reached the projector after stop: " + shell.scripts.subList(after, shell.scripts.size());
        }
    }

    /** Wobble inside the step stays home; a real move, unknown, or old age goes out. */
    static void steady() {
        final Steady st = new Steady();
        final long t = 1_000_000;
        // due() and, when it says so, sent(): what a publish that went through does.
        class Send { boolean go(String k, Double v, double step, long age, long now) { if (!st.due(k, v, step, age, now)) return false; st.sent(k, v, now); return true; } }
        Send s = new Send();
        assert s.go("fan1_rpm", 1096.0, 50, 1800_000, t) : "the first reading always goes";
        assert !s.go("fan1_rpm", 1112.0, 50, 1800_000, t + 60_000) : "16 rpm of jitter stays out";
        assert !s.go("fan1_rpm", 1081.0, 50, 1800_000, t + 120_000) : "jitter is measured from the value sent, not the last read";
        assert s.go("fan1_rpm", 1150.0, 50, 1800_000, t + 180_000) : "a 54 rpm move goes";
        assert !s.go("fan1_rpm", 1160.0, 50, 1800_000, t + 240_000) : "and becomes the new reference";
        assert s.go("fan1_rpm", 1160.0, 50, 1800_000, t + 180_000 + 1800_000) : "half an hour on, the current value goes anyway";
        assert s.go("fan1_rpm", null, 50, 1800_000, t + 3_700_000) : "turning unknown goes";
        assert !s.go("fan1_rpm", null, 50, 1800_000, t + 9_000_000) : "staying unknown does not";
        assert s.go("fan1_rpm", 1100.0, 50, 1800_000, t + 9_060_000) : "coming back goes";
        assert s.go("laser_hours", 571.7, 0.05, 3600_000, t) && !s.go("laser_hours", 571.7, 0.05, 3600_000, t + 60_000)
            && s.go("laser_hours", 571.8, 0.05, 3600_000, t + 360_000) : "laser hours go on every tenth";
        // A publish the host refused (its per-second budget) is not recorded: the next try goes.
        try {
            st.publish("cpu_busy", 40.0, 5, 300_000, t, new Runnable() { public void run() { throw new IllegalStateException("At most 64 entity changes per second"); } });
            assert false : "the refusal is passed on";
        } catch (IllegalStateException expected) { /* passed on */ }
        assert st.due("cpu_busy", 40.0, 5, 300_000, t + 1000) : "a refused value is still due";
        try {
            st.publish("cpu_busy", null, 5, 300_000, t + 2000, new Runnable() { public void run() { throw new IllegalStateException("budget"); } });
        } catch (IllegalStateException expected) { /* refused */ }
        assert st.due("cpu_busy", null, 5, 300_000, t + 3000) : "a refused turn to unknown is still due, not lost for good";
        st.clear();
        assert s.go("fan1_rpm", 1100.0, 50, 1800_000, t + 9_120_000) : "a fresh start sends everything again";
    }

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
