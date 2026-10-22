package de.ultron.relay

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/** Builds a small, colored Structure-from-Motion point cloud entirely on the phone. */
object LocalReconstruction {
    data class Output(val uri: Uri, val pointCount: Int, val pairsUsed: Int)

    private data class FrameFeatures(
        val points: Array<org.opencv.core.KeyPoint>,
        val descriptors: Mat,
        val colors: IntArray,
        val width: Int,
        val height: Int
    )

    private data class ColoredPoint(val x: Double, val y: Double, val z: Double, val color: Int)

    fun build(context: Context, videoUri: Uri, progress: (String) -> Unit): Output {
        if (!OpenCVLoader.initLocal()) throw IllegalStateException("OpenCV konnte auf diesem Handy nicht gestartet werden.")

        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<FrameFeatures>()
        try {
            retriever.setDataSource(context, videoUri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(1L)
                ?: throw IllegalArgumentException("Die Videolänge konnte nicht gelesen werden.")

            val sampleCount = ceil(durationMs / 1_000.0).toInt().coerceIn(8, 60)
            val stepUs = (durationMs * 1_000L / (sampleCount + 1)).coerceAtLeast(500_000L)
            val orb = ORB.create(1_500)
            for (index in 1..sampleCount) {
                progress("Handy verarbeitet Bild $index von $sampleCount …")
                val bitmap = retriever.getScaledFrameAtTime(
                    stepUs * index,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    960,
                    960
                ) ?: continue
                try {
                    val rgba = Mat()
                    val gray = Mat()
                    val keypoints = MatOfKeyPoint()
                    val descriptors = Mat()
                    val emptyMask = Mat()
                    Utils.bitmapToMat(bitmap, rgba)
                    Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                    orb.detectAndCompute(gray, emptyMask, keypoints, descriptors)
                    val pointArray = keypoints.toArray()
                    if (pointArray.size >= 80 && !descriptors.empty()) {
                        val pixelBuffer = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixelBuffer, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        val colors = IntArray(pointArray.size) { pointIndex ->
                            val point = pointArray[pointIndex].pt
                            val x = point.x.toInt().coerceIn(0, bitmap.width - 1)
                            val y = point.y.toInt().coerceIn(0, bitmap.height - 1)
                            pixelBuffer[y * bitmap.width + x]
                        }
                        frames += FrameFeatures(pointArray, descriptors, colors, bitmap.width, bitmap.height)
                    } else {
                        descriptors.release()
                    }
                    rgba.release()
                    gray.release()
                    keypoints.release()
                    emptyMask.release()
                } finally {
                    bitmap.recycle()
                }
            }
            if (frames.size < 3) throw IllegalArgumentException("Zu wenige klare Videobilder. Nimm ein längeres Video mit sichtbaren Details und langsamer Kamerabewegung.")

            progress("Merkmale zwischen den Videobildern werden abgeglichen …")
            val imageWidth = frames.first().width.toDouble()
            val imageHeight = frames.first().height.toDouble()
            // DJI Neo lens: 117.6° diagonal field of view. This is an approximate
            // starting focal length; the reconstruction scale remains relative.
            val focal = imageWidth * 0.38
            val cx = imageWidth / 2.0
            val cy = imageHeight / 2.0
            val camera = Mat.eye(3, 3, CvType.CV_64F)
            camera.put(0, 0, focal)
            camera.put(0, 2, cx)
            camera.put(1, 1, focal)
            camera.put(1, 2, cy)

            val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
            var rotationWorldCamera = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            var translationWorldCamera = doubleArrayOf(0.0, 0.0, 0.0)
            val points = mutableListOf<ColoredPoint>()
            var pairsUsed = 0

            for (frameIndex in 0 until frames.lastIndex) {
                progress("3D-Punktwolke: Bild ${frameIndex + 1} von ${frames.size} …")
                val first = frames[frameIndex]
                val second = frames[frameIndex + 1]
                val knn = ArrayList<MatOfDMatch>()
                matcher.knnMatch(first.descriptors, second.descriptors, knn, 2)
                val matches = knn.mapNotNull { pair ->
                    val candidates = pair.toArray()
                    if (candidates.size >= 2 && candidates[0].distance < candidates[1].distance * 0.72f && candidates[0].distance < 64f) candidates[0] else null
                }.take(1_000)
                knn.forEach { it.release() }
                if (matches.size < 30) continue

                val firstPoints = MatOfPoint2f(*matches.map { first.points[it.queryIdx].pt }.toTypedArray())
                val secondPoints = MatOfPoint2f(*matches.map { second.points[it.trainIdx].pt }.toTypedArray())
                val inlierMask = Mat()
                val essential = Calib3d.findEssentialMat(firstPoints, secondPoints, camera, Calib3d.RANSAC, 0.999, 1.5, 2_000, inlierMask)
                if (essential.empty()) {
                    firstPoints.release(); secondPoints.release(); inlierMask.release(); essential.release()
                    continue
                }
                val relativeRotation = Mat()
                val relativeTranslation = Mat()
                val inliers = Calib3d.recoverPose(essential, firstPoints, secondPoints, camera, relativeRotation, relativeTranslation, inlierMask)
                if (inliers < 18) {
                    firstPoints.release(); secondPoints.release(); inlierMask.release(); essential.release()
                    relativeRotation.release(); relativeTranslation.release()
                    continue
                }

                val selectedMatches = matches.indices.filter { inlierMask.get(it, 0)?.firstOrNull()?.let { value -> value != 0.0 } == true }
                val triangulationFirst = MatOfPoint2f(*selectedMatches.map { first.points[matches[it].queryIdx].pt }.toTypedArray())
                val triangulationSecond = MatOfPoint2f(*selectedMatches.map { second.points[matches[it].trainIdx].pt }.toTypedArray())
                val projectionFirst = projectionMatrix(camera, null, null)
                val relativeR = DoubleArray(9) { i -> relativeRotation.get(i / 3, i % 3)[0] }
                val relativeT = DoubleArray(3) { i -> relativeTranslation.get(i, 0)[0] }
                val projectionSecond = projectionMatrix(camera, relativeR, relativeT)
                val homogeneous = Mat()
                if (selectedMatches.size >= 12) {
                    Calib3d.triangulatePoints(projectionFirst, projectionSecond, triangulationFirst, triangulationSecond, homogeneous)
                    for (pointIndex in selectedMatches.indices) {
                        val w = homogeneous.get(3, pointIndex)?.firstOrNull() ?: 0.0
                        if (kotlin.math.abs(w) < 1e-9) continue
                        val x = homogeneous.get(0, pointIndex)!![0] / w
                        val y = homogeneous.get(1, pointIndex)!![0] / w
                        val z = homogeneous.get(2, pointIndex)!![0] / w
                        val inSecondCameraZ = relativeR[6] * x + relativeR[7] * y + relativeR[8] * z + relativeT[2]
                        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || z <= 0.02 || z > 80.0 || inSecondCameraZ <= 0.02) continue
                        val world = multiply(rotationWorldCamera, doubleArrayOf(x, y, z))
                        val color = first.colors[matches[selectedMatches[pointIndex]].queryIdx]
                        points += ColoredPoint(world[0] + translationWorldCamera[0], world[1] + translationWorldCamera[1], world[2] + translationWorldCamera[2], color)
                    }
                    pairsUsed++
                }

                val nextRotation = multiplyMatrices(rotationWorldCamera, transpose(relativeR))
                val movedTranslation = multiply(nextRotation, relativeT)
                translationWorldCamera = doubleArrayOf(
                    translationWorldCamera[0] - movedTranslation[0],
                    translationWorldCamera[1] - movedTranslation[1],
                    translationWorldCamera[2] - movedTranslation[2]
                )
                rotationWorldCamera = nextRotation

                firstPoints.release(); secondPoints.release(); inlierMask.release(); essential.release()
                relativeRotation.release(); relativeTranslation.release()
                triangulationFirst.release(); triangulationSecond.release()
                projectionFirst.release(); projectionSecond.release(); homogeneous.release()
            }
            camera.release()
            matcher.clear()
            if (points.size < 100 || pairsUsed == 0) throw IllegalArgumentException("Aus diesem Video konnten zu wenige 3D-Punkte berechnet werden. Fliege langsam an einem detailreichen Objekt vorbei und filme mehrere überlappende Blickwinkel.")
            progress("3D-Datei wird in Downloads/ULTRON gespeichert …")
            val savedUri = savePly(context, points)
            return Output(savedUri, points.size, pairsUsed)
        } finally {
            frames.forEach { it.descriptors.release() }
            retriever.release()
        }
    }

    private fun projectionMatrix(camera: Mat, rotation: DoubleArray?, translation: DoubleArray?): Mat {
        val extrinsic = Mat.zeros(3, 4, CvType.CV_64F)
        for (row in 0..2) for (column in 0..2) {
            extrinsic.put(row, column, rotation?.get(row * 3 + column) ?: if (row == column) 1.0 else 0.0)
        }
        if (translation != null) for (row in 0..2) extrinsic.put(row, 3, translation[row])
        val projection = Mat.zeros(3, 4, CvType.CV_64F)
        val empty = Mat()
        Core.gemm(camera, extrinsic, 1.0, empty, 0.0, projection)
        extrinsic.release()
        empty.release()
        return projection
    }

    private fun multiply(matrix: DoubleArray, vector: DoubleArray) = DoubleArray(3) { row ->
        matrix[row * 3] * vector[0] + matrix[row * 3 + 1] * vector[1] + matrix[row * 3 + 2] * vector[2]
    }

    private fun multiplyMatrices(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { index ->
        val row = index / 3
        val col = index % 3
        a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col]
    }

    private fun transpose(matrix: DoubleArray) = DoubleArray(9) { index -> matrix[(index % 3) * 3 + index / 3] }

    private fun savePly(context: Context, points: List<ColoredPoint>): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ULTRON_3D_${System.currentTimeMillis()}.ply")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.ply")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ULTRON")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Die 3D-Datei konnte nicht in Downloads/ULTRON angelegt werden.")
        try {
            val stream = context.contentResolver.openOutputStream(uri, "w")
                ?: throw IllegalStateException("Die 3D-Datei konnte nicht gespeichert werden.")
            stream.use { output ->
                BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer ->
                    writer.write("ply\nformat ascii 1.0\nelement vertex ${points.size}\n")
                    writer.write("property float x\nproperty float y\nproperty float z\n")
                    writer.write("property uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n")
                    points.forEach { point ->
                        val red = (point.color shr 16) and 0xff
                        val green = (point.color shr 8) and 0xff
                        val blue = point.color and 0xff
                        writer.write(String.format(Locale.US, "%.6f %.6f %.6f %d %d %d\n", point.x, point.y, point.z, red, green, blue))
                    }
                }
            }
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            return uri
        } catch (error: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }
}
