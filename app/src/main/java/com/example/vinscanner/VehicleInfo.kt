package com.example.vinscanner

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Simple container for decoded VIN fields we care about.
 */
@Parcelize
data class VehicleInfo(
    val vin: String,
    val make: String,
    val model: String,
    val modelYear: String,
    val engine: String,
    val driveType: String,
    val transmission: String,
    val bodyClass: String,
    val fuelType: String,
    val trim: String? = null,
    val plant: String? = null,
    val notes: String? = null
) : Parcelable
