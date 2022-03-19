package com.jeffrwatts.visionexperiments

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1
    }
    private val buttonBasicCamera: Button by lazy { findViewById(R.id.buttonBasicCamera) }
    private val buttonVisualOdometryTest: Button by lazy { findViewById(R.id.buttonVisualOdometryTest) }
    private val buttonVisualOdometry: Button by lazy { findViewById(R.id.buttonVisualOdometry) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        OpenCVLoader.initDebug()

        buttonBasicCamera.isEnabled = false
        buttonBasicCamera.setOnClickListener { launchActivity(BasicCameraActivity::class.java) }

        buttonVisualOdometryTest.setOnClickListener { testVisualOdometry() }

        buttonVisualOdometry.isEnabled = false
        buttonVisualOdometry.setOnClickListener { launchActivity(VisualOdometryActivity::class.java) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        } else {
            onCameraPermissionsGranted()
        }
    }

    private fun testVisualOdometry() {
        lifecycleScope.launch {
            // [f_x, f_y, c_x, c_y, s]
            val f_x = 640.0f
            val c_x = 640.0f
            val f_y = 480.0f
            val c_y = 480.0f

            val calibrationMatrix = FloatArray(5).also {
                it[0] = f_x
                it[1] = f_y
                it[2] = c_x
                it[3] = c_y
                it[4] = 0f
            }

            val analyzer = VisualOdometryAnalyzer (calibrationMatrix) { x, y, z ->
                Log.d(TAG, "x=$x; y=$y; z=$z")
            }

            analyzer.testWithAssets(this@MainActivity)
        }
    }

    private fun onCameraPermissionsGranted() {
        buttonBasicCamera.isEnabled = true
        buttonVisualOdometry.isEnabled = true
    }

    private fun launchActivity(klass: Class<*>) {
        val intent = Intent(this, klass)
        startActivity(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            onCameraPermissionsGranted()
        } else {
            Toast.makeText(this, "Camera Permission Denied", Toast.LENGTH_LONG).show()
        }
    }
}