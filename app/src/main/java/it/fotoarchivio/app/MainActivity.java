package it.fotoarchivio.app;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.database.Cursor;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executor;

public class MainActivity extends AppCompatActivity {
    private final Executor mainExecutor = ContextCompat.getMainExecutor(this);
    private SharedPreferences prefs;
    private String folder = "Juvanum";
    private PreviewView previewView;
    private ImageCapture imageCapture;
    private File pendingPhoto;
    private Location lastLocation;
    private FrameLayout root;
    private final ActivityResultLauncher<String[]> permissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            if (Boolean.TRUE.equals(result.get(Manifest.permission.CAMERA))) startCamera();
            else showMessage("Per usare FotoArchivio serve il permesso Fotocamera.");
            if (Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION)) ||
                Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION))) updateLocation();
        });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("fotoarchivio", MODE_PRIVATE);
        folder = prefs.getString("active_folder", "Juvanum");
        showCameraScreen();
        requestPermissionsIfNeeded();
    }

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView text(String value, int size) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(0xff17324d);
        return t;
    }

    private Button button(String value) {
        Button b = new Button(this); b.setText(value); return b;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(12), dp(16), dp(12));
        l.setBackgroundColor(0xffeef2f5);
        return l;
    }

    private void requestPermissionsIfNeeded() {
        boolean camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!camera) permissionLauncher.launch(new String[]{Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
        else { startCamera(); if (location) updateLocation(); }
    }

    private void showCameraScreen() {
        root = new FrameLayout(this);
        LinearLayout page = column();
        TextView title = text("FotoArchivio", 25); title.setGravity(Gravity.CENTER);
        page.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));
        Button folderButton = button("Cartella: " + folder + "   ·   Cambia cartella");
        folderButton.setOnClickListener(v -> chooseFolder());
        page.addView(folderButton, new LinearLayout.LayoutParams(-1, dp(52)));
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        LinearLayout.LayoutParams cameraParams = new LinearLayout.LayoutParams(-1, 0, 1);
        cameraParams.setMargins(0, dp(8), 0, dp(8));
        page.addView(previewView, cameraParams);
        Button capture = button("SCATTA FOTO");
        capture.setTextSize(19);
        capture.setOnClickListener(v -> takePhoto());
        page.addView(capture, new LinearLayout.LayoutParams(-1, dp(64)));
        root.addView(page);
        setContentView(root);
    }

    private void chooseFolder() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint("Nome nuova cartella");
        new AlertDialog.Builder(this).setTitle("Cartella attiva")
            .setMessage("Scrivi il nome di una cartella nuova oppure conferma quella attuale.")
            .setView(input)
            .setNegativeButton("Annulla", null)
            .setNeutralButton("Usa " + folder, (d,w) -> showCameraScreen())
            .setPositiveButton("Cambia", (d,w) -> {
                String name = input.getText().toString().trim();
                if (!name.isEmpty()) {
                    folder = cleanFolder(name);
                    prefs.edit().putString("active_folder", folder).apply();
                    showCameraScreen();
                }
            }).show();
    }

    private String cleanFolder(String s) {
        String out = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return out.isEmpty() ? "Juvanum" : out;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                imageCapture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);
            } catch (Exception e) { showMessage("Impossibile avviare la fotocamera: " + e.getMessage()); }
        }, mainExecutor);
    }

    private void takePhoto() {
        if (imageCapture == null) { showMessage("Fotocamera non ancora pronta."); return; }
        try {
            pendingPhoto = File.createTempFile("fotoarchivio_", ".jpg", getCacheDir());
            ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(pendingPhoto).build();
            imageCapture.takePicture(options, mainExecutor, new ImageCapture.OnImageSavedCallback() {
                @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults result) {
                    updateLocation();
                    showReviewScreen();
                }
                @Override public void onError(@NonNull ImageCaptureException error) {
                    showMessage("Errore nello scatto: " + error.getMessage());
                }
            });
        } catch (Exception e) { showMessage("Non riesco a preparare la foto."); }
    }

    private void showReviewScreen() {
        LinearLayout page = column();
        TextView title = text("Controlla la fotografia", 22);
        page.addView(title);
        ImageView image = new ImageView(this);
        image.setImageBitmap(BitmapFactory.decodeFile(pendingPhoto.getAbsolutePath()));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(-1, 0, 1);
        imageParams.setMargins(0, dp(8), 0, dp(8));
        page.addView(image, imageParams);
        EditText filename = new EditText(this);
        filename.setSingleLine(true);
        filename.setText(nextAutomaticName());
        filename.setSelectAllOnFocus(true);
        filename.setHint("Nome fotografia");
        page.addView(filename, new LinearLayout.LayoutParams(-1, dp(56)));
        TextView gps = text(lastLocation == null ? "Posizione GPS non disponibile (la foto verrà salvata lo stesso)." :
            String.format(Locale.ITALY, "GPS: %.6f, %.6f", lastLocation.getLatitude(), lastLocation.getLongitude()), 13);
        page.addView(gps);
        LinearLayout actions = new LinearLayout(this);
        Button retake = button("RIFAI");
        retake.setOnClickListener(v -> { if (pendingPhoto != null) pendingPhoto.delete(); showCameraScreen(); startCamera(); });
        Button save = button("CONFERMA E SALVA");
        save.setOnClickListener(v -> savePhoto(filename.getText().toString()));
        actions.addView(retake, new LinearLayout.LayoutParams(0, dp(58), 1));
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(58), 1));
        page.addView(actions);
        setContentView(page);
        filename.requestFocus();
        filename.post(() -> {
            filename.setSelection(filename.getText().length());
            ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
                .showSoftInput(filename, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private int nextNumber() { return prefs.getInt("counter_" + folder, 1); }
    private String nextAutomaticName() { return folder + "_" + String.format(Locale.US, "%03d", nextNumber()); }

    private String uniqueName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) name = nextAutomaticName();
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!name.toLowerCase(Locale.ROOT).endsWith(".jpg")) name += ".jpg";
        String base = name.substring(0, name.length() - 4);
        String candidate = name; int n = 2;
        while (exists(candidate)) candidate = base + "_" + (n++) + ".jpg";
        return candidate;
    }

    private boolean exists(String displayName) {
        String relative = Environment.DIRECTORY_PICTURES + "/FotoArchivio/" + folder + "/";
        String[] projection = {MediaStore.Images.Media._ID};
        try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, MediaStore.Images.Media.DISPLAY_NAME + "=? AND " + MediaStore.Images.Media.RELATIVE_PATH + "=?",
            new String[]{displayName, relative}, null)) { return c != null && c.moveToFirst(); }
        catch (Exception e) { return false; }
    }

    private void savePhoto(String requestedName) {
        if (pendingPhoto == null || !pendingPhoto.exists()) { showMessage("La foto temporanea non è disponibile."); return; }
        String finalName = uniqueName(requestedName);
        String relative = Environment.DIRECTORY_PICTURES + "/FotoArchivio/" + folder + "/";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, finalName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, relative);
        values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        Uri uri = null;
        try {
            uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("Archivio non disponibile");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                java.nio.file.Files.copy(pendingPhoto.toPath(), out);
            }
            if (lastLocation != null) {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    File exifTemp = File.createTempFile("exif_", ".jpg", getCacheDir());
                    try (OutputStream out = new java.io.FileOutputStream(exifTemp)) {
                        byte[] buffer = new byte[8192]; int count;
                        while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
                    }
                    ExifInterface exif = new ExifInterface(exifTemp.getAbsolutePath());
                    exif.setLatLong(lastLocation.getLatitude(), lastLocation.getLongitude());
                    exif.saveAttributes();
                    try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                        java.nio.file.Files.copy(exifTemp.toPath(), out);
                    }
                    exifTemp.delete();
                } catch (Exception ignored) { }
            }
            pendingPhoto.delete(); pendingPhoto = null;
            String auto = nextAutomaticName() + ".jpg";
            if (finalName.equalsIgnoreCase(auto)) prefs.edit().putInt("counter_" + folder, nextNumber() + 1).apply();
            showMessage("Foto salvata in Pictures/FotoArchivio/" + folder + "/" + finalName);
            showCameraScreen(); startCamera();
        } catch (Exception e) {
            if (uri != null) getContentResolver().delete(uri, null, null);
            showMessage("Salvataggio non riuscito: " + e.getMessage());
        }
    }

    private void updateLocation() {
        try {
            boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (!fine && !coarse) { lastLocation = null; return; }
            LocationManager lm = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            lastLocation = best;
        } catch (Exception e) { lastLocation = null; }
    }

    private void showMessage(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
