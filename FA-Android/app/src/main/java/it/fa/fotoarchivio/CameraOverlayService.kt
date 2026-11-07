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
import android.database.ContentObserver
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
        private const val CHANNEL_ID = "fa_camera_overlay"
        private const val NOTIFICATION_ID = 7401
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var overlay: LinearLayout? = null
    private var nameInput: EditText? = null
    private var photoUri: Uri? = null
    private var category = "PERSONALE"
    private var folder = "Generale"
    private var sessionStartSeconds = 0L
    private var candidateUri: Uri? = null
    private var candidateSize = -1L
    private var stableChecks = 0
    private var displayedId = -1L
    private var lastSavedId = -1L
    private var candidateLocation: Location? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var photoDateTakenMillis: Long? = null

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            schedulePhotoCheck()
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        val notification = buildNotification()
        // Mantiene invariato il tipo di servizio già collaudato nella 0.4.1.
        // La raccolta GPS è facoltativa e non deve impedire l'avvio della sovrapposizione.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        category = intent?.getStringExtra(EXTRA_CATEGORY) ?: "PERSONALE"
        folder = intent?.getStringExtra(EXTRA_FOLDER) ?: "Generale"
        sessionStartSeconds = (intent?.getLongExtra(EXTRA_START_TIME, System.currentTimeMillis()) 
            ?: System.currentTimeMillis()) / 1000L
        // Avvia il fix GPS all'inizio della sessione, prima che l'utente scatti.
        // Mantiene gli aggiornamenti attivi durante l'uso della fotocamera.
        startLocationCapture()
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
        )
        return START_NOT_STICKY
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

    private fun schedulePhotoCheck() {
        if (overlay != null) return
        handler.removeCallbacks(photoCheck)
        handler.postDelayed(photoCheck, 1200L)
    }

    private val photoCheck = object : Runnable {
        override fun run() {
            if (overlay != null) return
            val latest = findLatestCameraPhoto() ?: return
            val uri = latest.first
            val size = latest.second
            if (uri != candidateUri) {
                candidateUri = uri
                candidateSize = size
                stableChecks = 0
                photoDateTakenMillis = getPhotoDateTaken(uri)
                handler.postDelayed(this, 900L)
                return
            }
            if (size > 0L && size == candidateSize) stableChecks++ else stableChecks = 0
            candidateSize = size
            if (stableChecks >= 1) {
                val id = uri.lastPathSegment?.toLongOrNull() ?: -1L
                if (id != lastSavedId && id != displayedId) showNameOverlay(uri, id)
            } else {
                handler.postDelayed(this, 900L)
            }
        }
    }

    private fun findLatestCameraPhoto(): Pair<Uri, Long>? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.SIZE
        )
        return try {
            contentResolver.query(
                collection, projection,
                "${MediaStore.Images.Media.DATE_ADDED} >= ?",
                arrayOf(sessionStartSeconds.toString()),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val pathColumn = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                while (cursor.moveToNext()) {
                    val path = if (pathColumn >= 0) cursor.getString(pathColumn).orEmpty() else ""
                    if (!path.startsWith("DCIM/Camera", ignoreCase = true)) continue
                    val id = cursor.getLong(idColumn)
                    if (id == lastSavedId || id == displayedId) continue
                    val size = cursor.getLong(sizeColumn)
                    return@use if (size > 0L) Uri.withAppendedPath(collection, id.toString()) to size else null
                }
                null
            }
        } catch (_: Exception) {
            null
        }
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
        candidateLocation = null
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
            // Non avvia aggiornamenti continui in background: il fix recente già
            // disponibile viene acquisito mentre FA è ancora in primo piano.
            // Così il GPS non può interferire con il servizio overlay della fotocamera.

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
            val location = candidateLocation?.takeIf { System.currentTimeMillis() - it.time <= 30_000L }
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
            location != null
        } catch (_: Exception) {
            false
        }
    }
    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun showNameOverlay(uri: Uri, id: Long) {
        if (overlay != null || !android.provider.Settings.canDrawOverlays(this)) return
        photoUri = uri
        displayedId = id

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
        panel.addView(TextView(this).apply {
            text = "FOTO SCATTATA  •  $category / $folder"
            textSize = 14f
            setTextColor(0xFFF4EBDD.toInt())
            setPadding(0, 0, 0, dp(6))
        })
        val input = EditText(this).apply {
            hint = "Nome della foto"
            isSingleLine = true
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
        val skip = Button(this).apply {
            text = "SALTA"
            isAllCaps = false
            setOnClickListener { dismissOverlay(markAsHandled = true) }
        }
        val save = Button(this).apply {
            text = "SALVA IN FA"
            isAllCaps = false
            setOnClickListener { savePhotoToArchive() }
        }
        actions.addView(skip, LinearLayout.LayoutParams(0, dp(48), 1f))
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
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        try {
            windowManager.addView(panel, params)
            overlay = panel
            input.requestFocus()
            input.postDelayed({
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }, 250L)
        } catch (_: Exception) {
            overlay = null
            photoUri = null
            displayedId = -1L
        }
    }

    private fun safeName(value: String): String =
        value.trim().replace(Regex("[\\\\/:*?<>|\"\\n\\r]"), "_")
            .trim('.').ifBlank { "" }

    private fun savePhotoToArchive() {
        val uri = photoUri ?: return
        val rawName = nameInput?.text?.toString().orEmpty()
        val baseName = safeName(rawName)
        if (baseName.isBlank()) {
            nameInput?.error = "Inserisci un nome"
            return
        }
        val sourceName = try {
            contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) { null } ?: "foto.jpg"
        val extension = sourceName.substringAfterLast('.', "jpg")
            .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "").ifBlank { "jpg" }
        val segments = folder.trim('/').split('/').filter { it.isNotBlank() }.map { safeName(it) }
        val destinationDir = File(
            Environment.getExternalStorageDirectory(),
            "Pictures/FA archivio principale/$category/${segments.joinToString("/")}"
        )
        val destination = File(destinationDir, "$baseName.$extension")
        try {
            destinationDir.mkdirs()
            if (destination.exists()) {
                nameInput?.error = "Nome già presente in questa cartella"
                return
            }
            val input = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Foto originale non leggibile")
            input.use { source ->
                FileOutputStream(destination).use { target -> source.copyTo(target) }
            }
            if (!destination.exists() || destination.length() == 0L) {
                destination.delete()
                throw IllegalStateException("Copia non riuscita")
            }
            val gpsSaved = writePhotoMetadata(destination)
            val message = if (gpsSaved) "Copia salvata in FA/$category/$folder • GPS registrato" else "Copia salvata in FA/$category/$folder • posizione non disponibile"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            dismissOverlay(markAsHandled = true)
        } catch (e: Exception) {
            Toast.makeText(this, "Errore: ${e.localizedMessage ?: "salvataggio non riuscito"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun dismissOverlay(markAsHandled: Boolean) {
        val input = nameInput
        if (input != null) {
            try {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(input.windowToken, 0)
            } catch (_: Exception) { }
        }
        overlay?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { }
        }
        overlay = null
        nameInput = null
        photoUri = null
        if (markAsHandled) lastSavedId = displayedId
        displayedId = -1L
        stopLocationUpdates()
        candidateLocation = null
        photoDateTakenMillis = null
        candidateUri = null
        candidateSize = -1L
        stableChecks = 0
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopLocationUpdates()
        try { contentResolver.unregisterContentObserver(observer) } catch (_: Exception) { }
        dismissOverlay(markAsHandled = false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}