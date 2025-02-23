package com.google.ar.core.examples.kotlin.helloar

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper
import com.google.ar.core.examples.java.common.helpers.DepthSettings
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper
import com.google.ar.core.examples.java.common.helpers.InstantPlacementSettings
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.kotlin.common.helpers.ARCoreSessionLifecycleHelper
import com.google.ar.core.exceptions.*

data class LocationPoint(val name: String, val latitude: Double, val longitude: Double)

private val knownLocations = listOf(
  LocationPoint("My House", 33.60547247689105, -112.36485413089035),
  LocationPoint("Bob's Place", 33.605585848613586, -112.36481255665254),
  LocationPoint("Shari's Place", 33.605317777305, -112.36486351862148)
)

class HelloArActivity : AppCompatActivity() {
  companion object {
    private const val TAG = "HelloArActivity"
  }

  private lateinit var fusedLocationClient: FusedLocationProviderClient
  private var userLocation: Location? = null
  private lateinit var locationCallback: LocationCallback
  private var lastClosestLocation: String? = null

  lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
  lateinit var view: HelloArView
  lateinit var renderer: HelloArRenderer

  val instantPlacementSettings = InstantPlacementSettings()
  val depthSettings = DepthSettings()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
    arCoreSessionHelper.exceptionCallback = { exception ->
      val message = when (exception) {
        is UnavailableUserDeclinedInstallationException -> "Please install Google Play Services for AR"
        is UnavailableApkTooOldException -> "Please update ARCore"
        is UnavailableSdkTooOldException -> "Please update this app"
        is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
        is CameraNotAvailableException -> "Camera not available. Try restarting the app."
        else -> "Failed to create AR session: $exception"
      }
      Log.e(TAG, "ARCore threw an exception", exception)
      view.snackbarHelper.showError(this, message)
    }

    arCoreSessionHelper.beforeSessionResume = ::configureSession
    lifecycle.addObserver(arCoreSessionHelper)

    renderer = HelloArRenderer(this)
    lifecycle.addObserver(renderer)

    view = HelloArView(this)
    lifecycle.addObserver(view)
    setContentView(view.root)

    SampleRender(view.surfaceView, renderer, assets)

    depthSettings.onCreate(this)
    instantPlacementSettings.onCreate(this)

    // ✅ Fix: Initialize fusedLocationClient before using it
    fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

    checkLocationPermission()
  }

  private fun checkLocationPermission() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
      == PackageManager.PERMISSION_GRANTED
    ) {
      startLocationUpdates()
    } else {
      ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
    }
  }

  private fun startLocationUpdates() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
      != PackageManager.PERMISSION_GRANTED
    ) {
      Log.e("GPS", "Location permission not granted.")
      return
    }

    Log.d("GPS", "Starting continuous location updates...")

    val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000) // Every 3 sec
      .setWaitForAccurateLocation(false)
      .setMinUpdateIntervalMillis(1500) // Fastest update in 1.5 sec
      .build()

    locationCallback = object : LocationCallback() {
      override fun onLocationResult(result: LocationResult) {
        result.lastLocation?.let { location ->
          userLocation = location
          Log.d("TAG", "Updated location: ${location.latitude}, ${location.longitude}")
          checkNearestLocation(location)
        }
      }
    }

    fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
  }

  private fun checkNearestLocation(userLocation: Location) {
    var closestLocation: LocationPoint? = null
    var minDistance = Float.MAX_VALUE

    for (loc in knownLocations) {
      val distance = FloatArray(1)
      Location.distanceBetween(
        userLocation.latitude, userLocation.longitude,
        loc.latitude, loc.longitude, distance
      )

      if (distance[0] < minDistance) {
        minDistance = distance[0]
        closestLocation = loc
      }
    }

    if (closestLocation != null) {
      Log.d("TAG", "Closest location: ${closestLocation.name}, Distance: ${minDistance}m")

      // ✅ Fix: Show toast only when location changes
      if (closestLocation.name != lastClosestLocation) {
        lastClosestLocation = closestLocation.name
        runOnUiThread {
          Toast.makeText(this, "You are near ${closestLocation.name}!", Toast.LENGTH_LONG).show()
        }
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    fusedLocationClient.removeLocationUpdates(locationCallback)
  }

  fun configureSession(session: Session) {
    session.configure(
      session.config.apply {
        lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

        depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
          Config.DepthMode.AUTOMATIC
        } else {
          Config.DepthMode.DISABLED
        }

        instantPlacementMode = if (instantPlacementSettings.isInstantPlacementEnabled) {
          Config.InstantPlacementMode.LOCAL_Y_UP
        } else {
          Config.InstantPlacementMode.DISABLED
        }
      }
    )
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    results: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, results)
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG).show()
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        CameraPermissionHelper.launchPermissionSettings(this)
      }
      finish()
    } else if (requestCode == 1 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
      Log.d(TAG, "Location permission granted.")
      startLocationUpdates() // ✅ Fix: Start location updates after permission is granted
    } else {
      Log.e(TAG, "Location permission denied.")
      Toast.makeText(this, "Location permission is required to use this feature.", Toast.LENGTH_LONG).show()
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
  }
}