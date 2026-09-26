// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.aurora;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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

    /** The log lines alone, for a shell-user channel behind a direct one. */
    static final String TEMPS_SCRIPT = TEMPS_LINE + FAN_LINE;

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
    /** Within this much of ambient: dark. Between the two, or hot and cooling, the flag decides. */
    static final double DARK_OVER_AMBIENT = 8;
    /** How long after a picture command the heat is not trusted: the log line can be 30 s old and
     *  the laser takes a minute or more to warm or cool across the thresholds. */
    static final long LASER_SETTLE_MS = 180_000L;
    /** How long after the plugin (so Kiosk Satellite) starts a laser lit behind a held-off picture
     *  is put back out rather than reported: the start itself is what lit it. */
    static final long RESTART_GUARD_MS = 300_000L;

    /**
     * What the laser's heat says about the light: true when well over ambient and heating (a
     * laser lit behind the flags' back), false when back near ambient, null when it cannot tell
     * (no log, no previous reading, steady, cooling, or the band between). A steady hot laser
     * says nothing: the flag already agrees with it unless the plugin started after it was lit.
     */
    static Boolean laserLit(State s, Double previousBlue) {
        Double blue = s.temperatures.get(LASER_NTC), ambient = s.temperatures.get(AMBIENT_NTC);
        if (blue == null || ambient == null) return null;
        double over = blue - ambient;
        if (over <= DARK_OVER_AMBIENT) return Boolean.FALSE;
        if (over >= LIT_OVER_AMBIENT && previousBlue != null && blue - previousBlue >= HEATING_STEP) return Boolean.TRUE;
        return null;
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
}
