package com.example.vinscanner

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.vinscanner.databinding.ActivityVehicleInfoBinding

class VehicleInfoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INFO = "extra_vehicle_info"
    }

    private lateinit var binding: ActivityVehicleInfoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVehicleInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val vehicleInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_INFO, VehicleInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_INFO)
        }

        if (vehicleInfo != null) {
            renderVehicleInfo(vehicleInfo)
        } else {
            binding.vehicleValues.text = getString(R.string.vehicle_info_empty)
        }

        binding.btnClose.setOnClickListener { finish() }
    }

    private fun renderVehicleInfo(info: VehicleInfo) {
        val lines = listOf(
            R.string.vehicle_label_vin to info.vin,
            R.string.vehicle_label_make to info.make,
            R.string.vehicle_label_model to info.model,
            R.string.vehicle_label_year to info.modelYear,
            R.string.vehicle_label_engine to info.engine,
            R.string.vehicle_label_drive to info.driveType,
            R.string.vehicle_label_transmission to info.transmission,
            R.string.vehicle_label_body to info.bodyClass,
            R.string.vehicle_label_fuel to info.fuelType,
        )

        val builder = StringBuilder()
        lines.forEach { (labelRes, value) ->
            builder.appendLine(getString(R.string.vehicle_field_line, getString(labelRes), value))
        }
        binding.vehicleValues.text = builder.toString()
    }
}
