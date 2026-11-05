package it.fa.fotoarchivio

import android.content.ContentResolver
import android.net.Uri
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object FaZipExporter {
    fun write(resolver: ContentResolver, output: Uri, photos: List<Triple<Uri, String, String>>) {
        resolver.openOutputStream(output)?.use { stream ->
            ZipOutputStream(stream).use { zip ->
                photos.forEach { (uri, folder, name) ->
                    zip.putNextEntry(ZipEntry((if (folder.isBlank()) "" else "$folder/") + name))
                    resolver.openInputStream(uri)?.use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: throw IllegalStateException("Impossibile creare il file ZIP")
    }
}
