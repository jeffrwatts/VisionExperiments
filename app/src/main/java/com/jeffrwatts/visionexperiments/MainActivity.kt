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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        OpenCVLoader.initDebug()

        buttonBasicCamera.isEnabled = false
        buttonBasicCamera.setOnClickListener { launchActivity(BasicCameraActivity::class.java) }

        buttonVisualOdometryTest.setOnClickListener { testVisualOdometry() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        } else {
            onCameraPermissionsGranted()
        }
    }

    private fun testVisualOdometry() {
        lifecycleScope.launch {
            val analyzer = VisualOdometryAnalyzer { x, y, z ->
                Log.d(TAG, "x=$x; y=$y; z=$z")
            }
            analyzer.test(this@MainActivity)
        }
    }

    private fun onCameraPermissionsGranted() {
        buttonBasicCamera.isEnabled = true
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