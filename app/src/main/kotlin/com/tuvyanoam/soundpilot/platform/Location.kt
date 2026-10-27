package com.tuvyanoam.soundpilot.platform

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.tuvyanoam.soundpilot.core.platform.GeoPoint
import com.tuvyanoam.soundpilot.core.storage.PlaceRepository
import com.tuvyanoam.soundpilot.service.GeofenceReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

data class AddressResult(val label: String, val latitude: Double, val longitude: Double)

class LocationHelper(private val context: Context) {
    private val fused = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): GeoPoint? {
        if (!Permissions.hasFineLocation(context)) return null
        return try {
            val token = CancellationTokenSource()
            val location: Location? = withTimeoutOrNull(20_000) {
                fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token).await()
            } ?: fused.lastLocation.await()
            location?.let { GeoPoint(it.latitude, it.longitude, it.accuracy) }
        } catch (e: Exception) {
            null
        }
    }

    /** Looks up an address or place name with the platform geocoder (no API key needed). */
    suspend fun search(query: String): List<AddressResult> {
        if (!Geocoder.isPresent() || query.isBlank()) return emptyList()
        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine<List<Address>> { cont ->
                    geocoder.getFromLocationName(query, 5, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) = cont.resume(addresses.toList())
                        override fun onError(errorMessage: String?) = cont.resume(emptyList())
                    })
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(query, 5).orEmpty()
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
        return addresses.map { a ->
            val label = (0..a.maxAddressLineIndex).mapNotNull { a.getAddressLine(it) }.joinToString(", ").ifBlank { query }
            AddressResult(label, a.latitude, a.longitude)
        }
    }

    companion object {
        fun distanceMeters(a: GeoPoint, latitude: Double, longitude: Double): Float {
            val out = FloatArray(1)
            Location.distanceBetween(a.latitude, a.longitude, latitude, longitude, out)
            return out[0]
        }
    }
}

/** Registers one geofence per saved place; transitions arrive in [GeofenceReceiver]. */
class GeofenceManager(
    private val context: Context,
    private val places: PlaceRepository,
    private val presence: PresenceStore,
    private val location: LocationHelper,
) {
    private val client = LocationServices.getGeofencingClient(context)

    /** Whether geofences were registered successfully in this process. */
    @Volatile
    var synced = false
        private set

    private val pendingIntent: PendingIntent by lazy {
        // Mutable: Play services adds the transition details to the intent.
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, GeofenceReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /** Re-registers all geofences. Returns false when location permission (all the time) is missing. */
    @SuppressLint("MissingPermission")
    suspend fun sync(): Boolean {
        val all = places.all()
        presence.set(presence.current().filter { id -> all.any { it.id == id } }.toSet())
        if (!Permissions.hasBackgroundLocation(context)) return false
        try {
            client.removeGeofences(pendingIntent).await()
        } catch (e: Exception) {
            // Nothing registered yet.
        }
        if (all.isEmpty()) {
            synced = true
            return true
        }
        val geofences = all.take(MAX_GEOFENCES).map { place ->
            Geofence.Builder()
                .setRequestId(place.id)
                .setCircularRegion(place.latitude, place.longitude, place.radiusMeters.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .setNotificationResponsiveness(RESPONSIVENESS_MS)
                .build()
        }
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()
        return try {
            client.addGeofences(request, pendingIntent).await()
            synced = true
            refreshPresence()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Recomputes which places the phone is in from a fresh location fix. Geofence events can be missed (reboot,
     * location turned off), so this runs whenever the app is opened.
     */
    suspend fun refreshPresence(): Boolean {
        val here = location.currentLocation() ?: return false
        val inside = places.all().filter { place ->
            val slack = (here.accuracyMeters ?: 0f).coerceAtMost(100f) / 2
            LocationHelper.distanceMeters(here, place.latitude, place.longitude) <= place.radiusMeters + slack
        }.map { it.id }.toSet()
        val changed = inside != presence.current()
        presence.set(inside)
        return changed
    }

    companion object {
        private const val MAX_GEOFENCES = 100
        private const val RESPONSIVENESS_MS = 60_000
    }
}
