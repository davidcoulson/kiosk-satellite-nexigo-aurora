# ADB cheat sheet

```sh
adb connect <projector-ip>:5555          # only while it is on; standby drops the network

# picture
adb shell "setprop cur.appo.light.enabled false; /vendor/bin/hw/projector-test setLightSourceOnOff false; setprop cur.prj.screenOff true"
adb shell "setprop cur.appo.light.enabled true;  /vendor/bin/hw/projector-test setLightSourceOnOff true;  setprop cur.prj.screenOff false"
adb shell "getprop cur.prj.screenOff; getprop cur.appo.light.enabled"

# inputs and modes
adb shell "am start -a android.intent.action.VIEW -d content://android.media.tv/passthrough/com.mediatek.tvinput%2F.hdmi.HDMIInputService%2FHW6"   # HDMI 2
adb shell "settings put global picture_mode 8"                     # Cinema Pro
adb shell "settings get global boot_source_id"

# apps
adb shell "am start -n com.xming.xmprojectorsettings/.ProjectorSettingActivity"   # projector settings (keystone, focus...)
adb shell "monkey -p com.plexapp.android -c android.intent.category.LEANBACK_LAUNCHER 1"
adb shell "input keyevent 127"                                     # pause

# the light engine, as the HAL talks to it
adb shell "logcat -d | grep -E 'AT\+|Laser usedTime'"             # AT traffic, temperatures every 30 s, laser minutes
adb shell "/vendor/bin/hw/projector-test"                          # function list
adb shell "/vendor/bin/hw/projector-test setAppoLeds 2 6"          # front LEDs: status OFF (untested)

# state
adb shell "dumpsys power | grep -E 'mWakefulness=|Display Power'"
adb shell "dumpsys hdmi_control | grep -E 'mPowerStatus|logical_address'"
adb shell "dumpsys tv_input | grep inputId"
adb shell "getprop | grep -E 'prj|appo'"
adb shell "settings list global | grep -E 'picture_mode|hdmi|source|power'"
```

Pull the vendor code for your own look (nothing here needs root):

```sh
adb pull /system/app/BackgroundService/BackgroundService.apk
adb pull /system/app/XMProjectorSettings/XMProjectorSettings.apk
adb pull /system/framework/com.appotronics.support.jar
adb pull /vendor/bin/hw/vendor.appotronics.projectormanager@1.0-service
adb pull /vendor/lib/vendor.appotronics.projectormanager@1.0.so
```

`androguard` decodes the dex files; `strings` on the binaries gives the AT names and HIDL symbols.
