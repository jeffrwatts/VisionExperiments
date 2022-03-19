package com.jeffrwatts.visionexperiments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.FlannBasedMatcher
import org.opencv.features2d.SIFT
import java.io.File
import java.io.FileInputStream
import kotlin.Exception
import kotlin.text.StringBuilder

typealias PositionListener = (x: Float, y: Float, z: Float) -> Unit

class VisualOdometryAnalyzer (calibrationMatrix: FloatArray,
                              private val positionListener: PositionListener): ImageAnalysis.Analyzer {
    companion object {
        private const val TAG = "VisualOdometryAnalyzer"
        private const val SAMPLE_RATE_MS = 500
        private const val MATCH_DISTANCE_THRESHOLD = 0.2
    }

    private var isTracking = false
    private var lastProcessedTime = System.currentTimeMillis()

    private var cameraMatrix: Mat
    private var position = Mat.eye(4, 4, CvType.CV_64FC1)

    init {
        cameraMatrix = createCameraMatrix(calibrationMatrix)
    }

    private val sift = SIFT.create()
    private val flannBasedMatcher = FlannBasedMatcher()
    private var previousDescriptors = Mat()
    private var previousKeyPointsMat = MatOfKeyPoint()

    fun startTracking () {
        isTracking = true
    }

    fun resetPosition() {
        position = Mat.eye(4, 4, CvType.CV_64FC1)
    }

    fun pauseTracking () {
        isTracking = false
    }

    override fun analyze(image: ImageProxy) {
        val frameTime = System.currentTimeMillis()
        val delta = frameTime - lastProcessedTime
        if (delta >= SAMPLE_RATE_MS && isTracking) {
            lastProcessedTime = frameTime

            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(image.planes[0].buffer)

            val matrix = Matrix()
            matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
            val bitmapRotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

            updatePositionFromImage(bitmapRotated)

            val posX = position[0, 3][0]
            val posY = position[1, 3][0]
            val posZ = position[2, 3][0]
            positionListener(posX.toFloat(), posY.toFloat(), posZ.toFloat())
        }
        image.close()
    }

    private fun updatePositionFromImage(currentFrameBitmap: Bitmap) {
        // Convert Image to Matrix
        val currentFrame = Mat()
        Utils.bitmapToMat(currentFrameBitmap, currentFrame)

        // Extract the KeyPoints and Descriptors of the current frame.
        val currentKeyPointsMat = MatOfKeyPoint()
        val currentDescriptors = Mat()
        sift.detectAndCompute(currentFrame, Mat(), currentKeyPointsMat, currentDescriptors)

        val currentKeyPoints = currentKeyPointsMat.toArray()
        val previousKeyPoints = previousKeyPointsMat.toArray()

        // previousKeyPoints will be empty if this is the first frame.
        if (previousKeyPoints.isNotEmpty()) {
            // Find matches between this frame and the previous frame.
            val matches = mutableListOf<MatOfDMatch>()
            flannBasedMatcher.knnMatch(previousDescriptors, currentDescriptors, matches, 2)

            val previousImagePoints = mutableListOf<Point>()
            val currentImagePoints = mutableListOf<Point>()

            // Go through each match and get the image points for those who satisfy the
            // distance threshold.
            matches.forEach {
                val match = it.toArray()
                if (match.size == 2) {
                    if (match[0].distance < MATCH_DISTANCE_THRESHOLD * match[1].distance) {
                        val previousImagePoint = previousKeyPoints[match[0].queryIdx].pt
                        val currentImagePoint = currentKeyPoints[match[0].trainIdx].pt
                        previousImagePoints.add(previousImagePoint)
                        currentImagePoints.add(currentImagePoint)
                    }
                }
            }

            // Find the Essential Matrix
            val previousImagePointsMat = MatOfPoint().also { it.fromList(previousImagePoints) }
            val currentImagePointsMat = MatOfPoint().also { it.fromList(currentImagePoints) }

            val E = Calib3d.findEssentialMat(previousImagePointsMat, currentImagePointsMat, cameraMatrix)
            val displayE = displayMatrix(E)

            // Recover the Rotation and translation matrices from E
            val R = Mat()
            val t = Mat()

            Calib3d.recoverPose(E, previousImagePointsMat, currentImagePointsMat, cameraMatrix, R, t)

            val displayR = displayMatrix(R)
            val displayt = displayMatrix(t)

            updatePositionFromRt(R, t)
        }

        previousKeyPointsMat = currentKeyPointsMat
        previousDescriptors = currentDescriptors
    }

    private fun updatePositionFromRt (R: Mat, t: Mat) {
        val positionUpdated = Mat()
        try {
            val positionNew = Mat.eye(4, 4, CvType.CV_64FC1)
            val RT = R.t()
            for (row in 0 until RT.rows()) {
                for (col in 0 until RT.cols()) {
                    positionNew.put(row, col, RT[row, col][0])
                }
            }
            val neg_RT_dot_t = Mat()
            Core.gemm(R.mul(Mat(3, 3, CvType.CV_64FC1, Scalar(-1.0))).t(), t, 1.0, Mat(), 0.0, neg_RT_dot_t, 0)

            for (row in 0 until neg_RT_dot_t.rows()) {
                positionNew.put(row, 3, neg_RT_dot_t[row, 0][0])
            }

            Core.gemm(position, positionNew, 1.0, Mat(), 0.0, positionUpdated, 0)
        } catch (e: Exception) {
            Log.e(TAG, "updatePositionFromRt Exception", e)
        }

        position = positionUpdated
    }

    private fun createCameraMatrix(calibrationParameters: FloatArray): Mat {
        // [f_x, f_y, c_x, c_y, s]
        val cameraMatrix = Mat(3,3, CvType.CV_32F)
        cameraMatrix.put(0, 0, FloatArray(1) { calibrationParameters[0] })
        cameraMatrix.put(0, 1, FloatArray(1) { calibrationParameters[4] })
        cameraMatrix.put(0, 2, FloatArray(1) { calibrationParameters[2] })
        cameraMatrix.put(1, 0, FloatArray(1) { 0.0f })
        cameraMatrix.put(1, 1, FloatArray(1) { calibrationParameters[1]})
        cameraMatrix.put(1, 2, FloatArray(1) { calibrationParameters[3] })
        cameraMatrix.put(2, 0, FloatArray(1) { 0.0f })
        cameraMatrix.put(2, 1, FloatArray(1) { 0.0f })
        cameraMatrix.put(2, 2, FloatArray(1) { 1.0f })

        val displayCameraMatrix = displayMatrix(cameraMatrix)
        return cameraMatrix
    }
    private fun displayMatrix(mat: Mat): String {
        val displayMatrix = StringBuilder()
        displayMatrix.append("[")
        for (row in 0 until mat.rows()) {
            displayMatrix.append("[")
            for (col in 0 until mat.cols()) {
                val value = mat[row, col][0]
                if (col != 0) {
                    displayMatrix.append(", ")
                }
                displayMatrix.append(value)
            }
            displayMatrix.append("]")
        }
        displayMatrix.append("]")
        return displayMatrix.toString()
    }

    fun testWithPhotos(context: Context, totalFrames: Int) {
        resetPosition()
        try {
            val xTraj = FloatArray(totalFrames)
            val yTraj = FloatArray(totalFrames)
            val zTraj = FloatArray(totalFrames)

            for (frameNumber in 0 until totalFrames) {
                FileInputStream(File(context.filesDir, "frame$frameNumber.webp")).use {
                    val currentFrameBitmap = BitmapFactory.decodeStream(it)
                    updatePositionFromImage(currentFrameBitmap)

                    val posX = position[0, 3][0]
                    val posY = position[1, 3][0]
                    val posZ = position[2, 3][0]

                    xTraj[frameNumber] = posX.toFloat()
                    yTraj[frameNumber] = posY.toFloat()
                    zTraj[frameNumber] = posZ.toFloat()
                }
            }
            dumpToNumpyArrayStatement(xTraj, yTraj, zTraj)
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading image", e)
        }
    }

    fun testWithAssets(context: Context) {
        resetPosition()
        val xTraj = FloatArray(52)
        val yTraj = FloatArray(52)
        val zTraj = FloatArray(52)

        for (i in 1..52) {
            val frameAsset = if (i < 10) "rgb/frame_0000$i.png" else "rgb/frame_000$i.png"
            val currentFrameBitmap = BitmapFactory.decodeStream(context.assets.open(frameAsset))

            updatePositionFromImage(currentFrameBitmap)

            val posX = position[0, 3][0]
            val posY = position[1, 3][0]
            val posZ = position[2, 3][0]

            xTraj[i-1] = posX.toFloat()
            yTraj[i-1] = posY.toFloat()
            zTraj[i-1] = posZ.toFloat()

            Log.d(TAG, "Iteration: $i; X=$posX; Y=$posY; Z=$posZ")
        }
        dumpToNumpyArrayStatement(xTraj, yTraj, zTraj)
    }

    private fun dumpToNumpyArrayStatement(xTraj: FloatArray, yTraj: FloatArray, zTraj: FloatArray) {
        val dump = StringBuilder("trajectory = np.array([")

        dump.append("[")
        xTraj.forEachIndexed { index, fl ->
            if (index != 0) { dump.append(", ")}
            dump.append(fl)
        }
        dump.append("], ")

        dump.append("[")
        yTraj.forEachIndexed { index, fl ->
            if (index != 0) { dump.append(", ")}
            dump.append(fl)
        }
        dump.append("], ")

        dump.append("[")
        zTraj.forEachIndexed { index, fl ->
            if (index != 0) { dump.append(", ")}
            dump.append(fl)
        }
        dump.append("]")

        dump.append("])")

        val dumpReturn = dump.toString()
    }
}

