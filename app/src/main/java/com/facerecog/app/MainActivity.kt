package com.facerecog.app

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var embedder: FaceEmbedder
    private lateinit var matcher: FaceMatcher
    private val repo = SupabaseRepository.getInstance()

    private var lastUnknownBitmap: Bitmap? = null
    private var isProcessing = false
    private var currentCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    private var cameraProvider: ProcessCameraProvider? = null

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

        embedder = FaceEmbedder(this)
        matcher = FaceMatcher(repo)
        cameraExecutor = Executors.newSingleThreadExecutor()

        lifecycleScope.launch {
            matcher.refreshCache()
        }

        binding.btnAdmin.setOnClickListener {
            AdminGate.promptForCode(this) {
                startActivity(Intent(this, AdminActivity::class.java))
            }
        }

        binding.btnSwitchCamera.setOnClickListener {
            currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            binding.overlay.setFaces(emptyList())
            binding.tvStatus.text = "Scanning…"
            hideAssignButton()
            bindCameraUseCases()
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
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        val analyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy -> analyzeFrame(imageProxy) }
            }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, currentCameraSelector, preview, analyzer)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera bind failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
                hideAssignButton()
                binding.tvStatus.text = "Scanning…"
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
            val knownCount = overlays.count { it.isKnown }
            binding.tvStatus.text = when {
                foundUnknown != null && knownCount > 0 -> "$knownCount recognized · unknown face detected"
                foundUnknown != null -> "Unknown face detected"
                knownCount == 1 -> overlays.first { it.isKnown }.label
                knownCount > 1 -> "$knownCount people recognized"
                else -> "Scanning…"
            }
            if (foundUnknown != null) showAssignButton() else hideAssignButton()
        }

        imageProxy.close()
        isProcessing = false
    }

    private fun showAssignButton() {
        val btn = binding.btnAssignUnknown
        if (btn.visibility == View.VISIBLE && btn.alpha > 0.9f) return
        btn.visibility = View.VISIBLE
        btn.alpha = 0f
        btn.scaleX = 0.85f
        btn.scaleY = 0.85f
        btn.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
    }

    private fun hideAssignButton() {
        val btn = binding.btnAssignUnknown
        if (btn.visibility != View.VISIBLE) return
        btn.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(180)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    btn.visibility = View.GONE
                }
            }).start()
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
            val persons = repo.getAllPersons()
            val names = persons.map { it.name }.toMutableList()
            names.add(0, "+ New person")

            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Who is this?")
                .setItems(names.toTypedArray()) { _, which ->
                    if (which == 0) {
                        promptNewPersonName(bitmap)
                    } else {
                        val chosen = persons[which - 1]
                        saveFaceForExistingPerson(chosen.id, bitmap)
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
                        try {
                            val embedding = embedder.getEmbedding(bitmap)
                            repo.createPersonWithFace(name, bitmap, embedding)
                            matcher.refreshCache()
                            Snackbar.make(binding.root, "Saved ✓", Snackbar.LENGTH_SHORT).show()
                            hideAssignButton()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveFaceForExistingPerson(personId: String, bitmap: Bitmap) {
        lifecycleScope.launch {
            try {
                val embedding = embedder.getEmbedding(bitmap)
                repo.addFaceToPerson(personId, bitmap, embedding)
                matcher.refreshCache()
                Snackbar.make(binding.root, "Saved ✓", Snackbar.LENGTH_SHORT).show()
                hideAssignButton()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        embedder.close()
    }
}
