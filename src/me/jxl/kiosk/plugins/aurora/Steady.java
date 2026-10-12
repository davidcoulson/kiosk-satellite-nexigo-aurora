// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.aurora;

import java.util.HashMap;
import java.util.Map;

/**
 * Which readings are worth sending. Home Assistant's recorder keeps a row for every new value, and
 * a reading that wobbles (CPU use, fan tachometers, a temperature on the edge of a degree) writes
 * one on every poll while nothing is happening. A value is sent when it moves by at least its
 * step from the one last sent, when it turns unknown or comes back, or when the last one sent is
 * older than its age limit, so a slow drift inside the step still shows up.
 */
final class Steady {
    /** Per key: the value last sent (NaN for unknown) and when. */
    private final Map<String, double[]> sent = new HashMap<>();

    synchronized boolean due(String key, Double value, double step, long maxAgeMs, long now) {
        double v = value == null ? Double.NaN : value;
        double[] prev = sent.get(key);
        if (prev != null) {
            boolean wasUnknown = Double.isNaN(prev[0]), unknown = Double.isNaN(v);
            if (wasUnknown && unknown) return false;
            if (wasUnknown == unknown && Math.abs(v - prev[0]) < step && now - (long) prev[1] < maxAgeMs) return false;
        }
        sent.put(key, new double[] { v, now });
        return true;
    }

    /** Forget what was sent, so the next reading of everything goes out (a fresh start). */
    synchronized void clear() { sent.clear(); }
}
