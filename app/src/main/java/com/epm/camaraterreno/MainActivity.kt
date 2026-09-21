package com.epm.camaraterreno

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Chronometer
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // Vistas
    private lateinit var visorCamara: PreviewView
    private lateinit var capasFlash: View
    private lateinit var indicadorFoco: View
    private lateinit var indicadorGPS: TextView
    private lateinit var tvDeteccionIA: TextView
    private lateinit var btnFlash: TextView
    private lateinit var panelGrabacion: LinearLayout
    private lateinit var puntoRojoGrab: View
    private lateinit var cronometroGrabacion: Chronometer
    private lateinit var tvNivelZoom: TextView
    private lateinit var panelExposicion: LinearLayout
    private lateinit var tvValorExposicion: TextView
    private lateinit var btnModoFoto: TextView
    private lateinit var btnModoVideo: TextView
    private lateinit var btnCapturar: View
    private lateinit var anilloBoton: View
    private lateinit var btnGaleria: ImageButton
    private lateinit var progresoGuardado: ProgressBar
    private lateinit var btnGirarCamara: TextView
    private lateinit var bannerGPSDesactivado: LinearLayout

    // Gestores
    private lateinit var gestorCamara: GestorCamara
    private lateinit var gestorGPS: GestorGPS
    private lateinit var gestorIA: GestorIA
    private lateinit var gestorArchivos: GestorArchivos
    private lateinit var gestorMovimiento: GestorMovimiento

    // Estado
    private var isFlashOn = false
    private var modoVideo = false
    private var calidad = false
    private var exposicionMin = -3
    private var exposicionMax = 3
    private var exposicionActual = 0
    private var gestoBrilloActivo = false
    private var yInicioBrillo = 0f
    private var xTapFoco = 0f
    private var yTapFoco = 0f
    // Debounce: si una foto está en curso (captura + procesado en background),
    // ignoramos taps adicionales en el botón disparador para evitar acumular tareas.
    private var procesandoFoto = false

    private val handler = Handler(Looper.getMainLooper())
    private var runnableOcultarZoom: Runnable? = null
    private var runnableOcultarBrillo: Runnable? = null
    private var runnableOcultarFoco: Runnable? = null
    private var runnableParpadeo: Runnable? = null

    // Hilo dedicado al post-procesado de fotos (marca de agua + guardar a disco).
    // Lo hacemos fuera del main thread para que el shutter sea inmediato y para
    // evitar OOM en dispositivos con poca RAM (ej. Ulefone Armor X13).
    private val ejecutorFotos: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Bloquear orientación en vertical
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_main)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )

        enlazarVistas()
        inicializarGestores()

        if (tienePermisos()) {
            arrancar()
        } else {
            ActivityCompat.requestPermissions(this, PERMISOS_REQUERIDOS, COD_PERMISO)
        }

        configurarInteracciones()
    }

    override fun onResume() {
        super.onResume()
        gestorMovimiento.iniciar()
        // Re-verificar GPS cuando el usuario regresa (ej: de ajustes de ubicación)
        if (tienePermisos() && !gestorGPS.listo) {
            verificarEstadoGPS()
        }
    }

    override fun onPause() {
        super.onPause()
        gestorMovimiento.detener()
    }

    private fun enlazarVistas() {
        visorCamara = findViewById(R.id.visorCamara)
        capasFlash = findViewById(R.id.capasFlash)
        indicadorFoco = findViewById(R.id.indicadorFoco)
        indicadorGPS = findViewById(R.id.indicadorGPS)
        tvDeteccionIA = findViewById(R.id.tvDeteccionIA)
        btnFlash = findViewById(R.id.btnFlash)
        panelGrabacion = findViewById(R.id.panelGrabacion)
        puntoRojoGrab = findViewById(R.id.puntoRojoGrab)
        cronometroGrabacion = findViewById(R.id.cronometroGrabacion)
        tvNivelZoom = findViewById(R.id.tvNivelZoom)
        panelExposicion = findViewById(R.id.panelExposicion)
        tvValorExposicion = findViewById(R.id.tvValorExposicion)
        btnModoFoto = findViewById(R.id.btnModoFoto)
        btnModoVideo = findViewById(R.id.btnModoVideo)
        btnCapturar = findViewById(R.id.btnCapturar)
        anilloBoton = findViewById(R.id.anilloBoton)
        btnGaleria = findViewById(R.id.btnGaleria)
        progresoGuardado = findViewById(R.id.progresoGuardado)
        btnGirarCamara = findViewById(R.id.btnGirarCamara)
        bannerGPSDesactivado = findViewById(R.id.bannerGPSDesactivado)
    }

    private fun inicializarGestores() {
        gestorCamara = GestorCamara(this, this)
        gestorGPS = GestorGPS(this)
        gestorIA = GestorIA(this)
        gestorArchivos = GestorArchivos(this)
        gestorMovimiento = GestorMovimiento(this)

        // Cargar modelo IA pesados y limpiar caché en hilo de fondo para no bloquear la UI
        ejecutorFotos.execute {
            gestorIA.inicializar()
            limpiarCachePendiente()
        }

        // Cargar la miniatura inmediatamente en otro hilo ligero sin esperar a la IA
        Thread { cargarUltimaMiniatura() }.start()

        gestorCamara.onCamaraLista = { min, max, actual ->
            exposicionMin = min
            exposicionMax = max
            exposicionActual = actual
        }

        gestorCamara.onFrameAnalizado = { imagen, rotacionDispositivo ->
            val resultado = gestorIA.analizarFrame(imagen, rotacionDispositivo, isFlashOn)
            runOnUiThread { procesarResultadoAnalisis(resultado) }
        }

        gestorGPS.onUbicacionLista = {
            runOnUiThread {
                indicadorGPS.text = "⬤ GPS"
                indicadorGPS.setTextColor(Color.parseColor("#FF34C759"))
                bannerGPSDesactivado.visibility = View.GONE
                actualizarEstadoBoton()
            }
        }

        gestorGPS.onGPSDesactivado = {
            runOnUiThread { mostrarBannerGPSApagado() }
        }
    }

    private fun arrancar() {
        gestorCamara.iniciar(visorCamara)
        verificarEstadoGPS()
    }

    private fun verificarEstadoGPS() {
        if (gestorGPS.estaGPSActivado()) {
            bannerGPSDesactivado.visibility = View.GONE
            gestorGPS.iniciar()
        } else {
            mostrarBannerGPSApagado()
        }
    }

    private fun mostrarBannerGPSApagado() {
        indicadorGPS.text = "⬤ Sin GPS"
        indicadorGPS.setTextColor(Color.parseColor("#FFFF9500"))
        bannerGPSDesactivado.visibility = View.VISIBLE
        actualizarEstadoBoton()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun configurarInteracciones() {
        val escalaGestoZoom = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    gestorCamara.ajustarZoom(detector.scaleFactor)
                    mostrarNivelZoom()
                    return true
                }
            })

        visorCamara.setOnTouchListener { v, event ->
            escalaGestoZoom.onTouchEvent(event)
            val esLadoDerecho = event.x > v.width * 0.72f

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestoBrilloActivo = esLadoDerecho && !escalaGestoZoom.isInProgress
                    yInicioBrillo = event.y
                    xTapFoco = event.x
                    yTapFoco = event.y
                    if (gestoBrilloActivo) mostrarPanelExposicion()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (gestoBrilloActivo && event.pointerCount == 1 && !escalaGestoZoom.isInProgress) {
                        val delta = ((yInicioBrillo - event.y) / 55f).toInt()
                        val nuevoIndice = (exposicionActual + delta).coerceIn(exposicionMin, exposicionMax)
                        if (nuevoIndice != exposicionActual) {
                            exposicionActual = nuevoIndice
                            gestorCamara.ajustarExposicion(exposicionActual)
                            actualizarPanelExposicion()
                            yInicioBrillo = event.y
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!gestoBrilloActivo && !escalaGestoZoom.isInProgress) {
                        enfocarEnPunto(xTapFoco, yTapFoco)
                    }
                    gestoBrilloActivo = false
                    programarOcultarBrillo()
                }
            }
            v.performClick()
            true
        }

        // Flash (solo cámara trasera)
        btnFlash.setOnClickListener {
            if (gestorCamara.esCaraFrontal) {
                Toast.makeText(this, "Flash no disponible en cámara frontal", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isFlashOn = !isFlashOn
            gestorCamara.alternarAntorcha(isFlashOn)
            btnFlash.alpha = if (isFlashOn) 1f else 0.5f
        }

        // Resolución
        val btnResolucion = findViewById<TextView>(R.id.btnResolucion)
        btnResolucion.text = "1080p" // Default
        btnResolucion.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(this, view)
            popup.menu.add(0, 0, 0, "Nativa (Pantalla Completa)")
            popup.menu.add(0, 1, 1, "4K - 8.3 MP (3840x2160)")
            popup.menu.add(0, 2, 2, "1440p - 3.7 MP (2560x1440)")
            popup.menu.add(0, 3, 3, "1080p - 2.1 MP (1920x1080)")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> { // Nativa
                        gestorArchivos.resolucionLadoMayor = 4000
                        gestorCamara.actualizarPreview()
                        btnResolucion.text = "MAX"
                    }
                    1 -> { // 4K
                        gestorArchivos.resolucionLadoMayor = 3840
                        gestorCamara.actualizarPreview()
                        btnResolucion.text = "4K"
                    }
                    2 -> { // 1440p
                        gestorArchivos.resolucionLadoMayor = 2560
                        gestorCamara.actualizarPreview()
                        btnResolucion.text = "1440p"
                    }
                    3 -> { // 1080p
                        gestorArchivos.resolucionLadoMayor = 1920
                        gestorCamara.actualizarPreview()
                        btnResolucion.text = "1080p"
                    }
                }
                true
            }
            popup.show()
        }

        // Selector modo
        btnModoFoto.setOnClickListener { cambiarModo(false) }
        btnModoVideo.setOnClickListener { cambiarModo(true) }

        // Botón central
        btnCapturar.setOnClickListener {
            if (modoVideo) alternarGrabacion() else tomarFoto()
        }

        // Galería
        btnGaleria.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        // Girar cámara (frontal ↔ trasera)
        btnGirarCamara.setOnClickListener {
            // Apagar flash si estaba activo al cambiar a frontal
            if (!gestorCamara.esCaraFrontal && isFlashOn) {
                isFlashOn = false
                gestorCamara.alternarAntorcha(false)
                btnFlash.alpha = 0.5f
            }
            gestorCamara.girarCamara()
            // Icono flip animado
            btnGirarCamara.animate().rotationBy(180f).setDuration(300).start()
        }

        // Banner GPS → abre ajustes de ubicación
        bannerGPSDesactivado.setOnClickListener {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        // El indicador GPS también abre ajustes cuando GPS está apagado
        indicadorGPS.setOnClickListener {
            if (!gestorGPS.estaGPSActivado()) {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }
    }

    private fun cambiarModo(esVideo: Boolean) {
        if (modoVideo == esVideo) return
        modoVideo = esVideo
        gestorCamara.cambiarModo(esVideo)

        if (esVideo) {
            btnModoVideo.setTextColor(Color.WHITE)
            btnModoVideo.setBackgroundResource(R.drawable.fondo_modo_activo)
            btnModoFoto.setTextColor(Color.parseColor("#88FFFFFF"))
            btnModoFoto.background = null
            btnCapturar.setBackgroundResource(R.drawable.fondo_boton_grabar)
            btnCapturar.isEnabled = true
            btnCapturar.alpha = 1f
            anilloBoton.visibility = View.INVISIBLE
        } else {
            btnModoFoto.setTextColor(Color.WHITE)
            btnModoFoto.setBackgroundResource(R.drawable.fondo_modo_activo)
            btnModoVideo.setTextColor(Color.parseColor("#88FFFFFF"))
            btnModoVideo.background = null
            btnCapturar.setBackgroundResource(R.drawable.fondo_boton_captura)
            anilloBoton.visibility = View.VISIBLE
            actualizarEstadoBoton()
        }
    }

    private fun tomarFoto() {
        // Debounce: ignoramos taps mientras hay una foto en proceso.
        if (procesandoFoto) return

        // Guardia anti-shake: acelerómetro detecta movimiento en este instante
        if (gestorMovimiento.hayMovimiento) {
            Toast.makeText(this, "Mantén firme el dispositivo", Toast.LENGTH_SHORT).show()
            sacudirBoton()
            return
        }

        capasFlash.alpha = 1f
        capasFlash.animate().alpha(0f).setDuration(200).start()

        // Activamos el spinner y bloqueamos el botón hasta que termine el procesado.
        procesandoFoto = true
        progresoGuardado.visibility = View.VISIBLE
        actualizarEstadoBoton()

        // Snapshot del contexto IA/GPS al instante del disparo (puede cambiar antes
        // de que termine el post-procesado en background).
        val ubicacionSnap = gestorGPS.ubicacionActual
        val deteccionSnap = gestorIA.ultimaDeteccion

        val opcFoto = gestorArchivos.crearOpcionesCapturaTemporal()

        gestorCamara.tomarFotoAArchivo(opcFoto.opciones, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(resultados: ImageCapture.OutputFileResults) {
                // Procesado y publicación en MediaStore en hilo de fondo: la foto
                // recién aparece en la galería cuando ya tiene marca de agua.
                ejecutorFotos.execute {
                    val thumb = gestorArchivos.procesarYPublicar(
                        opcFoto.archivoTemporal, opcFoto.nombreBase,
                        ubicacionSnap, deteccionSnap
                    )
                    runOnUiThread {
                        if (thumb != null) btnGaleria.setImageBitmap(thumb)
                        Toast.makeText(this@MainActivity, "Foto guardada ✓", Toast.LENGTH_SHORT).show()
                        terminarProcesoFoto()
                    }
                }
            }

            override fun onError(exc: ImageCaptureException) {
                opcFoto.archivoTemporal.delete()
                Toast.makeText(this@MainActivity, "Error al capturar", Toast.LENGTH_SHORT).show()
                terminarProcesoFoto()
            }
        })
    }

    private fun terminarProcesoFoto() {
        procesandoFoto = false
        progresoGuardado.visibility = View.GONE
        actualizarEstadoBoton()
    }

    /**
     * Borra JPEGs temporales que pudieron quedar en cacheDir si la app crasheó
     * o fue matada antes de terminar de procesar una foto anterior. Sin esto,
     * el cacheDir va acumulando archivos huérfanos hasta que Android lo limpie.
     */
    private fun limpiarCachePendiente() {
        cacheDir.listFiles { f -> f.isFile && f.name.startsWith("captura_") && f.name.endsWith(".jpg") }
            ?.forEach { runCatching { it.delete() } }
    }

    private fun cargarUltimaMiniatura() {
        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL)
            else
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            val proyeccion = arrayOf(android.provider.MediaStore.Images.Media._ID)
            val sort = "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"
            
            contentResolver.query(
                uri, proyeccion,
                "${android.provider.MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("%TerrenoApp_Fotos%"),
                sort
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    val uriFoto = android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                    )
                    
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentResolver.loadThumbnail(uriFoto, android.util.Size(160, 160), null)
                    } else {
                        android.provider.MediaStore.Images.Thumbnails.getThumbnail(
                            contentResolver, id,
                            android.provider.MediaStore.Images.Thumbnails.MICRO_KIND, null
                        )
                    }
                    if (bitmap != null) {
                        runOnUiThread { btnGaleria.setImageBitmap(bitmap) }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error cargando miniatura", e)
        }
    }

    // Animación de sacudida cuando bloquea captura por movimiento
    private fun sacudirBoton() {
        btnCapturar.animate().translationX(-12f).setDuration(60).withEndAction {
            btnCapturar.animate().translationX(12f).setDuration(60).withEndAction {
                btnCapturar.animate().translationX(-8f).setDuration(50).withEndAction {
                    btnCapturar.animate().translationX(0f).setDuration(50).start()
                }.start()
            }.start()
        }.start()
    }

    private fun alternarGrabacion() {
        if (gestorCamara.estaGrabando) {
            gestorCamara.detenerGrabacion()
            detenerUIGrabacion()
        } else {
            val opciones = gestorArchivos.crearOpcionesVideoMediaStore()
            val iniciado = gestorCamara.iniciarGrabacion(opciones) { evento ->
                when (evento) {
                    is VideoRecordEvent.Start -> runOnUiThread { iniciarUIGrabacion() }
                    is VideoRecordEvent.Finalize -> runOnUiThread {
                        detenerUIGrabacion()
                        val msg = if (evento.hasError()) "Error al grabar" else "Video guardado ✓"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                    else -> {}
                }
            }
            if (!iniciado) Toast.makeText(this, "No se pudo iniciar grabación", Toast.LENGTH_SHORT).show()
        }
    }

    private fun iniciarUIGrabacion() {
        panelGrabacion.visibility = View.VISIBLE
        cronometroGrabacion.base = android.os.SystemClock.elapsedRealtime()
        cronometroGrabacion.start()
        btnCapturar.setBackgroundResource(R.drawable.fondo_boton_grabando)
        parpadeoPuntoRojo()
    }

    private fun detenerUIGrabacion() {
        panelGrabacion.visibility = View.GONE
        cronometroGrabacion.stop()
        btnCapturar.setBackgroundResource(R.drawable.fondo_boton_grabar)
        runnableParpadeo?.let { handler.removeCallbacks(it) }
        puntoRojoGrab.alpha = 1f
    }

    private fun parpadeoPuntoRojo() {
        runnableParpadeo = object : Runnable {
            override fun run() {
                if (!gestorCamara.estaGrabando) return
                puntoRojoGrab.animate().alpha(0f).setDuration(500).withEndAction {
                    puntoRojoGrab.animate().alpha(1f).setDuration(500).withEndAction {
                        handler.post(this)
                    }.start()
                }.start()
            }
        }
        handler.post(runnableParpadeo!!)
    }

    // ===== FOCO =====

    private fun enfocarEnPunto(x: Float, y: Float) {
        gestorCamara.enfocarEnPunto(x, y, visorCamara.meteringPointFactory)
        mostrarIndicadorFoco(x, y)
    }

    private fun mostrarIndicadorFoco(x: Float, y: Float) {
        val halfW = indicadorFoco.width / 2f
        val halfH = indicadorFoco.height / 2f
        indicadorFoco.translationX = x - halfW
        indicadorFoco.translationY = y - halfH
        indicadorFoco.alpha = 1f
        indicadorFoco.scaleX = 1.4f
        indicadorFoco.scaleY = 1.4f
        indicadorFoco.visibility = View.VISIBLE
        indicadorFoco.animate().scaleX(1f).scaleY(1f).setDuration(200).start()

        runnableOcultarFoco?.let { handler.removeCallbacks(it) }
        runnableOcultarFoco = Runnable {
            indicadorFoco.animate().alpha(0f).setDuration(300)
                .withEndAction { indicadorFoco.visibility = View.GONE }.start()
        }
        handler.postDelayed(runnableOcultarFoco!!, 2000)
    }

    // ===== ZOOM =====

    private fun mostrarNivelZoom() {
        val nivel = gestorCamara.obtenerNivelZoom()
        tvNivelZoom.text = "%.1f×".format(nivel)
        tvNivelZoom.visibility = View.VISIBLE
        tvNivelZoom.alpha = 1f

        runnableOcultarZoom?.let { handler.removeCallbacks(it) }
        runnableOcultarZoom = Runnable {
            tvNivelZoom.animate().alpha(0f).setDuration(400)
                .withEndAction { tvNivelZoom.visibility = View.GONE }.start()
        }
        handler.postDelayed(runnableOcultarZoom!!, 1500)
    }

    // ===== BRILLO =====

    private fun mostrarPanelExposicion() {
        runnableOcultarBrillo?.let { handler.removeCallbacks(it) }
        panelExposicion.visibility = View.VISIBLE
        panelExposicion.alpha = 1f
        actualizarPanelExposicion()
    }

    private fun actualizarPanelExposicion() {
        val signo = if (exposicionActual > 0) "+" else ""
        tvValorExposicion.text = "$signo$exposicionActual"
    }

    private fun programarOcultarBrillo() {
        runnableOcultarBrillo?.let { handler.removeCallbacks(it) }
        runnableOcultarBrillo = Runnable {
            panelExposicion.animate().alpha(0f).setDuration(400)
                .withEndAction { panelExposicion.visibility = View.GONE }.start()
        }
        handler.postDelayed(runnableOcultarBrillo!!, 2000)
    }

    // ===== ANÁLISIS IA =====

    private fun procesarResultadoAnalisis(resultado: GestorIA.ResultadoAnalisis) {
        val (texto, color, esOk) = when (resultado.estado) {
            GestorIA.EstadoCalidad.TAPADA        -> Triple("Cámara tapada", "#FFFF3B30", false)
            GestorIA.EstadoCalidad.TODO_NEGRO    -> Triple("Muy oscuro", "#FFFF3B30", false)
            GestorIA.EstadoCalidad.TODO_BLANCO   -> Triple("Sobreexpuesto", "#FFFF3B30", false)
            GestorIA.EstadoCalidad.BORROSA       -> Triple("Borroso / desenfocado", "#FFFF9500", false)
            GestorIA.EstadoCalidad.EN_MOVIMIENTO -> Triple("En movimiento", "#FFFF9500", false)
            GestorIA.EstadoCalidad.OK            -> Triple(resultado.deteccion, "#FFFFFFFF", true)
        }
        tvDeteccionIA.text = texto
        tvDeteccionIA.setTextColor(Color.parseColor(color))
        calidad = esOk
        actualizarEstadoBoton()
    }

    private fun actualizarEstadoBoton() {
        if (modoVideo) return
        val puedeCapturar = calidad && gestorGPS.listo && !procesandoFoto
        btnCapturar.isEnabled = puedeCapturar
        btnCapturar.alpha = if (puedeCapturar) 1f else 0.45f
    }

    // ===== PERMISOS =====

    private fun tienePermisos() = PERMISOS_REQUERIDOS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == COD_PERMISO) {
            if (tienePermisos()) arrancar()
            else {
                Toast.makeText(this, "Se requieren todos los permisos.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gestorCamara.liberar()
        gestorIA.liberar()
        gestorGPS.detener()
        handler.removeCallbacksAndMessages(null)
        ejecutorFotos.shutdown()
    }

    companion object {
        private const val COD_PERMISO = 10
        private val PERMISOS_REQUERIDOS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}
