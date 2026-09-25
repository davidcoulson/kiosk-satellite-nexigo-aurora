# Home Assistant

## Today: the Android Debug Bridge integration

Add the projector with the [Android Debug Bridge](https://www.home-assistant.io/integrations/androidtv/)
integration (device class Android TV, its IP, port 5555). It gives `media_player.<name>` with
`androidtv.adb_command` for anything below, `remote.<name>` for key events, and the app state.

What it cannot do is power on: standby drops the network. Wake it over HDMI-CEC from a source
(`media_player.turn_on` on an Apple TV, for example) and give the integration ~30 s to reconnect
(an automation that reloads its config entry when the source comes on helps).

Scripts, as used in [theater-panel](https://github.com/davidcoulson/theater-panel/blob/main/ha/theater.yaml):

```yaml
script:
  projector_picture_off:
    sequence:
      - action: androidtv.adb_command
        target: { entity_id: media_player.projector }
        data: { command: "setprop cur.appo.light.enabled false; /vendor/bin/hw/projector-test setLightSourceOnOff false; setprop cur.prj.screenOff true" }
  projector_picture_on:
    sequence:
      - action: androidtv.adb_command
        target: { entity_id: media_player.projector }
        data: { command: "setprop cur.appo.light.enabled true; /vendor/bin/hw/projector-test setLightSourceOnOff true; setprop cur.prj.screenOff false" }
  projector_input:
    fields: { hw: { example: 6 } }   # 5-8 = HDMI 1-4
    sequence:
      - action: androidtv.adb_command
        target: { entity_id: media_player.projector }
        data: { command: "am start -a android.intent.action.VIEW -d content://android.media.tv/passthrough/com.mediatek.tvinput%2F.hdmi.HDMIInputService%2FHW{{ hw }}" }
  projector_picture_mode:
    fields: { value: { example: 8 } } # 2 Cinema Home, 8 Cinema Pro, 9 Standard, 3 Brightest, 10 Game, 0 Custom
    sequence:
      - action: androidtv.adb_command
        target: { entity_id: media_player.projector }
        data: { command: "settings put global picture_mode {{ value | int }}" }
  projector_settings:
    sequence:
      - action: androidtv.adb_command
        target: { entity_id: media_player.projector }
        data: { command: "am start -n com.xming.xmprojectorsettings/.ProjectorSettingActivity" }
  projector_app:
    fields: { package: { example: com.plexapp.android } }
    sequence:
      - action: androidtv.adb_command
        target: { entity_id: media_player.projector }
        data: { command: "monkey -p {{ package }} -c android.intent.category.LEANBACK_LAUNCHER 1" }
```

Put `projector_picture_on` at the top of any "start the film" flow: a blanked projector comes back
in a second, and the command is harmless when it is already on.

Limits of this route: one ADB session at a time is shared with anything else using ADB, HA's
command has a ~9 s read timeout, and there is no state feedback (whether the picture is on) without
parsing logcat.

## With the plugin

Kiosk Satellite on the projector appears in HA as an ESPHome device; the plugin adds its entities
to it (object ids `plugin_nexigo_aurora____<type>_<key>`, shown under the device's name):

```yaml
script:
  projector_picture_off:
    sequence:
      - action: switch.turn_off
        target: { entity_id: switch.<device>_nexigo_aurora_pro_picture }
  projector_input:
    fields: { input: { example: "HDMI 2" } }
    sequence:
      - action: select.select_option
        target: { entity_id: select.<device>_nexigo_aurora_pro_input }
        data: { option: "{{ input }}" }

automation:
  - alias: Projector picture follows the Apple TV
    mode: restart
    triggers:
      - trigger: state
        entity_id: media_player.apple_tv
        to: "off"
        for: "00:00:30"
        id: asleep
      - trigger: state
        entity_id: media_player.apple_tv
        from: "off"
        id: awake
    actions:
      - action: "switch.turn_{{ 'off' if trigger.id == 'asleep' else 'on' }}"
        target: { entity_id: switch.<device>_nexigo_aurora_pro_picture }
```

With the plugin's stay-on guards the projector never stands by on its own, so the picture simply
follows the source. The ADB scripts above remain the reference for what each entity does, and
ADB is still the way to launch an app or send a key until the plugin grows those.
