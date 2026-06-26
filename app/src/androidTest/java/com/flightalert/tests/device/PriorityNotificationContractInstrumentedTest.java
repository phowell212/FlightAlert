package com.flightalert.tests.device;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import com.flightalert.alerts.AlertAircraft;
import com.flightalert.alerts.AlertAircraftClassifier;
import com.flightalert.alerts.AircraftAlertService;
import com.flightalert.alerts.MonitoringNotificationHiderService;
import com.flightalert.alerts.PriorityNotificationPresenter;
import com.flightalert.ui.FlightAlertSettings;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class PriorityNotificationContractInstrumentedTest {
    private Context context;
    private NotificationManager manager;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runShellCommand("pm grant " + context.getPackageName() + " " + Manifest.permission.POST_NOTIFICATIONS);
            InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation()
                    .adoptShellPermissionIdentity(Manifest.permission.POST_NOTIFICATIONS);
        }
        manager = context.getSystemService(NotificationManager.class);
        AircraftAlertService.Companion.stop(context);
        runShellCommand("cmd notification allow_listener " + watcherHiderComponent());
        SystemClock.sleep(500L);
        assertTrue(MonitoringNotificationHiderService.Companion.is_enabled(context));
        manager.createNotificationChannel(
                new NotificationChannel(
                        AircraftAlertService.PRIORITY_CHANNEL_ID,
                        "Extreme priority aircraft",
                        NotificationManager.IMPORTANCE_HIGH
                )
        );
        manager.cancel(AircraftAlertService.PRIORITY_NOTIFICATION_ID);
        manager.cancel(AircraftAlertService.ONGOING_NOTIFICATION_ID);
        waitForNotification(false);
    }

    @After
    public void tearDown() {
        if (manager != null) {
            AircraftAlertService.Companion.stop(context);
            manager.cancel(AircraftAlertService.PRIORITY_NOTIFICATION_ID);
            manager.cancel(AircraftAlertService.ONGOING_NOTIFICATION_ID);
            waitForNotification(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
        }
    }

    @Test
    public void priorityNotificationExistsOnlyWhileExtremeAircraftRemain() {
        applyPriorityNotification(Collections.singletonList(aircraft("NEAR1", "N-NEAR", 900.0, false)));
        assertFalse(hasPriorityNotification());

        applyPriorityNotification(Collections.singletonList(aircraft("EXT1", "N-EXT", 1200.0, true)));
        assertTrue(notificationTextContains("N-EXT 1200 ft"));

        applyPriorityNotification(Collections.singletonList(aircraft("EXT1", "N-EXT", 1500.0, true)));
        assertTrue(notificationTextContains("N-EXT 1500 ft"));

        applyPriorityNotification(Collections.emptyList());
        assertFalse(hasPriorityNotification());
    }

    @Test
    public void serviceSnapshotPublishesAndClearsExtremePriorityNotification() throws Exception {
        FlightAlertSettings.INSTANCE.prefs(context)
                .edit()
                .putBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, true)
                .putBoolean(FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED, true)
                .apply();

        AircraftAlertService.Companion.publish_priority_snapshot(
                context,
                Collections.singletonList(aircraft("EXT2", "N-SNAP", 1800.0, true, true))
        );
        assertTrue(notificationTextContains("N-SNAP 1800 ft est."));
        waitForSystemNotificationVisible(AircraftAlertService.PRIORITY_NOTIFICATION_ID, true);
        assertTrue(systemNotificationVisible(AircraftAlertService.PRIORITY_NOTIFICATION_ID));
        assertTrue(serviceForegroundNotificationIdIs(AircraftAlertService.PRIORITY_NOTIFICATION_ID));

        AircraftAlertService.Companion.publish_priority_snapshot(context, Collections.emptyList());
        SystemClock.sleep(700L);
        waitForNotification(AircraftAlertService.PRIORITY_NOTIFICATION_ID, false);
        waitForSystemNotificationVisible(AircraftAlertService.ONGOING_NOTIFICATION_ID, false);
        assertFalse(hasPriorityNotification());
        assertFalse(systemNotificationVisible(AircraftAlertService.ONGOING_NOTIFICATION_ID));
        assertTrue(serviceForegroundNotificationIdIs(AircraftAlertService.ONGOING_NOTIFICATION_ID));
    }

    @Test
    public void serviceStartDoesNotPublishWatchingNotification() throws Exception {
        FlightAlertSettings.INSTANCE.prefs(context)
                .edit()
                .putBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, true)
                .putBoolean(FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED, true)
                .apply();

        AircraftAlertService.Companion.start(context);
        SystemClock.sleep(700L);
        waitForSystemNotificationVisible(AircraftAlertService.ONGOING_NOTIFICATION_ID, false);

        assertFalse(systemNotificationVisible(AircraftAlertService.ONGOING_NOTIFICATION_ID));
        assertFalse(hasPriorityNotification());
        assertTrue(serviceForegroundNotificationIdIs(AircraftAlertService.ONGOING_NOTIFICATION_ID));
        AircraftAlertService.Companion.stop(context);
    }

    private void applyPriorityNotification(List<AlertAircraft> priorityAircraft) {
        List<AlertAircraft> extremePriorityAircraft =
                PriorityNotificationPresenter.INSTANCE.extreme_priority_aircraft(priorityAircraft);
        if (!AlertAircraftClassifier.INSTANCE.should_show_persistent_priority_notification(
                true,
                extremePriorityAircraft,
                true
        )) {
            manager.cancel(AircraftAlertService.PRIORITY_NOTIFICATION_ID);
            waitForNotification(AircraftAlertService.PRIORITY_NOTIFICATION_ID, false);
            return;
        }
        Notification notification = new Notification.Builder(context, AircraftAlertService.PRIORITY_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Extreme priority aircraft")
                .setContentText(PriorityNotificationPresenter.INSTANCE.notification_body(extremePriorityAircraft))
                .setStyle(new Notification.BigTextStyle().bigText(
                        PriorityNotificationPresenter.INSTANCE.notification_body(extremePriorityAircraft)
                ))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
        manager.notify(AircraftAlertService.PRIORITY_NOTIFICATION_ID, notification);
        waitForNotification(AircraftAlertService.PRIORITY_NOTIFICATION_ID, true);
    }

    private boolean notificationTextContains(String expected) {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications()) {
                if (active.getId() != AircraftAlertService.PRIORITY_NOTIFICATION_ID) continue;
                CharSequence title = active.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence text = active.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT);
                if (contains(title, "Extreme priority aircraft") && contains(text, expected))
                    return true;
            }
            SystemClock.sleep(80L);
        }
        return false;
    }

    private boolean hasPriorityNotification() {
        return hasNotification(AircraftAlertService.PRIORITY_NOTIFICATION_ID);
    }

    private boolean hasNotification(int notificationId) {
        for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications()) {
            if (active.getId() == notificationId) return true;
        }
        return false;
    }

    private void waitForNotification(boolean expected) {
        waitForNotification(AircraftAlertService.PRIORITY_NOTIFICATION_ID, expected);
    }

    private void waitForNotification(int notificationId, boolean expected) {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            if (hasNotification(notificationId) == expected) return;
            SystemClock.sleep(80L);
        }
    }

    private void waitForSystemNotificationVisible(int notificationId, boolean expected) throws Exception {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            if (systemNotificationVisible(notificationId) == expected) return;
            SystemClock.sleep(80L);
        }
    }

    private boolean systemNotificationVisible(int notificationId) throws Exception {
        String output = runShellCommandOutput("cmd notification list");
        return output.contains(context.getPackageName()) && output.contains(String.valueOf(notificationId));
    }

    private boolean serviceForegroundNotificationIdIs(int notificationId) throws Exception {
        String output = runShellCommandOutput("dumpsys activity services " + context.getPackageName());
        return output.contains("AircraftAlertService") &&
                output.contains("isForeground=true foregroundId=" + notificationId);
    }

    private boolean contains(CharSequence value, String expected) {
        return value != null && value.toString().contains(expected);
    }

    private void runShellCommand(String command) throws Exception {
        ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand(command);
        try (InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            byte[] buffer = new byte[256];
            int bytesRead;
            do {
                bytesRead = stream.read(buffer);
            } while (bytesRead != -1);
        }
    }

    private String runShellCommandOutput(String command) throws Exception {
        ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand(command);
        StringBuilder output = new StringBuilder();
        try (InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = stream.read(buffer)) != -1) {
                output.append(new String(buffer, 0, bytesRead, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return output.toString();
    }

    private String watcherHiderComponent() {
        return new ComponentName(context, MonitoringNotificationHiderService.class).flattenToString();
    }

    private AlertAircraft aircraft(String callsign, String registration, double altitudeFeet, boolean extreme) {
        return aircraft(callsign, registration, altitudeFeet, extreme, false);
    }

    private AlertAircraft aircraft(String callsign, String registration, double altitudeFeet, boolean extreme, boolean estimated) {
        return new AlertAircraft(
                callsign.toLowerCase(java.util.Locale.US),
                callsign,
                registration,
                500.0,
                altitudeFeet,
                25.0,
                1.0,
                extreme,
                true,
                extreme,
                estimated
        );
    }
}
