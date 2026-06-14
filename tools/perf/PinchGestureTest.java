package com.flightalert.perf;

import android.graphics.Point;

import com.android.uiautomator.core.UiDevice;
import com.android.uiautomator.core.UiObject;
import com.android.uiautomator.core.UiSelector;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;

public class PinchGestureTest extends UiAutomatorTestCase {
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

    public void testPinchInAndOut() throws Exception {
        MajorTrafficCity city = randomMajorTrafficCity();
        startAppAtMajorTraffic(city, 5.4);
        sleep(9000);
        Runtime.getRuntime().exec(new String[] { "logcat", "-c" }).waitFor();
        Runtime.getRuntime().exec(new String[] { "dumpsys", "gfxinfo", "com.flightalert", "reset" }).waitFor();

        UiDevice device = getUiDevice();
        UiObject app = flightAlertRoot(device);

        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int cx = trafficFocusX(width);
        int cy = trafficFocusY(height);
        int near = Math.max(56, width / 12);
        int far = Math.max(190, width / 3);

        pinchOut(app, cx, cy, near, far, 72);
        sleep(650);
        pinchIn(app, cx, cy, near, far, 72);
        sleep(650);
        pinchOut(app, cx, cy, near, far, 72);
        sleep(650);
        pinchIn(app, cx, cy, near, far, 72);
        sleep(1000);
        requireFlightAlertForeground(device);
    }

    public void testZoomLowToHighSweep() throws Exception {
        MajorTrafficCity city = randomMajorTrafficCity();
        startAppAtMajorTraffic(city, 4.8);
        sleep(9000);

        UiDevice device = getUiDevice();
        UiObject app = flightAlertRoot(device);

        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int cx = trafficFocusX(width);
        int cy = trafficFocusY(height);
        int near = Math.max(54, width / 13);
        int far = Math.max(170, width / 3);

        for (int i = 0; i < 6; i++) {
            pinchIn(app, cx, cy, near, far, 56);
            sleep(160);
        }
        sleep(650);
        Runtime.getRuntime().exec(new String[] { "logcat", "-c" }).waitFor();
        Runtime.getRuntime().exec(new String[] { "dumpsys", "gfxinfo", "com.flightalert", "reset" }).waitFor();

        for (int i = 0; i < 10; i++) {
            pinchOut(app, cx, cy, near, far, 62);
            sleep(180);
        }
        sleep(1200);
        requireFlightAlertForeground(device);
    }

    public void testPanAcrossZoomLevels() throws Exception {
        MajorTrafficCity city = randomMajorTrafficCity();
        startAppAtMajorTraffic(city, 5.5);
        sleep(9000);

        UiDevice device = getUiDevice();
        UiObject app = flightAlertRoot(device);

        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int cx = trafficFocusX(width);
        int cy = trafficFocusY(height);
        int near = Math.max(54, width / 13);
        int far = Math.max(170, width / 3);

        for (int i = 0; i < 5; i++) {
            pinchIn(app, cx, cy, near, far, 48);
            sleep(120);
        }
        sleep(650);
        Runtime.getRuntime().exec(new String[] { "logcat", "-c" }).waitFor();
        Runtime.getRuntime().exec(new String[] { "dumpsys", "gfxinfo", "com.flightalert", "reset" }).waitFor();

        for (int level = 0; level < 4; level++) {
            panMajorCityCorridor(device, width, height);
            sleep(180);
            pinchOut(app, cx, cy, near, far, 52);
            sleep(260);
        }
        panMajorCityCorridor(device, width, height);
        sleep(1200);
        requireFlightAlertForeground(device);
    }

    public void testQuickZoomJumpsOverTraffic() throws Exception {
        MajorTrafficCity city = randomMajorTrafficCity();
        startAppAtMajorTraffic(city, 5.4);
        sleep(9000);

        UiDevice device = getUiDevice();
        UiObject app = flightAlertRoot(device);

        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int cx = trafficFocusX(width);
        int cy = trafficFocusY(height);
        int near = Math.max(54, width / 13);
        int far = Math.max(190, width / 3);

        for (int i = 0; i < 6; i++) {
            pinchIn(app, cx, cy, near, far, 42);
            sleep(100);
        }
        for (int i = 0; i < 3; i++) {
            pinchOut(app, cx, cy, near, far, 42);
            sleep(140);
        }
        sleep(650);
        Runtime.getRuntime().exec(new String[] { "logcat", "-c" }).waitFor();
        Runtime.getRuntime().exec(new String[] { "dumpsys", "gfxinfo", "com.flightalert", "reset" }).waitFor();

        for (int cycle = 0; cycle < 4; cycle++) {
            pinchIn(app, cx, cy, near, far, 16);
            sleep(90);
            pinchIn(app, cx, cy, near, far, 16);
            sleep(160);
            pinchOut(app, cx, cy, near, far, 16);
            sleep(90);
            pinchOut(app, cx, cy, near, far, 16);
            sleep(180);
            panMajorCityTrafficPatch(device, width, height);
            sleep(160);
        }
        for (int i = 0; i < 5; i++) {
            pinchIn(app, cx, cy, near, far, 28);
            sleep(110);
        }
        sleep(1200);
        requireFlightAlertForeground(device);
    }

    private MajorTrafficCity randomMajorTrafficCity() {
        long seed = System.currentTimeMillis() ^ System.nanoTime();
        int index = (int) Math.abs(seed % MAJOR_TRAFFIC_CITIES.length);
        return MAJOR_TRAFFIC_CITIES[index];
    }

    private void startAppAtMajorTraffic(MajorTrafficCity city, double zoom) throws Exception {
        System.out.println("Testing major traffic city: " + city.name);
        Runtime.getRuntime().exec(new String[] {
                "am", "start", "--display", "0", "--activity-single-top", "-n", "com.flightalert/.MainActivity",
                "--es", "com.flightalert.PERF_LAT", Double.toString(city.lat),
                "--es", "com.flightalert.PERF_LON", Double.toString(city.lon),
                "--es", "com.flightalert.PERF_ZOOM", Double.toString(zoom)
        }).waitFor();
        UiDevice device = getUiDevice();
        device.waitForWindowUpdate("com.flightalert", 5000);
        waitForFlightAlertForeground(device, 8000);
    }

    private UiObject flightAlertRoot(UiDevice device) throws Exception {
        waitForFlightAlertForeground(device, 5000);
        UiObject app = new UiObject(new UiSelector().packageName("com.flightalert").instance(0));
        if (!app.waitForExists(5000)) {
            throw new IllegalStateException("Flight Alert root view was not found");
        }
        waitForFlightAlertForeground(device, 3000);
        return app;
    }

    private void waitForFlightAlertForeground(UiDevice device, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String currentPackage = device.getCurrentPackageName();
        while (!"com.flightalert".equals(currentPackage) && System.currentTimeMillis() < deadline) {
            sleep(250);
            currentPackage = device.getCurrentPackageName();
        }
        if (!"com.flightalert".equals(currentPackage)) {
            throw new IllegalStateException("Refusing to run gestures outside Flight Alert; current package=" + currentPackage);
        }
    }

    private void requireFlightAlertForeground(UiDevice device) {
        String currentPackage = device.getCurrentPackageName();
        if (!"com.flightalert".equals(currentPackage)) {
            throw new IllegalStateException("Refusing to run gestures outside Flight Alert; current package=" + currentPackage);
        }
    }

    private int trafficFocusX(int width) {
        return width / 2;
    }

    private int trafficFocusY(int height) {
        return Math.max(560, Math.min(height - 760, (int) (height * 0.50f)));
    }

    private void pinchOut(UiObject app, int cx, int cy, int near, int far, int steps) {
        app.performTwoPointerGesture(
                new Point(cx - near, cy - near),
                new Point(cx + near, cy + near),
                new Point(cx - far, cy - far),
                new Point(cx + far, cy + far),
                steps
        );
    }

    private void pinchIn(UiObject app, int cx, int cy, int near, int far, int steps) {
        app.performTwoPointerGesture(
                new Point(cx - far, cy - far),
                new Point(cx + far, cy + far),
                new Point(cx - near, cy - near),
                new Point(cx + near, cy + near),
                steps
        );
    }

    private void panMajorCityCorridor(UiDevice device, int width, int height) throws Exception {
        // Exercise busy city/corridor map areas and visible aircraft, not empty ocean or polar regions.
        // Include horizontal, vertical, and diagonal pans so cache reuse is tested in every direction.
        int left = Math.max(70, (int) (width * 0.34f));
        int right = Math.min(width - 70, (int) (width * 0.66f));
        int top = Math.max(340, (int) (height * 0.40f));
        int middle = trafficFocusY(height);
        int bottom = Math.min(height - 560, (int) (height * 0.58f));
        device.swipe(right, top, left, top, 34);
        sleep(90);
        device.swipe(left, top, right, top, 34);
        sleep(90);
        device.swipe(right, middle, left, middle, 34);
        sleep(90);
        device.swipe(left, middle, right, middle, 34);
        sleep(90);
        device.swipe(right, bottom, left, bottom, 34);
        sleep(90);
        device.swipe(left, bottom, right, bottom, 34);
        sleep(90);
        device.swipe(width / 2, bottom, width / 2, top, 34);
        sleep(90);
        device.swipe(width / 2, top, width / 2, bottom, 34);
        sleep(90);
        device.swipe(right, bottom, left, top, 34);
        sleep(90);
        device.swipe(left, top, right, bottom, 34);
        sleep(90);
        device.swipe(left, bottom, right, top, 34);
        sleep(90);
        device.swipe(right, top, left, bottom, 34);
        sleep(90);
    }

    private void panMajorCityTrafficPatch(UiDevice device, int width, int height) throws Exception {
        int left = Math.max(90, (int) (width * 0.42f));
        int right = Math.min(width - 90, (int) (width * 0.58f));
        int top = Math.max(380, (int) (height * 0.44f));
        int middle = trafficFocusY(height);
        int bottom = Math.min(height - 600, (int) (height * 0.54f));
        device.swipe(right, middle, left, middle, 20);
        sleep(70);
        device.swipe(left, middle, right, middle, 20);
        sleep(70);
        device.swipe(width / 2, bottom, width / 2, top, 20);
        sleep(70);
        device.swipe(width / 2, top, width / 2, bottom, 20);
        sleep(70);
        device.swipe(left, top, right, bottom, 20);
        sleep(70);
        device.swipe(right, bottom, left, top, 20);
        sleep(70);
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
