# The projector-manager HAL and its APIs

Everything projector-specific goes through one HIDL service,
`vendor.appotronics.projectormanager@1.0::IProjectorManager`. This page lists what it offers at
three levels: the HIDL methods (from the `.so` symbols), the Java wrapper apps use, and the
command-line tool.

## `projector-test` (command line, no root)

`/vendor/bin/hw/projector-test <fun> [args]`. Run without arguments for the list. Parameters are
plain strings; the on/off functions take `true`/`false`, the numeric ones decimal integers.

| Function | Arguments | Notes |
| --- | --- | --- |
| `setLightSourceOnOff` | `true`/`false` | The laser. Logs `AT+LightSource=On/Off`. **Set the flags too**, see [behaviors.md](behaviors.md#screen-off-picture-off-projector-on). |
| `setAppoLeds` | `mode value` | Front LED bar: `mode` 0 reset, 1 update, 2 status; `value` an `APPO_LED_STATUS` (0 PWON, 1 PWOFF, 2 STANDBY, 3 UPDATA, 4 BT, 5 LOOP, 6 OFF). |
| `setPassThroughOnOff` | `true`/`false` | HDMI pass-through. |
| `setLowLatencyModeOnOff` | `true`/`false` | Game/low-latency mode. |
| `setHighFreqMode` | int | |
| `setMutexMode` | int | |
| `powerDown` | string | Asks the MCU to power down. Not tested. |
| `setPanel`, `setDLP_LookMode`, `set3DMode` | ints | DLP panel / look / 3D. |
| `setAudioMode`, `setHsg`, `setSegmentRGBY`, `setCurrentRGBY`, `setKeyStoneTable`, `setDBEnable`, `setDBScope` | ... | Colour, laser current, keystone, dynamic black. Calibration territory. |
| `setUsbFunc`, `setUsbSel0Ctrl` | int | USB routing. |
| `setPlatformProperty`, `getPlatformProperty` | key [value] | The HAL's own key/value store (SQLite). `used_time` is the lifetime light-source minutes; `laser_used_time_wdt` the minutes since they were last folded in. |
| `getVersion`, `GetHdmiColorDepth`, `getSegmentRGBY`, `readSocReg`, `writeSocReg` | | Diagnostics. |

The tool has no getters for the light source, temperatures or hours; those come from the Java API
(or by reading the HAL's logcat lines, which is what the plugin does for temperatures).

## `com.appotronics.support.ProjectorManager` (Java, `/system/framework/com.appotronics.support.jar`)

`ProjectorManager.getInstance()`; one method per HIDL call. Signatures as decoded from the dex:

```
int      InvokeEvent(int, int, int, int)
int      enterSleepMode(int mode)              // used by DeviceManager.shutdown(): enterSleepMode(0) then REQUEST_SHUTDOWN
ArrayList getAllTemperatures()                  // NtcRedLaser1, NtcGreenLaser1, NtcBlueLaser1, NtcCw1 (colour wheel), NtcDmd1, NtcEnv1, NtcXpr1, NtcPowerSupply
int      getAudioMode(int)
int      getBrightnessMode()                   / int setBrightnessMode(int)
int      getColorWheelDelay()                  / int setColorWheelDelay(int)
int      getColorWheelSpeed()
int      getCurrentGammaIndex()                / int getGammaIndex(int,int) / int setGammaIndex(int,int,int,boolean)
ArrayList getCurrentRGBY()                     / int setCurrentRGBY(int,int,int,int)      // laser drive currents
String   getEeprom(String)                     / int setEeprom(String,String)
int      getEventSwitchStatus()
int      getFanDutyCycle(int fan)              / int setFanDutyCycle(int fan, int duty)
int      getFanSpeed(int fan)
int      getHdmiColorDepth()
int      getHdrAvgFALL() / getHdrMaxCLL() / getMaxLuminance() / getMinLuminance()
int      getHighFreqMode()                     / int setHighFreqMode(int)
ArrayList getHsgData() / getHsgData(int,int)   / setHsgData(...), setHsgIndex(int), resetHsg(int,int)   // hue/saturation/gain tables
int      getInstallationMode()                 / int setInstallationMode(int)             // front/rear, table/ceiling
long     getLightSourceTime()                  / int setLightSourceTime(long)             // laser hours (minutes)
int      getLookIndex()                        / int setLookIndex(int) / setDLP_LookMode(int)
String   getPlatformProperty(String)           / int setPlatformProperty(String,String)
ArrayList getSavedManualWarpTable(int,int)     / saveManualWarpTable(...), setManualWarpTable(...), setManualWarpPoint(int,int,int,int), setManualWarpOnOff(boolean), isManualWarpOn()
ArrayList getSegmentRGBY()                     / int setSegmentRGBY(int,int,int,int)
String   getTvHardwareVersion()
int      getUsbFunc()                          / int setUsbFunc(int) / setUsbSel0Ctrl(int)
String   getVersion(int which)
int      getXprMode()                          / int setXprMode(int)                      // pixel shift (4K XPR)
boolean  is3DSyncInvert()                      / set3DSyncInvert(boolean) / set3DMode(int,int)
boolean  isBrilliantColorOn()                  / setBrilliantColorOnOff(boolean)
boolean  isHdrEnable()                         / setHdrEnable(boolean) / setHdrLevel(int,int)
boolean  isIrBodyDetectOn()                    / setIrBodyDetectOnOff(boolean)            // the eye-protection body sensor
boolean  isLightSourceOn()                     / int setLightSourceOnOff(boolean)
boolean  isLowLatencyModeOn()                  / setLowLatencyModeOnOff(boolean)
boolean  isSmoothWarpEnable()                  / setSmoothWarpEnable(boolean) / setSmoothWarpTable(...)
int      motorForward() / motorBackward()      // focus motor
void     reconnect()
int      resetHdmiHpd(int port)                // pulse HDMI hot-plug
int      resetPlatformData()
int      setAppoLeds(int mode, int value)
int      setAtTimeout(int)
int      setAudioLRMode(int,boolean) / setAudioMode(int,int)
int      setControlPointsLayout(int,int)
int      setDBEnable(boolean) / setDBScope(int,int)   // dynamic black
int      setHdcpKey(int,byte[]) / setHdcpKeyFile(int,String)
int      setKeyStoneTable(ArrayList)
int      setPanel(int) / setPassThroughOnOff(boolean) / setTestPattern(String) / setVrrEnable(boolean)
```

## HIDL methods (`hw_*`)

The complete list from the `.so` symbols, for anything the wrapper leaves out:
`hw_enterSleepMode hw_get3DMode hw_getAllFanSpeeds hw_getAllTemperatures hw_getAudioMode
hw_getBrightnessMode hw_getColorWheelDelay hw_getColorWheelSpeed hw_getCurrentGammaIndex
hw_getCurrentRGBY hw_getEeprom hw_getEventSwitchStatus hw_getFanDutyCycle hw_getFanSpeed
hw_getGammaIndex hw_getGpioLevel hw_getHdmiColorDepth hw_getHdrAvgFALL hw_getHdrMaxCLL
hw_getHighFreqMode hw_getHsgData hw_getInstallationMode hw_getLightSourceTime hw_getLookIndex
hw_getMaxLuminance hw_getMinLuminance hw_getPlatformProperty hw_getSavedManualWarpTable
hw_getSegmentRGBY hw_getTvHardwareVersion hw_getUsbFunc hw_getVersion hw_getXprMode hw_invokeEvent
hw_is3DSyncInvert hw_isBrilliantColorOn hw_isHdrEnable hw_isIrBodyDetectOn hw_isLightSourceOn
hw_isLowLatencyModeOn hw_isManualWarpOn hw_isSmoothWarpEnable hw_motorBackward hw_motorForward
hw_powerDown hw_projectorManagerExit hw_projectorManagerInit hw_readSocReg hw_resetHdmiHpd
hw_resetHsg hw_resetPlatformData hw_saveManualWarpTable hw_set3DMode hw_set3DSyncInvert
hw_setAppoLeds hw_setAtTimeout hw_setAudioLRMode hw_setAudioMode hw_setBrightnessMode
hw_setBrilliantColorOnOff hw_setColorWheelDelay hw_setControlPointsLayout hw_setCurrentRGBY
hw_setDBEnable hw_setDBScope hw_setDlpWarpingData hw_setEeprom hw_setFanDutyCycle hw_setGammaIndex
hw_setGpioDirection hw_setGpioLevel hw_setHdcpKey hw_setHdcpKeyFile hw_setHdrEnable hw_setHdrLevel
hw_setHighFreqMode hw_setHsgData hw_setInstallationMode hw_setIrBodyDetectOnOff hw_setKeyStoneTable
hw_setLightSourceOnOff hw_setLightSourceTime hw_setLookIndex hw_setLowLatencyModeOnOff
hw_setManualWarpOnOff hw_setManualWarpPoint hw_setManualWarpTable hw_setMutexMode hw_setPanel
hw_setPassThroughOnOff hw_setPlatformProperty hw_setSegmentRGBY hw_setSmoothWarpEnable
hw_setSmoothWarpTable hw_setTestPattern hw_setUsbFunc hw_setUsbSel0Ctrl hw_setVrrEnable
hw_writeLogFormat hw_writeSocReg` ([raw list](raw/hidl-methods.txt)).

## `com.appo.tv` (what the apps call)

Bundled in every vendor app, implemented by `com.appo.internal.*DefaultImpl` on top of
`ProjectorManager`. The interesting parts:

**`DlpManager`** (`TvContext.getInstance().getDlpManager()`): `isLightSourceEnabled()` =
`ProjectorManager.isLightSourceOn()`; `setLightSourceEnabled(boolean)` = set property
`cur.appo.light.enabled` then `setLightSourceOnOff`. Plus brightness (`getBrightness/setBrightness`,
`getDlpBrightnessMode/setDlpBrightnessMode`), gamma, HSG, keystone (`getKeystoneData/setKeystoneData`,
`prjSetAutoKeystoneEnable`), warp, 3D, 24p, dynamic black (`DB`), HDR, XPR, brilliant colour,
low latency, pass-through, temperatures (`getTemperature()` as a `SparseIntArray`), laser hours,
zoom scale, installation mode, EEPROM. Full list in [raw/java-api.txt](raw/java-api.txt).

**`DeviceManager`** (`getDeviceManager()`): `shutdown(Context)` = `enterSleepMode(0)` + Android
`REQUEST_SHUTDOWN`; `setAppoLeds(mode,value)` (on a thread); `isLedEnabled()/setLedEnabled(boolean)`
= unify key `appo_led_str` (1/0) and eMMC env `appo_led_off` (on/off); `setCecEnabled` = the three
`hdmi_control_*` globals + `appo_hdmi_control_enabled` + eMMC env `appo_wakeup_cec`;
`getNoSignalPowerOffTime/setNoSignalPowerOff` = global `no_signal_auto_power_off`;
`getBootSource/setBootSource` = global `boot_source_id`; `isSignalLoss` = MediaTek
`MtkTvBroadcast.isSignalLoss()` on the current input; `setBodyDetectEnabled` =
`setIrBodyDetectOnOff`; `setPowerOnEnabled` = MediaTek factory power mode (power on when mains
returns); plus picture (PQ mode, colour temperature, DNR, MEMC, HDR, Dolby Vision), sound
(Dolby, surround, eARC, digital output), EDID version, HDCP keys, focus motor, fans, eMMC env and
"unify key" storage.

## The AT protocol

The HAL builds `AT+<Name>=<args>` strings, writes them to `/dev/ttyS1` and expects
`AT+<Name>#OK` (or `#<data>` for reads). Names seen on the wire or in the binary's string table:

`LightSource`, `LightSourceTime`, `Temperature` (returns `NtcRedLaser1:..,NtcGreenLaser1:..,
NtcBlueLaser1:..,NtcCw1:..,NtcDmd1:..,NtcEnv1:..`), `Version`, `FanEnable`, `FanStatus`,
`SetFan`, `Gamma`, `GetGammaIndex`/`SetHsgIndex`/`GetHsgData`/`SetHsgData`/`UserHSG`,
`GetCurrent`/`SetCurrent`/`GetSegment`/`SetSegment`, `ColorWheelDelay`/`CwSpeed`,
`Mode3D`/`SyncFlip3D`, `SetPanel`/`SwitchPanel`, `PassThrough`, `SetBrightnessMode`,
`BrilliantColor`, `SetHdrEnable`/`SetHdrTypeLevel`/`GetHdrMaxLuminance`/`GetHdrMinLuminance`/
`GetHdrMetaDataMaxCLL`/`GetHdrMetaDataAvgFALL`, `GetXprMode`, `SetLookIndex`/`GetLookIndex`,
`DlpWarping`, `GetKeyStoneTable`/`SaveKeyStoneTable`, `SetManualWarpTable`/`GetSavedManualWarpTable`,
`SetSmoothWarpTable`, `SetInitData`/`InitManualWarpData`, `TestPattern` (`White`, `Black`, `Red`,
`Green`, `Blue`, `Cyan`, `Megenta`, `Yellow`, `Grid`, `CheckErboard`, `HorizontalLines`,
`VerticalLines`, `HorizontalRamp`, `VerticalRamp`, `DiagonalLines`), `MoveForward`/`MoveBackward`
(focus motor), `DoMove`, `Connect`, `Init`, `Error`, `GetTvHardwareVersion`, `SetEeprom`,
`USBSEL0CTRL`, `AutoHighFreqMode`, `AutoSwitch4K24P`, `HdmiVrrEnable`, `IsLowLatencyModeOn`,
`ResetHdmiHpd`, `InvokeEvent`. Version sub-names: `DlpHardware`, `DlpSoftware`, `IduHardware`,
`IduSoftware`, `PmuSoftware`. Installation modes: `TableFront`, `TableRear`, `CeilingFront`,
`CeilingRear`.

Talking to `/dev/ttyS1` directly would race the HAL; go through the HAL.
