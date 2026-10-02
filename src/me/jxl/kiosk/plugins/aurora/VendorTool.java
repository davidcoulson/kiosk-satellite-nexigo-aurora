package me.jxl.kiosk.plugins.aurora;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * A helper process, not part of the plugin's running code: {@code app_process} starts it as the
 * shell user (CLASSPATH = a copy of this plugin's DEX in /data/local/tmp) and it calls the
 * projector's own Java API, the classes every vendor app bundles: {@code com.appo.tv} (DlpManager,
 * DeviceManager) on top of {@code com.appotronics.support.ProjectorManager}, and MediaTek's TV
 * library for the signal. Settings go through the vendor's own setters, which do more than a
 * property: the brightness mode also sets the laser drive currents and the thermal target.
 *
 * <p>Output is {@code key=value} lines. A value that could not be read is left out and its error
 * printed as {@code error.key=...}. Modes:
 * <ul>
 * <li>{@code quick}: the vendor API only, a fraction of a second.</li>
 * <li>{@code read}: quick plus the signal, its resolution and HDR type from MediaTek's TV library,
 * whose start takes about ten seconds.</li>
 * <li>{@code set <key> <value>}: one setting through its vendor setter, then quick.</li>
 * </ul>
 * Uses reflection only, so it builds without the vendor jars.
 */
public final class VendorTool {
    /** The TV app's copy of MediaTek's library first (it has getSrcVideoResolution), then the
     *  settings app's com.appo.tv, then the HAL wrapper. */
    static final String CLASSPATH = "/system/priv-app/LiveTV/LiveTV.apk:/system/app/XMProjectorSettings/XMProjectorSettings.apk:/system/framework/com.appotronics.support.jar";

    private static ClassLoader loader;

    private VendorTool() {}

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "quick";
        try {
            Class<?> pcl = Class.forName("dalvik.system.PathClassLoader");
            loader = (ClassLoader) pcl.getConstructor(String.class, String.class, ClassLoader.class)
                .newInstance(CLASSPATH, "/system/lib:/vendor/lib", ClassLoader.getSystemClassLoader());
        } catch (Throwable e) {
            System.out.println("error.loader=" + e);
            System.exit(1);
        }
        Object tv = call(null, "com.appo.tv.TvContext", "getInstance");
        Object dlp = call(tv, null, "getDlpManager");
        Object dev = call(tv, null, "getDeviceManager");
        Object pm = call(null, "com.appotronics.support.ProjectorManager", "getInstance");
        if (mode.equals("set") && args.length == 3) {
            int v;
            try { v = Integer.parseInt(args[2]); } catch (NumberFormatException e) { System.out.println("error.set=bad value"); System.exit(2); return; }
            Object r;
            switch (args[1]) {
                // The settings app passes a Context; the brightness setter does not use it.
                case "brightness": r = call(dlp, null, "setDlpBrightnessMode", null, v); break;
                case "db": r = call(dlp, null, "setDBMode", v); break;
                case "p24": r = call(dlp, null, "set24PMode", v); break;
                case "dhdr": r = call(dev, null, "setDynamicHdrMode", v); break;
                case "low_latency": r = call(dlp, null, "setLowLatencyEnabled", v != 0); break;
                default: System.out.println("error.set=unknown setting"); System.exit(2); return;
            }
            if (r instanceof Failure) System.out.println("error.set=" + r);
        }
        quick(dlp, dev, pm);
        if (mode.equals("read")) signal(dev, pm);
        System.out.flush();
        System.exit(0);
    }

    private static void quick(Object dlp, Object dev, Object pm) {
        StringBuilder fans = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            Object f = call(pm, null, "getFanSpeed", i);
            if (f instanceof Integer) fans.append(fans.length() > 0 ? "," : "").append(f);
        }
        if (fans.length() > 0) System.out.println("fans=" + fans);
        print("brightness", call(dlp, null, "getDlpBrightnessMode", (Object) null));
        print("db", call(dlp, null, "getDBMode"));
        print("p24", call(dlp, null, "get24PMode"));
        print("dhdr", call(dev, null, "getDynamicHdrMode"));
        print("low_latency", call(dlp, null, "isLowLatencyEnabled"));
        print("body_detect", call(dev, null, "isBodyDetectEnabled"));
        print("color_depth", call(pm, null, "getHdmiColorDepth"));
    }

    private static void signal(Object dev, Object pm) {
        Object broadcast = call(null, "com.mediatek.twoworlds.tv.MtkTvBroadcast", "getInstance");
        Object loss = call(broadcast, null, "isSignalLoss");
        if (loss instanceof Boolean) print("signal", !(Boolean) loss);
        else print("signal", loss);
        print("hdr", call(dev, null, "getHdrType"));
        print("max_cll", call(pm, null, "getHdrMaxCLL"));
        Object util = call(null, "com.mediatek.twoworlds.tv.MtkTvUtil", "getInstance");
        Object res = call(util, null, "getSrcVideoResolution", "main");
        if (res instanceof Failure) { print("res", res); return; }
        Object w = call(res, null, "getWidth"), h = call(res, null, "getHeight"), f = call(res, null, "getFrameRate");
        Object p = call(res, null, "getIsProgressive");
        if (w instanceof Integer && h instanceof Integer) System.out.println("res=" + w + "x" + h + "," + (f instanceof Integer ? f : 0) + "," + (Boolean.TRUE.equals(p) ? "p" : "i"));
    }

    private static void print(String key, Object value) {
        if (value instanceof Failure) System.out.println("error." + key + "=" + value);
        else if (value != null) System.out.println(key + "=" + value);
    }

    /** A call that did not return a value. */
    private static final class Failure {
        final String why;
        Failure(String why) { this.why = why; }
        @Override public String toString() { return why.replace('\n', ' '); }
    }

    /** Calls a method by name and arity on an object, or a static one on a class by name. */
    private static Object call(Object target, String className, String name, Object... args) {
        if (target instanceof Failure) return target;
        try {
            Class<?> c = target != null ? target.getClass() : Class.forName(className, true, loader);
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                for (Method m : k.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterTypes().length == args.length) {
                        m.setAccessible(true);
                        return m.invoke(target, args);
                    }
                }
            }
            return new Failure("no method " + name);
        } catch (InvocationTargetException e) {
            return new Failure(String.valueOf(e.getCause()));
        } catch (Throwable e) {
            return new Failure(String.valueOf(e));
        }
    }
}
