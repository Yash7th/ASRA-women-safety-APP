package com.example.sos;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.location.Address;
import android.location.Geocoder;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.view.View;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.MapTileIndex;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RouteActivity extends AppCompatActivity {

    private MapView map;
    private FusedLocationProviderClient fusedLocationClient;
    private MaterialButton btnGetRoute, btnStartRoute, btnOpenMaps;
    private AutoCompleteTextView etSource, etDestination;
    private View routeInputCard;
    private android.widget.ProgressBar progressBar;
    private GeoPoint currentLatLong;
    private GeoPoint destinationLatLong;
    private ArrayList<SimpleLocation> routePoints; // For Service
    private Polyline roadOverlay;
    private Marker currentLocationMarker;
    private com.google.android.gms.location.LocationCallback locationCallback;
    private boolean locationUpdatesActive;
    private final ExecutorService geocoderExecutor = Executors.newSingleThreadExecutor();
    private final android.os.Handler suggestionHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable sourceSuggestionTask;
    private Runnable destinationSuggestionTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // OSM Configuration
        Configuration.getInstance().load(getApplicationContext(),
                PreferenceManager.getDefaultSharedPreferences(getApplicationContext()));

        setContentView(R.layout.activity_route);

        map = findViewById(R.id.map);
        Configuration.getInstance().setUserAgentValue(getPackageName());
        map.setTileSource(createMapTilerSource());
        map.setMultiTouchControls(true);
        map.getController().setZoom(15.0);

        etSource = findViewById(R.id.etSource);
        etDestination = findViewById(R.id.etDestination);
        btnGetRoute = findViewById(R.id.btnGetRoute);
        btnStartRoute = findViewById(R.id.btnStartRoute);
        btnOpenMaps = findViewById(R.id.btnOpenMaps);
        progressBar = findViewById(R.id.progressBar);
        routeInputCard = findViewById(R.id.routeInputCard);
        setupKeyboardPositioning();
        setupLocationSuggestions(etSource, true);
        setupLocationSuggestions(etDestination, false);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        getCurrentLocation();
        startFollowingUser();

        // Restore active route state if any
        restoreRouteState();

        // Handle Map Clicks for Destination
        MapEventsReceiver mReceive = new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                destinationLatLong = p;
                etDestination.setText(String.format("%.4f, %.4f", p.getLatitude(), p.getLongitude()));

                Marker endMarker = new Marker(map);
                endMarker.setPosition(p);
                endMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                endMarker.setTitle("Destination");
                map.getOverlays().add(endMarker);
                map.invalidate();
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                return false;
            }
        };
        map.getOverlays().add(new MapEventsOverlay(mReceive));

        btnGetRoute.setOnClickListener(v -> {
            resolveRouteLocations();
        });

        btnStartRoute.setOnClickListener(v -> startSafeRouteService());
    }

    private void setupKeyboardPositioning() {
        View.OnFocusChangeListener listener = (view, hasFocus) -> {
            if (hasFocus) {
                float upwardOffset = 140 * getResources().getDisplayMetrics().density;
                routeInputCard.animate()
                        .translationY(-upwardOffset)
                        .setDuration(180)
                        .start();
            } else if (!etSource.hasFocus() && !etDestination.hasFocus()) {
                routeInputCard.animate()
                        .translationY(0f)
                        .setDuration(180)
                        .start();
            }
        };
        etSource.setOnFocusChangeListener(listener);
        etDestination.setOnFocusChangeListener(listener);
    }

    private OnlineTileSourceBase createMapTilerSource() {
        final String apiKey = getString(R.string.maptiler_api_key);
        return new OnlineTileSourceBase(
                "MapTiler Streets",
                0,
                20,
                256,
                ".png",
                new String[] { "https://api.maptiler.com/maps/streets/" }) {
            @Override
            public String getTileURLString(long tileIndex) {
                return getBaseUrl()
                        + MapTileIndex.getZoom(tileIndex) + "/"
                        + MapTileIndex.getX(tileIndex) + "/"
                        + MapTileIndex.getY(tileIndex)
                        + ".png?key=" + apiKey;
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (map != null) {
            map.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (map != null) {
            map.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        geocoderExecutor.shutdownNow();
        stopFollowingUser();
        if (map != null) {
            map.onDetach();
        }
        super.onDestroy();
    }

    // ... (Keep existing methods getCurrentLocation, fetchRoute, parseOSRMRoute)
    // ...

    private void startSafeRouteService() {
        if (new DatabaseHelper(this).count() == 0) {
            Toast.makeText(this, "Please verify trusted contacts first!", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, RegisterNumberActivity.class));
            return;
        }

        if (routePoints == null || routePoints.isEmpty())
            return;
        Toast.makeText(this, "Starting Safe Patrol...", Toast.LENGTH_SHORT).show();

        Intent intent = new Intent(this, ServiceMine.class);
        intent.setAction("START_ROUTE");
        intent.putParcelableArrayListExtra("route_points", routePoints);
        startService(intent);

        saveRouteState(); // Persistent Save

        // Monitoring Mode
        btnStartRoute.setText("Stop Patrol");
        btnStartRoute.setOnClickListener(v -> stopSafeRouteService());

        btnGetRoute.setEnabled(false);
        etDestination.setEnabled(false);

        Toast.makeText(this, "Patrol Started. Stay on the blue line.", Toast.LENGTH_LONG).show();
        startFollowingUser();
    }

    private void stopSafeRouteService() {
        Intent intent = new Intent(this, ServiceMine.class);
        intent.setAction("STOP");
        startService(intent);

        clearRouteState(); // Persistent Clear

        btnStartRoute.setText("Start Patrol");
        btnStartRoute.setOnClickListener(v -> startSafeRouteService());

        btnGetRoute.setEnabled(true);
        etDestination.setEnabled(true);

        // Remove Route Line
        if (map.getOverlays().contains(roadOverlay)) {
            map.getOverlays().remove(roadOverlay);
            map.invalidate();
        }

        Toast.makeText(this, "Patrol Stopped.", Toast.LENGTH_SHORT).show();
    }

    private void saveRouteState() {
        if (routePoints == null)
            return;
        StringBuilder sb = new StringBuilder();
        for (SimpleLocation loc : routePoints) {
            sb.append(loc.latitude).append(",").append(loc.longitude).append(";");
        }

        android.content.SharedPreferences prefs = getSharedPreferences("SafeRoute", MODE_PRIVATE);
        prefs.edit().putString("active_route", sb.toString()).putBoolean("is_active", true).apply();
    }

    private void clearRouteState() {
        getSharedPreferences("SafeRoute", MODE_PRIVATE).edit().clear().apply();
    }

    private void restoreRouteState() {
        android.content.SharedPreferences prefs = getSharedPreferences("SafeRoute", MODE_PRIVATE);
        boolean isActive = prefs.getBoolean("is_active", false);
        if (isActive) {
            String routeStr = prefs.getString("active_route", "");
            if (!routeStr.isEmpty()) {
                routePoints = new ArrayList<>();
                ArrayList<GeoPoint> geoPoints = new ArrayList<>();
                String[] points = routeStr.split(";");
                for (String p : points) {
                    if (!p.isEmpty()) {
                        String[] latlon = p.split(",");
                        if (latlon.length == 2) {
                            try {
                                double lat = Double.parseDouble(latlon[0]);
                                double lon = Double.parseDouble(latlon[1]);
                                if (lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180) {
                                    routePoints.add(new SimpleLocation(lat, lon));
                                    geoPoints.add(new GeoPoint(lat, lon));
                                }
                            } catch (NumberFormatException ignored) {
                                // Ignore malformed points from an interrupted or old route save.
                            }
                        }
                    }
                }

                // Draw Route
                if (roadOverlay != null)
                    map.getOverlays().remove(roadOverlay);
                roadOverlay = new Polyline();
                roadOverlay.setPoints(geoPoints);
                roadOverlay.setColor(0xFF0000FF);
                roadOverlay.setWidth(12f);
                map.getOverlays().add(0, roadOverlay);
                map.invalidate();

                // Set UI to Monitoring Mode
                btnStartRoute.setText("Stop Patrol");
                btnStartRoute.setOnClickListener(v -> stopSafeRouteService());
                btnStartRoute.setEnabled(true);
                btnGetRoute.setEnabled(false);
                startFollowingUser();
            }
        }
    }

    private void getCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, 1);
            return;
        }

        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                currentLatLong = new GeoPoint(location.getLatitude(), location.getLongitude());
                updateCurrentLocationMarker(currentLatLong);
                map.getController().setCenter(currentLatLong);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            getCurrentLocation();
            startFollowingUser();
        }
    }

    private void setupLocationSuggestions(AutoCompleteTextView field, boolean source) {
        field.setThreshold(2);
        field.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                new ArrayList<>()));
        field.setOnItemClickListener((parent, view, position, id) -> {
            Object selectedItem = parent.getItemAtPosition(position);
            if (selectedItem instanceof String) {
                resolveLocation((String) selectedItem, point -> {
                    if (point == null) {
                        Toast.makeText(this, "That location could not be found.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (source) {
                        currentLatLong = point;
                    } else {
                        destinationLatLong = point;
                        addDestinationMarker(point);
                    }
                    field.dismissDropDown();
                });
            }
        });

        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (source) {
                    currentLatLong = null;
                    if (sourceSuggestionTask != null) {
                        suggestionHandler.removeCallbacks(sourceSuggestionTask);
                    }
                    sourceSuggestionTask = createSuggestionTask(field, s.toString());
                    suggestionHandler.postDelayed(sourceSuggestionTask, 400);
                } else {
                    destinationLatLong = null;
                    if (destinationSuggestionTask != null) {
                        suggestionHandler.removeCallbacks(destinationSuggestionTask);
                    }
                    destinationSuggestionTask = createSuggestionTask(field, s.toString());
                    suggestionHandler.postDelayed(destinationSuggestionTask, 400);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private Runnable createSuggestionTask(AutoCompleteTextView field, String query) {
        return () -> {
            if (query.trim().length() < 2) {
                return;
            }
            geocoderExecutor.execute(() -> {
                List<Address> addresses = new ArrayList<>();
                try {
                    Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                    List<Address> results = geocoder.getFromLocationName(query.trim(), 5);
                    if (results != null) {
                        addresses = results;
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "Location suggestions are unavailable right now.",
                            Toast.LENGTH_SHORT).show());
                }

                ArrayList<String> suggestions = new ArrayList<>();
                for (Address address : addresses) {
                    String line = address.getAddressLine(0);
                    if (line != null && !line.trim().isEmpty()) {
                        suggestions.add(line);
                    }
                }
                runOnUiThread(() -> {
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(
                            this,
                            android.R.layout.simple_dropdown_item_1line,
                            suggestions);
                    field.setAdapter(adapter);
                    if (!suggestions.isEmpty() && field.hasFocus()) {
                        field.showDropDown();
                    }
                });
            });
        };
    }

    private void resolveRouteLocations() {
        String sourceText = etSource.getText().toString().trim();
        String destinationText = etDestination.getText().toString().trim();
        if (destinationText.isEmpty()) {
            Toast.makeText(this, "Enter a destination location.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (sourceText.isEmpty() && currentLatLong != null) {
            resolveDestinationAndFetch(destinationText);
            return;
        }
        if (sourceText.isEmpty()) {
            Toast.makeText(this, "Current location is not available yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        resolveLocation(sourceText, point -> {
            if (point == null) {
                Toast.makeText(this, "Start location could not be found.", Toast.LENGTH_SHORT).show();
                return;
            }
            currentLatLong = point;
            resolveDestinationAndFetch(destinationText);
        });
    }

    private void resolveDestinationAndFetch(String destinationText) {
        resolveLocation(destinationText, destination -> {
            if (destination == null) {
                Toast.makeText(this, "Destination could not be found.", Toast.LENGTH_SHORT).show();
                return;
            }
            destinationLatLong = destination;
            addDestinationMarker(destination);
            fetchRoute(currentLatLong, destinationLatLong);
        });
    }

    private void resolveLocation(String query, java.util.function.Consumer<GeoPoint> callback) {
        geocoderExecutor.execute(() -> {
            GeoPoint point = null;
            try {
                Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                List<Address> results = geocoder.getFromLocationName(query, 1);
                if (results != null && !results.isEmpty()) {
                    point = new GeoPoint(results.get(0).getLatitude(), results.get(0).getLongitude());
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Could not search that location.", Toast.LENGTH_SHORT).show());
            }
            GeoPoint resolvedPoint = point;
            runOnUiThread(() -> callback.accept(resolvedPoint));
        });
    }

    private void addDestinationMarker(GeoPoint point) {
        Marker endMarker = new Marker(map);
        endMarker.setPosition(point);
        endMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        endMarker.setTitle("Destination");
        map.getOverlays().add(endMarker);
        map.getController().animateTo(point);
        map.invalidate();
    }

    @SuppressLint("StaticFieldLeak")
    private void fetchRoute(GeoPoint start, GeoPoint end) {
        btnGetRoute.setVisibility(android.view.View.INVISIBLE);
        progressBar.setVisibility(android.view.View.VISIBLE);

        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... voids) {
                try {
                    String urlString = "https://router.project-osrm.org/route/v1/driving/" +
                            start.getLongitude() + "," + start.getLatitude() + ";" +
                            end.getLongitude() + "," + end.getLatitude() +
                            "?overview=full&geometries=geojson";

                    URL url = new URL(urlString);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", getPackageName() + "/1.0");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);

                    int responseCode = conn.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder result = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            result.append(line);
                        }
                        reader.close();
                        conn.disconnect();
                        return result.toString();
                    } else {
                        conn.disconnect();
                        return null;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    return null;
                }
            }

            @Override
            protected void onPostExecute(String result) {
                progressBar.setVisibility(android.view.View.GONE);
                btnGetRoute.setVisibility(android.view.View.VISIBLE);

                if (result != null) {
                    parseOSRMRoute(result);
                } else {
                    Toast.makeText(RouteActivity.this, "Route Fetch Failed: Check Internet or Try Again",
                            Toast.LENGTH_LONG).show();
                }
            }
        }.execute();
    }

    private void parseOSRMRoute(String jsonResponse) {
        try {
            JSONObject json = new JSONObject(jsonResponse);
            JSONArray routes = json.getJSONArray("routes");
            if (routes.length() > 0) {
                JSONObject route = routes.getJSONObject(0);
                JSONObject geometry = route.getJSONObject("geometry");
                JSONArray coordinates = geometry.getJSONArray("coordinates");

                ArrayList<GeoPoint> geoPoints = new ArrayList<>();
                routePoints = new ArrayList<>();

                for (int i = 0; i < coordinates.length(); i++) {
                    JSONArray coord = coordinates.getJSONArray(i);
                    double lon = coord.getDouble(0);
                    double lat = coord.getDouble(1);
                    geoPoints.add(new GeoPoint(lat, lon));
                    routePoints.add(new SimpleLocation(lat, lon));
                }

                if (roadOverlay != null)
                    map.getOverlays().remove(roadOverlay);
                roadOverlay = new Polyline();
                roadOverlay.setPoints(geoPoints);
                roadOverlay.setColor(0xFF0000FF);
                roadOverlay.setWidth(12f);
                map.getOverlays().add(0, roadOverlay);
                map.invalidate();

                // Zoom to bounds
                if (!geoPoints.isEmpty()) {
                    org.osmdroid.util.BoundingBox boundingBox = org.osmdroid.util.BoundingBox.fromGeoPoints(geoPoints);
                    map.zoomToBoundingBox(boundingBox, true, 50); // 50px padding
                }

                btnStartRoute.setEnabled(true);
                Toast.makeText(this, "Route Found!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "No route found.", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error parsing route.", Toast.LENGTH_SHORT).show();
        }
    }

    private void startFollowingUser() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return;

        if (locationUpdatesActive)
            return;

        com.google.android.gms.location.LocationRequest request = new com.google.android.gms.location.LocationRequest.Builder(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 5000).build();

        locationCallback = new com.google.android.gms.location.LocationCallback() {
            @Override
            public void onLocationResult(com.google.android.gms.location.LocationResult locationResult) {
                if (locationResult != null && locationResult.getLastLocation() != null) {
                    android.location.Location loc = locationResult.getLastLocation();
                    GeoPoint newLoc = new GeoPoint(loc.getLatitude(), loc.getLongitude());
                    currentLatLong = newLoc;
                    updateCurrentLocationMarker(newLoc);
                    map.getController().animateTo(newLoc);
                }
            }
        };
        fusedLocationClient.requestLocationUpdates(request, locationCallback, android.os.Looper.getMainLooper());
        locationUpdatesActive = true;
    }

    private void stopFollowingUser() {
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
        locationUpdatesActive = false;
    }

    private void updateCurrentLocationMarker(GeoPoint location) {
        if (currentLocationMarker == null) {
            currentLocationMarker = new Marker(map);
            currentLocationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            currentLocationMarker.setTitle("Your live location");
            map.getOverlays().add(currentLocationMarker);
        }
        currentLocationMarker.setPosition(location);
        map.invalidate();
    }
}
