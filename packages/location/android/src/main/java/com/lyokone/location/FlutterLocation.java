package com.lyokone.location;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import java.util.HashMap;

import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;

public class FlutterLocation
        implements PluginRegistry.RequestPermissionsResultListener, PluginRegistry.ActivityResultListener {
    private static final String TAG = "FlutterLocation";

    @Nullable
    public Activity activity;

    private static final int REQUEST_PERMISSIONS_REQUEST_CODE = 34;
    private static final int REQUEST_CHECK_SETTINGS = 0x1;

    private static final int GPS_ENABLE_REQUEST = 0x1001;

    private LocationManager locationManager;
    public LocationListener mLocationListener;

    @TargetApi(Build.VERSION_CODES.N)
    private OnNmeaMessageListener mMessageListener;

    private Double mLastMslAltitude;

    // Parameters of the request
    private long updateIntervalMilliseconds = 5000;
    private long fastestUpdateIntervalMilliseconds = updateIntervalMilliseconds / 2;
    private float distanceFilter = 0f;

    public EventSink events;

    // Store result until a permission check is resolved
    public Result result;

    // Store the result for the requestService, used in ActivityResult
    private Result requestServiceResult;

    // Store result until a location is getting resolved
    public Result getLocationResult;

    FlutterLocation(Context applicationContext, @Nullable Activity activity) {
        this.activity = activity;
        this.locationManager = (LocationManager) applicationContext.getSystemService(Context.LOCATION_SERVICE);
    }

    void setActivity(@Nullable Activity activity) {
        this.activity = activity;
        if (this.activity != null) {
            createLocationListener();
        } else {
            if (locationManager != null) {
                locationManager.removeUpdates(mLocationListener);
            }
            locationManager = null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && locationManager != null) {
                locationManager.removeNmeaListener(mMessageListener);
                mMessageListener = null;
            }
        }
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        return onRequestPermissionsResultHandler(requestCode, permissions, grantResults);
    }

    public boolean onRequestPermissionsResultHandler(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_PERMISSIONS_REQUEST_CODE && permissions.length == 1
                && permissions[0].equals(Manifest.permission.ACCESS_FINE_LOCATION)) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Checks if this permission was automatically triggered by a location request
                if (getLocationResult != null || events != null) {
                    startRequestingLocation();
                }
                if (result != null) {
                    result.success(1);
                    result = null;
                }
            } else {
                if (!shouldShowRequestPermissionRationale()) {
                    sendError("PERMISSION_DENIED_NEVER_ASK",
                            "Location permission denied forever - please open app settings", null);
                    if (result != null) {
                        result.success(2);
                        result = null;
                    }
                } else {
                    sendError("PERMISSION_DENIED", "Location permission denied", null);
                    if (result != null) {
                        result.success(0);
                        result = null;
                    }
                }
            }
            return true;
        }
        return false;

    }

    @Override
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case GPS_ENABLE_REQUEST:
                if (this.requestServiceResult == null) {
                    return false;
                }
                if (resultCode == Activity.RESULT_OK) {
                    this.requestServiceResult.success(1);
                } else {
                    this.requestServiceResult.success(0);
                }
                this.requestServiceResult = null;
                return true;
            case REQUEST_CHECK_SETTINGS:
                if (this.result == null) {
                    return false;
                }
                if (resultCode == Activity.RESULT_OK) {
                    startRequestingLocation();
                    return true;
                }

                this.result.error("SERVICE_STATUS_DISABLED", "Failed to get location. Location services disabled", null);
                this.result = null;
                return true;
            default:
                return false;
        }
    }

    public void changeSettings(Integer newLocationAccuracy, Long updateIntervalMilliseconds,
                               Long fastestUpdateIntervalMilliseconds, Float distanceFilter) {
        this.updateIntervalMilliseconds = updateIntervalMilliseconds;
        this.fastestUpdateIntervalMilliseconds = fastestUpdateIntervalMilliseconds;
        this.distanceFilter = distanceFilter;

        createLocationListener();
        startRequestingLocation();
    }

    private void sendError(String errorCode, String errorMessage, Object errorDetails) {
        if (getLocationResult != null) {
            getLocationResult.error(errorCode, errorMessage, errorDetails);
            getLocationResult = null;
        }
        if (events != null) {
            events.error(errorCode, errorMessage, errorDetails);
            events = null;
        }
    }

    /**
     * Creates a callback for receiving location events.
     */
    private void createLocationListener() {
        if (mLocationListener != null) {
            locationManager.removeUpdates(mLocationListener);
            mLocationListener = null;
        }

        mLocationListener = new LocationListener() {
            @Override
            public void onProviderEnabled(@NonNull String provider) {

            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {

            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {

            }

            @Override
            public void onLocationChanged(@NonNull Location location) {
                HashMap<String, Object> loc = new HashMap<>();
                loc.put("latitude", location.getLatitude());
                loc.put("longitude", location.getLongitude());
                loc.put("accuracy", (double) location.getAccuracy());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    loc.put("verticalAccuracy", (double) location.getVerticalAccuracyMeters());
                    loc.put("headingAccuracy", (double) location.getBearingAccuracyDegrees());
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    loc.put("elapsedRealtimeUncertaintyNanos", (double) location.getElapsedRealtimeUncertaintyNanos());
                }

                loc.put("provider", location.getProvider());
                final Bundle extras = location.getExtras();
                if (extras != null) {
                    loc.put("satelliteNumber", location.getExtras().getInt("satellites"));
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                    loc.put("elapsedRealtimeNanos", (double) location.getElapsedRealtimeNanos());

                    if (location.isFromMockProvider()) {
                        loc.put("isMock", (double) 1);
                    }
                } else {
                    loc.put("isMock", (double) 0);
                }

                // Using NMEA Data to get MSL level altitude
                if (mLastMslAltitude == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    loc.put("altitude", location.getAltitude());
                } else {
                    loc.put("altitude", mLastMslAltitude);
                }

                loc.put("speed", (double) location.getSpeed());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    loc.put("speed_accuracy", (double) location.getSpeedAccuracyMetersPerSecond());
                }
                loc.put("heading", (double) location.getBearing());
                loc.put("time", (double) location.getTime());

                if (getLocationResult != null) {
                    getLocationResult.success(loc);
                    getLocationResult = null;
                }
                if (events != null) {
                    events.success(loc);
                } else {
                    if (locationManager != null) {
                        locationManager.removeUpdates(mLocationListener);
                    }
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mMessageListener = (message, timestamp) -> {
                if (message.startsWith("$")) {
                    String[] tokens = message.split(",");
                    String type = tokens[0];

                    // Parse altitude above sea level, Detailed description of NMEA string here
                    // http://aprs.gids.nl/nmea/#gga
                    if (type.startsWith("$GPGGA") && tokens.length > 9) {
                        if (!tokens[9].isEmpty()) {
                            mLastMslAltitude = Double.parseDouble(tokens[9]);
                        }
                    }
                }
            };
        }
    }

    /**
     * Return the current state of the permissions needed.
     */
    public boolean checkPermissions() {
        if (this.activity == null) {
            result.error("MISSING_ACTIVITY", "You should not checkPermissions activation outside of an activity.", null);
            throw new ActivityNotFoundException();
        }
        int locationPermissionState = ActivityCompat.checkSelfPermission(activity,
                Manifest.permission.ACCESS_FINE_LOCATION);
        return locationPermissionState == PackageManager.PERMISSION_GRANTED;
    }

    public void requestPermissions() {
        if (this.activity == null) {
            result.error("MISSING_ACTIVITY", "You should not requestPermissions activation outside of an activity.", null);
            throw new ActivityNotFoundException();
        }
        if (checkPermissions()) {
            result.success(1);
            return;
        }
        ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                REQUEST_PERMISSIONS_REQUEST_CODE);
    }

    public boolean shouldShowRequestPermissionRationale() {
        if (activity == null) {
            return false;
        }
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION);
    }

    /**
     * Checks whether location services is enabled.
     */
    public boolean checkServiceEnabled() {
        boolean gps_enabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        return gps_enabled;
    }

    public void requestService(final Result requestServiceResult) {
        if (this.activity == null) {
            requestServiceResult.error("MISSING_ACTIVITY", "You should not requestService activation outside of an activity.", null);
            throw new ActivityNotFoundException();
        }
        try {
            if (this.checkServiceEnabled()) {
                requestServiceResult.success(1);
                return;
            }
        } catch (Exception e) {
            requestServiceResult.error("SERVICE_STATUS_ERROR", "Location service status couldn't be determined", null);
            return;
        }

        this.requestServiceResult = requestServiceResult;

        AlertDialog.Builder builder = new AlertDialog.Builder(this.activity);
        builder.setMessage("Your precise location is needed to record your activity.")
                .setTitle("Location services disabled");
        builder.setPositiveButton("Go to Settings", (dialog, id) -> {
            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            FlutterLocation.this.activity.startActivityForResult(intent, GPS_ENABLE_REQUEST);
        });
        builder.setNegativeButton(android.R.string.cancel, (dialog, id) -> {
            // User cancelled the dialog
            requestServiceResult.error("SERVICE_STATUS_ERROR", "Location service status couldn't be determined", null);
        });

        builder.show();
    }

    public void startRequestingLocation() {
        if (this.activity == null) {
            result.error("MISSING_ACTIVITY", "You should not requestLocation activation outside of an activity.", null);
            throw new ActivityNotFoundException();
        }

        if (this.checkServiceEnabled()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                locationManager.addNmeaListener(mMessageListener, null);
            }

            if (locationManager != null) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        this.updateIntervalMilliseconds,
                        this.distanceFilter,
                        mLocationListener,
                        Looper.myLooper());
            }
        } else {
            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            this.activity.startActivityForResult(intent, REQUEST_CHECK_SETTINGS);
        }
    }

    public void cancelUpdates() {
        if (locationManager != null) {
            locationManager.removeUpdates(mLocationListener);
        }
        events = null;
    }
}
