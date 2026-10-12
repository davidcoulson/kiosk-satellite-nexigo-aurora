// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.aurora;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
    /** Reads the HDMI-CEC history every few seconds on a shell-user channel (see watchCec). */
    private ScheduledFuture<?> cecWatch;
    /** The vendor helper's own thread: its full read takes about ten seconds (MediaTek's TV
     *  library starting), which must not hold up the polls or the CEC watch. */
    private ScheduledExecutorService vendorWorker;
    private ScheduledFuture<?> vendorReads;
    /** The helper's DEX on the projector, once installed; null until then or without one. */
    private volatile String vendorPath;
    /** The helper's last read, key=value. */
    private volatile Map<String, String> vendor = new HashMap<>();
    private Boolean lastEyeProtect;
    private Shell.Runner cecRunner;
    private List<Projector.Cec> lastCec;
    /** Each source's last reported power (true on), by CEC logical address. */
    private final Map<Integer, Boolean> sourcePower = new HashMap<>();
    /** CEC device names by logical address ("Apple TV"), refreshed with each read. */
    private volatile Map<Integer, String> cecNames = new HashMap<>();
    /** Each CEC device's HDMI port, by logical address. */
    private volatile Map<Integer, Integer> cecPorts = new HashMap<>();
    /** The tiles and the Showing sensor as last published, so only changes go out. */
    private String lastProjectorTile, lastShowingTile;
    private boolean tilesSupported = true;
    private long lastCpuBusy, lastCpuTotal;
    /** What each measured sensor last sent, so wobble within a step stays out of HA's recorder. */
    private final Steady steady = new Steady();
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
    /** The input on the wall, read from Android's TV input service each poll; null when no HDMI
     *  input is held (an app in front) or the read is not possible. Wins over everything else. */
    private volatile String liveInput;
    /** Whether the last picture command from here was "off": the flag below is kept for it. */
    private boolean commandedPictureOff;
    /** No signal on the HDMI input in front since then (null: there is one, or the picture is
     *  already dark), on which input, and whether the dark picture now is this plugin's doing for
     *  that reason. Worker thread only, like commandedPictureOff: every picture change and every
     *  signal reading is handled there, one at a time, so a manual Off and an automatic restore
     *  can never interleave. */
    private Long silentSince;
    private Integer silentPort;
    private boolean darkForSilence;
    /** A minute, for the no-signal timer; tests shorten it. */
    long silenceMinuteMs = 60_000;
    /** The bar cannot be read back; this is what was last asked of it. */
    private String commandedLeds;
    /** The light state the last read reported, so the bar can follow changes made elsewhere
     *  (a remote key re-lights a screen-off projector; the power menu darkens it). */
    private Boolean observedLight;
    /** The blue laser's last reading, for its trend; and when a picture command last went out. */
    private Double lastBlue;
    private volatile long pictureCommandAt;
    /** The newest light-source command already acted on: each one counts once, so a command
     *  from hours ago cannot outvote the laser's heat. */
    private long lightSourceHandledAt;
    private boolean fanPublished;
    /** When this plugin started, which is when Kiosk Satellite (re)started. */
    private volatile long startedAt;
    /** Temperature sensors published with a value, so one that stops reporting turns unknown. */
    private final java.util.Set<String> tempsPublished = new java.util.HashSet<>();
    /** Channel detection that came up short is tried again on its own, backing off to 5 minutes. */
    private ScheduledFuture<?> redetect;
    private int redetectSeconds;
    /** The first retry's wait; tests shorten it. */
    int redetectFirstSeconds = 15;
    private static final int REDETECT_MAX_S = 300;
    /** Helper runs that failed in a row; a few mean the copy on the projector is not to be trusted. */
    private int vendorFailures;
    /** The cancel epoch this session began in (see Shell.cancelAll): every channel it uses is
     *  refused once stop() has moved it on, even a call that was about to start. */
    private volatile long sessionEpoch;
    /** directRunner, bound to this session. */
    private Shell.Runner direct;
    /** The laser was seen lit while held off, and the Off that should follow has not gone
     *  through: kept until it does (or the picture is changed), so a refused correction is retried
     *  rather than forgotten once the evidence for it has been read. */
    private boolean pendingOff;
    /** Flags and hold note for a light change the HAL made but whose properties did not take
     *  (the script to write them), retried before every read; while it is owed, the stale note on
     *  the projector says nothing about who holds the picture, so it is not read back over this
     *  plugin's own record (a dark for silence would otherwise forget it may come back). */
    private String pendingFlags;
    /** The light those owed flags belong to: they are written only while the light still is that. */
    private boolean pendingFlagsOn;
    /** stop() must return inside Kiosk Satellite's callback deadline (3 s). */
    private static final long STOP_WAIT_MS = 2000;
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
        startedAt = System.currentTimeMillis();
        this.host = host;
        sessionEpoch = Shell.epoch();
        direct = session(directRunner);
        steady.clear();
        tempsPublished.clear();
        redetectSeconds = redetectFirstSeconds;
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
        host.publishBinarySensor("laser", "Laser status", "", null);
        host.publishBinarySensor("stays_on", "Stays on when the source sleeps", "", null);
        host.publishSelect("front_leds", "Front LEDs", Projector.LEDS, null);
        host.publishSensor("laser_hours", "Laser hours", sensorMeta("h", "duration", "total_increasing", 1), null);
        host.publishBinarySensor("eye_protect", "Eye protection", "", null);
        vendorWorker = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "nexigo-aurora-vendor");
                t.setDaemon(true);
                return t;
            }
        });
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
            case "pictureOff":
            case "pictureOn": {
                final boolean on = command.equals("pictureOn");
                submit(new Task() { @Override public void run() { picture(on, on ? "Picture on" : "Picture off", false); poll(); } });
                return;
            }
            // One button for a remote key: dark when the picture shows, on otherwise (an unknown
            // light is treated as off, since that is the state a key press is meant to end).
            case "pictureToggle": execute(Boolean.TRUE.equals(lastLight) ? "pictureOff" : "pictureOn", arguments); return;
            case "ledsOff": leds("Off"); return;
            case "ledsOn": leds("Standby"); return;
            case "refresh": script = null; break;
            default: throw new IllegalArgumentException("Unknown command " + command);
        }
        submit(new Task() { @Override public void run() {
            if (script != null) command(script, command);
            // Refresh with no channel at all tries to find one now rather than at the next retry.
            if (script == null && runner == null) detect(); else poll();
        } });
    }

    /** A channel bound to this session: refused once the plugin has stopped. */
    private Shell.Runner session(final Shell.Runner r) {
        if (r == null) return null;
        return new Shell.Runner() {
            @Override public Shell.Result run(String script, int timeoutMs) {
                if (!alive.get()) return Shell.Result.failure("stopped");
                return r.run(script, timeoutMs, sessionEpoch);
            }
        };
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
        if (event.equals("switch.picture")) {
            Object on = payload.get("on");
            if (!(on instanceof Boolean)) throw new IllegalArgumentException("Picture wants a boolean");
            final boolean want = (Boolean) on;
            submit(new Task() { @Override public void run() { picture(want, want ? "Picture on" : "Picture off", false); poll(); } });
            return;
        } else if (event.equals("select.input")) {
            Integer hw = Projector.idFor(Projector.INPUTS, Projector.INPUT_IDS, payload.get("option"));
            if (hw == null) throw new IllegalArgumentException("Unknown input");
            final String script = Projector.inputScript(hw);
            final String chosen = String.valueOf(payload.get("option"));
            submit(new Task() { @Override public void run() {
                Shell.Result r = runner == null ? Shell.Result.failure("no channel") : runner.run(script, Shell.DEFAULT_TIMEOUT_MS);
                if (r.ok()) { commandedInput = chosen; sourceAtCommand = last.sourceId; silentSince = null; }
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
                // The framework write needs WRITE_SECURE_SETTINGS; failing that, the settings command
                // has to run as the shell user, which behind a direct channel is the other one.
                if (!Framework.putGlobal("picture_mode", chosen)) {
                    Shell.Runner g = guardRunner();
                    Shell.Result r = g == null ? Shell.Result.failure("no channel") : g.run(Projector.pictureModeScript(chosen), Shell.DEFAULT_TIMEOUT_MS);
                    if (!r.ok()) status("Picture mode failed: " + r.why(), true);
                }
                poll();
            } });
            return;
        } else if (VENDOR_EVENTS.containsKey(event)) {
            final String key = VENDOR_EVENTS.get(event);
            final int value = vendorValue(event, payload);
            vendorWorker.execute(new Runnable() { @Override public void run() { safe(new Task() { @Override public void run() {
                vendorRun("set " + key + " " + value, false);
            } }); } });
            return;
        } else if (event.equals("shizuku.state")) {
            submit(new Task() { @Override public void run() { detect(); } });
        }
    }

    /** Nothing from this session touches the projector once stop returns: the flag stops new
     *  work, open shells and ADB exchanges are cut off (a blocked read ignores interrupts), and
     *  both threads are waited for. */
    @Override public void stop() throws Exception {
        alive.set(false);
        if (polling != null) polling.cancel(false);
        if (cecWatch != null) cecWatch.cancel(false);
        if (vendorReads != null) vendorReads.cancel(false);
        if (redetect != null) redetect.cancel(false);
        if (vendorWorker != null) vendorWorker.shutdownNow();
        if (worker != null) worker.shutdownNow();
        Shell.cancelAll();
        // One wait for both threads, inside the host's deadline. Work that is still finishing
        // (a call that ignores interrupts) can no longer reach the projector: every channel is
        // bound to this session, and the session is over.
        long until = System.currentTimeMillis() + STOP_WAIT_MS;
        boolean done = true;
        if (vendorWorker != null) done &= vendorWorker.awaitTermination(Math.max(0, until - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        if (worker != null) done &= worker.awaitTermination(Math.max(0, until - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        if (!done) { try { host.log("Stopped with a call still finishing; it can no longer reach the projector."); } catch (RuntimeException ignored) { /* host gone */ } }
    }

    // ---- the work, all on the worker thread ----

    /** No channel, or (in Auto and Direct) no shell-user channel behind direct for the log, CEC
     *  and the vendor helper: adbd or Shizuku may simply not be up yet after a boot. */
    private boolean channelIncomplete() {
        if (runner == null) return true;
        return runner == direct && extra == null && !CHANNEL_SHIZUKU.equals(String.valueOf(settings.get("channel")));
    }

    /** Detection again later, backing off, while it keeps coming up short. */
    private void retryDetect() {
        if (!alive.get() || worker == null) return;
        if (redetect != null) redetect.cancel(false);
        int wait = redetectSeconds;
        redetectSeconds = Math.min(REDETECT_MAX_S, redetectSeconds * 2);
        redetect = worker.schedule(new Runnable() {
            @Override public void run() { safe(new Task() { @Override public void run() { detect(); } }); }
        }, wait, TimeUnit.SECONDS);
    }

    /** Picks the channel from the setting and what the projector accepts, then reads once. */
    private void detect() {
        if (redetect != null) redetect.cancel(false);
        try { detectOnce(); }
        finally { if (channelIncomplete()) retryDetect(); else redetectSeconds = redetectFirstSeconds; }
    }

    private void detectOnce() {
        String want = String.valueOf(settings.get("channel"));
        runner = null; extra = null; extraName = null;
        int port = adbPort();
        Shell.Runner adb = session(adbFactory.create(port));
        boolean adbUp = false;
        if (CHANNEL_ADB.equals(want)) {
            adbUp = adbOk(adb);
            if (adbUp) { runner = adb; channelName = "ADB"; }
            else { status("The projector's ADB daemon did not answer on 127.0.0.1:" + port + ". Turn on network ADB, or pick another channel.", true); return; }
        } else if (!CHANNEL_SHIZUKU.equals(want)) {
            Shell.Result probe = direct.run(Projector.PROBE_SCRIPT, 4000);
            if (probe.ok()) { runner = direct; channelName = "direct"; }
            else if (CHANNEL_DIRECT.equals(want)) { status("The kiosk process cannot reach the projector's tools directly (" + probe.why() + "). Try ADB or Shizuku.", true); return; }
        }
        if (runner == null && CHANNEL_AUTO.equals(want)) {
            adbUp = adbOk(adb);
            if (adbUp) { runner = adb; channelName = "ADB"; }
        }
        if (runner == null) {
            if (Shell.shizukuGranted(host)) { runner = session(shizukuFactory.create(host)); channelName = "Shizuku"; }
            else {
                status(CHANNEL_SHIZUKU.equals(want)
                    ? "Shizuku is not running or not authorized for Kiosk Satellite."
                    : "No way to reach the projector: direct access was refused, adbd did not answer and Shizuku is not authorized.", true);
                return;
            }
        }
        // The log (temperatures) needs the shell user. Behind a direct channel, the projector's
        // own adbd is the first choice, since it is there after every reboot; Shizuku the second.
        if (runner == direct) {
            if (adbUp || adbOk(adb)) { extra = adb; extraName = "ADB"; }
            else if (Shell.shizukuGranted(host)) { extra = session(shizukuFactory.create(host)); extraName = "Shizuku"; }
        }
        // Shizuku for the other plugins: a loopback ADB session can start it after a reboot, which
        // is the one thing Shizuku cannot do for itself without root.
        Shell.Runner shellUser = runner == adb ? runner : (extra == adb ? extra : null);
        if (shellUser != null && Boolean.TRUE.equals(settings.get("startShizuku"))) shellUser.run(Projector.SHIZUKU_START_SCRIPT, 15000);
        if (Boolean.TRUE.equals(settings.get("stayOn"))) guardRunner().run(Projector.STAY_ON_SCRIPT, Shell.DEFAULT_TIMEOUT_MS);
        if (Boolean.TRUE.equals(settings.get("arcAudio"))) guardRunner().run(Projector.ARC_AUDIO_SCRIPT, Shell.DEFAULT_TIMEOUT_MS);
        // Known before the first read, so the Showing tile does not wait a poll.
        cecRunner = "direct".equals(channelName) ? extra : runner;
        poll();
        watchCec();
        startVendor();
    }

    // ---- HDMI-CEC: the picture follows the source, on the projector itself ----

    /** The CEC history needs the shell user: the ADB or Shizuku channel, or the one behind direct. */
    private void watchCec() {
        if (cecWatch != null) cecWatch.cancel(false);
        lastCec = null;
        cecRunner = "direct".equals(channelName) ? extra : runner;
        if (cecRunner == null) return;
        // What the projector heard before its last boot: after a standby that is the trigger.
        Shell.Result prev = cecRunner.run(Projector.CEC_LOG_START_SCRIPT, 4000);
        if (prev.ok()) {
            List<Projector.Cec> before = Projector.parseCec(prev.stdout);
            if (!before.isEmpty()) host.publishTextSensor("cec_before_boot", "CEC before last boot", Projector.cecTail(before, 4, cecNames));
        }
        cecWatch = worker.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { safe(new Task() { @Override public void run() { readCec(); } }); }
        }, 0, Projector.CEC_POLL_MS, TimeUnit.MILLISECONDS);
    }

    private void readCec() {
        Shell.Result r = cecRunner.run(Projector.CEC_SCRIPT, 4000);
        if (!r.ok()) return;
        List<Projector.Cec> now = Projector.parseCec(r.stdout);
        boolean first = lastCec == null;
        List<Projector.Cec> fresh = first ? Collections.<Projector.Cec>emptyList() : Projector.newCec(lastCec, now);
        lastCec = now;
        // Everything new goes to the log on storage, sent and received, so a standby that wipes the
        // service's history still leaves the last messages behind.
        List<String> logLines = new java.util.ArrayList<>();
        for (Projector.Cec c : first ? now.subList(Math.max(0, now.size() - 10), now.size()) : fresh) logLines.add(c.line);
        if (!logLines.isEmpty()) cecRunner.run(Projector.cecLogAppendScript(logLines), 4000);
        if (first) {
            // A start is a baseline: show the newest notable message, act on none of the old
            // ones, and learn each source's last reported power.
            for (Projector.Cec c : now) if (c.powerReport() != null) sourcePower.put(c.src, c.powerReport());
            for (int i = now.size() - 1; i >= 0; i--) {
                if (now.get(i).notable()) { host.publishTextSensor("cec", "Last CEC message", now.get(i).summary(cecNames)); break; }
            }
            return;
        }
        for (Projector.Cec c : fresh) {
            if (!c.received) continue;
            // The projector asks each source for its power every minute. Only a change is news,
            // and a source that sleeps without sending Standby (the Apple TV put to sleep over the
            // network) shows up here.
            Boolean power = c.powerReport();
            Boolean before = power == null ? null : sourcePower.put(c.src, power);
            boolean powerChanged = before != null && !before.equals(power);
            if (c.notable()) host.publishTextSensor("cec", "Last CEC message", c.summary(cecNames));
            else if (powerChanged) host.publishTextSensor("cec", "Last CEC message",
                Projector.nameOf(c.src, cecNames) + (power ? " woke up" : " went to standby"));
            if (Boolean.FALSE.equals(settings.get("followSource"))) continue;
            if (c.wakes()) sourceWoke(c);
            else if ((c.sleeps() || (powerChanged && Boolean.FALSE.equals(power))) && !c.fromAudioSystem()) sourceSlept(c);
        }
    }

    /** A source woke (Image View On / Active Source): picture on, and to its input when the
     *  projector is showing an app (Projectivy standing in for a sleeping Apple TV). */
    private void sourceWoke(Projector.Cec c) {
        boolean changed = false;
        if (!Boolean.TRUE.equals(lastLight)) changed = picture(true, "Picture on", false);
        // A source that wakes claims the screen, as CEC's One Touch Play means: to its input when
        // an app is in front or another input is showing (2026-10-01 21:11: the Apple TV woke and
        // the picture came back on the Unraid VM's HDMI 3).
        int port = c.port();
        if (port == 0) { Integer p = cecPorts.get(c.src); if (p != null) port = p; }
        Integer showing = currentPort();
        if (port > 0 && (!foregroundIsTv() || (showing != null && showing != port))) {
            command(Projector.inputScript(Projector.INPUT_IDS[port - 1]), "Input");
            commandedInput = Projector.INPUTS[port - 1];
            sourceAtCommand = last.sourceId;
            changed = true;
        }
        if (changed) poll();
    }

    /** The input on the wall: Android's own reading when there is one, else the input last
     *  picked through this plugin, else the vendor's property. */
    private String inputNow(Projector.State s) {
        return liveInput != null ? liveInput : commandedInput != null ? commandedInput : s.input();
    }

    /** The HDMI port on the wall, or null when unknown. */
    private Integer currentPort() {
        return Projector.portOf(inputNow(last));
    }

    /** A source went to sleep (Standby): the projector ignores it for its own power, so the
     *  picture goes dark instead, unless an app on the projector itself is in front or the
     *  source is not on the input showing (2026-10-01 20:40: the Apple TV's Standby darkened the
     *  Unraid VM on HDMI 3). */
    private void sourceSlept(Projector.Cec c) {
        if (Boolean.FALSE.equals(lastLight) || !foregroundIsTv()) return;
        Integer port = cecPorts.get(c.src);
        // The input on the wall now, read now, not as of the last poll or from memory: someone may
        // have switched since (2026-10-12 review: HDMI 2's Standby darkened HDMI 3 chosen a moment
        // before), and with no HDMI input held there is nothing of this source's to darken.
        Integer showing = readLivePort();
        if (port != null && showing == null) {
            note(Projector.nameOf(c.src, cecNames) + " slept on HDMI " + port + " but the input showing could not be read: picture left on");
            return;
        }
        if (port != null && !port.equals(showing)) {
            note(Projector.nameOf(c.src, cecNames) + " slept on HDMI " + port + " while HDMI " + showing + " shows: picture left on");
            return;
        }
        if (picture(false, "Picture off", false)) poll();
    }

    /**
     * The one way the picture changes, on the worker thread: the script (light, flags and hold
     * note, all or nothing), and only once the projector says it worked, what this plugin
     * remembers about it - who holds a dark picture, the silence clock, the LEDs. A failed
     * command changes nothing here, so a retry or the next reading still sees the truth.
     */
    private boolean picture(boolean on, String what, boolean forSilence) {
        if (runner == null) { status("No channel to the projector; check the plugin's settings.", true); return false; }
        if (!alive.get()) return false;
        String held = forSilence ? Projector.HELD_SILENCE : "true";
        Shell.Result r = runner.run(Projector.pictureScript(on, held), Shell.DEFAULT_TIMEOUT_MS);
        if (!halChanged(r)) { status(what + " failed via " + channelName + ": " + r.why(), true); return false; }
        if (!r.ok()) {
            // The light changed and a flag or the hold note did not take: once more for those,
            // and the change itself still counts (and still settles: the heat read next is from
            // before it).
            String flags = Projector.pictureFlagsScript(on, held);
            Shell.Result f = runner.run(flags, Shell.DEFAULT_TIMEOUT_MS);
            if (!f.ok()) {
                status(what + ": the light changed, but its flags could not be set via " + channelName + " (" + f.why() + "); trying again with each read", true);
                pendingFlags = flags;
                pendingFlagsOn = on;
            } else pendingFlags = null;
        } else pendingFlags = null;
        pendingOff = false;
        commandedPictureOff = !on;
        darkForSilence = !on && forSilence;
        silentSince = null;
        ledsForPicture(on);
        settleLight();
        return true;
    }

    /** Stay on keeps the projector out of standby, and with it out of its own no-signal shutdown,
     *  so a source that sleeps without CEC (the Unraid VM on HDMI 3) left the laser lighting a
     *  "No signal" screen all day (2026-10-11: 16 hours). After darkWithoutSignal minutes with no
     *  signal on the HDMI input in front the picture goes dark - Android and the network stay up -
     *  and it comes back when the signal does, unless someone has touched the picture since.
     *  signal is null when there is no telling (an app in front, or no read). */
    void watchSignal(final Boolean signal) { watchSignal(signal, lastShowing); }

    /** input is what was showing when the signal was sampled, not when the reading arrived. */
    void watchSignal(final Boolean signal, final String input) {
        submit(new Task() { @Override public void run() { onSignal(signal, input); } });
    }

    /** A signal reading, on the worker: input is what was showing when it was read. */
    private void onSignal(Boolean signal, String input) {
        Object v = settings.get("darkWithoutSignal");
        int minutes = v instanceof Number ? Math.max(0, Math.min(240, ((Number) v).intValue())) : 15;
        if (signal == null || minutes == 0) { silentSince = null; return; }
        if (signal) {
            silentSince = null;
            // Only a dark this plugin chose for silence comes back, and the claim is kept until
            // the picture is really on: a failed restore is tried again on the next reading.
            if (!darkForSilence || !commandedPictureOff || Boolean.TRUE.equals(lastLight)) return;
            note("signal back on " + input + ": picture on");
            if (picture(true, "Picture on", false)) poll();
            return;
        }
        if (!Boolean.TRUE.equals(lastLight)) { silentSince = null; return; }
        long now = System.currentTimeMillis();
        // The clock belongs to one input: silence on HDMI 3 says nothing about HDMI 1. (Showing
        // reads "HDMI 1 · Apple TV", so it is compared by port; null is an input not yet known.)
        Integer port = Projector.portOf(input == null ? null : input.split(" \u00b7 ")[0]);
        if (silentSince == null || !java.util.Objects.equals(port, silentPort)) { silentSince = now; silentPort = port; return; }
        if (now - silentSince < minutes * silenceMinuteMs) return;
        // Check again right before acting, and act only on what is known: the TV app positively in
        // front (not merely "could not tell"), and an HDMI port read just now that is the one the
        // silence was on. Anything else waits for a later reading (a queued Off once darkened Plex).
        Boolean tv = foregroundTv();
        if (Boolean.FALSE.equals(tv)) { silentSince = null; return; }
        if (tv == null) return;
        Integer live = readLivePort();
        if (live == null) return;
        if (silentPort != null && !live.equals(silentPort)) { silentSince = now; silentPort = live; return; }
        note("no signal on " + input + " for " + minutes + " min: picture off");
        if (picture(false, "Picture off", true)) poll();
    }

    /** Whether a picture script got as far as changing the light (all of it, or the HAL call
     *  before a later step failed). */
    private static boolean halChanged(Shell.Result r) { return r.ok() || r.stdout.contains(Projector.HAL_DONE); }

    /** Remote Admin's Overview tiles (two at most) and the Showing sensor. */
    private void publishStatus(Projector.State s) {
        // Real CPU use between two reads, from the kernel's counters.
        Double cpu = null;
        if (s.cpuTotal > 0) {
            cpu = Projector.cpuPercent(lastCpuBusy, lastCpuTotal, s.cpuBusy, s.cpuTotal);
            lastCpuBusy = s.cpuBusy;
            lastCpuTotal = s.cpuTotal;
            // Whole percent, sent on a move of 5 points (or every 5 minutes): idle use wobbles a
            // few points every poll, and each new value was a recorder row.
            if (cpu != null) publishMeasured("cpu_busy", "CPU busy", sensorMeta("%", null, "measurement", 0), (double) Math.round(cpu), 5, 5 * MINUTE);
        }
        String[] projector = Projector.projectorTile(s.light, Projector.hottestLaser(s), s.fanPercent, cpu);
        String showing = null;
        if (cecRunner != null) {
            Shell.Result r = cecRunner.run(Projector.SHOWING_SCRIPT, 4000);
            if (r.ok()) {
                showing = Projector.showing(Projector.foregroundPackage(r.stdout), inputNow(s), Projector.cecDevices(r.stdout));
                Map<Integer, String> names = Projector.cecNames(r.stdout);
                if (!names.isEmpty()) cecNames = names;
                Map<Integer, Integer> ports = Projector.cecPorts(r.stdout);
                if (!ports.isEmpty()) cecPorts = ports;
            }
        }
        if (showing != null) { host.publishTextSensor("showing", "Showing", showing); lastShowing = showing; }
        if (!tilesSupported) return;
        try {
            String p = projector[0] + "|" + projector[1];
            if (!p.equals(lastProjectorTile)) { host.publishStatusTile("projector", "Projector", projector[0], projector[1]); lastProjectorTile = p; }
            if (showing != null) {
                String level = Boolean.TRUE.equals(s.light) ? "on" : Boolean.FALSE.equals(s.light) ? "off" : "";
                String v = level + "|" + showing;
                if (!v.equals(lastShowingTile)) { host.publishStatusTile("showing", "Showing", level, showing); lastShowingTile = v; }
            }
        } catch (UnsupportedOperationException e) {
            tilesSupported = false;   // an older Kiosk Satellite without Overview tiles
        }
    }

    // ---- The vendor's own API, through a helper process (VendorTool) ----

    /** Home Assistant events the helper carries out, and the helper's name for each setting. */
    private static final Map<String, String> VENDOR_EVENTS = new HashMap<>();
    static {
        VENDOR_EVENTS.put("select.brightness_mode", "brightness");
        VENDOR_EVENTS.put("select.dynamic_black", "db");
        VENDOR_EVENTS.put("switch.cinema_24p", "p24");
        VENDOR_EVENTS.put("switch.dynamic_tone_mapping", "dhdr");
        VENDOR_EVENTS.put("switch.low_latency", "low_latency");
    }

    /** The vendor's number for an option or a switch state. */
    static int vendorValue(String event, Map<String, Object> payload) {
        if (event.startsWith("select.")) {
            String[] options = event.equals("select.brightness_mode") ? Projector.BRIGHTNESS_MODES : Projector.DYNAMIC_BLACK;
            Object o = payload.get("option");
            for (int i = 0; i < options.length; i++) if (options[i].equals(o)) return i;
            throw new IllegalArgumentException("Unknown option " + o);
        }
        Object on = payload.get("on");
        if (!(on instanceof Boolean)) throw new IllegalArgumentException("A switch wants a boolean");
        if (event.equals("switch.cinema_24p")) return (Boolean) on ? Projector.P24_ON : 0;
        return (Boolean) on ? 1 : 0;
    }

    /** Installs the helper (this plugin's own DEX) on the projector, reads once in full, and then
     *  every vendorSeconds: quick values each time, the signal on every read. */
    private void startVendor() {
        if (vendorReads != null) vendorReads.cancel(false);
        if (vendorWorker == null) return;
        Object v = settings.get("vendorSeconds");
        int seconds = v instanceof Number ? Math.max(30, Math.min(600, ((Number) v).intValue())) : 60;
        vendorReads = vendorWorker.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { safe(new Task() { @Override public void run() {
                if (vendorPath == null) installVendor();
                if (vendorPath != null) vendorRun("read", true);
            } }); }
        }, 0, seconds, TimeUnit.SECONDS);
    }

    /** Hook for tests: a helper already in place. */
    void useVendor(String path) { vendorPath = path; }

    String vendorPathForTests() { return vendorPath; }

    /** Puts this build's helper on the projector and trusts it only for its content: the copy
     *  there must hash to this DEX before it is ever run. A fresh copy is staged under a name of
     *  its own, checked, then moved into place in one step. */
    private void installVendor() {
        Shell.Runner r = cecRunner;
        if (r == null) return;
        byte[] dex = ownDex();
        if (dex == null) return;
        String sha = sha1(dex);
        String path = Projector.vendorDexPath(sha.substring(0, 12));
        Shell.Result have = r.run(Projector.vendorHashScript(path), 8000);
        if (!have.ok() || !sha.equals(Projector.vendorHash(have.stdout))) {
            String token = Long.toHexString(Double.doubleToLongBits(Math.random()) ^ System.nanoTime());
            String b64 = base64(dex);
            for (int i = 0; i < b64.length(); i += VENDOR_CHUNK) {
                if (!alive.get()) return;
                Shell.Result c = r.run(Projector.vendorChunkScript(path, token, b64.substring(i, Math.min(b64.length(), i + VENDOR_CHUNK)), i == 0), 10000);
                if (!c.ok()) { host.log("Vendor helper install failed: " + c.why()); r.run("rm -f " + path + "." + token + ".b64", 4000); return; }
            }
            if (!alive.get()) return;
            Shell.Result done = r.run(Projector.vendorFinishScript(path, token, sha), 15000);
            if (!done.ok() || !done.stdout.contains("present")) { host.log("Vendor helper install failed: " + done.why()); return; }
        }
        // Trusted only once it answers.
        Shell.Result check = r.run(Projector.vendorScript(path, "quick"), 20000);
        if (check.ok() && check.stdout.contains("brightness=")) { vendorPath = path; vendorFailures = 0; }
        else host.log("Vendor helper did not run: " + check.why());
    }

    /** Base64 pieces that fit one shell argument on every channel: Shizuku takes at most 4096
     *  characters per argument, and the printf around the piece needs a little of that. */
    static final int VENDOR_CHUNK = 3600;
    /** Kiosk Satellite refuses a Shizuku command longer than this; the helper's read takes ~10 s. */
    static final int VENDOR_TIMEOUT_MS = 30000;

    /** This plugin's own DEX, from the plugin.jar its class loader reads (Kiosk Satellite's
     *  DexClassLoader); null when there is none (the tests). Not getResourceAsStream: that asks
     *  the parent first and returns Kiosk Satellite's own classes.dex (0.4.0 installed that). */
    private static byte[] ownDex() {
        ClassLoader cl = AuroraPlugin.class.getClassLoader();
        java.net.URL own = null;
        try {
            if (cl != null) for (java.util.Enumeration<java.net.URL> e = cl.getResources("classes.dex"); e.hasMoreElements(); ) {
                java.net.URL u = e.nextElement();
                if (u.toString().contains("plugin.jar!")) own = u;
            }
        } catch (java.io.IOException e) {
            return null;
        }
        if (own == null) return null;
        try (java.io.InputStream in = own.openStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    static String sha1(byte[] data) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : java.security.MessageDigest.getInstance("SHA-1").digest(data)) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Standard base64, without java.util.Base64 (the test JDK and old Android disagree on it). */
    static String base64(byte[] data) {
        final String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b = (data[i] & 0xff) << 16 | (i + 1 < data.length ? (data[i + 1] & 0xff) << 8 : 0) | (i + 2 < data.length ? data[i + 2] & 0xff : 0);
            sb.append(abc.charAt(b >> 18 & 63)).append(abc.charAt(b >> 12 & 63));
            sb.append(i + 1 < data.length ? abc.charAt(b >> 6 & 63) : '=').append(i + 2 < data.length ? abc.charAt(b & 63) : '=');
        }
        return sb.toString();
    }

    /** Runs the helper and publishes what it read. A full read (with the signal) replaces the
     *  last one; a quick one keeps its signal values. */
    private void vendorRun(String args, boolean full) {
        Shell.Runner r = cecRunner;
        String path = vendorPath;
        if (r == null || path == null) return;
        String showingBefore = lastShowing;
        Shell.Result res = r.run(Projector.vendorScript(path, args), VENDOR_TIMEOUT_MS);
        if (!res.ok()) {
            host.log("Vendor helper (" + args + ") failed: " + res.why());
            // A few failures in a row: check the copy (and reinstall it) before the next read.
            if (++vendorFailures >= 3) { vendorPath = null; vendorFailures = 0; }
            return;
        }
        Map<String, String> read = Projector.parseVendor(res.stdout);
        if (read.isEmpty()) {
            // Nothing usable (only error lines): a failure like any other, and the old readings
            // are not passed off as new.
            host.log("Vendor helper (" + args + ") read nothing: " + res.stdout.trim().replace('\n', ' '));
            if (++vendorFailures >= 3) { vendorPath = null; vendorFailures = 0; }
            return;
        }
        vendorFailures = 0;
        // The signal drives the no-signal dark only when this read sampled it (a full read), on
        // the input that was showing throughout; a quick read's kept value is history.
        boolean fresh = full && read.containsKey("signal");
        String sampled = showingBefore != null && showingBefore.equals(lastShowing) ? showingBefore : null;
        if (!full) {
            for (String k : new String[] {"signal", "hdr", "res", "max_cll"}) if (vendor.containsKey(k) && !read.containsKey(k)) read.put(k, vendor.get(k));
        }
        vendor = read;
        publishVendor(read, fresh, sampled, fresh && sampled == null && showingBefore != lastShowing);
    }

    /** For tests: a reading published as if it were not a fresh signal sample. */
    void publishVendor(Map<String, String> v) { publishVendor(v, false, null, false); }

    /** fresh: this read sampled the signal now; sampled: the input showing throughout it; moved:
     *  the input changed during the read, so its signal belongs to neither. */
    private void publishVendor(Map<String, String> v, boolean fresh, String sampled, boolean moved) {
        if (!alive.get()) return;
        Integer b = intOf(v.get("brightness"));
        host.publishSelect("brightness_mode", "Laser brightness", Projector.BRIGHTNESS_MODES,
            b != null && b >= 0 && b < Projector.BRIGHTNESS_MODES.length ? Projector.BRIGHTNESS_MODES[b] : null);
        Integer db = intOf(v.get("db"));
        host.publishSelect("dynamic_black", "Dynamic black", Projector.DYNAMIC_BLACK,
            db != null && db >= 0 && db < Projector.DYNAMIC_BLACK.length ? Projector.DYNAMIC_BLACK[db] : null);
        Integer p24 = intOf(v.get("p24"));
        if (p24 != null) host.publishSwitch("cinema_24p", "24p cinema mode", p24 == Projector.P24_ON);
        Integer dhdr = intOf(v.get("dhdr"));
        if (dhdr != null) host.publishSwitch("dynamic_tone_mapping", "Dynamic tone mapping", dhdr != 0);
        if (v.containsKey("low_latency")) host.publishSwitch("low_latency", "Low latency", "true".equals(v.get("low_latency")));
        String fans = v.get("fans");
        if (fans != null) {
            // Positional; an empty slot is a fan that could not be read this time (unknown).
            String[] f = fans.split(",", -1);
            for (int i = 0; i < f.length; i++) {
                Integer rpm = intOf(f[i]);
                // The tachometers jitter 10-20 rpm at a steady speed: sent on a move of 50 rpm, or
                // every 30 minutes. Fan speed (%) is the one to watch; these are diagnostics.
                publishMeasured("fan" + (i + 1) + "_rpm", "Fan " + (i + 1), sensorMeta("rpm", null, "measurement", 0), rpm == null ? null : rpm.doubleValue(), 50, 30 * MINUTE);
            }
        }
        // The signal belongs to the TV app's input: with an app in front there is none to report.
        boolean tvFront = lastShowing == null || lastShowing.startsWith("HDMI");
        if (v.containsKey("signal")) host.publishBinarySensor("signal", "Signal", "", tvFront ? "true".equals(v.get("signal")) : null);
        if (moved) watchSignal(null, null);
        else if (fresh) {
            boolean sampledTv = sampled == null || sampled.startsWith("HDMI");
            watchSignal(sampledTv ? Boolean.valueOf("true".equals(v.get("signal"))) : null, sampled);
        }
        String format = tvFront ? Projector.videoFormat(v) : null;
        host.publishTextSensor("video_format", "Video format", format == null ? "" : format);
    }

    private static Integer intOf(String s) {
        if (s == null) return null;
        try { return Integer.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    /** What the Showing sensor last said, for the notes below. */
    private volatile String lastShowing;

    /** A line in the log on storage, beside the CEC messages, for what the plugin decided about
     *  the light on its own: after an unexplained dark picture or standby it says when and why.
     *  The CEC readers skip it. */
    private void note(String what) {
        Shell.Runner r = cecRunner != null ? cecRunner : runner;
        if (r == null) return;
        String time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date());
        r.run(Projector.cecLogAppendScript(Collections.singletonList("[P] time=" + time + " " + what)), 4000);
        // And to Home Assistant, whose history keeps every one: how often the plugin overrides.
        if (alive.get()) host.publishTextSensor("correction", "Last correction", what);
    }

    /** TRUE: the TV app (an HDMI input) is in front; FALSE: an app is; null: no telling (no
     *  shell-user channel, no answer, or nothing resumed). */
    private Boolean foregroundTv() {
        Shell.Runner r0 = cecRunner;
        if (r0 == null) return null;
        Shell.Result r = r0.run(Projector.FOREGROUND_SCRIPT, 4000);
        if (!r.ok() || r.stdout.trim().isEmpty()) return null;
        return r.stdout.contains(Projector.TV_APP);
    }

    /** For following a source over CEC, "no telling" counts as the TV, as it always has: there
     *  the live input read that follows is what keeps the wrong input from going dark. */
    private boolean foregroundIsTv() { return !Boolean.FALSE.equals(foregroundTv()); }

    /** The stay-on guard writes a secure setting, which the shell user may and the kiosk process
     *  may not: behind a direct channel the shell-user one does it. */
    private Shell.Runner guardRunner() { return extra != null ? extra : runner; }

    private static boolean adbOk(Shell.Runner adb) {
        Shell.Result r = adb.run(Projector.ADB_PROBE_SCRIPT, 4000);
        return r.ok() && r.stdout.contains("uid=2000");
    }

    /** adbd's port on the stock firmware; a manifest number setting for it was refused by the host's range check. */
    private int adbPort() { return 5555; }

    /** Runs a command on the main channel; false (and a status) when it did not work. */
    private boolean command(String script, String what) {
        if (runner == null) { status("No channel to the projector; check the plugin's settings.", true); return false; }
        if (!alive.get()) return false;
        Shell.Result r = runner.run(script, Shell.DEFAULT_TIMEOUT_MS);
        if (!r.ok()) status(what + " failed via " + channelName + ": " + r.why(), true);
        return r.ok();
    }

    private void poll() {
        if (runner == null || !alive.get()) return;
        Shell.Result r = runner.run(Projector.POLL_SCRIPT, Shell.DEFAULT_TIMEOUT_MS);
        if (!r.ok()) { status("Could not read the projector via " + channelName + ": " + r.why(), true); return; }
        Projector.State s = Projector.parse(r.stdout);
        // What this plugin asked for survives a Kiosk Satellite restart in the projector's own
        // properties; the in-memory flag alone forgot it, and the laser came back on.
        if (s.heldOff != null && pendingFlags == null) {
            commandedPictureOff = s.heldOff;
            // ...and why: a dark chosen for silence survives a restart as that, so the signal
            // coming back can still undo it.
            darkForSilence = s.heldOff && s.heldForSilence;
        }
        // The log answers only the shell user: behind a direct channel, the shell-user channel
        // reads that one line.
        if (s.temperatures.isEmpty() && extra != null) {
            Shell.Result more = extra.run(Projector.TEMPS_SCRIPT, Shell.DEFAULT_TIMEOUT_MS);
            if (more.ok()) {
                Projector.State log = Projector.parse(more.stdout);
                s.temperatures.putAll(log.temperatures);
                if (s.fanPercent == null) s.fanPercent = log.fanPercent;
                if (s.lightSource == null) { s.lightSource = log.lightSource; s.lightSourceAt = log.lightSourceAt; }
                if (s.cpuTotal == 0) { s.cpuBusy = log.cpuBusy; s.cpuTotal = log.cpuTotal; }
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
        // The HAL's own last light-source command outranks the heat: it is logged the moment it
        // is sent, a laser relit while still hot barely warms further, and it names every sender.
        // A command older than this plugin's own last picture command is stale: its own, newer
        // command was pruned from the log (2026-09-27: an old On re-lit a picture turned off from HA).
        if (s.lightSource != null && s.lightSourceAt > 0 && s.lightSourceAt < pictureCommandAt) s.lightSource = null;
        // Each command counts once, and only while fresh: the poll sees every new one within its
        // interval, so one older than the settle time is history (2026-09-30: an On from 11:46
        // still outvoted the flags three hours later).
        String why = heatSays == null ? null : "heat (blue " + Math.round(blue) + ")";
        if (s.lightSource != null && s.lightSourceAt > lightSourceHandledAt) {
            if (now - s.lightSourceAt < Projector.LASER_SETTLE_MS) {
                heatSays = s.lightSource;
                why = "AT+LightSource=" + (s.lightSource ? "On" : "Off");
            }
            lightSourceHandledAt = s.lightSourceAt;
        }
        // A correction that did not go through last time is still owed, evidence or not.
        if (pendingOff && commandedPictureOff && heatSays == null) {
            heatSays = Boolean.TRUE;
            why = "a correction that did not go through";
        }
        if (heatSays != null && !heatSays.equals(s.light)) {
            note("laser " + (heatSays ? "lit" : "dark") + " behind the flags, by " + why
                + (lastShowing != null ? "; showing " + lastShowing : ""));
            if (heatSays && commandedPictureOff && now - startedAt < Projector.RESTART_GUARD_MS) {
                // Kiosk Satellite starting brings its activity forward; the vendor's background
                // service then lights the laser for the Android UI (AT+LightSource=On, 2026-09-26),
                // flags untouched. The picture was held off from here, so it goes back off.
                if (!alive.get()) return;
                if (halChanged(runner.run(Projector.pictureScript(false, darkForSilence ? Projector.HELD_SILENCE : "true"), Shell.DEFAULT_TIMEOUT_MS))) {
                    s.light = Boolean.FALSE;
                    s.screenOff = Boolean.TRUE;
                    settleLight();
                    pendingOff = false;
                } else {
                    // Lit, and said so; the Off is owed until it works or the guard time is over.
                    s.light = Boolean.TRUE;
                    pendingOff = true;
                }
            } else {
                s.light = heatSays;
                pendingOff = false;
                if (heatSays) { s.screenOff = Boolean.FALSE; commandedPictureOff = false; darkForSilence = false; }
                if (!alive.get()) return;
                runner.run(Projector.reflagLightScript(heatSays), 4000);
            }
        }
        // Owed flags, now that this read has settled what the light is: written while it is still
        // the light they were for, dropped once it has changed since (a remote key, a source, the
        // heat), so a dark's "screen off, held" is never written over a picture lit after it.
        if (pendingFlags != null && alive.get()) {
            if (Boolean.valueOf(pendingFlagsOn).equals(s.light)) {
                if (runner.run(pendingFlags, Shell.DEFAULT_TIMEOUT_MS).ok()) { pendingFlags = null; s.screenOff = !pendingFlagsOn; }
            } else {
                pendingFlags = null;
                note("the light changed before its flags could be written; the old flags were dropped");
            }
        }
        // Android waking its display (a Kiosk Satellite restart does it) clears cur.prj.screenOff
        // while the light stays off. The flag is what tells the vendor's services the dark is
        // deliberate, so when this plugin turned the picture off it puts the flag back.
        if (!alive.get()) return;
        if (commandedPictureOff && Boolean.FALSE.equals(s.light) && Boolean.FALSE.equals(s.screenOff)) {
            if (runner.run(Projector.REFLAG_SCREEN_OFF_SCRIPT, 4000).ok()) s.screenOff = Boolean.TRUE;
        }
        if (!alive.get()) return;
        // A guard that slipped (the projector's own menu, a factory default after an update) is
        // put back here, not only at start: standby takes the network with it. The read after
        // this one says whether it held.
        if (Boolean.TRUE.equals(settings.get("stayOn")) && Boolean.FALSE.equals(s.staysOn())) {
            guardRunner().run(Projector.STAY_ON_SCRIPT, Shell.DEFAULT_TIMEOUT_MS);
        }
        // The same for ARC audio to a soundbar.
        if (!alive.get()) return;
        if (Boolean.TRUE.equals(settings.get("arcAudio"))) {
            for (String key : Projector.ARC_SETTINGS) {
                if (!"0".equals(globals.get(key))) continue;
                guardRunner().run(Projector.ARC_AUDIO_SCRIPT, Shell.DEFAULT_TIMEOUT_MS);
                note("ARC audio settings found off; turned back on");
                break;
            }
        }
        readLiveInput();
        publish(s);
        publishStatus(s);
    }

    /** Which HDMI input the TV app holds, through the shell-user channel (for the poll). */
    private void readLiveInput() { readLivePort(); }

    /** The HDMI port the TV app holds, read now: null when none is held or it could not be read
     *  (never the remembered or vendor-property input). Updates liveInput when it could be read. */
    private Integer readLivePort() {
        Shell.Runner r = cecRunner;
        if (r == null) return null;
        Shell.Result lr = r.run(Projector.LIVE_INPUT_SCRIPT, 4000);
        if (!lr.ok()) return null;
        Integer p = Projector.livePort(lr.stdout);
        liveInput = p == null ? null : Projector.INPUTS[p - 1];
        if (liveInput != null) commandedInput = null;   // the projector's own reading replaces the memory
        return p;
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
        host.publishSelect("input", "Input", Projector.INPUTS, inputNow(s));
        host.publishBinarySensor("stays_on", "Stays on when the source sleeps", "", s.staysOn());
        host.publishSelect("picture_mode", "Picture mode", Projector.PICTURE_MODES, s.pictureModeLabel());
        // What the laser is doing, as best this plugin can tell (the flags corrected by the HAL's
        // light-source commands and the heat). Replaces the vendor's inverted "screen off" flag.
        host.publishBinarySensor("laser", "Laser status", "", s.light);
        // The vendor's eye-protection flag: its body sensor cuts the light when someone is in front
        // of the lens, which is a dark picture nobody here asked for. A change is noted.
        host.publishBinarySensor("eye_protect", "Eye protection", "", s.eyeProtect);
        if (s.eyeProtect != null && lastEyeProtect != null && !s.eyeProtect.equals(lastEyeProtect)) {
            note(s.eyeProtect ? "eye protection tripped: the body sensor has the light off" : "eye protection cleared");
        }
        if (s.eyeProtect != null) lastEyeProtect = s.eyeProtect;
        // Tenths of an hour, so a lit laser adds a row every 6 minutes rather than every minute.
        publishMeasured("laser_hours", "Laser hours", sensorMeta("h", "duration", "total_increasing", 1),
            s.laserMinutes == null ? null : Math.round(s.laserMinutes / 6.0) / 10.0, 0.05, HOUR);
        // The speed appothermal commands (the same on every fan PWM); the fans have no tachometer.
        if (s.fanPercent != null || fanPublished) {
            publishMeasured("fan_speed", "Fan speed", sensorMeta("%", null, "measurement", 0),
                s.fanPercent == null ? null : s.fanPercent.doubleValue(), 1, 30 * MINUTE);
            fanPublished = true;
        }
        // One number for the light engine's heat: whichever laser bank runs warmest.
        Double laser = Projector.hottestLaser(s);
        if (laser != null || laserPublished) {
            publishMeasured("laser_temp", "Laser temperature", sensorMeta("°C", "temperature", "measurement", 0), wholeDegrees(laser), TEMP_STEP, 30 * MINUTE);
            laserPublished = true;
        }
        for (String[] t : Projector.TEMPERATURES) {
            Double value = s.temperatures.get(t[0]);
            String key = "temp_" + t[1];
            // Only publish a temperature the projector has ever reported, so a channel that cannot
            // read the log does not fill Home Assistant with eight unknown sensors; one that was
            // reported and is gone turns unknown rather than keeping its last value for good.
            if (value != null || s.temperatures.containsKey(t[0]) || tempsPublished.contains(key)) {
                publishMeasured(key, t[2] + " temperature", sensorMeta("°C", "temperature", "measurement", 0), wholeDegrees(value), TEMP_STEP, 30 * MINUTE);
                if (value != null) tempsPublished.add(key);
            }
        }
        StringBuilder text = new StringBuilder();
        text.append(s.light == null ? "Picture unknown" : (s.light ? "Picture on" : (Boolean.TRUE.equals(s.screenOff) ? "Screen off" : "Picture off")));
        String input = inputNow(s);
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

    private static final long MINUTE = 60_000, HOUR = 60 * MINUTE;
    /** Temperatures go out on a 2 °C move: a sensor on the edge of a degree flips every read. */
    private static final double TEMP_STEP = 2;

    /** A measurement through {@link Steady}: sent only on a real move, or once it is maxAgeMs old. */
    private void publishMeasured(final String key, final String name, final Map<String, Object> meta, final Double value, double step, long maxAgeMs) {
        try {
            steady.publish(key, value, step, maxAgeMs, System.currentTimeMillis(), new Runnable() {
                @Override public void run() { host.publishSensor(key, name, meta, value); }
            });
        } catch (IllegalStateException e) {
            // The host's per-second budget: nothing was recorded, so the next reading tries again.
        }
    }

    private static Double wholeDegrees(Double c) { return c == null ? null : (double) Math.round(c); }

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
