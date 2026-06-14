package com.flightalert.perf;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.ActivityOptions;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.DisplayMetrics;

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
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public class FlightMapGesturePerfTest {
    private static final String PACKAGE_NAME = "com.flightalert";
    private static final long TRAFFIC_LOAD_WAIT_MS = 9000L;
    private static final int QUICK_PINCH_SPEED_PX_PER_SEC = 14000;
    private static final int SMOOTH_PINCH_SPEED_PX_PER_SEC = 3200;
    private static final int PAN_STEPS = 44;
    private static final MajorTrafficCity[] MAJOR_TRAFFIC_CITIES = new MajorTrafficCity[] {
            new MajorTrafficCity("New York City", 40.73, -73.93),
            new MajorTrafficCity("Chicago", 41.88, -87.63),
            new MajorTrafficCity("Los Angeles", 33.94, -118.40),
            new MajorTrafficCity("Dallas-Fort Worth", 32.90, -97.04),
            new MajorTrafficCity("Atlanta", 33.64, -84.43),
            new MajorTrafficCity("Toronto", 43.68, -79.63),
            new MajorTrafficCity("Mexico City", 19.44, -99.07),
            new MajorTrafficCity("London", 51.47, -0.45),
            new MajorTrafficCity("Paris", 49.01, 2.55),
            new MajorTrafficCity("Amsterdam", 52.31, 4.77),
            new MajorTrafficCity("Frankfurt", 50.04, 8.56),
            new MajorTrafficCity("Madrid", 40.49, -3.57)
    };
    private static final MajorTrafficCity[] INLAND_TRAFFIC_CITIES = new MajorTrafficCity[] {
            new MajorTrafficCity("Chicago", 41.88, -87.63),
            new MajorTrafficCity("Dallas-Fort Worth", 32.90, -97.04),
            new MajorTrafficCity("Atlanta", 33.64, -84.43),
            new MajorTrafficCity("Frankfurt", 50.04, 8.56),
            new MajorTrafficCity("Paris", 49.01, 2.55)
    };

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
        MajorTrafficCity city = randomInlandTrafficCity();
        UiObject2 app = startAppAtMajorTraffic(city, 5.4);
        sleep(TRAFFIC_LOAD_WAIT_MS);
        captureActiveDisplay("flightalert-perf-quickZoomJumpsOverTraffic-before.png");
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
        captureActiveDisplay("flightalert-perf-quickZoomJumpsOverTraffic.png");
        capturePerfArtifacts("quickZoomJumpsOverTraffic");
    }

    @Test
    public void zoomLowToHighSweep() throws Exception {
        MajorTrafficCity city = randomInlandTrafficCity();
        UiObject2 app = startAppAtMajorTraffic(city, 4.8);
        sleep(TRAFFIC_LOAD_WAIT_MS);
        clearPerfCounters();

        for (int i = 0; i < 10; i++) {
            smoothPinchOpen(app);
            sleep(170);
        }
        sleep(900);
        for (int i = 0; i < 9; i++) {
            smoothPinchClose(app);
            sleep(170);
        }
        sleep(1200);
        requireFlightAlertForeground();
        captureActiveDisplay("flightalert-perf-zoomLowToHighSweep.png");
        capturePerfArtifacts("zoomLowToHighSweep");
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
        System.out.println("Testing major traffic city: " + city.name);
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("com.flightalert.PERF_LAT", Double.toString(city.lat))
                .putExtra("com.flightalert.PERF_LON", Double.toString(city.lon))
                .putExtra("com.flightalert.PERF_ZOOM", Double.toString(zoom));
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(0);
        context.startActivity(intent, options.toBundle());
        instrumentation.waitForIdleSync();
        waitForFlightAlertForeground();
        return flightAlertRoot();
    }

    private UiObject2 flightAlertRoot() throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
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
        app.pinchOpen(0.72f, QUICK_PINCH_SPEED_PX_PER_SEC);
    }

    private void quickPinchClose(UiObject2 app) {
        app.pinchClose(0.72f, QUICK_PINCH_SPEED_PX_PER_SEC);
    }

    private void smoothPinchOpen(UiObject2 app) {
        app.pinchOpen(0.64f, SMOOTH_PINCH_SPEED_PX_PER_SEC);
    }

    private void smoothPinchClose(UiObject2 app) {
        app.pinchClose(0.64f, SMOOTH_PINCH_SPEED_PX_PER_SEC);
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

    private void clearPerfCounters() throws Exception {
        runShell("logcat -c");
        runShell("dumpsys gfxinfo " + PACKAGE_NAME + " reset");
    }

    private void waitForFlightAlertForeground() throws Exception {
        long deadline = SystemClock.uptimeMillis() + 7000L;
        while (SystemClock.uptimeMillis() < deadline) {
            if (isFlightAlertForeground()) return;
            sleep(120);
        }
        requireFlightAlertForeground();
    }

    private void requireFlightAlertForeground() throws Exception {
        assertTrue("Refusing to run gestures outside Flight Alert", isFlightAlertForeground());
    }

    private boolean isFlightAlertForeground() throws Exception {
        String activity = runShell("dumpsys activity activities");
        String activityLine = PACKAGE_NAME + "/" + PACKAGE_NAME + ".MainActivity";
        String[] lines = activity.split("\\r?\\n");
        for (String line : lines) {
            if ((line.contains("mCurrentFocus=") ||
                    line.contains("topResumedActivity=") ||
                    line.contains("ResumedActivity:")) &&
                    line.contains(activityLine)) {
                return true;
            }
        }
        return false;
    }

    private void captureActiveDisplay(String fileName) throws Exception {
        runShell("screencap -d 4630946481096930692 -p /sdcard/" + fileName);
    }

    private void capturePerfArtifacts(String testName) throws Exception {
        writeExternalText("flightalert-perf-" + testName + "-gfxinfo.txt", runShell("dumpsys gfxinfo " + PACKAGE_NAME));
        writeExternalText("flightalert-perf-" + testName + "-activity.txt", runShell("dumpsys activity activities"));
        writeExternalText("flightalert-perf-" + testName + "-logcat.txt", runShell("logcat -d -t 3000"));
    }

    private void writeExternalText(String fileName, String value) throws IOException {
        File directory = InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null);
        if (directory == null) {
            throw new IOException("External files directory is unavailable");
        }
        File file = new File(directory, fileName);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes("UTF-8"));
        }
        System.out.println("Wrote artifact: " + file.getAbsolutePath());
    }

    private MajorTrafficCity randomMajorTrafficCity() {
        return randomCityFrom(MAJOR_TRAFFIC_CITIES);
    }

    private MajorTrafficCity randomInlandTrafficCity() {
        return randomCityFrom(INLAND_TRAFFIC_CITIES);
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
