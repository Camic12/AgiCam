package com.epm.camaraterreno

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.video.MediaStoreOutputOptions
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GestorArchivos(private val contexto: Context) {

    private val formatoNombre = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val formatoFecha = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * Prepara una captura a un ARCHIVO TEMPORAL privado de la app (cacheDir).
     *
     * Por qué a temp y no directo a MediaStore:
     * Si CameraX guardara directo a MediaStore, la foto sería visible en la
     * galería antes de aplicar la marca de agua. Al sobreescribir el JPEG
     * con la versión marcada se truncaba momentáneamente el archivo, y si el
     * usuario abría la galería en ese instante veía la foto a medio escribir
     * (parte negra / sin estampa). Yendo primero a temp, solo se publica
     * cuando el JPEG final con marca de agua está completo.
     */
    data class OpcionesFoto(
        val opciones: ImageCapture.OutputFileOptions,
        val archivoTemporal: File,
        val nombreBase: String
    )

    fun crearOpcionesCapturaTemporal(): OpcionesFoto {
        val nombre = "Terreno_${formatoNombre.format(Date())}"
        val temp = File(contexto.cacheDir, "captura_$nombre.jpg")
        val opc = ImageCapture.OutputFileOptions.Builder(temp).build()
        return OpcionesFoto(opc, temp, nombre)
    }

    /**
     * Lee el JPEG temporal escrito por CameraX, aplica la marca de agua, e
     * INSERTA la versión final en MediaStore (atómicamente — la foto aparece
     * en la galería solo cuando ya está completa con estampa).
     * Debe llamarse desde un hilo de fondo.
     *
     * @return bitmap pequeño (thumbnail) listo para mostrar en el botón de
     *         galería, o null si algo falló.
     */
    var resolucionLadoMayor: Int = 1920 // Default 1920 (1080p)

    fun procesarYPublicar(
        temp: File,
        nombreBase: String,
        ubicacion: String,
        deteccion: String
    ): Bitmap? {
        try {
            // Downsample en el decode según la calidad elegida.
            val original = decodificarConDownsample(temp, resolucionLadoMayor) ?: return null

            val marcado = agregarMarcaDeAgua(original, ubicacion, deteccion)
            if (marcado !== original) original.recycle()

            // Insertamos la versión final completa en MediaStore. Solo aquí la
            // foto se vuelve visible para la galería.
            val valores = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$nombreBase.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TerrenoApp_Fotos")
                }
            }
            val uri = contexto.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valores
            )
            if (uri == null) {
                marcado.recycle()
                return null
            }
            contexto.contentResolver.openOutputStream(uri)?.use { stream ->
                marcado.compress(Bitmap.CompressFormat.JPEG, 88, stream)
            }

            guardarRegistroTxt(nombreBase, ubicacion, deteccion)

            // Thumbnail mini para el botón circular de galería (~160 px de lado).
            val thumb = crearThumbnail(marcado, 160)
            marcado.recycle()
            return thumb
        } catch (e: Exception) {
            Log.e("GestorArchivos", "Error procesando foto", e)
            return null
        } finally {
            temp.delete()
        }
    }

    /**
     * Decodifica un JPEG desde un File aplicando inSampleSize para que el bitmap
     * resultante tenga su lado mayor cercano (no menor) a [ladoMayorObjetivo],
     * y luego escala exacto. Así nunca cargamos 12 MP en memoria.
     */
    private fun decodificarConDownsample(file: File, ladoMayorObjetivo: Int): Bitmap? {
        val path = file.absolutePath

        // Paso 1: leer dimensiones sin cargar pixeles.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Paso 2: calcular inSampleSize (potencia de 2) tal que el bitmap decodificado
        // no sea menor que el objetivo.
        val ladoOriginal = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (ladoOriginal / (sample * 2) >= ladoMayorObjetivo) sample *= 2

        // Paso 3: decodificar con downsample real.
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
            inMutable = true
        }
        val decodificado = BitmapFactory.decodeFile(path, opts) ?: return null

        // Paso 4: si quedó más grande que el objetivo, escalar exacto.
        val ladoDec = maxOf(decodificado.width, decodificado.height)
        return if (ladoDec > ladoMayorObjetivo) {
            val factor = ladoMayorObjetivo.toFloat() / ladoDec
            val w = (decodificado.width * factor).toInt().coerceAtLeast(1)
            val h = (decodificado.height * factor).toInt().coerceAtLeast(1)
            val escalado = Bitmap.createScaledBitmap(decodificado, w, h, true)
            if (escalado !== decodificado) decodificado.recycle()
            escalado
        } else {
            decodificado
        }
    }

    private fun crearThumbnail(src: Bitmap, ladoMax: Int): Bitmap {
        val factor = ladoMax.toFloat() / maxOf(src.width, src.height)
        val w = (src.width * factor).toInt().coerceAtLeast(1)
        val h = (src.height * factor).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    fun crearOpcionesVideoMediaStore(): MediaStoreOutputOptions {
        val nombre = "Video_Terreno_${formatoNombre.format(Date())}"
        val valores = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$nombre.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TerrenoApp_Videos")
            }
        }
        return MediaStoreOutputOptions.Builder(
            contexto.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(valores).build()
    }

    private fun guardarRegistroTxt(nombreArchivo: String, ubicacion: String, deteccion: String) {
        val nombreFichero = "Log_Inspecciones.txt"
        val carpeta = "Documents/TerrenoApp_Registros"
        val resolver = contexto.contentResolver

        val valoresFichero = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nombreFichero)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, carpeta)
            }
        }

        val uriBase = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val seleccion = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val args = arrayOf(nombreFichero, "$carpeta/")
        val cursor = resolver.query(uriBase, null, seleccion, args, null)

        val uri = if (cursor?.moveToFirst() == true) {
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            ContentUris.withAppendedId(uriBase, cursor.getLong(idCol))
        } else {
            resolver.insert(uriBase, valoresFichero)
        }
        cursor?.close()

        try {
            uri?.let {
                resolver.openOutputStream(it, "wa").use { stream: OutputStream? ->
                    val linea = buildString {
                        append("ARCHIVO: $nombreArchivo.jpg")
                        append(" | FECHA: ${formatoFecha.format(Date())}")
                        append(" | GPS: $ubicacion")
                        append(" | Modelo: $deteccion\n")
                    }
                    stream?.write(linea.toByteArray())
                }
            }
        } catch (e: Exception) {
            Log.e("GestorArchivos", "Error al escribir TXT", e)
        }
    }

    fun agregarMarcaDeAgua(src: Bitmap, ubicacion: String, deteccion: String): Bitmap {
        val resultado = if (src.isMutable) src else src.copy(src.config ?: Bitmap.Config.RGB_565, true)
        val canvas = Canvas(resultado)

        // Escala basada en el lado menor para que el texto se vea proporcional
        // tanto en portrait (1080×1920) como en landscape (1920×1080).
        val ladoMenor = minOf(src.width, src.height).toFloat()
        val escala = (ladoMenor / 1080f).coerceIn(0.85f, 2.5f)
        val pad = 22f * escala
        val startX = 24f * escala
        val tamTextoInicial = 38f * escala
        val tamTextoMinimo = 18f * escala

        val paintTexto = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD
            )
            setShadowLayer(3f * escala, 0f, 1f * escala, Color.BLACK)
        }

        val fecha = formatoFecha.format(Date())
        val linea1 = "Modelo: $deteccion"
        val linea2 = "$fecha  |  GPS: $ubicacion"

        // Reservamos espacio para el logo a la derecha. El ancho útil para texto
        // es el ancho de la imagen menos el logo, dos paddings y un margen.
        val tamLogoAprox = tamTextoInicial * 2.1f  // logo ≈ alto de 2 líneas
        val anchoUtil = src.width - tamLogoAprox - pad * 3 - startX

        // Auto-fit: reducimos textSize hasta que la línea más larga quepa en anchoUtil.
        var tamTexto = tamTextoInicial
        paintTexto.textSize = tamTexto
        while (tamTexto > tamTextoMinimo) {
            val anchoMax = maxOf(paintTexto.measureText(linea1), paintTexto.measureText(linea2))
            if (anchoMax <= anchoUtil) break
            tamTexto -= 1f
            paintTexto.textSize = tamTexto
        }

        val bounds = Rect()
        paintTexto.getTextBounds("Ag", 0, 2, bounds)
        val alturaLinea = bounds.height().toFloat()
        val entreLineas = 10f * escala
        val totalH = alturaLinea * 2 + entreLineas + pad * 2

        // Barra oscura ancho completo pegada al fondo
        canvas.drawRect(
            0f, (src.height - totalH),
            src.width.toFloat(), src.height.toFloat(),
            Paint().apply { color = Color.parseColor("#CC000000") }
        )

        val baseY = src.height - totalH + pad + alturaLinea
        canvas.drawText(linea1, startX, baseY, paintTexto)
        canvas.drawText(linea2, startX, baseY + alturaLinea + entreLineas, paintTexto)

        // Logo en la esquina inferior derecha de la barra
        try {
            BitmapFactory.decodeResource(contexto.resources, R.mipmap.ic_launcher)?.let { logo ->
                val logoSize = (totalH * 0.70f).toInt()
                val logoEsc = Bitmap.createScaledBitmap(logo, logoSize, logoSize, true)
                canvas.drawBitmap(
                    logoEsc,
                    src.width - logoSize - pad,
                    src.height - totalH + (totalH - logoSize) / 2f,
                    null
                )
                logoEsc.recycle()
                logo.recycle()
            }
        } catch (_: Exception) {}

        return resultado
    }
}
