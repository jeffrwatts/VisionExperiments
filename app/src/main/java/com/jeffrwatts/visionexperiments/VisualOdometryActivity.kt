package com.jeffrwatts.visionexperiments

import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias Listener = (bitmap: Bitmap) -> Unit

class VisualOdometryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BasicCameraActivity"
    }

    private lateinit var visualOdometryAnalyzer: VisualOdometryAnalyzer

    private var tracking = false
    private val cameraViewFinder: PreviewView by lazy { findViewById(R.id.cameraViewFinder) }
    private val imageViewFrameDebug: ImageView by lazy { findViewById(R.id.imageViewFrameDebug) }
    private val buttonStartStop: Button by lazy { findViewById(R.id.buttonStartStop) }
    private val textViewPosition: TextView by lazy { findViewById(R.id.textViewPosition) }

    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_visual_odometry)
        OpenCVLoader.initDebug()
        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()

        buttonStartStop.setOnClickListener {
            if (tracking) {
                buttonStartStop.text = "Start"
                visualOdometryAnalyzer.pauseTracking()
                tracking = false
            } else {
                buttonStartStop.text = "Stop"
                visualOdometryAnalyzer.startTracking(resetPosition = true)
                tracking = true
            }
        }
    }

    private fun loadLensCalibrationParameters(): FloatArray? {
        var lensCalibrationScaled: FloatArray? = null
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        cameraManager.cameraIdList.forEach { cameraId ->
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                val pixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                pixelArraySize?.let { size ->
                    val scalingFactor = 480.0f / size.height
                    characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.let { lensCalibration ->
                        lensCalibrationScaled = FloatArray(lensCalibration.size).also { scaled ->
                            lensCalibration.forEachIndexed { index, param ->
                                scaled[index] = param*scalingFactor
                            }
                        }
                    }
                }
            }
        }
        return lensCalibrationScaled
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun startCamera() {
        val lensCalibration = loadLensCalibrationParameters()
        if (lensCalibration == null) {
            Toast.makeText(this, "Failed to load camera calibration parameters", Toast.LENGTH_LONG).show()
            return
        }
        visualOdometryAnalyzer = VisualOdometryAnalyzer(lensCalibration) { x, y, z ->
            val positionString = "x = %.2f; y = %.2f; z = %.2f".format(x, y, z)
            runOnUiThread { textViewPosition.text = positionString }
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(cameraViewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also {
                    it.setAnalyzer(cameraExecutor, visualOdometryAnalyzer)
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (e: Exception) {
                Log.e(TAG, "Binding Failed", e)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private class Analyzer (private val listener: Listener) : ImageAnalysis.Analyzer {
        companion object {
            private const val sampleRate = 500 // in milliseconds.
        }

        private var lastProcessedTime = System.currentTimeMillis()

        override fun analyze(image: ImageProxy) {
            val frameTime = System.currentTimeMillis()
            val delta = frameTime - lastProcessedTime
            if (delta >= sampleRate) {
                lastProcessedTime = frameTime

                val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(image.planes[0].buffer)

                val matrix = Matrix()
                matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
                val bitmapRotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

                listener(bitmapRotated)
                Log.d(TAG, "timeDelta = ${System.currentTimeMillis() - frameTime}; width = ${bitmap.width}; height = ${bitmap.height}; rotation = ${image.imageInfo.rotationDegrees}")
            }
            image.close()
        }
    }
}