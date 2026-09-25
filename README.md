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

Kiosk Satellite runs on the projector's Android (it is an ordinary Android TV 9 device, `minSdk 24`
is fine) and appears in Home Assistant as an ESPHome device. The plugin in this repository will
add, on that device:

- **Picture** switch: the screen-off recipe above, with state read back from `isLightSourceOn`.
- **Input** select: HDMI 1-4.
- **Picture mode** select.
- **Projector settings** button: opens `com.xming.xmprojectorsettings` (keystone, focus, brightness
  mode, projection mode...) on the projector, so those can be reached from the couch without the
  remote.
- **Front LEDs** switch.
- Sensors: laser hours, the eight temperatures the light engine reports (red/green/blue laser,
  colour wheel, DMD, environment, XPR, power supply), fan speeds, current input.
- No fan control. The firmware's `appothermal` daemon runs the fans from those same temperatures;
  the plugin reads them and leaves the cooling of a triple-laser engine to the people who built it.

It uses the vendor's `projector-test` tool and system properties (through Shizuku or, if the
permissive SELinux allows it, directly), never root.

## Status

Verified on one Aurora Pro, firmware above. The screen-off recipe, inputs, picture modes and app
launching are in daily use from Home Assistant. The LED bar commands are decoded but not yet
exercised. Everything else in the HAL list is documented from the binaries, not tested.

## Licence

Apache-2.0. The vendor binaries and apps analysed here are not included; only what was learned from
them.
