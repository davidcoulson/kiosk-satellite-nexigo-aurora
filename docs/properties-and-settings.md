# Properties, settings and persisted keys

Full sanitised dumps: [raw/getprop.txt](raw/getprop.txt), [raw/settings.txt](raw/settings.txt).

## System properties the vendor code uses

Live state (`cur.*`, not persisted):

| Property | Meaning |
| --- | --- |
| `cur.appo.light.enabled` | Set by `DlpManager.setLightSourceEnabled` before it calls the HAL. Mirrors the intended light state. |
| `cur.prj.screenOff` | `true` while the power menu's Screen off (or the recipe) is in effect; cleared by the key handler. |
| `cur.prj.ScreenOffFrom` | `SleepMode` when the sleep timer turned the light off. |
| `cur.prj.inEyeProtect` | The body sensor has the light off. |
| `cur.prj.inShutdownPage` | The power menu is open. |
| `cur.prj.currentSourceId` | Current input id. |
| `cur.prj.bri`, `cur.prj.gamma`, `cur.prj.dynamic`, `cur.prj.contrastEnhance`, `cur.prj.autofocus`, `cur.prj.isC`, `cur.prj.enableFunction`, `cur.prj.usb` | Current picture/feature state as the settings app tracks it. |

Persisted (`persist.*`):

| Property | Meaning |
| --- | --- |
| `persist.prj.sleepMode` | Sleep timer mode (0 off). Shipped as 4 here and stood the projector by after ~2 h; the plugin keeps it 0. |
| `persist.prj.stayawake` | Debug: never sleep. |
| `persist.prj.laserType` (3), `persist.prj.screenType` (1), `persist.prj.initScreenType` | Hardware variant. |
| `persist.prj.power_off_vol` | Volume restored at power off. |
| `persist.prj.keystoneType` (4), `persist.prj.keystonestep`, `persist.prj.warpStep`, `persist.prj.warpFix`, `persist.prj.shiftX/Y`, `persist.prj.zoom` | Geometry. |
| `persist.prj.xpr_enabled`, `persist.prj.vrr_enable`, `persist.prj.sound_surround`, `persist.prj.tempLogic`, `persist.prj.fan_laser`, `persist.prj.withoutHDMI`, `persist.prj.withoutAV`, `persist.prj.typec`, `persist.prj.salesarea`, `persist.prj.version`, `persist.prj.screenResolution`, `persist.prj.settingsKeyFunc` | Feature flags and variant info. |
| `persist.appo.ignore.cec.standby` (false) | Ignore CEC standby requests. |
| `persist.appo.wakeup.ps4` (1) | Console wake special case. |
| `persist.appo.dolby.lastpicmode` (6) | Last Dolby Vision picture mode. |
| `persist.appo.need.delay` | Boot delay. |
| `persist.Zeasn.devicetype` | Zeasn platform id. |

Boot-time flags: `sys.appo.finish.boot`, `sys.appo.enter.launcher`, `sys.appo.hdmihpd.onoff.hdmi1`,
`vendor.appo.video.width/height`; services `init.svc.appothermal`,
`init.svc.appotronics-projectormanager-1-0`, `init.svc.mstar-light-hal-2-0`.

## `Settings.Global` keys

| Key | Meaning |
| --- | --- |
| `picture_mode` | 2 Cinema Home, 8 Cinema Pro, 9 Standard, 3 Brightest, 10 Game, 0 Custom (6 seen on this unit with a Dolby Vision source: `picture_mode_dolby`). Writing it applies the mode; `ContentObserverService` watches it. |
| `boot_source_id` | Input shown after boot (5 = HDMI 1). |
| `no_signal_auto_power_off` | No-signal shutdown timer (index, default 4). |
| `power_picture_off` | MediaTek "picture off" allowed (1). |
| `power_switch_off_timer` | Off timer. |
| `hdmi_control_enabled`, `hdmi_control_auto_wakeup_enabled`, `hdmi_control_auto_device_off_enabled`, `appo_hdmi_control_enabled` | CEC. |
| `hdmi_arc_control_enabled`, `hdmi_system_audio_control_enabled` | ARC/system audio (off). |
| `tv_input_hdmi_edid_version` | EDID version (3). |
| `picture_*_hdr_hdmi_N`, `picture_*_sdr_hdmi_N`, `g_video__vid_*`, `tv_picture_*` | Per-input, per-HDR-type picture parameters the MediaTek PQ code stores (backlight, gamma, colour temperature, sharpness, DNR, local contrast, luma). |
| `screen_off_timeout` (system) | 2147483647: Android's own screen timeout is disabled; the projector never dims through Android. |
| `sleep_timeout` (secure) | -1. |
| `screensaver_*` | Android's daydream (the clock) is enabled. |

## Persisted outside Android

`DeviceManager.setEmmcEnvVar` / `getEmmcEnvVar` write U-Boot-style environment variables
(`appo_led_off`, `appo_wakeup_cec`, boot args) and `readUnifyKey` / `writeUnifyKey` a key store
(`appo_led_str`). The HAL keeps its own SQLite "PlatformData" (`getPlatformProperty`,
`laser_used_time_wdt`). These survive factory resets of the Android side.
