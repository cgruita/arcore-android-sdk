package com.google.ar.core.examples.kotlin.helloar

import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.*
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper
import com.google.ar.core.examples.java.common.samplerender.*
import com.google.ar.core.examples.java.common.samplerender.Mesh
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer
import com.google.ar.core.examples.java.common.samplerender.arcore.PlaneRenderer
import com.google.ar.core.examples.java.common.samplerender.arcore.SpecularCubemapFilter
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class HelloArRenderer(val activity: HelloArActivity) : SampleRender.Renderer, DefaultLifecycleObserver {
  companion object {
    val TAG = "TAG"
    private val sphericalHarmonicFactors = floatArrayOf(0.282095f, -0.325735f, 0.325735f, -0.325735f, 0.273137f, -0.273137f, 0.078848f, -0.273137f, 0.136569f)
    private val Z_NEAR = 0.1f
    private val Z_FAR = 100f
    val APPROXIMATE_DISTANCE_METERS = 2.0f
    val CUBEMAP_RESOLUTION = 16
    val CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32
  }

  lateinit var render: SampleRender
  lateinit var planeRenderer: PlaneRenderer
  lateinit var backgroundRenderer: BackgroundRenderer
  lateinit var virtualSceneFramebuffer: Framebuffer
  var hasSetTextureNames = false

  lateinit var pointCloudVertexBuffer: VertexBuffer
  lateinit var pointCloudMesh: Mesh
  lateinit var pointCloudShader: Shader
  var lastPointCloudTimestamp: Long = 0

  lateinit var virtualObjectMesh: Mesh
  lateinit var virtualObjectShader: Shader
  lateinit var virtualObjectAlbedoTexture: Texture
  lateinit var virtualObjectAlbedoInstantPlacementTexture: Texture

  private val wrappedAnchors = mutableListOf<WrappedAnchor>()

  lateinit var dfgTexture: Texture
  lateinit var cubemapFilter: SpecularCubemapFilter

  val modelMatrix = FloatArray(16)
  val viewMatrix = FloatArray(16)
  val projectionMatrix = FloatArray(16)
  val modelViewMatrix = FloatArray(16)
  val modelViewProjectionMatrix = FloatArray(16)
  val sphericalHarmonicsCoefficients = FloatArray(9 * 3)
  val viewInverseMatrix = FloatArray(16)
  val worldLightDirection = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
  val viewLightDirection = FloatArray(4)

  private lateinit var sphereMesh: Mesh
  private lateinit var sphereShader: Shader
  private val sphereAnchors = mutableListOf<Anchor>()
  private var spheresPlaced = false

  val session get() = activity.arCoreSessionHelper.session
  val displayRotationHelper = DisplayRotationHelper(activity)
  val trackingStateHelper = TrackingStateHelper(activity)

  override fun onResume(owner: LifecycleOwner) {
    displayRotationHelper.onResume()
    hasSetTextureNames = false
  }

  override fun onPause(owner: LifecycleOwner) {
    displayRotationHelper.onPause()
  }

  override fun onSurfaceCreated(render: SampleRender) {
    this.render = render
    try {
      planeRenderer = PlaneRenderer(render)
      backgroundRenderer = BackgroundRenderer(render)
      virtualSceneFramebuffer = Framebuffer(render, 1, 1)

      cubemapFilter = SpecularCubemapFilter(render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES)
      dfgTexture = Texture(render, Texture.Target.TEXTURE_2D, Texture.WrapMode.CLAMP_TO_EDGE, false)
      val dfgResolution = 64
      val dfgChannels = 2
      val halfFloatSize = 2
      val buffer = ByteBuffer.allocateDirect(dfgResolution * dfgResolution * dfgChannels * halfFloatSize)
      activity.assets.open("models/dfg.raw").use { it.read(buffer.array()) }
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexture.textureId)
      GLError.maybeThrowGLException("Failed to bind DFG texture", "glBindTexture")
      GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG16F, dfgResolution, dfgResolution, 0, GLES30.GL_RG, GLES30.GL_HALF_FLOAT, buffer)
      GLError.maybeThrowGLException("Failed to populate DFG texture", "glTexImage2D")

      pointCloudShader = Shader.createFromAssets(render, "shaders/point_cloud.vert", "shaders/point_cloud.frag", null)
        .setVec4("u_Color", floatArrayOf(31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f))
        .setFloat("u_PointSize", 5.0f)
      pointCloudVertexBuffer = VertexBuffer(render, 4, null)
      pointCloudMesh = Mesh(render, Mesh.PrimitiveMode.POINTS, null, arrayOf(pointCloudVertexBuffer))

      virtualObjectAlbedoTexture = Texture.createFromAsset(render, "models/pawn_albedo.png", Texture.WrapMode.CLAMP_TO_EDGE, Texture.ColorFormat.SRGB)
      virtualObjectAlbedoInstantPlacementTexture = Texture.createFromAsset(render, "models/pawn_albedo_instant_placement.png", Texture.WrapMode.CLAMP_TO_EDGE, Texture.ColorFormat.SRGB)
      val virtualObjectPbrTexture = Texture.createFromAsset(render, "models/pawn_roughness_metallic_ao.png", Texture.WrapMode.CLAMP_TO_EDGE, Texture.ColorFormat.LINEAR)
      virtualObjectMesh = Mesh.createFromAsset(render, "models/pawn.obj")
      virtualObjectShader = Shader.createFromAssets(
        render, "shaders/environmental_hdr.vert", "shaders/environmental_hdr.frag",
        mapOf("NUMBER_OF_MIPMAP_LEVELS" to cubemapFilter.numberOfMipmapLevels.toString())
      ).setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture)
        .setTexture("u_RoughnessMetallicAmbientOcclusionTexture", virtualObjectPbrTexture)
        .setTexture("u_Cubemap", cubemapFilter.filteredCubemapTexture)
        .setTexture("u_DfgTexture", dfgTexture)

      // Sphere setup with broken chain for type clarity
      sphereShader = Shader.createFromAssets(render, "shaders/point_cloud.vert", "shaders/point_cloud.frag", null)
        .setVec4("u_Color", floatArrayOf(1.0f, 0.0f, 0.0f, 1.0f)) // Red
        .setFloat("u_PointSize", 50.0f) // Larger for visibility
      val sphereVerticesData = floatArrayOf(0f, 0f, 0f)
      val byteBuffer = ByteBuffer.allocateDirect(sphereVerticesData.size * 4).order(ByteOrder.nativeOrder())
      val sphereVerticesBuffer: FloatBuffer = byteBuffer.asFloatBuffer()
      sphereVerticesBuffer.put(sphereVerticesData)
      sphereVerticesBuffer.position(0)
      val sphereVertices = VertexBuffer(render, 3, sphereVerticesBuffer)
      sphereMesh = Mesh(render, Mesh.PrimitiveMode.POINTS, null, arrayOf(sphereVertices))
      Log.d(TAG, "Sphere mesh and shader initialized")
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      showError("Failed to read a required asset file: $e")
    }
  }

  override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
    displayRotationHelper.onSurfaceChanged(width, height)
    virtualSceneFramebuffer.resize(width, height)
  }

  fun placeSpheres(session: Session, camera: Camera) {
    if (spheresPlaced) return

    Log.d(TAG, "Attempting to place spheres, camera state: ${camera.trackingState}")
    if (camera.trackingState != TrackingState.TRACKING) {
      Log.d(TAG, "Camera not tracking, spheres not placed yet.")
      return
    }

    val cameraPose = camera.pose
    val offsets = listOf(
      floatArrayOf(0f, 0f, -1f), // 1m in front
      floatArrayOf(-0.5f, 0f, -2f), // 2m in front, left
      floatArrayOf(0.5f, 0f, -3f) // 3m in front, right
    )

    sphereAnchors.clear() // Reset anchors
    offsets.forEach { offset ->
      val translation = cameraPose.transformPoint(offset)
      val spherePose = Pose(translation, floatArrayOf(0f, 0f, 0f, 1f))
      val anchor = session.createAnchor(spherePose)
      sphereAnchors.add(anchor)
      Log.d(TAG, "Anchor placed at: ${translation[0]}, ${translation[1]}, ${translation[2]}")
    }
    spheresPlaced = true
    Log.d(TAG, "Spheres placed: ${sphereAnchors.size}")
  }

  override fun onDrawFrame(render: SampleRender) {
    val session = session ?: return
//    Log.d(TAG, "onDrawFrame running, session available")

    if (!hasSetTextureNames) {
      session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
      hasSetTextureNames = true
    }

    displayRotationHelper.updateSessionIfNeeded(session)
    val frame = try {
      session.update()
    } catch (e: CameraNotAvailableException) {
      Log.e(TAG, "Camera not available during onDrawFrame", e)
      showError("Camera not available. Try restarting the app.")
      return
    }

    val camera = frame.camera
    if (!spheresPlaced) {
      Log.d(TAG, "Calling placeSpheres")
      placeSpheres(session, camera)
    }

    try {
      backgroundRenderer.setUseDepthVisualization(render, activity.depthSettings.depthColorVisualizationEnabled())
      backgroundRenderer.setUseOcclusion(render, activity.depthSettings.useDepthForOcclusion())
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      showError("Failed to read a required asset file: $e")
      return
    }

    backgroundRenderer.updateDisplayGeometry(frame)
    val shouldGetDepthImage = activity.depthSettings.useDepthForOcclusion() || activity.depthSettings.depthColorVisualizationEnabled()
    if (camera.trackingState == TrackingState.TRACKING && shouldGetDepthImage) {
      try {
        val depthImage = frame.acquireDepthImage16Bits()
        backgroundRenderer.updateCameraDepthTexture(depthImage)
        depthImage.close()
      } catch (e: NotYetAvailableException) {
        // Normal, depth not available yet
      }
    }

    trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

    val message: String? = when {
      camera.trackingState == TrackingState.PAUSED -> TrackingStateHelper.getTrackingFailureReasonString(camera)
      sphereAnchors.isNotEmpty() -> null
      else -> "Waiting to place spheres..."
    }
    if (message == null) {
      activity.view.snackbarHelper.hide(activity)
    } else {
      activity.view.snackbarHelper.showMessage(activity, message)
    }

    if (frame.timestamp != 0L) {
      backgroundRenderer.drawBackground(render)
    }

    if (camera.trackingState == TrackingState.PAUSED) return

    camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)
    camera.getViewMatrix(viewMatrix, 0)

    frame.acquirePointCloud().use { pointCloud ->
      if (pointCloud.timestamp > lastPointCloudTimestamp) {
        pointCloudVertexBuffer.set(pointCloud.points)
        lastPointCloudTimestamp = pointCloud.timestamp
      }
      Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
      pointCloudShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
      render.draw(pointCloudMesh, pointCloudShader)
    }

    planeRenderer.drawPlanes(render, session.getAllTrackables(Plane::class.java), camera.displayOrientedPose, projectionMatrix)

    updateLightEstimation(frame.lightEstimate, viewMatrix)

    render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)
//    Log.d(TAG, "Rendering spheres, anchor count: ${sphereAnchors.size}")
    for (anchor in sphereAnchors) { // Removed filter for now to ensure drawing
      anchor.pose.toMatrix(modelMatrix, 0)
      Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
      Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
      sphereShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
      render.draw(sphereMesh, sphereShader, virtualSceneFramebuffer)
//      Log.d(TAG, "Drawing sphere at pose: ${anchor.pose.translation.joinToString()}")
    }
    backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
  }




  /** Checks if we detected at least one plane. */
  private fun Session.hasTrackingPlane() =
    getAllTrackables(Plane::class.java).any { it.trackingState == TrackingState.TRACKING }

  /** Update state based on the current frame's light estimation. */
  private fun updateLightEstimation(lightEstimate: LightEstimate, viewMatrix: FloatArray) {
    if (lightEstimate.state != LightEstimate.State.VALID) {
      virtualObjectShader.setBool("u_LightEstimateIsValid", false)
      return
    }
    virtualObjectShader.setBool("u_LightEstimateIsValid", true)
    Matrix.invertM(viewInverseMatrix, 0, viewMatrix, 0)
    virtualObjectShader.setMat4("u_ViewInverse", viewInverseMatrix)
    updateMainLight(
      lightEstimate.environmentalHdrMainLightDirection,
      lightEstimate.environmentalHdrMainLightIntensity,
      viewMatrix
    )
    updateSphericalHarmonicsCoefficients(lightEstimate.environmentalHdrAmbientSphericalHarmonics)
    cubemapFilter.update(lightEstimate.acquireEnvironmentalHdrCubeMap())
  }

  private fun updateMainLight(
    direction: FloatArray,
    intensity: FloatArray,
    viewMatrix: FloatArray
  ) {
    // We need the direction in a vec4 with 0.0 as the final component to transform it to view space
    worldLightDirection[0] = direction[0]
    worldLightDirection[1] = direction[1]
    worldLightDirection[2] = direction[2]
    Matrix.multiplyMV(viewLightDirection, 0, viewMatrix, 0, worldLightDirection, 0)
    virtualObjectShader.setVec4("u_ViewLightDirection", viewLightDirection)
    virtualObjectShader.setVec3("u_LightIntensity", intensity)
  }

  private fun updateSphericalHarmonicsCoefficients(coefficients: FloatArray) {
    // Pre-multiply the spherical harmonics coefficients before passing them to the shader. The
    // constants in sphericalHarmonicFactors were derived from three terms:
    //
    // 1. The normalized spherical harmonics basis functions (y_lm)
    //
    // 2. The lambertian diffuse BRDF factor (1/pi)
    //
    // 3. A <cos> convolution. This is done to so that the resulting function outputs the irradiance
    // of all incoming light over a hemisphere for a given surface normal, which is what the shader
    // (environmental_hdr.frag) expects.
    //
    // You can read more details about the math here:
    // https://google.github.io/filament/Filament.html#annex/sphericalharmonics
    require(coefficients.size == 9 * 3) {
      "The given coefficients array must be of length 27 (3 components per 9 coefficients"
    }

    // Apply each factor to every component of each coefficient
    for (i in 0 until 9 * 3) {
      sphericalHarmonicsCoefficients[i] = coefficients[i] * sphericalHarmonicFactors[i / 3]
    }
    virtualObjectShader.setVec3Array(
      "u_SphericalHarmonicsCoefficients",
      sphericalHarmonicsCoefficients
    )
  }

  // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
  private fun handleTap(frame: Frame, camera: Camera) {
    if (camera.trackingState != TrackingState.TRACKING) return
    val tap = activity.view.tapHelper.poll() ?: return

    val hitResultList =
      if (activity.instantPlacementSettings.isInstantPlacementEnabled) {
        frame.hitTestInstantPlacement(tap.x, tap.y, APPROXIMATE_DISTANCE_METERS)
      } else {
        frame.hitTest(tap)
      }

    // Hits are sorted by depth. Consider only closest hit on a plane, Oriented Point, Depth Point,
    // or Instant Placement Point.
    val firstHitResult =
      hitResultList.firstOrNull { hit ->
        when (val trackable = hit.trackable!!) {
          is Plane ->
            trackable.isPoseInPolygon(hit.hitPose) &&
              PlaneRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose) > 0
          is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
          is InstantPlacementPoint -> true
          // DepthPoints are only returned if Config.DepthMode is set to AUTOMATIC.
          is DepthPoint -> true
          else -> false
        }
      }

    if (firstHitResult != null) {
      // Cap the number of objects created. This avoids overloading both the
      // rendering system and ARCore.
      if (wrappedAnchors.size >= 20) {
        wrappedAnchors[0].anchor.detach()
        wrappedAnchors.removeAt(0)
      }

      // Adding an Anchor tells ARCore that it should track this position in
      // space. This anchor is created on the Plane to place the 3D model
      // in the correct position relative both to the world and to the plane.
      wrappedAnchors.add(WrappedAnchor(firstHitResult.createAnchor(), firstHitResult.trackable))

      // For devices that support the Depth API, shows a dialog to suggest enabling
      // depth-based occlusion. This dialog needs to be spawned on the UI thread.
      activity.runOnUiThread { activity.view.showOcclusionDialogIfNeeded() }
    }
  }

  private fun showError(errorMessage: String) =
    activity.view.snackbarHelper.showError(activity, errorMessage)
}

/**
 * Associates an Anchor with the trackable it was attached to. This is used to be able to check
 * whether or not an Anchor originally was attached to an {@link InstantPlacementPoint}.
 */
private data class WrappedAnchor(
  val anchor: Anchor,
  val trackable: Trackable,
)
