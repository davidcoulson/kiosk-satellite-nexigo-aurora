// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.aurora;

import java.lang.reflect.Method;

/**
 * Settings.Global from inside the kiosk process, for the readings the shell tools cannot make
 * from an app: the {@code settings} command exists for the shell user, and from an ordinary app
 * it answers nothing. The framework itself answers any app that asks to read a global setting.
 *
 * <p>The plugin has no Context of its own; the application's comes from
 * {@code ActivityThread.currentApplication()}, which is on Android's light-greylist and still
 * answers on the Android 9 this projector runs. Every call is reflective and every failure is
 * "unknown", so a build where this stops working loses two readings, not the plugin.
 *
 * <p>Writing needs {@code WRITE_SECURE_SETTINGS}, which Kiosk Satellite does not hold; a write
 * is attempted here first and falls through to the shell channel when the framework refuses.
 */
final class Framework {
    private Framework() {}

    private static Object application() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Method current = thread.getMethod("currentApplication");
            return current.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object resolver() {
        Object app = application();
        if (app == null) return null;
        try {
            return app.getClass().getMethod("getContentResolver").invoke(app);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Settings.Global.getString, or null when the framework is out of reach or the key unset. */
    static String getGlobal(String key) {
        Object resolver = resolver();
        if (resolver == null) return null;
        try {
            Class<?> global = Class.forName("android.provider.Settings$Global");
            Method get = global.getMethod("getString", Class.forName("android.content.ContentResolver"), String.class);
            Object value = get.invoke(null, resolver, key);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Settings.Global.putInt; false when refused (no WRITE_SECURE_SETTINGS) or unreachable. */
    static boolean putGlobal(String key, int value) {
        Object resolver = resolver();
        if (resolver == null) return false;
        try {
            Class<?> global = Class.forName("android.provider.Settings$Global");
            Method put = global.getMethod("putInt", Class.forName("android.content.ContentResolver"), String.class, int.class);
            return Boolean.TRUE.equals(put.invoke(null, resolver, key, value));
        } catch (Throwable t) {
            return false;
        }
    }
}
