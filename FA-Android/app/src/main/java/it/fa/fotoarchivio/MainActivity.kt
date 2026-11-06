package it.fa.fotoarchivio

import android.app.AlertDialog
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.content.Intent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var activeCategory = "PERSONALE"
    private var pendingPhoto: Uri? = null
    private var pendingVideo: Uri? = null
    private var importQueue: MutableList<Uri> = mutableListOf()
    private val selectedPhotos = linkedSetOf<String>()
    private var explorerCategory = "PERSONALE"
    private var explorerFolder = "Generale"
    private var sortMode = "Data"
    private val prefs by lazy { getSharedPreferences("fa_settings", Context.MODE_PRIVATE) }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingPhoto
        if (ok && uri != null) showNamingDialog(uri, false)
        else {
            uri?.let { contentResolver.delete(it, null, null) }
            pendingPhoto = null
            toast("Scatto annullato")
            showHome()
        }
    }

    private val videoLauncher = registerForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val uri = pendingVideo
        if (ok && uri != null) showNamingDialog(uri, true)
        else {
            uri?.let { contentResolver.delete(it, null, null) }
            pendingVideo = null
            toast("Registrazione annullata")
            showHome()
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) {
            importQueue = uris.toMutableList()
            showNextImportedItem()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF10191F.toInt()
        window.navigationBarColor = 0xFF05080B.toInt()
        window.decorView.systemUiVisibility = 0
        showHome()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun darkEditText(hintText: String = "", initialText: String? = null): EditText =
        EditText(this).apply {
            hint = hintText
            initialText?.let { setText(it) }
            isSingleLine = true
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFB8C7D2.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minHeight = dp(52)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF263744.toInt())
                cornerRadius = dp(9).toFloat()
                setStroke(dp(1), 0xFF9DB9D2.toInt())
            }
            backgroundTintList = null
            highlightColor = 0xFF496F91.toInt()
        }

    private fun page(title: String): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF10191F.toInt(), 0xFF182128.toInt(), 0xFF0C1217.toInt())
            )
        }
        root.addView(TextView(this).apply {
            text = title
            textSize = 27f
            typeface = android.graphics.Typeface.create("cursive", android.graphics.Typeface.BOLD)
            setTextColor(0xFFF4EBDD.toInt())
            setPadding(0, 0, 0, dp(8))
        })
        return root
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(0xFFF7F5F0.toInt())
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF294968.toInt(), 0xFF142B43.toInt())
            ).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), 0xFF9DB9D2.toInt())
            }
            backgroundTintList = null
            elevation = dp(3).toFloat()
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { onClick() }
        }

    private fun showHome() {
        val root = page("")
        root.setPadding(dp(18), dp(12), dp(18), dp(48))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "📷"
            textSize = 43f
            setPadding(0, 0, dp(12), 0)
        }, LinearLayout.LayoutParams(dp(68), dp(76)))
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(TextView(this).apply {
            text = "FA - Foto Archivio"
            textSize = 30f
            typeface = android.graphics.Typeface.create("cursive", android.graphics.Typeface.BOLD)
            setTextColor(0xFFF7F1E7.toInt())
        })
        heading.addView(TextView(this).apply {
            text = "Le tue foto, sempre con te"
            textSize = 16f
            typeface = android.graphics.Typeface.create("cursive", android.graphics.Typeface.NORMAL)
            setTextColor(0xFFD8D5D0.toInt())
        })
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(82)).apply {
            bottomMargin = dp(12)
        })

        val categories = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        categories.addView(actionButton(if (activeCategory == "PERSONALE") "👤  PERSONALE  ✓" else "👤  PERSONALE") {
            activeCategory = "PERSONALE"
            showHome()
            toast("Archivio attivo: PERSONALE")
        }, LinearLayout.LayoutParams(0, dp(58), 1f).apply { rightMargin = dp(7) })
        categories.addView(actionButton(if (activeCategory == "LAVORO") "💼  LAVORO  ✓" else "💼  LAVORO") {
            activeCategory = "LAVORO"
            showHome()
            toast("Archivio attivo: LAVORO")
        }, LinearLayout.LayoutParams(0, dp(58), 1f).apply { leftMargin = dp(7) })
        root.addView(categories, LinearLayout.LayoutParams(-1, dp(58)).apply {
            bottomMargin = dp(6)
        })

        val captureButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        captureButtons.addView(actionButton("📷  SCATTA FOTO") {
            launchCamera()
        }, LinearLayout.LayoutParams(0, dp(58), 1f).apply { rightMargin = dp(7) })
        captureButtons.addView(actionButton("🎥  REGISTRA VIDEO") {
            launchVideoCamera()
        }, LinearLayout.LayoutParams(0, dp(58), 1f).apply { leftMargin = dp(7) })
        root.addView(captureButtons, LinearLayout.LayoutParams(-1, dp(58)).apply {
            bottomMargin = dp(8)
        })

        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.fa_home_image)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Fotocamera vintage su album fotografico"
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(TextView(this).apply {
            text = "Creato da M@xm@so74 con ChatGPT"
            textSize = 16f
            typeface = android.graphics.Typeface.create("cursive", android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(0xFFE5D8C8.toInt())
            setPadding(0, dp(3), 0, dp(10))
        }, LinearLayout.LayoutParams(-1, -2))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        bottom.addView(actionButton("▧  GESTIONE FOTO\nE CARTELLE") {
            showFileManager()
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(7) })
        bottom.addView(actionButton("⇩  ESPORTA SU PC /\nZIP") {
            showExportOptions()
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(7) })
        root.addView(bottom, LinearLayout.LayoutParams(-1, dp(48)))

        setContentView(root)
    }

    private fun launchCamera() {
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "FA_TEMP_$stamp.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FA/INBOX/")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                toast("Non riesco a preparare il file per la fotocamera")
                showHome()
                return
            }
            pendingPhoto = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            pendingPhoto?.let { contentResolver.delete(it, null, null) }
            pendingPhoto = null
            toast("Fotocamera non disponibile: ${e.localizedMessage ?: "errore"}")
            showHome()
        }
    }

    private fun launchVideoCamera() {
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "FA_TEMP_$stamp.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FA/INBOX/")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                toast("Non riesco a preparare il file per la videocamera")
                showHome()
                return
            }
            pendingVideo = uri
            videoLauncher.launch(uri)
        } catch (e: Exception) {
            pendingVideo?.let { contentResolver.delete(it, null, null) }
            pendingVideo = null
            toast("Videocamera non disponibile: ${e.localizedMessage ?: "errore"}")
            showHome()
        }
    }

    private fun folderKey(category: String) = "folders_$category"
    private fun lastFolderKey(category: String) = "last_folder_$category"

    // I percorsi sono relativi alla categoria, ad esempio AMMEGA/CARTEGGIATRICE/RECINZIONE.
    private fun folders(category: String): MutableList<String> {
        val saved = prefs.getStringSet(folderKey(category), setOf("Generale"))?.toMutableSet()
            ?: mutableSetOf("Generale")
        val expanded = mutableSetOf<String>()
        saved.forEach { raw ->
            val clean = raw.trim('/').split('/').filter { it.isNotBlank() }
            var path = ""
            clean.forEach { part ->
                path = if (path.isEmpty()) safeFolder(part) else path + "/" + safeFolder(part)
                expanded.add(path)
            }
        }
        if (expanded.isEmpty()) expanded.add("Generale")
        return expanded.sorted().toMutableList()
    }

    private fun saveFolderList(category: String, list: List<String>) {
        prefs.edit().putStringSet(folderKey(category), list.toSet()).apply()
    }

    private fun folderChildren(category: String, parent: String): List<String> {
        val prefix = if (parent.isBlank()) "" else parent.trimEnd('/') + "/"
        return folders(category).mapNotNull { path ->
            if (!path.startsWith(prefix) || path == parent) null
            else path.removePrefix(prefix).substringBefore('/').takeIf { it.isNotBlank() }
        }.distinct().sorted()
    }

    private fun addFolderPath(category: String, parent: String, child: String): String {
        val part = safeFolder(child)
        val full = if (parent.isBlank()) part else parent.trimEnd('/') + "/" + part
        val all = folders(category).toMutableSet()
        var current = ""
        full.split('/').forEach { segment ->
            current = if (current.isEmpty()) segment else current + "/" + segment
            all.add(current)
        }
        saveFolderList(category, all.toList())
        return full
    }

    private fun safeFolder(value: String): String =
        value.trim().replace(Regex("[\\\\/:*?<>|]"), "_").replace("\"", "_").ifBlank { "Generale" }

    private fun showNextImportedItem() {
        if (importQueue.isEmpty()) {
            showExplorerFolder()
            return
        }
        val uri = importQueue.removeAt(0)
        val mime = contentResolver.getType(uri) ?: ""
        val isVideo = mime.startsWith("video/")
        if (!isVideo && !mime.startsWith("image/")) {
            toast("Formato non supportato: elemento ignorato")
            showNextImportedItem()
            return
        }
        showNamingDialog(uri, isVideo, true)
    }

    private fun mediaDisplayName(uri: Uri): String {
        var result = "Elemento"
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) result = cursor.getString(0) ?: result
        }
        return result
    }

    private fun showNamingDialog(uri: Uri, isVideo: Boolean = false, isImported: Boolean = false) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val preview = ImageView(this).apply {
            if (isVideo) setImageResource(android.R.drawable.ic_media_play) else setImageURI(uri)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            adjustViewBounds = true
            contentDescription = if (isVideo) "Video appena registrato" else "Anteprima della foto appena scattata"
        }
        content.setBackgroundColor(0xFF17232C.toInt())
        val sourceName = if (isImported) mediaDisplayName(uri) else ""
        val sourceExtension = sourceName.substringAfterLast('.', if (isVideo) "mp4" else "jpg")
            .let { it.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "").ifBlank { if (isVideo) "mp4" else "jpg" } }
        val sourceBaseName = if (isImported) sourceName.substringBeforeLast('.', sourceName) else ""
        val name = EditText(this).apply {
            hint = if (isVideo) "Scrivi il nome del video" else "Scrivi il nome della foto"
            if (isImported) setText(sourceBaseName)
            isSingleLine = true
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFB8C7D2.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minHeight = dp(52)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF263744.toInt())
                cornerRadius = dp(9).toFloat()
                setStroke(dp(1), 0xFF9DB9D2.toInt())
            }
            backgroundTintList = null
            highlightColor = 0xFF496F91.toInt()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val categorySpinner = Spinner(this)
        val folderSpinner = Spinner(this)
        fun darkSpinnerAdapter(items: List<String>) = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                TextView(this@MainActivity).apply {
                    text = getItem(position) ?: ""
                    textSize = 16f
                    setTextColor(0xFFF5F1E9.toInt())
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setBackgroundColor(0xFF263744.toInt())
                }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                TextView(this@MainActivity).apply {
                    text = getItem(position) ?: ""
                    textSize = 16f
                    setTextColor(0xFFF5F1E9.toInt())
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    setBackgroundColor(0xFF263744.toInt())
                }
        }
        val addFolder = actionButton("＋ Nuova sottocartella") { }

        fun refreshFolders(select: String? = null) {
            val category = categorySpinner.selectedItem?.toString() ?: activeCategory
            val list = folders(category)
            folderSpinner.adapter = darkSpinnerAdapter(list)
            val preferred = select ?: prefs.getString(lastFolderKey(category), "Generale")
            val index = list.indexOf(preferred)
            folderSpinner.setSelection(if (index >= 0) index else 0)
        }

        categorySpinner.adapter = darkSpinnerAdapter(listOf("PERSONALE", "LAVORO"))
        categorySpinner.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF9DB9D2.toInt())
        folderSpinner.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF9DB9D2.toInt())
        categorySpinner.setSelection(if (activeCategory == "LAVORO") 1 else 0)
        refreshFolders()
        categorySpinner.onItemSelectedListener = SimpleItemSelectedListener { refreshFolders() }
        addFolder.setOnClickListener {
            val input = darkEditText("Nome nuova sottocartella")
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Nuova sottocartella")
                .setView(input)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Crea") { _, _ ->
                    val category = categorySpinner.selectedItem.toString()
                    val parent = folderSpinner.selectedItem?.toString() ?: ""
                    val newName = input.text.toString().trim()
                    if (newName.isNotBlank()) refreshFolders(addFolderPath(category, parent, newName))
                }.show()
        }

        content.addView(preview, LinearLayout.LayoutParams(-1, dp(190)).apply {
            bottomMargin = dp(12)
        })
        content.addView(TextView(this).apply { text = "ARCHIVIA IN"; textSize = 12f; setTextColor(0xFFB9C4CF.toInt()) })
        content.addView(categorySpinner)
        content.addView(folderSpinner)
        content.addView(addFolder)
        content.addView(TextView(this).apply {
            text = if (isVideo) "NOME VIDEO" else "NOME FOTO"
            textSize = 12f
            setTextColor(0xFFB9C4CF.toInt())
            setPadding(0, dp(12), 0, 0)
        })
        content.addView(name)

        val dialogTitle = TextView(this).apply {
            text = if (isVideo) "Dai un nome al video" else "Dai un nome alla foto"
            textSize = 22f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(0xFFF5F1E9.toInt())
            setPadding(dp(24), dp(18), dp(24), dp(8))
            setBackgroundColor(0xFF17232C.toInt())
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setCustomTitle(dialogTitle)
            .setView(content)
            .setCancelable(false)
            .setNegativeButton("ELIMINA", null)
            .setPositiveButton("SALVA", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(0xFF17232C.toInt()))
            listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE).forEach { which ->
                dialog.getButton(which).apply {
                    setTextColor(0xFFF5F1E9.toInt())
                    background = android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(0xFF294968.toInt(), 0xFF142B43.toInt())
                    ).apply { cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0xFF9DB9D2.toInt()) }
                    backgroundTintList = null
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                if (!isImported) {
                    contentResolver.delete(uri, null, null)
                    if (isVideo) pendingVideo = null else pendingPhoto = null
                }
                dialog.dismiss()
                if (isImported) showNextImportedItem()
                else if (isVideo) launchVideoCamera() else launchCamera()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val baseName = safeFolder(name.text.toString())
                if (baseName == "Generale" && name.text.toString().isBlank()) {
                    name.error = "Inserisci un nome"
                    return@setOnClickListener
                }
                val category = categorySpinner.selectedItem.toString()
                val folder = safeFolder(folderSpinner.selectedItem?.toString() ?: "Generale")
                val extension = ".$sourceExtension"
                val fileName = if (baseName.lowercase(Locale.ROOT).endsWith(extension)) baseName else "$baseName$extension"
                val mediaRoot = if (isVideo) "Movies" else "Pictures"
                val path = "$mediaRoot/FA/$category/$folder/"
                val mimeType = contentResolver.getType(uri) ?: if (isVideo) "video/mp4" else "image/jpeg"
                prefs.edit().putString(lastFolderKey(category), folder).apply()
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                }
                val saved = if (isImported) {
                    val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    val destination = contentResolver.insert(collection, values)
                    if (destination == null) false else try {
                        val input = contentResolver.openInputStream(uri)
                        val output = contentResolver.openOutputStream(destination, "w")
                        if (input == null || output == null) {
                            input?.close()
                            output?.close()
                            contentResolver.delete(destination, null, null)
                            false
                        } else {
                            input.use { source -> output.use { target -> source.copyTo(target) } }
                            true
                        }
                    } catch (_: Exception) {
                        contentResolver.delete(destination, null, null)
                        false
                    }
                } else {
                    contentResolver.update(uri, values, null, null) > 0
                }
                if (saved) {
                    activeCategory = category
                    if (!isImported) {
                        if (isVideo) pendingVideo = null else pendingPhoto = null
                    }
                    dialog.dismiss()
                    toast("${if (isVideo) "Video" else "Foto"} importato/salvato in $category/$folder")
                    if (isImported) showNextImportedItem()
                    else if (isVideo) launchVideoCamera() else launchCamera()
                } else {
                    toast("Non riesco a salvare l'elemento. L'originale resta disponibile.")
                }
            }
            name.requestFocus()
            name.postDelayed({
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(name, InputMethodManager.SHOW_IMPLICIT)
            }, 250)
        }
        dialog.show()
    }

    private data class PhotoItem(val uri: Uri, val name: String, val path: String, val isVideo: Boolean = false)

    private fun listPhotos(): List<PhotoItem> {
        val result = mutableListOf<PhotoItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        fun collect(collection: Uri, isVideo: Boolean, root: String) {
            contentResolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ?",
                arrayOf("$root/FA/%", "$root/FA/INBOX/%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    result.add(
                        PhotoItem(
                            Uri.withAppendedPath(collection, id.toString()),
                            cursor.getString(nameCol) ?: if (isVideo) "Video" else "Foto",
                            cursor.getString(pathCol) ?: "",
                            isVideo
                        )
                    )
                }
            }
        }
        collect(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, "Pictures")
        collect(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, "Movies")
        return result
    }

    private fun isInCategory(path: String, category: String): Boolean =
        path.startsWith("Pictures/FA/$category/") || path.startsWith("Movies/FA/$category/")

    private fun categoryFolderPath(item: PhotoItem, category: String, folder: String): String {
        val root = if (item.isVideo) "Movies" else "Pictures"
        return "$root/FA/$category/$folder/"
    }

    private fun showFileManager() {
        selectedPhotos.clear()
        val root = page("Archivio FA")
        root.addView(TextView(this).apply {
            text = "Scegli l'archivio da esplorare"
            textSize = 15f
            setTextColor(0xFFB9C4CF.toInt())
            setPadding(0, 0, 0, dp(12))
        })
        root.addView(actionButton("PERSONALE") {
            explorerCategory = "PERSONALE"
            showFolderList(explorerCategory)
        }, LinearLayout.LayoutParams(-1, dp(54)))
        root.addView(actionButton("LAVORO") {
            explorerCategory = "LAVORO"
            showFolderList(explorerCategory)
        }, LinearLayout.LayoutParams(-1, dp(54)))
        root.addView(actionButton("IMPORTA FOTO E VIDEO DA DISPOSITIVO") {
            importLauncher.launch(arrayOf("image/*", "video/*"))
        }, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(actionButton("GESTISCI SOTTOCARTELLE") { manageFolders() },
            LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(actionButton("ESPORTA ARCHIVIO ZIP") { exportZip(listPhotos(), "FA-Archivio") },
            LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(actionButton("COLLEGA AL PC VIA USB") {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Trasferimento USB al PC")
                .setMessage("Collega il Motorola al PC con un cavo USB dati. Nella notifica USB scegli «Trasferimento file». In Windows apri Esplora file → Motorola → Memoria interna condivisa. Le foto sono in Pictures → FA e i video in Movies → FA: copia PERSONALE e LAVORO da entrambe le posizioni.")
                .setPositiveButton("OK", null).show()
        }, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(actionButton("INDIETRO") { showHome() }, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(TextView(this).apply {
            text = "FA  •  ${listPhotos().size} elementi archiviati"
            textSize = 13f
            setTextColor(0xFFB9C4CF.toInt())
            setPadding(0, dp(12), 0, 0)
        })
        setContentView(root)
    }

    private fun showFolderList(category: String) {
        explorerCategory = category
        explorerFolder = ""
        prefs.edit().putString(lastFolderKey(category), "").apply()
        showExplorerFolder()
    }

    private fun createChildFolder(parent: String) {
        val input = darkEditText("Nome nuova cartella")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (parent.isBlank()) "Nuova cartella" else "Nuova sottocartella in " + parent)
            .setView(input)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Crea") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    toast("Inserisci un nome")
                } else {
                    explorerFolder = addFolderPath(explorerCategory, parent, name)
                    prefs.edit().putString(lastFolderKey(explorerCategory), explorerFolder).apply()
                    showExplorerFolder()
                }
            }.show()
    }

    private fun showExplorerFolder() {
        val root = page(explorerCategory)
        val current = explorerFolder.trim('/')
        val titlePath = if (current.isBlank()) "Archivio " + explorerCategory
            else "Archivio " + explorerCategory + " / " + current
        root.addView(TextView(this).apply {
            text = titlePath
            textSize = 15f
            setTextColor(0xFFF1E9DD.toInt())
            setPadding(0, 0, 0, dp(8))
        })
        val crumbs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val parts = current.split('/').filter { it.isNotBlank() }
        val crumbLabels = mutableListOf("⌂")
        var built = ""
        parts.forEach { part ->
            built = if (built.isEmpty()) part else built + "/" + part
            crumbLabels.add(built)
        }
        crumbLabels.forEachIndexed { index, value ->
            crumbs.addView(actionButton(if (index == 0) "⌂" else parts[index - 1]) {
                explorerFolder = if (index == 0) "" else value
                prefs.edit().putString(lastFolderKey(explorerCategory), explorerFolder).apply()
                showExplorerFolder()
            }, LinearLayout.LayoutParams(-2, dp(40)).apply { rightMargin = dp(4) })
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(crumbs)
        }, LinearLayout.LayoutParams(-1, dp(46)))
        root.addView(actionButton("＋ NUOVA SOTTOCARTELLA") {
            createChildFolder(current)
        }, LinearLayout.LayoutParams(-1, dp(46)))
        root.addView(actionButton("⬇ IMPORTA FOTO E VIDEO QUI") {
            activeCategory = explorerCategory
            prefs.edit().putString(lastFolderKey(explorerCategory), current).apply()
            importLauncher.launch(arrayOf("image/*", "video/*"))
        }, LinearLayout.LayoutParams(-1, dp(48)))
        if (current.isNotBlank()) {
            root.addView(actionButton("↑ CARTELLA SUPERIORE") {
                explorerFolder = current.substringBeforeLast('/', "")
                prefs.edit().putString(lastFolderKey(explorerCategory), explorerFolder).apply()
                showExplorerFolder()
            }, LinearLayout.LayoutParams(-1, dp(42)))
        } else {
            root.addView(actionButton("← GESTIONE ARCHIVIO") { showFileManager() },
                LinearLayout.LayoutParams(-1, dp(42)))
        }

        val children = folderChildren(explorerCategory, current)
        val allItems = listPhotos().filter { isInCategory(it.path, explorerCategory) }
        val exactRelative = current.trim('/')
        val photos = allItems.filter {
            val relative = it.path.substringAfter("/FA/" + explorerCategory + "/", "").trimEnd('/')
            relative == exactRelative
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
        val descendantCount = allItems.count {
            val relative = it.path.substringAfter("/FA/" + explorerCategory + "/", "").trimEnd('/')
            if (exactRelative.isBlank()) relative.isNotBlank()
            else relative.startsWith(exactRelative + "/")
        }
        root.addView(TextView(this).apply {
            text = children.size.toString() + " cartelle  •  " + photos.count { !it.isVideo } +
                " foto  •  " + photos.count { it.isVideo } + " video" +
                (if (descendantCount > 0) "  •  " + descendantCount + " elementi nelle sottocartelle" else "")
            textSize = 12f
            setTextColor(0xFFC7B9A7.toInt())
            setPadding(0, dp(6), 0, dp(6))
        })
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        children.forEach { child ->
            val fullPath = if (current.isBlank()) child else current + "/" + child
            val count = allItems.count {
                val relative = it.path.substringAfter("/FA/" + explorerCategory + "/", "").trimEnd('/')
                relative == fullPath || relative.startsWith(fullPath + "/")
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }
            row.addView(TextView(this).apply {
                text = "📁"
                textSize = 27f
                setPadding(0, 0, dp(12), 0)
            })
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(this).apply {
                text = child
                textSize = 16f
                setTextColor(0xFFF1E9DD.toInt())
            })
            labels.addView(TextView(this).apply {
                text = count.toString() + " elementi"
                textSize = 12f
                setTextColor(0xFFC7B9A7.toInt())
            })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(actionButton("APRI") {
                explorerFolder = fullPath
                prefs.edit().putString(lastFolderKey(explorerCategory), fullPath).apply()
                showExplorerFolder()
            })
            row.addView(actionButton("⋮") { folderActions(fullPath) })
            list.addView(row)
            list.addView(View(this).apply { setBackgroundColor(0xFF3D4B57.toInt()) },
                LinearLayout.LayoutParams(-1, dp(1)))
        }
        photos.forEach { photo ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val thumb = ImageView(this).apply {
                if (photo.isVideo) {
                    setImageResource(android.R.drawable.ic_media_play)
                    setBackgroundColor(0xFF263744.toInt())
                    scaleType = ImageView.ScaleType.CENTER
                } else {
                    setImageURI(photo.uri)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                contentDescription = photo.name
            }
            row.addView(thumb, LinearLayout.LayoutParams(dp(66), dp(66)))
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(TextView(this).apply {
                text = photo.name
                textSize = 14f
                setTextColor(0xFFF1E9DD.toInt())
            })
            details.addView(actionButton("OPZIONI") { showPhotoActions(photo) })
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(CheckBox(this).apply {
                isChecked = selectedPhotos.contains(photo.uri.toString())
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedPhotos.add(photo.uri.toString())
                    else selectedPhotos.remove(photo.uri.toString())
                }
            })
            list.addView(row)
            list.addView(View(this).apply { setBackgroundColor(0xFF3D4B57.toInt()) },
                LinearLayout.LayoutParams(-1, dp(1)))
        }
        if (children.isEmpty() && photos.isEmpty()) list.addView(TextView(this).apply {
            text = "Questa cartella è vuota. Puoi creare una sottocartella con il pulsante qui sopra."
            textSize = 15f
            setTextColor(0xFFF1E9DD.toInt())
            setPadding(0, dp(18), 0, 0)
        })
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.fa_home_image)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Fotocamera vintage su album fotografico"
        }, LinearLayout.LayoutParams(-1, 0, 0.45f))
        root.addView(TextView(this).apply {
            text = "Creato da M@xm@so74 con Chatgpt"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFFC7B9A7.toInt())
            setPadding(0, dp(4), 0, dp(6))
        }, LinearLayout.LayoutParams(-1, -2))
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(42))
        }
        bottom.addView(actionButton("AZIONI SELEZIONATE") { showBulkPhotoActions() },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(6) })
        bottom.addView(actionButton("CARTELLE") { showFolderList(explorerCategory) },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(6) })
        root.addView(bottom)
        setContentView(root)
    }

    private fun folderActions(path: String) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(path)
            .setItems(arrayOf("Rinomina", "Elimina cartella")) { _, which ->
                if (which == 0) renameFolderPath(path) else deleteFolderPath(path)
            }.show()
    }

    private fun renameFolderPath(oldPath: String) {
        val input = darkEditText("Nuovo nome", oldPath.substringAfterLast('/')).apply { selectAll() }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Rinomina cartella")
            .setView(input)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Rinomina") { _, _ ->
                val newPart = safeFolder(input.text.toString())
                val parent = oldPath.substringBeforeLast('/', "")
                val newPath = if (parent.isBlank()) newPart else parent + "/" + newPart
                if (newPath != oldPath) {
                    val all = folders(explorerCategory).toMutableSet()
                    val changed = all.map { value ->
                        if (value == oldPath || value.startsWith(oldPath + "/"))
                            newPath + value.removePrefix(oldPath) else value
                    }.toSet()
                    saveFolderList(explorerCategory, changed.toList())
                    listPhotos().filter { isInCategory(it.path, explorerCategory) }.forEach { item ->
                        val relative = item.path.substringAfter("/FA/" + explorerCategory + "/", "").trimEnd('/')
                        if (relative == oldPath || relative.startsWith(oldPath + "/")) {
                            val rootName = if (item.isVideo) "Movies" else "Pictures"
                            val updatedPath = rootName + "/FA/" + explorerCategory + "/" +
                                newPath + relative.removePrefix(oldPath) + "/"
                            contentResolver.update(item.uri, ContentValues().apply {
                                put(MediaStore.MediaColumns.RELATIVE_PATH, updatedPath)
                            }, null, null)
                        }
                    }
                    if (explorerFolder == oldPath || explorerFolder.startsWith(oldPath + "/"))
                        explorerFolder = newPath + explorerFolder.removePrefix(oldPath)
                    prefs.edit().putString(lastFolderKey(explorerCategory), explorerFolder).apply()
                }
                showExplorerFolder()
            }.show()
    }

    private fun deleteFolderPath(path: String) {
        val hasChildren = folders(explorerCategory).any { it.startsWith(path + "/") }
        val hasMedia = listPhotos().any { item ->
            if (!isInCategory(item.path, explorerCategory)) false
            else {
                val relative = item.path.substringAfter("/FA/" + explorerCategory, "").trim('/')
                relative == path || relative.startsWith(path + "/")
            }
        }
        if (hasChildren || hasMedia) {
            toast("Cartella non vuota: elimina prima il contenuto e le sottocartelle")
            return
        }
        saveFolderList(explorerCategory, folders(explorerCategory).filterNot {
            it == path || it.startsWith(path + "/")
        })
        showExplorerFolder()
    }

    private fun showBulkPhotoActions() {
        val chosen = listPhotos().filter { selectedPhotos.contains(it.uri.toString()) }
        if (chosen.isEmpty()) { toast("Seleziona prima uno o più elementi"); return }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("${chosen.size} elementi selezionati")
            .setItems(arrayOf("Esporta selezione in ZIP", "Elimina selezione")) { _, which ->
                if (which == 0) exportZip(chosen, "FA-Selezione")
                else AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setMessage("Eliminare definitivamente gli elementi selezionati?")
                    .setNegativeButton("Annulla", null)
                    .setPositiveButton("Elimina") { _, _ ->
                        chosen.forEach { contentResolver.delete(it.uri, null, null) }
                        selectedPhotos.clear()
                        showExplorerFolder()
                    }.show()
            }.show()
    }

    private fun showPhotoActions(photo: PhotoItem) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(photo.name)
            .setItems(arrayOf("Apri", "Rinomina", "Sposta in un'altra cartella", "Elimina")) { _, which ->
                when (which) {
                    0 -> openMediaItem(photo)
                    1 -> renamePhoto(photo)
                    2 -> movePhoto(photo)
                    3 -> AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setMessage("Eliminare definitivamente questo elemento?")
                        .setNegativeButton("Annulla", null)
                        .setPositiveButton("Elimina") { _, _ ->
                            contentResolver.delete(photo.uri, null, null)
                            showFileManager()
                        }.show()
                }
            }.show()
    }

    private fun openMediaItem(item: PhotoItem) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(item.uri, if (item.isVideo) "video/*" else "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            toast("Non trovo un'app adatta per aprire questo elemento")
        }
    }

    private fun renamePhoto(photo: PhotoItem) {
        val input = darkEditText(initialText = photo.name.substringBeforeLast('.')).apply { selectAll() }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Rinomina elemento")
            .setView(input)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva") { _, _ ->
                val base = safeFolder(input.text.toString())
                val extension = if (photo.isVideo) ".mp4" else ".jpg"
                val file = if (base.lowercase(Locale.ROOT).endsWith(extension)) base else "$base$extension"
                val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, file) }
                contentResolver.update(photo.uri, values, null, null)
                showFileManager()
            }.show()
    }

    private fun movePhoto(photo: PhotoItem) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        fun darkAdapter(values: List<String>) = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item, values
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                TextView(this@MainActivity).apply {
                    text = getItem(position) ?: ""
                    setTextColor(0xFFF5F1E9.toInt())
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setBackgroundColor(0xFF263744.toInt())
                }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                TextView(this@MainActivity).apply {
                    text = getItem(position) ?: ""
                    setTextColor(0xFFF5F1E9.toInt())
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    setBackgroundColor(0xFF263744.toInt())
                }
        }
        val category = Spinner(this)
        category.adapter = darkAdapter(listOf("PERSONALE", "LAVORO"))
        val folder = Spinner(this)
        fun refresh() {
            val values = folders(category.selectedItem.toString())
            folder.adapter = darkAdapter(values)
        }
        category.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF9DB9D2.toInt())
        folder.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF9DB9D2.toInt())
        category.onItemSelectedListener = SimpleItemSelectedListener { refresh() }
        refresh()
        layout.addView(category)
        layout.addView(folder)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Sposta elemento")
            .setView(layout)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Sposta") { _, _ ->
                val path = categoryFolderPath(photo, category.selectedItem.toString(), folder.selectedItem.toString())
                val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, path) }
                contentResolver.update(photo.uri, values, null, null)
                showFileManager()
            }.show()
    }

    private fun manageFolders() {
        val choices = mutableListOf<Pair<String, String>>()
        listOf("PERSONALE", "LAVORO").forEach { category ->
            folders(category).forEach { folder -> choices.add(category to folder) }
        }
        if (choices.isEmpty()) {
            toast("Non ci sono cartelle da gestire")
            return
        }
        val labels = choices.map { it.first + "  /  " + it.second }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Gestisci cartelle")
            .setItems(labels) { _, index ->
                explorerCategory = choices[index].first
                folderActions(choices[index].second)
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun showExportOptions() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Esporta archivio")
            .setItems(arrayOf("Tutto l'archivio in ZIP", "Solo PERSONALE in ZIP", "Solo LAVORO in ZIP", "Istruzioni collegamento USB")) { _, which ->
                val all = listPhotos()
                when (which) {
                    0 -> exportZip(all, "FA-Archivio")
                    1 -> exportZip(all.filter { isInCategory(it.path, "PERSONALE") }, "FA-PERSONALE")
                    2 -> exportZip(all.filter { isInCategory(it.path, "LAVORO") }, "FA-LAVORO")
                    3 -> AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Trasferimento USB al PC")
                        .setMessage("Collega il Motorola al PC con un cavo USB dati. Nella notifica USB scegli «Trasferimento file». In Windows apri Esplora file → Motorola → Memoria interna condivisa. Le foto sono in Pictures → FA e i video in Movies → FA; copia le cartelle PERSONALE e LAVORO da entrambe le posizioni.")
                        .setPositiveButton("OK", null).show()
                }
            }.show()
    }

    private fun exportZip(items: List<PhotoItem>, baseName: String) {
        if (items.isEmpty()) { toast("Non ci sono elementi da esportare"); return }
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "${baseName}_$stamp.zip")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/FA-Export/")
            }
            val zipUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (zipUri == null) { toast("Non riesco a creare il file ZIP"); return }
            val entries = items.map { photo ->
                Triple(photo.uri, photo.path.substringAfter("/FA/").trimEnd('/'), photo.name)
            }
            FaZipExporter.write(contentResolver, zipUri, entries)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, zipUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Esporta archivio ZIP"))
            toast("ZIP creato in Download/FA-Export")
        } catch (e: Exception) {
            toast("Errore esportazione: ${e.localizedMessage ?: "operazione non riuscita"}")
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

private class SimpleItemSelectedListener(
    private val action: () -> Unit
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    override fun onItemSelected(
        parent: android.widget.AdapterView<*>?,
        view: View?,
        position: Int,
        id: Long
    ) = action()
}
