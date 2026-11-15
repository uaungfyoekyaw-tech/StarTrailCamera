package com.example.startrailcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PERMISSIONS = 101
        private const val TARGET_SHOTS = 200
    }

    private lateinit var textureView: TextureView
    private lateinit var tvSpecs: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnCapture: Button

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraId: String = "0"
    private var widestAperture: Float? = null
    private var lowestIso: Int = 100
    private var longestExposureNs: Long = 10_000_000_000L
    private var manualSensorSupported: Boolean = false

    private val stacker = StarTrailStacker()
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isCapturingSequence = false

    @Volatile
    private var currentShotIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        textureView = findViewById(R.id.textureView)
        tvSpecs = findViewById(R.id.tvSpecs)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        btnCapture = findViewById(R.id.btnCapture)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        btnCapture.setOnClickListener {
            if (!isCapturingSequence) {
                startStarTrailSequence()
            } else {
                stopAndSaveEarly()
            }
        }

        if (hasPermissions()) {
            setupTextureListener()
        } else {
            requestPermissions()
        }
    }

    private fun hasPermissions(): Boolean {
        val camGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        val storageGranted = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
        return camGranted && storageGranted
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            setupTextureListener()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun setupTextureListener() {
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    openCamera()
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (_: InterruptedException) {}
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        startBackgroundThread()
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id
                    readCameraCapabilities(chars)
                    break
                }
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            tvStatus.text = "Camera Error: ${e.message}"
        }
    }

    private fun readCameraCapabilities(chars: CameraCharacteristics) {
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        manualSensorSupported = capabilities.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
        )

        val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        widestAperture = apertures?.minOrNull()

        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        if (isoRange != null) {
            lowestIso = isoRange.lower
        }

        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        if (exposureRange != null) {
            longestExposureNs = exposureRange.upper
        }

        val exposureSeconds = longestExposureNs / 1_000_000_000.0
        val apertureText = widestAperture?.let { String.format(Locale.US, "f/%.1f", it) } ?: "Fixed"
        val specString = String.format(
            Locale.US,
            "Aperture: %s | Min ISO: %d | Max Shutter: %.2fs%s",
            apertureText,
            lowestIso,
            exposureSeconds,
            if (manualSensorSupported) "" else " (Limited Manual Support)"
        )
        runOnUiThread {
            tvSpecs.text = specString
        }
    }

    private fun createCameraSession() {
        val camera = cameraDevice ?: return
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
        val captureSize = chooseBestSize(jpegSizes, 4032, 3024)

        imageReader = ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.JPEG, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()

            if (isCapturingSequence) {
                ioScope.launch {
                    val count = stacker.addFrame(bytes)
                    withContext(Dispatchers.Main) {
                        progressBar.progress = count
                        tvStatus.text = "Capturing & Stacking: $count / $TARGET_SHOTS"
                    }

                    if (count >= TARGET_SHOTS) {
                        finishSequence()
                    } else if (isCapturingSequence) {
                        captureNextLongExposureFrame()
                    }
                }
            }
        }, backgroundHandler)

        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(1920, 1080)
        val previewSurface = Surface(texture)
        val readerSurface = imageReader!!.surface

        camera.createCaptureSession(
            listOf(previewSurface, readerSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    startPreview(previewSurface)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    runOnUiThread { tvStatus.text = "Session configuration failed" }
                }
            },
            backgroundHandler
        )
    }

    private fun chooseBestSize(sizes: Array<Size>, maxW: Int, maxH: Int): Size {
        val valid = sizes.filter { it.width <= maxW && it.height <= maxH }
        return valid.maxByOrNull { it.width * it.height } ?: sizes.last()
    }

    private fun startPreview(previewSurface: Surface) {
        val camera = cameraDevice ?: return
        val previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        }
        captureSession?.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler)
    }

    private fun startStarTrailSequence() {
        if (captureSession == null || cameraDevice == null) return
        stacker.reset()
        currentShotIndex = 0
        isCapturingSequence = true

        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        btnCapture.text = "STOP & SAVE EARLY"
        tvStatus.text = "Starting 200-shot Long Exposure..."

        captureSession?.stopRepeating()
        captureNextLongExposureFrame()
    }

    private fun captureNextLongExposureFrame() {
        if (!isCapturingSequence) return
        val camera = cameraDevice ?: return
        val readerSurface = imageReader?.surface ?: return

        try {
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(readerSurface)

                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, lowestIso)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, longestExposureNs)
                set(CaptureRequest.SENSOR_FRAME_DURATION, longestExposureNs)

                widestAperture?.let {
                    set(CaptureRequest.LENS_APERTURE, it)
                }

                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
                set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            }

            currentShotIndex++
            captureSession?.capture(captureBuilder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            runOnUiThread {
                tvStatus.text = "Capture Error: ${e.message}"
            }
            stopAndSaveEarly()
        }
    }

    private fun stopAndSaveEarly() {
        isCapturingSequence = false
        ioScope.launch {
            finishSequence()
        }
    }

    private suspend fun finishSequence() {
        isCapturingSequence = false
        withContext(Dispatchers.Main) {
            tvStatus.text = "Saving final Star Trail image..."
            btnCapture.isEnabled = false
        }

        val savedUri = stacker.saveToGallery(this@MainActivity)

        withContext(Dispatchers.Main) {
            progressBar.visibility = View.GONE
            btnCapture.isEnabled = true
            btnCapture.text = "START 200 SHOTS"
            if (savedUri != null) {
                tvStatus.text = "Saved to Pictures/StarTrail!"
                Toast.makeText(this@MainActivity, "Star Trail saved!", Toast.LENGTH_LONG).show()
            } else {
                tvStatus.text = "Save failed or 0 frames captured."
            }

            textureView.surfaceTexture?.let { startPreview(Surface(it)) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isCapturingSequence = false
        ioScope.cancel()
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        stopBackgroundThread()
    }
}
