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

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public class FlightMapGesturePerfTest {
    private static final String PACKAGE_NAME = "com.flightalert";
    private static final String PERF_PHASE_LOG_TAG = "FlightAlertPerfPhase";
    private static final String PERF_BAND_NOT_APPLICABLE = "not_applicable";
    private static final long TRAFFIC_LOAD_WAIT_MS = 6000L;
    private static final long SANITY_TRAFFIC_LOAD_WAIT_MS = 3500L;
    private static final long BENCHMARK_PAN_ZOOM_WORKLOAD_MS = 60000L;
    private static final int SATELLITE_FAST_ZOOM_OUT_STEPS = 4;
    private static final double COUNTRY_CONTINUITY_CONTINENT_ZOOM = 4.25;
    private static final double COUNTRY_CONTINUITY_COUNTRY_ZOOM = 5.4;
    private static final double COUNTRY_CONTINUITY_REGIONAL_100_MI_ZOOM = 7.55;
    private static final String COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND = "regional100mi";
    private static final String COUNTRY_CONTINUITY_FULL_RANGE_BAND = "fullRange";
    private static final float MAP_FOCUS_SAFE_INSET_DP = 96f;
    private static final MajorTrafficCity[] MAJOR_TRAFFIC_CITIES = new MajorTrafficCity[] {
            new MajorTrafficCity("Dallas-Fort Worth", 32.90, -97.04),
            new MajorTrafficCity("Atlanta", 33.64, -84.43),
            new MajorTrafficCity("Phoenix", 33.43, -112.01),
            new MajorTrafficCity("Las Vegas", 36.08, -115.15),
            new MajorTrafficCity("Chicago", 41.88, -87.63),
            new MajorTrafficCity("New York City", 40.73, -73.93),
            new MajorTrafficCity("Los Angeles", 33.94, -118.40),
            new MajorTrafficCity("London", 51.47, -0.45),
            new MajorTrafficCity("Amsterdam", 52.31, 4.77),
            new MajorTrafficCity("Frankfurt", 50.04, 8.56),
            new MajorTrafficCity("Paris", 49.01, 2.55),
            new MajorTrafficCity("Madrid", 40.49, -3.57)
    };
    private static final MajorTrafficCity[] INLAND_TRAFFIC_CITIES = new MajorTrafficCity[] {
            new MajorTrafficCity("Dallas-Fort Worth", 32.90, -97.04),
            new MajorTrafficCity("Atlanta", 33.64, -84.43),
            new MajorTrafficCity("Phoenix", 33.43, -112.01),
            new MajorTrafficCity("Las Vegas", 36.08, -115.15),
            new MajorTrafficCity("Chicago", 41.88, -87.63),
            new MajorTrafficCity("New York City", 40.73, -73.93),
            new MajorTrafficCity("Los Angeles", 33.94, -118.40),
            new MajorTrafficCity("London", 51.47, -0.45),
            new MajorTrafficCity("Amsterdam", 52.31, 4.77),
            new MajorTrafficCity("Frankfurt", 50.04, 8.56),
            new MajorTrafficCity("Paris", 49.01, 2.55),
            new MajorTrafficCity("Madrid", 40.49, -3.57)
    };
    private static final MajorTrafficCity[] FULL_RANGE_LAND_SAFE_TRAFFIC_CITIES = new MajorTrafficCity[] {
            new MajorTrafficCity("Dallas-Fort Worth", 32.90, -97.04),
            new MajorTrafficCity("Atlanta", 33.64, -84.43),
            new MajorTrafficCity("Phoenix", 33.43, -112.01),
            new MajorTrafficCity("Las Vegas", 36.08, -115.15),
            new MajorTrafficCity("Frankfurt", 50.04, 8.56),
            new MajorTrafficCity("Paris", 49.01, 2.55),
            new MajorTrafficCity("Madrid", 40.49, -3.57)
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
    private boolean currentPerfMapDetailTiming = false;
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

    @After
    public void holdForegroundForVideoEvidence() throws Exception {
        int holdSeconds = instrumentationIntArgument("videoEvidenceHoldSeconds", 0);
        if (holdSeconds <= 0) return;
        long deadline = SystemClock.uptimeMillis() + holdSeconds * 1000L;
        while (SystemClock.uptimeMillis() < deadline) {
            requireFlightAlertForeground();
            sleep(250);
        }
    }

    @Test
    public void launchOnly() throws Exception {
        MajorTrafficCity city = randomMajorTrafficCity();
        UiObject2 app = startAppAtMajorTraffic(city, 5.4);
        assertNotNull("Flight Alert root was not found", app);
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

        int cycles = captureScreenshots ? 5 : 3;
        for (int cycle = 0; cycle < cycles; cycle++) {
            humanLikeQuickOverlappedPinch(app, "country", false);
            sleep(80);
            humanLikeQuickOverlappedPinch(app, "country", true);
            sleep(110);
            briefHumanPanOverTraffic(app, "country");
            requireFlightAlertForeground();
        }
        humanLikeOverlappedPinch(app, "country", true);
        sleep(120);
        humanLikeOverlappedPinch(app, "country", false);
        sleep(500);
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
    public void closeSatellitePanLabels() throws Exception {
        runCloseSatellitePanLabels("closeSatellitePanLabels", true);
    }

    @Test
    public void closeSatellitePanLabelsPerf() throws Exception {
        runCloseSatellitePanLabels("closeSatellitePanLabelsPerf", false);
    }

    @Test
    public void satellitePanZoomSanityPerf() throws Exception {
        MajorTrafficCity city = randomInlandTrafficCity();
        boolean skipChrome = instrumentationBooleanArgument("skipChrome");
        boolean skipTraffic = instrumentationBooleanArgument("skipTraffic");
        UiObject2 app = startAppAtMajorTraffic(city, COUNTRY_CONTINUITY_REGIONAL_100_MI_ZOOM, "SATELLITE", skipChrome, skipTraffic);
        sleep(SANITY_TRAFFIC_LOAD_WAIT_MS);
        setPerfPhaseMetadata(
                "sanityPanZoom",
                "launch_zoom=" + COUNTRY_CONTINUITY_REGIONAL_100_MI_ZOOM + "; bounded_pan=true; human_like_overlapped_pan_pinch=true",
                "short bounded pan plus overlapping pan/pinch zoom-out and zoom-in gestures over the timetable-selected US/EU traffic target"
        );
        clearPerfCounters();
        markPerfPhase("satellitePanZoomSanityPerf", "sanityPanZoom", COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, "phase_start");
        briefHumanPanOverTraffic(app, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND);
        sleep(90);
        markPerfPhase("satellitePanZoomSanityPerf", "sanityPanZoom", COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, "human_like_overlap_zoom_out");
        humanLikeOverlappedPinch(app, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, false);
        sleep(120);
        markPerfPhase("satellitePanZoomSanityPerf", "sanityPanZoom", COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, "human_like_overlap_zoom_in");
        humanLikeOverlappedPinch(app, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, true);
        sleep(180);
        requireFlightAlertForeground();
        capturePerfArtifacts("satellitePanZoomSanityPerf");
    }

    @Test
    public void satelliteBenchmarkPanZoomWorkloadPerf() throws Exception {
        MajorTrafficCity city = randomInlandTrafficCity();
        boolean skipChrome = instrumentationBooleanArgument("skipChrome");
        boolean skipTraffic = instrumentationBooleanArgument("skipTraffic");
        UiObject2 app = startAppAtMajorTraffic(city, 5.88, "SATELLITE", skipChrome, skipTraffic);
        sleep(TRAFFIC_LOAD_WAIT_MS);
        setPerfPhaseMetadata(
                "benchmarkPanZoom60s",
                "launch_zoom=5.88; map_source=SATELLITE; target_motion_ms=" + BENCHMARK_PAN_ZOOM_WORKLOAD_MS + "; timetable_target=" + city.name,
                "repeatable workload-to-workload benchmark with bounded human-like pan plus overlapping zoom-in/out gestures across country and regional bands"
        );
        clearPerfCounters();

        long deadline = SystemClock.uptimeMillis() + BENCHMARK_PAN_ZOOM_WORKLOAD_MS;
        int cycle = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            cycle++;
            markPerfPhase("satelliteBenchmarkPanZoomWorkloadPerf", "benchmarkPanZoom60s", "country", "human_like_pan cycle=" + cycle);
            briefHumanPanOverTraffic(app, "country");
            sleep(90);

            markPerfPhase("satelliteBenchmarkPanZoomWorkloadPerf", "benchmarkPanZoom60s", "country", "human_like_overlap direction=zoom_in cycle=" + cycle);
            humanLikeOverlappedPinch(app, "country", true);
            sleep(120);

            markPerfPhase("satelliteBenchmarkPanZoomWorkloadPerf", "benchmarkPanZoom60s", COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, "human_like_pan cycle=" + cycle);
            briefHumanPanOverTraffic(app, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND);
            sleep(90);

            markPerfPhase("satelliteBenchmarkPanZoomWorkloadPerf", "benchmarkPanZoom60s", COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, "human_like_overlap direction=zoom_out cycle=" + cycle);
            humanLikeOverlappedPinch(app, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, false);
            sleep(140);

            if (cycle % 3 == 0) {
                requireFlightAlertForeground();
            }
        }
        markPerfPhase("satelliteBenchmarkPanZoomWorkloadPerf", "benchmarkPanZoom60s", "country", "phase_capture_artifacts cycles=" + cycle);
        sleep(700);
        requireFlightAlertForeground();
        capturePerfArtifacts("satelliteBenchmarkPanZoomWorkloadPerf");
    }

    private void runCloseSatellitePanLabels(String artifactName, boolean captureScreenshots) throws Exception {
        MajorTrafficCity city = randomInlandTrafficCity();
        boolean skipChrome = instrumentationBooleanArgument("skipChrome");
        boolean skipTraffic = instrumentationBooleanArgument("skipTraffic");
        UiObject2 app = startAppAtMajorTraffic(city, 12.2, "SATELLITE", skipChrome, skipTraffic);
        sleep(TRAFFIC_LOAD_WAIT_MS);
        if (captureScreenshots) {
            captureActiveDisplay("flightalert-perf-" + artifactName + "-rest.png");
        }
        clearPerfCounters();

        if (captureScreenshots) {
            scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-pan-start.png", 120);
        }
        int panPasses = captureScreenshots ? 3 : 2;
        for (int pass = 0; pass < panPasses; pass++) {
            markPerfPhase(artifactName, "closeSatellitePanLabels", "close", "human_like_pan pass=" + (pass + 1) + "/" + panPasses);
            briefHumanPanOverTraffic(app, "close");
            sleep(80);
        }
        if (captureScreenshots) {
            scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-pan-active.png", 120);
        }
        sleep(450);
        if (captureScreenshots) {
            markPerfPhase(artifactName, "closeSatellitePanLabels", "close", "human_like_pan evidence_finish");
            briefHumanPanOverTraffic(app, "close");
            sleep(900);
        } else {
            sleep(250);
        }
        if (captureScreenshots) {
            waitForScheduledCaptures();
        }
        requireFlightAlertForeground();
        if (captureScreenshots) {
            captureActiveDisplay("flightalert-perf-" + artifactName + ".png");
        }
        capturePerfArtifacts(artifactName);
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
            humanLikeOverlappedPinch(app, i < 4 ? "country" : COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, true);
            sleep(170);
        }
        sleep(250);
        briefHumanPanOverTraffic(app, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND);
        app = reanchorAtTrafficTarget(
                city,
                11.6,
                mapSource,
                false,
                false,
                true,
                artifactName,
                "close",
                "reverse_leg_reanchor"
        );
        for (int i = 0; i < 9; i++) {
            if (i == 0) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-reverse-start.png", 120);
            if (i == 4) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-motion-active-in.png", 120);
            humanLikeOverlappedPinch(app, i < 4 ? COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND : "country", false);
            sleep(170);
        }
        sleep(600);
        waitForScheduledCaptures();
        requireFlightAlertForeground();
        captureActiveDisplay("flightalert-perf-" + artifactName + ".png");
        capturePerfArtifacts(artifactName);
    }

    private void runMorphTransitionSweep(String mapSource, String artifactName, boolean captureScreenshots) throws Exception {
        MajorTrafficCity city = randomInlandTrafficCity();
        UiObject2 app = startAppAtMajorTraffic(city, 7.55, mapSource);
        sleep(TRAFFIC_LOAD_WAIT_MS);
        if (captureScreenshots) {
            captureActiveDisplay("flightalert-perf-" + artifactName + "-rest.png");
        }
        clearPerfCounters();

        for (int i = 0; i < 7; i++) {
            if (captureScreenshots && i == 0) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-motion-start.png", 120);
            if (captureScreenshots && i == 3) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-motion-active-out.png", 120);
            humanLikeOverlappedPinch(app, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, true);
            sleep(150);
        }
        sleep(220);
        briefHumanPanOverTraffic(app, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND);
        app = reanchorAtTrafficTarget(
                city,
                10.2,
                mapSource,
                false,
                false,
                true,
                artifactName,
                COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND,
                "reverse_leg_reanchor"
        );
        for (int i = 0; i < 7; i++) {
            if (captureScreenshots && i == 0) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-reverse-start.png", 120);
            if (captureScreenshots && i == 3) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-motion-active-in.png", 120);
            humanLikeOverlappedPinch(app, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, false);
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
        MajorTrafficCity city = randomFullRangeLandSafeTrafficCity();
        UiObject2 app = startAppAtMajorTraffic(city, COUNTRY_CONTINUITY_CONTINENT_ZOOM, mapSource, true, false, true);
        sleep(TRAFFIC_LOAD_WAIT_MS);
        if (captureScreenshots) {
            captureActiveDisplay("flightalert-perf-" + artifactName + "-rest.png");
        }
        setPerfPhaseMetadata(
                "continent",
                "launch_zoom=" + COUNTRY_CONTINUITY_CONTINENT_ZOOM + "; app_focus_open_map=true; timetable_target=" + city.name,
                "wide-scale continuity uses timetable-selected US/EU traffic and short human-like overlapped pan/pinch gestures, avoiding random far-field terrain"
        );
        clearPerfCounters();
        markPerfPhase(artifactName, "continent", "continent", "phase_start");
        runHumanLikeBandMotion(app, artifactName, "continent", true, captureScreenshots);
        sleep(700);
        if (captureScreenshots) {
            waitForScheduledCaptures();
        }
        requireFlightAlertForeground();
        markPerfPhase(artifactName, "continent", "continent", "phase_capture_artifacts");
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
            UiObject2 app = startAppAtMajorTraffic(city, COUNTRY_CONTINUITY_COUNTRY_ZOOM, mapSource, skipChrome, skipTraffic, true);
            sleep(TRAFFIC_LOAD_WAIT_MS);
            if (captureScreenshots) {
                captureActiveDisplay("flightalert-perf-" + artifactName + "-country-rest.png");
            }
            setPerfPhaseMetadata(
                    "country",
                    "launch_zoom=" + COUNTRY_CONTINUITY_COUNTRY_ZOOM + "; app_focus_open_map=true",
                    "app camera and gestures share the unobstructed open-map focus; short human-like overlapped pan/pinch gestures; first_direction=zoom_out"
            );
            clearPerfCounters();
            markPerfPhase(artifactName, "country", "country", "phase_start");
            runHumanLikeBandMotion(app, artifactName, "country", false, captureScreenshots);
            sleep(700);
            if (captureScreenshots) {
                waitForScheduledCaptures();
            }
            requireFlightAlertForeground();
            markPerfPhase(artifactName, "country", "country", "phase_capture_artifacts");
            capturePerfArtifacts(artifactName);
            if (captureScreenshots) {
                captureActiveDisplay("flightalert-perf-" + artifactName + ".png");
            }
        } finally {
            centeredPerfGestureFocus = false;
        }
    }

    private void runHumanLikeBandMotion(UiObject2 app, String artifactName, String scaleName, boolean zoomInFirst, boolean captureScreenshots) throws Exception {
        String firstDirection = zoomInFirst ? "zoom_in" : "zoom_out";
        String reverseDirection = zoomInFirst ? "zoom_out" : "zoom_in";
        if (captureScreenshots) {
            scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-" + scaleName + "-human-motion-start.png", 120);
        }
        markPerfPhase(artifactName, scaleName, scaleName, "human_like_pan_start");
        briefHumanPanOverTraffic(app, scaleName);
        sleep(90);

        if (captureScreenshots) {
            scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-" + scaleName + "-human-overlap-a.png", 120);
        }
        markPerfPhase(artifactName, scaleName, scaleName, "human_like_overlap direction=" + firstDirection);
        humanLikeOverlappedPinch(app, scaleName, zoomInFirst);
        sleep(130);

        if (captureScreenshots) {
            scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-" + scaleName + "-human-overlap-b.png", 120);
        }
        markPerfPhase(artifactName, scaleName, scaleName, "human_like_overlap direction=" + reverseDirection);
        humanLikeOverlappedPinch(app, scaleName, !zoomInFirst);
        sleep(130);

        markPerfPhase(artifactName, scaleName, scaleName, "human_like_pan_finish");
        briefHumanPanOverTraffic(app, scaleName);
        requireFlightAlertForeground();
    }

    private void runCloseScaleZoomContinuity(String mapSource, String artifactName, boolean captureScreenshots) throws Exception {
        MajorTrafficCity city = randomFullRangeLandSafeTrafficCity();
        UiObject2 app = startAppAtMajorTraffic(city, 11.6, mapSource, false, false, true);
        sleep(TRAFFIC_LOAD_WAIT_MS);
        if (captureScreenshots) {
            captureActiveDisplay("flightalert-perf-" + artifactName + "-rest.png");
        }
        setPerfPhaseMetadata(
                "close",
                "launch_zoom=11.6; app_focus_open_map=true; timetable_target=" + city.name,
                "close-scale continuity uses brief human-like pan plus overlapping zoom-out/zoom-in gestures over the selected traffic target"
        );
        clearPerfCounters();
        markPerfPhase(artifactName, "close", "close", "phase_start");
        runHumanLikeBandMotion(app, artifactName, "close", false, captureScreenshots);
        sleep(700);
        if (captureScreenshots) {
            waitForScheduledCaptures();
        }
        requireFlightAlertForeground();
        markPerfPhase(artifactName, "close", "close", "phase_capture_artifacts");
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
        setPerfPhaseMetadata(
                COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND,
                "launch_zoom=5.88; map_source=SATELLITE; timetable_target=" + city.name,
                "satellite transition band uses human-like pan and overlapping pinch gestures rather than isolated pan/pause/zoom steps"
        );
        clearPerfCounters();

        if (captureScreenshots) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-pan-start.png", 120);
        markPerfPhase(artifactName, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, "country", "human_like_pan_start");
        briefHumanPanOverTraffic(app, "country");
        sleep(180);

        if (captureScreenshots) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-zoom-in-start.png", 120);
        markPerfPhase(artifactName, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, "country", "human_like_overlap direction=zoom_in step=1/2");
        humanLikeOverlappedPinch(app, "country", true);
        if (captureScreenshots) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-zoom-in-active.png", 170);
        markPerfPhase(artifactName, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, "human_like_overlap direction=zoom_in step=2/2");
        humanLikeOverlappedPinch(app, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, true);
        sleep(220);

        if (captureScreenshots) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-zoom-out-start.png", 120);
        markPerfPhase(artifactName, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, "human_like_overlap direction=zoom_out step=1/2");
        humanLikeOverlappedPinch(app, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, false);
        if (captureScreenshots) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-zoom-out-active.png", 170);
        markPerfPhase(artifactName, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, "country", "human_like_overlap direction=zoom_out step=2/2");
        humanLikeOverlappedPinch(app, "country", false);
        sleep(220);

        if (captureScreenshots) scheduleActiveDisplayCapture("flightalert-perf-" + artifactName + "-pan-after-zoom.png", 120);
        markPerfPhase(artifactName, COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, "country", "human_like_pan_finish");
        briefHumanPanOverTraffic(app, "country");
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
                "map_source=" + mapSource + "; launch_zoom=13.2; app_focus_open_map=true; timetable_target=" + city.name + "; " + SATELLITE_FAST_ZOOM_OUT_STEPS + " human-like quick overlapping zoom-out gestures from close detail toward country/wide scale; post-zoom recovery window captures tile load catch-up",
                "anchored quick overlap pinch-close gestures over selected US/EU airport traffic; no keyboard zoom; recovery screenshots at 250/650/1200 ms after final zoom-out"
        );
        clearPerfCounters();
        markPerfPhase(artifactName, "fastZoomOutTileLoad", "close", "phase_start " + mapLabel + "_close_tiles_loaded");

        for (int step = 0; step < SATELLITE_FAST_ZOOM_OUT_STEPS; step++) {
            String plannedBand = plannedFastZoomOutBand(step);
            markPerfPhase(
                    artifactName,
                    "fastZoomOutTileLoad",
                    plannedBand,
                    "fast_zoom_out_step=" + (step + 1) + "/" + SATELLITE_FAST_ZOOM_OUT_STEPS + " gesture=" + fastZoomOutGestureName(plannedBand)
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
            fastSatelliteZoomOutStep(app, plannedBand);
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

    private void fastSatelliteZoomOutStep(UiObject2 app, String plannedBand) {
        humanLikeQuickOverlappedPinch(app, plannedBand, false);
    }

    private String fastZoomOutGestureName(String plannedBand) {
        return "human_like_quick_overlap_pinch_close_" + plannedBand;
    }

    @Test
    public void panAcrossZoomLevels() throws Exception {
        MajorTrafficCity city = randomInlandTrafficCity();
        UiObject2 app = startAppAtMajorTraffic(city, COUNTRY_CONTINUITY_COUNTRY_ZOOM, null, false, false, true);
        sleep(TRAFFIC_LOAD_WAIT_MS);
        setPerfPhaseMetadata(
                "multiZoomPan",
                "launch_zoom=" + COUNTRY_CONTINUITY_COUNTRY_ZOOM + "; app_focus_open_map=true; timetable_target=" + city.name,
                "pan checks include overlapping human-like zoom transitions across country, regional, and close bands"
        );
        clearPerfCounters();

        String[] bands = new String[] {"country", COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND, "close"};
        for (int level = 0; level < bands.length; level++) {
            String band = bands[level];
            markPerfPhase("panAcrossZoomLevels", "multiZoomPan", band, "human_like_pan level=" + (level + 1));
            briefHumanPanOverTraffic(app, band);
            sleep(120);
            markPerfPhase("panAcrossZoomLevels", "multiZoomPan", band, "human_like_overlap direction=zoom_in level=" + (level + 1));
            humanLikeOverlappedPinch(app, band, true);
            sleep(180);
            requireFlightAlertForeground();
        }
        markPerfPhase("panAcrossZoomLevels", "multiZoomPan", "close", "human_like_pan_finish");
        briefHumanPanOverTraffic(app, "close");
        sleep(900);
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
        boolean mapDetailTiming = instrumentationBooleanArgument("mapDetailTiming");
        Boolean mapRoads = instrumentationOptionalBooleanArgument("mapRoads");
        Boolean mapBorders = instrumentationOptionalBooleanArgument("mapBorders");
        requireCompleteMapReferenceArguments(mapRoads, mapBorders);
        currentPerfTrafficDetailTiming = trafficDetailTiming;
        currentPerfMapDetailTiming = mapDetailTiming;
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
            intent.putExtra("com.flightalert.PERF_SKIP_CHROME", true);
        }
        if (currentPerfSkipTopStatus) {
            intent.putExtra("com.flightalert.PERF_SKIP_TOP_STATUS", true);
        }
        if (currentPerfSkipControls) {
            intent.putExtra("com.flightalert.PERF_SKIP_CONTROLS", true);
        }
        if (currentPerfSkipTrafficPanel) {
            intent.putExtra("com.flightalert.PERF_SKIP_TRAFFIC_PANEL", true);
        }
        if (skipTraffic) {
            intent.putExtra("com.flightalert.PERF_SKIP_TRAFFIC", true);
        }
        if (trafficDetailTiming) {
            intent.putExtra("com.flightalert.PERF_TRAFFIC_DETAIL_TIMING", true);
        }
        if (mapDetailTiming) {
            intent.putExtra("com.flightalert.PERF_MAP_DETAIL_TIMING", true);
        }
        if (mapSource != null) {
            intent.putExtra("com.flightalert.PERF_MAP_SOURCE", mapSource);
        }
        context.startActivity(intent);
        sleep(250);
        acceptFlightAlertPermissionsIfPresent();
        if (!waitForFlightAlertForeground(8000L)) {
            launchFlightAlertWithShell(city, zoom, mapSource, skipChrome, skipTraffic, trafficDetailTiming, mapDetailTiming, focusOpenMap);
            sleep(250);
            acceptFlightAlertPermissionsIfPresent();
            assertTrue("Refusing to run gestures outside Flight Alert. Foreground was:\n" + foregroundDiagnostic(), waitForFlightAlertForeground(8000L));
        }
        return flightAlertRoot();
    }

    private UiObject2 reanchorAtTrafficTarget(
            MajorTrafficCity city,
            double zoom,
            String mapSource,
            boolean skipChrome,
            boolean skipTraffic,
            boolean focusOpenMap,
            String artifactName,
            String scaleBand,
            String detail
    ) throws Exception {
        markPerfPhase(artifactName, scaleBand, scaleBand, detail + "_start city=" + city.name + " zoom=" + zoom);
        UiObject2 app = startAppAtMajorTraffic(city, zoom, mapSource, skipChrome, skipTraffic, focusOpenMap);
        sleep(SANITY_TRAFFIC_LOAD_WAIT_MS);
        requireFlightAlertForeground();
        markPerfPhase(artifactName, scaleBand, scaleBand, detail + "_complete city=" + city.name + " zoom=" + zoom);
        return app;
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

    private void launchFlightAlertWithShell(MajorTrafficCity city, double zoom, String mapSource, boolean skipChrome, boolean skipTraffic, boolean trafficDetailTiming, boolean mapDetailTiming, boolean focusOpenMap) throws Exception {
        StringBuilder command = new StringBuilder("am start -n ")
                .append(PACKAGE_NAME).append("/.MainActivity")
                .append(" --es com.flightalert.PERF_LAT ").append(city.lat)
                .append(" --es com.flightalert.PERF_LON ").append(city.lon)
                .append(" --es com.flightalert.PERF_ZOOM ").append(zoom)
                .append(" --es com.flightalert.PERF_RUN_ID ").append(currentPerfRunId)
                .append(" --es com.flightalert.PERF_FOCUS_X_FRACTION ").append(currentPerfFocusXFraction)
                .append(" --es com.flightalert.PERF_FOCUS_Y_FRACTION ").append(currentPerfFocusYFraction)
                .append(" --ez com.flightalert.PERF_CLEAR_SELECTION true")
                .append(" --ez com.flightalert.PERF_FOCUS_OPEN_MAP ").append(focusOpenMap);
        Boolean mapRoads = instrumentationOptionalBooleanArgument("mapRoads");
        Boolean mapBorders = instrumentationOptionalBooleanArgument("mapBorders");
        requireCompleteMapReferenceArguments(mapRoads, mapBorders);
        if (mapRoads != null) {
            command.append(" --ez com.flightalert.PERF_MAP_ROADS_ENABLED ").append(mapRoads);
        }
        if (mapBorders != null) {
            command.append(" --ez com.flightalert.PERF_MAP_BORDERS_ENABLED ").append(mapBorders);
        }
        if (skipChrome) {
            command.append(" --ez com.flightalert.PERF_SKIP_CHROME true");
        }
        if (currentPerfSkipTopStatus) {
            command.append(" --ez com.flightalert.PERF_SKIP_TOP_STATUS true");
        }
        if (currentPerfSkipControls) {
            command.append(" --ez com.flightalert.PERF_SKIP_CONTROLS true");
        }
        if (currentPerfSkipTrafficPanel) {
            command.append(" --ez com.flightalert.PERF_SKIP_TRAFFIC_PANEL true");
        }
        if (skipTraffic) {
            command.append(" --ez com.flightalert.PERF_SKIP_TRAFFIC true");
        }
        if (trafficDetailTiming) {
            command.append(" --ez com.flightalert.PERF_TRAFFIC_DETAIL_TIMING true");
        }
        if (mapDetailTiming) {
            command.append(" --ez com.flightalert.PERF_MAP_DETAIL_TIMING true");
        }
        if (mapSource != null) {
            command.append(" --es com.flightalert.PERF_MAP_SOURCE ").append(mapSource);
        }
        runShell(command.toString());
    }

    private UiObject2 flightAlertRoot() {
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

    private void acceptFlightAlertPermissionsIfPresent() {
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
        humanLikeOverlappedPinch(app, "country", true);
        sleep(140);
        humanLikeOverlappedPinch(app, "country", false);
        sleep(140);
        requireFlightAlertForeground();
    }

    private void humanLikeOverlappedPinch(UiObject2 app, String scaleBand, boolean open) {
        humanLikeOverlappedPinchWithCadence(app, scaleBand, open, false, 18, 10L, 0.62f);
    }

    private void humanLikeQuickOverlappedPinch(UiObject2 app, String scaleBand, boolean open) {
        humanLikeOverlappedPinchWithCadence(app, scaleBand, open, true, 10, 8L, 0.95f);
    }

    private void humanLikeOverlappedPinchWithCadence(UiObject2 app, String scaleBand, boolean open, boolean quick, int steps, long stepDelayMs, float percentScale) {
        android.graphics.Rect bounds = app.getVisibleBounds();
        android.graphics.Rect mapBounds = safeMapGestureBounds(bounds);
        android.graphics.Point focus = mapFocusForBounds(bounds);
        float shortSide = Math.max(1f, Math.min(bounds.width(), bounds.height()));
        float innerRadius = Math.max(42f, shortSide * 0.055f);
        float percent = pinchPercentForScaleBand(scaleBand, quick, open) * percentScale;
        float outerRadius = boundedHorizontalPinchRadius(focus, mapBounds, Math.max(innerRadius + 42f, shortSide * percent * 0.42f), innerRadius);
        float startRadius = open ? innerRadius : outerRadius;
        float endRadius = open ? outerRadius : innerRadius;
        int clampedSteps = Math.max(10, steps);
        float maxShiftX = Math.min(dp(26f), Math.max(0f, Math.min(focus.x - mapBounds.left - outerRadius, mapBounds.right - focus.x - outerRadius) - dp(8f)));
        float maxShiftY = Math.min(dp(18f), Math.max(0f, Math.min(focus.y - mapBounds.top, mapBounds.bottom - focus.y) - dp(8f)));
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
            float centerX = focus.x + envelope * maxShiftX * (open ? -0.7f : 0.7f);
            float centerY = focus.y + envelope * maxShiftY * 0.45f * (float) Math.sin(Math.PI * 1.5f * eased);
            centerX = Math.max(mapBounds.left + radius, Math.min(mapBounds.right - radius, centerX));
            centerY = Math.max(mapBounds.top + dp(8f), Math.min(mapBounds.bottom - dp(8f), centerY));
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

    private void briefHumanPanOverTraffic(UiObject2 app, String scaleBand) {
        PanEnvelope envelope = panEnvelopeForScaleBand(scaleBand);
        ellipticalPanOverTraffic(app, 24, envelope.widthFraction * 0.55f, envelope.heightFraction * 0.52f, true, envelope.minRadiusDp, envelope.maxRadiusXDp * 0.65f, envelope.maxRadiusYDp * 0.65f);
        sleep(45);
    }

    private void ellipticalPanOverTraffic(UiObject2 app, int steps, float widthFraction, float heightFraction, boolean clockwise, float minRadiusDp, float maxRadiusXDp, float maxRadiusYDp) {
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

    private void scheduleActiveDisplayCapture(String fileName, long delayMs) {
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
    }

    private String currentPerfTargetDescription(String scaleBand) {
        MajorTrafficCity city = currentPerfCity;
        //noinspection StringBufferReplaceableByString
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
        builder.append("map_detail_timing=").append(currentPerfMapDetailTiming).append('\n');
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
        builder.append("target_rule=timetable-backed US/EU major-airport traffic target plus bounded closed gestures for optimizer evidence; discard and rerun if the gesture focus drifts over water, empty terrain, an unintended desert/empty area, or the launcher.\n");
        builder.append("visual_evidence_land_safe=requires_review; screenshots/video must show the active gesture/focus over inland land/traffic. Incidental coastline or water at continent scale is acceptable only when focusLat/focusLon and visible motion stay over the requested inland traffic target.\n");
        builder.append("reject_evidence_if=gesture/focus over ocean/large lake/empty terrain/upper Canada/launcher/home screen, or focusLat/focusLon logs do not match the requested target/focus mode.\n");
        builder.append("phase_name=").append(currentPerfPhaseName).append('\n');
        builder.append("phase_zoom_plan=").append(currentPerfPhaseZoomPlan).append('\n');
        builder.append("phase_gesture_plan=").append(currentPerfPhaseGesturePlan).append('\n');
        builder.append("phase_log_tag=").append(PERF_PHASE_LOG_TAG).append('\n');
        builder.append("scale_bands=continent,country,").append(COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND).append(',').append(COUNTRY_CONTINUITY_FULL_RANGE_BAND).append('\n');
        builder.append("country_phase_note=country launch zoom ").append(COUNTRY_CONTINUITY_COUNTRY_ZOOM).append(" intentionally pinches closed first, so active/quick screenshots can show country-to-global stress frames; do not treat those frames as pure city-centered country visual proof.\n");
        builder.append("regional100mi_phase_note=").append(COUNTRY_CONTINUITY_REGIONAL_100_MI_BAND).append(" launch zoom ").append(COUNTRY_CONTINUITY_REGIONAL_100_MI_ZOOM).append(" targets the 100 mi scale-bar neighborhood and pinches open first so the first active frames stay in the regional transition band before reversing.\n");
        builder.append("full_range_phase_note=").append(COUNTRY_CONTINUITY_FULL_RANGE_BAND).append(" sweeps through the zoom range in/out with anchored pinches, bounded pan checkpoints, and a relaunch/re-anchor over the selected US/EU target before reversing direction so zooming back in/out does not drift to unrelated terrain.\n");
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

    private int instrumentationIntArgument(String name, int defaultValue) {
        String value = InstrumentationRegistry.getArguments().getString(name);
        if (value == null || value.trim().isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
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
        IOException durableFailure;
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

        IOException appFailure;
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
            fallbackFailure.addSuppressed(durableFailure);
            fallbackFailure.addSuppressed(appFailure);
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
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private MajorTrafficCity randomMajorTrafficCity() {
        MajorTrafficCity fixed = fixedCityFromArguments(MAJOR_TRAFFIC_CITIES);
        rejectUnsupportedTargetCityIfRequested(fixed);
        return fixed != null ? fixed : timetableTrafficCity(MAJOR_TRAFFIC_CITIES);
    }

    private MajorTrafficCity randomInlandTrafficCity() {
        MajorTrafficCity fixed = fixedCityFromArguments(INLAND_TRAFFIC_CITIES);
        rejectUnsupportedTargetCityIfRequested(fixed);
        return fixed != null ? fixed : timetableTrafficCity(INLAND_TRAFFIC_CITIES);
    }

    private MajorTrafficCity randomFullRangeLandSafeTrafficCity() {
        MajorTrafficCity fixed = fixedCityFromArguments(FULL_RANGE_LAND_SAFE_TRAFFIC_CITIES);
        if (fixed != null) return fixed;
        String requested = InstrumentationRegistry.getArguments().getString("targetCity");
        if (requested != null && !requested.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "targetCity=" + requested + " is not a full-range land-safe optimizer target. Choose from the timetable-backed US/EU target list."
            );
        }
        return timetableTrafficCity(FULL_RANGE_LAND_SAFE_TRAFFIC_CITIES);
    }

    private void rejectUnsupportedTargetCityIfRequested(MajorTrafficCity fixed) {
        String requested = InstrumentationRegistry.getArguments().getString("targetCity");
        if (fixed == null && requested != null && !requested.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "targetCity=" + requested + " is not a bounded land-safe optimizer target for this test. Choose from the timetable-backed US/EU target list."
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
        //noinspection IfCanBeSwitch
        if ("dfw".equals(key)) return findCity(cities, "Dallas-Fort Worth");
        if ("atl".equals(key)) return findCity(cities, "Atlanta");
        if ("phx".equals(key)) return findCity(cities, "Phoenix");
        if ("las".equals(key) || "vegas".equals(key)) return findCity(cities, "Las Vegas");
        if ("chi".equals(key) || "ord".equals(key)) return findCity(cities, "Chicago");
        if ("nyc".equals(key) || "jfk".equals(key) || "ewr".equals(key) || "lga".equals(key)) return findCity(cities, "New York City");
        if ("la".equals(key) || "lax".equals(key)) return findCity(cities, "Los Angeles");
        if ("lhr".equals(key) || "lon".equals(key)) return findCity(cities, "London");
        if ("ams".equals(key)) return findCity(cities, "Amsterdam");
        if ("fra".equals(key)) return findCity(cities, "Frankfurt");
        if ("cdg".equals(key) || "par".equals(key)) return findCity(cities, "Paris");
        if ("mad".equals(key)) return findCity(cities, "Madrid");
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

    private MajorTrafficCity timetableTrafficCity(MajorTrafficCity[] cities) {
        int utcHour = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                .get(java.util.Calendar.HOUR_OF_DAY);
        //noinspection ExtractMethodRecommender
        String[] preferredNames;
        if ((utcHour >= 5 && utcHour < 15) || (utcHour >= 3 && utcHour < 5)) {
            preferredNames = new String[] {"Frankfurt", "Paris", "Madrid", "London", "Amsterdam"};
        } else if (utcHour >= 15 && utcHour < 22) {
            preferredNames = new String[] {"Atlanta", "Dallas-Fort Worth", "Chicago", "New York City", "Los Angeles", "Phoenix", "Las Vegas"};
        } else {
            preferredNames = new String[] {"Dallas-Fort Worth", "Atlanta", "Los Angeles", "Chicago", "New York City", "Phoenix", "Las Vegas"};
        }
        for (String name : preferredNames) {
            MajorTrafficCity city = findCity(cities, name);
            if (city != null) {
                System.out.println("Timetable-selected traffic city at UTC hour " + utcHour + ": " + city.name);
                return city;
            }
        }
        return cities[0];
    }

    private String normalizeCityName(String value) {
        return value.toLowerCase(java.util.Locale.US).replaceAll("[^a-z0-9]", "");
    }

    private String runShell(String command) throws IOException {
        ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand(command);
        //noinspection TryFinallyCanBeTryWithResources
        try (FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        } finally {
            descriptor.close();
        }
    }

    private void sleep(long ms) {
        SystemClock.sleep(ms);
    }

    //noinspection JUnitTestClassNamingConvention
    @SuppressWarnings("JUnitTestClassNamingConvention")
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

    //noinspection JUnitTestClassNamingConvention
    @SuppressWarnings("JUnitTestClassNamingConvention")
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
