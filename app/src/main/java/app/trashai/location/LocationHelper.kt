package app.trashai.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Thin wrapper around FusedLocationProviderClient + Geocoder.
 * Reverse-geocoded result is the Korean "시/구" pair we display in the header.
 */
object LocationHelper {

    sealed interface Result {
        data class Ok(val sido: String, val locality: String, val subLocality: String) : Result {
            val display: String get() {
                val parts = listOf(sido, locality, subLocality).filter { it.isNotBlank() }.distinct()
                return parts.joinToString(" ")
            }
        }
        data object PermissionDenied : Result
        data object NoLocation : Result
        data class Error(val message: String) : Result
    }

    @SuppressLint("MissingPermission")
    suspend fun fetchCurrentRegion(context: Context): Result = withContext(Dispatchers.IO) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return@withContext Result.PermissionDenied

        val client = LocationServices.getFusedLocationProviderClient(context)
        val location: Location? = try {
            // Prefer fresh: getCurrentLocation requests a new fix; falls back to last cached if needed.
            suspendCancellableCoroutine { cont ->
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc) }
                    .addOnFailureListener { e ->
                        Log.w("LocationHelper", "getCurrentLocation failed: ${e.message}")
                        if (cont.isActive) cont.resume(null)
                    }
            } ?: suspendCancellableCoroutine { cont ->
                client.lastLocation
                    .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }
        } catch (t: Throwable) {
            return@withContext Result.Error(t.message ?: t::class.java.simpleName)
        }

        location ?: return@withContext Result.NoLocation

        val geocoder = Geocoder(context, Locale.KOREAN)
        try {
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(location.latitude, location.longitude, 1) { results ->
                        if (cont.isActive) cont.resume(results)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(location.latitude, location.longitude, 1)
            }
            val first = addresses?.firstOrNull() ?: return@withContext Result.Error("주소 변환 실패")
            // Korean admin levels: adminArea = 시/도, locality/subAdminArea = 시/군, subLocality = 구/동
            val sido = first.adminArea ?: ""
            val locality = first.locality ?: first.subAdminArea ?: ""
            val subLocality = first.subLocality ?: ""
            return@withContext Result.Ok(sido, locality, subLocality)
        } catch (t: Throwable) {
            Log.w("LocationHelper", "geocoder failed: ${t.message}")
            return@withContext Result.Error(t.message ?: t::class.java.simpleName)
        }
    }
}
