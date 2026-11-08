# FA — Foto Archivio

FA è l'app Android nativa in sviluppo per archiviare fotografie usando la fotocamera installata sul telefono.

## Progetto Android

Il progetto si trova in `FA-Android/`.

### Flusso attuale (prima versione)

1. All'avvio si sceglie PERSONALE oppure LAVORO.
2. FA apre la fotocamera di sistema tramite l'azione standard Android `TakePicture`. Sul Motorola verrà usata l'app fotocamera predefinita del dispositivo, se impostata come gestore predefinito.
3. Dopo lo scatto compare il modulo di archiviazione con tastiera, nome file, scelta PERSONALE/LAVORO e sottocartella.
4. SALVA rinomina e sposta la foto in `Pictures/FotoArchivio/PERSONALE/<sottocartella>/` oppure `Pictures/FotoArchivio/LAVORO/<sottocartella>/`.
5. ELIMINA cancella lo scatto e riapre la fotocamera.
6. Gestione archivio permette di elencare, rinominare, spostare ed eliminare le foto. È disponibile anche la rinomina e la rimozione delle sottocartelle vuote.

Le sottocartelle create dal modulo vengono ricordate nelle preferenze dell'app; la foto viene salvata come file immagine reale in MediaStore, visibile anche alle altre app.

## Compilazione APK

Il workflow GitHub Actions `.github/workflows/build-fa-android.yml` compila automaticamente la versione debug quando cambiano i file in `FA-Android/`, oppure manualmente da Actions > Build FA Android APK > Run workflow.

Al termine, scaricare l'artifact **FA-Foto-Archivio-debug** e installare `app-debug.apk`.

## Note e limiti della prima versione

- La fotocamera è avviata come app esterna Android: FA non disegna selettori sopra il mirino.
- La disponibilità di modalità come Notte, Panorama e controlli manuali dipende dalla fotocamera predefinita Motorola e dal comportamento dell'app fotocamera con l'azione di acquisizione.
- L'app richiede Android 10 o successivo.
- Questa è una prima base funzionale da provare sul Motorola. Prima di considerarla definitiva occorre verificare il comportamento reale del passaggio alla fotocamera, il salvataggio e la gestione cartelle sul dispositivo.

## Candidato 0.5.2

Questa build di prova include:
- selezione di cartelle e foto senza ricostruire l'intera schermata a ogni tocco;
- gestione del salvataggio collegata alla sessione del popup, anche se Motorola rinomina il file durante la copia;
- verifica della copia prima della conferma e gestione del nome automatico `fotoN`;
- reset una tantum dell'archivio FA alla prima apertura della 0.5.2: PERSONALE e LAVORO vengono ricreate vuote e le preferenze delle sottocartelle vengono azzerate;
- nessuna cancellazione o modifica di `DCIM/Camera`;
- PNG fotografico di sfondo originale mantenuto separato da una piccola cinepresa vintage WebP trasparente; il PNG non viene ricostruito né modificato.
