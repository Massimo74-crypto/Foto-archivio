FOTOARCHIVIO 0.1 — PROTOTIPO HTML

CONTENUTO
- index.html: app di prova
- manifest.webmanifest + sw.js: installazione PWA/offline (serve HTTPS)
- icone PNG

COME SI COMPORTA
- Ricorda l'ultima cartella selezionata.
- Permette di creare cartelle logiche e passare dall'una all'altra.
- Apre la fotocamera posteriore tramite il selettore foto del telefono.
- Dopo lo scatto chiede un nome e prova a rilevare il GPS.
- Conserva immagini e metadati nel database locale di Chrome (IndexedDB).
- Permette di scaricare ogni immagine e un CSV con nomi, date e coordinate.

LIMITI IMPORTANTI DEL PROTOTIPO
Questa versione HTML NON scrive direttamente in cartelle reali condivise del telefono.
Le cartelle sono organizzate dentro l'archivio locale dell'app/browser; le foto sono conservate nei dati del sito.
Per installare l'icona PWA e usare fotocamera/GPS in modo affidabile è necessario pubblicare i file su un indirizzo HTTPS.
Non cancellare i dati di Chrome/sito durante la prova: potresti cancellare l'archivio locale.
La futura APK nativa userà invece cartelle reali in Pictures/FotoArchivio e potrà salvare direttamente lì.

PROVA SU MOTOROLA
1. Pubblicare tutti i file su un hosting HTTPS statico.
2. Aprire l'indirizzo con Chrome sul Motorola.
3. Concedere i permessi Fotocamera e Posizione.
4. Menu Chrome > Aggiungi a schermata Home / Installa app.
5. Aprire FotoArchivio dall'icona e fare una prova con una foto non importante.
