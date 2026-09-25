package ru.groksnap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val CAMERA_REQUEST = 1001
        private const val GROK_PACKAGE = "ai.x.grok"
        private const val PREVIEW_DELAY_MS = 600L
    }

    private lateinit var textureView: TextureView
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sent = false
    private var cameraId: String? = nullprivate fun chooseOptimalSize(sizes: Array<Size>): Size {
        // Prefer \~12MP or closest reasonable size, avoid huge 48MP if possible
        val target = 4000 * 3000L
        return sizes
            .filter { it.width * it.height.toLong() <= 48_000_000L }
            .minByOrNull { kotlin.math.abs(it.width.toLong() * it.height - target) }
            ?: sizes.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1920, 1080)
    }

    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val reader = imageReader ?: return
        val surfaceTexture = textureView.surfaceTexture ?: return

        try {
            // Configure preview surface (even if invisible)
            surfaceTexture.setDefaultBufferSize(1280, 720)
            val previewSurface = Surface(surfaceTexture)
            val jpegSurface = reader.surface

            camera.createCaptureSession(
                listOf(previewSurface, jpegSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        // Start a short preview so AF/AE can converge
                        startPreviewThenCapture(session, previewSurface, jpegSurface)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        finish()
                    }
                },
                backgroundHandler
            )
        } catch (_: Exception) {
            finish()
        }
    }

    private fun startPreviewThenCapture(
        session: CameraCaptureSession,
        previewSurface: Surface,
        jpegSurface: Surface
    ) {
        val camera = cameraDevice ?: return

        try {
            val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }.build()

            session.setRepeatingRequest(previewRequest, null, backgroundHandler)

            // Give camera time to focus / expose, then take still
            backgroundHandler?.postDelayed({
                captureStill(session, jpegSurface)
            }, PREVIEW_DELAY_MS)
        } catch (_: Exception) {
            finish()
        }
    }

    private fun captureStill(session: CameraCaptureSession, jpegSurface: Surface) {
        val camera = cameraDevice ?: return

        try {
            val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jpegSurface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation())
                set(CaptureRequest.JPEG_QUALITY, 92.toByte())
            }.build()

            session.capture(captureRequest, null, backgroundHandler)
        } catch (_: Exception) {
            finish()
        }
    }

    private fun getJpegOrientation(): Int {
        val manager = getSystemService(CameraManager::class.java) ?: return 90
        val id = cameraId ?: return 90
        val chars = manager.getCameraCharacteristics(id)
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val rotation = display?.rotation ?: Surface.ROTATION_0
        val deviceDegrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        // For back camera
        return (sensorOrientation - deviceDegrees + 360) % 360
    }

    private fun saveAndSend(bytes: ByteArray) {
        if (sent) return
        sent = true

        try {
            val dir = File(getExternalFilesDir(null), "Pictures/GrokSnap")
            if (!dir.exists()) dir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "GrokSnap_$timestamp.jpg")
            FileOutputStream(file).use { it.write(bytes) }

            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            sendToGrok(uri)
        } catch (_: Exception) {
            finish()
        }
    }

    private fun sendToGrok(uri: Uri) {
        val prompt = "Реши задачу на этом фото. Дай пошаговое решение."

        // Check if Grok is installed
        val grokInstalled = try {
            packageManager.getPackageInfo(GROK_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }

        if (grokInstalled) {
            val direct = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, prompt)
                setPackage(GROK_PACKAGE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                grantUriPermission(
                    GROK_PACKAGE,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                startActivity(direct)
                mainHandler.postDelayed({ finish() }, 800)
                return
            } catch (_: Exception) {
                // fall through to chooser
            }
        }

        // Fallback: system share sheet
        val fallback = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, prompt)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(Intent.createChooser(fallback, "Отправить фото в Grok"))
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось открыть Grok", Toast.LENGTH_SHORT).show()
        }

        mainHandler.postDelayed({ finish() }, 800)
    }

    override fun onDestroy() {
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (_: Exception) {
        }
        cameraDevice = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        stopBackgroundThread()
        super.onDestroy()
    }
}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Transparent activity – host for Camera2 only
        textureView = TextureView(this).apply { alpha = 0f }
        setContentView(textureView)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_REQUEST
            )
        } else {
            startBackgroundThread()
            startCameraWhenReady()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startBackgroundThread()
            startCameraWhenReady()
        } else {
            Toast.makeText(this, "Нужно разрешение камеры", Toast.LENGTH_SHORT).show()
            finish()
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
        } catch (_: InterruptedException) {
        }
        backgroundThread = null
        backgroundHandler = null
    }

    private fun startCameraWhenReady() {
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    openCamera()
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) = Unit

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        }
    }

    private fun openCamera() {
        val manager = getSystemService(CameraManager::class.java) ?: run {
            finish()
            return
        }

        try {
            val id = manager.cameraIdList.firstOrNull { camId ->
                val chars = manager.getCameraCharacteristics(camId)
                chars.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()

            if (id == null) {
                Toast.makeText(this, "Камера не найдена", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            cameraId = id

            val chars = manager.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: run {
                    finish()
                    return
                }

            val jpegSizes = map.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
            val size = chooseOptimalSize(jpegSizes)

            imageReader = ImageReader.newInstance(
                size.width,
                size.height,
                ImageFormat.JPEG,
                2
            ).also { reader ->
                reader.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        mainHandler.post { saveAndSend(bytes) }
                    } finally {
                        image.close()
                    }
                }, backgroundHandler)
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                finish()
                return
            }

            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    finish()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    finish()
                }
            }, backgroundHandler)

        } catch (_: Exception) {
            finish()
        }
    }
