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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.github.chrisbanes.photoview.PhotoView

class GalleryActivity : AppCompatActivity() {

    private lateinit var recyclerGrid: RecyclerView
    private lateinit var overlayVisor: FrameLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var tvContador: TextView
    private lateinit var barraAcciones: LinearLayout
    private lateinit var btnVolver: FrameLayout
    private lateinit var btnEliminar: FrameLayout
    private lateinit var btnCompartir: FrameLayout
    private lateinit var btnFiltrosGrid: ImageView
    private lateinit var btnVolverGrid: ImageView
    private lateinit var panelSinFotos: LinearLayout
    private lateinit var barraInferiorGrid: LinearLayout

    private val itemsTodos = mutableListOf<ItemGaleria>()
    private val items = mutableListOf<ItemGaleria>()
    private lateinit var adaptadorGrid: AdaptadorGrid
    private lateinit var adaptadorVisor: AdaptadorVisor
    private var controlesVisibles = true
    private var filtroActual: Filtro = Filtro.TODO
    private var isVisorAbierto = false

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
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
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

        configurarGrid()
        configurarVisor()
        configurarBotones()
        configurarBotonAtras()
        refrescarEstadoVacio()
    }

    private fun enlazarVistas() {
        recyclerGrid = findViewById(R.id.recyclerGrid)
        overlayVisor = findViewById(R.id.overlayVisor)
        viewPager = findViewById(R.id.viewPagerGallery)
        tvContador = findViewById(R.id.tvContador)
        barraAcciones = findViewById(R.id.barraAcciones)
        btnVolver = findViewById(R.id.btnVolver)
        btnEliminar = findViewById(R.id.btnEliminar)
        btnCompartir = findViewById(R.id.btnCompartir)
        btnFiltrosGrid = findViewById(R.id.btnFiltrosGrid)
        btnVolverGrid = findViewById(R.id.btnVolverGrid)
        panelSinFotos = findViewById(R.id.panelSinFotos)
        barraInferiorGrid = findViewById(R.id.barraInferiorGrid)
    }

    private fun cargarItems() {
        itemsTodos.clear()
        val coleccionImg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        contentResolver.query(coleccionImg, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED), "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("%TerrenoApp_Fotos%"), null)?.use { cursor ->
            val colId = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val colTs = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) itemsTodos.add(ItemGaleria(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(colId)), false, cursor.getLong(colTs)))
        }
        val coleccionVid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        contentResolver.query(coleccionVid, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED), "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("%TerrenoApp_Videos%"), null)?.use { cursor ->
            val colId = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val colTs = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (cursor.moveToNext()) itemsTodos.add(ItemGaleria(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(colId)), true, cursor.getLong(colTs)))
        }
        itemsTodos.sortByDescending { it.timestamp }
    }

    private fun aplicarFiltro() {
        val ahoraSeg = System.currentTimeMillis() / 1000
        val filtrados = when (filtroActual) {
            Filtro.TODO -> itemsTodos
            Filtro.SOLO_FOTOS -> itemsTodos.filter { !it.esVideo }
            Filtro.SOLO_VIDEOS -> itemsTodos.filter { it.esVideo }
            Filtro.ULTIMOS_7_DIAS -> itemsTodos.filter { ahoraSeg - it.timestamp <= 7L * 24 * 60 * 60 }
            Filtro.ULTIMOS_30_DIAS -> itemsTodos.filter { ahoraSeg - it.timestamp <= 30L * 24 * 60 * 60 }
        }
        items.clear()
        items.addAll(filtrados)
    }

    private fun configurarGrid() {
        adaptadorGrid = AdaptadorGrid(items) { pos -> abrirVisor(pos) }
        recyclerGrid.layoutManager = GridLayoutManager(this, 3)
        recyclerGrid.adapter = adaptadorGrid
    }

    private fun configurarVisor() {
        adaptadorVisor = AdaptadorVisor(items, { alternarControles() }, { uri -> reproducirVideo(uri) })
        viewPager.adapter = adaptadorVisor
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { actualizarContador(position) }
        })
    }

    private fun configurarBotones() {
        btnVolverGrid.setOnClickListener { finish() }
        btnFiltrosGrid.setOnClickListener { mostrarMenuFiltros(it) }
        btnVolver.setOnClickListener { cerrarVisor() }
        btnEliminar.setOnClickListener { confirmarEliminacion() }
        btnCompartir.setOnClickListener { compartirActual() }
    }

    private fun abrirVisor(pos: Int) {
        if (items.isEmpty()) return
        isVisorAbierto = true
        overlayVisor.visibility = View.VISIBLE
        overlayVisor.alpha = 0f
        overlayVisor.animate().alpha(1f).setDuration(250).start()
        viewPager.setCurrentItem(pos, false)
        actualizarContador(pos)
    }

    private fun cerrarVisor() {
        isVisorAbierto = false
        overlayVisor.animate().alpha(0f).setDuration(250).withEndAction { overlayVisor.visibility = View.GONE }.start()
    }

    private fun configurarBotonAtras() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isVisorAbierto) cerrarVisor() else finish()
            }
        })
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
        } catch (e: Exception) { Toast.makeText(this, "No se pudo compartir", Toast.LENGTH_SHORT).show() }
    }

    private fun mostrarMenuFiltros(anchor: View) {
        val popup = PopupMenu(this, anchor)
        Filtro.values().forEachIndexed { idx, filtro -> popup.menu.add(0, idx, idx, if (filtro == filtroActual) "✓ ${filtro.etiqueta}" else filtro.etiqueta) }
        popup.setOnMenuItemClickListener { mi ->
            val nuevo = Filtro.values()[mi.itemId]
            if (nuevo != filtroActual) {
                filtroActual = nuevo
                aplicarFiltro()
                adaptadorGrid.notifyDataSetChanged()
                adaptadorVisor.notifyDataSetChanged()
                refrescarEstadoVacio()
            }
            true
        }
        popup.show()
    }

    private fun refrescarEstadoVacio() {
        if (items.isEmpty()) {
            recyclerGrid.visibility = View.GONE
            panelSinFotos.visibility = View.VISIBLE
            if (isVisorAbierto) cerrarVisor()
        } else {
            recyclerGrid.visibility = View.VISIBLE
            panelSinFotos.visibility = View.GONE
        }
    }

    private fun actualizarContador(pos: Int) { tvContador.text = "${pos + 1} / ${items.size}" }

    private fun alternarControles() {
        controlesVisibles = !controlesVisibles
        val alpha = if (controlesVisibles) 1f else 0f
        barraAcciones.animate().alpha(alpha).setDuration(200).withEndAction { barraAcciones.visibility = if (controlesVisibles) View.VISIBLE else View.INVISIBLE }.start()
    }

    private fun reproducirVideo(uri: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "video/mp4"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) } 
        catch (e: Exception) { Toast.makeText(this, "No hay reproductor de video disponible", Toast.LENGTH_SHORT).show() }
    }

    private fun confirmarEliminacion() {
        val pos = viewPager.currentItem
        if (pos < 0 || pos >= items.size) return
        val esVideo = items[pos].esVideo
        AlertDialog.Builder(this)
            .setTitle(if (esVideo) "Eliminar video" else "Eliminar foto")
            .setMessage(if (esVideo) "¿Eliminar este video permanentemente?" else "¿Eliminar esta foto permanentemente?")
            .setPositiveButton("Eliminar") { _, _ -> eliminarItem(pos) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun eliminarItem(pos: Int) {
        val item = items[pos]
        try {
            if (contentResolver.delete(item.uri, null, null) > 0) {
                items.removeAt(pos)
                itemsTodos.removeAll { it.uri == item.uri }
                adaptadorGrid.notifyItemRemoved(pos)
                adaptadorGrid.notifyItemRangeChanged(pos, items.size)
                adaptadorVisor.notifyItemRemoved(pos)
                adaptadorVisor.notifyItemRangeChanged(pos, items.size)
                refrescarEstadoVacio()
                if (items.isNotEmpty()) {
                    val nuevaPos = pos.coerceAtMost(items.size - 1)
                    viewPager.setCurrentItem(nuevaPos, false)
                    actualizarContador(nuevaPos)
                }
                Toast.makeText(this, if (item.esVideo) "Video eliminado" else "Foto eliminada", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this, "No se pudo eliminar", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    inner class AdaptadorGrid(private val data: List<ItemGaleria>, private val onClick: (Int) -> Unit) : RecyclerView.Adapter<AdaptadorGrid.VH>() {
        inner class VH(val root: ImageView) : RecyclerView.ViewHolder(root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val iv = ImageView(parent.context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 350)
                (layoutParams as ViewGroup.MarginLayoutParams).setMargins(4, 4, 4, 4)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(android.graphics.Color.DKGRAY)
            }
            return VH(iv)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            Glide.with(holder.root.context).load(data[position].uri).override(350).into(holder.root)
            holder.root.setOnClickListener { onClick(position) }
        }
        override fun getItemCount() = data.size
    }

    inner class AdaptadorVisor(private val data: List<ItemGaleria>, private val onTap: () -> Unit, private val onPlayVideo: (Uri) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val TIPO_FOTO = 0
        private val TIPO_VIDEO = 1
        inner class VHFoto(root: View) : RecyclerView.ViewHolder(root) { val foto: PhotoView = root.findViewById(R.id.photoView) }
        inner class VHVideo(root: View) : RecyclerView.ViewHolder(root) {
            val thumb: ImageView = root.findViewById(R.id.thumbVideo)
            val botonPlay: FrameLayout = root.findViewById(R.id.btnReproducir)
            val raiz: View = root
        }
        override fun getItemViewType(position: Int) = if (data[position].esVideo) TIPO_VIDEO else TIPO_FOTO
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = if (viewType == TIPO_VIDEO) VHVideo(LayoutInflater.from(parent.context).inflate(R.layout.item_gallery_video, parent, false)) else VHFoto(LayoutInflater.from(parent.context).inflate(R.layout.item_gallery_image, parent, false))
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = data[position]
            if (holder is VHFoto) {
                Glide.with(holder.foto.context).load(item.uri).into(holder.foto)
                holder.foto.setOnClickListener { onTap() }
            } else if (holder is VHVideo) {
                Glide.with(holder.thumb.context).load(item.uri).into(holder.thumb)
                holder.botonPlay.setOnClickListener { onPlayVideo(item.uri) }
                holder.raiz.setOnClickListener { onTap() }
            }
        }
        override fun getItemCount() = data.size
    }
}
