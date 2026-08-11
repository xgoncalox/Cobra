package com.facerecog.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.facerecog.app.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var embedder: FaceEmbedder
    private lateinit var matcher: FaceMatcher
    private lateinit var db: AppDatabase

    private var lastUnknownBitmap: Bitmap? = null
    private var isProcessing = false

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
    )

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getInstance(this)
        embedder = FaceEmbedder(this)
        matcher = FaceMatcher(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        lifecycleScope.launch { matcher.refreshCache() }

        binding.btnAdmin.setOnClickListener {
            startActivity(Intent(this, AdminActivity::class.java))
        }

        binding.btnAssignUnknown.setOnClickListener {
            showAssignDialog()
        }
        binding.btnAssignUnknown.visibility = android.view.View.GONE

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::matcher.isInitialized) {
            lifecycleScope.launch { matcher.refreshCache() }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy -> analyzeFrame(imageProxy) }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, analyzer)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera bind failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || isProcessing) {
            imageProxy.close()
            return
        }
        isProcessing = true
        val rotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        detector.process(inputImage)
            .addOnSuccessListener { faces -> handleFaces(faces, imageProxy, rotation) }
            .addOnFailureListener { imageProxy.close(); isProcessing = false }
    }

    private fun handleFaces(faces: List<Face>, imageProxy: ImageProxy, rotation: Int) {
        if (faces.isEmpty()) {
            runOnUiThread {
                binding.overlay.setFaces(emptyList())
                binding.btnAssignUnknown.visibility = android.view.View.GONE
            }
            imageProxy.close()
            isProcessing = false
            return
        }

        val fullBitmap = imageProxyToBitmap(imageProxy, rotation)
        val overlays = mutableListOf<OverlayFace>()
        var foundUnknown: Bitmap? = null

        for (face in faces) {
            val box = face.boundingBox
            val faceBitmap = cropSafely(fullBitmap, box)
            var label = "..."
            var isKnown = false
            if (faceBitmap != null) {
                val embedding = embedder.getEmbedding(faceBitmap)
                val result = matcher.match(embedding)
                label = if (result.personId != null) result.personName else "Unknown"
                isKnown = result.personId != null
                if (!isKnown) foundUnknown = faceBitmap
            }
            val scaleX = binding.overlay.width.toFloat() / fullBitmap.width
            val scaleY = binding.overlay.height.toFloat() / fullBitmap.height
            val scaledBox = RectF(
                box.left * scaleX, box.top * scaleY,
                box.right * scaleX, box.bottom * scaleY
            )
            overlays.add(OverlayFace(scaledBox, label, isKnown))
        }

        lastUnknownBitmap = foundUnknown

        runOnUiThread {
            binding.overlay.setFaces(overlays)
            binding.btnAssignUnknown.visibility =
                if (foundUnknown != null) android.view.View.VISIBLE else android.view.View.GONE
        }

        imageProxy.close()
        isProcessing = false
    }

    private fun cropSafely(bitmap: Bitmap, box: Rect): Bitmap? {
        val left = box.left.coerceIn(0, bitmap.width - 1)
        val top = box.top.coerceIn(0, bitmap.height - 1)
        val right = box.right.coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.coerceIn(top + 1, bitmap.height)
        if (right <= left || bottom <= top) return null
        return try {
            Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        } catch (e: Exception) {
            null
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy, rotation: Int): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 90, out)
        val bytes = out.toByteArray()
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (rotation != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotation.toFloat())
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        return bitmap
    }

    private fun showAssignDialog() {
        val bitmap = lastUnknownBitmap ?: return
        lifecycleScope.launch {
            val persons = db.personDao().getAllPersonsList()
            val names = persons.map { it.name }.toMutableList()
            names.add(0, "+ New person")

            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Who is this?")
                .setItems(names.toTypedArray()) { _, which ->
                    if (which == 0) {
                        promptNewPersonName(bitmap)
                    } else {
                        val chosen = persons[which - 1]
                        saveFaceForPerson(chosen.id, bitmap)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun promptNewPersonName(bitmap: Bitmap) {
        val input = android.widget.EditText(this)
        android.app.AlertDialog.Builder(this)
            .setTitle("New person's name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        val imagePath = saveBitmapToDisk(bitmap, name)
                        val personId = db.personDao().insertPerson(
                            Person(name = name, thumbnailPath = imagePath)
                        )
                        saveFaceForPerson(personId, bitmap, imagePath)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveFaceForPerson(personId: Long, bitmap: Bitmap, existingPath: String? = null) {
        lifecycleScope.launch {
            val path = existingPath ?: saveBitmapToDisk(bitmap, "person_$personId")
            val embedding = embedder.getEmbedding(bitmap)
            db.personDao().insertEmbedding(
                FaceEmbeddingEntity(personId = personId, vector = embedding, imagePath = path)
            )
            matcher.refreshCache()
            Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
            binding.btnAssignUnknown.visibility = android.view.View.GONE
        }
    }

    private fun saveBitmapToDisk(bitmap: Bitmap, prefix: String): String {
        val dir = File(filesDir, "faces").apply { mkdirs() }
        val file = File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return file.absolutePath
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        embedder.close()
    }
}
