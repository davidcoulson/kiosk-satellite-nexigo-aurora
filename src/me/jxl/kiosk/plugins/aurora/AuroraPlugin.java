// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.aurora;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * The NexiGo Aurora Pro as Home Assistant entities, from a Kiosk Satellite running on the
 * projector's own Android.
 *
 * <p>Picture (switch), Input and Picture mode (selects), Screen off (binary sensor), laser hours
 * and the light engine's temperatures (sensors), plus buttons for the projector's settings app
 * and the front LED bar. State is read back from the projector on a timer and after every
 * command, and only what was read is published: there is no optimistic state.
 *
 * <p>All host callbacks return at once; the work runs on one plugin-owned thread, so commands
 * and polls never overlap and Kiosk Satellite's three-second callback deadline is never at risk.
 */
public final class AuroraPlugin implements KioskPlugin {
    private static final String CHANNEL_AUTO = "Auto";
    private static final String CHANNEL_DIRECT = "Direct";
    private static final String CHANNEL_SHIZUKU = "Shizuku";
    private static final String CHANNEL_ADB = "ADB";

    private final AtomicBoolean alive = new AtomicBoolean();
    private PluginHost host;
    private ScheduledExecutorService worker;
    private ScheduledFuture<?> polling;
    private Map<String, Object> settings = new HashMap<>();
    private Shell.Runner runner;
    private String channelName = "no channel";
    private final Shell.Runner directRunner;
    private final ShizukuFactory shizukuFactory;
    private final AdbFactory adbFactory;
    /** A shell-user channel behind a direct one, for the log; null when there is none. */
    private Shell.Runner extra;
    private String extraName;
    private Projector.State last = new Projector.State();
    private boolean switchPublished;
    /** The input last selected through this plugin: the pass-through URI does not update the
     *  vendor's property, so this is what Input shows until a read says otherwise. */
    private String commandedInput;
    /** Whether the last picture command from here was "off": the flag below is kept for it. */
    private boolean commandedPictureOff;
    /** The bar cannot be read back; this is what was last asked of it. */
    private String commandedLeds;
    /** The light state the last read reported, so the bar can follow changes made elsewhere
     *  (a remote key re-lights a screen-off projector; the power menu darkens it). */
    private Boolean observedLight;
    /** The blue laser's last reading, for its trend; and when a picture command last went out. */
    private Double lastBlue;
    private volatile long pictureCommandAt;
    private boolean fanPublished;
    private boolean laserPublished;
    /** What cur.prj.currentSourceId said when that input was commanded; a later change means
     *  the projector's own menu was used and wins. */
    private Integer sourceAtCommand;

    /** Settings.Global reads through the framework; tests supply their own. */
    interface Globals {
        String get(String key);
    }

    private Globals globals = new Globals() {
        @Override public String get(String key) { return Framework.getGlobal(key); }
    };

    void useGlobals(Globals g) { globals = g; }

    /** How the Shizuku runner is made, so tests can hand in their own. */
    interface ShizukuFactory {
        Shell.Runner create(PluginHost host);
    }

    /** The constructor Kiosk Satellite uses. */
    public AuroraPlugin() {
        this(Shell.DIRECT, new ShizukuFactory() {
            @Override public Shell.Runner create(PluginHost h) { return Shell.shizuku(h); }
        });
    }

    /** Builds the loopback ADB runner for a port. Tests substitute their own. */
    interface AdbFactory {
        Shell.Runner create(int port);
    }

    AuroraPlugin(Shell.Runner directRunner, ShizukuFactory shizukuFactory) {
        this(directRunner, shizukuFactory, new AdbFactory() {
            @Override public Shell.Runner create(int port) { return Shell.adb(port); }
        });
    }

    AuroraPlugin(Shell.Runner directRunner, ShizukuFactory shizukuFactory, AdbFactory adbFactory) {
        this.directRunner = directRunner;
        this.shizukuFactory = shizukuFactory;
        this.adbFactory = adbFactory;
    }

    @Override public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        this.settings = new HashMap<>(settings);
        alive.set(true);
        worker = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "nexigo-aurora");
                t.setDaemon(true);
                return t;
            }
        });
        // Selects and sensors may start unknown; a switch may not, so Picture waits for a read.
        host.publishSelect("input", "Input", Projector.INPUTS, null);
        host.publishSelect("picture_mode", "Picture mode", Projector.PICTURE_MODES, null);
        host.publishBinarySensor("screen_off", "Screen off", "", null);
        host.publishBinarySensor("stays_on", "Stays on when the source sleeps", "", null);
        host.publishSelect("front_leds", "Front LEDs", Projector.LEDS, null);
        host.publishSensor("laser_hours", "Laser hours", sensorMeta("h", "duration", "total_increasing", 1), null);
        submit(new Task() { @Override public void run() { detect(); } });
        schedule();
    }

    @Override public void configure(Map<String, Object> settings) {
        this.settings = new HashMap<>(settings);
        submit(new Task() { @Override public void run() { detect(); } });
        schedule();
    }

    @Override public void execute(String command, Map<String, Object> arguments) {
        final String script;
        switch (command) {
            case "settings": script = Projector.openSettingsScript(); break;
            case "pictureOff": script = Projector.pictureScript(false); commandedPictureOff = true; ledsForPicture(false); settleLight(); break;
            case "pictureOn": script = Projector.pictureScript(true); commandedPictureOff = false; ledsForPicture(true); settleLight(); break;
            // One button for a remote key: dark when the picture shows, on otherwise (an unknown
            // light is treated as off, since that is the state a key press is meant to end).
            case "pictureToggle": execute(Boolean.TRUE.equals(lastLight) ? "pictureOff" : "pictureOn", arguments); return;
            case "ledsOff": leds("Off"); return;
            case "ledsOn": leds("Standby"); return;
            case "refresh": script = null; break;
            default: throw new IllegalArgumentException("Unknown command " + command);
        }
        submit(new Task() { @Override public void run() { if (script != null) command(script, command); poll(); } });
    }

    /** Sets the bar to one of the named patterns and remembers it; the bar has no readback. */
    private void leds(final String label) {
        final Integer id = Projector.idFor(Projector.LEDS, Projector.LED_IDS, label);
        if (id == null) throw new IllegalArgumentException("Unknown LED pattern");
        submit(new Task() { @Override public void run() {
            if (runner == null) { status("No channel to the projector; check the plugin's settings.", true); return; }
            Shell.Result r = runner.run(Projector.ledsScript(id), Shell.DEFAULT_TIMEOUT_MS);
            if (r.ok()) { commandedLeds = label; host.publishSelect("front_leds", "Front LEDs", Projector.LEDS, label); }
            else status("Front LEDs failed via " + channelName + ": " + r.why(), true);
        } });
    }

    /** With "LEDs follow the picture" on, a picture change brings the bar along: standby lights
     *  while it is dark, nothing while it shows. */
    private void ledsForPicture(boolean pictureOn) {
        if (Boolean.TRUE.equals(settings.get("ledsFollowPicture"))) leds(pictureOn ? "Off" : "Standby");
    }

    /** After a picture command from here the flag is the truth until the laser has had time to
     *  warm or cool. */
    private void settleLight() { pictureCommandAt = System.currentTimeMillis(); }

    /** The light as last published, for the toggle. */
    private volatile Boolean lastLight;

    /** The same, for a light change the projector reports rather than one asked for here. */
    private void followObservedLight(Boolean light) {
        lastLight = light;
        if (light == null || light.equals(observedLight)) return;
        observedLight = light;
        String want = light ? "Off" : "Standby";
        if (!want.equals(commandedLeds)) ledsForPicture(light);
    }

    @Override public void onEvent(String event, Map<String, Object> payload) {
        final String script;
        if (event.equals("switch.picture")) {
            Object on = payload.get("on");
            if (!(on instanceof Boolean)) throw new IllegalArgumentException("Picture wants a boolean");
            script = Projector.pictureScript((Boolean) on);
            commandedPictureOff = !(Boolean) on;
            ledsForPicture((Boolean) on);
            settleLight();
        } else if (event.equals("select.input")) {
            Integer hw = Projector.idFor(Projector.INPUTS, Projector.INPUT_IDS, payload.get("option"));
            if (hw == null) throw new IllegalArgumentException("Unknown input");
            script = Projector.inputScript(hw);
            final String chosen = String.valueOf(payload.get("option"));
            submit(new Task() { @Override public void run() {
                Shell.Result r = runner == null ? Shell.Result.failure("no channel") : runner.run(script, Shell.DEFAULT_TIMEOUT_MS);
                if (r.ok()) { commandedInput = chosen; sourceAtCommand = last.sourceId; }
                else status("Input failed via " + channelName + ": " + r.why(), true);
                poll();
            } });
            return;
        } else if (event.equals("select.front_leds")) {
            leds(String.valueOf(payload.get("option")));
            return;
        } else if (event.equals("select.picture_mode")) {
            Integer mode = Projector.idFor(Projector.PICTURE_MODES, Projector.PICTURE_MODE_IDS, payload.get("option"));
            if (mode == null) throw new IllegalArgumentException("Unknown picture mode");
            final int chosen = mode;
            submit(new Task() { @Override public void run() {
                if (!Framework.putGlobal("picture_mode", chosen)) command(Projector.pictureModeScript(chosen), "Picture mode");
                poll();
            } });
            return;
        } else if (event.equals("shizuku.state")) {
            submit(new Task() { @Override public void run() { detect(); } });
            return;
        } else {
            return;
        }
        final String what = event;
        submit(new Task() { @Override public void run() { command(script, what); poll(); } });
    }

    @Override public void stop() throws Exception {
        alive.set(false);
        if (polling != null) polling.cancel(false);
        if (worker != null) {
            worker.shutdownNow();
            worker.awaitTermination(1000, TimeUnit.MILLISECONDS);
        }
    }

    // ---- the work, all on the worker thread ----

    /** Picks the channel from the setting and what the projector accepts, then reads once. */
    private void detect() {
        String want = String.valueOf(settings.get("channel"));
        runner = null; extra = null; extraName = null;
        int port = adbPort();
        Shell.Runner adb = adbFactory.create(port);
        boolean adbUp = false;
        if (CHANNEL_ADB.equals(want)) {
            adbUp = adbOk(adb);
            if (adbUp) { runner = adb; channelName = "ADB"; }
            else { status("The projector's ADB daemon did not answer on 127.0.0.1:" + port + ". Turn on network ADB, or pick another channel.", true); return; }
        } else if (!CHANNEL_SHIZUKU.equals(want)) {
            Shell.Result probe = directRunner.run(Projector.PROBE_SCRIPT, 4000);
            if (probe.ok()) { runner = directRunner; channelName = "direct"; }
            else if (CHANNEL_DIRECT.equals(want)) { status("The kiosk process cannot reach the projector's tools directly (" + probe.why() + "). Try ADB or Shizuku.", true); return; }
        }
        if (runner == null && CHANNEL_AUTO.equals(want)) {
            adbUp = adbOk(adb);
            if (adbUp) { runner = adb; channelName = "ADB"; }
        }
        if (runner == null) {
            if (Shell.shizukuGranted(host)) { runner = shizukuFactory.create(host); channelName = "Shizuku"; }
            else {
                status(CHANNEL_SHIZUKU.equals(want)
                    ? "Shizuku is not running or not authorized for Kiosk Satellite."
                    : "No way to reach the projector: direct access was refused, adbd did not answer and Shizuku is not authorized.", true);
                return;
            }
        }
        // The log (temperatures) needs the shell user. Behind a direct channel, the projector's
        // own adbd is the first choice, since it is there after every reboot; Shizuku the second.
        if (runner == directRunner) {
            if (adbUp || adbOk(adb)) { extra = adb; extraName = "ADB"; }
            else if (Shell.shizukuGranted(host)) { extra = shizukuFactory.create(host); extraName = "Shizuku"; }
        }
        // Shizuku for the other plugins: a loopback ADB session can start it after a reboot, which
        // is the one thing Shizuku cannot do for itself without root.
        Shell.Runner shellUser = runner == adb ? runner : (extra == adb ? extra : null);
        if (shellUser != null && Boolean.TRUE.equals(settings.get("startShizuku"))) shellUser.run(Projector.SHIZUKU_START_SCRIPT, 15000);
        if (Boolean.TRUE.equals(settings.get("stayOn"))) guardRunner().run(Projector.STAY_ON_SCRIPT, Shell.DEFAULT_TIMEOUT_MS);
        poll();
    }

    /** The stay-on guard writes a secure setting, which the shell user may and the kiosk process
     *  may not: behind a direct channel the shell-user one does it. */
    private Shell.Runner guardRunner() { return extra != null ? extra : runner; }

    private static boolean adbOk(Shell.Runner adb) {
        Shell.Result r = adb.run(Projector.ADB_PROBE_SCRIPT, 4000);
        return r.ok() && r.stdout.contains("uid=2000");
    }

    /** adbd's port on the stock firmware; a manifest number setting for it was refused by the host's range check. */
    private int adbPort() { return 5555; }

    private void command(String script, String what) {
        if (runner == null) { status("No channel to the projector; check the plugin's settings.", true); return; }
        Shell.Result r = runner.run(script, Shell.DEFAULT_TIMEOUT_MS);
        if (!r.ok()) status(what + " failed via " + channelName + ": " + r.why(), true);
    }

    private void poll() {
        if (runner == null || !alive.get()) return;
        Shell.Result r = runner.run(Projector.POLL_SCRIPT, Shell.DEFAULT_TIMEOUT_MS);
        if (!r.ok()) { status("Could not read the projector via " + channelName + ": " + r.why(), true); return; }
        Projector.State s = Projector.parse(r.stdout);
        // What this plugin asked for survives a Kiosk Satellite restart in the projector's own
        // properties; the in-memory flag alone forgot it, and the laser came back on.
        if (s.heldOff != null) commandedPictureOff = s.heldOff;
        // The log answers only the shell user: behind a direct channel, the shell-user channel
        // reads that one line.
        if (s.temperatures.isEmpty() && extra != null) {
            Shell.Result more = extra.run(Projector.TEMPS_SCRIPT, Shell.DEFAULT_TIMEOUT_MS);
            if (more.ok()) {
                Projector.State log = Projector.parse(more.stdout);
                s.temperatures.putAll(log.temperatures);
                if (s.fanPercent == null) s.fanPercent = log.fanPercent;
            }
        }
        // The settings command answers only the shell user; from the kiosk process the framework
        // answers instead, and it wins whenever it has a value.
        fill(s, "picture_mode", "mode");
        fill(s, "boot_source_id", "boot");
        fill(s, "no_signal_auto_power_off", "nosignal");
        // The flags only record what was asked through the vendor's own code path; the TV app can
        // light the laser straight through the HAL and leave cur.appo.light.enabled at false. The
        // laser's own heat is the readback: the blue laser runs ~30 °C over ambient when lit, climbs
        // fast when it lights and settles near ambient when dark. The HAL's minute counter looked like one and is not (on
        // 2026-09-26 it climbed every minute with the laser cold and re-lit the picture twice).
        long now = System.currentTimeMillis();
        Double blue = s.temperatures.get(Projector.LASER_NTC);
        Boolean heatSays = Projector.laserLit(s, lastBlue);
        if (blue != null) lastBlue = blue;
        // The log line can be 30 s old and a laser takes a minute to warm or cool: right after a
        // picture command the flag is the truth.
        if (now - pictureCommandAt < Projector.LASER_SETTLE_MS) heatSays = null;
        if (heatSays != null && !heatSays.equals(s.light)) {
            s.light = heatSays;
            if (heatSays) { s.screenOff = Boolean.FALSE; commandedPictureOff = false; }
            runner.run(Projector.reflagLightScript(heatSays), 4000);
        }
        // Android waking its display (a Kiosk Satellite restart does it) clears cur.prj.screenOff
        // while the light stays off. The flag is what tells the vendor's services the dark is
        // deliberate, so when this plugin turned the picture off it puts the flag back.
        if (commandedPictureOff && Boolean.FALSE.equals(s.light) && Boolean.FALSE.equals(s.screenOff)) {
            if (runner.run(Projector.REFLAG_SCREEN_OFF_SCRIPT, 4000).ok()) s.screenOff = Boolean.TRUE;
        }
        // A guard that slipped (the projector's own menu, a factory default after an update) is
        // put back here, not only at start: standby takes the network with it. The read after
        // this one says whether it held.
        if (Boolean.TRUE.equals(settings.get("stayOn")) && Boolean.FALSE.equals(s.staysOn())) {
            guardRunner().run(Projector.STAY_ON_SCRIPT, Shell.DEFAULT_TIMEOUT_MS);
        }
        publish(s);
    }

    private void fill(Projector.State s, String key, String field) {
        String v = globals.get(key);
        if (v != null && !v.trim().isEmpty()) Projector.apply(s, field, v.trim());
    }

    private void publish(Projector.State s) {
        if (!alive.get()) return;
        last = s;
        if (s.light != null) {
            host.publishSwitch("picture", "Picture", s.light);
            switchPublished = true;
        }
        if (commandedInput != null && s.sourceId != null && !s.sourceId.equals(sourceAtCommand)) commandedInput = null;
        followObservedLight(s.light);
        host.publishSelect("input", "Input", Projector.INPUTS, commandedInput != null ? commandedInput : s.input());
        host.publishBinarySensor("stays_on", "Stays on when the source sleeps", "", s.staysOn());
        host.publishSelect("picture_mode", "Picture mode", Projector.PICTURE_MODES, s.pictureModeLabel());
        host.publishBinarySensor("screen_off", "Screen off", "", s.screenOff);
        host.publishSensor("laser_hours", "Laser hours", sensorMeta("h", "duration", "total_increasing", 1),
            s.laserMinutes == null ? null : s.laserMinutes / 60.0);
        // The speed appothermal commands (the same on every fan PWM); the fans have no tachometer.
        if (s.fanPercent != null || fanPublished) {
            host.publishSensor("fan_speed", "Fan speed", sensorMeta("%", null, "measurement", 0),
                s.fanPercent == null ? null : s.fanPercent.doubleValue());
            fanPublished = true;
        }
        // One number for the light engine's heat: whichever laser bank runs warmest.
        Double laser = Projector.hottestLaser(s);
        if (laser != null || laserPublished) {
            host.publishSensor("laser_temp", "Laser temperature", sensorMeta("°C", "temperature", "measurement", 0), laser);
            laserPublished = true;
        }
        for (String[] t : Projector.TEMPERATURES) {
            Double value = s.temperatures.get(t[0]);
            // Only publish a temperature the projector has ever reported, so a channel that cannot
            // read the log does not fill Home Assistant with eight unknown sensors.
            if (value != null || s.temperatures.containsKey(t[0])) {
                host.publishSensor("temp_" + t[1], t[2] + " temperature", sensorMeta("°C", "temperature", "measurement", 0), value);
            }
        }
        StringBuilder text = new StringBuilder();
        text.append(s.light == null ? "Picture unknown" : (s.light ? "Picture on" : (Boolean.TRUE.equals(s.screenOff) ? "Screen off" : "Picture off")));
        String input = commandedInput != null ? commandedInput : s.input();
        if (input != null) text.append(" · ").append(input);
        if (s.pictureModeLabel() != null) text.append(" · ").append(s.pictureModeLabel());
        if (s.laserMinutes != null) text.append(String.format(Locale.ROOT, " · %.1f laser hours", s.laserMinutes / 60.0));
        Double dmd = s.temperatures.get("NtcDmd1");
        if (dmd != null) text.append(String.format(Locale.ROOT, " · DMD %.0f °C", dmd));
        if (Boolean.TRUE.equals(settings.get("stayOn")) && Boolean.FALSE.equals(s.staysOn())) {
            text.append(s.noSignalOff != null && s.noSignalOff != Projector.NO_SIGNAL_OFF
                ? " · no-signal shutdown still on (needs ADB, Shizuku or `pm grant ... WRITE_SECURE_SETTINGS` once)"
                : s.sleepMode != null && s.sleepMode != Projector.SLEEP_OFF
                    ? " · sleep timer still on"
                    : " · CEC standby not ignored yet");
        }
        text.append(" · via ").append(channelName);
        if (extraName != null) text.append(" (+").append(extraName).append(" for the log)");
        if (!switchPublished) text.append(". The Picture switch appears once the light state has been read.");
        status(text.toString(), false);
    }

    private static Map<String, Object> sensorMeta(String unit, String deviceClass, String stateClass, int decimals) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unit", unit);
        if (deviceClass != null) m.put("deviceClass", deviceClass);
        m.put("stateClass", stateClass);
        m.put("accuracyDecimals", decimals);
        return m;
    }

    private void schedule() {
        if (polling != null) polling.cancel(false);
        Object v = settings.get("pollSeconds");
        int seconds = v instanceof Number ? Math.max(10, Math.min(300, ((Number) v).intValue())) : 30;
        polling = worker.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { safe(new Task() { @Override public void run() { poll(); } }); }
        }, seconds, seconds, TimeUnit.SECONDS);
    }

    private void status(String text, boolean error) {
        if (alive.get()) host.status(text, error);
    }

    private interface Task { void run() throws Exception; }

    private void safe(Task task) {
        if (!alive.get()) return;
        try { task.run(); }
        catch (Throwable t) { status(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(), true); }
    }

    private void submit(final Task task) {
        if (!alive.get()) return;
        worker.execute(new Runnable() { @Override public void run() { safe(task); } });
    }

    /** For tests: the last state the projector reported. */
    Projector.State lastState() { return last; }
}
