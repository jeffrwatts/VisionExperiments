package com.jeffrwatts.visionexperiments

import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.Exception

class BasicCameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BasicCameraActivity"
    }

    private val cameraViewFinder: PreviewView by lazy { findViewById(R.id.cameraViewFinder) }
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_basic_camera)
        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()
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
                .build().also {
                it.setAnalyzer(cameraExecutor, Analyzer())
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

    private class Analyzer : ImageAnalysis.Analyzer {
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
                bitmap.copyPixelsToBuffer(image.planes[0].buffer)
                Log.d(TAG, "timeDelta = ${delta}; width = ${bitmap.width}; height = ${bitmap.height};")
            }
            image.close()
        }
    }
}