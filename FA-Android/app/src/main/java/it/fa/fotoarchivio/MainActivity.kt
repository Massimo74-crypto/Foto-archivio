package it.fa.fotoarchivio

import android.app.AlertDialog
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.content.Intent
import android.widget.ArrayAdapter
import android.widget.Button
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
        showHome()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun page(title: String): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(22))
            setBackgroundColor(0xFFF4F6FA.toInt())
        }
        root.addView(TextView(this).apply {
            text = title
            textSize = 29f
            setTextColor(0xFF14243A.toInt())
            setPadding(0, 0, 0, dp(8))
        })
        return root
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 16f
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun showHome() {
        val root = page("FA")
        root.addView(TextView(this).apply {
            text = "FOTO ARCHIVIO"
            textSize = 13f
            letterSpacing = 0.16f
            setTextColor(0xFF53647A.toInt())
            setPadding(0, 0, 0, dp(26))
        })
        root.addView(TextView(this).apply {
            text = "Dove vuoi archiviare le prossime foto?"
            textSize = 18f
            setTextColor(0xFF1D2C40.toInt())
            setPadding(0, 0, 0, dp(12))
        })
        root.addView(actionButton("PERSONALE",) { activeCategory = "PERSONALE"; launchCamera() },
            LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(10) })
        root.addView(actionButton("LAVORO") { activeCategory = "LAVORO"; launchCamera() },
            LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(26) })
        root.addView(actionButton("GESTIONE FOTO E CARTELLE") { showFileManager() },
            LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(actionButton("ESPORTA SU PC / ZIP") { showExportOptions() },
            LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(TextView(this).apply {
            text = "Le immagini vengono archiviate in Pictures/FA sul telefono."
            textSize = 13f
            setTextColor(0xFF64748B.toInt())
            setPadding(0, dp(22), 0, 0)
        })
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
        content.addView(TextView(this).apply { text = "ARCHIVIA IN"; textSize = 12f; setTextColor(0xFF64748B.toInt()) })
        content.addView(categorySpinner)
        content.addView(folderSpinner)
        content.addView(addFolder)
        content.addView(TextView(this).apply {
            text = "NOME FOTO"
            textSize = 12f
            setTextColor(0xFF64748B.toInt())
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
        val photos = listPhotos()
        val root = page("Archivio FA")
        root.addView(TextView(this).apply {
            text = "Pictures/FA  •  ${photos.size} foto"
            textSize = 14f
            setTextColor(0xFF64748B.toInt())
            setPadding(0, 0, 0, dp(16))
        })
        root.addView(actionButton("AGGIORNA ELENCO") { showFileManager() },
            LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(actionButton("GESTISCI SOTTOCARTELLE") { manageFolders() },
            LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(actionButton("INDIETRO") { showHome() },
            LinearLayout.LayoutParams(-1, dp(48)))
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (photos.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Non ci sono ancora foto archiviate."
                textSize = 16f
                setPadding(0, dp(20), 0, 0)
            })
        } else {
            photos.forEach { photo ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(10), 0, dp(10))
                }
                row.addView(TextView(this).apply {
                    text = photo.name
                    textSize = 16f
                    setTextColor(0xFF172B45.toInt())
                })
                row.addView(TextView(this).apply {
                    text = photo.path
                    textSize = 12f
                    setTextColor(0xFF64748B.toInt())
                })
                row.addView(actionButton("GESTISCI") { showPhotoActions(photo) })
                list.addView(row)
                list.addView(View(this).apply { setBackgroundColor(0xFFD8DEE8.toInt()) },
                    LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
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
