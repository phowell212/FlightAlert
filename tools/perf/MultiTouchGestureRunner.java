package com.flightalert.perf;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

import java.lang.reflect.Method;

public final class MultiTouchGestureRunner {
    private static final int INJECT_WAIT_FOR_FINISH = 2;
    private static final int SOURCE = InputDevice.SOURCE_TOUCHSCREEN;

    private final Object inputManager;
    private final Method injectInputEvent;
    private final Method setDisplayId;
    private final int displayId;

    private MultiTouchGestureRunner(int displayId) throws Exception {
        this.displayId = displayId;
        inputManager = InputManager.class.getMethod("getInstance").invoke(null);
        injectInputEvent = InputManager.class.getMethod("injectInputEvent", android.view.InputEvent.class, int.class);
        Method displayMethod = null;
        try {
            displayMethod = android.view.InputEvent.class.getDeclaredMethod("setDisplayId", int.class);
            displayMethod.setAccessible(true);
        } catch (NoSuchMethodException ignored) {
            // Older Android API stubs hide this method; injection still works on single-display devices.
        }
        setDisplayId = displayMethod;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: MultiTouchGestureRunner <quick|pinch|sweep|stress|select> <width> <height>");
        }
        String mode = args[0];
        int width = Integer.parseInt(args[1]);
        int height = Integer.parseInt(args[2]);
        int displayId = args.length >= 4 ? Integer.parseInt(args[3]) : 0;
        MultiTouchGestureRunner runner = new MultiTouchGestureRunner(displayId);
        if ("quick".equals(mode)) {
            runner.quickZoom(width, height);
        } else if ("pinch".equals(mode)) {
            runner.basicPinch(width, height);
        } else if ("sweep".equals(mode)) {
            runner.sweepZoom(width, height);
        } else if ("stress".equals(mode)) {
            runner.stressZoomAndPan(width, height);
        } else if ("select".equals(mode)) {
            runner.selectAndPan(width, height);
        } else {
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }
    }

    private void quickZoom(int width, int height) throws Exception {
        int cx = width / 2;
        int cy = Math.max(560, Math.min(height - 760, (int) (height * 0.50f)));
        int near = Math.max(54, width / 13);
        int far = Math.max(190, width / 3);
        for (int i = 0; i < 5; i++) {
            pinchIn(cx, cy, near, far, 12, 7);
            SystemClock.sleep(90);
        }
        SystemClock.sleep(450);
        for (int cycle = 0; cycle < 4; cycle++) {
            pinchOut(cx, cy, near, far, 12, 7);
            SystemClock.sleep(100);
            pinchIn(cx, cy, near, far, 12, 7);
            SystemClock.sleep(130);
        }
        SystemClock.sleep(700);
    }

    private void stressZoomAndPan(int width, int height) throws Exception {
        int cx = width / 2;
        int cy = Math.max(560, Math.min(height - 760, (int) (height * 0.50f)));
        int near = Math.max(54, width / 13);
        int far = Math.max(190, width / 3);
        for (int i = 0; i < 2; i++) {
            pinchIn(cx, cy, near, far, 28, 8);
            SystemClock.sleep(90);
        }
        for (int level = 0; level < 3; level++) {
            panCorridor(width, height);
            pinchOut(cx, cy, near, far, 30, 8);
            SystemClock.sleep(140);
            panCorridor(width, height);
            pinchIn(cx, cy, near, far, 30, 8);
            SystemClock.sleep(140);
        }
        SystemClock.sleep(800);
    }

    private void sweepZoom(int width, int height) throws Exception {
        int cx = width / 2;
        int cy = Math.max(560, Math.min(height - 760, (int) (height * 0.50f)));
        int near = Math.max(54, width / 13);
        int far = Math.max(190, width / 3);
        for (int i = 0; i < 8; i++) {
            pinchOut(cx, cy, near, far, 34, 8);
            SystemClock.sleep(110);
        }
        SystemClock.sleep(420);
        for (int i = 0; i < 8; i++) {
            pinchIn(cx, cy, near, far, 34, 8);
            SystemClock.sleep(110);
        }
        SystemClock.sleep(800);
    }

    private void panCorridor(int width, int height) throws Exception {
        int left = Math.max(110, (int) (width * 0.42f));
        int right = Math.min(width - 110, (int) (width * 0.58f));
        int top = Math.max(420, (int) (height * 0.45f));
        int middle = Math.max(560, Math.min(height - 760, (int) (height * 0.50f)));
        int bottom = Math.min(height - 620, (int) (height * 0.55f));
        drag(right, middle, left, middle, 20, 7);
        SystemClock.sleep(60);
        drag(left, middle, right, middle, 20, 7);
        SystemClock.sleep(60);
        drag(width / 2, bottom, width / 2, top, 20, 7);
        SystemClock.sleep(60);
        drag(width / 2, top, width / 2, bottom, 20, 7);
        SystemClock.sleep(60);
        drag(right, bottom, left, top, 20, 7);
        SystemClock.sleep(60);
        drag(left, top, right, bottom, 20, 7);
        SystemClock.sleep(60);
        drag(left, bottom, right, top, 20, 7);
        SystemClock.sleep(60);
        drag(right, top, left, bottom, 20, 7);
        SystemClock.sleep(90);
    }

    private void selectAndPan(int width, int height) throws Exception {
        int[] xs = new int[] {
                Math.max(72, (int) (width * 0.24f)),
                Math.max(72, (int) (width * 0.38f)),
                width / 2,
                Math.min(width - 72, (int) (width * 0.62f)),
                Math.min(width - 72, (int) (width * 0.76f))
        };
        int[] ys = new int[] {
                Math.max(360, (int) (height * 0.28f)),
                Math.max(480, (int) (height * 0.38f)),
                Math.max(560, Math.min(height - 780, (int) (height * 0.48f))),
                Math.min(height - 700, (int) (height * 0.58f))
        };
        for (int round = 0; round < 3; round++) {
            for (int y : ys) {
                for (int x : xs) {
                    tap(x, y);
                    SystemClock.sleep(85);
                }
            }
            panCorridor(width, height);
            SystemClock.sleep(160);
        }
        SystemClock.sleep(900);
    }

    private void basicPinch(int width, int height) throws Exception {
        int cx = width / 2;
        int cy = Math.max(560, Math.min(height - 760, (int) (height * 0.50f)));
        int near = Math.max(56, width / 12);
        int far = Math.max(190, width / 3);
        pinchOut(cx, cy, near, far, 48, 8);
        SystemClock.sleep(350);
        pinchIn(cx, cy, near, far, 48, 8);
        SystemClock.sleep(350);
        pinchOut(cx, cy, near, far, 48, 8);
        SystemClock.sleep(350);
        pinchIn(cx, cy, near, far, 48, 8);
        SystemClock.sleep(700);
    }

    private void pinchOut(int cx, int cy, int near, int far, int steps, long frameMs) throws Exception {
        gesture(cx, cy, near, far, steps, frameMs);
    }

    private void pinchIn(int cx, int cy, int near, int far, int steps, long frameMs) throws Exception {
        gesture(cx, cy, far, near, steps, frameMs);
    }

    private void gesture(int cx, int cy, int startDistance, int endDistance, int steps, long frameMs) throws Exception {
        long downTime = SystemClock.uptimeMillis();
        Pointer a = new Pointer(0, cx - startDistance, cy - startDistance);
        Pointer b = new Pointer(1, cx + startDistance, cy + startDistance);
        inject(downTime, MotionEvent.ACTION_DOWN, a);
        inject(downTime, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), a, b);
        for (int step = 1; step <= steps; step++) {
            float progress = step / (float) steps;
            int distance = Math.round(startDistance + (endDistance - startDistance) * progress);
            a = new Pointer(0, cx - distance, cy - distance);
            b = new Pointer(1, cx + distance, cy + distance);
            inject(downTime, MotionEvent.ACTION_MOVE, a, b);
            SystemClock.sleep(frameMs);
        }
        inject(downTime, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), a, b);
        inject(downTime, MotionEvent.ACTION_UP, a);
    }

    private void drag(int startX, int startY, int endX, int endY, int steps, long frameMs) throws Exception {
        long downTime = SystemClock.uptimeMillis();
        Pointer pointer = new Pointer(0, startX, startY);
        inject(downTime, MotionEvent.ACTION_DOWN, pointer);
        for (int step = 1; step <= steps; step++) {
            float progress = step / (float) steps;
            pointer = new Pointer(
                    0,
                    Math.round(startX + (endX - startX) * progress),
                    Math.round(startY + (endY - startY) * progress)
            );
            inject(downTime, MotionEvent.ACTION_MOVE, pointer);
            SystemClock.sleep(frameMs);
        }
        inject(downTime, MotionEvent.ACTION_UP, pointer);
    }

    private void tap(int x, int y) throws Exception {
        long downTime = SystemClock.uptimeMillis();
        Pointer pointer = new Pointer(0, x, y);
        inject(downTime, MotionEvent.ACTION_DOWN, pointer);
        SystemClock.sleep(28);
        inject(downTime, MotionEvent.ACTION_UP, pointer);
    }

    private void inject(long downTime, int action, Pointer... pointers) throws Exception {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[pointers.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pointers.length];
        for (int i = 0; i < pointers.length; i++) {
            MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
            props.id = pointers[i].id;
            props.toolType = MotionEvent.TOOL_TYPE_FINGER;
            properties[i] = props;

            MotionEvent.PointerCoords point = new MotionEvent.PointerCoords();
            point.x = pointers[i].x;
            point.y = pointers[i].y;
            point.pressure = 1f;
            point.size = 1f;
            coords[i] = point;
        }
        MotionEvent event = MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                pointers.length,
                properties,
                coords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                SOURCE,
                0
        );
        if (setDisplayId != null) {
            setDisplayId.invoke(event, displayId);
        }
        try {
            Object result = injectInputEvent.invoke(inputManager, event, INJECT_WAIT_FOR_FINISH);
            if (result instanceof Boolean && !((Boolean) result)) {
                throw new IllegalStateException("InputManager rejected touch injection on display " + displayId);
            }
        } finally {
            event.recycle();
        }
    }

    private static final class Pointer {
        final int id;
        final int x;
        final int y;

        Pointer(int id, int x, int y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }
    }
}
