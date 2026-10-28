package com.example.sos;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class AnomalyDetector {

    private final List<Long> screenToggles = new ArrayList<>();
    private final List<Long> appSwitches = new ArrayList<>();

    // Power-button panic proxy: screen ON/OFF events within the panic window.
    private static final int SCREEN_WINDOW_MS = 15000;

    // Rule 2: App Switching Frequency Anomaly
    private static final int APP_WINDOW_MS = 15000;
    private static final int APP_THRESHOLD = 7;

    // Rule 3: Rapid Power Button Panic (5 presses)
    private static final int PANIC_WINDOW_MS = 15000;
    private static final int PANIC_THRESHOLD = 5;

    /**
     * Records a screen toggle event (On or Off).
     * @return true if the anomaly threshold is breached or panic trigger is met.
     */
    public boolean recordScreenToggle() {
        long now = System.currentTimeMillis();
        synchronized (screenToggles) {
            screenToggles.add(now);
            prune(screenToggles, now, SCREEN_WINDOW_MS);
            
            int count = screenToggles.size();
            
            // Trigger only on 5 or more screen-toggle events within 15 seconds.
            if (count >= PANIC_THRESHOLD) {
                long timeStart = screenToggles.get(count - PANIC_THRESHOLD);
                if (now - timeStart <= PANIC_WINDOW_MS) {
                    return true;
                }
            }
            
            return false;
        }
    }

    /**
     * Records an app switch event.
     * @return true if the anomaly threshold is breached.
     */
    public boolean recordAppSwitch() {
        long now = System.currentTimeMillis();
        synchronized (appSwitches) {
            appSwitches.add(now);
            prune(appSwitches, now, APP_WINDOW_MS);

            int count = appSwitches.size();
            // Trigger on the seventh distinct app switch within 15 seconds.
            return count >= APP_THRESHOLD;
        }
    }

    // Rule 3: Prolonged Inactivity with Screen On
    private long screenOnTime = -1;
    // 10 minutes (absolute threshold as per Rule 3)
    private static final long INACTIVITY_THRESHOLD_MS = 10 * 60 * 1000;
    
    /**
     * Call this when screen turns ON.
     */
    public void onScreenOn() {
        screenOnTime = System.currentTimeMillis();
    }

    /**
     * Call this when screen turns OFF.
     */
    public void onScreenOff() {
        screenOnTime = -1;
    }

    /**
     * Checks if the screen has been on for too long without interaction.
     * Should be called periodically.
     * @return true if inactivity threshold encountered.
     */
    public boolean checkProlongedScreenInactivity() {
        if (screenOnTime == -1) return false;
        long duration = System.currentTimeMillis() - screenOnTime;
        return duration > INACTIVITY_THRESHOLD_MS;
    }

    private void prune(List<Long> timestamps, long now, int window) {
        Iterator<Long> it = timestamps.iterator();
        while (it.hasNext()) {
            if (now - it.next() > window) {
                it.remove();
            } else {
                break; // List is ordered by time
            }
        }
    }
}
