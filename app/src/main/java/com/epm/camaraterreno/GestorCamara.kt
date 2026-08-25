package com.epm.camaraterreno

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GestorCamara(
    private val contexto: Context,
    private val cicloVida: LifecycleOwner
) {

    private var visor: PreviewView? = null
    private var capturaFoto: ImageCapture? = null
    private var analisisImagen: ImageAnalysis? = null
    private var capturaVideo: VideoCapture<Recorder>? = null
    private var grabacionActual: Recording? = null
    private var proveedorCamara: ProcessCameraProvider? = null
    private var controlCamara: CameraControl? = null
    private var infoCamara: CameraInfo? = null
    private val ejecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Para foto usamos setTargetAspectRatio(RATIO_4_3): CameraX entrega la
    // resolución NATIVA del sensor a 4:3, garantizando 0 recorte (full FOV).
    // El downsample a ~1600 px lo hacemos en GestorArchivos durante la marca
    // de agua, para evitar OOM y mantener el archivo final liviano.
    // Análisis IA: 640×480 (4:3) — liviano y matchea el aspect ratio de la foto.
    private val resolucionAnalisis = Size(640, 480)

    private var modoVideo = false
    private var esFrontal = false

    var onFrameAnalizado: ((ImageProxy) -> Unit)? = null
    var onCamaraLista: ((Int, Int, Int) -> Unit)? = null // min, max, indiceActual exposición

    var estaGrabando = false
        private set

    val esCaraFrontal: Boolean get() = esFrontal

    fun iniciar(visorCamara: PreviewView) {
        this.visor = visorCamara
        ProcessCameraProvider.getInstance(contexto).also { futuro ->
            futuro.addListener({
                proveedorCamara = futuro.get()
                vincularUsoCasos()
            }, ContextCompat.getMainExecutor(contexto))
        }
    }

    fun cambiarModo(esVideo: Boolean) {
        if (modoVideo == esVideo) return
        modoVideo = esVideo
        vincularUsoCasos()
    }

    fun girarCamara() {
        esFrontal = !esFrontal
        vincularUsoCasos()
    }

    private fun selectorActual() = if (esFrontal)
        CameraSelector.DEFAULT_FRONT_CAMERA
    else
        CameraSelector.DEFAULT_BACK_CAMERA

    fun actualizarPreview() {
        vincularUsoCasos()
    }

    private fun vincularUsoCasos() {
        val proveedor = proveedorCamara ?: return
        val visorCamara = visor ?: return

        // Rotación del display: con esto CameraX entrega el JPEG ya orientado
        @Suppress("DEPRECATION")
        val rotacion = visorCamara.display?.rotation ?: 0

        val preview = Preview.Builder()
            .setTargetRotation(rotacion)
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build().also {
                it.setSurfaceProvider(visorCamara.surfaceProvider)
            }

        try {
            proveedor.unbindAll()

            val camara = if (modoVideo) {
                val grabador = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.HIGHEST,
                            FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                        )
                    ).build()
                capturaVideo = VideoCapture.withOutput(grabador)
                proveedor.bindToLifecycle(
                    cicloVida, selectorActual(),
                    preview, capturaVideo!!
                )
            } else {
                @Suppress("DEPRECATION")
                capturaFoto = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(rotacion)
                    .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                    .build()
                @Suppress("DEPRECATION")
                analisisImagen = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetResolution(resolucionAnalisis)
                    .setTargetRotation(rotacion)
                    .build().also {
                        it.setAnalyzer(ejecutor) { imagen -> onFrameAnalizado?.invoke(imagen) }
                    }
                proveedor.bindToLifecycle(
                    cicloVida, selectorActual(),
                    preview, capturaFoto!!, analisisImagen!!
                )
            }

            controlCamara = camara.cameraControl
            infoCamara = camara.cameraInfo
            notificarRangoBrillo()

        } catch (e: Exception) {
            Log.e("GestorCamara", "Error al vincular cámara", e)
        }
    }

    private fun notificarRangoBrillo() {
        val estado = infoCamara?.exposureState ?: return
        if (estado.isExposureCompensationSupported) {
            val rango = estado.exposureCompensationRange
            onCamaraLista?.invoke(rango.lower, rango.upper, estado.exposureCompensationIndex)
        }
    }

    fun tomarFoto(callback: ImageCapture.OnImageCapturedCallback) {
        capturaFoto?.takePicture(ContextCompat.getMainExecutor(contexto), callback)
    }

    /**
     * Guarda la foto directamente en disco con la pipeline de alta calidad de CameraX.
     * Evita cargar el bitmap full-res en memoria (era la causa de los OOM al disparar).
     */
    fun tomarFotoAArchivo(
        opciones: ImageCapture.OutputFileOptions,
        callback: ImageCapture.OnImageSavedCallback
    ) {
        capturaFoto?.takePicture(opciones, ContextCompat.getMainExecutor(contexto), callback)
    }

    fun iniciarGrabacion(
        opciones: MediaStoreOutputOptions,
        onEvento: (VideoRecordEvent) -> Unit
    ): Boolean {
        val vc = capturaVideo ?: return false
        return try {
            grabacionActual = vc.output
                .prepareRecording(contexto, opciones)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(contexto)) { evento ->
                    estaGrabando = evento !is VideoRecordEvent.Finalize
                    onEvento(evento)
                }
            true
        } catch (e: Exception) {
            Log.e("GestorCamara", "Error al iniciar grabación", e)
            false
        }
    }

    fun detenerGrabacion() {
        grabacionActual?.stop()
        grabacionActual = null
    }

    fun ajustarZoom(factor: Float) {
        // Cámara frontal generalmente no soporta zoom óptico
        if (esFrontal) return
        val ratioActual = infoCamara?.zoomState?.value?.zoomRatio ?: 1f
        controlCamara?.setZoomRatio((ratioActual * factor).coerceIn(1f, 10f))
    }

    fun obtenerNivelZoom(): Float = infoCamara?.zoomState?.value?.zoomRatio ?: 1f

    fun ajustarExposicion(indice: Int) {
        controlCamara?.setExposureCompensationIndex(indice)
    }

    fun enfocarEnPunto(x: Float, y: Float, factory: MeteringPointFactory) {
        val punto = factory.createPoint(x, y)
        // AF: autofocus en el punto. AE: ajusta exposición (mide luz ahí mismo).
        // AWB: ajusta balance de blancos según el color de la zona tocada.
        val flags = FocusMeteringAction.FLAG_AF or
                FocusMeteringAction.FLAG_AE or
                FocusMeteringAction.FLAG_AWB
        val accion = FocusMeteringAction.Builder(punto, flags)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        controlCamara?.startFocusAndMetering(accion)
    }

    fun alternarAntorcha(encendida: Boolean) {
        // Cámara frontal no tiene linterna
        if (esFrontal) return
        controlCamara?.enableTorch(encendida)
    }

    fun liberar() {
        ejecutor.shutdown()
    }
}
