package com.jeffrwatts.visionexperiments

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias ImageListener = (image: Bitmap) -> Unit

class BasicCameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BasicCameraActivity"
    }

    private var frameNumber = 0
    private var captureOnNextFrame = false
    private val cameraViewFinder: PreviewView by lazy { findViewById(R.id.cameraViewFinder) }
    private val buttonCaptureFrame: Button by lazy { findViewById(R.id.buttonCaptureFrame) }
    private val imageView: ImageView by lazy { findViewById(R.id.imageView) }
    private val textViewCameraImages: TextView by lazy { findViewById(R.id.textViewCameraImages) }
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_basic_camera)
        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()

        buttonCaptureFrame.setOnClickListener { captureOnNextFrame = true }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(cameraViewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetResolution(Size(1020, 768))
                .build().also {
                it.setAnalyzer(cameraExecutor, Analyzer { image: Bitmap ->
                    if (captureOnNextFrame) {
                        captureOnNextFrame = false
                        saveFrameImage(image, "frame$frameNumber.webp")
                        frameNumber++
                        runOnUiThread {
                            textViewCameraImages.text = "Camera Images $frameNumber"
                            imageView.setImageBitmap(image)

                        }
                    }
                })
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

    private fun saveFrameImage(bitmap: Bitmap, filename: String) {
        try {
            FileOutputStream(File(filesDir, filename)).use {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception writing file", e)
        }
    }

    private class Analyzer (private val imageListener: ImageListener) : ImageAnalysis.Analyzer {
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
                Log.d(TAG, "timeDelta = ${delta}; width = ${bitmap.width}; height = ${bitmap.height};")

                //val matrix = Matrix()
                //matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
                //val bitmapRotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

                imageListener(bitmap)
            }
            image.close()
        }
    }
}