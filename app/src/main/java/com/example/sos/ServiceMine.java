package com.example.sos;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.net.Uri;
import android.media.MediaPlayer;
import android.media.AudioManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationToken;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.OnTokenCanceledListener;

import android.os.Handler;
import android.os.Looper;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import java.util.ArrayList;
import android.os.Bundle;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.app.AppOpsManager;
import android.provider.Settings;

public class ServiceMine extends Service implements SensorEventListener {

    boolean isRunning = false;
    private Vibrator vibrator;
    DatabaseHelper db;
    FusedLocationProviderClient fusedLocationClient;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private long lastShakeTime;
    private long lastMovementTime;
    private static final int SHAKE_THRESHOLD = 90;
    private static final double RUNNING_MOTION_THRESHOLD = 18.0;
    private static final int RUNNING_PEAK_COUNT = 5;
    private static final long RUNNING_MIN_DURATION_MS = 6000;
    private static final long RUNNING_WINDOW_MS = 7000;
    private final ArrayList<Long> runningMotionPeaks = new ArrayList<>();
    private long runningMotionStartTime;
    private long lastRunningMotionTime;
    private static final int MOVEMENT_THRESHOLD_MIN = 8;
    private static final int MOVEMENT_THRESHOLD_MAX = 12;
    private static final long INACTIVITY_LIMIT = 1 * 60 * 1000; // 1 Minute for testing

    private LocationCallback locationCallback;
    private Handler inactivityHandler = new Handler(Looper.getMainLooper());
    private Runnable inactivityRunnable;
    private final AnomalyDetector anomalyDetector = new AnomalyDetector();
    private final Handler anomalyHandler = new Handler(Looper.getMainLooper());
    private Runnable appSwitchRunnable;
    private String lastForegroundPackage;
    private ScreenStateReceiver screenStateReceiver;
    private boolean anomalyMonitoringRegistered;
    private final Handler escalationHandler = new Handler(Looper.getMainLooper());
    private ArrayList<ContactModel> escalationContacts = new ArrayList<>();
    private int escalationIndex = -1;
    private Runnable escalationRunnable;
    private SmsAcknowledgementReceiver smsAcknowledgementReceiver;
    private boolean smsReceiverRegistered;
    private static final long ESCALATION_DELAY_MS = 60 * 1000;
    private static final long FALL_RESPONSE_WINDOW_MS = 15 * 1000;
    private static final double FALL_IMPACT_THRESHOLD = 25.0;
    private static final long FALL_COOLDOWN_MS = 10 * 1000;
    private final Handler fallDetectionHandler = new Handler(Looper.getMainLooper());
    private Runnable fallTimeoutRunnable;
    private boolean fallConfirmationPending;
    private long lastFallDetectionTime;
    SmsManager manager = SmsManager.getDefault();
    String myLocation;

    @Override
    public void onCreate() {
        super.onCreate();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        lastMovementTime = System.currentTimeMillis();

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                super.onLocationResult(locationResult);
                for (Location location : locationResult.getLocations()) {
                    if (location != null) {
                        myLocation = "https://maps.google.com/maps?q=" + location.getLatitude() + ","
                                + location.getLongitude();
                    }
                }
            }
        };
        startLocationUpdates();
        startAnomalyMonitoring();
        startInactivityCheck();

        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            // ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            // public void onRequestPermissionsResult(int requestCode, String[] permissions,
            // int[] grantResults)
            // to handle the case where the user grants the permission. See the
            // documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }

        // Get the current location of user
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, new CancellationToken() {
            @Override
            public boolean isCancellationRequested() {
                return false;
            }

            @NonNull
            @Override
            public CancellationToken onCanceledRequested(@NonNull OnTokenCanceledListener onTokenCanceledListener) {
                return null;
            }
        }).addOnSuccessListener(new OnSuccessListener<Location>() {
            @Override
            public void onSuccess(Location location) {
                if (location != null) {
                    myLocation = "https://maps.google.com/maps?q=" + location.getLatitude() + ","
                            + location.getLongitude();
                } else {
                    myLocation = "Unable to Find Location :(";
                }
            }
        });

        initializeVoiceListening();
        listenForNearbyAlerts();
        registerSmsAcknowledgementReceiver();
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
                .setWaitForAccurateLocation(false)
                .setMinUpdateIntervalMillis(5000)
                .setMaxUpdateDelayMillis(15000)
                .build();

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
    }

    private void startInactivityCheck() {
        inactivityRunnable = new Runnable() {
            @Override
            public void run() {
                long currentTime = System.currentTimeMillis();
                if ((currentTime - lastMovementTime) > INACTIVITY_LIMIT) {
                    triggerInactivityAlert();
                }
                if (anomalyDetector.checkProlongedScreenInactivity()) {
                    triggerSOS("Prolonged screen inactivity");
                    anomalyDetector.onScreenOn();
                }
                inactivityHandler.postDelayed(this, 10000); // Check frequently for demo
            }

        };
        inactivityHandler.post(inactivityRunnable);
    }

    private void startAnomalyMonitoring() {
        anomalyDetector.onScreenOn();
        screenStateReceiver = new ScreenStateReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenStateReceiver, filter);

        appSwitchRunnable = new Runnable() {
            @Override
            public void run() {
                checkForegroundAppSwitch();
                anomalyHandler.postDelayed(this, 2000);
            }
        };
        anomalyHandler.post(appSwitchRunnable);
        anomalyMonitoringRegistered = true;
    }

    private void checkForegroundAppSwitch() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP
                || !hasUsageStatsAccess()) {
            return;
        }

        UsageStatsManager usageStatsManager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        if (usageStatsManager == null) {
            return;
        }
        long now = System.currentTimeMillis();
        UsageEvents events = usageStatsManager.queryEvents(now - 3000, now);
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND
                    && !event.getPackageName().equals(getPackageName())) {
                if (!event.getPackageName().equals(lastForegroundPackage)
                        && anomalyDetector.recordAppSwitch()) {
                    triggerSOS("App switching anomaly");
                }
                lastForegroundPackage = event.getPackageName();
            }
        }
    }

    private boolean hasUsageStatsAccess() {
        AppOpsManager appOpsManager = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        if (appOpsManager == null) {
            return false;
        }
        int mode = appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private final class ScreenStateReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                anomalyDetector.onScreenOn();
            } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                anomalyDetector.onScreenOff();
            }
            if (anomalyDetector.recordScreenToggle()) {
                triggerSOS("Rapid screen toggles / power-button panic");
            }
        }
    }

    private void triggerInactivityAlert() {
        if (vibrator != null) {
            vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE));
        }
        lastMovementTime = System.currentTimeMillis();
    }

    MediaPlayer mediaPlayer;
    private AudioManager audioManager;
    private int originalVolume;
    private Handler volumeHandler = new Handler(Looper.getMainLooper());
    private Runnable volumeRunnable;

    // Safe Route Fields
    boolean isRouteMode = false;
    java.util.ArrayList<SimpleLocation> routePath; // Store full path using generic model
    private final Handler liveLocationHandler = new Handler(Looper.getMainLooper());
    private Runnable liveLocationRunnable;
    private static final long LIVE_LOCATION_INTERVAL = 30 * 1000; // Check every 30 seconds
    private static final double MAX_DEVIATION_METERS = 200.0;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }

        String action = (intent != null) ? intent.getAction() : null;

        if (action != null && action.equalsIgnoreCase("STOP")) {
            stopServiceCommon();
        } else if (action != null && action.equalsIgnoreCase("CANCEL_SOS")) {
            cancelActiveSos();
        } else if (action != null && action.equalsIgnoreCase("FALL_SAFE")) {
            confirmFallSafety();
        } else if (action != null && action.equalsIgnoreCase("FALL_NOT_SAFE")) {
            triggerFallSos();
        } else if (action != null && action.equalsIgnoreCase("FALL_TIMEOUT")) {
            triggerFallSos();
        } else if (action != null && action.equalsIgnoreCase("START_ROUTE")) {
            // Receive the full list of points
            routePath = intent.getParcelableArrayListExtra("route_points");

            if (routePath != null && !routePath.isEmpty()) {
                isRouteMode = true;
                startLiveLocationUpdates();
                startForegroundNotification("Safe Route Active", "Monitoring your path...");
                isRunning = true;
            } else {
                startForegroundNotification("Safe Route Error", "No route data received.");
            }
            return START_NOT_STICKY;
        } else if (action != null && action.equalsIgnoreCase("NEARBY_HELP")) {
            triggerNearbyAlert("COMMUNITY_HELP");
            return START_STICKY;
        } else {
            startForegroundNotification("Protected", "Shake to SOS Active");
            isRunning = true;
            startListening();
            return START_STICKY;
        }
        return START_STICKY;
    }

    private void startLiveLocationUpdates() {
        liveLocationRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRouteMode && myLocation != null) {
                    // Only check for deviation, don't spam "I am Safe" messages
                    // sendLiveLocationToContacts();
                    checkRouteDeviation();
                }
                liveLocationHandler.postDelayed(this, LIVE_LOCATION_INTERVAL);
            }
        };
        liveLocationHandler.post(liveLocationRunnable);
    }

    private void checkRouteDeviation() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return;

        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null && routePath != null) {
                if (!isLocationOnPath(location, routePath)) {
                    // DEVIATION DETECTED!
                    triggerSOS("Safe-route deviation");
                    // Notify specifically about deviation
                    if (!db.fetchData().isEmpty()) {
                        manager.sendTextMessage(db.fetchData().get(0).getNumber(), null,
                                "ALERT: Route Deviation Detected! I am off track.", null, null);
                    }
                }
            }
        });
    }

    // Check if user is within tolerance of ANY point on the path
    private boolean isLocationOnPath(Location location, java.util.ArrayList<SimpleLocation> polyline) {
        for (SimpleLocation point : polyline) {
            float[] results = new float[1];
            Location.distanceBetween(location.getLatitude(), location.getLongitude(), point.latitude, point.longitude,
                    results);
            if (results[0] < MAX_DEVIATION_METERS) {
                return true; // We are close enough to at least one point
            }
        }
        return false;
    }

    private void sendLiveLocationToContacts() {
        db = new DatabaseHelper(ServiceMine.this);
        ArrayList<ContactModel> list = db.fetchData();
        for (ContactModel c : list) {
            manager.sendTextMessage(c.getNumber(), null,
                    "Safe Route Update: I am safe. My current location: " + myLocation,
                    null, null);
        }
    }

    private void startForegroundNotification(String title, String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        NotificationChannel channel = new NotificationChannel("MYID", "CHANNELFOREGROUND",
                NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager m = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        m.createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, "MYID")
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.siren)
                .setContentIntent(pendingIntent)
                .build();
        this.startForeground(115, notification);
    }

    private void showFallConfirmationNotification() {
        Intent safeIntent = new Intent(this, ServiceMine.class);
        safeIntent.setAction("FALL_SAFE");
        PendingIntent safePendingIntent = PendingIntent.getService(
                this, 119, safeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent notSafeIntent = new Intent(this, ServiceMine.class);
        notSafeIntent.setAction("FALL_NOT_SAFE");
        PendingIntent notSafePendingIntent = PendingIntent.getService(
                this, 120, notSafeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationChannel channel = new NotificationChannel(
                "FALL_CONFIRMATION", "Fall Detection", NotificationManager.IMPORTANCE_HIGH);
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
            Notification notification = new Notification.Builder(this, "FALL_CONFIRMATION")
                    .setContentTitle("Possible fall detected")
                    .setContentText("Are you safe? You have 15 seconds to respond.")
                    .setSmallIcon(R.drawable.siren)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .addAction(android.R.drawable.ic_menu_view, "I'M SAFE", safePendingIntent)
                    .addAction(android.R.drawable.ic_dialog_alert, "I'M NOT SAFE", notSafePendingIntent)
                    .build();
            notificationManager.notify(117, notification);
        }
    }

    private void confirmFallSafety() {
        if (!fallConfirmationPending) {
            return;
        }
        fallConfirmationPending = false;
        if (fallTimeoutRunnable != null) {
            fallDetectionHandler.removeCallbacks(fallTimeoutRunnable);
            fallTimeoutRunnable = null;
        }
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.cancel(117);
        }
    }

    private void triggerFallSos() {
        if (!fallConfirmationPending) {
            return;
        }
        fallConfirmationPending = false;
        if (fallTimeoutRunnable != null) {
            fallDetectionHandler.removeCallbacks(fallTimeoutRunnable);
            fallTimeoutRunnable = null;
        }
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.cancel(117);
        }
        triggerSOS("Fall detected");
    }

    private void detectPossibleFall(long currentTime, double acceleration) {
        if (isSOSActive || fallConfirmationPending
                || currentTime - lastFallDetectionTime < FALL_COOLDOWN_MS
                || acceleration < FALL_IMPACT_THRESHOLD
                || acceleration >= SHAKE_THRESHOLD) {
            return;
        }
        lastFallDetectionTime = currentTime;
        fallConfirmationPending = true;
        showFallConfirmationNotification();
        fallTimeoutRunnable = this::triggerFallSos;
        fallDetectionHandler.postDelayed(fallTimeoutRunnable, FALL_RESPONSE_WINDOW_MS);
    }

    private boolean detectRunningPattern(long currentTime, double acceleration) {
        if (acceleration < RUNNING_MOTION_THRESHOLD || acceleration >= SHAKE_THRESHOLD) {
            if (runningMotionStartTime > 0
                    && currentTime - lastRunningMotionTime > 1000) {
                runningMotionStartTime = 0;
                runningMotionPeaks.clear();
            }
            return false;
        }
        if (runningMotionStartTime == 0
                || currentTime - lastRunningMotionTime > 1000) {
            runningMotionStartTime = currentTime;
            runningMotionPeaks.clear();
        }
        lastRunningMotionTime = currentTime;
        runningMotionPeaks.add(currentTime);
        while (!runningMotionPeaks.isEmpty()
                && currentTime - runningMotionPeaks.get(0) > RUNNING_WINDOW_MS) {
            runningMotionPeaks.remove(0);
        }
        if (currentTime - runningMotionStartTime < RUNNING_MIN_DURATION_MS
                || runningMotionPeaks.size() < RUNNING_PEAK_COUNT) {
            return false;
        }
        runningMotionStartTime = 0;
        runningMotionPeaks.clear();
        return true;
    }

    private boolean beginSosIncident(String trigger) {
        long now = System.currentTimeMillis();
        if (isSOSActive || now - lastIncidentTime < SOS_COOLDOWN_MS) {
            return false;
        }
        isSOSActive = true;
        lastIncidentTime = now;
        stopVoiceDetection();
        recordIncident(trigger);
        showSosActiveNotification(trigger);
        return true;
    }

    private void showSosActiveNotification(String trigger) {
        Intent cancelIntent = new Intent(this, ServiceMine.class);
        cancelIntent.setAction("CANCEL_SOS");
        PendingIntent cancelPendingIntent = PendingIntent.getService(
                this,
                118,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationChannel channel = new NotificationChannel(
                "SOS_ACTIVE",
                "Active SOS",
                NotificationManager.IMPORTANCE_HIGH);
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
            Notification notification = new Notification.Builder(this, "SOS_ACTIVE")
                    .setContentTitle("SOS is active")
                    .setContentText("Triggered by: " + trigger)
                    .setSmallIcon(R.drawable.siren)
                    .setOngoing(true)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel SOS", cancelPendingIntent)
                    .build();
            notificationManager.notify(116, notification);
        }
    }

    private void cancelActiveSos() {
        if (!isSOSActive) {
            return;
        }
        isSOSActive = false;
        isVoiceDetectionOn = true;
        stopSiren();
        startListening();
        if (liveLocationHandler != null && liveLocationRunnable != null) {
            liveLocationHandler.removeCallbacks(liveLocationRunnable);
        }
        confirmFallSafety();
        if (escalationRunnable != null) {
            escalationHandler.removeCallbacks(escalationRunnable);
            escalationRunnable = null;
        }
        isRouteMode = false;
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.cancel(116);
        }
        startForegroundNotification("Protected", "Monitoring active");
    }

    // Voice Detection Fields
    private android.speech.SpeechRecognizer speechRecognizer;
    private Intent speechRecognizerIntent;
    private boolean isVoiceDetectionOn = true; // Enabled by default for SHAKTI

    private void initializeVoiceListening() {
        if (android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizerIntent = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speechRecognizerIntent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            speechRecognizerIntent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "en-US");
            speechRecognizerIntent.putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE,
                    this.getPackageName());
            speechRecognizerIntent.putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            speechRecognizerIntent.putExtra(
                    android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    60000L);
            speechRecognizerIntent.putExtra(
                    android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    60000L);

            speechRecognizer.setRecognitionListener(new android.speech.RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                }

                @Override
                public void onBeginningOfSpeech() {
                }

                @Override
                public void onRmsChanged(float rmsdB) {
                }

                @Override
                public void onBufferReceived(byte[] buffer) {
                }

                @Override
                public void onEndOfSpeech() {
                }

                @Override
                public void onError(int error) {
                }

                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> matches = results
                            .getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null) {
                        for (String result : matches) {
                            String cmd = result.toLowerCase(java.util.Locale.ROOT);
                            boolean keywordMatched = false;

                            // 1. Check Personal Contact Keywords
                            db = new DatabaseHelper(ServiceMine.this);
                            ArrayList<ContactModel> list = db.fetchData();
                            for (ContactModel c : list) {
                                if (c.getKeyword() != null && !c.getKeyword().isEmpty()
                                        && cmd.contains(c.getKeyword().toLowerCase())) {
                                    triggerSpecificContactSOS(c);
                                    keywordMatched = true;
                                }
                            }

                            if (keywordMatched)
                                continue;

                            // 2. Check Helpline Keywords
                            ArrayList<HelplineModel> helplines = db.fetchHelplineData();
                            for (HelplineModel h : helplines) {
                                if (h.getKeyword() != null && !h.getKeyword().isEmpty()
                                        && cmd.contains(h.getKeyword().toLowerCase())) {
                                    triggerHelplineSOS(h);
                                    keywordMatched = true;
                                }
                            }

                            if (keywordMatched)
                                continue;

                            // 3. Authority-Specific Triggers (Legacy/Fallback)
                            if (cmd.contains("help") || cmd.contains("bachao") || cmd.contains("nahi")
                                    || cmd.contains("mummy") || cmd.contains("save me") || cmd.contains("emergency")) {
                                triggerSOS(); // General SOS
                            }
                        }
                    }
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                }

                @Override
                public void onEvent(int eventType, Bundle params) {
                }
            });
        }
    }

    private void startListening() {
        if (speechRecognizer != null && isVoiceDetectionOn && isRunning && !isSOSActive) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        speechRecognizer.startListening(speechRecognizerIntent);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    private void stopVoiceDetection() {
        isVoiceDetectionOn = false;
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
            speechRecognizer.cancel();
        }
    }

    private boolean isSOSActive = false; // Prevent multiple triggers
    private long lastIncidentTime = 0L;
    private static final long SOS_COOLDOWN_MS = 60 * 1000;

    private void triggerSpecificContactSOS(ContactModel contact) {
        if (!beginSosIncident("Personal contact keyword: " + contact.getKeyword()))
            return;

        startSiren();
        if (vibrator != null) {
            vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE));
        }

        // 1. Launch Camera to take photos automatically
        Intent cameraIntent = new Intent(this, CameraActivity.class);
        cameraIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        cameraIntent.putExtra("AUTO_START", true);
        cameraIntent.putExtra("SOS_TRIGGER", "Personal contact keyword " + contact.getKeyword());
        startActivity(cameraIntent);

        // 2. Send SMS ONLY to the specific contact
        myLocation = (myLocation == null) ? "Checking Location..." : myLocation;
        manager.sendTextMessage(contact.getNumber(), null,
                "PERSONAL SOS: I called out '" + contact.getKeyword() + "'! I need help! My location: " + myLocation,
                null, null);

        // 3a. Trigger Nearby Help Alert
        triggerNearbyAlert("SOS_SPECIFIC");

        // 4. Start Live Tracking
        isRouteMode = true;
        if (liveLocationHandler != null && liveLocationRunnable != null) {
            liveLocationHandler.removeCallbacks(liveLocationRunnable);
        }
        startLiveLocationUpdates();
    }

    private void triggerHelplineSOS(HelplineModel helpline) {
        if (!beginSosIncident("Helpline keyword: " + helpline.getKeyword()))
            return;

        startSiren();
        if (vibrator != null) {
            vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE));
        }

        // 1. Launch Camera to take photos automatically
        Intent cameraIntent = new Intent(this, CameraActivity.class);
        cameraIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        cameraIntent.putExtra("AUTO_START", true);
        cameraIntent.putExtra("SOS_TRIGGER", "Helpline keyword " + helpline.getKeyword());
        startActivity(cameraIntent);

        // 2. Send SMS to the specific helpline number with Custom Message
        myLocation = (myLocation == null) ? "Checking Location..." : myLocation;
        String messageToSend = helpline.getCustomMessage();
        if (messageToSend == null || messageToSend.trim().isEmpty()) {
            messageToSend = "EMERGENCY: I need help! My location: " + myLocation;
        } else {
            messageToSend += "\n\nLocation: " + myLocation;
        }

        try {
            manager.sendTextMessage(helpline.getNumber(), null, messageToSend, null, null);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 3a. Trigger Nearby Help Alert
        triggerNearbyAlert("HELPLINE_SOS");

        // 4. Start Live Tracking
        isRouteMode = true;
        if (liveLocationHandler != null && liveLocationRunnable != null) {
            liveLocationHandler.removeCallbacks(liveLocationRunnable);
        }
        startLiveLocationUpdates();

        // Also call them
        Intent intent = new Intent(Intent.ACTION_CALL);
        intent.setData(Uri.parse("tel:" + helpline.getNumber()));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (ActivityCompat.checkSelfPermission(ServiceMine.this,
                Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            // Keep the SOS active, but silence the siren while speaking with the helpline.
            stopSiren();
            startActivity(intent);
        }
    }

    private void triggerSOS() {
        triggerSOS("Voice SOS keyword");
    }

    private void triggerSOS(String trigger) {
        if (!beginSosIncident(trigger))
            return;

        startSiren();
        if (vibrator != null) {
            vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE));
        }

        // 1. Launch Camera to take photos automatically
        Intent cameraIntent = new Intent(this, CameraActivity.class);
        cameraIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        cameraIntent.putExtra("AUTO_START", true);
        cameraIntent.putExtra("SOS_TRIGGER", "Voice SOS keyword");
        startActivity(cameraIntent);

        // 2. Notify contacts in priority order with acknowledgement-based escalation
        myLocation = (myLocation == null) ? "Checking Location..." : myLocation;
        startContactEscalation("VOICE ALERT: I need help. My location: " + myLocation
                + "\nReply SAFE, HELPING, or CALLING when you receive this.");

        // 3a. Trigger Nearby Help Alert
        triggerNearbyAlert("SOS");

        // 3. Start Continuous Live Tracking
        isRouteMode = true;
        if (liveLocationHandler != null && liveLocationRunnable != null) {
            liveLocationHandler.removeCallbacks(liveLocationRunnable);
        }
        startLiveLocationUpdates();
    }

    private void triggerAuthorityEmergency(String emergencyType) {
        if (!beginSosIncident("Authority emergency: " + emergencyType))
            return;

        startSiren();
        if (vibrator != null) {
            vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE));
        }

        // Launch Camera for evidence
        Intent cameraIntent = new Intent(this, CameraActivity.class);
        cameraIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        cameraIntent.putExtra("AUTO_START", true);
        cameraIntent.putExtra("SOS_TRIGGER", "Authority emergency " + emergencyType);
        startActivity(cameraIntent);

        // Fetch user profile from Firebase
        com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance()
                .getCurrentUser();
        if (user != null) {
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(user.getUid())
                    .get()
                    .addOnSuccessListener(document -> {
                        EmergencyConfig.UserProfile profile = new EmergencyConfig.UserProfile();
                        if (document.exists()) {
                            profile.name = document.getString("name");
                            profile.age = document.getString("age");
                            profile.gender = document.getString("gender");
                            profile.mobile = document.getString("mobile");
                            profile.emergencyContact = document.getString("emergencyContact");
                            profile.city = document.getString("city");
                            profile.bloodType = document.getString("bloodType");
                            profile.allergies = document.getString("allergies");
                            profile.medicalConditions = document.getString("medicalConditions");
                            profile.medicines = document.getString("medicines");
                        }

                        sendAuthorityAlert(emergencyType, profile);
                    })
                    .addOnFailureListener(e -> {
                        // Fallback with minimal info
                        EmergencyConfig.UserProfile profile = new EmergencyConfig.UserProfile();
                        profile.name = user.getDisplayName() != null ? user.getDisplayName() : "User";
                        sendAuthorityAlert(emergencyType, profile);
                    });
        }

        // Start live tracking
        isRouteMode = true;
        if (liveLocationHandler != null && liveLocationRunnable != null) {
            liveLocationHandler.removeCallbacks(liveLocationRunnable);
        }
        startLiveLocationUpdates();
    }

    private void sendAuthorityAlert(String emergencyType, EmergencyConfig.UserProfile profile) {
        String location = (myLocation != null) ? myLocation : "Getting Location...";
        String message = EmergencyConfig.formatEmergencyMessage(emergencyType, profile, location);
        String emergencyNumber = EmergencyConfig.getEmergencyNumber(emergencyType);

        // Send SMS to emergency contacts
        db = new DatabaseHelper(ServiceMine.this);
        ArrayList<ContactModel> list = db.fetchData();
        for (ContactModel c : list) {
            manager.sendTextMessage(c.getNumber(), null, message, null, null);
        }

        // For certain emergencies, also call the authority
        if (emergencyType.equals(EmergencyConfig.TYPE_AMBULANCE) ||
                emergencyType.equals(EmergencyConfig.TYPE_ACCIDENT) ||
                emergencyType.equals(EmergencyConfig.TYPE_FIRE)) {

            // Auto-call emergency number
            Intent callIntent = new Intent(Intent.ACTION_CALL);
            callIntent.setData(android.net.Uri.parse("tel:" + emergencyNumber));
            callIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                startActivity(callIntent);
            }
        }

        // Trigger Nearby Help Alert
        triggerNearbyAlert(emergencyType);
    }

    private void triggerNearbyAlert(String type) {
        Location location = null;
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getLastLocation().addOnSuccessListener(loc -> {
                if (loc != null) {
                    uploadAlertToFirestore(loc, type);
                }
            });
        }
    }

    private void uploadAlertToFirestore(Location location, String type) {
        com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance()
                .getCurrentUser();
        if (user == null)
            return;

        java.util.Map<String, Object> alert = new java.util.HashMap<>();
        alert.put("userId", user.getUid());
        alert.put("type", type);
        alert.put("latitude", location.getLatitude());
        alert.put("longitude", location.getLongitude());
        alert.put("timestamp", System.currentTimeMillis());
        alert.put("status", "ACTIVE");

        com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("active_alerts")
                .document(user.getUid())
                .set(alert);
    }

    private void listenForNearbyAlerts() {
        com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance()
                .getCurrentUser();
        if (user == null)
            return;

        com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("active_alerts")
                .whereEqualTo("status", "ACTIVE")
                .whereGreaterThan("timestamp", System.currentTimeMillis() - 3600000) // Last 1 hour
                .addSnapshotListener((value, error) -> {
                    if (error != null || value == null)
                        return;

                    for (com.google.firebase.firestore.DocumentChange dc : value.getDocumentChanges()) {
                        if (dc.getType() == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                            String userId = dc.getDocument().getString("userId");
                            if (userId != null && !userId.equals(user.getUid())) {
                                double lat = dc.getDocument().getDouble("latitude");
                                double lng = dc.getDocument().getDouble("longitude");
                                String type = dc.getDocument().getString("type");

                                checkDistanceAndAlert(lat, lng, type);
                            }
                        }
                    }
                });
    }

    private void checkDistanceAndAlert(double lat, double lng, String type) {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                float[] results = new float[1];
                Location.distanceBetween(location.getLatitude(), location.getLongitude(), lat, lng, results);
                float distanceInMeters = results[0];

                if (distanceInMeters <= 1000) { // 1km radius
                    showNearbyAlertNotification(distanceInMeters, type, lat, lng);
                }
            }
        });
    }

    private void showNearbyAlertNotification(float distance, String type, double lat, double lng) {
        Intent intent = new Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("http://maps.google.com/maps?daddr=" + lat + "," + lng));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(this, (int) System.currentTimeMillis(), intent,
                PendingIntent.FLAG_IMMUTABLE);

        NotificationChannel channel = new NotificationChannel("NEARBY_ALERT", "Nearby Alerts",
                NotificationManager.IMPORTANCE_HIGH);
        NotificationManager m = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        m.createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, "NEARBY_ALERT")
                .setContentTitle("🚨 SOMEONE NEARBY NEEDS HELP!")
                .setContentText("Distance: " + (int) distance + "m. Emergency: " + type)
                .setSmallIcon(R.drawable.siren)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();

        m.notify((int) System.currentTimeMillis(), notification);

        // Vibrate to alert user
        if (vibrator != null) {
            vibrator.vibrate(VibrationEffect.createOneShot(2000, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    private void stopServiceCommon() {
        if (isRunning) {
            // Stop Location Updates
            if (locationCallback != null)
                fusedLocationClient.removeLocationUpdates(locationCallback);

            // Stop Sensor Listener (Shake Detection)
            if (sensorManager != null) {
                sensorManager.unregisterListener(this);
            }

            // Stop Inactivity Check
            if (inactivityHandler != null && inactivityRunnable != null) {
                inactivityHandler.removeCallbacks(inactivityRunnable);
            }
            if (escalationRunnable != null) {
                escalationHandler.removeCallbacks(escalationRunnable);
                escalationRunnable = null;
            }
            if (smsReceiverRegistered && smsAcknowledgementReceiver != null) {
                unregisterReceiver(smsAcknowledgementReceiver);
                smsReceiverRegistered = false;
            }
            if (anomalyMonitoringRegistered) {
                if (anomalyHandler != null && appSwitchRunnable != null) {
                    anomalyHandler.removeCallbacks(appSwitchRunnable);
                }
                if (screenStateReceiver != null) {
                    unregisterReceiver(screenStateReceiver);
                }
                anomalyMonitoringRegistered = false;
            }

            // Stop Live Location Tracking
            if (liveLocationHandler != null && liveLocationRunnable != null) {
                liveLocationHandler.removeCallbacks(liveLocationRunnable);
            }

            // Stop Voice Recognition
            if (speechRecognizer != null) {
                speechRecognizer.stopListening();
                speechRecognizer.destroy();
                speechRecognizer = null;
            }

            // Stop Siren & Volume Lock
            stopSiren();

            // Stop Foreground Notification
            stopForeground(true);
            NotificationManager notificationManager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.cancel(116);
            }
            stopSelf();

            // Reset Flags
            isRunning = false;
            isSOSActive = false;
            isRouteMode = false;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            long currentTime = System.currentTimeMillis();

            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            // Gravity is ~9.8. If accel is significantly different or changing, it's
            // moving.
            double acceleration = Math.sqrt(x * x + y * y + z * z);

            if (acceleration > MOVEMENT_THRESHOLD_MAX || acceleration < MOVEMENT_THRESHOLD_MIN) {
                lastMovementTime = currentTime; // Reset inactivity timer
            }

            detectPossibleFall(currentTime, acceleration);

            if ((currentTime - lastShakeTime) > 5000) { // Add a time gap between two shakes
                if (acceleration > SHAKE_THRESHOLD) {

                    lastShakeTime = currentTime;
                    lastMovementTime = currentTime; // Mobile is definitely moving
                    if (!beginSosIncident("Shake detection")) {
                        return;
                    }

                    vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));

                    // Activate Siren
                    startSiren();

                    String locationToSend = (myLocation != null) ? myLocation : "Getting Location...";

                    SharedPreferences sp = getSharedPreferences("message", MODE_PRIVATE);
                    String msg = sp.getString("msg", null);
                    String message;
                    if (msg != null) {
                        message = "EMERGENCY: " + msg + "\n\nHere are my coordinates:\n"
                                + locationToSend
                                + "\nReply SAFE, HELPING, or CALLING when you receive this.";
                    } else {
                        message = "I am in DANGER and need help. Please urgently reach me. "
                                + "\n\nHere are my coordinates:\n" + locationToSend
                                + "\nReply SAFE, HELPING, or CALLING when you receive this.";
                    }
                    startContactEscalation(message);

                    lastShakeTime = currentTime;
                }
            }
            if (detectRunningPattern(currentTime, acceleration) && !isSOSActive) {
                triggerSOS("Running motion detected");
            }
        }
    }

    private void recordIncident(String trigger) {
        new DatabaseHelper(this).insertIncident(trigger,
                myLocation == null ? "Location unavailable" : myLocation);
    }

    private void registerSmsAcknowledgementReceiver() {
        smsAcknowledgementReceiver = new SmsAcknowledgementReceiver();
        IntentFilter filter = new IntentFilter("android.provider.Telephony.SMS_RECEIVED");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsAcknowledgementReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(smsAcknowledgementReceiver, filter);
        }
        smsReceiverRegistered = true;
    }

    private void startContactEscalation(String message) {
        db = new DatabaseHelper(ServiceMine.this);
        escalationContacts = db.fetchData();
        escalationIndex = -1;
        if (escalationContacts.isEmpty()) {
            return;
        }
        notifyNextContact(message);
    }

    private void notifyNextContact(String message) {
        escalationIndex++;
        if (escalationIndex >= escalationContacts.size() || !isSOSActive) {
            escalationRunnable = null;
            return;
        }

        ContactModel contact = escalationContacts.get(escalationIndex);
        manager.sendTextMessage(contact.getNumber(), null, message, null, null);
        escalationRunnable = () -> notifyNextContact(message);
        escalationHandler.postDelayed(escalationRunnable, ESCALATION_DELAY_MS);
    }

    private void acknowledgeContact(String sender, String acknowledgement) {
        if (!isSOSActive || escalationIndex < 0 || escalationIndex >= escalationContacts.size()) {
            return;
        }
        ContactModel currentContact = escalationContacts.get(escalationIndex);
        if (!samePhoneNumber(sender, currentContact.getNumber())) {
            return;
        }
        if (escalationRunnable != null) {
            escalationHandler.removeCallbacks(escalationRunnable);
            escalationRunnable = null;
        }
        manager.sendTextMessage(currentContact.getNumber(), null,
                "Acknowledgement received: " + acknowledgement + ". SOS remains active.", null, null);
    }

    private boolean samePhoneNumber(String first, String second) {
        String normalizedFirst = first.replaceAll("[^0-9]", "");
        String normalizedSecond = second.replaceAll("[^0-9]", "");
        if (normalizedFirst.length() > 10) {
            normalizedFirst = normalizedFirst.substring(normalizedFirst.length() - 10);
        }
        if (normalizedSecond.length() > 10) {
            normalizedSecond = normalizedSecond.substring(normalizedSecond.length() - 10);
        }
        return normalizedFirst.equals(normalizedSecond);
    }

    private final class SmsAcknowledgementReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) {
                return;
            }
            Object[] pdus = (Object[]) intent.getSerializableExtra("pdus");
            if (pdus == null) {
                return;
            }
            String format = intent.getStringExtra("format");
            for (Object pdu : pdus) {
                SmsMessage message = SmsMessage.createFromPdu((byte[]) pdu, format);
                String body = message.getMessageBody().trim().toUpperCase(java.util.Locale.ROOT);
                if (body.equals("SAFE") || body.equals("HELPING") || body.equals("CALLING")) {
                    acknowledgeContact(message.getOriginatingAddress(), body);
                }
            }
        }
    }

    private void startSiren() {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }

        if (audioManager != null) {
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

            // Continuous Volume Max Enforcement
            volumeRunnable = new Runnable() {
                @Override
                public void run() {
                    if (audioManager != null) {
                        // Force Music Stream to Max
                        int maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0);

                        // Force Alarm Stream to Max
                        int maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0);
                    }
                    volumeHandler.postDelayed(this, 100); // Check every 100ms
                }
            };
            volumeHandler.post(volumeRunnable);
        }

        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer.create(this, android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI);
            if (mediaPlayer == null) {
                mediaPlayer = MediaPlayer.create(this, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI);
            }
        }

        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            mediaPlayer.setLooping(true);
            mediaPlayer.start();
        }
    }

    private void stopSiren() {
        // Stop Volume Enforcement
        if (volumeHandler != null && volumeRunnable != null) {
            volumeHandler.removeCallbacks(volumeRunnable);
        }

        // Restore Original Volume
        if (audioManager != null) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0);
        }

        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (smsReceiverRegistered && smsAcknowledgementReceiver != null) {
            unregisterReceiver(smsAcknowledgementReceiver);
            smsReceiverRegistered = false;
        }
        // Unregister the sensor listener when the service is destroyed
        sensorManager.unregisterListener(this);
        stopSiren();
    }
}