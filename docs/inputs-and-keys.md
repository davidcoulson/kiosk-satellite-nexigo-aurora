# Inputs and keys

## HDMI inputs

MediaTek's TV input framework exposes the four HDMI ports as hardware inputs `HW5`-`HW8`
(`hdmi_port` 1-4; ids 14-17 are a second set of the same ports). The only registered
`TvInputInfo` is `com.mediatek.tvinput/.hdmi.HDMIInputService/HDMI100004`. Select a port by opening
its pass-through URI:

```sh
am start -a android.intent.action.VIEW -d content://android.media.tv/passthrough/com.mediatek.tvinput%2F.hdmi.HDMIInputService%2FHW5   # HDMI 1
# HW6 = HDMI 2, HW7 = HDMI 3, HW8 = HDMI 4
```

`Settings.Global boot_source_id` (5 = HDMI 1) is what comes up after boot; `cur.prj.currentSourceId`
tracks the current one. Ids 0, 1, 9, 10 are tuner/component-type hardware (type 2) the box also
declares. On this unit HDMI 1 carries eARC (`isEarcEnable`, `setAutoEarcEnable` in `DeviceManager`).

## The remote

Vendor key layout `Vendor_3697_Product_0001.kl` (BT remote; `_0002` is the same): the usual
Android TV set (`POWER`, `DPAD_*`, `DPAD_CENTER`, `HOME`, `MENU`, `BACK`, `VOLUME_*`,
`VOLUME_MUTE`, `MEDIA_*`, `CHANNEL_*`, `INFO`, `GUIDE`) plus `TV_POWER`, `TV_INPUT` (scan code 79),
`TV_ZOOM_MODE`, `WINDOW`, `STEM_1`/`STEM_PRIMARY`, `F2`/`F4`, `PROG_RED..BLUE`. Full file in
[raw/keylayout-vendor-3697.txt](raw/keylayout-vendor-3697.txt). The IR receiver
(`MStar Smart TV IR Receiver`) and the keypad expose `KEY_POWER`, `KEY_SLEEP`, `KEY_WAKEUP`,
`KEY_SUSPEND`, `KEY_SCREEN`, `KEY_TV`, `KEY_PC`... through the generic layout.

Every physical key also produces the vendor broadcast `com.appo.action.keydown` (int extra
`KeyCode`), which is what restores the picture after Screen off.

## Key events over ADB

`input keyevent N` works while Android is awake:

| Code | Key | Notes |
| --- | --- | --- |
| 26 | POWER | Toggles standby (network drops: only CEC/remote can wake it). |
| 85 / 126 / 127 / 86 | PLAY_PAUSE / PLAY / PAUSE / STOP | Media keys reach the foreground app (Plex, Plezy). |
| 24 / 25 / 164 | VOLUME_UP / DOWN / MUTE | |
| 89 / 90 | MEDIA_REWIND / FAST_FORWARD | |
| 3 / 4 / 82 | HOME / BACK / MENU | |
| 178 | TV_INPUT | Opens the source chooser. |
| 223 / 224 | SLEEP / WAKEUP | Android's screen sleep. Not the same as the projector's Screen off; use the recipe instead. |

`input keyevent` does **not** fire `com.appo.action.keydown` (that comes from the input HAL path), so
it will not wake a Screen off by itself; send the on recipe.
