package com.example.vinscanner

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.vinscanner.databinding.ActivityMainBinding
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@androidx.camera.core.ExperimentalGetImage
class MainActivity : AppCompatActivity() {

    companion object {
        private const val STATE_VIN = "state_vin"
        private const val STATE_VIN_LOCKED = "state_vin_locked"
        private const val STATE_VEHICLE_INFO = "state_vehicle_info"
    }

    private lateinit var binding: ActivityMainBinding
    private val CAMERA_REQ = 100
    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
    private var cameraControl: CameraControl? = null
    private var latestVehicleInfo: VehicleInfo? = null
    private var decodeJob: Job? = null

    @Volatile
    private var vinLocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Restore saved VIN if activity recreated (rotation etc.)
        savedInstanceState?.let { bundle ->
            val savedVin = bundle.getString(STATE_VIN)
            val savedLocked = bundle.getBoolean(STATE_VIN_LOCKED, false)
            if (!savedVin.isNullOrEmpty()) {
                vinLocked = savedLocked
                binding.vinText.text = savedVin
                binding.btnCopy.isEnabled = isValidVin(savedVin)
                binding.btnPts.isEnabled = isValidVin(savedVin)
            }

            val restoredVehicleInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bundle.getParcelable(STATE_VEHICLE_INFO, VehicleInfo::class.java)
            } else {
                @Suppress("DEPRECATION")
                bundle.getParcelable(STATE_VEHICLE_INFO)
            }

            restoredVehicleInfo?.let { info ->
                latestVehicleInfo = info
                binding.btnVehicleInfo.visibility = View.VISIBLE
            }
        }

        // Start disabled until we have a valid VIN
        if (!::binding.isInitialized) return
        binding.btnCopy.isEnabled = binding.vinText.text?.length == 17 && isValidVin(binding.vinText.text.toString())
        // If already enabled above from saved state this will keep it enabled

        binding.btnCopy.setOnClickListener {
            val vin = binding.vinText.text.toString()
            if (isValidVin(vin)) {
                copyToClipboard(vin, showToast = true)
            }
        }

        binding.btnPts.setOnClickListener {
            val vin = binding.vinText.text.toString()
            if (!isValidVin(vin)) return@setOnClickListener
            val intent = Intent(this, PtsWebActivity::class.java)
            intent.putExtra(AppConstants.EXTRA_VIN, vin)
            startActivity(intent)
        }

        binding.btnVehicleInfo.setOnClickListener {
            latestVehicleInfo?.let { openVehicleInfoScreen(it) }
        }

        binding.btnScanAgain.setOnClickListener {
            resetForNewScan()
        }

        setupTapToFocus()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_REQ
            )
        } else {
            startCamera()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_VIN, binding.vinText.text?.toString())
        outState.putBoolean(STATE_VIN_LOCKED, vinLocked)
        outState.putParcelable(STATE_VEHICLE_INFO, latestVehicleInfo)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQ && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else if (requestCode == CAMERA_REQ) {
            Toast.makeText(this, "Camera permission is required to scan VIN barcodes.", Toast.LENGTH_LONG).show()
        }
    }

    private fun resetForNewScan() {
        vinLocked = false
        binding.vinText.text = getString(R.string.vin_placeholder)
        binding.btnCopy.isEnabled = false
        binding.btnPts.isEnabled = false
        binding.btnVehicleInfo.visibility = View.GONE
        binding.decodeStatus.text = getString(R.string.decode_status_idle)
        binding.decodeProgress.visibility = View.GONE
        binding.tapFocusHint.visibility = View.VISIBLE
        latestVehicleInfo = null
        decodeJob?.cancel()
    }

    private fun isValidVin(vin: String): Boolean {
        // 17 chars, A-H J-N P R-Z 0-9 (excludes I,O,Q)
        val vinRegex = Regex("^[A-HJ-NPR-Z0-9]{17}$")
        return vinRegex.matches(vin)
    }

    private fun copyToClipboard(vin: String, showToast: Boolean) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("VIN", vin))

        // Android 13+ shows a system UI toast/snackbar automatically; we still show ours for consistency,
        // but keep it short.
        if (showToast) {
            Toast.makeText(this, "VIN copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            // Only scan Code 39 to reduce false positives and speed up detection
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_CODE_39)
                .build()

            val scanner = BarcodeScanning.getClient(options)

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analyzer.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                if (vinLocked) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val image = InputImage.fromMediaImage(
                        mediaImage,
                        imageProxy.imageInfo.rotationDegrees
                    )

                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            if (vinLocked) return@addOnSuccessListener
                            if (barcodes.isEmpty()) return@addOnSuccessListener

                            val vin = barcodes[0].rawValue ?: return@addOnSuccessListener
                            if (!isValidVin(vin)) return@addOnSuccessListener

                            // Freeze scanning after detection
                            vinLocked = true

                            // Update UI
                            binding.vinText.text = vin
                            binding.btnCopy.isEnabled = true
                            binding.btnPts.isEnabled = true

                            // Auto-copy VIN
                            copyToClipboard(vin, showToast = (Build.VERSION.SDK_INT < 33))

                            // Beep on success
                            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)

                            beginDecodeFlow(vin)
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    imageProxy.close()
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analyzer
                ).let { camera ->
                    cameraControl = camera.cameraControl
                }
            } catch (exc: Exception) {
                Toast.makeText(this, "Camera start failed: ${exc.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupTapToFocus() {
        binding.previewView.setOnTouchListener { view, event ->
            if (event.action != MotionEvent.ACTION_DOWN) return@setOnTouchListener false
            val control = cameraControl ?: return@setOnTouchListener false
            val point = binding.previewView.meteringPointFactory.createPoint(event.x, event.y)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()

            view.performClick()
            showFocusRing(event.x, event.y)
            control.startFocusAndMetering(action)
            true
        }
        binding.previewView.setOnClickListener { }
    }

    override fun onResume() {
        super.onResume()
        binding.previewView.setOnClickListener { }
    }

    private fun showFocusRing(x: Float, y: Float) {
        val ring = binding.focusRing
        ring.translationX = x - ring.width / 2f
        ring.translationY = y - ring.height / 2f
        ring.visibility = View.VISIBLE

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            addUpdateListener { anim ->
                val scale = 1f + anim.animatedFraction * 0.3f
                ring.scaleX = scale
                ring.scaleY = scale
                ring.alpha = 1f - anim.animatedFraction
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    ring.visibility = View.GONE
                    ring.scaleX = 1f
                    ring.scaleY = 1f
                    ring.alpha = 1f
                }
            })
            start()
        }

        binding.tapFocusHint.visibility = View.GONE
    }

    private fun beginDecodeFlow(vin: String) {
        decodeJob?.cancel()
        binding.decodeStatus.text = getString(R.string.decode_status_loading)
        binding.decodeProgress.visibility = View.VISIBLE
        binding.btnVehicleInfo.visibility = View.GONE
        latestVehicleInfo = null

        decodeJob = lifecycleScope.launch {
            val result = VinDecoder.decode(vin)
            result.onSuccess { info ->
                latestVehicleInfo = info
                binding.decodeStatus.text = getString(R.string.decode_status_success)
                binding.decodeProgress.visibility = View.GONE
                binding.btnVehicleInfo.visibility = View.VISIBLE
                openVehicleInfoScreen(info)
            }.onFailure {
                binding.decodeStatus.text = getString(R.string.decode_status_error)
                binding.decodeProgress.visibility = View.GONE
                binding.btnVehicleInfo.visibility = View.GONE
            }
        }
    }

    private fun openVehicleInfoScreen(info: VehicleInfo) {
        val intent = Intent(this, VehicleInfoActivity::class.java)
        intent.putExtra(VehicleInfoActivity.EXTRA_INFO, info)
        startActivity(intent)
    }
}
