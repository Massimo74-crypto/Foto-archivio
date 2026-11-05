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
    private val selectedPhotos = linkedSetOf<String>()
    private var explorerCategory = "PERSONALE"
    private var explorerFolder = "Generale"
    private var sortMode = "Data"
    private val prefs by lazy { getSharedPreferences("fa_settings", Context.MODE_PRIVATE) }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingPhoto
        if (ok && uri != null) showNamingDialog(uri)
        else {
            uri?.let { contentResolver.delete(it, null, null) }
            pendingPhoto = null
            toast("Scatto annullato")
            showHome()
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
        root.setPadding(dp(18), dp(12), dp(18), dp(14))

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
        categories.addView(actionButton("👤  PERSONALE   ←") {
            activeCategory = "PERSONALE"
            launchCamera()
        }, LinearLayout.LayoutParams(0, dp(70), 1f).apply { rightMargin = dp(7) })
        categories.addView(actionButton("💼  LAVORO   ←") {
            activeCategory = "LAVORO"
            launchCamera()
        }, LinearLayout.LayoutParams(0, dp(70), 1f).apply { leftMargin = dp(7) })
        root.addView(categories, LinearLayout.LayoutParams(-1, dp(70)).apply {
            bottomMargin = dp(8)
        })

        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.fa_launcher_icon_image)
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
        }, LinearLayout.LayoutParams(0, dp(78), 1f).apply { rightMargin = dp(7) })
        bottom.addView(actionButton("⇩  ESPORTA SU PC /\nZIP") {
            showExportOptions()
        }, LinearLayout.LayoutParams(0, dp(78), 1f).apply { leftMargin = dp(7) })
        root.addView(bottom, LinearLayout.LayoutParams(-1, dp(78)))

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

    private fun folderKey(category: String) = "folders_$category"
    private fun lastFolderKey(category: String) = "last_folder_$category"

    private fun folders(category: String): MutableList<String> {
        val saved = prefs.getStringSet(folderKey(category), setOf("Generale"))?.toMutableList()
            ?: mutableListOf("Generale")
        if (saved.isEmpty()) saved.add("Generale")
        return saved.sorted().toMutableList()
    }

    private fun saveFolderList(category: String, list: List<String>) {
        prefs.edit().putStringSet(folderKey(category), list.toSet()).apply()
    }

    private fun safeFolder(value: String): String =
        value.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Generale" }

    private fun showNamingDialog(uri: Uri) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val preview = ImageView(this).apply {
            setImageURI(uri)
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = true
            contentDescription = "Anteprima della foto appena scattata"
        }
        val name = EditText(this).apply {
            hint = "Nome della foto"
            isSingleLine = true
            textSize = 18f
        }
        val categorySpinner = Spinner(this)
        val folderSpinner = Spinner(this)
        val addFolder = Button(this).apply { text = "＋ Nuova sottocartella"; isAllCaps = false }

        fun refreshFolders(select: String? = null) {
            val category = categorySpinner.selectedItem?.toString() ?: activeCategory
            val list = folders(category)
            folderSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, list)
            val preferred = select ?: prefs.getString(lastFolderKey(category), "Generale")
            val index = list.indexOf(preferred)
            folderSpinner.setSelection(if (index >= 0) index else 0)
        }

        categorySpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, listOf("PERSONALE", "LAVORO")
        )
        categorySpinner.setSelection(if (activeCategory == "LAVORO") 1 else 0)
        refreshFolders()
        categorySpinner.onItemSelectedListener = SimpleItemSelectedListener { refreshFolders() }
        addFolder.setOnClickListener {
            val input = EditText(this).apply { hint = "Nome nuova sottocartella" }
            AlertDialog.Builder(this)
                .setTitle("Nuova sottocartella")
                .setView(input)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Crea") { _, _ ->
                    val category = categorySpinner.selectedItem.toString()
                    val newName = safeFolder(input.text.toString())
                    val list = folders(category)
                    if (newName.isNotBlank() && !list.contains(newName)) {
                        list.add(newName)
                        saveFolderList(category, list)
                        refreshFolders(newName)
                    }
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
            text = "NOME FOTO"
            textSize = 12f
            setTextColor(0xFFB9C4CF.toInt())
            setPadding(0, dp(12), 0, 0)
        })
        content.addView(name)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Dai un nome alla foto")
            .setView(content)
            .setCancelable(false)
            .setNegativeButton("ELIMINA", null)
            .setPositiveButton("SALVA", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                contentResolver.delete(uri, null, null)
                pendingPhoto = null
                dialog.dismiss()
                launchCamera()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val baseName = safeFolder(name.text.toString())
                if (baseName == "Generale" && name.text.toString().isBlank()) {
                    name.error = "Inserisci un nome"
                    return@setOnClickListener
                }
                val category = categorySpinner.selectedItem.toString()
                val folder = safeFolder(folderSpinner.selectedItem?.toString() ?: "Generale")
                val fileName = if (baseName.lowercase(Locale.ROOT).endsWith(".jpg")) baseName else "$baseName.jpg"
                val path = "Pictures/FA/$category/$folder/"
                prefs.edit().putString(lastFolderKey(category), folder).apply()
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.RELATIVE_PATH, path)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                }
                val updated = contentResolver.update(uri, values, null, null)
                if (updated > 0) {
                    activeCategory = category
                    pendingPhoto = null
                    dialog.dismiss()
                    toast("Foto salvata in $category/$folder")
                    launchCamera()
                } else {
                    toast("Non riesco a salvare il nome. La foto è ancora disponibile.")
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

    private data class PhotoItem(val uri: Uri, val name: String, val path: String)

    private fun listPhotos(): List<PhotoItem> {
        val result = mutableListOf<PhotoItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH
        )
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("Pictures/FA/%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                result.add(
                    PhotoItem(
                        Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()),
                        cursor.getString(nameCol) ?: "Foto",
                        cursor.getString(pathCol) ?: ""
                    )
                )
            }
        }
        return result
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
        root.addView(actionButton("GESTISCI SOTTOCARTELLE") { manageFolders() },
            LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(actionButton("ESPORTA ARCHIVIO ZIP") { exportZip(listPhotos(), "FA-Archivio") },
            LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(actionButton("COLLEGA AL PC VIA USB") {
            AlertDialog.Builder(this).setTitle("Trasferimento USB al PC")
                .setMessage("Collega il Motorola al PC con un cavo USB dati. Nella notifica USB scegli «Trasferimento file». In Windows apri Esplora file → Motorola → Memoria interna condivisa → Pictures → FA. Copia PERSONALE e LAVORO sul PC.")
                .setPositiveButton("OK", null).show()
        }, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(actionButton("INDIETRO") { showHome() }, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(TextView(this).apply {
            text = "Pictures/FA  •  ${listPhotos().size} foto archiviate"
            textSize = 13f
            setTextColor(0xFFB9C4CF.toInt())
            setPadding(0, dp(12), 0, 0)
        })
        setContentView(root)
    }

    private fun showFolderList(category: String) {
        explorerCategory = category
        val root = page(category).apply {
            setPadding(dp(22), dp(28), dp(22), dp(48))
        }
        root.addView(TextView(this).apply {
            text = "Pictures / FA / $category"
            textSize = 13f
            setTextColor(0xFFB9C4CF.toInt())
            setPadding(0, 0, 0, dp(12))
        })
        root.addView(actionButton("＋ NUOVA CARTELLA") {
            val input = EditText(this).apply { hint = "Nome nuova cartella" }
            AlertDialog.Builder(this).setTitle("Crea cartella").setView(input)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Crea") { _, _ ->
                    val newName = safeFolder(input.text.toString())
                    val values = folders(category)
                    if (newName !in values) { values.add(newName); saveFolderList(category, values) }
                    explorerFolder = newName
                    prefs.edit().putString(lastFolderKey(category), newName).apply()
                    showExplorerFolder()
                }.show()
        }, LinearLayout.LayoutParams(-1, dp(48)))
        val allPhotos = listPhotos().filter { it.path.startsWith("Pictures/FA/$category/") }
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        folders(category).forEach { folder ->
            val count = allPhotos.count { it.path.trimEnd('/') == "Pictures/FA/$category/$folder" }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setOnClickListener {
                    explorerFolder = folder
                    prefs.edit().putString(lastFolderKey(category), folder).apply()
                    showExplorerFolder()
                }
            }
            row.addView(TextView(this).apply { text = "📁"; textSize = 30f; setPadding(0, 0, dp(14), 0) })
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(this).apply { text = folder; textSize = 17f; setTextColor(0xFFF1E9DD.toInt()) })
            labels.addView(TextView(this).apply { text = "$count elementi"; textSize = 12f; setTextColor(0xFFC7B9A7.toInt()) })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply { text = "›"; textSize = 28f; setTextColor(0xFFC7B9A7.toInt()) })
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
            list.addView(View(this).apply { setBackgroundColor(0xFF3D4B57.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(actionButton("INDIETRO") { showFileManager() }, LinearLayout.LayoutParams(-1, dp(48)))
        setContentView(root)
    }

    private fun showExplorerFolder() {
        val root = page(explorerCategory)
        root.addView(TextView(this).apply {
            text = "Pictures / FA / $explorerCategory"
            textSize = 13f
            setTextColor(0xFFB9C4CF.toInt())
        })
        root.addView(actionButton("📁 CARTELLE") { showFolderList(explorerCategory) }, LinearLayout.LayoutParams(-1, dp(44)))
        root.addView(TextView(this).apply {
            text = "Cartella aperta: $explorerFolder"
            textSize = 15f
            setTextColor(0xFFF1E9DD.toInt())
            setPadding(0, dp(8), 0, dp(8))
        })
        val photos = listPhotos().filter {
            it.path.trimEnd('/') == "Pictures/FA/$explorerCategory/$explorerFolder"
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
        root.addView(TextView(this).apply {
            text = "${photos.size} foto  •  ${selectedPhotos.size} selezionate"
            textSize = 13f
            setPadding(0, dp(6), 0, dp(6))
        })
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        photos.forEach { photo ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val thumb = ImageView(this).apply {
                setImageURI(photo.uri)
                scaleType = ImageView.ScaleType.CENTER_CROP
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
        if (photos.isEmpty()) list.addView(TextView(this).apply {
            text = "Questa sottocartella è vuota."
            textSize = 16f
            setPadding(0, dp(18), 0, 0)
        })
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.fa_launcher_icon_image)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Album fotografico vintage con macchina fotografica"
        }, LinearLayout.LayoutParams(dp(278), dp(278)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(TextView(this).apply {
            text = "Creato da M@xm@so74 con Chatgpt"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFFC7B9A7.toInt())
            setPadding(0, dp(6), 0, dp(8))
        }, LinearLayout.LayoutParams(-1, -2))
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(18))
        }
        bottom.addView(actionButton("AZIONI SELEZIONATE") { showBulkPhotoActions() },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(6) })
        bottom.addView(actionButton("CARTELLE") { showFolderList(explorerCategory) },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(6) })
        root.addView(bottom)
        setContentView(root)
    }

    private fun showBulkPhotoActions() {
        val chosen = listPhotos().filter { selectedPhotos.contains(it.uri.toString()) }
        if (chosen.isEmpty()) { toast("Seleziona prima una o più foto"); return }
        AlertDialog.Builder(this).setTitle("${chosen.size} foto selezionate")
            .setItems(arrayOf("Esporta selezione in ZIP", "Elimina selezione")) { _, which ->
                if (which == 0) exportZip(chosen, "FA-Selezione")
                else AlertDialog.Builder(this).setMessage("Eliminare definitivamente le foto selezionate?")
                    .setNegativeButton("Annulla", null)
                    .setPositiveButton("Elimina") { _, _ ->
                        chosen.forEach { contentResolver.delete(it.uri, null, null) }
                        selectedPhotos.clear()
                        showExplorerFolder()
                    }.show()
            }.show()
    }

    private fun showPhotoActions(photo: PhotoItem) {
        AlertDialog.Builder(this)
            .setTitle(photo.name)
            .setItems(arrayOf("Rinomina", "Sposta in un'altra cartella", "Elimina")) { _, which ->
                when (which) {
                    0 -> renamePhoto(photo)
                    1 -> movePhoto(photo)
                    2 -> AlertDialog.Builder(this)
                        .setMessage("Eliminare definitivamente questa foto?")
                        .setNegativeButton("Annulla", null)
                        .setPositiveButton("Elimina") { _, _ ->
                            contentResolver.delete(photo.uri, null, null)
                            showFileManager()
                        }.show()
                }
            }.show()
    }

    private fun renamePhoto(photo: PhotoItem) {
        val input = EditText(this).apply {
            setText(photo.name.substringBeforeLast('.'))
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Rinomina foto")
            .setView(input)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva") { _, _ ->
                val base = safeFolder(input.text.toString())
                val file = if (base.lowercase(Locale.ROOT).endsWith(".jpg")) base else "$base.jpg"
                val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, file) }
                contentResolver.update(photo.uri, values, null, null)
                showFileManager()
            }.show()
    }

    private fun movePhoto(photo: PhotoItem) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        val category = Spinner(this)
        category.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("PERSONALE", "LAVORO"))
        val folder = Spinner(this)
        fun refresh() {
            val values = folders(category.selectedItem.toString())
            folder.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
        }
        category.onItemSelectedListener = SimpleItemSelectedListener { refresh() }
        refresh()
        layout.addView(category)
        layout.addView(folder)
        AlertDialog.Builder(this)
            .setTitle("Sposta foto")
            .setView(layout)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Sposta") { _, _ ->
                val path = "Pictures/FA/${category.selectedItem}/${folder.selectedItem}/"
                val values = ContentValues().apply { put(MediaStore.Images.Media.RELATIVE_PATH, path) }
                contentResolver.update(photo.uri, values, null, null)
                showFileManager()
            }.show()
    }

    private fun manageFolders() {
        val entries = mutableListOf<String>()
        listOf("PERSONALE", "LAVORO").forEach { cat ->
            folders(cat).forEach { entries.add("$cat  /  $it") }
        }
        AlertDialog.Builder(this)
            .setTitle("Sottocartelle")
            .setItems(entries.toTypedArray()) { _, index ->
                val selected = entries[index]
                val category = selected.substringBefore("  /  ")
                val oldName = selected.substringAfter("  /  ")
                val input = EditText(this).apply { setText(oldName); selectAll() }
                AlertDialog.Builder(this)
                    .setTitle("Rinomina sottocartella")
                    .setView(input)
                    .setNegativeButton("Annulla", null)
                    .setPositiveButton("Rinomina") { _, _ ->
                        val newName = safeFolder(input.text.toString())
                        if (newName != oldName) {
                            val values = ContentValues().apply {
                                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FA/$category/$newName/")
                            }
                            contentResolver.update(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                values,
                                "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
                                arrayOf("Pictures/FA/$category/$oldName/")
                            )
                            val updated = folders(category).filterNot { it == oldName } + newName
                            saveFolderList(category, updated)
                        }
                        showFileManager()
                    }.setNeutralButton("Elimina lista") { _, _ ->
                        val hasPhotos = listPhotos().any { it.path == "Pictures/FA/$category/$oldName/" }
                        if (hasPhotos) toast("Sottocartella non vuota: sposta o elimina prima le foto")
                        else saveFolderList(category, folders(category).filterNot { it == oldName })
                        showFileManager()
                    }.show()
            }.setNegativeButton("Chiudi", null).show()
    }

    private fun showExportOptions() {
        AlertDialog.Builder(this).setTitle("Esporta archivio")
            .setItems(arrayOf("Tutto l'archivio in ZIP", "Solo PERSONALE in ZIP", "Solo LAVORO in ZIP", "Istruzioni collegamento USB")) { _, which ->
                val all = listPhotos()
                when (which) {
                    0 -> exportZip(all, "FA-Archivio")
                    1 -> exportZip(all.filter { it.path.startsWith("Pictures/FA/PERSONALE/") }, "FA-PERSONALE")
                    2 -> exportZip(all.filter { it.path.startsWith("Pictures/FA/LAVORO/") }, "FA-LAVORO")
                    3 -> AlertDialog.Builder(this).setTitle("Trasferimento USB al PC")
                        .setMessage("Collega il Motorola al PC con un cavo USB dati. Nella notifica USB scegli «Trasferimento file». In Windows apri Esplora file → Motorola → Memoria interna condivisa → Pictures → FA e copia le cartelle PERSONALE e LAVORO.")
                        .setPositiveButton("OK", null).show()
                }
            }.show()
    }

    private fun exportZip(items: List<PhotoItem>, baseName: String) {
        if (items.isEmpty()) { toast("Non ci sono foto da esportare"); return }
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
                Triple(photo.uri, photo.path.substringAfter("Pictures/FA/").trimEnd('/'), photo.name)
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
