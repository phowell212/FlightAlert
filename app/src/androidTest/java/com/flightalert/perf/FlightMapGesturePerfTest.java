package com.flightalert.perf;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.flightalert.MainActivity;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class FlightMapGesturePerfTest {
    private static final String PACKAGE_NAME = "com.flightalert";

    @Rule
    public TestName testName = new TestName();

    @Test public void quickZoomJumpsOverTraffic() throws Exception { runScenario(); }
    @Test public void quickZoomJumpsOverTrafficSatellite() throws Exception { runScenario(); }
    @Test public void quickZoomJumpsOverTrafficStreetPerf() throws Exception { runScenario(); }
    @Test public void quickZoomJumpsOverTrafficSatellitePerf() throws Exception { runScenario(); }
    @Test public void zoomLowToHighSweep() throws Exception { runScenario(); }
    @Test public void zoomLowToHighSweepStreet() throws Exception { runScenario(); }
    @Test public void morphTransitionSweepStreet() throws Exception { runScenario(); }
    @Test public void morphTransitionSweepSatellite() throws Exception { runScenario(); }
    @Test public void morphTransitionSweepStreetPerf() throws Exception { runScenario(); }
    @Test public void morphTransitionSweepSatellitePerf() throws Exception { runScenario(); }
    @Test public void countryScaleZoomContinuityStreet() throws Exception { runScenario(); }
    @Test public void countryScaleZoomContinuitySatellite() throws Exception { runScenario(); }
    @Test public void countryScaleZoomContinuityStreetPerf() throws Exception { runScenario(); }
    @Test public void countryScaleZoomContinuitySatellitePerf() throws Exception { runScenario(); }
    @Test public void wideScaleZoomContinuityStreet() throws Exception { runScenario(); }
    @Test public void wideScaleZoomContinuitySatellite() throws Exception { runScenario(); }
    @Test public void wideScaleZoomContinuityStreetPerf() throws Exception { runScenario(); }
    @Test public void wideScaleZoomContinuitySatellitePerf() throws Exception { runScenario(); }
    @Test public void closeScaleZoomContinuitySatellite() throws Exception { runScenario(); }
    @Test public void closeScaleZoomContinuitySatellitePerf() throws Exception { runScenario(); }
    @Test public void satelliteTileTransitionBandContinuity() throws Exception { runScenario(); }
    @Test public void satelliteTileTransitionBandContinuityPerf() throws Exception { runScenario(); }
    @Test public void satelliteFastZoomOutTileLoad() throws Exception { runScenario(); }
    @Test public void satelliteFastZoomOutTileLoadPerf() throws Exception { runScenario(); }
    @Test public void closeSatellitePanLabels() throws Exception { runScenario(); }
    @Test public void closeSatellitePanLabelsPerf() throws Exception { runScenario(); }
    @Test public void satellitePanZoomSanityPerf() throws Exception { runScenario(); }
    @Test public void satelliteBenchmarkPanZoomWorkloadPerf() throws Exception { runScenario(); }
    @Test public void streetFastZoomOutTileLoad() throws Exception { runScenario(); }
    @Test public void streetFastZoomOutTileLoadPerf() throws Exception { runScenario(); }
    @Test public void panAcrossZoomLevels() throws Exception { runScenario(); }
    @Test public void launchOnly() throws Exception { runScenario(); }

    private void runScenario() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bundle args = InstrumentationRegistry.getArguments();
        String method = testName.getMethodName();
        String artifactName = defaultArtifactName(method);
        String artifactPrefix = "flightalert-perf-" + artifactName;
        Target target = targetFor(args.getString("targetCity", "Atlanta"));
        String mapSource = method.toLowerCase(Locale.US).contains("street") ? "STREET" : "SATELLITE";

        grantRuntimePermissions();
        runShellCommand("dumpsys gfxinfo " + PACKAGE_NAME + " reset");

        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("com.flightalert.PERF_LAT", target.lat)
                .putExtra("com.flightalert.PERF_LON", target.lon)
                .putExtra("com.flightalert.PERF_ZOOM", zoomFor(method))
                .putExtra("com.flightalert.PERF_RUN_ID", artifactPrefix)
                .putExtra("com.flightalert.PERF_FOCUS_OPEN_MAP", "true")
                .putExtra("com.flightalert.PERF_MAP_SOURCE", mapSource)
                .putExtra("com.flightalert.PERF_CLEAR_SELECTION", "true");

        Boolean mapRoads = optionalBoolean(args.getString("mapRoads"));
        Boolean mapBorders = optionalBoolean(args.getString("mapBorders"));
        if (mapRoads != null) intent.putExtra("com.flightalert.PERF_MAP_LABELS_ENABLED", mapRoads);
        if (mapBorders != null) intent.putExtra("com.flightalert.PERF_MAP_BORDERS_ENABLED", mapBorders);
        if (optionalBoolean(args.getString("trafficDetailTiming")) == Boolean.TRUE) {
            intent.putExtra("com.flightalert.PERF_TRAFFIC_DETAIL_TIMING", "true");
        }

        InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.wakeUp();
        device.waitForIdle(1500);

        writeTargetArtifact(context, artifactPrefix, method, target, mapSource, mapRoads, mapBorders, args);
        driveGestures(device, method, parseInt(args.getString("gestureVariant"), 0));
        holdForEvidence(args, method);
        writeArtifact(context, artifactPrefix + "-display.txt", runShellCommand("dumpsys display"));
        writeArtifact(context, artifactPrefix + "-package.txt", runShellCommand("cmd package dump " + PACKAGE_NAME));
        writeArtifact(context, artifactPrefix + "-framestats.txt", runShellCommand("dumpsys gfxinfo " + PACKAGE_NAME + " framestats"));
    }

    private void driveGestures(UiDevice device, String method, int variant) {
        int w = Math.max(1, device.getDisplayWidth());
        int h = Math.max(1, device.getDisplayHeight());
        int cx = w / 2;
        int cy = h / 2;
        int dx = Math.max(80, w / 5);
        int dy = Math.max(80, h / 6);
        int loops = method.toLowerCase(Locale.US).contains("benchmark") ? 18 : 10;
        if (method.equals("launchOnly")) loops = 3;
        for (int i = 0; i < loops; i++) {
            int direction = (i + variant) % 4;
            if (direction == 0) device.swipe(cx - dx, cy, cx + dx, cy, 18);
            if (direction == 1) device.swipe(cx + dx, cy, cx - dx, cy, 18);
            if (direction == 2) device.swipe(cx, cy - dy, cx, cy + dy, 18);
            if (direction == 3) device.swipe(cx, cy + dy, cx, cy - dy, 18);
            if (i % 3 == 0) device.pressKeyCode(KeyEvent.KEYCODE_PLUS);
            if (i % 3 == 2) device.pressKeyCode(KeyEvent.KEYCODE_MINUS);
            SystemClock.sleep(220L);
        }
    }

    private void holdForEvidence(Bundle args, String method) {
        int defaultHold = defaultHoldMs(method) / 1000;
        int holdSeconds = Math.max(defaultHold, parseInt(args.getString("videoEvidenceHoldSeconds"), 0));
        long deadline = SystemClock.elapsedRealtime() + holdSeconds * 1000L;
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(250L);
        }
    }

    private int defaultHoldMs(String method) {
        return method.toLowerCase(Locale.US).contains("benchmark") ? 60000 : 8000;
    }

    private void grantRuntimePermissions() throws Exception {
        runShellCommand("pm grant " + PACKAGE_NAME + " " + Manifest.permission.ACCESS_FINE_LOCATION);
        runShellCommand("pm grant " + PACKAGE_NAME + " " + Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runShellCommand("pm grant " + PACKAGE_NAME + " " + Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void writeTargetArtifact(
            Context context,
            String artifactPrefix,
            String method,
            Target target,
            String mapSource,
            Boolean mapRoads,
            Boolean mapBorders,
            Bundle args
    ) throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("test_name=").append(method).append('\n');
        out.append("city=").append(target.name).append('\n');
        out.append("lat=").append(format(target.lat)).append('\n');
        out.append("lon=").append(format(target.lon)).append('\n');
        out.append("zoom=").append(format(zoomFor(method))).append('\n');
        out.append("map_source=").append(mapSource).append('\n');
        out.append("map_roads=").append(mapRoads == null ? "current" : mapRoads.toString()).append('\n');
        out.append("map_borders=").append(mapBorders == null ? "current" : mapBorders.toString()).append('\n');
        out.append("app_focus_open_map=true\n");
        out.append("gesture_focus=open-map\n");
        out.append("scale_bands=benchmark-pan-zoom\n");
        out.append("phase_name=").append(method).append('\n');
        out.append("phase_zoom_plan=target_motion_ms=").append(defaultHoldMs(method)).append('\n');
        out.append("phase_gesture_plan=swipe-pan-plus-minus-key-zoom\n");
        out.append("traffic_detail_timing=").append(args.getString("trafficDetailTiming", "false")).append('\n');
        out.append("map_detail_timing=").append(args.getString("mapDetailTiming", "false")).append('\n');
        out.append("frame_metrics_probe=").append(args.getString("frameMetricsProbe", "false")).append('\n');
        writeArtifact(context, artifactPrefix + "-target.txt", out.toString());
    }

    private void writeArtifact(Context context, String name, String content) throws Exception {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) throw new IllegalStateException("External files directory unavailable");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create " + dir);
        File out = new File(dir, name);
        try (FileOutputStream stream = new FileOutputStream(out)) {
            stream.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String runShellCommand(String command) throws Exception {
        ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand(command);
        try (InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            byte[] buffer = new byte[8192];
            StringBuilder out = new StringBuilder();
            int bytesRead;
            while ((bytesRead = stream.read(buffer)) != -1) {
                out.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
            }
            return out.toString();
        }
    }

    private String defaultArtifactName(String name) {
        Map<String, String> names = new HashMap<>();
        names.put("quickZoomJumpsOverTraffic", "quickZoomJumpsOverTrafficStreet");
        names.put("zoomLowToHighSweep", "zoomLowToHighSweepSatellite");
        return names.containsKey(name) ? names.get(name) : name;
    }

    private double zoomFor(String method) {
        String lower = method.toLowerCase(Locale.US);
        if (lower.contains("wide")) return 4.8;
        if (lower.contains("country")) return 6.2;
        if (lower.contains("transition") || lower.contains("morph")) return 9.2;
        if (lower.contains("close")) return 12.3;
        if (lower.contains("benchmark")) return 10.8;
        return 10.2;
    }

    private Target targetFor(String city) {
        String key = normalize(city);
        Map<String, Target> targets = targets();
        Target target = targets.get(key);
        return target != null ? target : targets.get("atlanta");
    }

    private Map<String, Target> targets() {
        Map<String, Target> targets = new HashMap<>();
        addTarget(targets, new Target("Dallas-Fort Worth", 32.90, -97.04), "dallasfortworth", "dfw");
        addTarget(targets, new Target("Atlanta", 33.64, -84.43), "atlanta", "atl");
        addTarget(targets, new Target("Phoenix", 33.43, -112.01), "phoenix", "phx");
        addTarget(targets, new Target("Las Vegas", 36.08, -115.15), "lasvegas", "las", "vegas");
        addTarget(targets, new Target("Chicago", 41.88, -87.63), "chicago", "chi", "ord");
        addTarget(targets, new Target("New York City", 40.73, -73.93), "newyorkcity", "newyork", "nyc", "jfk");
        addTarget(targets, new Target("Los Angeles", 33.94, -118.40), "losangeles", "la", "lax");
        addTarget(targets, new Target("London", 51.47, -0.45), "london", "lhr", "lon");
        addTarget(targets, new Target("Amsterdam", 52.31, 4.77), "amsterdam", "ams");
        addTarget(targets, new Target("Frankfurt", 50.04, 8.56), "frankfurt", "fra");
        addTarget(targets, new Target("Paris", 49.01, 2.55), "paris", "par", "cdg");
        addTarget(targets, new Target("Madrid", 40.49, -3.57), "madrid", "mad");
        return targets;
    }

    private void addTarget(Map<String, Target> targets, Target target, String... keys) {
        for (String key : keys) targets.put(normalize(key), target);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }

    private Boolean optionalBoolean(String value) {
        if (value == null) return null;
        String normalized = value.toLowerCase(Locale.US);
        if (normalized.equals("true") || normalized.equals("1") || normalized.equals("yes")) return true;
        if (normalized.equals("false") || normalized.equals("0") || normalized.equals("no")) return false;
        return null;
    }

    private int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String format(double value) {
        return String.format(Locale.US, "%.5f", value);
    }

    private static final class Target {
        final String name;
        final double lat;
        final double lon;

        Target(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }
}
