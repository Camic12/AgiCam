package com.epm.camaraterreno

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.github.chrisbanes.photoview.PhotoView

class GalleryActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tvContador: TextView
    private lateinit var barraContraoles: LinearLayout
    private lateinit var barraAcciones: LinearLayout
    private lateinit var btnVolver: FrameLayout
    private lateinit var btnEliminar: FrameLayout
    private lateinit var btnCompartir: FrameLayout
    private lateinit var btnFiltros: FrameLayout
    private lateinit var panelSinFotos: LinearLayout

    // Lista completa cargada de MediaStore. items = lista actualmente mostrada
    // (puede ser una vista filtrada de itemsTodos).
    private val itemsTodos = mutableListOf<ItemGaleria>()
    private val items = mutableListOf<ItemGaleria>()
    private lateinit var adaptador: AdaptadorVisor
    private var controlesVisibles = true
    private var filtroActual: Filtro = Filtro.TODO

    /** Un elemento de la galería puede ser foto o video. */
    data class ItemGaleria(val uri: Uri, val esVideo: Boolean, val timestamp: Long)

    enum class Filtro(val etiqueta: String) {
        TODO("Todo"),
        SOLO_FOTOS("Solo fotos"),
        SOLO_VIDEOS("Solo videos"),
        ULTIMOS_7_DIAS("Últimos 7 días"),
        ULTIMOS_30_DIAS("Últimos 30 días")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )

        setContentView(R.layout.activity_gallery)
        enlazarVistas()
        cargarItems()
        aplicarFiltro()

        if (items.isEmpty()) {
            viewPager.visibility = View.GONE
            barraContraoles.visibility = View.GONE
            barraAcciones.visibility = View.GONE
            panelSinFotos.visibility = View.VISIBLE

            btnVolver.setOnClickListener { finish() }
            barraAcciones.visibility = View.VISIBLE
            btnEliminar.visibility = View.GONE
            btnCompartir.visibility = View.GONE
            btnFiltros.visibility = View.GONE
            return
        }

        configurarVisor()
        configurarBotones()
        configurarBotonAtras()
    }

    private fun enlazarVistas() {
        viewPager = findViewById(R.id.viewPagerGallery)
        tvContador = findViewById(R.id.tvContador)
        barraContraoles = findViewById(R.id.barraContraoles)
        barraAcciones = findViewById(R.id.barraAcciones)
        btnVolver = findViewById(R.id.btnVolver)
        btnEliminar = findViewById(R.id.btnEliminar)
        btnCompartir = findViewById(R.id.btnCompartir)
        btnFiltros = findViewById(R.id.btnFiltros)
        panelSinFotos = findViewById(R.id.panelSinFotos)
    }

    /** Carga fotos (TerrenoApp_Fotos) y videos (TerrenoApp_Videos) ordenados por fecha desc. */
    private fun cargarItems() {
        itemsTodos.clear()

        // --- Fotos ---
        val coleccionImg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        contentResolver.query(
            coleccionImg,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("%TerrenoApp_Fotos%"),
            null
        )?.use { cursor ->
            val colId = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val colTs = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                itemsTodos.add(
                    ItemGaleria(
                        ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            cursor.getLong(colId)
                        ),
                        esVideo = false,
                        timestamp = cursor.getLong(colTs)
                    )
                )
            }
        }

        // --- Videos ---
        val coleccionVid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        contentResolver.query(
            coleccionVid,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED),
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("%TerrenoApp_Videos%"),
            null
        )?.use { cursor ->
            val colId = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val colTs = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                itemsTodos.add(
                    ItemGaleria(
                        ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            cursor.getLong(colId)
                        ),
                        esVideo = true,
                        timestamp = cursor.getLong(colTs)
                    )
                )
            }
        }

        itemsTodos.sortByDescending { it.timestamp }
    }

    /** Reconstruye [items] aplicando [filtroActual] sobre [itemsTodos]. */
    private fun aplicarFiltro() {
        // DATE_ADDED en MediaStore es timestamp UNIX en segundos.
        val ahoraSeg = System.currentTimeMillis() / 1000
        val seg7Dias = 7L * 24 * 60 * 60
        val seg30Dias = 30L * 24 * 60 * 60

        val filtrados = when (filtroActual) {
            Filtro.TODO -> itemsTodos
            Filtro.SOLO_FOTOS -> itemsTodos.filter { !it.esVideo }
            Filtro.SOLO_VIDEOS -> itemsTodos.filter { it.esVideo }
            Filtro.ULTIMOS_7_DIAS -> itemsTodos.filter { ahoraSeg - it.timestamp <= seg7Dias }
            Filtro.ULTIMOS_30_DIAS -> itemsTodos.filter { ahoraSeg - it.timestamp <= seg30Dias }
        }

        items.clear()
        items.addAll(filtrados)
    }

    private fun configurarVisor() {
        val posInicial = intent.getIntExtra("posicion", 0).coerceIn(0, items.size - 1)

        adaptador = AdaptadorVisor(items, { alternarControles() }, { uri -> reproducirVideo(uri) })
        viewPager.adapter = adaptador
        viewPager.setCurrentItem(posInicial, false)
        actualizarContador(posInicial)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                actualizarContador(position)
            }
        })
    }

    private fun configurarBotones() {
        btnVolver.setOnClickListener { finish() }
        btnEliminar.setOnClickListener { confirmarEliminacion() }
        btnCompartir.setOnClickListener { compartirActual() }
        btnFiltros.setOnClickListener { mostrarMenuFiltros(it) }
    }

    private fun compartirActual() {
        val pos = viewPager.currentItem
        if (pos < 0 || pos >= items.size) return
        val item = items[pos]
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (item.esVideo) "video/mp4" else "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, item.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Compartir"))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo compartir", Toast.LENGTH_SHORT).show()
        }
    }

    private fun mostrarMenuFiltros(anchor: View) {
        val popup = PopupMenu(this, anchor)
        Filtro.values().forEachIndexed { idx, filtro ->
            val titulo = if (filtro == filtroActual) "✓ ${filtro.etiqueta}" else filtro.etiqueta
            popup.menu.add(0, idx, idx, titulo)
        }
        popup.setOnMenuItemClickListener { mi ->
            val nuevo = Filtro.values()[mi.itemId]
            if (nuevo != filtroActual) {
                filtroActual = nuevo
                aplicarFiltro()
                refrescarTrasFiltro()
            }
            true
        }
        popup.show()
    }

    private fun refrescarTrasFiltro() {
        adaptador.notifyDataSetChanged()
        if (items.isEmpty()) {
            viewPager.visibility = View.GONE
            panelSinFotos.visibility = View.VISIBLE
            btnEliminar.visibility = View.GONE
            btnCompartir.visibility = View.GONE
            tvContador.text = "0 / 0"
        } else {
            viewPager.visibility = View.VISIBLE
            panelSinFotos.visibility = View.GONE
            btnEliminar.visibility = View.VISIBLE
            btnCompartir.visibility = View.VISIBLE
            viewPager.setCurrentItem(0, false)
            actualizarContador(0)
        }
    }

    private fun configurarBotonAtras() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }

    private fun actualizarContador(pos: Int) {
        tvContador.text = "${pos + 1} / ${items.size}"
    }

    private fun alternarControles() {
        controlesVisibles = !controlesVisibles
        val alpha = if (controlesVisibles) 1f else 0f
        val duracion = 200L
        barraContraoles.animate().alpha(alpha).setDuration(duracion).withEndAction {
            barraContraoles.visibility = if (controlesVisibles) View.VISIBLE else View.INVISIBLE
        }.start()
        barraAcciones.animate().alpha(alpha).setDuration(duracion).withEndAction {
            barraAcciones.visibility = if (controlesVisibles) View.VISIBLE else View.INVISIBLE
        }.start()
    }

    /** Lanza la app de video predeterminada del sistema para reproducir el clip. */
    private fun reproducirVideo(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No hay reproductor de video disponible", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmarEliminacion() {
        val pos = viewPager.currentItem
        if (pos < 0 || pos >= items.size) return
        val esVideo = items[pos].esVideo
        val titulo = if (esVideo) "Eliminar video" else "Eliminar foto"
        val mensaje = if (esVideo)
            "¿Eliminar este video permanentemente?"
        else
            "¿Eliminar esta foto permanentemente?"

        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(mensaje)
            .setPositiveButton("Eliminar") { _, _ -> eliminarItem(pos) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun eliminarItem(pos: Int) {
        val item = items[pos]
        try {
            if (contentResolver.delete(item.uri, null, null) > 0) {
                items.removeAt(pos)
                itemsTodos.removeAll { it.uri == item.uri }
                adaptador.notifyItemRemoved(pos)
                adaptador.notifyItemRangeChanged(pos, items.size)

                if (items.isEmpty()) {
                    viewPager.visibility = View.GONE
                    barraContraoles.visibility = View.GONE
                    btnEliminar.visibility = View.GONE
                    btnCompartir.visibility = View.GONE
                    panelSinFotos.visibility = View.VISIBLE
                } else {
                    val nuevaPos = pos.coerceAtMost(items.size - 1)
                    viewPager.setCurrentItem(nuevaPos, false)
                    actualizarContador(nuevaPos)
                }
                val msg = if (item.esVideo) "Video eliminado" else "Foto eliminada"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No se pudo eliminar", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== ADAPTADOR =====

    inner class AdaptadorVisor(
        private val data: MutableList<ItemGaleria>,
        private val onTap: () -> Unit,
        private val onPlayVideo: (Uri) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TIPO_FOTO = 0
        private val TIPO_VIDEO = 1

        inner class VHFoto(root: View) : RecyclerView.ViewHolder(root) {
            val foto: PhotoView = root.findViewById(R.id.photoView)
        }

        inner class VHVideo(root: View) : RecyclerView.ViewHolder(root) {
            val thumb: ImageView = root.findViewById(R.id.thumbVideo)
            val botonPlay: FrameLayout = root.findViewById(R.id.btnReproducir)
            val raiz: View = root
        }

        override fun getItemViewType(position: Int): Int =
            if (data[position].esVideo) TIPO_VIDEO else TIPO_FOTO

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TIPO_VIDEO) {
                VHVideo(inflater.inflate(R.layout.item_gallery_video, parent, false))
            } else {
                VHFoto(inflater.inflate(R.layout.item_gallery_image, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = data[position]
            when (holder) {
                is VHFoto -> {
                    Glide.with(holder.foto.context)
                        .load(item.uri)
                        .into(holder.foto)
                    holder.foto.setOnClickListener { onTap() }
                }
                is VHVideo -> {
                    // Glide genera el thumbnail (primer frame) automáticamente para URIs de video.
                    Glide.with(holder.thumb.context)
                        .load(item.uri)
                        .into(holder.thumb)
                    holder.botonPlay.setOnClickListener { onPlayVideo(item.uri) }
                    // Tap fuera del botón de play: alterna controles como con las fotos.
                    holder.raiz.setOnClickListener { onTap() }
                }
            }
        }

        override fun getItemCount() = data.size
    }
}
