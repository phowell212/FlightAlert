package com.flightalert.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class PriorityNotificationContractInstrumentedTest {
    private Context context;
    private NotificationManager manager;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation()
                    .adoptShellPermissionIdentity(Manifest.permission.POST_NOTIFICATIONS);
        }
        manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(
                new NotificationChannel(
                        AircraftAlertService.PRIORITY_CHANNEL_ID,
                        "Extreme priority aircraft",
                        NotificationManager.IMPORTANCE_HIGH
                )
        );
        manager.cancel(AircraftAlertService.PRIORITY_NOTIFICATION_ID);
    }

    @After
    public void tearDown() {
        if (manager != null) {
            manager.cancel(AircraftAlertService.PRIORITY_NOTIFICATION_ID);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
        }
    }

    @Test
    public void priorityNotificationExistsOnlyWhileExtremeAircraftRemain() throws Exception {
        applyPriorityNotification(Collections.singletonList(aircraft("NEAR1", "N-NEAR", 900.0, false)));
        assertFalse(hasPriorityNotification());

        applyPriorityNotification(Collections.singletonList(aircraft("EXT1", "N-EXT", 1200.0, true)));
        assertTrue(notificationTextContains("N-EXT 1200 ft"));

        applyPriorityNotification(Collections.singletonList(aircraft("EXT1", "N-EXT", 1500.0, true)));
        assertTrue(notificationTextContains("N-EXT 1500 ft"));

        applyPriorityNotification(Collections.<AlertAircraft>emptyList());
        assertFalse(hasPriorityNotification());
    }

    private void applyPriorityNotification(List<AlertAircraft> priorityAircraft) throws Exception {
        List<AlertAircraft> extremePriorityAircraft =
                PriorityNotificationPresenter.INSTANCE.extreme_priority_aircraft(priorityAircraft);
        if (!AlertAircraftClassifier.INSTANCE.should_show_persistent_priority_notification(
                true,
                extremePriorityAircraft,
                true
        )) {
            manager.cancel(AircraftAlertService.PRIORITY_NOTIFICATION_ID);
            waitForNotification(false);
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
        waitForNotification(true);
    }

    private boolean notificationTextContains(String expected) throws Exception {
        waitForNotification(true);
        for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications()) {
            if (active.getId() != AircraftAlertService.PRIORITY_NOTIFICATION_ID) continue;
            CharSequence title = active.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = active.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT);
            return contains(title, "Extreme priority aircraft") && contains(text, expected);
        }
        return false;
    }

    private boolean hasPriorityNotification() {
        for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications()) {
            if (active.getId() == AircraftAlertService.PRIORITY_NOTIFICATION_ID) return true;
        }
        return false;
    }

    private void waitForNotification(boolean expected) throws Exception {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            if (hasPriorityNotification() == expected) return;
            Thread.sleep(80L);
        }
    }

    private boolean contains(CharSequence value, String expected) {
        return value != null && value.toString().contains(expected);
    }

    private AlertAircraft aircraft(String callsign, String registration, double altitudeFeet, boolean extreme) {
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
                extreme
        );
    }
}
