# Changelog

## Unreleased

- Plugin 0.4.2: far fewer Home Assistant recorder rows (about 10.6k a day before, most of them
  wobble). Measured sensors are sent only on a real move or once they are old: CPU busy in whole
  percent on a 5-point move (5 min at most), fan tachometers on a 50 rpm move (30 min), fan
  speed on any change, temperatures in whole degrees on a 2 °C move (30 min), and laser hours in
  tenths of an hour (a row every 6 minutes of laser, not every minute).

- Plugin 0.2.1 (0.2.0's port setting was refused by the host): a loopback ADB channel. The stock firmware leaves adbd on port 5555 with no
  key, so the plugin talks to it from the kiosk process itself: in Auto it reads the log (the
  temperatures) behind the direct channel and carries everything when direct access is refused,
  and it starts Shizuku after a reboot for the plugins that want it. No Shizuku needed for the
  temperatures any more.

- Plugin 0.1.4: `am start --user 0` for the settings app and the input switch, so both work when
  Kiosk Satellite runs them from its own process (a remote key mapped to `settings` opened
  nothing before).

- Plugin 0.1.3: a `pictureToggle` command, one button that darkens a lit picture and lights a dark
  one, made for a remote key mapping.

- Plugin 0.1.2: a laser the counter shows lit also clears `cur.prj.screenOff`, so the Screen off
  sensor no longer stays on after the projector lit the picture itself.

- Reverse-engineering notes for the NexiGo Aurora Pro: the Appotronics projector-manager HAL and its
  serial "AT" light-engine protocol, the vendor apps and services, power states, the screen-off
  recipe that keeps the OS awake, inputs, picture modes, the front LED bar, properties and settings.
- Home Assistant scripts (Android Debug Bridge integration) for picture off/on, inputs, picture
  mode and apps.
- Kiosk Satellite plugin 0.1.0: Picture switch, Input and Picture mode selects, Screen off
  sensor, laser hours, temperatures (with Shizuku), and actions for the projector's settings app,
  picture and front LEDs. Direct execution first, Shizuku as the fallback.
