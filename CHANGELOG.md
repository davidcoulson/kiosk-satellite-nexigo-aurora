# Changelog

## Unreleased

- Plugin 0.4.6: a light change whose flags or hold note did not take keeps them owed and retries
  them before every read, and while they are owed the projector's stale note is not read over
  the plugin's own record: a dark for silence whose "silence" note failed still comes back with
  the signal.

- Plugin 0.4.5: fixes from Codex's review of 0.4.4. The picture script undoes only a refused HAL
  call, back to the light flag's own previous value, and marks when the HAL changed the light, so
  a later failing flag still counts as a change (settled, flags retried). A restart correction
  that is refused stays owed until it works. The no-signal dark needs the TV app positively in
  front and an input read just now, and only fresh full signal reads (on the input showing
  throughout) drive it; a CEC Standby needs the live input too. stop() returns within the host's
  deadline and its session's channels refuse anything after it; ADB's deadline also ends blocked
  writes, checksums follow the negotiated version and the stream state is enforced; Shizuku
  commands are one at a time, 250 ms apart; a timed-out direct script's whole process group is
  ended (setsid); helper runs with nothing usable count as failures.

- Plugin 0.4.4: fixes from a full review (Codex, 2026-10-12). Picture: the script is all or
  nothing (a refused HAL fails it and puts the light flag back) and the plugin records a change
  only once it worked; every picture change and signal reading runs on one thread, so a manual
  Off and an automatic restore cannot interleave; the no-signal dark is re-checked against the
  app in front and the live input before it happens, its clock restarts on a manual change or a
  new input, a refused restore is retried, and the reason ("silence" vs asked for) is kept in the
  projector's hold note across restarts. A CEC Standby checks the live input first. ADB has one
  deadline for the whole exchange (a trickling peer used to keep it alive forever) and checks
  packets; stop() cuts off open shells and ADB exchanges and waits for both threads; a direct
  script that times out takes its children with it and never blocks on a full pipe. The vendor
  helper is trusted for its hash, staged under a name of its own, fits Shizuku's argument and
  timeout limits, and is re-checked after repeated failures. Also: a missing channel is retried
  with backoff, Picture mode falls back through the shell user, a refused publish is retried,
  partial helper output no longer throws, fans keep their numbers, a vanished temperature turns
  unknown, minAndroidSdk 26.

- Plugin 0.4.3: Dark after no signal (default 15 min). Stay on keeps the projector out of standby,
  which also stops its own no-signal shutdown, so a source that sleeps without HDMI-CEC (the
  Unraid VM) left the laser lighting a No signal screen all day. After the set time with no
  signal on the HDMI input in front the picture goes dark (Android and the network stay up), and
  it comes back when the signal does, unless the picture was switched since. Each is noted in
  Last correction.

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
