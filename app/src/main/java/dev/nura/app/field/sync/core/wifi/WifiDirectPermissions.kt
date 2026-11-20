package dev.nura.app.field.sync.core.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * Runtime permissions for Wi-Fi Direct discovery and connection:
 * - Android 13+: `NEARBY_WIFI_DEVICES` (declared with `neverForLocation`);
 * - Android 12 and lower: fine location, and location services must be switched on.
 */
object WifiDirectPermissions {

    val required: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    /** Optional: lets the transfer notification appear while a transfer runs. */
    val optional: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    fun hasRequired(context: Context): Boolean =
        required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Before Android 13, peer discovery silently returns nothing while location services are off. */
    fun needsLocationServices(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return !LocationManagerCompat.isLocationEnabled(manager)
    }
}
