package com.epm.camaraterreno

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class GestorGPS(private val contexto: Context) {

    private val clienteUbicacion: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(contexto)
    private lateinit var callbackUbicacion: LocationCallback

    var ubicacionActual: String = "Buscando..."
        private set
    var listo: Boolean = false
        private set

    var onUbicacionLista: (() -> Unit)? = null
    var onGPSDesactivado: (() -> Unit)? = null

    fun estaGPSActivado(): Boolean {
        val lm = contexto.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
               lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    fun iniciar() {
        if (!tienePermiso()) return

        if (!estaGPSActivado()) {
            onGPSDesactivado?.invoke()
            return
        }

        val solicitud = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(1500)
            .build()

        callbackUbicacion = object : LocationCallback() {
            override fun onLocationResult(resultado: LocationResult) {
                resultado.locations.firstOrNull()?.let { ubicacion ->
                    // 5 decimales ≈ 1.1 m de precisión, suficiente para trabajo
                    // de campo y mantiene la estampa más corta.
                    ubicacionActual = "%.5f, %.5f".format(ubicacion.latitude, ubicacion.longitude)
                    if (!listo) {
                        listo = true
                        onUbicacionLista?.invoke()
                    }
                }
            }
        }

        clienteUbicacion.requestLocationUpdates(solicitud, callbackUbicacion, Looper.getMainLooper())
    }

    fun reiniciar() {
        listo = false
        ubicacionActual = "Buscando..."
        iniciar()
    }

    fun detener() {
        if (::callbackUbicacion.isInitialized) {
            clienteUbicacion.removeLocationUpdates(callbackUbicacion)
        }
    }

    private fun tienePermiso() =
        ContextCompat.checkSelfPermission(
            contexto, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}
