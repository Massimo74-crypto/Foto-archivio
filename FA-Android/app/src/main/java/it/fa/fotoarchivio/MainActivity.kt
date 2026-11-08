package it.fa.fotoarchivio

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.location.Location
import android.location.LocationManager
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

// GPS metadata test build 0.4.3; stable 0.4.1 branch remains untouched.
class MainActivity : ComponentActivity() {
    private var activeCategory = "PERSONALE"
    private var pendingPhoto: Uri? = null
    private var pendingVideo: Uri? = null
    private var pendingPhotoFile: File? = null
    private var pendingVideoFile: File? = null
    private var waitingForStoragePermission = false
    private var importQueue: MutableList<Uri> = mutableListOf()
    private val selectedPhotos = linkedSetOf<String>()
    private val selectedFolders = linkedSetOf<String>()
    private var cachedPhotoItems: List<PhotoItem>? = null
    private var cachedPhotoItemsAt = 0L
    private val cachedFolderLists = mutableMapOf<String, Pair<Long, List<String>>>()
    private var explorerCategory = "PERSONALE"
    private var explorerFolder = "Generale"
    private var sortMode = "Data"
    private val prefs by lazy { getSharedPreferences("fa_settings", Context.MODE_PRIVATE) }
    private val gpsHandler = Handler(Looper.getMainLooper())
    private var gpsCancellation: CancellationSignal? = null
    private var bestCameraLocation: Location? = null
    private var gpsLaunchFinished = false

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingPhoto
        if (ok && uri != null) showNamingDialog(uri, false)
        else {
            pendingPhotoFile?.delete()
            pendingPhotoFile = null
            pendingPhoto = null
            toast("Scatto annullato")
            showHome()
        }
    }

    private val videoLauncher = registerForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val uri = pendingVideo
        val recordedFile = pendingVideoFile
        // Alcune fotocamere Motorola restituiscono RESULT_CANCELED anche
        // dopo aver scritto correttamente il video nell'URI EXTRA_OUTPUT.
        // In quel caso il file realmente registrato prevale sul solo flag "ok".
        val videoExists = recordedFile?.isFile == true && recordedFile.length() > 0L
        if (uri != null && (ok || videoExists) && videoExists) {
            showNamingDialog(uri, true)
        } else {
            recordedFile?.delete()
            pendingVideoFile = null
            pendingVideo = null
            toast("Registrazione annullata o file video non disponibile")
            showHome()
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!granted) toast("Posizione non autorizzata: FA salverà comunque la foto, ma senza nuove coordinate GPS")
        // Non attendere il fix GPS: avvia subito la fotocamera.
        startNativeCameraSession()
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) {
            importQueue = uris.toMutableList()
            showNextImportedItem()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeCategory = prefs.getString("active_category", "PERSONALE") ?: "PERSONALE"
        window.statusBarColor = 0xFF10191F.toInt()
        window.navigationBarColor = 0xFF05080B.toInt()
        window.decorView.systemUiVisibility = 0
        if (hasArchiveAccess()) {
            prepareArchiveFolders()
            showHome()
            askArchiveAtStartup()
        } else requestArchiveAccess()
    }

    private val storageSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (hasArchiveAccess()) {
            prepareArchiveFolders()
            if (waitingForStoragePermission) {
                waitingForStoragePermission = false
                showHome()
                askArchiveAtStartup()
            }
        } else toast("Per gestire l'archivio serve l'autorizzazione di accesso ai file")
    }

    private fun hasArchiveAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun requestArchiveAccess() {
        waitingForStoragePermission = true
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Autorizzazione archivio FA")
            .setMessage("FA deve poter creare, rinominare, spostare e leggere le proprie cartelle nella memoria condivisa. Serve anche per mantenere l'archivio accessibile da Windows via USB. Non modifica né cancella le foto originali in DCIM/Camera.")
            .setCancelable(false)
            .setNegativeButton("Esci") { _, _ -> finish() }
            .setPositiveButton("Apri impostazioni") { _, _ ->
                try {
                    storageSettingsLauncher.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    storageSettingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }.show()
    }

    private fun archiveRoot(isVideo: Boolean): File {
        val top = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        return File(Environment.getExternalStorageDirectory(), "$top/FotoArchivio")
    }

    // Pulizia iniziale richiesta per la nuova versione: riguarda solo le cartelle
    // archivio create da FA. DCIM/Camera e la Galleria Motorola non vengono toccate.
    private fun resetArchiveForNextVersion() {
        val storage = Environment.getExternalStorageDirectory()
        val photoRoot = File(storage, "Pictures/FotoArchivio")
        val marker = File(photoRoot, ".fa_reset_060_done")
        if (marker.exists()) return
        val faOwnedRoots = listOf(
            photoRoot,
            File(storage, "Movies/FotoArchivio"),
            File(storage, "Pictures/FA archivio principale"),
            File(storage, "Pictures/FA"),
            File(storage, "Movies/FA archivio principale"),
            File(storage, "Movies/FA")
        )
        faOwnedRoots.forEach { directory ->
            try { if (directory.exists()) directory.deleteRecursively() } catch (_: Exception) { }
        }
        photoRoot.mkdirs()
        File(storage, "Movies/FotoArchivio").mkdirs()
        // Il reset riguarda anche le sottocartelle ricordate dall'app:
        // PERSONALE e LAVORO ripartono entrambe dalla cartella principale.
        getSharedPreferences("fa_settings", Context.MODE_PRIVATE).edit()
            .remove("folders_PERSONALE")
            .remove("folders_LAVORO")
            .remove("last_folder_PERSONALE")
            .remove("last_folder_LAVORO")
            .remove("fa_last_move_destination")
            .putString("active_category", "PERSONALE")
            .apply()
        activeCategory = "PERSONALE"
        try { marker.createNewFile() } catch (_: Exception) { }
    }

    private fun prepareArchiveFolders() {
        resetArchiveForNextVersion()
        listOf(false, true).forEach { isVideo ->
            val root = archiveRoot(isVideo)
            root.mkdirs()
            val marker = File(root, ".nomedia")
            if (!marker.exists()) marker.createNewFile()
            listOf("PERSONALE", "LAVORO", ".FA_TEMP").forEach { File(root, it).mkdirs() }
        }
    }

    /**
     * Unifica i vecchi archivi nella cartella pubblica FotoArchivio.
     * Ogni file viene copiato e verificato prima di rimuovere la sorgente.
     * In caso di omonimia non sovrascrive: crea un nome "recuperato".
     */
    private fun migrateLegacyArchive(isVideo: Boolean) {
        val top = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val storage = Environment.getExternalStorageDirectory()
        val legacyNames = mutableListOf("FA archivio principale")
        val shortLegacy = File(storage, "$top/FA")
        if (shortLegacy.isDirectory) legacyNames.add("FA")
        legacyNames.forEach { legacyName ->
            val legacy = File(storage, "$top/$legacyName")
            if (!legacy.isDirectory || legacy.canonicalFile == archiveRoot(isVideo).canonicalFile) return@forEach
            mergeLegacyNode(legacy, archiveRoot(isVideo))
            removeEmptyLegacyDirectories(legacy)
        }
    }

    private fun mergeLegacyNode(source: File, destination: File) {
        try {
            var target = destination
            if (source.isDirectory) {
                if (target.exists() && !target.isDirectory) {
                    target = uniqueLegacyTarget(target.parentFile ?: return, target.name)
                }
                if (!target.exists() && !target.mkdirs()) return
                source.listFiles()?.forEach { child ->
                    var childTarget = File(target, child.name)
                    if (child.isFile && childTarget.exists()) childTarget = uniqueLegacyTarget(target, child.name)
                    mergeLegacyNode(child, childTarget)
                }
                if (source.listFiles()?.isEmpty() == true) source.delete()
            } else {
                if (target.exists()) target = uniqueLegacyTarget(target.parentFile ?: return, target.name)
                target.parentFile?.mkdirs()
                source.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                if (target.exists() && target.length() == source.length()) source.delete()
                else target.delete()
            }
        } catch (_: Exception) {
            // Se il trasferimento non riesce, la sorgente resta intatta.
        }
    }

    private fun uniqueLegacyTarget(parent: File, originalName: String): File {
        val dot = originalName.lastIndexOf('.')
        val stem = if (dot > 0) originalName.substring(0, dot) else originalName
        val ext = if (dot > 0) originalName.substring(dot) else ""
        var index = 1
        var candidate = File(parent, "$stem (recuperato $index)$ext")
        while (candidate.exists()) {
            index++
            candidate = File(parent, "$stem (recuperato $index)$ext")
        }
        return candidate
    }

    private fun removeEmptyLegacyDirectories(directory: File) {
        try {
            directory.listFiles()?.filter { it.isDirectory }?.forEach { removeEmptyLegacyDirectories(it) }
            if (directory.isDirectory && directory.listFiles()?.isEmpty() == true) directory.delete()
        } catch (_: Exception) { }
    }

    private fun archiveFileUri(file: File): Uri =
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

    private fun askArchiveAtStartup() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Quale archivio vuoi aprire?")
            .setItems(arrayOf("👤  PERSONALE", "💼  LAVORO")) { _, which ->
                activeCategory = if (which == 1) "LAVORO" else "PERSONALE"
                prefs.edit().putString("active_category", activeCategory).apply()
                showHome()
            }
            .setCancelable(false)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun darkEditText(hintText: String = "", initialText: String? = null): EditText =
        EditText(this).apply {
            hint = hintText
            initialText?.let { setText(it) }
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
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
            setPadding(dp(20), dp(28), dp(20), dp(16))
            val base = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF10191F.toInt(), 0xFF182128.toInt(), 0xFF0C1217.toInt())
            )
            val watermarkBitmap = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.fa_home_image)
            // Il PNG originale resta invariato; viene solo mostrato più piccolo
            // per mantenere margine laterale su tutti gli schermi.
            val watermarkWidth = dp(250)
            val watermarkHeight = if (watermarkBitmap.width > 0)
                (watermarkWidth * watermarkBitmap.height.toFloat() / watermarkBitmap.width).toInt()
            else watermarkWidth
            val watermark = android.graphics.drawable.BitmapDrawable(resources, watermarkBitmap).apply {
                alpha = 92
                // FILL rispetta i 250dp assegnati al layer: CENTER userebbe
                // le dimensioni intrinseche e ritaglierebbe i bordi del PNG.
                gravity = Gravity.FILL
            }
            // Cinepresa vintage separata in WebP trasparente: non ricostruisce
            // né sostituisce il PNG fotografico originale.
            val movieCameraBitmap = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.fa_vintage_movie_camera)
            val movieCamera = android.graphics.drawable.BitmapDrawable(resources, movieCameraBitmap).apply {
                alpha = 210
                gravity = Gravity.FILL
            }
            background = LayerDrawable(arrayOf(base, watermark, movieCamera)).apply {
                setLayerGravity(1, Gravity.CENTER)
                setLayerWidth(1, watermarkWidth)
                setLayerHeight(1, watermarkHeight)
                // Offset rispetto al centro del drawable: la cinepresa resta
                // agganciata all'angolo alto-sinistro del PNG centrato.
                setLayerGravity(2, Gravity.CENTER)
                setLayerWidth(2, dp(58))
                setLayerHeight(2, dp(60))
                setLayerInset(2, -dp(178), -dp(105), 0, 0)
            }
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
            ).apply { cornerRadius = dp(18).toFloat(); setStroke(dp(2), 0xFF9DB9D2.toInt()) }
            backgroundTintList = null; elevation = dp(3).toFloat()
            setPadding(dp(8), dp(4), dp(8), dp(4)); setOnClickListener { onClick() }
        }

    private fun breadcrumbButton(label: String, active: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label; textSize = 12f; isAllCaps = false
            minHeight = dp(34); minimumHeight = dp(34); minWidth = 0; minimumWidth = 0
            setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFFE5D8C8.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                if (active) intArrayOf(0xFF786448.toInt(), 0xFF51432F.toInt())
                else intArrayOf(0xFF39434A.toInt(), 0xFF252F36.toInt())
            ).apply { cornerRadius = dp(10).toFloat(); setStroke(dp(1), if (active) 0xFFCDB58E.toInt() else 0xFF66727A.toInt()) }
            backgroundTintList = null; elevation = dp(1).toFloat()
            setPadding(dp(9), 0, dp(9), 0); setOnClickListener { onClick() }
        }

    private fun showHome() {
        val root = page("")
        root.setPadding(dp(18), dp(30), dp(18), dp(48))

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
            prefs.edit().putString("active_category", activeCategory).apply()
            showHome()
            toast("Archivio attivo: PERSONALE")
        }, LinearLayout.LayoutParams(0, dp(58), 1f).apply { rightMargin = dp(7) })
        categories.addView(actionButton(if (activeCategory == "LAVORO") "💼  LAVORO  ✓" else "💼  LAVORO") {
            activeCategory = "LAVORO"
            prefs.edit().putString("active_category", activeCategory).apply()
            showHome()
            toast("Archivio attivo: LAVORO")
        }, LinearLayout.LayoutParams(0, dp(58), 1f).apply { leftMargin = dp(7) })
        root.addView(categories, LinearLayout.LayoutParams(-1, dp(58)).apply {
            bottomMargin = dp(10)
        })

        root.addView(actionButton("📷  FOTOCAMERA") {
            launchCamera()
        }, LinearLayout.LayoutParams(-1, dp(58)).apply {
            bottomMargin = dp(12)
        })

        root.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
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

    private var waitingForOverlayPermission = false
    private var cameraSessionLaunched = false

    override fun onResume() {
        super.onResume()
        if (waitingForOverlayPermission) {
            waitingForOverlayPermission = false
            if (Settings.canDrawOverlays(this)) startCameraWithLocationPermission()
            else toast("Senza questa autorizzazione FA non può mostrare il nome sopra la fotocamera")
        } else if (cameraSessionLaunched) {
            cameraSessionLaunched = false
            stopService(Intent(this, CameraOverlayService::class.java))
        }
    }

    private fun launchCamera() {
        if (!Settings.canDrawOverlays(this)) {
            waitingForOverlayPermission = true
            try {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            } catch (e: Exception) {
                waitingForOverlayPermission = false
                toast("Impossibile aprire l'autorizzazione per la sovrapposizione")
            }
            return
        }
        startCameraWithLocationPermission()
    }

    private fun startCameraWithLocationPermission() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            // La fotocamera parte subito: il servizio aggiorna il GPS in parallelo.
            startNativeCameraSession()
        } else {
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    /**
     * Acquisisce un fix mentre MainActivity è ancora visibile.
     * La fotocamera Motorola viene avviata comunque dopo il primo fix utile
     * oppure dopo un timeout breve: il GPS non blocca mai lo scatto.
     */
    private fun acquireLocationThenStartCamera() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            startNativeCameraSession()
            return
        }
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = try {
            manager.getProviders(true).filter {
                it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER
            }
        } catch (_: Exception) { emptyList() }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || providers.isEmpty()) {
            startNativeCameraSession()
            return
        }
        gpsCancellation?.cancel()
        gpsCancellation = CancellationSignal()
        bestCameraLocation = null
        gpsLaunchFinished = false
        val timeout = Runnable { finishLocationAcquisition() }
        gpsHandler.removeCallbacksAndMessages(null)
        gpsHandler.postDelayed(timeout, 7000L)
        var requested = false
        providers.forEach { provider ->
            try {
                manager.getCurrentLocation(provider, gpsCancellation, mainExecutor) { location ->
                    if (gpsLaunchFinished) return@getCurrentLocation
                    if (location != null) {
                        val current = bestCameraLocation
                        if (current == null || !current.hasAccuracy() ||
                            (location.hasAccuracy() && location.accuracy < current.accuracy)) {
                            bestCameraLocation = location
                        }
                        // Un breve margine permette al GPS di superare il fix di rete.
                        gpsHandler.removeCallbacks(timeout)
                        gpsHandler.postDelayed(timeout, 1200L)
                    }
                }
                requested = true
            } catch (_: SecurityException) { }
              catch (_: Exception) { }
        }
        if (!requested) finishLocationAcquisition()
    }

    private fun finishLocationAcquisition() {
        if (gpsLaunchFinished) return
        gpsLaunchFinished = true
        gpsHandler.removeCallbacksAndMessages(null)
        gpsCancellation?.cancel()
        gpsCancellation = null
        startNativeCameraSession(bestCameraLocation)
    }

    private fun startNativeCameraSession(location: Location? = null) {
        try {
            prepareArchiveFolders()
            val folder = prefs.getString(lastFolderKey(activeCategory), "") ?: ""
            val serviceIntent = Intent(this, CameraOverlayService::class.java).apply {
                putExtra(CameraOverlayService.EXTRA_CATEGORY, activeCategory)
                putExtra(CameraOverlayService.EXTRA_FOLDER, folder)
                putExtra(CameraOverlayService.EXTRA_START_TIME, System.currentTimeMillis())
                location?.let {
                    putExtra(CameraOverlayService.EXTRA_GPS_LATITUDE, it.latitude)
                    putExtra(CameraOverlayService.EXTRA_GPS_LONGITUDE, it.longitude)
                    putExtra(CameraOverlayService.EXTRA_GPS_TIME, it.time)
                    if (it.hasAltitude()) putExtra(CameraOverlayService.EXTRA_GPS_ALTITUDE, it.altitude)
                    if (it.hasAccuracy()) putExtra(CameraOverlayService.EXTRA_GPS_ACCURACY, it.accuracy)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
            else startService(serviceIntent)

            // Avvia l'interfaccia completa della fotocamera predefinita: l'utente
            // può scegliere Foto o Video dai controlli nativi del dispositivo.
            val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            cameraSessionLaunched = true
            try {
                startActivity(cameraIntent)
            } catch (e: android.content.ActivityNotFoundException) {
                cameraSessionLaunched = false
                stopService(Intent(this, CameraOverlayService::class.java))
                toast("Android non ha trovato un'app fotocamera compatibile")
            }
        } catch (e: Exception) {
            cameraSessionLaunched = false
            stopService(Intent(this, CameraOverlayService::class.java))
            toast("Avvio fotocamera non riuscito: ${e.localizedMessage ?: "errore"}")
        }
    }

    private fun launchVideoCamera() {
        try {
            prepareArchiveFolders()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(File(archiveRoot(true), ".FA_TEMP"), "FA_TEMP_$stamp.mp4")
            pendingVideoFile = file
            pendingVideo = archiveFileUri(file)
            videoLauncher.launch(pendingVideo!!)
        } catch (e: Exception) {
            pendingVideoFile?.delete()
            pendingVideoFile = null
            pendingVideo = null
            toast("Videocamera non disponibile: ${e.localizedMessage ?: "errore"}")
            showHome()
        }
    }

    private fun folderKey(category: String) = "folders_$category"
    private fun lastFolderKey(category: String) = "last_folder_$category"

    // I percorsi sono relativi alla categoria, ad esempio AMMEGA/CARTEGGIATRICE/RECINZIONE.
    private fun folders(category: String): MutableList<String> {
        val now = System.currentTimeMillis()
        cachedFolderLists[category]?.let { cached ->
            if (now - cached.first < 5000L) return cached.second.toMutableList()
        }
        // Una sola scansione per categoria ogni 5 secondi: i normali tocchi
        // di navigazione e selezione riutilizzano l'elenco già ricavato.
        val saved = prefs.getStringSet(folderKey(category), emptySet())?.toMutableSet()
            ?: mutableSetOf()
        val expanded = mutableSetOf<String>()
        saved.forEach { raw ->
            val clean = raw.trim('/').split('/').filter { it.isNotBlank() }
            var path = ""
            clean.forEach { part ->
                path = if (path.isEmpty()) safeFolder(part) else path + "/" + safeFolder(part)
                expanded.add(path)
            }
        }
        listOf(false, true).forEach { isVideo ->
            val categoryRoot = File(archiveRoot(isVideo), category)
            if (categoryRoot.exists() && categoryRoot.isDirectory) {
                categoryRoot.walkTopDown()
                    .filter { it.isDirectory && it != categoryRoot }
                    .forEach { directory ->
                        val relative = directory.relativeTo(categoryRoot).path
                            .replace(File.separatorChar, '/').trim('/')
                        if (relative.isNotBlank() && relative != ".FA_TEMP" &&
                            !relative.startsWith(".FA_TEMP/")) {
                            var current = ""
                            relative.split('/').filter { it.isNotBlank() }.forEach { part ->
                                current = if (current.isEmpty()) part else "$current/$part"
                                expanded.add(current)
                            }
                        }
                    }
            }
        }
        val emptyDefaultGeneral = listOf(false, true).all { isVideo ->
            val directory = File(archiveRoot(isVideo), "$category/Generale")
            !directory.exists() || (directory.isDirectory && directory.listFiles().isNullOrEmpty())
        }
        if (emptyDefaultGeneral) expanded.remove("Generale")
        val result = expanded.sorted()
        cachedFolderLists[category] = now to result
        return result.toMutableList()
    }

    private fun invalidateArchiveCaches() {
        cachedPhotoItems = null
        cachedPhotoItemsAt = 0L
        cachedFolderLists.clear()
    }

    private fun saveFolderList(category: String, list: List<String>) {
        prefs.edit().putStringSet(folderKey(category), list.toSet()).apply()
        cachedFolderLists.remove(category)
        cachedPhotoItems = null
        cachedPhotoItemsAt = 0L
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
        prepareArchiveFolders()
        listOf(false, true).forEach { isVideo -> File(archiveRoot(isVideo), "$category/$full").mkdirs() }
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
        var result = uri.lastPathSegment?.substringAfterLast('/') ?: "Elemento"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
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
            val rootLabel = "— Cartella principale —"
            val list = listOf(rootLabel) + folders(category)
            folderSpinner.adapter = darkSpinnerAdapter(list)
            val preferred = select ?: prefs.getString(lastFolderKey(category), "") ?: ""
            val index = if (preferred.isBlank()) 0 else list.indexOf(preferred)
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
                    val selectedParent = folderSpinner.selectedItem?.toString() ?: "— Cartella principale —"
                    val parent = if (selectedParent == "— Cartella principale —") "" else selectedParent
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
                    if (isVideo) { pendingVideoFile?.delete(); pendingVideoFile = null; pendingVideo = null }
                    else { pendingPhotoFile?.delete(); pendingPhotoFile = null; pendingPhoto = null }
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
                // Il valore dello spinner può essere un percorso completo:
                // sanificare ogni segmento separatamente preserva le sottocartelle.
                val selectedFolder = folderSpinner.selectedItem?.toString() ?: "— Cartella principale —"
                val folder = if (selectedFolder == "— Cartella principale —") "" else selectedFolder.trim('/').split('/').filter { it.isNotBlank() }.joinToString("/") { safeFolder(it) }
                val extension = ".$sourceExtension"
                val fileName = if (baseName.lowercase(Locale.ROOT).endsWith(extension)) baseName else "$baseName$extension"
                val destinationDir = File(archiveRoot(isVideo), if (folder.isBlank()) category else "$category/$folder")
                val destination = File(destinationDir, fileName)
                prefs.edit().putString(lastFolderKey(category), folder).apply()
                val saved = try {
                    destinationDir.mkdirs()
                    if (destination.exists()) {
                        name.error = "Esiste già un file con questo nome"
                        false
                    } else if (isImported) {
                        // Copia fisica: la sorgente in DCIM/Camera o altrove non viene mai modificata.
                        val input = contentResolver.openInputStream(uri)
                        if (input == null) false else {
                            input.use { source -> FileOutputStream(destination).use { target -> source.copyTo(target) } }
                            true
                        }
                    } else {
                        val temp = if (isVideo) pendingVideoFile else pendingPhotoFile
                        temp != null && temp.exists() && temp.renameTo(destination)
                    }
                } catch (_: Exception) {
                    destination.delete()
                    false
                }
                if (saved) {
                    activeCategory = category
                    if (!isImported) {
                        if (isVideo) { pendingVideo = null; pendingVideoFile = null }
                        else { pendingPhoto = null; pendingPhotoFile = null }
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

    private data class PhotoItem(val file: File, val uri: Uri, val name: String, val path: String, val isVideo: Boolean = false)

    private fun listPhotos(): List<PhotoItem> {
        val now = System.currentTimeMillis()
        cachedPhotoItems?.let { cached ->
            if (now - cachedPhotoItemsAt < 1500L) return cached
        }
        val result = mutableListOf<PhotoItem>()
        listOf(false, true).forEach { isVideo ->
            val root = archiveRoot(isVideo)
            if (!root.exists()) return@forEach
            root.walkTopDown()
                .filter { it.isFile && it.name != ".nomedia" && !it.relativeTo(root).path.replace(File.separatorChar, '/').startsWith(".FA_TEMP/") }
                .forEach { file ->
                    val rootName = if (isVideo) "Movies" else "Pictures"
                    val relative = file.relativeTo(Environment.getExternalStorageDirectory()).path.replace(File.separatorChar, '/')
                    if (relative.startsWith("$rootName/FotoArchivio/PERSONALE/") ||
                        relative.startsWith("$rootName/FotoArchivio/LAVORO/")) {
                        try { result.add(PhotoItem(file, archiveFileUri(file), file.name, "/$relative", isVideo)) }
                        catch (_: Exception) { }
                    }
                }
        }
        val sorted = result.sortedByDescending { it.file.lastModified() }
        cachedPhotoItems = sorted
        cachedPhotoItemsAt = now
        return sorted
    }

    private fun isInCategory(path: String, category: String): Boolean =
        path.trimStart('/').startsWith("Pictures/FotoArchivio/$category/") || path.trimStart('/').startsWith("Movies/FotoArchivio/$category/")

    private fun categoryFolderPath(item: PhotoItem, category: String, folder: String): String {
        val root = if (item.isVideo) "Movies" else "Pictures"
        return "$root/FotoArchivio/$category/$folder/"
    }

    private fun showFileManager() {
        selectedPhotos.clear()
        selectedFolders.clear()
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
                .setMessage("Collega il Motorola al PC con un cavo USB dati. Nella notifica USB scegli «Trasferimento file». In Windows apri Esplora file → Motorola → Memoria interna condivisa. Le foto sono in Pictures → FotoArchivio e i video in Movies → FotoArchivio: copia PERSONALE e LAVORO da entrambe le posizioni.")
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
        selectedPhotos.clear()
        selectedFolders.clear()
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
        val root = page("")
        val current = explorerFolder.trim('/')
        val parts = current.split('/').filter { it.isNotBlank() }
        val crumbs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        crumbs.addView(breadcrumbButton(explorerCategory, parts.isEmpty()) {
            explorerFolder = ""; selectedPhotos.clear(); selectedFolders.clear()
            prefs.edit().putString(lastFolderKey(explorerCategory), "").apply(); showExplorerFolder()
        }, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(6) })
        var built = ""
        parts.forEachIndexed { index, part ->
            built = if (built.isEmpty()) part else built + "/" + part
            val targetPath = built
            crumbs.addView(TextView(this).apply { text = "›"; textSize = 16f; setTextColor(0xFFB9A98F.toInt()); gravity = Gravity.CENTER; setPadding(0, 0, dp(5), 0) })
            crumbs.addView(breadcrumbButton(part, index == parts.lastIndex) {
                explorerFolder = targetPath; selectedPhotos.clear(); selectedFolders.clear()
                prefs.edit().putString(lastFolderKey(explorerCategory), targetPath).apply(); showExplorerFolder()
            }, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(6) })
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; isFillViewport = false; addView(crumbs)
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(3); bottomMargin = dp(5) })
        root.addView(actionButton("＋ NUOVA SOTTOCARTELLA") {
            createChildFolder(current)
        }, LinearLayout.LayoutParams(-1, dp(46)))
        root.addView(actionButton("⬇ IMPORTA FOTO E VIDEO QUI") {
            activeCategory = explorerCategory
            prefs.edit().putString(lastFolderKey(explorerCategory), current).apply()
            importLauncher.launch(arrayOf("image/*", "video/*"))
        }, LinearLayout.LayoutParams(-1, dp(48)))
        if (current.isBlank()) {
            root.addView(actionButton("← GESTIONE ARCHIVIO") { showFileManager() },
                LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(4) })
        }

        val folderPaths = folders(explorerCategory)
        val prefix = if (current.isBlank()) "" else current + "/"
        val children = folderPaths.mapNotNull { path ->
            if (!path.startsWith(prefix) || path == current) null
            else path.removePrefix(prefix).substringBefore('/').takeIf { it.isNotBlank() }
        }.distinct().sorted()
        val allItems = listPhotos().filter { isInCategory(it.path, explorerCategory) }
        val exactRelative = current.trim('/')
        // Un solo passaggio calcola i conteggi per tutte le cartelle antenate.
        val descendantCounts = mutableMapOf<String, Int>()
        allItems.forEach { item ->
            var parentPath = item.path.substringAfter("/FotoArchivio/" + explorerCategory + "/", "").substringBeforeLast('/', "")
            while (parentPath.isNotBlank()) {
                descendantCounts[parentPath] = (descendantCounts[parentPath] ?: 0) + 1
                parentPath = parentPath.substringBeforeLast('/', "")
            }
        }
        val photos = allItems.filter {
            val relative = it.path.substringAfter("/FotoArchivio/" + explorerCategory + "/", "").trimEnd('/')
            relative.substringBeforeLast('/', "") == exactRelative
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
        val totalFiles = if (exactRelative.isBlank()) allItems.size else (descendantCounts[exactRelative] ?: 0)
        val folderPrefix = if (exactRelative.isBlank()) "" else exactRelative + "/"
        val totalSubfolders = folderPaths.count { path ->
            path != exactRelative && (exactRelative.isBlank() || path.startsWith(folderPrefix))
        }
        root.addView(TextView(this).apply {
            text = totalFiles.toString() + " file totali (incluse sottocartelle)  •  " + totalSubfolders + " sottocartelle"
            textSize = 12f; setTextColor(0xFFC7B9A7.toInt()); setPadding(0, dp(5), 0, dp(7))
        })
        val selectedCount = selectedFolders.size + selectedPhotos.size
        val selectedActionButton = actionButton("ELEMENTI SELEZIONATI (" + selectedCount + ")") {
            showSelectedItemsActions()
        }
        selectedActionButton.visibility = if (selectedCount > 0) View.VISIBLE else View.GONE
        root.addView(selectedActionButton, LinearLayout.LayoutParams(-1, dp(44)))
        fun refreshSelectionIndicator() {
            val countNow = selectedFolders.size + selectedPhotos.size
            selectedActionButton.text = "ELEMENTI SELEZIONATI (" + countNow + ")"
            selectedActionButton.visibility = if (countNow > 0) View.VISIBLE else View.GONE
        }
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        children.forEach { child ->
            val fullPath = if (current.isBlank()) child else current + "/" + child
            val count = descendantCounts[fullPath] ?: 0
            val subfolderCount = folderPaths.count { it.startsWith(fullPath + "/") }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }
            val folderCheck = CheckBox(this).apply {
                text = "MULTI-SELEZIONE"
                textSize = 10f
                setTextColor(0xFFC7B9A7.toInt())
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF9DB9D2.toInt())
                isChecked = selectedFolders.contains(fullPath)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedFolders.add(fullPath) else selectedFolders.remove(fullPath)
                    refreshSelectionIndicator()
                }
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
                text = count.toString() + " elementi  •  " + subfolderCount + " sottocartelle"
                textSize = 12f
                setTextColor(0xFFC7B9A7.toInt())
            })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(folderCheck)
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener {
                selectedPhotos.clear()
                selectedFolders.clear()
                explorerFolder = fullPath
                prefs.edit().putString(lastFolderKey(explorerCategory), fullPath).apply()
                showExplorerFolder()
            }
            row.setOnLongClickListener {
                folderActions(fullPath)
                true
            }
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
            val photoCheck = CheckBox(this).apply {
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF9DB9D2.toInt())
                isChecked = selectedPhotos.contains(photo.uri.toString())
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedPhotos.add(photo.uri.toString()) else selectedPhotos.remove(photo.uri.toString())
                    refreshSelectionIndicator()
                }
            }
            row.addView(photoCheck)
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
            details.addView(TextView(this).apply {
                text = "Tocco breve: apri  •  Tocco lungo: opzioni"
                textSize = 11f
                setTextColor(0xFFB9C4CF.toInt())
            })
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { openMediaItem(photo) }
            row.setOnLongClickListener {
                showPhotoActions(photo)
                true
            }
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
        // Immagine vintage usata come watermark trasparente sullo sfondo della pagina.
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
        bottom.addView(actionButton("HOME") { showHome() },
            LinearLayout.LayoutParams(-1, dp(50)))
        root.addView(bottom)
        setContentView(root)
    }

    private fun showSelectedItemsActions() {
        val count = selectedFolders.size + selectedPhotos.size
        if (count == 0) return
        val options = arrayOf("Seleziona tutti gli elementi di questa cartella", "Sposta selezione", "Esporta selezione in ZIP", "Elimina selezione", "Deseleziona tutto")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("ELEMENTI SELEZIONATI (" + count + ")")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        folderChildren(explorerCategory, explorerFolder).forEach { child ->
                            selectedFolders.add(if (explorerFolder.isBlank()) child else explorerFolder.trimEnd('/') + "/" + child)
                        }
                        listPhotos().filter { photo ->
                            if (!isInCategory(photo.path, explorerCategory)) false
                            else photo.path.substringAfter("/FotoArchivio/" + explorerCategory + "/", "").substringBeforeLast("/", "") == explorerFolder.trim('/')
                        }.forEach { selectedPhotos.add(it.uri.toString()) }
                        showExplorerFolder()
                    }
                    1 -> moveSelectedItems()
                    2 -> { val chosen = selectedMediaForActions(); if (chosen.isEmpty()) toast("La selezione non contiene foto o video") else exportZip(chosen, "FA-Selezione") }
                    3 -> confirmDeleteSelectedItems()
                    4 -> { selectedFolders.clear(); selectedPhotos.clear(); showExplorerFolder() }
                }
            }.show()
    }

    private fun selectedMediaForActions(): List<PhotoItem> {
        val selectedPaths = selectedFolders.toList()
        return listPhotos().filter { item ->
            if (selectedPhotos.contains(item.uri.toString())) true
            else if (!isInCategory(item.path, explorerCategory)) false
            else {
                val relative = item.path.substringAfter("/FotoArchivio/" + explorerCategory + "/", "").trim('/')
                selectedPaths.any { relative == it || relative.startsWith(it + "/") }
            }
        }.distinctBy { it.file.absolutePath }
    }

    private fun confirmDeleteSelectedItems() {
        val foldersToDelete = selectedFolders.toList().filterNot { path ->
            selectedFolders.any { other -> other != path && path.startsWith(other + "/") }
        }
        val photosToDelete = selectedMediaForActions().filter { photo ->
            val relative = photo.path.substringAfter("/FotoArchivio/" + explorerCategory + "/", "").trim('/')
            !foldersToDelete.any { relative == it || relative.startsWith(it + "/") }
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Confermi eliminazione?")
            .setMessage("Saranno eliminati gli elementi selezionati solo dall'archivio FA. Gli originali in DCIM/Camera restano intatti.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Elimina") { _, _ ->
                var deleted = 0
                photosToDelete.forEach { if (deleteFaItem(it)) deleted++ }
                foldersToDelete.forEach { path ->
                    listOf(false, true).forEach { isVideo ->
                        val archive = File(archiveRoot(isVideo), explorerCategory).canonicalFile
                        val folder = File(archive, path).canonicalFile
                        if (folder.path.startsWith(archive.path + File.separator) && folder.exists() && folder.deleteRecursively()) deleted++
                    }
                    saveFolderList(explorerCategory, folders(explorerCategory).filterNot { it == path || it.startsWith(path + "/") })
                }
                selectedFolders.clear(); selectedPhotos.clear()
                invalidateArchiveCaches()
                toast(deleted.toString() + " elementi eliminati")
                showExplorerFolder()
            }.show()
    }

    private fun moveSelectedItems() {
        val folderRoots = selectedFolders.toList().filterNot { path ->
            selectedFolders.any { other -> other != path && path.startsWith(other + "/") }
        }
        val media = selectedMediaForActions()
        val photosToMove = media.filter { photo ->
            val relative = photo.path.substringAfter("/FotoArchivio/" + explorerCategory + "/", "").trim('/')
            !folderRoots.any { relative == it || relative.startsWith(it + "/") }
        }
        val targets = mutableListOf<Pair<String, String>>()
        listOf("PERSONALE", "LAVORO").forEach { category ->
            targets.add(category to "")
            folders(category).forEach { targets.add(category to it) }
        }
        val labels = targets.map { it.first + " / " + if (it.second.isBlank()) "Cartella principale" else it.second }.toTypedArray()
        val last = prefs.getString("fa_last_move_destination", "") ?: ""
        val initial = targets.indexOfFirst { it.first + "/" + it.second == last }.coerceAtLeast(0)
        var chosen = initial
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Sposta selezione")
            .setSingleChoiceItems(labels, initial) { _, which -> chosen = which }
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Sposta") { _, _ ->
                val category = targets[chosen].first
                val destinationFolder = targets[chosen].second
                val sourceCategory = explorerCategory
                val movedDirs = mutableListOf<Pair<File, File>>()
                val movedFiles = mutableListOf<Pair<File, File>>()
                var ok = true
                for (oldPath in folderRoots) {
                    val newPath = if (destinationFolder.isBlank()) oldPath.substringAfterLast('/') else destinationFolder + "/" + oldPath.substringAfterLast('/')
                    if (category == sourceCategory && (newPath == oldPath || newPath.startsWith(oldPath + "/") || oldPath.startsWith(newPath + "/"))) { ok = false; break }
                    for (isVideo in listOf(false, true)) {
                        val oldDir = File(archiveRoot(isVideo), "$sourceCategory/$oldPath")
                        if (!oldDir.exists()) continue
                        val newDir = File(archiveRoot(isVideo), "$category/$newPath")
                        if (newDir.exists()) { ok = false; break }
                        newDir.parentFile?.mkdirs()
                        if (!oldDir.renameTo(newDir)) { ok = false; break }
                        movedDirs.add(newDir to oldDir)
                    }
                    if (!ok) break
                }
                if (ok) for (photo in photosToMove) {
                    val dir = File(archiveRoot(photo.isVideo), if (destinationFolder.isBlank()) category else "$category/$destinationFolder")
                    val target = File(dir, photo.file.name)
                    if (target.exists()) { ok = false; break }
                    dir.mkdirs()
                    if (!photo.file.renameTo(target)) { ok = false; break }
                    movedFiles.add(target to photo.file)
                }
                if (!ok) {
                    movedFiles.asReversed().forEach { (to, from) -> to.renameTo(from) }
                    movedDirs.asReversed().forEach { (to, from) -> from.parentFile?.mkdirs(); to.renameTo(from) }
                    toast("Spostamento non riuscito: archivio ripristinato dove possibile")
                } else {
                    val sourceList = folders(sourceCategory).toMutableSet()
                    val targetList = if (category == sourceCategory) sourceList else folders(category).toMutableSet()
                    folderRoots.forEach { oldPath ->
                        val newPath = if (destinationFolder.isBlank()) oldPath.substringAfterLast('/') else destinationFolder + "/" + oldPath.substringAfterLast('/')
                        sourceList.filter { it == oldPath || it.startsWith(oldPath + "/") }.forEach { old ->
                            sourceList.remove(old); targetList.add(newPath + old.removePrefix(oldPath))
                        }
                    }
                    saveFolderList(sourceCategory, sourceList.toList())
                    if (category != sourceCategory) saveFolderList(category, targetList.toList())
                    prefs.edit().putString("fa_last_move_destination", category + "/" + destinationFolder).apply()
                    invalidateArchiveCaches()
                    selectedFolders.clear(); selectedPhotos.clear()
                    explorerCategory = category; explorerFolder = destinationFolder
                    prefs.edit().putString(lastFolderKey(category), destinationFolder).apply()
                    toast("Spostamento completato"); showExplorerFolder()
                }
            }.show()
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
                    var renameOk = true
                    val moved = mutableListOf<Pair<File, File>>()
                    listOf(false, true).forEach { isVideo ->
                        val oldDir = File(archiveRoot(isVideo), "$explorerCategory/$oldPath")
                        if (oldDir.exists()) {
                            val newDir = File(archiveRoot(isVideo), "$explorerCategory/$newPath")
                            newDir.parentFile?.mkdirs()
                            if (newDir.exists() || !oldDir.renameTo(newDir)) renameOk = false
                            else moved.add(newDir to oldDir)
                        }
                    }
                    if (renameOk) saveFolderList(explorerCategory, changed.toList())
                    else {
                        moved.asReversed().forEach { (newDir, oldDir) -> newDir.renameTo(oldDir) }
                        toast("Rinomina non riuscita: archivio lasciato invariato")
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
                val relative = item.path.substringAfter("/FotoArchivio/" + explorerCategory, "").trim('/')
                relative == path || relative.startsWith(path + "/")
            }
        }
        if (hasChildren || hasMedia) {
            toast("Cartella non vuota: elimina prima il contenuto e le sottocartelle")
            return
        }
        listOf(false, true).forEach { isVideo ->
            val emptyDir = File(archiveRoot(isVideo), "$explorerCategory/$path")
            if (emptyDir.exists()) emptyDir.delete()
        }
        saveFolderList(explorerCategory, folders(explorerCategory).filterNot {
            it == path || it.startsWith(path + "/")
        })
        showExplorerFolder()
    }

    private fun showBulkFolderActions() {
        val chosen = selectedFolders.toList()
        if (chosen.isEmpty()) { toast("Seleziona prima una o più cartelle"); return }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("${chosen.size} cartelle selezionate")
            .setItems(arrayOf("Elimina cartelle vuote selezionate", "Deseleziona tutto")) { _, which ->
                if (which == 1) {
                    selectedFolders.clear()
                    showExplorerFolder()
                    return@setItems
                }
                val allFolders = folders(explorerCategory)
                val media = listPhotos().filter { isInCategory(it.path, explorerCategory) }
                val nonEmpty = chosen.filter { path ->
                    val hasChild = allFolders.any { it.startsWith(path + "/") }
                    val hasMedia = media.any { item ->
                        val relative = item.path.substringAfter("/FotoArchivio/" + explorerCategory + "/", "").trim('/')
                        relative == path || relative.startsWith(path + "/")
                    }
                    hasChild || hasMedia
                }
                if (nonEmpty.isNotEmpty()) {
                    toast("Alcune cartelle contengono elementi: non sono state eliminate")
                    return@setItems
                }
                chosen.forEach { path ->
                    listOf(false, true).forEach { isVideo ->
                        File(archiveRoot(isVideo), "$explorerCategory/$path").delete()
                    }
                }
                saveFolderList(explorerCategory, allFolders.filterNot { path ->
                    chosen.any { path == it || path.startsWith(it + "/") }
                })
                selectedFolders.clear()
                showExplorerFolder()
            }
            .setNegativeButton("Annulla", null)
            .show()
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
                        chosen.forEach { deleteFaItem(it) }
                        selectedPhotos.clear()
                        showExplorerFolder()
                    }.show()
            }.show()
    }

    private fun deleteFaItem(photo: PhotoItem): Boolean {
        val root = archiveRoot(photo.isVideo).canonicalFile
        val file = photo.file.canonicalFile
        val path = file.absolutePath
        val base = root.absolutePath + File.separator
        val isManaged = path.startsWith(base + "PERSONALE" + File.separator) ||
            path.startsWith(base + "LAVORO" + File.separator)
        if (!isManaged || !file.isFile) {
            toast("Cancellazione bloccata: percorso FA non verificato")
            return false
        }
        return file.delete()
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
                            deleteFaItem(photo)
                            showExplorerFolder()
                        }.show()
                }
            }.show()
    }

    private fun openMediaItem(item: PhotoItem) {
        try {
            val extension = item.file.extension.lowercase(Locale.ROOT)
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: if (item.isVideo) "video/*" else "image/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(item.uri, mimeType)
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
                val extension = photo.file.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: if (photo.isVideo) ".mp4" else ".jpg"
                val fileName = if (base.lowercase(Locale.ROOT).endsWith(extension)) base else "$base$extension"
                val target = File(photo.file.parentFile, fileName)
                if (!target.exists() && photo.file.renameTo(target)) showFileManager()
                else toast("Rinomina non riuscita: nome già presente o file non accessibile")
            }.show()
    }

    private fun movePhoto(photo: PhotoItem) {
        var targetCategory = explorerCategory
        fun chooseDestination() {
            val paths = folders(targetCategory)
            val labels = paths.map { path ->
                val depth = path.count { it == '/' }
                ("   ".repeat(depth)) + "📁  " + path.substringAfterLast('/')
            }.toTypedArray()
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Scegli la cartella in " + targetCategory)
                .setItems(labels) { _, index ->
                    val destinationFolder = paths[index]
                    val destinationDir = File(archiveRoot(photo.isVideo), "$targetCategory/$destinationFolder")
                    destinationDir.mkdirs()
                    val target = File(destinationDir, photo.file.name)
                    if (!target.exists() && photo.file.renameTo(target)) {
                        explorerCategory = targetCategory
                        explorerFolder = destinationFolder
                        showExplorerFolder()
                    } else toast("Spostamento non riuscito: nome già presente o file non accessibile")
                }
                .setNegativeButton("Annulla", null)
                .show()
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Sposta elemento")
            .setItems(arrayOf("PERSONALE", "LAVORO")) { _, which ->
                targetCategory = if (which == 0) "PERSONALE" else "LAVORO"
                chooseDestination()
            }
            .setNegativeButton("Annulla", null)
            .show()
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
                        .setMessage("Collega il Motorola al PC con un cavo USB dati. Nella notifica USB scegli «Trasferimento file». In Windows apri Esplora file → Motorola → Memoria interna condivisa. Le foto sono in Pictures → FotoArchivio e i video in Movies → FotoArchivio; copia PERSONALE e LAVORO da entrambe le posizioni.")
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
                Triple(photo.uri, photo.path.substringAfter("/FotoArchivio/").trimEnd('/'), photo.name)
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

// Build trigger: include the immediate camera launch and asynchronous GPS startup changes.