# Apps and services

The stock firmware's package list is in [raw/packages.txt](raw/packages.txt); the services running
on a normal boot in [raw/running-services.txt](raw/running-services.txt). What follows is what the
vendor parts do.

## `com.appo.backgroundservice` - the vendor's daemon

Runs from boot (`BootReceiver` -> `BootInitService`). Its services:

| Service | Purpose |
| --- | --- |
| `shutdownPage.ShutdownPageService` | The long-press-power menu. Builds `Behavior`s: `StandbyBehavior`, `ScreenOffBehavior`, `RebootBehavior`, `ShutdownBehavior`, plus a `StandbyTimeSeletor`/`StandbyTimeController` for a delayed standby. Sets `cur.prj.inShutdownPage` while open. |
| `sleepmode.SleepmodeService` | The sleep timer. `persist.prj.sleepMode` (0 off, `SLEEP_MODE_HALF_HOUR`, `SLEEP_MODE_ONE_HOUR`), `persist.prj.stayawake`, a countdown dialog (`sleep_mode_count_down`), pauses on signal loss checks. |
| `BodySensorService` | The IR body sensor (eye protection). Listens to a kernel `UEvent` (`SWITCH_STATE`), and when someone is in front of the lens with the light on, shows a warning dialog and can turn the light off; `cur.prj.inEyeProtect` marks that state. Restores the light afterwards (`setLightSourceEnabled(true)`). |
| `ContentObserverService` | Watches `Settings` changes (picture mode etc.) and applies them. |
| `CustomParamService`, `WB11ProcessorService` | Vendor customisation / white-balance processing. |
| `FocusAdjustService` | Focus motor UI. |
| `volume.VolumeChangeService` | Volume OSD. |
| `UdiskUpgradeService` | Firmware update from a USB stick. |
| `ShutdownService` | Shutdown handling. |

### The power-menu behaviours

Decoded from `ScreenOffBehavior.operate()` and friends:

```
ScreenOff : if (dlp.isLightSourceEnabled()) dlp.setLightSourceEnabled(false);
            SystemProperties.set("cur.prj.screenOff", "true");
Standby   : (logs only here; the standby itself is the platform's)
Shutdown  : deviceManager.shutdown(ctx)  ->  ProjectorManager.enterSleepMode(0)
                                             + android REQUEST_SHUTDOWN (KEY_CONFIRM=false)
Reboot    : PowerManager.reboot
```

and the way back, `ShutdownPageReceiver.onReceive` on broadcast **`com.appo.action.keydown`**
(sent for every remote key, with an int extra `KeyCode`):

```
if (!dlp.isLightSourceEnabled()) { dlp.setLightSourceEnabled(true); SystemProperties.set("cur.prj.screenOff","false"); }
if (KeyCode == 3 /* HOME */) dismissDialog();
```

`SleepmodeService.SleepModeReceiver` does the same for keys when `cur.prj.ScreenOffFrom` is
`"SleepMode"`.

`DlpManagerDefaultImpl.setLightSourceEnabled(b)` is `SystemProperties.set("cur.appo.light.enabled",
b)` followed by `ProjectorManager.setLightSourceOnOff(b)`; `isLightSourceEnabled()` is
`ProjectorManager.isLightSourceOn()`.

## `com.xming.xmprojectorsettings` - the projector settings app

`ProjectorSettingActivity` (launchable: `am start -n com.xming.xmprojectorsettings/.ProjectorSettingActivity`).
Its items, from the class names, are the whole projector-side settings surface:

`AutoCeilingItem`, `AutoFocusItem`, `AutoKeystoneItem`, `BlankScreenItem`, `BootAutoFocusItem`,
`BootSourceItem`, `BrightnessModeItem`, `DigitScale_HorItem` / `_VerItem` / `_PropItem` (digital
zoom), `Dlp24PItem`, `DynamicBlackItem`, `DynamicContrastItem`, `EdgeBlendItem`, `HdmiMode`,
`HighAltitudeItem`, `ImageModeItem`, `LightSourceBrightnessItem`, `MalFormationCorrectItem` (warp),
`ManualFocusItem`, `ManualKeyStoneItem`, `MemcItem`, `ProjectionModeItem`, `ProjectorScreenItem`,
`RedGainItem`, `ScreenSaveItem`, `ScreenScaleItem`, `TestPictureItem`, `XprItem`.

`BlankScreenItem` starts `com.appo.engineering/.ui.NativeColorImageActivity`: a solid colour over
the picture, light source still on. Not the same as the power menu's Screen off.

It also has a `TypeCSwitchReceiver` (USB-C input switching) and reads `cur.prj.*` for current
state (`cur.prj.bri`, `cur.prj.gamma`, `cur.prj.dynamic`, `cur.prj.contrastEnhance`,
`cur.prj.autofocus`, `cur.prj.currentSourceId`, `cur.prj.isC`, `cur.prj.enableFunction`).

## `com.xming.xmsettings` - general settings

`MainActivity`, `CommonSettingsActivity` (has the sleep-mode entry), network, Wi-Fi, AP, P2P,
Bluetooth, language, input method, time, storage.

## Other vendor packages

| Package | What |
| --- | --- |
| `com.appo.engineering` | Engineering menu; `NativeColorImageActivity` test images; `data.UsedTimeService` (usage hours). |
| `com.appo.factorytest`, `mediatek.factorymenu.ui`, `com.appo.audiomodetest`, `com.mstar.android.media.tests` | Factory/test. |
| `com.appo.fotaupgrade` | OTA client. |
| `com.appo.loghelper` | Log collection. |
| `com.appo.bt.autoconnect` | Pairs the BT remote (`BTCAutoPairService`); listens for `appo.test.active.bluetooth` / `disactive` / `com.appo.action.BLE_CLOSE`. |
| `com.appo.miracast`, `com.ecloud.eairplay`, `com.ecloud.emedia`, `com.ecloud.eshare.server`, `com.allshare.chromcast.castapp` | Miracast, AirPlay, DLNA, EShare, Chromecast receivers (always-on services: `AirPlayService`, `ChromecastService`, `DlnaServer`, `CifsServer`...). |
| `com.appo.wizard`, `com.mediatek.wwtv.setupwizard` | First-run. |
| `com.appo.gallery`, `com.xming.VideoPlayer`, `com.xming.MusicPlayer`, `com.xming.xmexplorer`, `cn.wps.moffice_i18n_TV` | Local media, files, office viewer. |
| `com.xming.xmosd` | On-screen overlays (volume, source...). |
| `com.xming.userdata` | Per-user settings; a HAL client. |
| `appo.com.tvsource` | Input-source chooser. |
| `com.zeasn.whale.saas`, `com.zeasn.audiodemo` | Zeasn "Whale" TV platform services (`persist.Zeasn.devicetype=AppoGlobalFilter`). |
| `com.appotronics.demo`, `fusion.android.tv.demo` | Demo content. |
| `com.mediatek.tvinput`, `com.mediatek.tvinputservice.arbitratorservice`, `com.mediatek.wwtv.tvcenter`, `com.mediatek.wwtv.mediaplayer`, `com.mediatek.AIPQDisplay`, `com.mediatek.hotkey.dispatcher`, `com.mediatek.network`, `com.mediatek.androidbox`, `com.mediatek.TimeMeasurementAgent` | The MediaTek TV stack: HDMI/tuner/component inputs, PQ, hot keys, network monitor. |
| `com.mtk.bluetooth`, `com.mtk.bluetooth.dualmode`, `com.airoha.*` | Bluetooth stack and the Airoha BT remote. |

## Native services

| Process | Role |
| --- | --- |
| `vendor.appotronics.projectormanager@1.0-service` | The HAL ([hal-api.md](hal-api.md)). |
| `appothermal` | Thermal management (`init.svc.appothermal`), a HAL client. |
| `mstar.hardware.light@2.0-service` | `android.hardware.light@2.0` + `mstar.hardware.light@2.0::IMstarLight`: the platform light HAL. |
| `/vendor/bin/hw/projector-test` | Test client for the HAL. |

Sysfs the HAL knows about: `/sys/class/appo_led_pwm_pm/appo_led_pwm_pm/{led_pwm,led_duty,led_period}`
(the front LED PWM, root-writable only) and `/sys/class/leds/lap-green/brightness` (absent on this
unit). `/sys/class/backlight/backlight` exists but is a 0/1 stub (`max_brightness=1`).

## What was switched off on this unit

Reversible with `pm enable <package>`; none of it needs root.

| Package | Why |
| --- | --- |
| `com.zeasn.whale.saas` | Telemetry: posts the MAC, model and build fingerprint to `log.saas.zeasn.tv`. |
| `com.ecloud.eshare.server`, `com.ecloud.eairplay`, `com.ecloud.emedia`, `com.allshare.chromcast.castapp`, `com.appo.miracast` | The EShare/AppoFly, AirPlay, DLNA, Chromecast and Miracast receivers: five always-on services with open LAN ports (1106, 7100, 8000, 8008/8009, 8082, 8121), none of them used when an Apple TV is the source. |
| `com.appo.fotaupgrade` | The OTA client. Everything in this repository depends on this exact firmware (permissive SELinux, `projector-test`, the property names); updates are now a deliberate act. |
| `com.appo.loghelper`, `com.mediatek.TimeMeasurementAgent` | An always-running log collector (no upload endpoint found; USB/local) and MediaTek's boot-time profiler. |
| `cn.wps.moffice_i18n_TV`, `com.appotronics.demo`, `fusion.android.tv.demo`, `com.zeasn.audiodemo` | WPS Office and three demo apps: idle bloat. |

Also `settings put secure screensaver_enabled 0`: Android's clock daydream is one more reason for
the laser to be on. Afterwards only ADB (5555) and one Android system port listen on all
interfaces.
