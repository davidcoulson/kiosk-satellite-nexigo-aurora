// SPDX-License-Identifier: Apache-2.0
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.jxl.kiosk.plugins.PluginHost;
import me.jxl.kiosk.plugins.aurora.AuroraPluginTestAccess;

/**
 * Drives the plugin against a fake host and a fake shell: no projector, no Android. What is
 * checked is what the plugin publishes for a given projector answer, and exactly which script
 * each Home Assistant command turns into, since the scripts are the whole point.
 */
public final class AuroraPluginTest {
    public static void main(String[] args) throws Exception {
        AuroraPluginTestAccess.run();
        System.out.println("PASS: parsing, entity publication, command scripts, option rejection, channel fallback and shutdown.");
    }
}
