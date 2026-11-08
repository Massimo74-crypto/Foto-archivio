package it.fa.fotoarchivio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.content.pm.ServiceInfo
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.Calendar
import androidx.exifinterface.media.ExifInterface

class CameraOverlayService : Service() {
    companion object {
        const val EXTRA_CATEGORY = "fa_category"
        const val EXTRA_FOLDER = "fa_folder"
        const val EXTRA_START_TIME = "fa_start_time"
        const val EXTRA_GPS_LATITUDE = "fa_gps_latitude"
        const val EXTRA_GPS_LONGITUDE = "fa_gps_longitude"
        const val EXTRA_GPS_TIME = "fa_gps_time"
        const val EXTRA_GPS_ALTITUDE = "fa_gps_altitude"
        const val EXTRA_GPS_ACCURACY = "fa_gps_accuracy"
        private const val CHANNEL_ID = "fa_camera_overlay"
        private const val NOTIFICATION_ID = 7401
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var overlay: LinearLayout? = null
    private var nameInput: EditText? = null
    private var saveButton: Button? = null
    private var saveInProgress = false
    @Volatile private var displayedFile: File? = null
    private val handledFilePaths = mutableSetOf<String>()
    private val popupQueuedOrShownPaths = mutableSetOf<String>()
    // Tiene traccia dei nomi provvisori già riconosciuti: alcune fotocamere
    // rinominano il file anche dopo che il popup è stato chiuso.
    private val handledPathsAwaitingRename = mutableSetOf<String>()
    private val completedFilePaths = mutableSetOf<String>()
    private var cameraDirectoryObserver: FileObserver? = null
    private var pendingPopupFile: File? = null
    private var pendingPopupWasMoved = false
    private var pendingPopupRunnable: Runnable? = null
    private var displayedFileWasMoved = false
    private var sourceReadyForSave = false
    private var archiveCopyInProgress = false
    private var suppressCameraPopupsUntil = 0L
    private var saveOperationId = 0L
    private var pendingSaveStartedAt = 0L
    private var pendingSaveFile: File? = null
    private var pendingSaveLastLength = -1L
    private var pendingSaveStableChecks = 0
    private var readyCheckRunnable: Runnable? = null
    private var readyCheckLastLength = -1L
    private var readyCheckStableChecks = 0
    private var readyCheckStartedAt = 0L
    private var category = "PERSONALE"
    private var folder = ""
    private var sessionStartSeconds = 0L
    private var candidateLocation: Location? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var photoDateTakenMillis: Long? = null
    private var folderLabel: TextView? = null
    private var gpsDiagnostic: String = "GPS non ancora verificato"
    private var saveStatusLabel: TextView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        val notification = buildNotification()
        // Mantiene invariato il tipo di servizio già collaudato nella 0.4.1.
        // La raccolta GPS è facoltativa e non deve impedire l'avvio della sovrapposizione.
        if (Build.VERSION.SDK_INT >= 34) {
            val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                (if (hasAnyLocationPermission()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            startForeground(NOTIFICATION_ID, notification, serviceTypes)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        category = intent?.getStringExtra(EXTRA_CATEGORY) ?: "PERSONALE"
        folder = intent?.getStringExtra(EXTRA_FOLDER) ?: ""
        sessionStartSeconds = (intent?.getLongExtra(EXTRA_START_TIME, System.currentTimeMillis()) 
            ?: System.currentTimeMillis()) / 1000L
        // Conserva il fix eventualmente acquisito dall'attività prima dell'avvio
        // e poi avvia gli aggiornamenti continui nel servizio.
        if (intent?.hasExtra(EXTRA_GPS_LATITUDE) == true &&
            intent.hasExtra(EXTRA_GPS_LONGITUDE) && intent.hasExtra(EXTRA_GPS_TIME)) {
            candidateLocation = Location("FA-current-fix").apply {
                latitude = intent.getDoubleExtra(EXTRA_GPS_LATITUDE, 0.0)
                longitude = intent.getDoubleExtra(EXTRA_GPS_LONGITUDE, 0.0)
                time = intent.getLongExtra(EXTRA_GPS_TIME, System.currentTimeMillis())
                if (intent.hasExtra(EXTRA_GPS_ALTITUDE)) altitude = intent.getDoubleExtra(EXTRA_GPS_ALTITUDE, 0.0)
                if (intent.hasExtra(EXTRA_GPS_ACCURACY)) accuracy = intent.getFloatExtra(EXTRA_GPS_ACCURACY, 0f)
            }
        }
        startLocationCapture()
        startCameraDirectoryObserver()
        return START_NOT_STICKY
    }

    // Intercetta la chiusura del file scritto dalla fotocamera nativa:
    // il popup non deve attendere l'indicizzazione di MediaStore.
    // Il controllo MediaStore resta attivo come fallback.
    private fun startCameraDirectoryObserver() {
        val cameraDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
        if (!cameraDir.exists() || !cameraDir.isDirectory) return
        try {
            cameraDirectoryObserver?.stopWatching()
            cameraDirectoryObserver = object : FileObserver(
                cameraDir.absolutePath,
                FileObserver.CREATE or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.MOVED_FROM
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if (path.isNullOrBlank()) return
                    val file = File(cameraDir, path)
                    if (!isCameraImage(file)) return
                    handler.post { handleCameraFileEvent(file, event) }
                }
            }.also { it.startWatching() }
        } catch (_: Exception) {
            cameraDirectoryObserver = null
        }
    }

    private fun isCameraImage(file: File): Boolean =
        file.extension.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "dng")

    private fun filePath(file: File): String =
        try { file.canonicalPath } catch (_: Exception) { file.absolutePath }

    private fun handleCameraFileEvent(file: File, event: Int) {
        val path = filePath(file)
        val isMovedFrom = (event and FileObserver.MOVED_FROM) != 0
        val isMovedTo = (event and FileObserver.MOVED_TO) != 0
        val isCompleteEvent = (event and (FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO)) != 0
        if (isCompleteEvent) completedFilePaths.add(path)

        val displayedPath = displayedFile?.let { filePath(it) }

        // Motorola può scrivere su un nome provvisorio e poi rinominare la foto.
        // Seguiamo MOVED_FROM/MOVED_TO per aggiornare il riferimento usato dal popup.
        if (isMovedFrom) {
            if (path == displayedPath) {
                displayedFileWasMoved = true
                handledPathsAwaitingRename.add(path)
            }
            if (pendingPopupFile?.let { filePath(it) } == path) pendingPopupWasMoved = true
            if (handledFilePaths.contains(path) || popupQueuedOrShownPaths.contains(path)) {
                handledPathsAwaitingRename.add(path)
            }
            return
        }

        if (isMovedTo && file.isFile && file.length() > 0L) {
            if (overlay != null && displayedFileWasMoved) {
                displayedFile = file
                displayedFileWasMoved = false
                completedFilePaths.add(path)
                photoDateTakenMillis = file.lastModified().takeIf { it > 0L }
                sourceReadyForSave = true
                cancelReadinessCheck()
                updateSaveButtonState(true, saving = saveInProgress)
                if (pendingSaveFile != null) pendingSaveFile = file
                if (saveInProgress) handler.post { continuePendingSave(file) }
                return
            }
            if (pendingPopupWasMoved && pendingPopupFile != null) {
                pendingPopupFile = file
                pendingPopupWasMoved = false
                popupQueuedOrShownPaths.add(path)
                photoDateTakenMillis = file.lastModified().takeIf { it > 0L }
                return
            }
            if (overlay == null && handledPathsAwaitingRename.remove(path)) {
                handledFilePaths.add(filePath(file))
                popupQueuedOrShownPaths.add(filePath(file))
                completedFilePaths.add(filePath(file))
                return
            }
        }

        if (path == displayedPath) {
            if (isCompleteEvent && file.isFile && file.length() > 0L) {
                sourceReadyForSave = true
                cancelReadinessCheck()
                updateSaveButtonState(true, saving = saveInProgress)
                if (pendingSaveFile?.let { filePath(it) } == path) {
                    handler.post { continuePendingSave(file) }
                }
            }
            return
        }

        // CLOSE_WRITE conferma il completamento, ma non genera un secondo popup.
        if (handledFilePaths.contains(path) || popupQueuedOrShownPaths.contains(path) || overlay != null) return
        // Dopo il salvataggio la fotocamera può consegnare in ritardo un evento
        // CREATE/MOVED_TO relativo allo stesso scatto. Non riaprire il popup vuoto.
        if (System.currentTimeMillis() < suppressCameraPopupsUntil) {
            handledFilePaths.add(path)
            popupQueuedOrShownPaths.add(path)
            return
        }
        val isNewFileEvent = (event and (FileObserver.CREATE or FileObserver.MOVED_TO)) != 0
        if (!isNewFileEvent || !file.exists()) return
        if (pendingPopupFile != null) return

        popupQueuedOrShownPaths.add(path)
        pendingPopupFile = file
        photoDateTakenMillis = file.lastModified().takeIf { it > 0L }
        val show = Runnable {
            // Durante i 500 ms il file potrebbe essere stato rinominato.
            val currentFile = pendingPopupFile ?: return@Runnable
            val currentPath = filePath(currentFile)
            pendingPopupFile = null
            pendingPopupWasMoved = false
            pendingPopupRunnable = null
            if (overlay != null || handledFilePaths.contains(currentPath) || !currentFile.exists()) return@Runnable
            sourceReadyForSave = completedFilePaths.contains(currentPath) && currentFile.length() > 0L
            photoDateTakenMillis = currentFile.lastModified().takeIf { it > 0L }
            showNameOverlay(currentFile)
        }
        pendingPopupRunnable = show
        handler.postDelayed(show, 500L)
    }
    private fun continuePendingSave(file: File) {
        if (overlay == null || !saveInProgress || pendingSaveFile?.let { filePath(it) } != filePath(file)) return
        if (!file.isFile || file.length() <= 0L) return
        sourceReadyForSave = true
        cancelReadinessCheck()
        updateSaveButtonState(true, saving = true)
        pendingSaveFile = null
        pendingSaveLastLength = -1L
        pendingSaveStableChecks = 0
        savePhotoToArchive()
    }

    // Il pulsante diventa verde e cliccabile solo quando il file originale
    // è stato chiuso dalla fotocamera o la sua dimensione è rimasta stabile.
    private fun updateSaveButtonState(ready: Boolean, saving: Boolean = false) {
        saveButton?.apply {
            isEnabled = ready && !saving
            text = when {
                saving -> "SALVATAGGIO…"
                ready -> "SALVA IN FA"
                else -> "ATTENDI FOTO…"
            }
            alpha = if (ready && !saving) 1.0f else 0.72f
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (ready && !saving) 0xFF2EAD62.toInt() else 0xFF667781.toInt()
            )
            setTextColor(0xFFFFFFFF.toInt())
        }
    }

    private fun cancelReadinessCheck() {
        readyCheckRunnable?.let { handler.removeCallbacks(it) }
        readyCheckRunnable = null
        readyCheckLastLength = -1L
        readyCheckStableChecks = 0
        readyCheckStartedAt = 0L
    }

    private fun startReadinessCheck(file: File) {
        cancelReadinessCheck()
        readyCheckStartedAt = System.currentTimeMillis()
        val path = filePath(file)
        val check = object : Runnable {
            override fun run() {
                if (overlay == null || displayedFile?.let { filePath(it) } != path) return
                val length = if (file.isFile) file.length() else 0L
                if (completedFilePaths.contains(path) && length > 0L) {
                    sourceReadyForSave = true
                    cancelReadinessCheck()
                    updateSaveButtonState(true)
                    return
                }
                if (length > 0L) {
                    if (length == readyCheckLastLength) readyCheckStableChecks++
                    else readyCheckStableChecks = 0
                    readyCheckLastLength = length
                } else {
                    readyCheckLastLength = -1L
                    readyCheckStableChecks = 0
                }
                // Fallback per i dispositivi che non consegnano CLOSE_WRITE.
                if (length > 0L && readyCheckStableChecks >= 8) {
                    completedFilePaths.add(path)
                    sourceReadyForSave = true
                    cancelReadinessCheck()
                    updateSaveButtonState(true)
                    return
                }
                if (System.currentTimeMillis() - readyCheckStartedAt >= 30000L) {
                    readyCheckRunnable = null
                    saveButton?.apply { isEnabled = false; text = "FILE NON PRONTO" }
                    return
                }
                handler.postDelayed(this, 100L)
            }
        }
        readyCheckRunnable = check
        handler.postDelayed(check, 100L)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Sessione fotocamera FA", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Mantiene attivo il campo nome durante gli scatti" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("FA - Foto Archivio")
            .setContentText("In attesa dello scatto della fotocamera")
            .setOngoing(true)
            .build()
    }

    private fun hasAnyLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun getPhotoDateTaken(uri: Uri): Long? {
        return try {
            contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DATE_TAKEN), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it > 0L } else null
            }
        } catch (_: Exception) { null }
    }

    private fun startLocationCapture() {
        stopLocationUpdates()
        if (!hasAnyLocationPermission()) return
        try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            locationManager = manager
            val now = System.currentTimeMillis()
            manager.getProviders(true).forEach { provider ->
                try {
                    val last = manager.getLastKnownLocation(provider)
                    if (last != null && now - last.time in 0..120_000L) {
                        val current = candidateLocation
                        if (current == null || last.accuracy < current.accuracy) candidateLocation = last
                    }
                } catch (_: SecurityException) { }
            }
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val current = candidateLocation
                    if (current == null || location.accuracy < current.accuracy ||
                        (location.time > current.time && location.accuracy <= current.accuracy + 15f)) {
                        candidateLocation = location
                    }
                }
            }
            locationListener = listener
            // Aggiornamenti GPS asincroni durante la sessione: non bloccano mai
            // l'apertura della fotocamera né il rilevamento dello scatto.
            manager.getProviders(true).forEach { provider ->
                try {
                    manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                } catch (_: SecurityException) { }
                  catch (_: IllegalArgumentException) { }
            }

        } catch (_: Exception) {
            stopLocationUpdates()
        }
    }

    private fun stopLocationUpdates() {
        val manager = locationManager
        val listener = locationListener
        if (manager != null && listener != null) {
            try { manager.removeUpdates(listener) } catch (_: Exception) { }
        }
        locationListener = null
        locationManager = null
    }

    private fun writePhotoMetadata(file: File): Boolean {
        return try {
            val exif = ExifInterface(file)
            val takenAt = photoDateTakenMillis
            if (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).isNullOrBlank() && takenAt != null) {
                val formatter = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formatter.format(Date(takenAt)))
                exif.setAttribute(ExifInterface.TAG_DATETIME, formatter.format(Date(takenAt)))
            }
            val location = candidateLocation?.takeIf {
                System.currentTimeMillis() - it.time in 0..120_000L
            }
            if (location != null) {
                exif.setLatLong(location.latitude, location.longitude)
                if (location.hasAltitude()) {
                    val altitudeHundredths = kotlin.math.round(kotlin.math.abs(location.altitude) * 100.0).toLong()
                    exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "$altitudeHundredths/100")
                    exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, if (location.altitude < 0) "1" else "0")
                }
                val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = location.time }
                exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, String.format(Locale.US, "%04d:%02d:%02d",
                    utc.get(Calendar.YEAR), utc.get(Calendar.MONTH) + 1, utc.get(Calendar.DAY_OF_MONTH)))
                exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, String.format(Locale.US, "%d/1,%d/1,%d/1",
                    utc.get(Calendar.HOUR_OF_DAY), utc.get(Calendar.MINUTE), utc.get(Calendar.SECOND)))
            }
            exif.saveAttributes()
            val check = ExifInterface(file).latLong
            if (check != null) {
                gpsDiagnostic = if (location != null) {
                    "GPS scritto e verificato • ${"%.6f".format(Locale.US, check[0])}, ${"%.6f".format(Locale.US, check[1])}" +
                        (if (location.hasAccuracy()) " • ±${location.accuracy.toInt()} m" else "")
                } else {
                    "GPS già presente nell'originale • ${"%.6f".format(Locale.US, check[0])}, ${"%.6f".format(Locale.US, check[1])}"
                }
                true
            } else {
                val age = candidateLocation?.let { ((System.currentTimeMillis() - it.time).coerceAtLeast(0L) / 1000L) }
                gpsDiagnostic = if (age != null) "GPS non scritto: ultimo fix di ${age}s" else
                    "GPS non scritto: nessun fix disponibile"
                false
            }
        } catch (e: Exception) {
            gpsDiagnostic = "Errore EXIF: ${e.localizedMessage ?: e.javaClass.simpleName}"
            false
        }
    }
    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun showNameOverlay(sourceFile: File) {
        if (overlay != null || !android.provider.Settings.canDrawOverlays(this)) return
        saveInProgress = false
        sourceReadyForSave = sourceFile?.let { completedFilePaths.contains(filePath(it)) } ?: true
        pendingSaveFile = null
        displayedFileWasMoved = false
        if (sourceFile != null && pendingPopupFile?.let { filePath(it) } == filePath(sourceFile)) {
            pendingPopupRunnable?.let { handler.removeCallbacks(it) }
            pendingPopupRunnable = null
            pendingPopupFile = null
            pendingPopupWasMoved = false
        }
        pendingSaveStartedAt = 0L
        displayedFile = sourceFile

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(0xF21B2933.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(2), 0xFF9DB9D2.toInt())
            }
            elevation = dp(10).toFloat()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val destinationLabel = TextView(this).apply {
            text = "FOTO SCATTATA  •  $category / $folder"
            textSize = 14f
            setTextColor(0xFFF4EBDD.toInt())
            setPadding(0, 0, 0, dp(6))
        }
        folderLabel = destinationLabel
        header.addView(destinationLabel, LinearLayout.LayoutParams(0, dp(44), 1f))
        val close = Button(this).apply {
            text = "✕"
            contentDescription = "Chiudi senza salvare"
            isAllCaps = false
            minWidth = dp(44)
            setOnClickListener {
                if (saveInProgress) Toast.makeText(this@CameraOverlayService, "Attendi la fine del salvataggio", Toast.LENGTH_SHORT).show()
                else dismissOverlay(markAsHandled = true)
            }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(48), dp(44)))
        panel.addView(header)
        panel.addView(TextView(this).apply {
            text = currentGpsStatus()
            textSize = 12f
            setTextColor(0xFFD8D5D0.toInt())
            setPadding(0, 0, 0, dp(6))
        })
        val saveStatus = TextView(this).apply {
            text = "Pronta per il salvataggio"
            textSize = 12f
            setTextColor(0xFFD8D5D0.toInt())
            setPadding(0, 0, 0, dp(6))
        }
        saveStatusLabel = saveStatus
        panel.addView(saveStatus)
        val input = object : EditText(this) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus) {
                    requestFocus()
                    post {
                        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .showSoftInput(this, 0)
                    }
                }
            }
        }.apply {
            hint = "Nome della foto"
            isSingleLine = true
            isFocusable = true
            isFocusableInTouchMode = true
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFB8C7D2.toInt())
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply {
                setColor(0xFF263744.toInt())
                cornerRadius = dp(9).toFloat()
                setStroke(dp(1), 0xFF9DB9D2.toInt())
            }
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        nameInput = input
        panel.addView(input)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val changeFolder = Button(this).apply {
            text = "CAMBIA CARTELLA"
            isAllCaps = false
            setOnClickListener { if (!saveInProgress) showFolderPicker() }
        }
        val save = Button(this).apply {
            text = if (sourceReadyForSave) "SALVA IN FA" else "ATTENDI FOTO…"
            isAllCaps = false
            setOnClickListener {
                if (!saveInProgress && sourceReadyForSave) {
                    saveInProgress = true
                    updateSaveButtonState(true, saving = true)
                    savePhotoToArchive()
                }
            }
        }
        saveButton = save
        actions.addView(changeFolder, LinearLayout.LayoutParams(0, dp(48), 1.15f))
        actions.addView(save, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(actions)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(74)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        try {
            windowManager.addView(panel, params)
            overlay = panel
            if (sourceReadyForSave) updateSaveButtonState(true)
            else {
                updateSaveButtonState(false)
                startReadinessCheck(sourceFile)
            }
            panel.isFocusableInTouchMode = true
            input.requestFocus()
            input.post {
                input.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(input, 0)
            }
            // Secondo tentativo dopo il completamento del focus della finestra overlay.
            input.postDelayed({
                if (overlay === panel && input.hasWindowFocus()) {
                    input.requestFocus()
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(input, 0)
                }
            }, 500L)
        } catch (_: Exception) {
            overlay = null
            displayedFile = null
        }
    }

    private fun currentGpsStatus(): String {
        val location = candidateLocation ?: return "GPS: nessuna posizione recente disponibile"
        val ageSeconds = ((System.currentTimeMillis() - location.time).coerceAtLeast(0L)) / 1000L
        return "GPS rilevato • precisione ±${location.accuracy.toInt()} m • ${ageSeconds}s fa"
    }

    private fun availableFolders(targetCategory: String): List<String> {
        val prefs = getSharedPreferences("fa_settings", Context.MODE_PRIVATE)
        val saved = prefs.getStringSet("folders_$targetCategory", emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        val root = File(Environment.getExternalStorageDirectory(),
            "Pictures/FotoArchivio/$targetCategory")
        if (root.exists()) {
            root.walkTopDown().filter { it.isDirectory && it != root }.forEach { dir ->
                val relative = dir.relativeTo(root).path.replace(File.separatorChar, '/').trim('/')
                if (relative.isNotBlank() && !relative.startsWith(".FA_TEMP")) saved.add(relative)
            }
        }
        return saved.sorted()
    }

    private fun showFolderPicker() {
        val choices = mutableListOf<Pair<String, String>>()
        listOf("PERSONALE", "LAVORO").forEach { cat ->
            choices.add(cat to "")
            availableFolders(cat).forEach { path -> choices.add(cat to path) }
        }
        val labels = choices.map { (cat, path) ->
            (if (cat == category) "✓ " else "") + "$cat  /  " +
                if (path.isBlank()) "Cartella principale" else path
        }.toTypedArray()
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Scegli la cartella di destinazione")
            .setItems(labels) { _, which ->
                category = choices[which].first
                folder = choices[which].second
                getSharedPreferences("fa_settings", Context.MODE_PRIVATE).edit()
                    .putString("active_category", category)
                    .putString("last_folder_$category", folder)
                    .apply()
                folderLabel?.text = "FOTO SCATTATA  •  $category / $folder"
                Toast.makeText(this, "Destinazione: $category / $folder", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private fun safeName(value: String): String =
        value.trim().replace(Regex("[\\\\/:*?<>|\"\\n\\r]"), "_")
            .trim('.').ifBlank { "" }

    private fun nextAutomaticPhotoName(directory: File, extension: String): String {
        var number = 1
        while (File(directory, "foto$number.$extension").exists()) number++
        return "foto$number"
    }

    private fun savePhotoToArchive() {
        val sourceFile = displayedFile
        if (sourceFile == null) {
            saveInProgress = false
            updateSaveButtonState(sourceReadyForSave)
            Toast.makeText(this, "Foto sorgente non disponibile", Toast.LENGTH_LONG).show()
            return
        }
        if (!sourceReadyForSave) {
            if (pendingSaveFile?.let { filePath(it) } != filePath(sourceFile)) {
                pendingSaveLastLength = -1L
                pendingSaveStableChecks = 0
            }
            pendingSaveFile = sourceFile
            if (pendingSaveStartedAt == 0L) pendingSaveStartedAt = System.currentTimeMillis()
            updateSaveButtonState(false, saving = true)
            waitForCameraFileClose(sourceFile)
            return
        }

        val requestedName = safeName(nameInput?.text?.toString().orEmpty())
        val categorySnapshot = category.takeIf { it == "PERSONALE" || it == "LAVORO" } ?: "PERSONALE"
        val folderSnapshot = folder.trim('/').split('/').filter { it.isNotBlank() }
            .map { safeName(it) }.filter { it.isNotBlank() }
        val extension = sourceFile.extension.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]"), "").ifBlank { "jpg" }

        if (archiveCopyInProgress) return
        val operationId = ++saveOperationId
        archiveCopyInProgress = true
        // La copia e la scrittura EXIF avvengono fuori dal thread grafico.
        // L'operazione resta associata al popup, non al nome provvisorio:
        // Motorola può rinominare il file mentre il salvataggio è in corso.
        Thread({
            var temporaryFile: File? = null
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    throw SecurityException("Autorizzazione archivio non attiva")
                }
                val archiveRoot = File(Environment.getExternalStorageDirectory(), "Pictures/FotoArchivio")
                val destinationDir = File(archiveRoot, categorySnapshot + File.separator + folderSnapshot.joinToString(File.separator))
                val rootCanonical = archiveRoot.canonicalFile
                val dirCanonical = destinationDir.canonicalFile
                if (dirCanonical.path != rootCanonical.path &&
                    !dirCanonical.path.startsWith(rootCanonical.path + File.separator)) {
                    throw SecurityException("Percorso archivio non valido")
                }
                if (!destinationDir.exists() && !destinationDir.mkdirs()) {
                    throw java.io.IOException("Non riesco a creare la cartella di archivio")
                }
                if (!destinationDir.isDirectory || !destinationDir.canWrite()) {
                    throw java.io.IOException("Cartella archivio non accessibile in scrittura")
                }

                val baseName = if (requestedName.isBlank()) {
                    nextAutomaticPhotoName(destinationDir, extension)
                } else requestedName
                val destination = File(destinationDir, "$baseName.$extension")
                if (destination.exists()) throw java.io.IOException("Nome già presente in questa cartella")

                val actualSource = sourceFile.takeIf { it.isFile && it.length() > 0L }
                    ?: displayedFile?.takeIf { it.isFile && it.length() > 0L }
                    ?: sourceFile
                if (!actualSource.isFile || actualSource.length() <= 0L) {
                    throw java.io.IOException("Foto originale non disponibile: controlla che sia ancora in DCIM/Camera")
                }
                val sourceLengthBefore = actualSource.length()
                val tempFile = File(destinationDir, ".$baseName.fa_tmp_${System.nanoTime()}.$extension")
                temporaryFile = tempFile
                actualSource.inputStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                val currentSourceAfter = actualSource.takeIf { it.isFile }
                    ?: displayedFile?.takeIf { it.isFile } ?: actualSource
                val sourceLengthAfter = currentSourceAfter.length()
                if (sourceLengthBefore != sourceLengthAfter || tempFile.length() != sourceLengthAfter) {
                    throw java.io.IOException("La fotocamera sta ancora completando la foto: premi SALVA di nuovo")
                }

                // La scrittura EXIF può cambiare la dimensione del file:
                // la verifica finale deve confrontare il file rinominato con
                // la dimensione effettiva del temporaneo DOPO i metadati.
                writePhotoMetadata(tempFile)
                val expectedFinalLength = tempFile.length()
                if (expectedFinalLength <= 0L) {
                    throw java.io.IOException("La copia temporanea è vuota dopo i metadati")
                }
                if (destination.exists() || !tempFile.renameTo(destination)) {
                    throw java.io.IOException("Non riesco a completare il trasferimento nell'archivio")
                }
                if (!destination.isFile || destination.length() != expectedFinalLength) {
                    val actualLength = if (destination.exists()) destination.length() else -1L
                    destination.delete()
                    throw java.io.IOException("Verifica finale non riuscita (attesi $expectedFinalLength byte, trovati $actualLength)")
                }

                handler.post {
                    if (overlay != null && saveOperationId == operationId) {
                        archiveCopyInProgress = false
                        Toast.makeText(
                            this,
                            "Salvata come $baseName.$extension in FA/$categorySnapshot/${folderSnapshot.joinToString("/")}",
                            Toast.LENGTH_LONG
                        ).show()
                        dismissOverlay(markAsHandled = true)
                    }
                }
            } catch (e: Exception) {
                try { temporaryFile?.delete() } catch (_: Exception) { }
                handler.post {
                    if (overlay != null && saveOperationId == operationId) {
                        archiveCopyInProgress = false
                        saveInProgress = false
                        pendingSaveFile = null
                        pendingSaveStartedAt = 0L
                        updateSaveButtonState(sourceReadyForSave)
                        saveStatusLabel?.text = "ERRORE: ${e.javaClass.simpleName} — ${e.localizedMessage ?: "nessun dettaglio"}"
                        saveStatusLabel?.setTextColor(0xFFFF8A80.toInt())
                        Toast.makeText(
                            this,
                            "Salvataggio non riuscito: ${e.localizedMessage ?: e.javaClass.simpleName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }, "FA-archive-copy").start()
    }

    private fun waitForCameraFileClose(file: File) {
        if (overlay == null || !saveInProgress || pendingSaveFile?.let { filePath(it) } != filePath(file)) return

        // CLOSE_WRITE è il segnale principale. Come rete di sicurezza, se Android
        // non lo consegna, accettiamo il file solo dopo 8 controlli consecutivi
        // con dimensione invariata (circa 800 ms).
        val length = if (file.isFile) file.length() else 0L
        if (length > 0L) {
            if (length == pendingSaveLastLength) pendingSaveStableChecks++
            else pendingSaveStableChecks = 0
            pendingSaveLastLength = length
        } else {
            pendingSaveLastLength = -1L
            pendingSaveStableChecks = 0
        }

        val completed = completedFilePaths.contains(filePath(file)) ||
            (length > 0L && pendingSaveStableChecks >= 8)
        if (completed) {
            sourceReadyForSave = true
            pendingSaveFile = null
            pendingSaveLastLength = -1L
            pendingSaveStableChecks = 0
            savePhotoToArchive()
            return
        }
        if (System.currentTimeMillis() - pendingSaveStartedAt > 15000L) {
            saveInProgress = false
            pendingSaveFile = null
            pendingSaveLastLength = -1L
            pendingSaveStableChecks = 0
            pendingSaveStartedAt = 0L
            updateSaveButtonState(sourceReadyForSave)
            Toast.makeText(this, "La foto non risulta ancora completa. Riprova SALVA.", Toast.LENGTH_LONG).show()
            return
        }
        handler.postDelayed({ waitForCameraFileClose(file) }, 100L)
    }

    private fun dismissOverlay(markAsHandled: Boolean) {
        cancelReadinessCheck()
        if (markAsHandled) {
            suppressCameraPopupsUntil = System.currentTimeMillis() + 900L
            pendingPopupRunnable?.let { handler.removeCallbacks(it) }
            pendingPopupRunnable = null
            pendingPopupFile?.let { pending ->
                val pendingPath = filePath(pending)
                handledFilePaths.add(pendingPath)
                popupQueuedOrShownPaths.add(pendingPath)
                handledPathsAwaitingRename.add(pendingPath)
            }
            pendingPopupFile = null
            pendingPopupWasMoved = false
        }
        val panel = overlay
        // Azzera prima lo stato: nessun controllo successivo deve considerare
        // ancora aperta la finestra appena salvata.
        overlay = null
        panel?.let {
            it.visibility = android.view.View.GONE
            try {
                windowManager.removeViewImmediate(it)
            } catch (_: Exception) {
                try { windowManager.removeView(it) } catch (_: Exception) { }
            }
        }
        val input = nameInput
        if (input != null) {
            try {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(input.windowToken, 0)
            } catch (_: Exception) { }
        }
        nameInput = null
        saveButton = null
        saveInProgress = false
        archiveCopyInProgress = false
        saveOperationId++
        sourceReadyForSave = false
        pendingSaveFile = null
        pendingSaveLastLength = -1L
        pendingSaveStableChecks = 0
        pendingSaveStartedAt = 0L
        folderLabel = null
        if (markAsHandled) displayedFile?.let { file ->
            val path = filePath(file)
            handledFilePaths.add(path)
            popupQueuedOrShownPaths.add(path)
            // Se il nome provvisorio viene rinominato dopo la chiusura,
            // handleCameraFileEvent trasferirà la protezione al nome finale.
            handledPathsAwaitingRename.add(path)
        }
        displayedFile = null
        displayedFileWasMoved = false
        // Conserva il fix acquisito prima di aprire la fotocamera per gli scatti
        // successivi della stessa sessione; writePhotoMetadata lo usa solo se recente.
        photoDateTakenMillis = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Chiudendo FA dal selettore delle app recenti, rimuove il popup
        // e arresta il servizio che lo mantiene visibile.
        pendingPopupRunnable?.let { handler.removeCallbacks(it) }
        pendingPopupRunnable = null
        pendingPopupFile = null
        pendingPopupWasMoved = false
        dismissOverlay(markAsHandled = false)
        stopLocationUpdates()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cancelReadinessCheck()
        cameraDirectoryObserver?.stopWatching()
        cameraDirectoryObserver = null
        stopLocationUpdates()
        dismissOverlay(markAsHandled = false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
// Build verification: archive save runs on a worker and reports only verified completion.
