FOTO ARCHIVIO — SORGENTI ANDROID

Versione di riferimento conservata
- APK archiviato in ChatGPT Library: Foto-Archivio-V0.0.apk
- I metadati interni dell'APK indicano versionName 0.6.1 e versionCode 15.
- applicationId: it.fa.fotoarchivio
- Sorgenti Android corrispondenti per package e versione: cartella FA-Android/
- Build firmata tramite GitHub Actions: workflow .github/workflows/build-fa-android.yml

Struttura
- FA-Android/: progetto Android nativo (Kotlin), sorgenti e risorse.
- .github/workflows/build-fa-android.yml: compilazione APK firmata.
- .github/assets/fa_vintage_movie_camera.webp.b64: risorsa usata dal workflow Android.
- Screenshot e immagini alla radice: conservati come riferimenti grafici; non eliminare senza verificarne l'uso.

Pulizia
- I vecchi file della PWA/HTML sono stati rimossi dal branch main.
- Il ramo backup-pre-punto-zero-2026-11-09 conserva lo stato precedente alla pulizia.
- Il vecchio archivio Java iniziale è conservato separatamente nella Libreria ChatGPT e non è il sorgente dell'APK sopra indicato.

Firma
- Il workflow usa i GitHub Actions secrets FA_KEYSTORE_BASE64 e FA_KEYSTORE_PASSWORD.
- Non inserire nel repository pubblico certificati, backup del certificato o password.

SAS Rapportino
- SAS-Rapportino-Android/ e il workflow build-sas-rapportino.yml sono progetti separati e non fanno parte di Foto Archivio. Non modificarli durante gli interventi su FA.
