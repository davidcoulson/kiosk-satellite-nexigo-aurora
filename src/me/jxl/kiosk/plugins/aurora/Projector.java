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

    /** Front LED bar statuses for {@code setAppoLeds 2 <status>} (APPO_LED_STATUS). */
    static final int LED_POWER_ON = 0;
    static final int LED_OFF = 6;

    /**
     * The power menu's "Screen off", step for step: flag the light as deliberately off, tell the
     * light engine, then flag the screen-off state. Without the two flags the eye-protection
     * service re-lights the laser or the projector drops to standby.
     */
    static String pictureScript(boolean on) {
        String flag = on ? "true" : "false";
        return "setprop cur.appo.light.enabled " + flag
            + " && " + TOOL + " setLightSourceOnOff " + flag
            + " && setprop cur.prj.screenOff " + (on ? "false" : "true");
    }

    static String ledsScript(boolean on) {
        return TOOL + " setAppoLeds 2 " + (on ? LED_POWER_ON : LED_OFF);
    }

    /** Selects an HDMI port by opening the TV input framework's pass-through URI. */
    static String inputScript(int hw) {
        return "am start -a android.intent.action.VIEW -d"
            + " content://android.media.tv/passthrough/com.mediatek.tvinput%2F.hdmi.HDMIInputService%2FHW" + hw;
    }

    /** picture_mode is a global setting the projector's ContentObserverService applies. */
    static String pictureModeScript(int mode) {
        return "settings put global picture_mode " + mode;
    }

    static String openSettingsScript() {
        return "am start -n " + SETTINGS_ACTIVITY;
    }

    /** No-signal shutdown off ("Close" in the projector's menu; 1-5 are 5, 10, 15, 30 and 60 min). */
    static final int NO_SIGNAL_OFF = 0;

    /**
     * Keep the projector on the network: ignore the CEC standby a sleeping source broadcasts
     * (the Apple TV's "Control TVs and receivers" does), and turn off the no-signal shutdown.
     * The property is the vendor's own switch; the setting needs WRITE_SECURE_SETTINGS, so it
     * may fail on the direct channel and is reported from the read-back instead.
     */
    static final String STAY_ON_SCRIPT = "setprop persist.appo.ignore.cec.standby true;"
        + " settings put global no_signal_auto_power_off " + NO_SIGNAL_OFF + " 2>/dev/null; true";

    /** Proves the channel can run the tool and read properties; the first thing a session does. */
    static final String PROBE_SCRIPT = "test -x " + TOOL + " && getprop ro.product.model";

    /**
     * One round trip for everything the entities show. Each line is key=value; a reading the
     * channel cannot make (logcat needs the shell user) comes back empty and parses to unknown.
     */
    static final String POLL_SCRIPT =
        "echo \"light=$(getprop cur.appo.light.enabled)\";"
        + " echo \"screenoff=$(getprop cur.prj.screenOff)\";"
        + " echo \"source=$(getprop cur.prj.currentSourceId)\";"
        + " echo \"mode=$(settings get global picture_mode 2>/dev/null)\";"
        + " echo \"minutes=$(" + TOOL + " getPlatformProperty laser_used_time_wdt 2>/dev/null | grep -oE '[0-9]+' | tail -1)\";"
        + " echo \"temps=$(logcat -d -t 600 2>/dev/null | grep -oE 'AT\\+Temperature#[A-Za-z0-9:,]+' | tail -1)\";"
        + " echo \"leds=$(cat /sys/class/appo_led_pwm_pm/appo_led_pwm_pm/led_pwm 2>/dev/null)\";"
        + " echo \"boot=$(settings get global boot_source_id 2>/dev/null)\";"
        + " echo \"cec=$(getprop persist.appo.ignore.cec.standby)\";"
        + " echo \"nosignal=$(settings get global no_signal_auto_power_off 2>/dev/null)\"";

    /** The light engine's NTC names as the HAL logs them, and the entity keys they become. */
    static final String[][] TEMPERATURES = {
        {"NtcRedLaser1", "red_laser", "Red laser"},
        {"NtcGreenLaser1", "green_laser", "Green laser"},
        {"NtcBlueLaser1", "blue_laser", "Blue laser"},
        {"NtcCw1", "color_wheel", "Colour wheel"},
        {"NtcDmd1", "dmd", "DMD"},
        {"NtcEnv1", "ambient", "Ambient"},
        {"NtcXpr1", "xpr", "XPR"},
        {"NtcPowerSupply", "power_supply", "Power supply"},
    };

    /** A snapshot of what the projector reported; null fields are unknown. */
    static final class State {
        Boolean light;
        Boolean screenOff;
        Integer sourceId;
        Integer pictureMode;
        Long laserMinutes;
        Integer ledPwm;
        Integer bootSourceId;
        Boolean ignoreCecStandby;
        Integer noSignalOff;
        final Map<String, Double> temperatures = new LinkedHashMap<>();

        /** The vendor sets cur.prj.currentSourceId only from its own source menu; after a boot it
         *  is the boot source until then. */
        String input() { return label(INPUTS, INPUT_IDS, sourceId != null ? sourceId : bootSourceId); }
        /** Both guards in place: CEC standby ignored and the no-signal shutdown off. */
        Boolean staysOn() {
            if (ignoreCecStandby == null || noSignalOff == null) return null;
            return ignoreCecStandby && noSignalOff == NO_SIGNAL_OFF;
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
                case "leds": s.ledPwm = integer(value); break;
                case "boot": s.bootSourceId = integer(value); break;
                case "cec": s.ignoreCecStandby = bool(value); break;
                case "nosignal": s.noSignalOff = integer(value); break;
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
