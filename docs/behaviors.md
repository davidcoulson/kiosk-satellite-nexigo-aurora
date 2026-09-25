# Behaviours: power, screen off, sensors, LEDs

## Power states

| State | Laser | Fans | Front LEDs | Android | Network / ADB | How you get there | How you leave |
| --- | --- | --- | --- | --- | --- | --- | --- |
| On | on | on | off | awake | up | CEC wake, remote power, mains with power-on enabled | - |
| **Screen off** | off | off | off | **awake** | **up** | power menu "Screen off", or the recipe below | any remote key, or the on recipe |
| Standby | off | off | on | asleep | **down** | remote power, CEC standby, power menu, no-signal timer, sleep timer | CEC wake, remote power. Nothing over IP. |
| Off | off | off | off | off | down | power menu "Shutdown" (`enterSleepMode(0)` + Android shutdown) | mains |

Standby is the one that hurts automation: the SoC keeps enough alive for CEC and IR but the
network is gone, so Home Assistant's ADB entity goes unavailable and only HDMI-CEC from a source
(here an Apple TV) or the remote brings it back; boot to picture takes about 25 s.

## Screen off: picture off, projector on

What the power menu's "Screen off" does (`ScreenOffBehavior`), reproduced from a shell:

```sh
setprop cur.appo.light.enabled false
/vendor/bin/hw/projector-test setLightSourceOnOff false     # -> AT+LightSource=Off, ack AT+LightSource#OK
setprop cur.prj.screenOff true
```

and back:

```sh
setprop cur.appo.light.enabled true
/vendor/bin/hw/projector-test setLightSourceOnOff true      # -> AT+LightSource=On
setprop cur.prj.screenOff false
```

Observed with the flags: laser off, fans spin down within seconds, the front LED bar goes dark,
`dumpsys power` stays `mWakefulness=Awake`, ADB stays connected, no further `AT+LightSource`
traffic for as long as it was watched (minutes), and `On` restores the picture in about a second.
Any remote key also restores it (the `com.appo.action.keydown` broadcast handler in
`ShutdownPageService`), which is the behaviour you want in a room.

**Without the flags** the same `setLightSourceOnOff false` is not stable. Two different outcomes
were seen:

1. The laser came back on by itself ~5 s later - two `AT+LightSource=On` from the HAL's binder
   threads, i.e. a client asked for it. The body sensor's handler (`BodySensorService`) turns the
   light back on when it finds it off while not in eye-protect, and someone had just walked into
   the room.
2. On a second attempt the projector went to **standby** ~30 s later, taking the Apple TV with it
   over CEC (`hdmi_control_auto_device_off_enabled=1`).

So: always the three-step recipe, in that order.

`isLightSourceOn()` (HAL) is the truth for state; `getprop cur.prj.screenOff` says whether the
dark is deliberate.

## The eye-protection body sensor

An IR body sensor in front of the lens raises a kernel `UEvent` (`SWITCH_STATE`) that
`BodySensorService` watches. With `cur.prj.inEyeProtect` false, the light on and
`/keybin/usermode.bin` present (a factory/user-mode marker), it shows a warning and, on a timer, cuts
the light, then restores it (`setLightSourceEnabled(true)`) when the way is clear or the dialog is
dismissed. Toggle it through the projector settings (`setIrBodyDetectOnOff` in the HAL;
`DeviceManager.setBodyDetectEnabled`). It is also why an unflagged light-off does not stick.

## Sleep timer

`SleepmodeService`: `persist.prj.sleepMode` (0 off, half hour, one hour), `persist.prj.stayawake`
(a debug override that keeps it awake), a countdown dialog before it acts, and `cur.prj.ScreenOffFrom`
= `SleepMode` when it turned the light off, so the key handler knows to restore it.

## No-signal shutdown

Seen in practice: with the picture off and the Apple TV left to fall asleep (its own idle timer),
the projector went to standby about fifteen minutes after the source dropped, and took the Apple
TV's CEC state with it. Screen off does not suspend this timer; keep the source awake, or accept
that a blanked projector will eventually stand by.

`Settings.Global no_signal_auto_power_off` (default index 4) is the "turn off after N minutes
without signal" setting; `DeviceManager.isSignalLoss()` combines MediaTek's `MtkTvBroadcast.isSignalLoss()`
with "is any input selected". With a source that keeps its HDMI output alive (Apple TV on, screen
saver) this never fires; with a source that sleeps, it will.

## CEC

Android's HDMI-CEC service is on (`hdmi_control_enabled=1`), with auto wake-up
(`hdmi_control_auto_wakeup_enabled=1`) and auto device off (`hdmi_control_auto_device_off_enabled=1`),
plus the vendor's own switch `appo_hdmi_control_enabled` and an eMMC env `appo_wakeup_cec` written
by `DeviceManager.setCecEnabled`. `persist.appo.ignore.cec.standby` (false here) lets it ignore
standby from sources; `persist.appo.wakeup.ps4=1` is a special case for consoles. The projector is
CEC logical address 0 (TV); see [raw/hdmi_control.txt](raw/hdmi_control.txt).

## Boot source and power-on

`boot_source_id` (global; 5 = HDMI 1) is what it shows after boot. `DeviceManager.setPowerOnEnabled`
sets MediaTek's factory power mode: on when mains returns, or stay in standby. `isPowerOnRingtoneEnabled`
is the start-up sound.

## The front LED bar

Driven by the MCU/HAL, not by Android's light HAL. `ProjectorManager.setAppoLeds(mode, value)`
with `APPO_LED_MODE` {0 `RESET`, 1 `UPDATA`, 2 `STATUS`, 3 `MAX`} and, for `STATUS`,
`APPO_LED_STATUS` {0 `PWON`, 1 `PWOFF`, 2 `STANDBY`, 3 `UPDATA`, 4 `BT`, 5 `LOOP`, 6 `OFF`}. The
HAL logs `Lap appo led str on/off` and reads `/sys/class/appo_led_pwm_pm/appo_led_pwm_pm/led_pwm`
(0 while the picture is off). From the shell: `projector-test setAppoLeds 2 6` (off),
`2 0` (power-on pattern), `2 2` (standby pattern). A global enable lives in the unify key
`appo_led_str` (1/0) and eMMC env `appo_led_off` (`DeviceManager.setLedEnabled`); the projector
settings expose it as the LED indicator switch. The bar is on in standby and off while the picture
is on or screen-off, as observed; the `setAppoLeds` calls above are decoded but not yet tested.

## Blank screen (settings app)

Different thing: `BlankScreenItem` opens `com.appo.engineering/.ui.NativeColorImageActivity`, a
solid-colour full-screen activity. The laser stays on; it is a test pattern, not a power state.
