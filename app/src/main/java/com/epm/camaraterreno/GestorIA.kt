package com.epm.camaraterreno

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.util.Log
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.classifier.ImageClassifier
import kotlin.math.abs
import kotlin.math.pow

class GestorIA(private val contexto: Context) {

    private var clasificador: ImageClassifier? = null
    private var etiquetas = emptyList<String>()

    private var lumaAnterior = -1.0
    private var framesEnMovimiento = 0

    // Throttling de la clasificación TFLite: limitamos la inferencia para no
    // saturar la CPU en dispositivos lentos (ej. Helio G36). El chequeo de
    // calidad (luma/varianza) sigue corriendo en cada frame porque es muy barato.
    // 150 ms ≈ 6-7 inferencias/seg → buen balance entre fluidez de detección
    // y carga de CPU. Si en el Ulefone notas que vuelve a lag-uear, subir a 200-250.
    private val intervaloClasificacionMs = 150L
    private var ultimaClasificacionMs = 0L

    var ultimaDeteccion: String = "Iniciando IA..."
        private set

    enum class EstadoCalidad {
        TAPADA,
        TODO_NEGRO,
        TODO_BLANCO,
        BORROSA,
        EN_MOVIMIENTO,
        OK
    }

    data class ResultadoAnalisis(
        val luma: Double,
        val varianza: Double,
        val deteccion: String,
        val estado: EstadoCalidad
    )

    fun inicializar() {
        cargarEtiquetas()
        inicializarClasificador()
    }

    private fun cargarEtiquetas() {
        etiquetas = try {
            contexto.assets.open("labels.txt").bufferedReader().readLines()
        } catch (e: Exception) {
            listOf("Medidor", "Indefinido")
        }
    }

    /**
     * Construye BaseOptions con aceleración por hardware cuando es posible:
     * - NNAPI delegate en Android 8.1+ (API 27+): usa el chip de IA del dispositivo
     *   si existe (Hexagon DSP, NPU, GPU vía driver), si no cae a CPU optimizada.
     * - Fallback: 4 hilos de CPU.
     * Si NNAPI falla al crear el clasificador, reintentamos sólo con CPU.
     */
    private fun construirBaseOptions(usarNnapi: Boolean): BaseOptions {
        val builder = BaseOptions.builder().setNumThreads(1) // Reducido a 1 para dispositivos de baja RAM
        if (usarNnapi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            builder.useNnapi()
        }
        return builder.build()
    }

    private fun crearClasificador(rutaModelo: String): ImageClassifier? {
        // Intento 1: con NNAPI.
        try {
            val opciones = ImageClassifier.ImageClassifierOptions.builder()
                .setMaxResults(1)
                .setBaseOptions(construirBaseOptions(usarNnapi = true))
                .build()
            return ImageClassifier.createFromFileAndOptions(contexto, rutaModelo, opciones)
        } catch (e: Exception) {
            Log.w("GestorIA", "NNAPI no disponible para $rutaModelo, caigo a CPU", e)
        }
        // Intento 2: solo CPU multihilo.
        return try {
            val opciones = ImageClassifier.ImageClassifierOptions.builder()
                .setMaxResults(1)
                .setBaseOptions(construirBaseOptions(usarNnapi = false))
                .build()
            ImageClassifier.createFromFileAndOptions(contexto, rutaModelo, opciones)
        } catch (e: Exception) {
            Log.e("GestorIA", "No se pudo cargar $rutaModelo", e)
            null
        }
    }

    private fun inicializarClasificador() {
        clasificador = crearClasificador("model.tflite")
        if (clasificador == null) ultimaDeteccion = "Falta Modelo IA"
    }

    fun analizarFrame(imagen: ImageProxy, rotacionDispositivo: Int, flashActivo: Boolean): ResultadoAnalisis {
        try {
            val bitmap = imagen.toBitmap()
            val (luma, varianza) = calcularCalidad(bitmap)
            val estado = evaluarEstadoCalidad(luma, varianza, flashActivo)

            val ahora = System.currentTimeMillis()
            val debeClasificar = estado == EstadoCalidad.OK &&
                    clasificador != null &&
                    (ahora - ultimaClasificacionMs) >= intervaloClasificacionMs

            if (debeClasificar) {
                ultimaClasificacionMs = ahora

                // Compensamos la rotación del sensor (rotationDegrees) con la rotación física del dispositivo
                // Esto garantiza que si el celular está horizontal, la IA reciba la imagen derecha.
                val grados = (imagen.imageInfo.rotationDegrees - rotacionDispositivo + 360) % 360
                val bitmapParaIA = if (grados != 0) {
                    val matrix = Matrix().apply { postRotate(grados.toFloat()) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                } else {
                    bitmap
                }

                val resultados = clasificador?.classify(TensorImage.fromBitmap(bitmapParaIA))
                if (!resultados.isNullOrEmpty() && resultados[0].categories.isNotEmpty()) {
                    val cat = resultados[0].categories[0]
                    val pct = (cat.score * 100).toInt()
                    val idx = cat.label.toIntOrNull()
                    var etiqueta = if (idx != null && idx < etiquetas.size) etiquetas[idx] else cat.label
                    etiqueta = etiqueta.replace(Regex("^\\d+\\s*"), "")
                    val esIndefinido = etiqueta.trim().equals("Indefinido", ignoreCase = true)
                    ultimaDeteccion = when {
                        cat.score >= 0.60f && !esIndefinido -> "$etiqueta ($pct%)"
                        else -> "No detectado"
                    }
                } else {
                    ultimaDeteccion = "No detectado"
                }

                if (bitmapParaIA !== bitmap) bitmapParaIA.recycle()
            }

            return ResultadoAnalisis(luma, varianza, ultimaDeteccion, estado)
        } finally {
            imagen.close()
        }
    }

    private fun evaluarEstadoCalidad(luma: Double, varianza: Double, flashActivo: Boolean): EstadoCalidad {
        val hayMovimiento = if (lumaAnterior >= 0) {
            val delta = abs(luma - lumaAnterior)
            if (delta > 22.0) {
                framesEnMovimiento++
            } else {
                framesEnMovimiento = (framesEnMovimiento - 1).coerceAtLeast(0)
            }
            framesEnMovimiento >= 2
        } else {
            false
        }
        lumaAnterior = luma

        val umbralOscuro = if (flashActivo) 18.0 else 42.0

        return when {
            luma < 6.0 && varianza < 150.0 -> EstadoCalidad.TAPADA
            luma < umbralOscuro             -> EstadoCalidad.TODO_NEGRO
            luma > 245.0                    -> EstadoCalidad.TODO_BLANCO
            varianza < 700.0                -> EstadoCalidad.BORROSA
            hayMovimiento                   -> EstadoCalidad.EN_MOVIMIENTO
            else                            -> EstadoCalidad.OK
        }
    }

    private fun calcularCalidad(bitmap: Bitmap): Pair<Double, Double> {
        val escalado = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        val pixeles = IntArray(escalado.width * escalado.height)
        escalado.getPixels(pixeles, 0, escalado.width, 0, 0, escalado.width, escalado.height)

        val n = pixeles.size.toDouble()
        val luma = pixeles.sumOf { p ->
            0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
        } / n

        val varianza = pixeles.sumOf { p ->
            val l = 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
            (l - luma).pow(2.0)
        } / n

        return Pair(luma, varianza)
    }

    fun liberar() {
        clasificador?.close()
    }
}
