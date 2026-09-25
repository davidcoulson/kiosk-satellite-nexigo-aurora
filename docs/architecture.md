# Architecture

## Hardware and OS

| | |
| --- | --- |
| Model string | `AURORA PRO` (`ro.product.model`), brand/manufacturer `MediaTek` |
| Board | MediaTek `m7642` TV platform (the MStar lineage: `MStar Smart TV IR Receiver`, `mstar.hardware.light@2.0`) |
| OS | Android 9 (API 28), TV build, `m7642-userdebug 9 PPR2.180905.006.A1`, `ro.debuggable=1`, SELinux **permissive** |
| Light engine | Appotronics ALPD, driven by a separate MCU over a UART (`/dev/ttyS1`) with a text "AT" protocol |
| Firmware author | build host `appo-server`; vendor packages `com.appo.*`, `com.xming.*`, `com.appotronics.*` |
| Network | Ethernet/Wi-Fi while on. **Standby turns the network off**; there is no Wake-on-LAN. |
| ADB | TCP port 5555 is open on the stock firmware; no key approval was needed |

## The layers

```
Apps (com.xming.xmprojectorsettings, com.appo.backgroundservice, ...)
   |  Java: com.appo.tv.{DlpManager, DeviceManager, TvContext}   (bundled in each app)
   |        com.appotronics.support.ProjectorManager             (/system/framework/com.appotronics.support.jar)
   v
vendor.appotronics.projectormanager@1.0::IProjectorManager        (HIDL, /vendor/bin/hw/vendor.appotronics.projectormanager@1.0-service)
   |  "AtSerialCmder": AT+<Command>=<args> over /dev/ttyS1, ack "AT+<Command>#OK"
   v
Light-engine MCU: laser on/off, temperatures (NTCs), fans, colour wheel, DLP warping, LEDs
```

Beside it, the MediaTek TV stack (`com.mediatek.tvinput`, `com.mediatek.wwtv.tvcenter`, the `MtkTv*`
Java classes, `MI3` peripheral HAL) owns HDMI inputs, picture processing, CEC and audio, as on any
MediaTek TV. The Appotronics layer only owns what is projector-specific.

### The projector-manager HAL

`vendor.appotronics.projectormanager@1.0::IProjectorManager/default` runs as pid of
`vendor.appotronics.projectormanager@1.0-service`. Its clients at runtime (from `lshal`) are
`com.xming.userdata`, `com.appo.backgroundservice`, `appothermal` (the thermal daemon), the TV input
arbitrator and system_server. The full method list is in [hal-api.md](hal-api.md); it is the
one place that talks to the MCU.

Its log tag is empty (`D         :`), which makes it easy to find in logcat:

```
AtSerialCmder WriteAtPacket pckData : AT+LightSource=Off
AtSerialCmder WriteAtCmd Ack : AT+LightSource#OK
AtSerialCmder ReadAtCmd pckData : AT+Temperature#NtcRedLaser1:28,NtcGreenLaser1:25,NtcBlueLaser1:34,NtcCw1:33,NtcDmd1:37,NtcEnv1:23
BackgroundTask update Laser usedTime : 25 mins
```

Temperatures are polled every 30 s; the laser-hours counter (`laser_used_time_wdt` in the HAL's own
SQLite "PlatformData" store) is updated every minute the light is on.

### The Java API

`/system/framework/com.appotronics.support.jar` wraps the HAL as
`com.appotronics.support.ProjectorManager` (`getInstance()`, one method per HIDL call, see
[hal-api.md](hal-api.md)). The vendor apps do not use it directly; each bundles the
`com.appo.tv` interfaces (`DlpManager` for the light engine, `DeviceManager` for TV-side things,
`TvContext.getInstance()` to get either) with a default implementation
(`com.appo.internal.DlpManagerDefaultImpl`, `DeviceManagerDefaultImpl`) that calls `ProjectorManager`
and keeps a few system properties in step. Those properties are what the rest of the system reads,
so mimicking an app means setting them too ([behaviors.md](behaviors.md)).

### Command-line access

`/vendor/bin/hw/projector-test` (`-rwxr-xr-x root shell`) is a test client for the HAL that any
shell can run. It covers the calls that matter for control (light source, LEDs, HDMI pass-through,
low-latency, panel, GPIO, SoC registers). `setprop`/`getprop` and `settings` cover the flags and
`Settings.Global` keys. Together they reach everything the plugin and the Home Assistant scripts
need without root. SELinux being permissive is what makes the HAL reachable from the shell domain;
on an enforcing build the same calls would need a privileged process.

## The apps

Summarised here, detailed in [apps-and-services.md](apps-and-services.md):

- `com.appo.backgroundservice`: the always-running vendor service. Power menu (long-press power:
  standby / screen off / reboot / shutdown), sleep timer, the IR body sensor "eye protection",
  volume, focus adjust, USB-stick upgrades, boot init.
- `com.xming.xmprojectorsettings`: the projector's own settings UI (the one under the gear on the
  launcher, not Android's `com.android.tv.settings`): keystone, focus, brightness mode, image mode,
  projection mode, digital zoom, MEMC, HDMI mode, boot source, screen saver, XPR, blank screen...
- `com.xming.xmsettings`: general settings (network, Bluetooth, language, time, input method).
- `com.appo.engineering`: engineering menus and test images; its `NativeColorImageActivity` is what
  "Blank screen" opens (a solid colour, light still on).
- `com.appo.factorytest`, `mediatek.factorymenu.ui`: factory menus.
- `com.appo.fotaupgrade`, `com.appo.loghelper`, `com.appo.bt.autoconnect`, `com.appo.miracast`,
  `com.appo.wizard`, `com.appo.gallery`: OTA, logs, the remote's BT pairing, Miracast, first-run
  wizard, gallery.
- `com.xming.xmosd`, `com.xming.userdata`, `com.xming.xmexplorer`, `com.xming.VideoPlayer`,
  `com.xming.MusicPlayer`: OSD overlays, per-user data, file browser and players.
- `com.ecloud.*` / `com.allshare.chromcast.castapp`: the EShare/AirPlay/Chromecast/DLNA receivers.
- User-installed: Plex, Plezy, SmartTube, Moonlight (`com.limelight`), Projectivy launcher
  (`com.spocky.projengmenu`), Kiosk Satellite (`me.jxl.kiosk_satellite`).
