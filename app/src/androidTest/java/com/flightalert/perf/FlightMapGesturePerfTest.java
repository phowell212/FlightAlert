package com.flightalert.perf;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.flightalert.MainActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public class FlightMapGesturePerfTest {
    private static final String PACKAGE_NAME = "com.flightalert";
    private static final long TRAFFIC_LOAD_WAIT_MS = 9000L;
    private static final int QUICK_PINCH_SPEED_PX_PER_SEC = 14000;
    private static final int SMOOTH_PINCH_SPEED_PX_PER_SEC = 3200;
    private static final int HUMAN_TRANSITION_PINCH_STEPS = 24;
    private static final long HUMAN_TRANSITION_PINCH_STEP_DELAY_MS = 14L;
    private static final int PAN_STEPS = 44;
    private static final MajorTrafficCity[] MAJOR_TRAFFIC_CITIES = new MajorTrafficCity[] {
            new MajorTrafficCity("New York City", 40.73, -73.93),
            new MajorTrafficCity("Chicago", 41.88, -87.63),
            new MajorTrafficCity("Los Angeles", 33.94, -118.40),
            new MajorTrafficCity("Dallas-Fort Worth", 32.90, -97.04),
            new MajorTrafficCity("Atlanta", 33.64, -84.43),
            new MajorTrafficCity("Mexico City", 19.44, -99.07),
            new MajorTrafficCity("London", 51.47, -0.45),
            new MajorTrafficCity("Paris", 49.01, 2.55)
    };
    private static final MajorTrafficCity[] INLAND_TRAFFIC_CITIES = new MajorTrafficCity[] {
            new MajorTrafficCity("Chicago", 41.88, -87.63),
            new MajorTrafficCity("Dallas-Fort Worth", 32.90, -97.04),
            new MajorTrafficCity("Atlanta", 33.64, -84.43),
            new MajorTrafficCity("Mexico City", 19.44, -99.07),
            new MajorTrafficCity("Paris", 49.01, 2.55)
    };
    private final java.util.ArrayList<Thread> scheduledCaptureThreads = new java.util.ArrayList<>();
    private MajorTrafficCity currentPerfCity = null;
    private double currentPerfZoom = Double.NaN;
    private String currentPerfMapSource = "";
    private boolean currentPerfSkipChrome = false;
    private boolean currentPerfSkipTopStatus = false;
    private boolean currentPerfSkipControls = false;
    private boolean currentPerfSkipTrafficPanel = false;
    private boolean currentPerfSkipTraffic = false;
    private boolean currentPerfTrafficDetailTiming = false;
    private boolean centeredPerfGestureFocus = false;

    @Test
    public void launchOnly() throws Exception {
        MajorTrafficCity city = randomMajorTrafficCity();
        startAppAtMajorTraffic(city, 5.4);
        sleep(TRAFFIC_LOAD_WAIT_MS);
        requireFlightAlertForeground();
        captureActiveDisplay("flightalert-perf-launchOnly.png");
    }

    @Test
    public void quickZoomJumpsOverTraffic() throws Exception {
        runQuickZoomJumpsOverTraffic("STREET", "quickZoomJumpsOverTrafficStreet", true);
    }

    @Test
    public void quickZoomJumpsOverTrafficSatellite() throws Exception {
        runQuickZoomJumpsOverTraffic("SATELLITE", "quickZoomJumpsOverTrafficSatellite", true);
    }

    @Test
    public void quickZoomJumpsOverTrafficStreetPerf() throws Exception {
        runQuickZoomJumpsOverTraffic("STREET", "quickZoomJumpsOverTrafficStreetPerf", false);
    }

    @Test
    public void quickZoomJumpsOverTrafficSatellitePerf() throws Exception {
        runQuickZoomJumpsOverTraffic("SATELLITE", "quickZoomJumpsOverTrafficSatellitePerf", false);
    }

    private void runQuickZoomJumpsOverTraffic(String mapSource, String artifactName, boolean captureScreenshots) throws Exception {
        MajorTrafficCity city = randomInlandTrafficCity();
        UiObject2 app = startAppAtMajorTraffic(city, 5.4, mapSource);
        sleep(TRAFFIC_LOAD_WAIT_MS);
        if (captureScreenshots) {
            captureActiveDisplay("flightalert-perf-" + artifactName + "-before.png");
        }
        warmUpZoom(app);
        clearPerfCounters();

        for (int cycle = 0; cycle < 6; cycle++) {
            quickPinchClose(app);
            sleep(90);
            quickPinchOpen(app);
            sleep(140);
            quickPinchClose(app);
            sleep(90);
            quickPinchOpen(app);
            sleep(160);
            smallPanOverTraffic();
            requireFlightAlertForeground();
        }
        for (int i = 0; i < 2; i++) {
            quickPinchOpen(app);
            sleep(110);
        }
        for (int i = 0; i < 2; i++) {
            quickPinchClose(app);
            sleep(110);
        }
        sleep(1200);
        requireFlightAlertForeground();
        capturePerfArtifacts(artifactName);
        if (captureScreenshots) {
            captureActiveDisplay("flightalert-perf-" + artifactName + ".png");
        }
    }

    @Test
    public void zoomLowToHighSweep() throws Exception {
        runZoomLowToHighSweep("SATELLITE", "zoomLowToHighSweepSatellite");
    }

    @Test
    public void zoomLowToHighSweepStreet() throws Exception {
        runZoomLowToHighSweep("STREET", "zoomLowToHighSweepStreet");
    }

    @Test
    public void morphTransitionSweepStreet() throws Exception {
        runMorphTransitionSweep("STREET", "morphTransitionSweepStreet", true);
    }

    @Test
    public void morphTransitionSweepSatellite() throws Exception {
        runMorphTransitionSweep("SATELLITE", "morphTransitionSweepSatellite", true);
    }

    @Test
    public void morphTransitionSweepStreetPerf() throws Exception {
        runMorphTransitionSweep("STREET", "morphTransitionSweepStreetPerf", false);
    }

    @Test
    public void morphTransitionSweepSatellitePerf() throws Exception {
        runMorphTransitionSweep("SATELLITE", "morphTransitionSweepSatellitePerf", false);
    }

    @Test
    public void countryScaleZoomContinuityStreet() throws Exception {
        runCountryScaleZoomContinuity("STREET", "countryScaleZoomContinuityStreet", true);
    }

    @Test
    public void countryScaleZoomContinuitySatellite() throws Exception {
        runCountryScaleZoomContinuity("SATELLITE", "countryScaleZoomContinuitySatellite", true);
    }

    @Test
    public void countryScaleZoomContinuityStreetPerf() throws Exception {
        runCountryScaleZoomContinuity("STREET", "countryScaleZoomContinuityStreetPerf", false);
    }

    @Test
    public void countryScaleZoomContinuitySatellitePerf() throws Exception {
        runCountryScaleZoomContinuity("SATELLITE", "countryScaleZoomContinuitySatellitePerf", false);
    }

    @Test
    public void wideScaleZoomContinuityStreet() throws Exception {
        runWideScaleZoomContinuity("STREET", "wideScaleZoomContinuityStreet", true);
    }

    @Test
    public void wideScaleZoomContinuitySatellite() throws Exception {
        runWideScaleZoomContinuity("SATELLITE", "wideScaleZoomContinuitySatellite", true);
    }

    @Test
    public void wideScaleZoomContinuityStreetPerf() throws Exception {
        runWideScaleZoomContinuity("STREET", "wideScaleZoomContinuityStreetPerf", false);
    }

    @Test
    public void wideScaleZoomContinuitySatellitePerf() throws Exception {
        runWideScaleZoomContinuity("SATELLITE", "wideScaleZoomContinuitySatellitePerf", false);
    }

    @Test
    public void closeScaleZoomContinuitySatellite() throws Exception {
        runCloseScaleZoomContinuity("SATELLITE", "closeScaleZoomContinuitySatellite", true);
    }

    @Test
    public void closeScaleZoomContinuitySatellitePerf() throws Exception {
        runCloseScaleZoomContinuity("SATELLITE", "closeScaleZoomContinuitySatellitePerf", false);
    }

    @Test
    public void satelliteTileTransitionBandContinuity() throws Exception {
        runSatelliteTileTransitionBandContinuity("satelliteTileTransitionBandContinuity", true);
    }

    @Test
    public void satelliteTileTransitionBandContinuityPerf() throws Exception {
        runSatelliteTileTransitionBandContinuity("satelliteTileTransitionBandContinuityPerf", false);
    }

    private void runZoomLowToHighSweep(String mapSource, String artifactName) throws Exception {
        MajorTrafficCity city = randomInlandTrafficCity();
        UiObject2 app = startAppAtMajorTraffic(city, 4.8, mapSource);
        sleep(TRAFFIC_LOAD_WAIT_MS);
        captureActiveDisplay("flightalert-perf-" + artifactName + "-rest.png");
        clearPerfCounters();

        for (int i = 0; i < 10; i++) {
            if (i == 0) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-motion-start.png", 120);
            if (i == 4) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-motion-active-out.png", 120);
            smoothPinchOpen(app);
            sleep(170);
        }
        sleep(900);
        for (int i = 0; i < 9; i++) {
            if (i == 0) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-reverse-start.png", 120);
            if (i == 4) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-motion-active-in.png", 120);
            smoothPinchClose(app);
            sleep(170);
        }
        sleep(1200);
        waitForScheduledCaptures();
        requireFlightAlertForeground();
        captureActiveDisplay("flightalert-perf-" + artifactName + ".png");
        capturePerfArtifacts(artifactName);
    }

    private void runMorphTransitionSweep(String mapSource, String artifactName, boolean captureScreenshots) throws Exception {
        MajorTrafficCity city = new MajorTrafficCity("Chicago", 41.88, -87.63);
        UiObject2 app = startAppAtMajorTraffic(city, 7.55, mapSource);
        sleep(TRAFFIC_LOAD_WAIT_MS);
        if (captureScreenshots) {
            captureActiveDisplay("flightalert-perf-" + artifactName + "-rest.png");
        }
        clearPerfCounters();

        for (int i = 0; i < 7; i++) {
            if (captureScreenshots && i == 0) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-motion-start.png", 120);
            if (captureScreenshots && i == 3) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-motion-active-out.png", 120);
            gentlePinchOpen(app);
            sleep(150);
        }
        sleep(450);
        for (int i = 0; i < 7; i++) {
            if (captureScreenshots && i == 0) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-reverse-start.png", 120);
            if (captureScreenshots && i == 3) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-motion-active-in.png", 120);
            gentlePinchClose(app);
            sleep(150);
        }
        sleep(900);
        if (captureScreenshots) {
            waitForScheduledCaptures();
        }
        requireFlightAlertForeground();
        capturePerfArtifacts(artifactName);
        if (captureScreenshots) {
            captureActiveDisplay("flightalert-perf-" + artifactName + ".png");
        }
    }

    private void runWideScaleZoomContinuity(String mapSource, String artifactName, boolean captureScreenshots) throws Exception {
        MajorTrafficCity city = new MajorTrafficCity("Dallas-Fort Worth", 32.90, -97.04);
        UiObject2 app = startAppAtMajorTraffic(city, 3.25, mapSource, true);
        sleep(TRAFFIC_LOAD_WAIT_MS);
        if (captureScreenshots) {
            captureActiveDisplay("flightalert-perf-" + artifactName + "-rest.png");
        }
        clearPerfCounters();

        for (int i = 0; i < 4; i++) {
            if (captureScreenshots && i == 0) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-zoom-in-start.png", 120);
            if (captureScreenshots && i == 2) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-zoom-in-active.png", 120);
            smoothPinchOpen(app);
            sleep(170);
        }
        sleep(260);
        for (int i = 0; i < 4; i++) {
            if (captureScreenshots && i == 0) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-zoom-out-start.png", 120);
            if (captureScreenshots && i == 2) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-zoom-out-active.png", 120);
            smoothPinchClose(app);
            sleep(170);
        }
        sleep(260);
        for (int cycle = 0; cycle < 3; cycle++) {
            if (captureScreenshots && cycle == 0) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-quick-start.png", 80);
            if (captureScreenshots && cycle == 1) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-quick-active.png", 80);
            quickPinchOpen(app);
            sleep(90);
            quickPinchClose(app);
            sleep(110);
        }
        sleep(900);
        if (captureScreenshots) {
            waitForScheduledCaptures();
        }
        requireFlightAlertForeground();
        capturePerfArtifacts(artifactName);
        if (captureScreenshots) {
            captureActiveDisplay("flightalert-perf-" + artifactName + ".png");
        }
    }

    private void runCountryScaleZoomContinuity(String mapSource, String artifactName, boolean captureScreenshots) throws Exception {
        MajorTrafficCity city = randomInlandTrafficCity();
        boolean skipChrome = instrumentationBooleanArgument("skipChrome");
        boolean skipTraffic = instrumentationBooleanArgument("skipTraffic");
        centeredPerfGestureFocus = true;
        try {
            UiObject2 app = startAppAtMajorTraffic(city, 4.25, mapSource, skipChrome, skipTraffic, false);
            sleep(TRAFFIC_LOAD_WAIT_MS);
            if (captureScreenshots) {
                captureActiveDisplay("flightalert-perf-" + artifactName + "-continent-rest.png");
            }
            clearPerfCounters();

            runZoomContinuityPhase(app, artifactName, "continent", 3, true, captureScreenshots);
            if (captureScreenshots) {
                waitForScheduledCaptures();
            }
            requireFlightAlertForeground();
            capturePerfArtifacts(artifactName + "-continent");

            app = startAppAtMajorTraffic(city, 5.4, mapSource, skipChrome, skipTraffic, false);
            sleep(TRAFFIC_LOAD_WAIT_MS);
            if (captureScreenshots) {
                captureActiveDisplay("flightalert-perf-" + artifactName + "-country-rest.png");
            }
            clearPerfCounters();
            runZoomContinuityPhase(app, artifactName, "country", 5, false, captureScreenshots);

            sleep(900);
            if (captureScreenshots) {
                waitForScheduledCaptures();
            }
            requireFlightAlertForeground();
            capturePerfArtifacts(artifactName + "-country");
            if (captureScreenshots) {
                captureActiveDisplay("flightalert-perf-" + artifactName + ".png");
            }
        } finally {
            centeredPerfGestureFocus = false;
        }
    }

    private void runZoomContinuityPhase(UiObject2 app, String artifactName, String scaleName, int smoothCycles, boolean zoomInFirst, boolean captureScreenshots) throws Exception {
        for (int i = 0; i < 5; i++) {
            if (i >= smoothCycles) break;
            if (captureScreenshots && i == 0) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-" + scaleName + "-motion-start-a.png", 120);
            if (captureScreenshots && i == Math.max(1, smoothCycles / 2)) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-" + scaleName + "-motion-active-a.png", 120);
            if (zoomInFirst) {
                smoothPinchOpen(app);
            } else {
                smoothPinchClose(app);
            }
            sleep(150);
        }
        sleep(300);
        for (int i = 0; i < 5; i++) {
            if (i >= smoothCycles) break;
            if (captureScreenshots && i == 0) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-" + scaleName + "-reverse-start-b.png", 120);
            if (captureScreenshots && i == Math.max(1, smoothCycles / 2)) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-" + scaleName + "-motion-active-b.png", 120);
            if (zoomInFirst) {
                smoothPinchClose(app);
            } else {
                smoothPinchOpen(app);
            }
            sleep(150);
        }
        sleep(250);
        for (int cycle = 0; cycle < 3; cycle++) {
            if (captureScreenshots && cycle == 0) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-" + scaleName + "-quick-start.png", 80);
            if (captureScreenshots && cycle == 1) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-" + scaleName + "-quick-active.png", 80);
            quickPinchClose(app);
            sleep(85);
            quickPinchOpen(app);
            sleep(95);
        }
        requireFlightAlertForeground();
    }

    private void runCloseScaleZoomContinuity(String mapSource, String artifactName, boolean captureScreenshots) throws Exception {
        MajorTrafficCity city = randomInlandTrafficCity();
        UiObject2 app = startAppAtMajorTraffic(city, 11.6, mapSource);
        sleep(TRAFFIC_LOAD_WAIT_MS);
        if (captureScreenshots) {
            captureActiveDisplay("flightalert-perf-" + artifactName + "-rest.png");
        }
        clearPerfCounters();

        for (int i = 0; i < 4; i++) {
            if (captureScreenshots && i == 0) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-zoom-in-start.png", 120);
            if (captureScreenshots && i == 2) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-zoom-in-active.png", 120);
            smoothPinchOpen(app);
            sleep(170);
        }
        sleep(300);
        for (int i = 0; i < 5; i++) {
            if (captureScreenshots && i == 0) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-zoom-out-start.png", 120);
            if (captureScreenshots && i == 2) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-zoom-out-active.png", 120);
            smoothPinchClose(app);
            sleep(150);
        }
        sleep(240);
        for (int cycle = 0; cycle < 3; cycle++) {
            if (captureScreenshots && cycle == 0) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-quick-start.png", 80);
            if (captureScreenshots && cycle == 1) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-quick-active.png", 80);
            quickPinchClose(app);
            sleep(90);
            quickPinchOpen(app);
            sleep(110);
        }
        sleep(900);
        if (captureScreenshots) {
            waitForScheduledCaptures();
        }
        requireFlightAlertForeground();
        capturePerfArtifacts(artifactName);
        if (captureScreenshots) {
            captureActiveDisplay("flightalert-perf-" + artifactName + ".png");
        }
    }

    private void runSatelliteTileTransitionBandContinuity(String artifactName, boolean captureScreenshots) throws Exception {
        MajorTrafficCity city = randomMajorTrafficCity();
        boolean skipChrome = instrumentationBooleanArgument("skipChrome");
        boolean skipTraffic = instrumentationBooleanArgument("skipTraffic");
        UiObject2 app = startAppAtMajorTraffic(city, 5.88, "SATELLITE", skipChrome, skipTraffic);
        sleep(5200);
        if (captureScreenshots) {
            captureActiveDisplay("flightalert-perf-" + artifactName + "-rest.png");
        }
        clearPerfCounters();

        if (captureScreenshots) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-pan-start.png", 120);
        panSatelliteTransitionBand();
        sleep(180);

        if (captureScreenshots) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-zoom-in-start.png", 120);
        humanTransitionPinchOpen(app);
        if (captureScreenshots) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-zoom-in-active.png", 170);
        humanTransitionPinchOpen(app);
        sleep(220);

        if (captureScreenshots) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-zoom-out-start.png", 120);
        humanTransitionPinchClose(app);
        if (captureScreenshots) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-zoom-out-active.png", 170);
        humanTransitionPinchClose(app);
        sleep(220);

        if (captureScreenshots) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-pan-after-zoom.png", 120);
        panSatelliteTransitionBand();
        sleep(900);
        if (captureScreenshots) {
            waitForScheduledCaptures();
        }
        requireFlightAlertForeground();
        capturePerfArtifacts(artifactName);
        if (captureScreenshots) {
            captureActiveDisplay("flightalert-perf-" + artifactName + ".png");
        }
    }

    @Test
    public void panAcrossZoomLevels() throws Exception {
        MajorTrafficCity city = randomInlandTrafficCity();
        UiObject2 app = startAppAtMajorTraffic(city, 5.5);
        sleep(TRAFFIC_LOAD_WAIT_MS);
        smoothPinchOpen(app);
        sleep(500);
        clearPerfCounters();

        for (int level = 0; level < 4; level++) {
            panMajorCityCorridor(PAN_STEPS);
            sleep(180);
            smoothPinchOpen(app);
            sleep(260);
            requireFlightAlertForeground();
        }
        panMajorCityCorridor(PAN_STEPS);
        sleep(1200);
        requireFlightAlertForeground();
        captureActiveDisplay("flightalert-perf-panAcrossZoomLevels.png");
        capturePerfArtifacts("panAcrossZoomLevels");
    }

    private UiObject2 startAppAtMajorTraffic(MajorTrafficCity city, double zoom) throws Exception {
        return startAppAtMajorTraffic(city, zoom, null);
    }

    private UiObject2 startAppAtMajorTraffic(MajorTrafficCity city, double zoom, String mapSource) throws Exception {
        return startAppAtMajorTraffic(city, zoom, mapSource, false);
    }

    private UiObject2 startAppAtMajorTraffic(MajorTrafficCity city, double zoom, String mapSource, boolean skipChrome) throws Exception {
        return startAppAtMajorTraffic(city, zoom, mapSource, skipChrome, false);
    }

    private UiObject2 startAppAtMajorTraffic(MajorTrafficCity city, double zoom, String mapSource, boolean skipChrome, boolean skipTraffic) throws Exception {
        return startAppAtMajorTraffic(city, zoom, mapSource, skipChrome, skipTraffic, true);
    }

    private UiObject2 startAppAtMajorTraffic(MajorTrafficCity city, double zoom, String mapSource, boolean skipChrome, boolean skipTraffic, boolean focusOpenMap) throws Exception {
        System.out.println("Testing major traffic city: " + city.name);
        currentPerfCity = city;
        currentPerfZoom = zoom;
        currentPerfMapSource = mapSource == null ? "default" : mapSource;
        currentPerfSkipChrome = skipChrome;
        currentPerfSkipTraffic = skipTraffic;
        currentPerfSkipTopStatus = instrumentationBooleanArgument("skipTopStatus");
        currentPerfSkipControls = instrumentationBooleanArgument("skipControls");
        currentPerfSkipTrafficPanel = instrumentationBooleanArgument("skipTrafficPanel");
        boolean trafficDetailTiming = instrumentationBooleanArgument("trafficDetailTiming");
        currentPerfTrafficDetailTiming = trafficDetailTiming;
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        grantFlightAlertPermissions();
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("com.flightalert.PERF_LAT", Double.toString(city.lat))
                .putExtra("com.flightalert.PERF_LON", Double.toString(city.lon))
                .putExtra("com.flightalert.PERF_ZOOM", Double.toString(zoom))
                .putExtra("com.flightalert.PERF_MAP_LABELS_ENABLED", true)
                .putExtra("com.flightalert.PERF_CLEAR_SELECTION", true)
                .putExtra("com.flightalert.PERF_FOCUS_OPEN_MAP", focusOpenMap);
        if (skipChrome) {
            intent.putExtra("com.flightalert.PERF_SKIP_CHROME", "true");
        }
        if (currentPerfSkipTopStatus) {
            intent.putExtra("com.flightalert.PERF_SKIP_TOP_STATUS", "true");
        }
        if (currentPerfSkipControls) {
            intent.putExtra("com.flightalert.PERF_SKIP_CONTROLS", "true");
        }
        if (currentPerfSkipTrafficPanel) {
            intent.putExtra("com.flightalert.PERF_SKIP_TRAFFIC_PANEL", "true");
        }
        if (skipTraffic) {
            intent.putExtra("com.flightalert.PERF_SKIP_TRAFFIC", "true");
        }
        if (trafficDetailTiming) {
            intent.putExtra("com.flightalert.PERF_TRAFFIC_DETAIL_TIMING", "true");
        }
        if (mapSource != null) {
            intent.putExtra("com.flightalert.PERF_MAP_SOURCE", mapSource);
        }
        context.startActivity(intent);
        instrumentation.waitForIdleSync();
        acceptFlightAlertPermissionsIfPresent();
        if (!waitForFlightAlertForeground(8000L)) {
            launchFlightAlertWithShell(city, zoom, mapSource, skipChrome, skipTraffic, trafficDetailTiming, focusOpenMap);
            instrumentation.waitForIdleSync();
            acceptFlightAlertPermissionsIfPresent();
            assertTrue("Refusing to run gestures outside Flight Alert. Foreground was:\n" + foregroundDiagnostic(), waitForFlightAlertForeground(8000L));
        }
        return flightAlertRoot();
    }

    private void grantFlightAlertPermissions() throws Exception {
        runShell("pm grant " + PACKAGE_NAME + " android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1 || true");
        runShell("pm grant " + PACKAGE_NAME + " android.permission.ACCESS_COARSE_LOCATION >/dev/null 2>&1 || true");
        runShell("pm grant " + PACKAGE_NAME + " android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true");
        runShell("appops set " + PACKAGE_NAME + " ACCESS_FINE_LOCATION allow >/dev/null 2>&1 || true");
        runShell("appops set " + PACKAGE_NAME + " ACCESS_COARSE_LOCATION allow >/dev/null 2>&1 || true");
    }

    private void launchFlightAlertWithShell(MajorTrafficCity city, double zoom, String mapSource, boolean skipChrome) throws Exception {
        launchFlightAlertWithShell(city, zoom, mapSource, skipChrome, false);
    }

    private void launchFlightAlertWithShell(MajorTrafficCity city, double zoom, String mapSource, boolean skipChrome, boolean skipTraffic) throws Exception {
        launchFlightAlertWithShell(city, zoom, mapSource, skipChrome, skipTraffic, false);
    }

    private void launchFlightAlertWithShell(MajorTrafficCity city, double zoom, String mapSource, boolean skipChrome, boolean skipTraffic, boolean trafficDetailTiming) throws Exception {
        launchFlightAlertWithShell(city, zoom, mapSource, skipChrome, skipTraffic, trafficDetailTiming, true);
    }

    private void launchFlightAlertWithShell(MajorTrafficCity city, double zoom, String mapSource, boolean skipChrome, boolean skipTraffic, boolean trafficDetailTiming, boolean focusOpenMap) throws Exception {
        StringBuilder command = new StringBuilder("am start -n ")
                .append(PACKAGE_NAME).append("/.MainActivity")
                .append(" --es com.flightalert.PERF_LAT ").append(city.lat)
                .append(" --es com.flightalert.PERF_LON ").append(city.lon)
                .append(" --es com.flightalert.PERF_ZOOM ").append(zoom)
                .append(" --ez com.flightalert.PERF_MAP_LABELS_ENABLED true")
                .append(" --ez com.flightalert.PERF_CLEAR_SELECTION true")
                .append(" --ez com.flightalert.PERF_FOCUS_OPEN_MAP ").append(focusOpenMap ? "true" : "false");
        if (skipChrome) {
            command.append(" --es com.flightalert.PERF_SKIP_CHROME true");
        }
        if (currentPerfSkipTopStatus) {
            command.append(" --es com.flightalert.PERF_SKIP_TOP_STATUS true");
        }
        if (currentPerfSkipControls) {
            command.append(" --es com.flightalert.PERF_SKIP_CONTROLS true");
        }
        if (currentPerfSkipTrafficPanel) {
            command.append(" --es com.flightalert.PERF_SKIP_TRAFFIC_PANEL true");
        }
        if (skipTraffic) {
            command.append(" --es com.flightalert.PERF_SKIP_TRAFFIC true");
        }
        if (trafficDetailTiming) {
            command.append(" --es com.flightalert.PERF_TRAFFIC_DETAIL_TIMING true");
        }
        if (mapSource != null) {
            command.append(" --es com.flightalert.PERF_MAP_SOURCE ").append(mapSource);
        }
        runShell(command.toString());
    }

    private UiObject2 flightAlertRoot() throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        acceptFlightAlertPermissionsIfPresent();
        assertTrue("Flight Alert package did not appear", device.wait(Until.hasObject(By.pkg(PACKAGE_NAME)), 7000));
        UiObject2 app = device.findObject(By.pkg(PACKAGE_NAME));
        assertNotNull("Flight Alert root was not found", app);
        DisplayMetrics metrics = InstrumentationRegistry.getInstrumentation()
                .getTargetContext()
                .getResources()
                .getDisplayMetrics();
        app.setGestureMargins(
                Math.max(36, metrics.widthPixels / 18),
                Math.max(300, metrics.heightPixels / 8),
                Math.max(36, metrics.widthPixels / 18),
                Math.max(460, metrics.heightPixels / 5)
        );
        return app;
    }

    private void acceptFlightAlertPermissionsIfPresent() throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        for (int attempt = 0; attempt < 4; attempt++) {
            UiObject2 permission = device.findObject(By.pkg("com.google.android.permissioncontroller"));
            if (permission == null) return;
            UiObject2 allow = permission.findObject(By.textContains("While using"));
            if (allow == null) allow = permission.findObject(By.textContains("Allow"));
            if (allow == null) allow = permission.findObject(By.textContains("OK"));
            if (allow == null) return;
            allow.click();
            sleep(500);
        }
    }

    private void warmUpZoom(UiObject2 app) throws Exception {
        for (int i = 0; i < 3; i++) {
            smoothPinchOpen(app);
            sleep(180);
        }
        for (int i = 0; i < 2; i++) {
            smoothPinchClose(app);
            sleep(180);
        }
        requireFlightAlertForeground();
    }

    private void quickPinchOpen(UiObject2 app) {
        anchoredPinch(app, 0.72f, QUICK_PINCH_SPEED_PX_PER_SEC, true);
    }

    private void quickPinchClose(UiObject2 app) {
        anchoredPinch(app, 0.72f, QUICK_PINCH_SPEED_PX_PER_SEC, false);
    }

    private void smoothPinchOpen(UiObject2 app) {
        anchoredPinch(app, 0.64f, SMOOTH_PINCH_SPEED_PX_PER_SEC, true);
    }

    private void smoothPinchClose(UiObject2 app) {
        anchoredPinch(app, 0.64f, SMOOTH_PINCH_SPEED_PX_PER_SEC, false);
    }

    private void gentlePinchOpen(UiObject2 app) {
        anchoredPinch(app, 0.28f, SMOOTH_PINCH_SPEED_PX_PER_SEC, true);
    }

    private void gentlePinchClose(UiObject2 app) {
        anchoredPinch(app, 0.28f, SMOOTH_PINCH_SPEED_PX_PER_SEC, false);
    }

    private void humanTransitionPinchOpen(UiObject2 app) {
        anchoredPinchWithCadence(app, 0.42f, true, HUMAN_TRANSITION_PINCH_STEPS, HUMAN_TRANSITION_PINCH_STEP_DELAY_MS);
    }

    private void humanTransitionPinchClose(UiObject2 app) {
        anchoredPinchWithCadence(app, 0.42f, false, HUMAN_TRANSITION_PINCH_STEPS, HUMAN_TRANSITION_PINCH_STEP_DELAY_MS);
    }

    private void anchoredPinch(UiObject2 app, float percent, int speedPxPerSecond, boolean open) {
        android.graphics.Rect bounds = app.getVisibleBounds();
        float shortSide = Math.max(1f, Math.min(bounds.width(), bounds.height()));
        float innerRadius = Math.max(42f, shortSide * 0.055f);
        float outerRadius = Math.max(innerRadius + 48f, shortSide * percent * 0.42f);
        int steps = anchoredPinchSteps(innerRadius, outerRadius, speedPxPerSecond);
        anchoredPinchWithCadence(app, percent, open, steps, 8L);
    }

    private void anchoredPinchWithCadence(UiObject2 app, float percent, boolean open, int steps, long stepDelayMs) {
        android.graphics.Rect bounds = app.getVisibleBounds();
        android.graphics.Point focus = anchoredMapFocus(app, bounds);
        float centerX = focus.x;
        float centerY = focus.y;
        float shortSide = Math.max(1f, Math.min(bounds.width(), bounds.height()));
        float innerRadius = Math.max(42f, shortSide * 0.055f);
        float outerRadius = Math.max(innerRadius + 48f, shortSide * percent * 0.42f);
        float startRadius = open ? innerRadius : outerRadius;
        float endRadius = open ? outerRadius : innerRadius;
        int clampedSteps = Math.max(8, steps);
        long downTime = SystemClock.uptimeMillis();

        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[] {
                pointerProperties(0),
                pointerProperties(1)
        };
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[] {
                pointerCoords(centerX - startRadius, centerY),
                pointerCoords(centerX + startRadius, centerY)
        };
        injectMotion(downTime, downTime, MotionEvent.ACTION_DOWN, properties, coords, 1);
        injectMotion(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), properties, coords, 2);
        for (int step = 1; step <= clampedSteps; step++) {
            float t = step / (float) clampedSteps;
            float eased = t * t * (3f - 2f * t);
            float radius = startRadius + (endRadius - startRadius) * eased;
            coords[0].x = centerX - radius;
            coords[0].y = centerY;
            coords[1].x = centerX + radius;
            coords[1].y = centerY;
            injectMotion(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, properties, coords, 2);
            sleep(stepDelayMs);
        }
        injectMotion(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), properties, coords, 2);
        injectMotion(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, properties, coords, 1);
    }

    private int anchoredPinchSteps(float startRadius, float endRadius, int speedPxPerSecond) {
        float travel = Math.abs(endRadius - startRadius);
        float durationMs = travel * 1000f / Math.max(1, speedPxPerSecond);
        return Math.max(8, Math.min(42, Math.round(durationMs / 8f)));
    }

    private android.graphics.Point anchoredMapFocus(UiObject2 app, android.graphics.Rect bounds) {
        if (centeredPerfGestureFocus) {
            return new android.graphics.Point(bounds.centerX(), bounds.centerY());
        }
        float width = Math.max(1f, bounds.width());
        float height = Math.max(1f, bounds.height());
        float focusX;
        float focusY;
        if (width > height * 1.15f) {
            float margin = dp(12f);
            float infoWidth = Math.min(dp(360f), Math.max(dp(300f), width * 0.32f));
            float infoLeft = width - margin - infoWidth;
            float top = margin + dp(66f);
            float bottom = height - dp(44f) - dp(14f);
            focusX = infoLeft * 0.5f;
            focusY = top + Math.max(1f, bottom - top) * 0.5f;
        } else {
            float margin = dp(12f);
            float top = margin + dp(66f);
            float panelHeight = Math.min(dp(176f), Math.max(dp(152f), height * 0.24f));
            float bottom = height - margin - panelHeight - dp(12f);
            focusX = width * 0.5f;
            focusY = top + Math.max(1f, bottom - top) * 0.5f;
        }
        focusX = Math.max(bounds.left + dp(72f), Math.min(bounds.right - dp(72f), bounds.left + focusX));
        focusY = Math.max(bounds.top + dp(72f), Math.min(bounds.bottom - dp(72f), bounds.top + focusY));
        return new android.graphics.Point(Math.round(focusX), Math.round(focusY));
    }

    private float dp(float value) {
        return value * InstrumentationRegistry.getInstrumentation()
                .getTargetContext()
                .getResources()
                .getDisplayMetrics()
                .density;
    }

    private MotionEvent.PointerProperties pointerProperties(int id) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = id;
        properties.toolType = MotionEvent.TOOL_TYPE_FINGER;
        return properties;
    }

    private MotionEvent.PointerCoords pointerCoords(float x, float y) {
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.pressure = 1f;
        coords.size = 1f;
        return coords;
    }

    private void injectMotion(
            long downTime,
            long eventTime,
            int action,
            MotionEvent.PointerProperties[] properties,
            MotionEvent.PointerCoords[] coords,
            int pointerCount
    ) {
        MotionEvent event = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                pointerCount,
                properties,
                coords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
        );
        try {
            InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(event, true);
        } finally {
            event.recycle();
        }
    }

    private void panMajorCityCorridor(int steps) throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int left = Math.max(70, (int) (width * 0.20f));
        int right = Math.min(width - 70, (int) (width * 0.76f));
        int top = Math.max(340, (int) (height * 0.34f));
        int middle = trafficFocusY(height);
        int bottom = Math.min(height - 560, (int) (height * 0.56f));
        device.swipe(right, top, left, top, steps);
        sleep(90);
        device.swipe(left, top, right, top, steps);
        sleep(90);
        device.swipe(right, middle, left, middle, steps);
        sleep(90);
        device.swipe(left, middle, right, middle, steps);
        sleep(90);
        device.swipe(right, bottom, left, bottom, steps);
        sleep(90);
        device.swipe(left, bottom, right, bottom, steps);
        sleep(90);
        device.swipe(width / 2, bottom, width / 2, top, steps);
        sleep(90);
        device.swipe(width / 2, top, width / 2, bottom, steps);
        sleep(90);
        device.swipe(right, bottom, left, top, steps);
        sleep(90);
        device.swipe(left, top, right, bottom, steps);
        sleep(90);
        device.swipe(left, bottom, right, top, steps);
        sleep(90);
        device.swipe(right, top, left, bottom, steps);
        sleep(90);
    }

    private void smallPanOverTraffic() throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int cx = width / 2;
        int cy = trafficFocusY(height);
        int dx = Math.max(90, width / 7);
        int dy = Math.max(120, height / 14);
        device.swipe(cx + dx, cy, cx - dx, cy, 24);
        sleep(70);
        device.swipe(cx - dx, cy, cx + dx, cy, 24);
        sleep(70);
        device.swipe(cx, cy + dy, cx, cy - dy, 24);
        sleep(70);
        device.swipe(cx, cy - dy, cx, cy + dy, 24);
        sleep(70);
    }

    private void panSatelliteTransitionBand() throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int left = Math.max(96, (int) (width * 0.22f));
        int right = Math.min(width - 96, (int) (width * 0.58f));
        int top = Math.max(180, (int) (height * 0.32f));
        int middle = Math.max(220, (int) (height * 0.46f));
        int bottom = Math.min(height - 240, (int) (height * 0.60f));
        device.swipe(right, middle, left, middle, 34);
        sleep(90);
        device.swipe(left, middle, right, middle, 34);
        sleep(90);
        device.swipe(right, bottom, left, top, 38);
        sleep(90);
        device.swipe(left, top, right, bottom, 38);
        sleep(90);
    }

    private void clearPerfCounters() throws Exception {
        runShell("logcat -c");
        runShell("dumpsys gfxinfo " + PACKAGE_NAME + " reset");
    }

    private boolean waitForFlightAlertForeground(long timeoutMs) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            acceptFlightAlertPermissionsIfPresent();
            if (isFlightAlertForeground()) return true;
            sleep(120);
        }
        return false;
    }

    private void requireFlightAlertForeground() throws Exception {
        assertTrue("Refusing to run gestures outside Flight Alert. Foreground was:\n" + foregroundDiagnostic(), isFlightAlertForeground());
    }

    private boolean isFlightAlertForeground() throws Exception {
        String foreground = foregroundDiagnostic();
        String fullActivityLine = PACKAGE_NAME + "/" + PACKAGE_NAME + ".MainActivity";
        String shortActivityLine = PACKAGE_NAME + "/.MainActivity";
        String[] lines = foreground.split("\\r?\\n");
        for (String line : lines) {
            if ((line.contains("mCurrentFocus=") ||
                    line.contains("mFocusedApp=") ||
                    line.contains("topResumedActivity=") ||
                    line.contains("mResumedActivity:") ||
                    line.contains("ResumedActivity:")) &&
                    (line.contains(fullActivityLine) || line.contains(shortActivityLine))) {
                return true;
            }
        }
        return false;
    }

    private String foregroundDiagnostic() throws Exception {
        return runShell("dumpsys activity activities") + "\n" + runShell("dumpsys window windows");
    }

    private void captureActiveDisplay(String fileName) throws Exception {
        runShell("screencap -d 4630946481096930692 -p /sdcard/" + fileName);
    }

    private void scheduleActiveDisplayCapture(String fileName, long delayMs) throws Exception {
        Thread thread = new Thread(() -> {
            SystemClock.sleep(delayMs);
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            device.takeScreenshot(new File("/sdcard/Download/" + fileName));
        }, "flightalert-screencap-" + fileName);
        scheduledCaptureThreads.add(thread);
        thread.start();
    }

    private void waitForScheduledCaptures() throws InterruptedException {
        for (Thread thread : scheduledCaptureThreads) {
            thread.join(4000L);
        }
        scheduledCaptureThreads.clear();
    }

    private void capturePerfArtifacts(String testName) throws Exception {
        writeTextArtifact("flightalert-perf-" + testName + "-target.txt", currentPerfTargetDescription());
        writeShellArtifact("flightalert-perf-" + testName + "-gfxinfo.txt", "dumpsys gfxinfo " + PACKAGE_NAME);
        writeShellArtifact("flightalert-perf-" + testName + "-framestats.txt", "dumpsys gfxinfo " + PACKAGE_NAME + " framestats");
        writeShellArtifact("flightalert-perf-" + testName + "-activity.txt", "dumpsys activity activities");
        writeShellArtifact("flightalert-perf-" + testName + "-logcat.txt", "logcat -d -t 3000");
    }

    private String currentPerfTargetDescription() {
        MajorTrafficCity city = currentPerfCity;
        StringBuilder builder = new StringBuilder();
        builder.append("city=").append(city == null ? "unknown" : city.name).append('\n');
        builder.append("lat=").append(city == null ? "unknown" : city.lat).append('\n');
        builder.append("lon=").append(city == null ? "unknown" : city.lon).append('\n');
        builder.append("last_launch_zoom=").append(currentPerfZoom).append('\n');
        builder.append("map_source=").append(currentPerfMapSource).append('\n');
        builder.append("skip_chrome=").append(currentPerfSkipChrome).append('\n');
        builder.append("skip_top_status=").append(currentPerfSkipTopStatus).append('\n');
        builder.append("skip_controls=").append(currentPerfSkipControls).append('\n');
        builder.append("skip_traffic_panel=").append(currentPerfSkipTrafficPanel).append('\n');
        builder.append("skip_traffic=").append(currentPerfSkipTraffic).append('\n');
        builder.append("traffic_detail_timing=").append(currentPerfTrafficDetailTiming).append('\n');
        builder.append("focus=").append(centeredPerfGestureFocus ? "center-map" : "open-map").append('\n');
        builder.append("scale_bands=continent,country\n");
        builder.append("country_phase_note=country launch zoom 5.4 intentionally pinches closed first, so active/quick screenshots can show country-to-global stress frames; do not treat those frames as pure city-centered country visual proof.\n");
        return builder.toString();
    }

    private boolean instrumentationBooleanArgument(String name) {
        String value = InstrumentationRegistry.getArguments().getString(name);
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    private void writeShellArtifact(String fileName, String command) throws IOException {
        writeTextArtifact(fileName, runShell(command));
    }

    private void writeTextArtifact(String fileName, String value) throws IOException {
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        IOException durableFailure = null;
        try {
            File file = writeTextFile(new File("/sdcard/Download"), fileName, value);
            System.out.println("Wrote artifact: " + file.getAbsolutePath());
            return;
        } catch (IOException e) {
            durableFailure = e;
        }
        try {
            File file = writeTextFile(new File("/sdcard"), fileName, value);
            System.out.println("Wrote artifact: " + file.getAbsolutePath());
            return;
        } catch (IOException e) {
            durableFailure.addSuppressed(e);
        }

        IOException appFailure = null;
        File externalDirectory = targetContext.getExternalFilesDir(null);
        if (externalDirectory != null) {
            try {
                File file = writeTextFile(externalDirectory, fileName, value);
                System.out.println("Wrote artifact: " + file.getAbsolutePath());
                return;
            } catch (IOException e) {
                appFailure = e;
            }
        } else {
            appFailure = new IOException("External files directory is unavailable");
        }

        try {
            File file = writeTextFile(targetContext.getFilesDir(), fileName, value);
            System.out.println("Wrote artifact: " + file.getAbsolutePath());
        } catch (IOException fallbackFailure) {
            if (durableFailure != null) {
                fallbackFailure.addSuppressed(durableFailure);
            }
            if (appFailure != null) {
                fallbackFailure.addSuppressed(appFailure);
            }
            throw fallbackFailure;
        }
    }

    private File writeTextFile(File directory, String fileName, String value) throws IOException {
        if (directory == null) {
            throw new IOException("Artifact directory is unavailable");
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Failed to create artifact directory: " + directory.getAbsolutePath());
        }
        File file = new File(directory, fileName);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes("UTF-8"));
        }
        return file;
    }

    private MajorTrafficCity randomMajorTrafficCity() {
        MajorTrafficCity fixed = fixedCityFromArguments(MAJOR_TRAFFIC_CITIES);
        return fixed != null ? fixed : randomCityFrom(MAJOR_TRAFFIC_CITIES);
    }

    private MajorTrafficCity randomInlandTrafficCity() {
        MajorTrafficCity fixed = fixedCityFromArguments(INLAND_TRAFFIC_CITIES);
        return fixed != null ? fixed : randomCityFrom(INLAND_TRAFFIC_CITIES);
    }

    private MajorTrafficCity fixedCityFromArguments(MajorTrafficCity[] cities) {
        String requested = InstrumentationRegistry.getArguments().getString("targetCity");
        if (requested == null || requested.trim().isEmpty()) return null;
        String key = normalizeCityName(requested);
        for (MajorTrafficCity city : cities) {
            if (normalizeCityName(city.name).equals(key)) return city;
        }
        if ("nyc".equals(key)) return findCity(cities, "New York City");
        if ("dfw".equals(key)) return findCity(cities, "Dallas-Fort Worth");
        if ("la".equals(key)) return findCity(cities, "Los Angeles");
        return null;
    }

    private MajorTrafficCity findCity(MajorTrafficCity[] cities, String name) {
        String key = normalizeCityName(name);
        for (MajorTrafficCity city : cities) {
            if (normalizeCityName(city.name).equals(key)) return city;
        }
        return null;
    }

    private String normalizeCityName(String value) {
        return value.toLowerCase(java.util.Locale.US).replaceAll("[^a-z0-9]", "");
    }

    private MajorTrafficCity randomCityFrom(MajorTrafficCity[] cities) {
        long seed = System.currentTimeMillis() ^ System.nanoTime();
        int index = (int) Math.abs(seed % cities.length);
        return cities[index];
    }

    private int trafficFocusY(int height) {
        return Math.max(560, Math.min(height - 760, (int) (height * 0.50f)));
    }

    private String runShell(String command) throws IOException {
        ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand(command);
        try (FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        } finally {
            descriptor.close();
        }
    }

    private void sleep(long ms) {
        SystemClock.sleep(ms);
    }

    private static final class MajorTrafficCity {
        final String name;
        final double lat;
        final double lon;

        MajorTrafficCity(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }
}
