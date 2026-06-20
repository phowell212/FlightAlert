package com.flightalert.perf;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
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
    private static final String PERF_PHASE_LOG_TAG = "FlightAlertPerfPhase";
    private static final String PERF_BAND_NOT_APPLICABLE = "not_applicable";
    private static final long TRAFFIC_LOAD_WAIT_MS = 9000L;
    private static final int QUICK_PINCH_SPEED_PX_PER_SEC = 14000;
    private static final int SMOOTH_PINCH_SPEED_PX_PER_SEC = 3200;
    private static final int HUMAN_TRANSITION_PINCH_STEPS = 24;
    private static final long HUMAN_TRANSITION_PINCH_STEP_DELAY_MS = 14L;
    private static final int PAN_STEPS = 44;
    private static final int SATELLITE_FAST_ZOOM_OUT_STEPS = 4;
    private static final int SATELLITE_FAST_ZOOM_OUT_FULL_POWER_STEPS = 3;
    private static final float SATELLITE_FAST_ZOOM_OUT_FINISH_PERCENT = 0.46f;
    private static final double COUNTRY_CONTINUITY_CONTINENT_ZOOM = 4.25;
    private static final double COUNTRY_CONTINUITY_COUNTRY_ZOOM = 5.4;
    private static final double COUNTRY_CONTINUITY_REGIONAL_100_MI_ZOOM = 7.55;
    private static final String COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND = "regional100mi";
    private static final String COUNTRY_CONTINUITY_FULL_RANGE_BAND = "fullRange";
    private static final int COUNTRY_CONTINUITY_FULL_RANGE_CYCLES = 1;
    private static final int COUNTRY_CONTINUITY_FULL_RANGE_STEPS_PER_LEG = 7;
    private static final int COUNTRY_CONTINUITY_FULL_RANGE_PREROLL_CLOSES = 0;
    private static final float MAP_FOCUS_SAFE_INSET_DP = 96f;
    private static final MajorTrafficCity[] MAJOR_TRAFFIC_CITIES = new MajorTrafficCity[] {
            new MajorTrafficCity("Dallas-Fort Worth", 32.90, -97.04),
            new MajorTrafficCity("Atlanta", 33.64, -84.43),
            new MajorTrafficCity("Denver", 39.86, -104.67),
            new MajorTrafficCity("Phoenix", 33.43, -112.01),
            new MajorTrafficCity("Las Vegas", 36.08, -115.15)
    };
    private static final MajorTrafficCity[] INLAND_TRAFFIC_CITIES = new MajorTrafficCity[] {
            new MajorTrafficCity("Dallas-Fort Worth", 32.90, -97.04),
            new MajorTrafficCity("Atlanta", 33.64, -84.43),
            new MajorTrafficCity("Denver", 39.86, -104.67),
            new MajorTrafficCity("Phoenix", 33.43, -112.01),
            new MajorTrafficCity("Las Vegas", 36.08, -115.15)
    };
    private static final MajorTrafficCity[] FULL_RANGE_LAND_SAFE_TRAFFIC_CITIES = new MajorTrafficCity[] {
            new MajorTrafficCity("Dallas-Fort Worth", 32.90, -97.04),
            new MajorTrafficCity("Atlanta", 33.64, -84.43),
            new MajorTrafficCity("Denver", 39.86, -104.67),
            new MajorTrafficCity("Phoenix", 33.43, -112.01),
            new MajorTrafficCity("Las Vegas", 36.08, -115.15)
    };
    private final java.util.ArrayList<Thread> scheduledCaptureThreads = new java.util.ArrayList<>();
    private MajorTrafficCity currentPerfCity = null;
    private double currentPerfZoom = Double.NaN;
    private String currentPerfMapSource = "";
    private String currentPerfRunId = "";
    private boolean currentPerfSkipChrome = false;
    private boolean currentPerfSkipTopStatus = false;
    private boolean currentPerfSkipControls = false;
    private boolean currentPerfSkipTrafficPanel = false;
    private boolean currentPerfSkipTraffic = false;
    private boolean currentPerfTrafficDetailTiming = false;
    private String currentPerfPhaseName = PERF_BAND_NOT_APPLICABLE;
    private String currentPerfPhaseZoomPlan = "";
    private String currentPerfPhaseGesturePlan = "";
    private String currentPerfLastGestureAnchor = "unavailable";
    private String currentPerfLastGestureBounds = "unavailable";
    private final StringBuilder currentPerfGestureTrace = new StringBuilder();
    private float currentPerfFocusXFraction = Float.NaN;
    private float currentPerfFocusYFraction = Float.NaN;
    private boolean centeredPerfGestureFocus = false;
    private boolean currentPerfFocusOpenMap = false;
    private boolean currentPerfTargetOptimizerSafe = true;

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
            scaleAwareQuickPinchClose(app, "country");
            sleep(90);
            scaleAwareQuickPinchOpen(app, "country");
            sleep(140);
            scaleAwareQuickPinchClose(app, "country");
            sleep(90);
            scaleAwareQuickPinchOpen(app, "country");
            sleep(160);
            smallPanOverTraffic(app, "country");
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

    @Test
    public void satelliteFastZoomOutTileLoad() throws Exception {
        runSatelliteFastZoomOutTileLoad("satelliteFastZoomOutTileLoad", true);
    }

    @Test
    public void satelliteFastZoomOutTileLoadPerf() throws Exception {
        runSatelliteFastZoomOutTileLoad("satelliteFastZoomOutTileLoadPerf", false);
    }

    @Test
    public void streetFastZoomOutTileLoad() throws Exception {
        runStreetFastZoomOutTileLoad("streetFastZoomOutTileLoad", true);
    }

    @Test
    public void streetFastZoomOutTileLoadPerf() throws Exception {
        runStreetFastZoomOutTileLoad("streetFastZoomOutTileLoadPerf", false);
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
        MajorTrafficCity city = new MajorTrafficCity("Dallas-Fort Worth", 32.90, -97.04);
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
        MajorTrafficCity city = randomFullRangeLandSafeTrafficCity();
        boolean skipChrome = instrumentationBooleanArgument("skipChrome");
        boolean skipTraffic = instrumentationBooleanArgument("skipTraffic");
        centeredPerfGestureFocus = false;
        try {
            UiObject2 app = startAppAtMajorTraffic(city, COUNTRY_CONTINUITY_CONTINENT_ZOOM, mapSource, skipChrome, skipTraffic, true);
            sleep(TRAFFIC_LOAD_WAIT_MS);
            if (captureScreenshots) {
                captureActiveDisplay("flightalert-perf-" + artifactName + "-continent-rest.png");
            }
            setPerfPhaseMetadata(
                    "continent",
                    "launch_zoom=" + COUNTRY_CONTINUITY_CONTINENT_ZOOM + "; app_focus_open_map=true",
                    "app camera and gestures share the unobstructed open-map focus; smooth_cycles=3; first_direction=zoom_in; quick_cycles=3; pan_after_quick=true"
            );
            clearPerfCounters();
            markPerfPhase(artifactName, "continent", "continent", "phase_start");

            runZoomContinuityPhase(app, artifactName, "continent", 3, true, captureScreenshots);
            if (captureScreenshots) {
                waitForScheduledCaptures();
            }
            requireFlightAlertForeground();
            markPerfPhase(artifactName, "continent", "continent", "phase_capture_artifacts");
            capturePerfArtifacts(artifactName + "-continent");

            app = startAppAtMajorTraffic(city, COUNTRY_CONTINUITY_COUNTRY_ZOOM, mapSource, skipChrome, skipTraffic, true);
            sleep(TRAFFIC_LOAD_WAIT_MS);
            if (captureScreenshots) {
                captureActiveDisplay("flightalert-perf-" + artifactName + "-country-rest.png");
            }
            setPerfPhaseMetadata(
                    "country",
                    "launch_zoom=" + COUNTRY_CONTINUITY_COUNTRY_ZOOM + "; app_focus_open_map=true",
                    "app camera and gestures share the unobstructed open-map focus; smooth_cycles=5; first_direction=zoom_out; quick_cycles=3; pan_after_quick=true"
            );
            clearPerfCounters();
            markPerfPhase(artifactName, "country", "country", "phase_start");
            runZoomContinuityPhase(app, artifactName, "country", 5, false, captureScreenshots);

            sleep(900);
            if (captureScreenshots) {
                waitForScheduledCaptures();
            }
            requireFlightAlertForeground();
            markPerfPhase(artifactName, "country", "country", "phase_capture_artifacts");
            capturePerfArtifacts(artifactName + "-country");

            app = startAppAtMajorTraffic(city, COUNTRY_CONTINUITY_REGIONAL_100_MI_ZOOM, mapSource, skipChrome, skipTraffic, true);
            sleep(TRAFFIC_LOAD_WAIT_MS);
            if (captureScreenshots) {
                captureActiveDisplay("flightalert-perf-" + artifactName + "-" + COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND + "-rest.png");
            }
            setPerfPhaseMetadata(
                    COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND,
                    "launch_zoom=" + COUNTRY_CONTINUITY_REGIONAL_100_MI_ZOOM + "; app_focus_open_map=true",
                    "app camera and gestures share the unobstructed open-map focus; smooth_cycles=3; first_direction=zoom_in; quick_cycles=3; pan_after_quick=true"
            );
            clearPerfCounters();
            markPerfPhase(artifactName, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, "phase_start");
            runZoomContinuityPhase(app, artifactName, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, 3, true, captureScreenshots);

            sleep(900);
            if (captureScreenshots) {
                waitForScheduledCaptures();
            }
            requireFlightAlertForeground();
            markPerfPhase(artifactName, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, "phase_capture_artifacts");
            capturePerfArtifacts(artifactName + "-" + COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND);

            app = startAppAtMajorTraffic(city, COUNTRY_CONTINUITY_CONTINENT_ZOOM, mapSource, skipChrome, skipTraffic, true);
            sleep(TRAFFIC_LOAD_WAIT_MS);
            if (captureScreenshots) {
                captureActiveDisplay("flightalert-perf-" + artifactName + "-" + COUNTRY_CONTINUITY_FULL_RANGE_BAND + "-rest.png");
            }
            setPerfPhaseMetadata(
                    COUNTRY_CONTINUITY_FULL_RANGE_BAND,
                    "start_from_launch_zoom=" + COUNTRY_CONTINUITY_CONTINENT_ZOOM +
                            "; app_focus_open_map=true" +
                            "; preroll_close_pinches=" + COUNTRY_CONTINUITY_FULL_RANGE_PREROLL_CLOSES +
                            "; cycles=" + COUNTRY_CONTINUITY_FULL_RANGE_CYCLES +
                            "; steps_per_in_out_leg=" + COUNTRY_CONTINUITY_FULL_RANGE_STEPS_PER_LEG +
                            "; planned_band_order=continent>country>" + COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND + ">close",
                    "app camera and gestures share the unobstructed open-map focus; anchored smooth pinches; bounded closed elliptical pan checkpoints; one bounded elliptical pinch while panning per leg"
            );
            clearPerfCounters();
            markPerfPhase(artifactName, COUNTRY_CONTINUITY_FULL_RANGE_BAND, COUNTRY_CONTINUITY_FULL_RANGE_BAND, "phase_start");
            runFullRangeZoomInvestigation(app, artifactName, captureScreenshots);

            sleep(900);
            if (captureScreenshots) {
                waitForScheduledCaptures();
            }
            requireFlightAlertForeground();
            markPerfPhase(artifactName, COUNTRY_CONTINUITY_FULL_RANGE_BAND, COUNTRY_CONTINUITY_FULL_RANGE_BAND, "phase_capture_artifacts");
            capturePerfArtifacts(artifactName + "-" + COUNTRY_CONTINUITY_FULL_RANGE_BAND);
            if (captureScreenshots) {
                captureActiveDisplay("flightalert-perf-" + artifactName + ".png");
            }
        } finally {
            centeredPerfGestureFocus = false;
        }
    }

    private void runZoomContinuityPhase(UiObject2 app, String artifactName, String scaleName, int smoothCycles, boolean zoomInFirst, boolean captureScreenshots) throws Exception {
        String firstDirection = zoomInFirst ? "zoom_in" : "zoom_out";
        String reverseDirection = zoomInFirst ? "zoom_out" : "zoom_in";
        markPerfPhase(artifactName, scaleName, scaleName, "smooth_a_start direction=" + firstDirection + " cycles=" + smoothCycles);
        for (int i = 0; i < 5; i++) {
            if (i >= smoothCycles) break;
            if (captureScreenshots && i == 0) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-" + scaleName + "-motion-start-a.png", 120);
            if (captureScreenshots && i == Math.max(1, smoothCycles / 2)) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-" + scaleName + "-motion-active-a.png", 120);
            markPerfPhase(artifactName, scaleName, scaleName, "smooth_a_step=" + (i + 1) + "/" + smoothCycles + " direction=" + firstDirection);
            if (zoomInFirst) {
                scaleAwareSmoothPinchOpen(app, scaleName);
            } else {
                scaleAwareSmoothPinchClose(app, scaleName);
            }
            sleep(150);
        }
        sleep(300);
        markPerfPhase(artifactName, scaleName, scaleName, "smooth_b_start direction=" + reverseDirection + " cycles=" + smoothCycles);
        for (int i = 0; i < 5; i++) {
            if (i >= smoothCycles) break;
            if (captureScreenshots && i == 0) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-" + scaleName + "-reverse-start-b.png", 120);
            if (captureScreenshots && i == Math.max(1, smoothCycles / 2)) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-" + scaleName + "-motion-active-b.png", 120);
            markPerfPhase(artifactName, scaleName, scaleName, "smooth_b_step=" + (i + 1) + "/" + smoothCycles + " direction=" + reverseDirection);
            if (zoomInFirst) {
                scaleAwareSmoothPinchClose(app, scaleName);
            } else {
                scaleAwareSmoothPinchOpen(app, scaleName);
            }
            sleep(150);
        }
        sleep(250);
        markPerfPhase(artifactName, scaleName, scaleName, "quick_cycles_start cycles=3 direction=zoom_out_then_in");
        for (int cycle = 0; cycle < 3; cycle++) {
            if (captureScreenshots && cycle == 0) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-" + scaleName + "-quick-start.png", 80);
            if (captureScreenshots && cycle == 1) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-" + scaleName + "-quick-active.png", 80);
            markPerfPhase(artifactName, scaleName, scaleName, "quick_cycle=" + (cycle + 1) + "/3 direction=zoom_out_then_in");
            scaleAwareQuickPinchClose(app, scaleName);
            sleep(85);
            scaleAwareQuickPinchOpen(app, scaleName);
            sleep(95);
        }
        markPerfPhase(artifactName, scaleName, scaleName, "pan_checkpoint after_quick_cycles");
        smallPanOverTraffic(app, scaleName);
        requireFlightAlertForeground();
    }

    private void runFullRangeZoomInvestigation(UiObject2 app, String artifactName, boolean captureScreenshots) throws Exception {
        for (int i = 0; i < COUNTRY_CONTINUITY_FULL_RANGE_PREROLL_CLOSES; i++) {
            if (captureScreenshots && i == 0) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-" + COUNTRY_CONTINUITY_FULL_RANGE_BAND + "-preroll-start.png", 120);
            markPerfPhase(artifactName, COUNTRY_CONTINUITY_FULL_RANGE_BAND, "country_to_continent", "preroll_close_step=" + (i + 1) + "/" + COUNTRY_CONTINUITY_FULL_RANGE_PREROLL_CLOSES);
            smoothPinchClose(app);
            sleep(145);
            if (i == 2) {
                markPerfPhase(artifactName, COUNTRY_CONTINUITY_FULL_RANGE_BAND, "country_to_continent", "pan_checkpoint preroll");
                smallPanOverTraffic(app, "country_to_continent");
            }
        }
        sleep(240);

        for (int cycle = 0; cycle < COUNTRY_CONTINUITY_FULL_RANGE_CYCLES; cycle++) {
            runFullRangeZoomLeg(app, artifactName, cycle, true, captureScreenshots);
            sleep(220);
            runFullRangeZoomLeg(app, artifactName, cycle, false, captureScreenshots);
            sleep(220);
        }
        requireFlightAlertForeground();
    }

    private void runFullRangeZoomLeg(UiObject2 app, String artifactName, int cycle, boolean zoomIn, boolean captureScreenshots) throws Exception {
        String directionName = zoomIn ? "zoom-in" : "zoom-out";
        for (int step = 0; step < COUNTRY_CONTINUITY_FULL_RANGE_STEPS_PER_LEG; step++) {
            String plannedBand = plannedFullRangeBand(zoomIn, step);
            if (captureScreenshots && cycle == 0 && step == 0) {
                scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-" + COUNTRY_CONTINUITY_FULL_RANGE_BAND + "-" + directionName + "-start.png", 120);
            }
            if (captureScreenshots && cycle == 0 && step == 4) {
                scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-" + COUNTRY_CONTINUITY_FULL_RANGE_BAND + "-" + directionName + "-active.png", 120);
            }
            boolean driftWhilePanning = step == 3;
            markPerfPhase(
                    artifactName,
                    COUNTRY_CONTINUITY_FULL_RANGE_BAND,
                    plannedBand,
                    "cycle=" + (cycle + 1) + "/" + COUNTRY_CONTINUITY_FULL_RANGE_CYCLES +
                            " direction=" + directionName +
                            " step=" + (step + 1) + "/" + COUNTRY_CONTINUITY_FULL_RANGE_STEPS_PER_LEG +
                            " gesture=" + (driftWhilePanning ? "bounded_elliptical_pinch_pan" : "anchored_pinch")
            );
            if (driftWhilePanning) {
                if (zoomIn) {
                    driftingPinchOpen(app, plannedBand);
                } else {
                    driftingPinchClose(app, plannedBand);
                }
            } else if (zoomIn) {
                scaleAwareSmoothPinchOpen(app, plannedBand);
            } else {
                scaleAwareSmoothPinchClose(app, plannedBand);
            }
            sleep(145);
            if (cycle == 0 && (step == 1 || step == 5)) {
                markPerfPhase(artifactName, COUNTRY_CONTINUITY_FULL_RANGE_BAND, plannedBand, "pan_checkpoint direction=" + directionName + " step=" + (step + 1));
                smallPanOverTraffic(app, plannedBand);
            }
        }
    }

    private String plannedFullRangeBand(boolean zoomIn, int step) {
        if (zoomIn) {
            if (step < 2) return "continent";
            if (step < 4) return "country";
            if (step < 6) return COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND;
            return "close";
        }
        if (step < 2) return "close";
        if (step < 4) return COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND;
        if (step < 6) return "country";
        return "continent";
    }

    private void runCloseScaleZoomContinuity(String mapSource, String artifactName, boolean captureScreenshots) throws Exception {
        MajorTrafficCity city = randomFullRangeLandSafeTrafficCity();
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
        MajorTrafficCity city = randomFullRangeLandSafeTrafficCity();
        boolean skipChrome = instrumentationBooleanArgument("skipChrome");
        boolean skipTraffic = instrumentationBooleanArgument("skipTraffic");
        UiObject2 app = startAppAtMajorTraffic(city, 5.88, "SATELLITE", skipChrome, skipTraffic);
        sleep(5200);
        if (captureScreenshots) {
            captureActiveDisplay("flightalert-perf-" + artifactName + "-rest.png");
        }
        clearPerfCounters();

        if (captureScreenshots) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-pan-start.png", 120);
        panSatelliteTransitionBand(app);
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
        panSatelliteTransitionBand(app);
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

    private void runSatelliteFastZoomOutTileLoad(String artifactName, boolean captureScreenshots) throws Exception {
        runFastZoomOutTileLoad("satellite", "SATELLITE", artifactName, captureScreenshots);
    }

    private void runStreetFastZoomOutTileLoad(String artifactName, boolean captureScreenshots) throws Exception {
        runFastZoomOutTileLoad("street", "STREET", artifactName, captureScreenshots);
    }

    private void runFastZoomOutTileLoad(String mapLabel, String mapSource, String artifactName, boolean captureScreenshots) throws Exception {
        MajorTrafficCity city = randomFullRangeLandSafeTrafficCity();
        boolean skipChrome = instrumentationBooleanArgument("skipChrome");
        boolean skipTraffic = instrumentationBooleanArgument("skipTraffic");
        UiObject2 app = startAppAtMajorTraffic(city, 13.2, mapSource, skipChrome, skipTraffic, true);
        sleep(6500);
        if (captureScreenshots) {
            captureActiveDisplay("flightalert-perf-" + artifactName + "-close-rest.png");
        }
        setPerfPhaseMetadata(
                "fastZoomOutTileLoad",
                "map_source=" + mapSource + "; launch_zoom=13.2; app_focus_open_map=true; " + SATELLITE_FAST_ZOOM_OUT_STEPS + " fast zoom-out pinches from close detail toward country/wide scale; first " + SATELLITE_FAST_ZOOM_OUT_FULL_POWER_STEPS + " are full-power and the remaining steps use a controlled finish to keep route proof valid; post-zoom recovery window captures tile load catch-up",
                "anchored human-style quick pinch-close gestures over inland airport traffic; no keyboard zoom; recovery screenshots at 250/650/1200 ms after final zoom-out"
        );
        clearPerfCounters();
        markPerfPhase(artifactName, "fastZoomOutTileLoad", "close", "phase_start " + mapLabel + "_close_tiles_loaded");

        for (int step = 0; step < SATELLITE_FAST_ZOOM_OUT_STEPS; step++) {
            String plannedBand = plannedFastZoomOutBand(step);
            markPerfPhase(
                    artifactName,
                    "fastZoomOutTileLoad",
                    plannedBand,
                    "fast_zoom_out_step=" + (step + 1) + "/" + SATELLITE_FAST_ZOOM_OUT_STEPS + " gesture=" + fastZoomOutGestureName(step)
            );
            if (captureScreenshots && step == 0) {
                scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-zoom-out-start.png", 90);
            }
            if (captureScreenshots && step == 2) {
                scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-zoom-out-mid.png", 90);
            }
            if (captureScreenshots && step == SATELLITE_FAST_ZOOM_OUT_STEPS - 1) {
                scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-zoom-out-wide.png", 90);
            }
            fastSatelliteZoomOutStep(app, step);
            sleep(65);
        }

        markPerfPhase(artifactName, "fastZoomOutTileLoad", "country", "recovery_window_start " + mapLabel + "_after_fast_zoom_out");
        if (captureScreenshots) {
            scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-recovery-250ms.png", 250);
            scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-recovery-650ms.png", 650);
            scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-recovery-1200ms.png", 1200);
        }
        sleep(1500);
        markPerfPhase(artifactName, "fastZoomOutTileLoad", "country", "recovery_window_end " + mapLabel);
        if (captureScreenshots) {
            waitForScheduledCaptures();
        }
        requireFlightAlertForeground();
        capturePerfArtifacts(artifactName);
        if (captureScreenshots) {
            captureActiveDisplay("flightalert-perf-" + artifactName + ".png");
        }
    }

    private String plannedFastZoomOutBand(int step) {
        if (step < 2) return "close";
        if (step < 3) return COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND;
        if (step < 5) return "country";
        return "continent";
    }

    private void fastSatelliteZoomOutStep(UiObject2 app, int step) {
        if (step < SATELLITE_FAST_ZOOM_OUT_FULL_POWER_STEPS) {
            quickPinchClose(app);
        } else {
            anchoredPinch(app, SATELLITE_FAST_ZOOM_OUT_FINISH_PERCENT, QUICK_PINCH_SPEED_PX_PER_SEC, false);
        }
    }

    private String fastZoomOutGestureName(int step) {
        if (step < SATELLITE_FAST_ZOOM_OUT_FULL_POWER_STEPS) return "anchored_full_power_quick_pinch_close";
        return "anchored_controlled_quick_pinch_close_percent_" + SATELLITE_FAST_ZOOM_OUT_FINISH_PERCENT;
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
            panMajorCityCorridor(app, PAN_STEPS);
            sleep(180);
            smoothPinchOpen(app);
            sleep(260);
            requireFlightAlertForeground();
        }
        panMajorCityCorridor(app, PAN_STEPS);
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
        currentPerfRunId = "run-" + SystemClock.elapsedRealtime() + "-" + normalizeCityName(city.name) + "-" + Math.round(zoom * 100.0);
        currentPerfSkipChrome = skipChrome;
        currentPerfSkipTraffic = skipTraffic;
        currentPerfFocusOpenMap = focusOpenMap;
        currentPerfLastGestureAnchor = "unavailable";
        currentPerfLastGestureBounds = "unavailable";
        currentPerfGestureTrace.setLength(0);
        currentPerfFocusXFraction = Float.NaN;
        currentPerfFocusYFraction = Float.NaN;
        currentPerfSkipTopStatus = instrumentationBooleanArgument("skipTopStatus");
        currentPerfSkipControls = instrumentationBooleanArgument("skipControls");
        currentPerfSkipTrafficPanel = instrumentationBooleanArgument("skipTrafficPanel");
        boolean trafficDetailTiming = instrumentationBooleanArgument("trafficDetailTiming");
        Boolean mapRoads = instrumentationOptionalBooleanArgument("mapRoads");
        Boolean mapBorders = instrumentationOptionalBooleanArgument("mapBorders");
        requireCompleteMapReferenceArguments(mapRoads, mapBorders);
        currentPerfTrafficDetailTiming = trafficDetailTiming;
        currentPerfTargetOptimizerSafe = isOptimizerSafeTrafficCity(city);
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        UiDevice device = UiDevice.getInstance(instrumentation);
        android.graphics.Rect displayBounds = new android.graphics.Rect(0, 0, device.getDisplayWidth(), device.getDisplayHeight());
        android.graphics.Point launchFocus = mapFocusForBounds(displayBounds);
        currentPerfFocusXFraction = launchFocus.x / (float) Math.max(1, displayBounds.width());
        currentPerfFocusYFraction = launchFocus.y / (float) Math.max(1, displayBounds.height());
        grantFlightAlertPermissions();
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("com.flightalert.PERF_LAT", Double.toString(city.lat))
                .putExtra("com.flightalert.PERF_LON", Double.toString(city.lon))
                .putExtra("com.flightalert.PERF_ZOOM", Double.toString(zoom))
                .putExtra("com.flightalert.PERF_RUN_ID", currentPerfRunId)
                .putExtra("com.flightalert.PERF_FOCUS_X_FRACTION", Float.toString(currentPerfFocusXFraction))
                .putExtra("com.flightalert.PERF_FOCUS_Y_FRACTION", Float.toString(currentPerfFocusYFraction))
                .putExtra("com.flightalert.PERF_CLEAR_SELECTION", true)
                .putExtra("com.flightalert.PERF_FOCUS_OPEN_MAP", focusOpenMap);
        if (mapRoads != null) {
            intent.putExtra("com.flightalert.PERF_MAP_ROADS_ENABLED", mapRoads.booleanValue());
        }
        if (mapBorders != null) {
            intent.putExtra("com.flightalert.PERF_MAP_BORDERS_ENABLED", mapBorders.booleanValue());
        }
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

    private void requireCompleteMapReferenceArguments(Boolean mapRoads, Boolean mapBorders) {
        if ((mapRoads == null) != (mapBorders == null)) {
            throw new IllegalArgumentException("Provide both mapRoads and mapBorders, or neither, so split map-label tests cannot inherit half their state from device preferences.");
        }
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
                .append(" --es com.flightalert.PERF_RUN_ID ").append(currentPerfRunId)
                .append(" --es com.flightalert.PERF_FOCUS_X_FRACTION ").append(currentPerfFocusXFraction)
                .append(" --es com.flightalert.PERF_FOCUS_Y_FRACTION ").append(currentPerfFocusYFraction)
                .append(" --ez com.flightalert.PERF_CLEAR_SELECTION true")
                .append(" --ez com.flightalert.PERF_FOCUS_OPEN_MAP ").append(focusOpenMap ? "true" : "false");
        Boolean mapRoads = instrumentationOptionalBooleanArgument("mapRoads");
        Boolean mapBorders = instrumentationOptionalBooleanArgument("mapBorders");
        requireCompleteMapReferenceArguments(mapRoads, mapBorders);
        if (mapRoads != null) {
            command.append(" --ez com.flightalert.PERF_MAP_ROADS_ENABLED ").append(mapRoads.booleanValue() ? "true" : "false");
        }
        if (mapBorders != null) {
            command.append(" --ez com.flightalert.PERF_MAP_BORDERS_ENABLED ").append(mapBorders.booleanValue() ? "true" : "false");
        }
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

    private void scaleAwareQuickPinchOpen(UiObject2 app, String scaleBand) {
        anchoredPinch(app, pinchPercentForScaleBand(scaleBand, true, true), QUICK_PINCH_SPEED_PX_PER_SEC, true);
    }

    private void scaleAwareQuickPinchClose(UiObject2 app, String scaleBand) {
        anchoredPinch(app, pinchPercentForScaleBand(scaleBand, true, false), QUICK_PINCH_SPEED_PX_PER_SEC, false);
    }

    private void smoothPinchOpen(UiObject2 app) {
        anchoredPinch(app, 0.64f, SMOOTH_PINCH_SPEED_PX_PER_SEC, true);
    }

    private void smoothPinchClose(UiObject2 app) {
        anchoredPinch(app, 0.64f, SMOOTH_PINCH_SPEED_PX_PER_SEC, false);
    }

    private void scaleAwareSmoothPinchOpen(UiObject2 app, String scaleBand) {
        anchoredPinch(app, pinchPercentForScaleBand(scaleBand, false, true), SMOOTH_PINCH_SPEED_PX_PER_SEC, true);
    }

    private void scaleAwareSmoothPinchClose(UiObject2 app, String scaleBand) {
        anchoredPinch(app, pinchPercentForScaleBand(scaleBand, false, false), SMOOTH_PINCH_SPEED_PX_PER_SEC, false);
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

    private void driftingPinchOpen(UiObject2 app, String scaleBand) {
        driftingPinchWithCadence(app, scaleBand, true, HUMAN_TRANSITION_PINCH_STEPS, HUMAN_TRANSITION_PINCH_STEP_DELAY_MS);
    }

    private void driftingPinchClose(UiObject2 app, String scaleBand) {
        driftingPinchWithCadence(app, scaleBand, false, HUMAN_TRANSITION_PINCH_STEPS, HUMAN_TRANSITION_PINCH_STEP_DELAY_MS);
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
        android.graphics.Rect mapBounds = safeMapGestureBounds(bounds);
        android.graphics.Point focus = anchoredMapFocus(app, bounds);
        float centerX = focus.x;
        float centerY = focus.y;
        float shortSide = Math.max(1f, Math.min(bounds.width(), bounds.height()));
        float innerRadius = Math.max(42f, shortSide * 0.055f);
        float outerRadius = boundedHorizontalPinchRadius(focus, mapBounds, Math.max(innerRadius + 48f, shortSide * percent * 0.42f), innerRadius);
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

    private void driftingPinchWithCadence(UiObject2 app, String scaleBand, boolean open, int steps, long stepDelayMs) {
        android.graphics.Rect bounds = app.getVisibleBounds();
        android.graphics.Rect mapBounds = safeMapGestureBounds(bounds);
        android.graphics.Point focus = anchoredMapFocus(app, bounds);
        float shortSide = Math.max(1f, Math.min(bounds.width(), bounds.height()));
        float innerRadius = Math.max(42f, shortSide * 0.055f);
        float percent = pinchPercentForScaleBand(scaleBand, false, open);
        float outerRadius = boundedHorizontalPinchRadius(focus, mapBounds, Math.max(innerRadius + 48f, shortSide * percent * 0.42f), innerRadius);
        float startRadius = open ? innerRadius : outerRadius;
        float endRadius = open ? outerRadius : innerRadius;
        int clampedSteps = Math.max(8, steps);
        float driftX = Math.min(Math.max(38f, bounds.width() * 0.055f), Math.max(24f, Math.min(focus.x - mapBounds.left - outerRadius, mapBounds.right - focus.x - outerRadius) - dp(12f)));
        float driftY = Math.min(Math.max(28f, bounds.height() * 0.035f), Math.max(20f, Math.min(focus.y - mapBounds.top, mapBounds.bottom - focus.y) - dp(12f)));
        long downTime = SystemClock.uptimeMillis();

        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[] {
                pointerProperties(0),
                pointerProperties(1)
        };
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[] {
                pointerCoords(focus.x - startRadius, focus.y),
                pointerCoords(focus.x + startRadius, focus.y)
        };
        injectMotion(downTime, downTime, MotionEvent.ACTION_DOWN, properties, coords, 1);
        injectMotion(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), properties, coords, 2);
        for (int step = 1; step <= clampedSteps; step++) {
            float t = step / (float) clampedSteps;
            float eased = t * t * (3f - 2f * t);
            float radius = startRadius + (endRadius - startRadius) * eased;
            float envelope = (float) Math.sin(Math.PI * eased);
            float angle = (float) (Math.PI * 2.0 * eased);
            float centerX = focus.x + envelope * driftX * (float) Math.cos(angle);
            float centerY = focus.y + envelope * driftY * (float) Math.sin(angle);
            centerX = Math.max(mapBounds.left + radius, Math.min(mapBounds.right - radius, centerX));
            centerY = Math.max(mapBounds.top + dp(12f), Math.min(mapBounds.bottom - dp(12f), centerY));
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

    private float pinchPercentForScaleBand(String scaleBand, boolean quick, boolean open) {
        String band = scaleBand == null ? "" : scaleBand.toLowerCase(java.util.Locale.US);
        if (band.contains("continent")) {
            if (!open) return quick ? 0.12f : 0.14f;
            return quick ? 0.24f : 0.30f;
        }
        if (band.contains("country")) {
            if (!open) return quick ? 0.18f : 0.22f;
            return quick ? 0.30f : 0.36f;
        }
        if (band.contains(COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND.toLowerCase(java.util.Locale.US))) {
            if (!open) return quick ? 0.30f : 0.32f;
            return quick ? 0.42f : 0.46f;
        }
        if (!open) return quick ? 0.26f : 0.28f;
        return quick ? 0.36f : 0.40f;
    }

    private int anchoredPinchSteps(float startRadius, float endRadius, int speedPxPerSecond) {
        float travel = Math.abs(endRadius - startRadius);
        float durationMs = travel * 1000f / Math.max(1, speedPxPerSecond);
        return Math.max(8, Math.min(42, Math.round(durationMs / 8f)));
    }

    private android.graphics.Point anchoredMapFocus(UiObject2 app, android.graphics.Rect bounds) {
        return mapFocusForBounds(bounds);
    }

    private android.graphics.Point mapFocusForBounds(android.graphics.Rect bounds) {
        android.graphics.Rect mapBounds = safeMapGestureBounds(bounds);
        if (centeredPerfGestureFocus) {
            android.graphics.Point focus = new android.graphics.Point(mapBounds.centerX(), mapBounds.centerY());
            recordGestureAnchor("screen_center", focus, mapBounds);
            return focus;
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
        focusX = Math.max(mapBounds.left, Math.min(mapBounds.right, bounds.left + focusX));
        focusY = Math.max(mapBounds.top, Math.min(mapBounds.bottom, bounds.top + focusY));
        android.graphics.Point focus = new android.graphics.Point(Math.round(focusX), Math.round(focusY));
        recordGestureAnchor("open_map", focus, mapBounds);
        return focus;
    }

    private void recordGestureAnchor(String mode, android.graphics.Point focus, android.graphics.Rect bounds) {
        currentPerfLastGestureAnchor = mode + "@x=" + focus.x + ",y=" + focus.y;
        currentPerfLastGestureBounds = "left=" + bounds.left + ",top=" + bounds.top + ",right=" + bounds.right + ",bottom=" + bounds.bottom;
        if (currentPerfGestureTrace.length() < 6144) {
            currentPerfGestureTrace
                    .append(mode)
                    .append(" x=").append(focus.x)
                    .append(" y=").append(focus.y)
                    .append(" boundsLeft=").append(bounds.left)
                    .append(" boundsTop=").append(bounds.top)
                    .append(" boundsRight=").append(bounds.right)
                    .append(" boundsBottom=").append(bounds.bottom)
                    .append('\n');
        }
    }

    private float boundedHorizontalPinchRadius(android.graphics.Point focus, android.graphics.Rect mapBounds, float desiredRadius, float innerRadius) {
        float available = Math.min(focus.x - mapBounds.left, mapBounds.right - focus.x) - dp(12f);
        float minimumUsefulRadius = innerRadius + dp(24f);
        if (available <= minimumUsefulRadius) {
            return minimumUsefulRadius;
        }
        return Math.max(minimumUsefulRadius, Math.min(desiredRadius, available));
    }

    private android.graphics.Rect safeMapGestureBounds(android.graphics.Rect bounds) {
        int inset = Math.round(dp(MAP_FOCUS_SAFE_INSET_DP));
        int left = bounds.left + inset;
        int right = bounds.right - inset;
        int top = bounds.top + inset;
        int bottom = bounds.bottom - inset;
        float width = Math.max(1f, bounds.width());
        float height = Math.max(1f, bounds.height());
        if (width > height * 1.15f) {
            float margin = dp(12f);
            float infoWidth = Math.min(dp(360f), Math.max(dp(300f), width * 0.32f));
            right = Math.min(right, Math.round(bounds.left + width - margin - infoWidth - dp(24f)));
        } else {
            float panelHeight = Math.min(dp(176f), Math.max(dp(152f), height * 0.24f));
            bottom = Math.min(bottom, Math.round(bounds.top + height - panelHeight - dp(36f)));
        }
        if (right <= left) {
            left = bounds.left + Math.round(width * 0.18f);
            right = bounds.left + Math.round(width * 0.72f);
        }
        if (bottom <= top) {
            top = bounds.top + Math.round(height * 0.24f);
            bottom = bounds.top + Math.round(height * 0.68f);
        }
        return new android.graphics.Rect(left, top, right, bottom);
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

    private void panMajorCityCorridor(UiObject2 app, int steps) throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        android.graphics.Rect appBounds = app.getVisibleBounds();
        int width = appBounds.width();
        int height = appBounds.height();
        android.graphics.Rect bounds = safeMapGestureBounds(appBounds);
        int cx = bounds.centerX();
        int cy = bounds.centerY();
        int horizontal = Math.max(Math.round(dp(96f)), Math.min(bounds.width() / 4, Math.round(width * 0.11f)));
        int vertical = Math.max(Math.round(dp(72f)), Math.min(bounds.height() / 4, Math.round(height * 0.08f)));
        int left = cx - horizontal;
        int right = cx + horizontal;
        int top = cy - vertical;
        int middle = cy;
        int bottom = cy + vertical;
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
        device.swipe(cx, bottom, cx, top, steps);
        sleep(90);
        device.swipe(cx, top, cx, bottom, steps);
        sleep(90);
        device.swipe(right, bottom, left, top, steps);
        sleep(90);
        device.swipe(left, top, right, bottom, steps);
        sleep(90);
        device.swipe(left, bottom, right, top, steps);
        sleep(90);
        device.swipe(right, top, left, bottom, steps);
        sleep(90);
        ellipticalPanOverTraffic(app, Math.max(36, steps), 0.22f, 0.15f, true);
        sleep(90);
    }

    private void smallPanOverTraffic(UiObject2 app, String scaleBand) throws Exception {
        PanEnvelope envelope = panEnvelopeForScaleBand(scaleBand);
        ellipticalPanOverTraffic(app, 44, envelope.widthFraction, envelope.heightFraction, true, envelope.minRadiusDp, envelope.maxRadiusXDp, envelope.maxRadiusYDp);
        sleep(70);
        ellipticalPanOverTraffic(app, 44, envelope.widthFraction * 0.8f, envelope.heightFraction * 0.82f, false, envelope.minRadiusDp, envelope.maxRadiusXDp, envelope.maxRadiusYDp);
        sleep(70);
    }

    private void ellipticalPanOverTraffic(UiObject2 app, int steps, float widthFraction, float heightFraction, boolean clockwise) throws Exception {
        ellipticalPanOverTraffic(app, steps, widthFraction, heightFraction, clockwise, 42f, 156f, 112f);
    }

    private void ellipticalPanOverTraffic(UiObject2 app, int steps, float widthFraction, float heightFraction, boolean clockwise, float minRadiusDp, float maxRadiusXDp, float maxRadiusYDp) throws Exception {
        android.graphics.Rect bounds = safeMapGestureBounds(app.getVisibleBounds());
        float centerX = bounds.centerX();
        float centerY = bounds.centerY();
        recordGestureAnchor("elliptical_pan", new android.graphics.Point(Math.round(centerX), Math.round(centerY)), bounds);
        float radiusX = Math.max(dp(minRadiusDp), Math.min(bounds.width() * widthFraction, dp(maxRadiusXDp)));
        float radiusY = Math.max(dp(Math.max(18f, minRadiusDp * 0.72f)), Math.min(bounds.height() * heightFraction, dp(maxRadiusYDp)));
        long downTime = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[] {
                pointerProperties(0)
        };
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[] {
                pointerCoords(centerX + radiusX, centerY)
        };
        injectMotion(downTime, downTime, MotionEvent.ACTION_DOWN, properties, coords, 1);
        int clampedSteps = Math.max(20, steps);
        float direction = clockwise ? 1f : -1f;
        for (int step = 1; step <= clampedSteps; step++) {
            float t = step / (float) clampedSteps;
            float angle = direction * (float) (Math.PI * 2.0 * t);
            float wobble = 1f + 0.035f * (float) Math.sin(Math.PI * 4.0 * t);
            coords[0].x = centerX + radiusX * wobble * (float) Math.cos(angle);
            coords[0].y = centerY + radiusY * (float) Math.sin(angle);
            coords[0].x = Math.max(bounds.left, Math.min(bounds.right, coords[0].x));
            coords[0].y = Math.max(bounds.top, Math.min(bounds.bottom, coords[0].y));
            injectMotion(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, properties, coords, 1);
            sleep(10L);
        }
        coords[0].x = centerX + radiusX;
        coords[0].y = centerY;
        injectMotion(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, properties, coords, 1);
        injectMotion(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, properties, coords, 1);
    }

    private PanEnvelope panEnvelopeForScaleBand(String scaleBand) {
        String band = scaleBand == null ? "" : scaleBand.toLowerCase(java.util.Locale.US);
        if (band.contains("continent")) {
            return new PanEnvelope(0.024f, 0.018f, 10f, 22f, 18f);
        }
        if (band.contains("country")) {
            return new PanEnvelope(0.044f, 0.032f, 18f, 42f, 32f);
        }
        if (band.contains(COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND.toLowerCase(java.util.Locale.US))) {
            return new PanEnvelope(0.105f, 0.074f, 32f, 104f, 78f);
        }
        return new PanEnvelope(0.140f, 0.100f, 38f, 132f, 96f);
    }

    private void panSatelliteTransitionBand(UiObject2 app) throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        android.graphics.Rect appBounds = app.getVisibleBounds();
        int width = appBounds.width();
        int height = appBounds.height();
        android.graphics.Rect bounds = safeMapGestureBounds(appBounds);
        int cx = bounds.centerX();
        int cy = bounds.centerY();
        int dx = Math.max(Math.round(dp(12f)), Math.min(bounds.width() / 24, width / 42));
        int dy = Math.max(Math.round(dp(10f)), Math.min(bounds.height() / 24, height / 44));
        int left = cx - dx;
        int right = cx + dx;
        int top = cy - dy;
        int middle = cy;
        int bottom = cy + dy;
        device.swipe(right, middle, left, middle, 34);
        sleep(90);
        device.swipe(left, middle, right, middle, 34);
        sleep(90);
        device.swipe(right, bottom, left, top, 38);
        sleep(90);
        device.swipe(left, top, right, bottom, 38);
        sleep(90);
        ellipticalPanOverTraffic(app, 36, 0.026f, 0.020f, true, 10f, 28f, 22f);
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
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        if (!device.takeScreenshot(new File("/sdcard/Download/" + fileName))) {
            runShell("screencap -p /sdcard/" + fileName);
        }
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

    private void setPerfPhaseMetadata(String phaseName, String zoomPlan, String gesturePlan) {
        currentPerfPhaseName = phaseName;
        currentPerfPhaseZoomPlan = zoomPlan;
        currentPerfPhaseGesturePlan = gesturePlan;
    }

    private void markPerfPhase(String artifactName, String scaleBand, String plannedBand, String detail) {
        String message = "artifact=" + artifactName +
                " runId=" + currentPerfRunId +
                " city=" + (currentPerfCity == null ? "unknown" : currentPerfCity.name) +
                " requestedLat=" + (currentPerfCity == null ? "unknown" : currentPerfCity.lat) +
                " requestedLon=" + (currentPerfCity == null ? "unknown" : currentPerfCity.lon) +
                " appFocusOpenMap=" + currentPerfFocusOpenMap +
                " gestureAnchor=" + currentPerfLastGestureAnchor +
                " scaleBand=" + scaleBand +
                " plannedBand=" + plannedBand +
                " elapsedMs=" + SystemClock.elapsedRealtime() +
                " detail=" + detail;
        Log.i(PERF_PHASE_LOG_TAG, message);
        System.out.println(PERF_PHASE_LOG_TAG + ": " + message);
    }

    private void capturePerfArtifacts(String testName) throws Exception {
        writeTextArtifact("flightalert-perf-" + testName + "-target.txt", currentPerfTargetDescription(scaleBandFromArtifactName(testName)));
        writeShellArtifact("flightalert-perf-" + testName + "-gfxinfo.txt", "dumpsys gfxinfo " + PACKAGE_NAME);
        writeShellArtifact("flightalert-perf-" + testName + "-framestats.txt", "dumpsys gfxinfo " + PACKAGE_NAME + " framestats");
        writeShellArtifact("flightalert-perf-" + testName + "-activity.txt", "dumpsys activity activities");
        writeShellArtifact("flightalert-perf-" + testName + "-logcat.txt", "logcat -d -t 3000");
    }

    private String currentPerfTargetDescription(String scaleBand) {
        MajorTrafficCity city = currentPerfCity;
        StringBuilder builder = new StringBuilder();
        builder.append("city=").append(city == null ? "unknown" : city.name).append('\n');
        builder.append("lat=").append(city == null ? "unknown" : city.lat).append('\n');
        builder.append("lon=").append(city == null ? "unknown" : city.lon).append('\n');
        builder.append("last_launch_zoom=").append(currentPerfZoom).append('\n');
        builder.append("scale_band=").append(scaleBand).append('\n');
        builder.append("map_source=").append(currentPerfMapSource).append('\n');
        builder.append("run_id=").append(currentPerfRunId).append('\n');
        builder.append("skip_chrome=").append(currentPerfSkipChrome).append('\n');
        builder.append("skip_top_status=").append(currentPerfSkipTopStatus).append('\n');
        builder.append("skip_controls=").append(currentPerfSkipControls).append('\n');
        builder.append("skip_traffic_panel=").append(currentPerfSkipTrafficPanel).append('\n');
        builder.append("skip_traffic=").append(currentPerfSkipTraffic).append('\n');
        builder.append("traffic_detail_timing=").append(currentPerfTrafficDetailTiming).append('\n');
        builder.append("gesture_focus=").append(centeredPerfGestureFocus ? "screen-center" : "open-map").append('\n');
        builder.append("app_focus_open_map=").append(currentPerfFocusOpenMap).append('\n');
        builder.append("debug_focus_x_fraction=").append(Float.isNaN(currentPerfFocusXFraction) ? "unavailable" : currentPerfFocusXFraction).append('\n');
        builder.append("debug_focus_y_fraction=").append(Float.isNaN(currentPerfFocusYFraction) ? "unavailable" : currentPerfFocusYFraction).append('\n');
        builder.append("focus_alignment_rule=").append(currentPerfFocusOpenMap
                ? "PERF_FOCUS_OPEN_MAP=true and gestures use the same unobstructed open-map anchor."
                : "PERF_FOCUS_OPEN_MAP=false; accept only if screen-center gestures are intentional.").append('\n');
        builder.append("last_gesture_anchor=").append(currentPerfLastGestureAnchor).append('\n');
        builder.append("last_gesture_bounds=").append(currentPerfLastGestureBounds).append('\n');
        builder.append("gesture_trace_begin\n");
        builder.append(currentPerfGestureTrace.length() == 0 ? "unavailable\n" : currentPerfGestureTrace.toString());
        builder.append("gesture_trace_end\n");
        builder.append("actual_camera_log_source=paired logcat artifact; search for this run_id and Debug draw perf camera centerLat/centerLon lines.\n");
        builder.append("target_optimizer_safe=").append(currentPerfTargetOptimizerSafe).append('\n');
        builder.append("target_rule=inland US major-airport traffic target plus bounded closed gestures for optimizer evidence; discard and rerun if the gesture focus drifts over water, empty terrain, or the launcher.\n");
        builder.append("visual_evidence_land_safe=requires_review; screenshots/video must show the active gesture/focus over inland land/traffic. Incidental coastline or water at continent scale is acceptable only when focusLat/focusLon and visible motion stay over the requested inland traffic target.\n");
        builder.append("reject_evidence_if=gesture/focus over ocean/large lake/empty terrain/upper Canada/launcher/home screen, or focusLat/focusLon logs do not match the requested target/focus mode.\n");
        builder.append("phase_name=").append(currentPerfPhaseName).append('\n');
        builder.append("phase_zoom_plan=").append(currentPerfPhaseZoomPlan).append('\n');
        builder.append("phase_gesture_plan=").append(currentPerfPhaseGesturePlan).append('\n');
        builder.append("phase_log_tag=").append(PERF_PHASE_LOG_TAG).append('\n');
        builder.append("scale_bands=continent,country,").append(COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND).append(',').append(COUNTRY_CONTINUITY_FULL_RANGE_BAND).append('\n');
        builder.append("country_phase_note=country launch zoom ").append(COUNTRY_CONTINUITY_COUNTRY_ZOOM).append(" intentionally pinches closed first, so active/quick screenshots can show country-to-global stress frames; do not treat those frames as pure city-centered country visual proof.\n");
        builder.append("regional100mi_phase_note=").append(COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND).append(" launch zoom ").append(COUNTRY_CONTINUITY_REGIONAL_100_MI_ZOOM).append(" targets the 100 mi scale-bar neighborhood and pinches open first so the first active frames stay in the regional transition band before reversing.\n");
        builder.append("full_range_phase_note=").append(COUNTRY_CONTINUITY_FULL_RANGE_BAND).append(" sweeps through the zoom range in/out, adds pan checkpoints, and logs planned zoom bands so framestats can be compared with logcat phase markers.\n");
        builder.append("fast_zoom_out_tile_load_note=fast tile-load tests start at close map detail, quickly pinch out to country/wide scale, then record the tile-load recovery window so unloaded-tile duration can be compared in logcat.\n");
        return builder.toString();
    }

    private String scaleBandFromArtifactName(String testName) {
        if (testName.endsWith("-continent")) return "continent";
        if (testName.endsWith("-country")) return "country";
        if (testName.endsWith("-" + COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND)) return COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND;
        if (testName.endsWith("-" + COUNTRY_CONTINUITY_FULL_RANGE_BAND)) return COUNTRY_CONTINUITY_FULL_RANGE_BAND;
        if (testName.contains("satelliteFastZoomOutTileLoad")) return "fastZoomOutTileLoad";
        return PERF_BAND_NOT_APPLICABLE;
    }

    private boolean instrumentationBooleanArgument(String name) {
        String value = InstrumentationRegistry.getArguments().getString(name);
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    private Boolean instrumentationOptionalBooleanArgument(String name) {
        String value = InstrumentationRegistry.getArguments().getString(name);
        if (value == null) return null;
        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(value) || "0".equals(value) || "no".equalsIgnoreCase(value)) return Boolean.FALSE;
        return null;
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
        rejectUnsupportedTargetCityIfRequested(fixed);
        return fixed != null ? fixed : randomCityFrom(MAJOR_TRAFFIC_CITIES);
    }

    private MajorTrafficCity randomInlandTrafficCity() {
        MajorTrafficCity fixed = fixedCityFromArguments(INLAND_TRAFFIC_CITIES);
        rejectUnsupportedTargetCityIfRequested(fixed);
        return fixed != null ? fixed : randomCityFrom(INLAND_TRAFFIC_CITIES);
    }

    private MajorTrafficCity randomFullRangeLandSafeTrafficCity() {
        MajorTrafficCity fixed = fixedCityFromArguments(FULL_RANGE_LAND_SAFE_TRAFFIC_CITIES);
        if (fixed != null) return fixed;
        String requested = InstrumentationRegistry.getArguments().getString("targetCity");
        if (requested != null && !requested.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "targetCity=" + requested + " is not a full-range inland US optimizer target. Use Dallas-Fort Worth, Atlanta, Denver, Phoenix, or Las Vegas."
            );
        }
        return randomCityFrom(FULL_RANGE_LAND_SAFE_TRAFFIC_CITIES);
    }

    private void rejectUnsupportedTargetCityIfRequested(MajorTrafficCity fixed) {
        String requested = InstrumentationRegistry.getArguments().getString("targetCity");
        if (fixed == null && requested != null && !requested.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "targetCity=" + requested + " is not a bounded inland US optimizer target for this test. Use Dallas-Fort Worth, Atlanta, Denver, Phoenix, or Las Vegas."
            );
        }
    }

    private MajorTrafficCity fixedCityFromArguments(MajorTrafficCity[] cities) {
        String requested = InstrumentationRegistry.getArguments().getString("targetCity");
        if (requested == null || requested.trim().isEmpty()) return null;
        String key = normalizeCityName(requested);
        for (MajorTrafficCity city : cities) {
            if (normalizeCityName(city.name).equals(key)) return city;
        }
        if ("dfw".equals(key)) return findCity(cities, "Dallas-Fort Worth");
        if ("atl".equals(key)) return findCity(cities, "Atlanta");
        if ("den".equals(key)) return findCity(cities, "Denver");
        if ("phx".equals(key)) return findCity(cities, "Phoenix");
        if ("las".equals(key) || "vegas".equals(key)) return findCity(cities, "Las Vegas");
        return null;
    }

    private MajorTrafficCity findCity(MajorTrafficCity[] cities, String name) {
        String key = normalizeCityName(name);
        for (MajorTrafficCity city : cities) {
            if (normalizeCityName(city.name).equals(key)) return city;
        }
        return null;
    }

    private boolean isOptimizerSafeTrafficCity(MajorTrafficCity candidate) {
        if (candidate == null) return false;
        String candidateKey = normalizeCityName(candidate.name);
        for (MajorTrafficCity city : FULL_RANGE_LAND_SAFE_TRAFFIC_CITIES) {
            if (normalizeCityName(city.name).equals(candidateKey)) return true;
        }
        return false;
    }

    private String normalizeCityName(String value) {
        return value.toLowerCase(java.util.Locale.US).replaceAll("[^a-z0-9]", "");
    }

    private MajorTrafficCity randomCityFrom(MajorTrafficCity[] cities) {
        long seed = System.currentTimeMillis() ^ System.nanoTime();
        int index = (int) Math.abs(seed % cities.length);
        return cities[index];
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

    private static final class PanEnvelope {
        final float widthFraction;
        final float heightFraction;
        final float minRadiusDp;
        final float maxRadiusXDp;
        final float maxRadiusYDp;

        PanEnvelope(float widthFraction, float heightFraction, float minRadiusDp, float maxRadiusXDp, float maxRadiusYDp) {
            this.widthFraction = widthFraction;
            this.heightFraction = heightFraction;
            this.minRadiusDp = minRadiusDp;
            this.maxRadiusXDp = maxRadiusXDp;
            this.maxRadiusYDp = maxRadiusYDp;
        }
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
