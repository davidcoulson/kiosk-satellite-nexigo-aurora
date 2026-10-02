// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.aurora;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the Aurora Pro's Android side answers to, as fixed shell scripts and their parsers.
 *
 * Everything here is a string the plugin hands to {@code /system/bin/sh -c}; nothing is built
 * from user input except through the tables below, so a Home Assistant option can only ever
 * become one of the numbers listed here. The commands and their reasons are documented in the
 * repository's docs/ (behaviors.md for the picture recipe, hal-api.md for the tool).
 */
final class Projector {
    private Projector() {}

    /** The vendor's test client for the projector-manager HAL: world-executable, no root. */
    static final String TOOL = "/vendor/bin/hw/projector-test";
    /** The projector's own settings app (keystone, focus, brightness mode...), not Android's. */
    static final String SETTINGS_ACTIVITY = "com.xming.xmprojectorsettings/.ProjectorSettingActivity";

    static final String[] INPUTS = {"HDMI 1", "HDMI 2", "HDMI 3", "HDMI 4"};
    /** MediaTek TV-input hardware ids: HW5..HW8 are HDMI ports 1..4. */
    static final int[] INPUT_IDS = {5, 6, 7, 8};

    static final String[] PICTURE_MODES = {"Cinema Home", "Cinema Pro", "Standard", "Brightest", "Game", "Custom"};
    /** Settings.Global picture_mode values on the Aurora Pro. */
    static final int[] PICTURE_MODE_IDS = {2, 8, 9, 3, 10, 0};

    /**
     * Front LED bar patterns: {@code setAppoLeds 2 <APPO_LED_STATUS>}, verified on the bar.
     * "Standby" is what the projector shows in real standby; "Power on" is the boot animation.
     */
    static final String[] LEDS = {"Off", "Standby", "Power on", "Loop", "Bluetooth", "Update"};
    static final int[] LED_IDS = {6, 2, 0, 5, 4, 3};
    static final int LED_OFF = 6;
    static final int LED_STANDBY = 2;

    /**
     * The power menu's "Screen off", step for step: flag the light as deliberately off, tell the
     * light engine, then flag the screen-off state. Without the two flags the eye-protection
     * service re-lights the laser or the projector drops to standby.
     */
    /**
     * This plugin's own note that it turned the picture off. The vendor's screen-off flag is
     * cleared whenever Android wakes its display (a Kiosk Satellite restart or update does it),
     * and without it the firmware re-lights the laser; the note says to put it back. A cur.*
     * property outlives the app and the plugin and is gone after a boot, like the flags.
     */
    static final String HELD_PROP = "cur.djc.picture_off";

    static String pictureScript(boolean on) {
        String flag = on ? "true" : "false";
        return "setprop cur.appo.light.enabled " + flag
            + " && " + TOOL + " setLightSourceOnOff " + flag
            + " && setprop cur.prj.screenOff " + (on ? "false" : "true")
            + "; setprop " + HELD_PROP + " " + (on ? "false" : "true");
    }

    static String ledsScript(int status) {
        return TOOL + " setAppoLeds 2 " + status;
    }

    /**
     * Starts an activity from the kiosk's own process. {@code am} run by an app (not the shell
     * user) needs the user spelled out, or it starts nothing and says nothing.
     */
    private static final String AM_START = "am start --user 0";

    /** Selects an HDMI port by opening the TV input framework's pass-through URI. */
    static String inputScript(int hw) {
        return AM_START + " -a android.intent.action.VIEW -d"
            + " content://android.media.tv/passthrough/com.mediatek.tvinput%2F.hdmi.HDMIInputService%2FHW" + hw;
    }

    /** picture_mode is a global setting the projector's ContentObserverService applies. */
    static String pictureModeScript(int mode) {
        return "settings put global picture_mode " + mode;
    }

    static String openSettingsScript() {
        return AM_START + " -n " + SETTINGS_ACTIVITY;
    }

    /** No-signal shutdown off ("Close" in the projector's menu; 1-5 are 5, 10, 15, 30 and 60 min). */
    static final int NO_SIGNAL_OFF = 0;

    /** The vendor sleep timer off. It shipped on (4 on this unit) and stood the projector by
     *  about two hours after the last activity, which took it off the network on 2026-09-25. */
    static final int SLEEP_OFF = 0;

    /**
     * Keep the projector on the network: ignore the CEC standby a sleeping source broadcasts
     * (the Apple TV's "Control TVs and receivers" does), turn off the sleep timer, and turn off
     * the no-signal shutdown. The properties are the vendor's own switches (its SleepmodeService
     * reads the timer's on every check); the setting needs WRITE_SECURE_SETTINGS, so it may fail
     * on the direct channel and is reported from the read-back instead.
     */
    /** Android's CEC system-audio and ARC control: with both on, ARC to a soundbar on HDMI 1 is
     *  established and the projector's own apps play through the bar. Both shipped off, and on
     *  2026-09-30 a standby put them back to off after they had been turned on. */
    static final String[] ARC_SETTINGS = {"hdmi_system_audio_control_enabled", "hdmi_arc_control_enabled"};
    static final String ARC_AUDIO_SCRIPT = "settings put global " + ARC_SETTINGS[0] + " 1; settings put global " + ARC_SETTINGS[1] + " 1; true";

    static final String STAY_ON_SCRIPT = "setprop persist.appo.ignore.cec.standby true;"
        + " setprop persist.prj.sleepMode " + SLEEP_OFF + ";"
        + " settings put global no_signal_auto_power_off " + NO_SIGNAL_OFF + " 2>/dev/null; true";

    /** Re-asserts the deliberate-dark flag alone; see AuroraPlugin.poll. */
    static final String REFLAG_SCREEN_OFF_SCRIPT = "setprop cur.prj.screenOff true";
    /**
     * Brings the intent flags back in line with a light the projector lit or darkened itself. A
     * light that came on is no longer a deliberate dark, so the screen-off flag is cleared with it;
     * a light that went out on its own is only recorded, never promoted to a deliberate dark.
     */
    static String reflagLightScript(boolean on) {
        return on ? "setprop cur.appo.light.enabled true; setprop cur.prj.screenOff false; setprop " + HELD_PROP + " false"
            : "setprop cur.appo.light.enabled false";
    }

    /** appothermal's commanded fan speed, logged with every temperature read (every 30 s). */
    static final String FAN_LINE = " echo \"fan=$(logcat -d --pid=$(pidof appothermal) 2>/dev/null | grep -oE 'Thermal speed:[0-9]+' | tail -1)\"";

    /** The light engine's 30-second temperature report. Read from the HAL service's own lines:
     *  the log is chatty enough that the last few hundred lines often miss it. */
    static final String TEMPS_LINE = "echo \"temps=$(logcat -d --pid=$(pidof vendor.appotronics.projectormanager@1.0-service) 2>/dev/null | grep -oE 'AT\\+Temperature#[A-Za-z0-9:,]+' | tail -1)\";";

    /** The last light-source command the HAL sent the light engine, whoever asked for it: this
     *  plugin, the remote, or the vendor's own services lighting it for the Android UI (which
     *  leave the flags alone). The one direct readback of the laser. */
    static final String LIGHT_SOURCE_LINE = " echo \"ls=$(logcat -d -v epoch --pid=$(pidof vendor.appotronics.projectormanager@1.0-service) 2>/dev/null"
        + " | grep -E 'AT\\+LightSource=(On|Off)' | tail -1 | sed -E 's/^ *([0-9]+)\\.([0-9]{3}).*AT\\+LightSource=(On|Off).*/\\1\\2 \\3/')\";";

    /** The log lines alone, for a shell-user channel behind a direct one. */
    /** The kernel's CPU time counters since boot. KS's own CPU sensor can't read them from the app
     *  sandbox and falls back to the clock position on this chip (no cpuidle), which reads 100 %
     *  whenever the governor holds 1.3 GHz. The shell user reads them fine. */
    static final String CPU_LINE = " echo \"cpu=$(head -1 /proc/stat 2>/dev/null)\";";

    static final String TEMPS_SCRIPT = TEMPS_LINE + LIGHT_SOURCE_LINE + FAN_LINE + ";" + CPU_LINE;

    /** Proves a loopback ADB session is the shell user, which is the point of it. */
    static final String ADB_PROBE_SCRIPT = "id";

    /**
     * Starts Shizuku from its own APK when it is installed and not running: what its start.sh
     * does, minus the file it writes to the SD card. Harmless when it is already up (its starter
     * replaces the old server) or not installed (nothing found, exit 0).
     */
    static final String SHIZUKU_START_SCRIPT = "if ! pidof shizuku_server >/dev/null 2>&1; then"
        + " APK=$(pm path moe.shizuku.privileged.api 2>/dev/null | head -1 | cut -d: -f2);"
        + " ST=$(ls $(dirname \"$APK\")/lib/*/libshizuku.so 2>/dev/null | head -1);"
        + " [ -n \"$APK\" ] && [ -n \"$ST\" ] && \"$ST\" --apk=\"$APK\" >/dev/null 2>&1; fi; true";

    /** Proves the channel can run the tool and read properties; the first thing a session does. */
    static final String PROBE_SCRIPT = "test -x " + TOOL + " && getprop ro.product.model";

    /**
     * One round trip for everything the entities show. Each line is key=value; a reading the
     * channel cannot make (logcat needs the shell user) comes back empty and parses to unknown.
     */
    static final String POLL_SCRIPT =
        "echo \"light=$(getprop cur.appo.light.enabled)\";"
        + " echo \"screenoff=$(getprop cur.prj.screenOff)\";"
        + " echo \"held=$(getprop " + HELD_PROP + ")\";"
        + " echo \"source=$(getprop cur.prj.currentSourceId)\";"
        + " echo \"mode=$(settings get global picture_mode 2>/dev/null)\";"
        + " echo \"minutes=$(" + TOOL + " getPlatformProperty used_time 2>/dev/null | grep -oE '[0-9]+' | tail -1)\";"
        + " echo \"wdt=$(" + TOOL + " getPlatformProperty laser_used_time_wdt 2>/dev/null | grep -oE '[0-9]+' | tail -1)\";"
        + " " + TEMPS_LINE
        + LIGHT_SOURCE_LINE
        + CPU_LINE
        + FAN_LINE + ";"
        + " echo \"leds=$(cat /sys/class/appo_led_pwm_pm/appo_led_pwm_pm/led_pwm 2>/dev/null)\";"
        + " echo \"boot=$(settings get global boot_source_id 2>/dev/null)\";"
        + " echo \"cec=$(getprop persist.appo.ignore.cec.standby)\";"
        + " echo \"sleep=$(getprop persist.prj.sleepMode)\";"
        + " echo \"nosignal=$(settings get global no_signal_auto_power_off 2>/dev/null)\"";

    /** The sensor that tells a lit laser: the blue laser runs ~53 °C lit and 26-28 °C dark in a
     *  22-23 °C room (2026-09-25/26). */
    static final String LASER_NTC = "NtcBlueLaser1";
    static final String AMBIENT_NTC = "NtcEnv1";
    /** The three laser banks, for the single hottest-laser reading. */
    static final String[] LASER_NTCS = {"NtcRedLaser1", "NtcGreenLaser1", "NtcBlueLaser1"};

    /** The warmest laser bank, or null when the log gave none of them. */
    static Double hottestLaser(State s) {
        Double hottest = null;
        for (String k : LASER_NTCS) {
            Double v = s.temperatures.get(k);
            if (v != null && (hottest == null || v > hottest)) hottest = v;
        }
        return hottest;
    }
    /** Over ambient by this much and heating by at least HEATING_STEP since the last read: lit.
     *  Heating, not merely hot: a cooling laser plateaus (the readings are whole degrees), and a
     *  plateau must not read as lit. */
    static final double LIT_OVER_AMBIENT = 12;
    static final double HEATING_STEP = 2;
    /** The heat never says dark. The laser dims with the picture: a dark scene, an input
     *  switching or a black screen cools it 6 °C in 30 s and can leave it near idle for hours
     *  (2026-10-01 20:10: an Unraid desktop coming up on HDMI 2 read as "laser off" and lit the
     *  standby LEDs over a lit picture). Dark comes from the flags or the HAL's own Off. */
    /** How long after a picture command the heat is not trusted: the log line can be 30 s old and
     *  the laser takes a minute or more to warm or cool across the thresholds. */
    static final long LASER_SETTLE_MS = 180_000L;
    /** How long after the plugin (so Kiosk Satellite) starts a laser lit behind a held-off picture
     *  is put back out rather than reported: the start itself is what lit it. */
    static final long RESTART_GUARD_MS = 300_000L;

    /**
     * What the laser's heat says about the light: true when well over ambient and heating fast (a
     * laser lit behind the flags' back), otherwise null. Never false: see above.
     */
    static Boolean laserLit(State s, Double previousBlue) {
        Double blue = s.temperatures.get(LASER_NTC), ambient = s.temperatures.get(AMBIENT_NTC);
        if (blue == null || ambient == null || previousBlue == null) return null;
        return blue - ambient >= LIT_OVER_AMBIENT && blue - previousBlue >= HEATING_STEP ? Boolean.TRUE : null;
    }

    /** The light engine's NTC names as the HAL logs them, and the entity keys they become. The
     *  three laser banks are read (the light check, the hottest-laser sensor) but not published
     *  one by one: Laser temperature stands for them. */
    static final String[][] TEMPERATURES = {
        {"NtcCw1", "color_wheel", "Colour wheel"},
        {"NtcDmd1", "dmd", "DMD"},
        {"NtcEnv1", "ambient", "Ambient"},
        {"NtcXpr1", "xpr", "XPR"},
        {"NtcPowerSupply", "power_supply", "Power supply"},
    };

    /*
     * Laser hours come from the HAL's own store: "used_time" is the lifetime light-source
     * minutes (the value the projector's menu shows); "laser_used_time_wdt" next to it is only
     * the minutes since the HAL last folded them in, and resets every few minutes.
     */

    /** A snapshot of what the projector reported; null fields are unknown. */
    static final class State {
        Boolean light;
        Boolean screenOff;
        Integer sourceId;
        Integer pictureMode;
        Long laserMinutes;
        /** The HAL's minute counter since it last saved the hours. Read for the record only: it
         *  climbs with the laser cold, so it says nothing about the light. */
        Integer laserWdt;
        Integer ledPwm;
        Integer bootSourceId;
        Boolean ignoreCecStandby;
        Integer noSignalOff;
        Integer sleepMode;
        /** This plugin turned the picture off and nothing has lit it since (see HELD_PROP). */
        Boolean heldOff;
        /** The last AT+LightSource command in the HAL's log: true On, false Off, null none seen. */
        Boolean lightSource;
        /** When that command was logged (epoch ms), 0 when unknown. The log prunes busy
         *  processes, so a newer command can be missing while an older one is still there. */
        long lightSourceAt;
        /** CPU time since boot from /proc/stat: busy and total jiffies, 0 when unread. */
        long cpuBusy, cpuTotal;
        /** appothermal's commanded fan speed in percent. */
        Integer fanPercent;
        final Map<String, Double> temperatures = new LinkedHashMap<>();

        /** The vendor sets cur.prj.currentSourceId only from its own source menu; after a boot it
         *  is the boot source until then. */
        String input() { return label(INPUTS, INPUT_IDS, sourceId != null ? sourceId : bootSourceId); }
        /** Every guard in place: CEC standby ignored, the sleep timer and the no-signal shutdown off. */
        Boolean staysOn() {
            if (ignoreCecStandby == null || noSignalOff == null || sleepMode == null) return null;
            return ignoreCecStandby && noSignalOff == NO_SIGNAL_OFF && sleepMode == SLEEP_OFF;
        }
        String pictureModeLabel() { return label(PICTURE_MODES, PICTURE_MODE_IDS, pictureMode); }
    }

    static State parse(String output) {
        State s = new State();
        if (output == null) return s;
        for (String line : output.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (value.isEmpty()) continue;
            apply(s, key, value);
        }
        // A fresh boot has set neither flag yet, and a projector that just booted has its light
        // on: that is the one case the properties cannot describe, so it is read as on.
        if (s.light == null && s.screenOff == null) { s.light = Boolean.TRUE; s.screenOff = Boolean.FALSE; }
        return s;
    }

    /** One key=value line of the poll output, or a framework reading, into the state. */
    static void apply(State s, String key, String value) {
        {
            switch (key) {
                case "light": s.light = bool(value); break;
                case "screenoff": s.screenOff = bool(value); break;
                // -1 while the picture is off: no input is being shown, which is not "unknown
                // input", so it is dropped and the boot source or the last command stands.
                case "source": { Integer id = integer(value); s.sourceId = id == null || id < 0 ? null : id; break; }
                case "mode": s.pictureMode = integer(value); break;
                case "minutes": { Integer m = integer(value); if (m != null) s.laserMinutes = m.longValue(); break; }
                case "wdt": s.laserWdt = integer(value); break;
                case "leds": s.ledPwm = integer(value); break;
                case "boot": s.bootSourceId = integer(value); break;
                case "cec": s.ignoreCecStandby = bool(value); break;
                case "nosignal": s.noSignalOff = integer(value); break;
                case "sleep": s.sleepMode = integer(value); break;
                case "held": s.heldOff = bool(value); break;
                case "cpu": {
                    long[] c = cpuTimes(value);
                    if (c != null) { s.cpuBusy = c[0]; s.cpuTotal = c[1]; }
                    break;
                }
                case "ls": {
                    s.lightSource = value.endsWith("On") ? Boolean.TRUE : value.endsWith("Off") ? Boolean.FALSE : null;
                    int sp = value.indexOf(' ');
                    if (sp > 0) { try { s.lightSourceAt = Long.parseLong(value.substring(0, sp)); } catch (NumberFormatException ignored) { s.lightSourceAt = 0; } }
                    break;
                }
                case "fan": s.fanPercent = integer(value.substring(value.indexOf(':') + 1)); break;
                case "temps": parseTemperatures(value, s); break;
                default: break;
            }
        }
    }

    /** {@code AT+Temperature#NtcRedLaser1:28,NtcGreenLaser1:25,...} */
    static void parseTemperatures(String value, State s) {
        int hash = value.indexOf('#');
        if (hash < 0) return;
        for (String pair : value.substring(hash + 1).split(",")) {
            int colon = pair.indexOf(':');
            if (colon < 0) continue;
            Integer t = integer(pair.substring(colon + 1).trim());
            if (t != null) s.temperatures.put(pair.substring(0, colon).trim(), t.doubleValue());
        }
    }

    static int indexOf(String[] labels, Object label) {
        for (int i = 0; i < labels.length; i++) if (labels[i].equals(label)) return i;
        return -1;
    }

    static Integer idFor(String[] labels, int[] ids, Object label) {
        int i = indexOf(labels, label);
        return i < 0 ? null : ids[i];
    }

    private static String label(String[] labels, int[] ids, Integer id) {
        if (id == null) return null;
        for (int i = 0; i < ids.length; i++) if (ids[i] == id) return labels[i];
        return null;
    }

    private static Boolean bool(String v) {
        String x = v.toLowerCase(Locale.ROOT);
        if (x.equals("true") || x.equals("1")) return Boolean.TRUE;
        if (x.equals("false") || x.equals("0")) return Boolean.FALSE;
        return null;
    }

    private static Integer integer(String v) {
        try { return Integer.valueOf(v.trim()); } catch (NumberFormatException e) { return null; }
    }

    // ------------------------------------------------------------------ HDMI-CEC

    /**
     * Android's HDMI-CEC service keeps every message it sent [S] and received [R] with a timestamp;
     * logcat does not carry them. Reading it needs the shell user (DUMP), and takes ~20 ms.
     */
    static final String CEC_SCRIPT = "dumpsys hdmi_control 2>/dev/null | grep -E '^ *\\[[RS]\\] time='";
    /** The app in front: the TV app means the projector is showing an HDMI input. */
    static final String FOREGROUND_SCRIPT = "dumpsys activity activities 2>/dev/null | grep -m1 mResumedActivity";
    static final String TV_APP = "com.mediatek.wwtv.tvcenter";
    /** What the projector is showing: the app in front, and the CEC devices by HDMI port. */
    static final String SHOWING_SCRIPT = FOREGROUND_SCRIPT + "; dumpsys hdmi_control 2>/dev/null | grep 'display_name' | grep -v mDeviceInfo";
    private static final Pattern RESUMED = Pattern.compile("u0 ([A-Za-z0-9_.]+)/");
    private static final Pattern CEC_DEVICE = Pattern.compile("device_type: (\\d+) .*?display_name: (.+?) power_status: (-?\\d+) physical_address: \\S+ port_id: (-?\\d+)");

    /** Package of the app in front, or null. */
    static String foregroundPackage(String output) {
        if (output == null) return null;
        for (String line : output.split("\n")) {
            if (!line.contains("mResumedActivity")) continue;
            Matcher m = RESUMED.matcher(line);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /** An audio system's CEC logical address, which is also its device type. */
    static final int AUDIO_SYSTEM = 5;

    /** CEC device names by HDMI port (1-4), from the service's device list. A source (playback,
     *  recorder, tuner) wins over an audio system on the same port: behind a soundbar the Apple
     *  TV is what is showing, the bar only passes it through. */
    static Map<Integer, String> cecDevices(String output) {
        Map<Integer, String> out = new LinkedHashMap<>();
        Map<Integer, Integer> types = new HashMap<>();
        if (output == null) return out;
        for (String line : output.split("\n")) {
            Matcher m = CEC_DEVICE.matcher(line);
            if (!m.find()) continue;
            int type = Integer.parseInt(m.group(1));
            int port = Integer.parseInt(m.group(4));
            if (port < 1 || port > 4) continue;
            Integer had = types.get(port);
            if (had != null && had != AUDIO_SYSTEM && type == AUDIO_SYSTEM) continue;   // keep the source over the audio system
            out.put(port, m.group(2).trim());
            types.put(port, type);
        }
        return out;
    }

    /** A readable name for an app in front of the projector. */
    static String appLabel(String pkg) {
        if (pkg == null) return "Unknown";
        switch (pkg) {
            case "com.spocky.projengmenu": return "Projectivy";
            case "com.plexapp.android": return "Plex";
            case "com.edde746.plezy": return "Plezy";
            case "org.smarttube.stable": return "SmartTube";
            case "com.limelight": return "Moonlight";
            case "me.jxl.kiosk_satellite": return "Kiosk Satellite";
            case "moe.shizuku.privileged.api": return "Shizuku";
            case "com.appo.settings": return "Projector settings";
            default: return pkg;
        }
    }

    /** "HDMI 1 · Apple TV" for the TV app, the app's name otherwise. */
    static String showing(String pkg, String input, Map<Integer, String> devices) {
        if (pkg != null && !pkg.equals(TV_APP)) return appLabel(pkg);
        if (input == null) return "HDMI input";
        int idx = java.util.Arrays.asList(INPUTS).indexOf(input);
        String name = idx >= 0 ? devices.get(idx + 1) : null;
        return name == null ? input : input + " \u00b7 " + name;
    }

    /** Busy and total jiffies from the /proc/stat "cpu" line (user nice system idle iowait irq
     *  softirq steal), or null. Idle and iowait are idle; the rest is busy. */
    static long[] cpuTimes(String line) {
        if (line == null) return null;
        String[] f = line.trim().split("\\s+");
        if (f.length < 8 || !f[0].equals("cpu")) return null;
        long total = 0, idle = 0;
        try {
            for (int i = 1; i <= Math.min(8, f.length - 1); i++) {
                long v = Long.parseLong(f[i]);
                total += v;
                if (i == 4 || i == 5) idle += v;
            }
        } catch (NumberFormatException e) { return null; }
        return new long[] {total - idle, total};
    }

    /** Busy share between two readings, 0-100, or null when they cannot be compared. */
    static Double cpuPercent(long busy0, long total0, long busy1, long total1) {
        long dt = total1 - total0, db = busy1 - busy0;
        if (total0 <= 0 || dt <= 0 || db < 0) return null;
        return Math.min(100.0, 100.0 * db / dt);
    }

    /** A laser hotter than this shows the Projector tile in amber. */
    static final double LASER_HOT_C = 75;

    /** Projector tile: level and text from the light, the hottest laser and the fan. */
    static String[] projectorTile(Boolean light, Double laser, Integer fan) { return projectorTile(light, laser, fan, null); }

    static String[] projectorTile(Boolean light, Double laser, Integer fan, Double cpu) {
        StringBuilder t = new StringBuilder(light == null ? "Laser unknown" : light ? "Laser on" : "Laser off");
        if (laser != null) t.append(String.format(Locale.ROOT, " \u00b7 %.0f \u00b0C", laser));
        if (fan != null) t.append(" \u00b7 fan ").append(fan).append('%');
        if (cpu != null) t.append(String.format(Locale.ROOT, " \u00b7 CPU %.0f%%", cpu));
        String level = laser != null && laser >= LASER_HOT_C ? "warn" : light == null ? "" : light ? "on" : "off";
        return new String[] {level, t.toString()};
    }

    /** The CEC messages seen, kept on the projector's storage across a standby (a cold boot wipes
     *  the service's own history). The first line names the boot they belong to. */
    static final String CEC_LOG = "/data/local/tmp/aurora-cec.log";

    /** At start: a log from an earlier boot becomes the ".prev" log and a fresh one is begun;
     *  prints the previous boot's log. Keyed by boot id, not the clock (it is wrong right after a boot). */
    static final String CEC_LOG_START_SCRIPT = "B=$(cat /proc/sys/kernel/random/boot_id); F=" + CEC_LOG + ";"
        + " if [ -f $F ] && [ \"$(head -1 $F)\" != \"boot=$B\" ]; then mv $F $F.prev; fi;"
        + " [ -f $F ] || echo \"boot=$B\" > $F; cat $F.prev 2>/dev/null; true";

    /** Appends lines to the log, keeping the boot line and the newest 100 entries. */
    static String cecLogAppendScript(List<String> lines) {
        StringBuilder sb = new StringBuilder("F=" + CEC_LOG + "; printf '%s\\n'");
        for (String l : lines) sb.append(' ').append(shellQuote(l));
        sb.append(" >> $F; if [ $(wc -l < $F) -gt 120 ]; then (head -1 $F; tail -n 100 $F) > $F.t && mv $F.t $F; fi; true");
        return sb.toString();
    }

    static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** "16:56:01 Set System Audio Mode from JBL to all · ..." for the newest messages of a log. */
    static String cecTail(List<Cec> log, int count, Map<Integer, String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, log.size() - count); i < log.size(); i++) {
            Cec c = log.get(i);
            if (sb.length() > 0) sb.append(" \u00b7 ");
            String clock = c.time.length() >= 8 ? c.time.substring(c.time.length() - 8) : c.time;
            sb.append(clock).append(' ').append(c.received ? "" : "sent ").append(c.summary(names));
        }
        return sb.length() > 250 ? sb.substring(sb.length() - 250) : sb.toString();
    }

    /** How often the CEC history is read. */
    static final long CEC_POLL_MS = 2000L;

    private static final Pattern CEC_LINE = Pattern.compile(
        "^\\s*\\[([RS])\\] time=(\\S+ \\S+) message=<([^>]+)> src: (\\d+), dst: (\\d+)(?:, params: ([0-9A-Fa-f ]+))?");

    /** One message from the CEC history. */
    static final class Cec {
        final boolean received;
        final String time;
        final String name;
        final int src;
        final int dst;
        final String params;
        final String line;

        Cec(boolean received, String time, String name, int src, int dst, String params, String line) {
            this.received = received; this.time = time; this.name = name;
            this.src = src; this.dst = dst; this.params = params == null ? "" : params.trim(); this.line = line;
        }

        /** A source announcing itself: the picture should show. */
        boolean wakes() {
            return received && src != 0 && (name.equals("Image View On") || name.equals("Text View On") || name.equals("Active Source"));
        }

        /** A source going to sleep. The projector ignores it for its own power (the stay-on
         *  guard); the plugin turns it into a dark picture instead. */
        boolean sleeps() {
            return received && src != 0 && name.equals("Standby");
        }

        /** From the audio system (a soundbar on the ARC port). Its standby says nothing about the
         *  picture: on 2026-09-30 the JBL's auto-standby after AirPlay darkened the Apple TV. */
        boolean fromAudioSystem() {
            return src == AUDIO_SYSTEM;
        }

        /** A Report Power Status answer: true on (00), false standby (01) or going to standby
         *  (03), null for anything else. */
        Boolean powerReport() {
            if (!received || !name.equals("Report Power Status") || params.isEmpty()) return null;
            String p = params.substring(0, Math.min(2, params.length()));
            if (p.equals("00") || p.equals("02")) return Boolean.TRUE;
            if (p.equals("01") || p.equals("03")) return Boolean.FALSE;
            return null;
        }

        /** HDMI port of an Active Source (first nibble of its physical address), or 0. */
        int port() {
            if (!name.equals("Active Source") || params.length() < 2) return 0;
            int p = Character.digit(params.charAt(0), 16);
            return p >= 1 && p <= 4 ? p : 0;
        }

        /** A message worth showing: sources waking, sleeping, switching inputs or passing remote
         *  keys. The projector's own minute-by-minute power polling and the discovery chatter
         *  (vendor id, CEC version, physical address) are not. */
        boolean notable() {
            return received && NOTABLE.contains(name);
        }

        /** Sensor text: "Image View On from Apple TV". HA keeps the time. */
        String summary(Map<Integer, String> names) {
            return name + " from " + nameOf(src, names) + (dst == 15 ? " to all" : "");
        }
    }

    static final java.util.Set<String> NOTABLE = new java.util.HashSet<>(java.util.Arrays.asList(
        "Image View On", "Text View On", "Active Source", "Inactive Source", "InActive Source", "Standby",
        "Routing Change", "Request Active Source", "User Control Pressed",
        "System Audio Mode Request", "Set System Audio Mode"));

    /** A source's CEC name ("Apple TV") when the service knows it, else its role ("Playback 1"). */
    static String nameOf(int logical, Map<Integer, String> names) {
        String n = names == null ? null : names.get(logical);
        return n != null && !n.isEmpty() ? n : deviceName(logical);
    }

    /** HDMI port (1-4) of each CEC device by logical address, from the service's device list:
     *  which input a source that wakes or sleeps is on. */
    static Map<Integer, Integer> cecPorts(String output) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        if (output == null) return out;
        for (String line : output.split("\n")) {
            Matcher m = CEC_PORT.matcher(line);
            if (!m.find()) continue;
            int port = Integer.parseInt(m.group(2));
            if (port >= 1 && port <= 4) out.put(Integer.parseInt(m.group(1), 16), port);
        }
        return out;
    }

    private static final Pattern CEC_PORT = Pattern.compile("logical_address: 0x([0-9A-Fa-f]+) .*?port_id: (-?\\d+)");

    /** The HDMI hardware the TV app holds right now, from Android's TV input service: the input
     *  actually on the wall. The vendor's cur.prj.currentSourceId only follows its own menu (on
     *  2026-10-01 it said HDMI 3 after the Apple TV had taken the projector to HDMI 1 over CEC), and
     *  what this plugin last asked for is forgotten at a restart. No line: no HDMI input held (an
     *  app is in front). Needs the shell user (dumpsys). */
    static final String LIVE_INPUT_SCRIPT = "dumpsys tv_input | grep 'Connection{ mHardwareInfo' | grep 'mCallingUid: [0-9]'; true";

    /** The port (1-4) in LIVE_INPUT_SCRIPT's output, or null. */
    static Integer livePort(String output) {
        if (output == null) return null;
        for (String line : output.split("\n")) {
            Matcher m = LIVE_PORT.matcher(line);
            if (m.find()) {
                int p = Integer.parseInt(m.group(1));
                if (p >= 1 && p <= 4) return p;
            }
        }
        return null;
    }

    private static final Pattern LIVE_PORT = Pattern.compile("hdmi_port=(\\d+).*mCallingUid: \\d+");

    /** "HDMI 3" to 3, anything else to null. */
    static Integer portOf(String input) {
        if (input == null) return null;
        for (int i = 0; i < INPUTS.length; i++) if (INPUTS[i].equals(input)) return i + 1;
        return null;
    }

    private static final Pattern CEC_NAME = Pattern.compile("logical_address: 0x([0-9A-Fa-f]+) .*?display_name: (.+?) power_status:");

    /** CEC device names by logical address, from the service's device list. */
    static Map<Integer, String> cecNames(String output) {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (output == null) return out;
        for (String line : output.split("\n")) {
            Matcher m = CEC_NAME.matcher(line);
            if (m.find()) out.put(Integer.parseInt(m.group(1), 16), m.group(2).trim());
        }
        return out;
    }

    /** CEC logical address names (HDMI-CEC 1.4, table 5). */
    static String deviceName(int logical) {
        switch (logical) {
            case 0: return "TV";
            case 1: case 2: case 9: return "Recorder";
            case 3: case 6: case 7: case 10: return "Tuner";
            case 4: return "Playback 1";
            case 8: return "Playback 2";
            case 11: return "Playback 3";
            case 5: return "Audio system";
            default: return "Device " + logical;
        }
    }

    static List<Cec> parseCec(String output) {
        List<Cec> out = new ArrayList<>();
        if (output == null) return out;
        for (String line : output.split("\n")) {
            Matcher m = CEC_LINE.matcher(line);
            if (!m.find()) continue;
            out.add(new Cec("R".equals(m.group(1)), m.group(2), m.group(3),
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), m.group(6), line.trim()));
        }
        return out;
    }

    /**
     * The messages in {@code now} that were not in {@code before}. The history is a ring that
     * drops its oldest entries, so the newest lines already seen are found in the new read and
     * everything after them is new. No overlap (a reboot, or more traffic than the ring holds)
     * means everything is new.
     */
    static List<Cec> newCec(List<Cec> before, List<Cec> now) {
        if (before == null || before.isEmpty()) return new ArrayList<>(now);
        int k = Math.min(4, before.size());
        List<String> tail = new ArrayList<>();
        for (Cec c : before.subList(before.size() - k, before.size())) tail.add(c.line);
        for (int i = now.size() - k; i >= 0; i--) {
            boolean match = true;
            for (int j = 0; j < k && match; j++) match = now.get(i + j).line.equals(tail.get(j));
            if (match) return new ArrayList<>(now.subList(i + k, now.size()));
        }
        return new ArrayList<>(now);
    }
}
