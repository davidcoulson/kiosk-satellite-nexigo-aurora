# NexiGo Aurora Pro: inside the projector, and a Kiosk Satellite plugin for it

The NexiGo Aurora Pro is a triple-laser UST projector built on an Appotronics (ALPD) light engine and a
MediaTek Android TV 9 board. This repository is what came out of opening it up over ADB: how the
Android side talks to the light engine, which apps and services do what, how power really works, and
which of it can be driven from outside. It also carries the Home Assistant scripts that use those
findings today and, coming, a [Kiosk Satellite](https://github.com/davidcoulson/kiosk-satellite) plugin
that runs on the projector itself and exposes the useful parts as Home Assistant entities.

Nothing here needs root. Everything was done with `adb shell` as the `shell` user on the stock
firmware (`m7642-userdebug 9 PPR2.180905.006.A1`, dated 2025-12-02), which ships with SELinux
permissive and ADB over TCP enabled.

## The short version

| Want | How |
| --- | --- |
| Picture off, projector stays on | `setprop cur.appo.light.enabled false; /vendor/bin/hw/projector-test setLightSourceOnOff false; setprop cur.prj.screenOff true` |
| Picture back | `setprop cur.appo.light.enabled true; /vendor/bin/hw/projector-test setLightSourceOnOff true; setprop cur.prj.screenOff false` (any remote key does the same) |
| Switch to HDMI 2 | `am start -a android.intent.action.VIEW -d content://android.media.tv/passthrough/com.mediatek.tvinput%2F.hdmi.HDMIInputService%2FHW6` (HW5-HW8 = HDMI 1-4) |
| Picture mode | `settings put global picture_mode 8` (2 Cinema Home, 8 Cinema Pro, 9 Standard, 3 Brightest, 10 Game, 0 Custom) |
| Open the projector's own settings (keystone, focus, brightness mode...) | `am start -n com.xming.xmprojectorsettings/.ProjectorSettingActivity` |
| Open an app | `monkey -p com.plexapp.android -c android.intent.category.LEANBACK_LAUNCHER 1` |
| Laser hours, temperatures, fans | through the HAL: see [docs/hal-api.md](docs/hal-api.md) |
| Front LED bar | `projector-test setAppoLeds 2 <status>` - see [docs/behaviors.md](docs/behaviors.md#the-front-led-bar) |
| Power on | Only HDMI-CEC (or the remote). Standby cuts the network, so nothing over IP can wake it. |

The picture-off recipe is exactly what the projector's own power menu does for "Screen off"
(`ScreenOffBehavior` in `com.appo.backgroundservice`): the two properties tell the eye-protection
sensor and the sleep logic that dark is deliberate. Send the light-source command without them and
the firmware re-lights the laser within seconds, or drops into standby. With them, the laser and fans
stop, the front LEDs go out, Android stays awake, ADB and CEC keep working, and the picture is back
about a second after the on command instead of after a 25 s boot.

## What is in here

- [docs/architecture.md](docs/architecture.md): the board, the OS, the Appotronics HAL and the serial
  "AT" protocol to the light-engine MCU, the vendor apps, and how they fit together.
- [docs/hal-api.md](docs/hal-api.md): every method of the projector-manager HAL, the Java API on
  top of it (`com.appotronics.support` and `com.appo.tv`), the `projector-test` command-line tool,
  and the AT commands seen on the wire.
- [docs/apps-and-services.md](docs/apps-and-services.md): the packages on the box and what each
  one is for, the background services, the power-menu behaviours and the broadcasts they listen to.
- [docs/behaviors.md](docs/behaviors.md): power states, screen off, wake, standby, the eye-protection
  body sensor, the sleep timer, no-signal shutdown, CEC, boot source and the front LED bar.
- [docs/properties-and-settings.md](docs/properties-and-settings.md): the `cur.prj.*`,
  `persist.prj.*`, `persist.appo.*` system properties, the `Settings.Global` keys, and the
  eMMC/unify keys the vendor code persists things in.
- [docs/inputs-and-keys.md](docs/inputs-and-keys.md): the HDMI inputs and how to select them, the
  remote's key layout, and the key events that work over ADB.
- [docs/home-assistant.md](docs/home-assistant.md): driving it from Home Assistant today (Android
  Debug Bridge integration + scripts) and what the plugin will change.
- [docs/adb-cheatsheet.md](docs/adb-cheatsheet.md): the commands, in one place.
- [docs/raw/](docs/raw/): the dumps the above was written from (properties, settings, packages,
  key layout, HALs, CEC, TV inputs, running services, the decoded Java API, the HIDL method list),
  with identifiers removed.

## The plugin

Kiosk Satellite runs on the projector's Android (an ordinary Android TV 9 device; `minSdk 24`) and
appears in Home Assistant as an ESPHome device. This plugin adds the projector to that device:

| Entity | Kind | What it does |
| --- | --- | --- |
| **Picture** | switch | The screen-off recipe above. Off: laser and fans stop, Android stays awake. On: back in a second. State is read back from `cur.appo.light.enabled`, which every path on the projector (power menu, remote key, sleep timer) keeps in step, and checked against the laser's heat: a blue laser well over ambient and heating is lit, one near ambient is dark (the HAL's minute counter climbs with the laser cold and is not used). |
| **Input** | select | HDMI 1-4, through the TV input framework. Reads back from `cur.prj.currentSourceId`. |
| **Picture mode** | select | Cinema Home, Cinema Pro, Standard, Brightest, Game, Custom (`picture_mode`). Read through the framework; written through the framework when Kiosk Satellite holds `WRITE_SECURE_SETTINGS`, else through Shizuku. |
| **Screen off** | binary sensor | `cur.prj.screenOff`: the dark is deliberate. |
| **Stays on when the source sleeps** | binary sensor | Every guard in place: CEC standby ignored, the vendor sleep timer off (`persist.prj.sleepMode` 0; it shipped on and stands the projector by after about two hours) and the no-signal shutdown off. The plugin sets them at start and again on any read that finds one changed (the no-signal setting needs the shell user: ADB, Shizuku or the permission above). |
| **Laser hours** | sensor | The lifetime light-source counter from the HAL's own store (`getPlatformProperty used_time`, minutes; the value the projector's menu shows). |
| **Colour wheel, DMD, Ambient temperature** | sensors | The light engine's NTCs, from its 30-second report in the log. Needs the shell user to read the log: the loopback ADB channel or Shizuku; a temperature is published once it has been seen. |
| **Laser temperature** | sensor | The warmest of the red, green and blue laser banks: one number for the light engine's heat. |
| **Fan speed** | sensor | The speed the vendor's thermal daemon commands, in percent, from the same log (one value drives every fan PWM; the fans have no tachometer). |

| **Front LEDs** | select | Off, Standby, Power on, Loop, Bluetooth, Update: the bar's patterns (`setAppoLeds 2 <status>`, verified). By default the bar follows the picture: standby lights while it is dark, nothing while it shows. |

and seven actions, which Kiosk Satellite can put in its drawer, on a gesture or on the Home
Assistant device as buttons: **Open projector settings** (the projector's own app: keystone, focus,
brightness mode, projection mode...), **Picture off** / **Picture on** / **Picture on/off** (one
button for a remote key), **Front LEDs off** /
**Front LEDs: standby lights** and **Refresh readings**.

Every command is a fixed script run through `/system/bin/sh -c`; a Home Assistant option can only
become one of the numbers in `Projector.java`. After each command the plugin reads the projector
back and publishes what it found; there is no optimistic state. Nothing touches the fans: the
firmware's `appothermal` runs those from the same temperatures.

### Install

1. Kiosk Satellite on the projector, with its ESPHome device added to Home Assistant.
2. **Plugin Manager > Add plugin**, this repository's URL, **Trust and install**, then enable it.
   (Or **Developer Tools > Install from ZIP** with a local build from `python3 tools/build.py`.)
3. Picture mode and the no-signal shutdown are `Settings.Global` writes, which need
   `WRITE_SECURE_SETTINGS`. Stock Kiosk Satellite does not declare that permission, so `pm grant`
   refuses it; a build that declares it can be granted once over ADB and keeps it across reboots.
   Until then those two writes go through Shizuku when it is running, and the projector's own menu
   otherwise. Everything else works without it.
4. Nothing else. The stock firmware leaves the projector's own ADB daemon on port 5555 with no
   key, and the plugin talks to it from the kiosk process (the **ADB** channel): that is the shell
   user, so the log and the temperatures come through it behind the direct channel, and it starts
   [Shizuku](https://shizuku.rikka.app/) after a reboot if Shizuku is installed, for the plugins
   that want it. Shizuku itself is optional.
   Satellite adds the temperature sensors. A Shizuku started over ADB does not survive a power
   cycle, and this projector cold-boots from standby, so the plugin never depends on it.

**Run commands through** (plugin settings): *Auto* tries the kiosk process first (the stock
firmware's SELinux is permissive, so the vendor tool and the properties are reachable from an
app) and falls back to Shizuku; *Direct* and *Shizuku* force one. The status line says which
channel answered.

### Build and test

```sh
python3 tools/test.py     # parsing, entity publication, the exact command scripts, channel fallback
python3 tools/build.py    # dist/nexigo-aurora-<version>.zip (needs JDK 17+ and an Android SDK, ANDROID_HOME)
```

Releases are built by GitHub Actions from a `v<version>` tag, as Kiosk Satellite requires.

## Status

Verified on one Aurora Pro, firmware above. The plugin runs on the projector's Kiosk Satellite
(installed through Remote Admin's `installPlugin` API) alongside Device Performance, Network
Diagnostics, Network ADB and Package Management, and its Picture switch has been driven from
Home Assistant: the projector logs the serial
command, the flags follow, and the switch and Screen-off sensor confirm - through the direct
channel, no Shizuku. With the stay-on guards the projector stayed on the network through the
Apple TV sleeping (it used to stand by 19 s after). Inputs and picture modes were verified over
ADB before the plugin. The LED bar commands are decoded but not yet exercised. Everything else in
the HAL list is documented from the binaries, not tested.

## Licence

Apache-2.0. The vendor binaries and apps analysed here are not included; only what was learned from
them.
