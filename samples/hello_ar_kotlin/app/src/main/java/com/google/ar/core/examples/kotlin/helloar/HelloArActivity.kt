package com.google.ar.core.examples.kotlin.helloar

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.widget.TextView
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
    private const val TAG = "TAG"
  }

//  compass
  private lateinit var sensorManager: SensorManager
  private var accelerometerReading = FloatArray(3)
  private var magnetometerReading = FloatArray(3)
  private var rotationMatrix = FloatArray(9)
  private var orientationAngles = FloatArray(3)
  private lateinit var sensorEventListener: SensorEventListener

//  time compass settings
  private var lastDirection: String? = null
  private var lastUpdateTime = 0L
  private val UPDATE_INTERVAL_MS = 5000L // Only update once per second

  private lateinit var fusedLocationClient: FusedLocationProviderClient
  private var userLocation: Location? = null
  private lateinit var locationCallback: LocationCallback
  private var lastClosestLocation: String? = null

  lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
  lateinit var view: HelloArView
  lateinit var renderer: HelloArRenderer

  private lateinit var closestLocationText: TextView
  private lateinit var gpsText: TextView
  private lateinit var compassText: TextView

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

    closestLocationText = findViewById(R.id.closestLocationText)
    gpsText = findViewById(R.id.gpsText)
    compassText = findViewById(R.id.compassText)


    SampleRender(view.surfaceView, renderer, assets)

    depthSettings.onCreate(this)
    instantPlacementSettings.onCreate(this)

    // ✅ Fix: Initialize fusedLocationClient before using it
    fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

    checkLocationPermission()
    registerCompassListener()
  }





  private fun registerCompassListener() {
    sensorManager = getSystemService(SensorManager::class.java)
    val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    sensorEventListener = object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
          accelerometerReading = event.values.clone()
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
          magnetometerReading = event.values.clone()
        }

        val rotationMatrix = FloatArray(9)
        val inclinationMatrix = FloatArray(9)
        val remappedRotationMatrix = FloatArray(9)

        if (accelerometerReading.isNotEmpty() && magnetometerReading.isNotEmpty()) {
          val success = SensorManager.getRotationMatrix(rotationMatrix, inclinationMatrix, accelerometerReading, magnetometerReading)
          if (!success) return

          // ✅ Remap to align with **portrait mode**
          SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Y, remappedRotationMatrix)

          // ✅ Get azimuth from remapped rotation matrix
          SensorManager.getOrientation(remappedRotationMatrix, orientationAngles)
          var azimuthDegrees = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()

          // ✅ Apply geomagnetic declination properly
          userLocation?.let {
            val geomagneticField = GeomagneticField(
              it.latitude.toFloat(),
              it.longitude.toFloat(),
              it.altitude.toFloat(),
              System.currentTimeMillis()
            )
            azimuthDegrees -= geomagneticField.declination
          }

          // ✅ Normalize the azimuth:
          //   - Keep range between **0 to 360** for consistency
          //   - Ensure North stays **90°**
          azimuthDegrees = (azimuthDegrees + 360) % 360

          val direction = getDirectionFromAzimuth(azimuthDegrees)
          val currentTime = System.currentTimeMillis()

          // ✅ Only update UI every 5 sec or when direction changes
          if (direction != lastDirection || (currentTime - lastUpdateTime) > UPDATE_INTERVAL_MS) {
            lastDirection = direction
            lastUpdateTime = currentTime

            Log.d("COMPASS", "Heading: ${azimuthDegrees.toInt()}° ($direction)")

            runOnUiThread {
              compassText.text = "Compass: ${azimuthDegrees.toInt()}° ($direction)"
            }
          }
        }
      }

      override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    sensorManager.registerListener(sensorEventListener, accelerometer, SensorManager.SENSOR_DELAY_UI)
    sensorManager.registerListener(sensorEventListener, magnetometer, SensorManager.SENSOR_DELAY_UI)
  }

  private fun getDirectionFromAzimuth(azimuth: Float): String {
    return when {
      azimuth < 22.5 || azimuth >= 337.5 -> "North"
      azimuth in 22.5..67.5 -> "Northeast"
      azimuth in 67.5..112.5 -> "East"
      azimuth in 112.5..157.5 -> "Southeast"
      azimuth in 157.5..202.5 -> "South"
      azimuth in 202.5..247.5 -> "Southwest"
      azimuth in 247.5..292.5 -> "West"
      azimuth in 292.5..337.5 -> "Northwest"
      else -> "Unknown"
    }
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
          Log.d(TAG, "Updated location: ${location.latitude}, ${location.longitude}")
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
      Log.d(TAG, "Closest location: ${closestLocation.name}, Distance: ${minDistance}m")

      // ✅ Fix: Show toast only when location changes
      if (closestLocation.name != lastClosestLocation) {
        lastClosestLocation = closestLocation.name
        runOnUiThread {
//          Toast.makeText(this, "You are near ${closestLocation.name}!", Toast.LENGTH_LONG).show()
          gpsText.text = "GPS: ${userLocation.latitude}, ${userLocation.longitude}"
          closestLocationText.text = "Closest: ${closestLocation.name} (${minDistance.toInt()}m)"
        }
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    fusedLocationClient.removeLocationUpdates(locationCallback)

    //unregister compass listener
    sensorManager.unregisterListener(sensorEventListener)


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