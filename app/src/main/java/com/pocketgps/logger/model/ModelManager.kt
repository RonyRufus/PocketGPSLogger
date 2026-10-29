package com.pocketgps.logger.model

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Manages the offline Vosk model in app-private storage.
 *
 * The model is **never** bundled in the APK. It is either
 *  - imported from a user-supplied `.zip` (fully offline), or
 *  - downloaded once from a configured URL (network used only for this step).
 *
 * The extracted model lives at `filesDir/model` and is verified structurally before use.
 * A loaded [Model] is cached for the process lifetime so entering/leaving the listening screen
 * never re-parses the model.
 */
class ModelManager(private val context: Context) {

    sealed interface Status {
        data object Missing : Status
        data class Ready(val directory: File, val sizeBytes: Long) : Status
        data class Corrupt(val reason: String) : Status
    }

    sealed interface ImportResult {
        data class Success(val directory: File) : ImportResult
        data class Failure(val reason: String) : ImportResult
    }

    private val modelDir: File get() = File(context.filesDir, MODEL_DIR_NAME)
    private val tempDir: File get() = File(context.cacheDir, "model-import")

    @Volatile private var cachedModel: Model? = null

    // ------------------------------------------------------------------ status

    fun status(): Status = when {
        !modelDir.exists() -> Status.Missing
        !isStructurallyValid(modelDir) -> Status.Corrupt("Required model files are missing")
        else -> Status.Ready(modelDir, storageBytes())
    }

    fun isReady(): Boolean = status() is Status.Ready

    /** Total bytes occupied by the extracted model (or 0). */
    fun storageBytes(): Long {
        if (!modelDir.exists()) return 0L
        return modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    // ------------------------------------------------------------------ load

    /**
     * Return a loaded [Model], reusing the cached instance. Returns null when the model is
     * missing or corrupt. Safe to call from the service on a background coroutine.
     */
    @Synchronized
    fun loadModel(): Model? {
        cachedModel?.let { return it }
        val status = status()
        if (status !is Status.Ready) return null
        return runCatching { Model(status.directory.absolutePath) }
            .onSuccess { cachedModel = it }
            .getOrNull()
    }

    /** Release native memory (only needed when deleting/replacing the model). */
    @Synchronized
    fun unload() {
        runCatching { cachedModel?.close() }
        cachedModel = null
    }

    fun deleteModel() {
        unload()
        runCatching { modelDir.deleteRecursively() }
    }

    // ------------------------------------------------------------------ import (offline)

    /**
     * Import a model from a user-picked `.zip` via SAF. Extraction happens into a temp dir; the
     * detected model root is then atomically moved to [modelDir]. Zip-slip is rejected.
     */
    suspend fun importFromZip(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            tempDir.deleteRecursively()
            tempDir.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                extractZip(input, tempDir)
            } ?: return@withContext ImportResult.Failure("Could not open the selected file")

            val root = findModelRoot(tempDir)
                ?: return@withContext ImportResult.Failure(
                    "No Vosk model found in the archive (expected am/final.mdl)",
                )
            if (!isStructurallyValid(root)) {
                return@withContext ImportResult.Failure("Extracted model is incomplete")
            }

            replaceModelDir(root)
            ImportResult.Success(modelDir)
        } catch (t: Throwable) {
            ImportResult.Failure(t.message ?: "Import failed")
        } finally {
            runCatching { tempDir.deleteRecursively() }
        }
    }

    // ------------------------------------------------------------------ download (one-time)

    /**
     * Download a model zip from [url] and import it. This is the only network operation in the
     * app; every other feature is fully offline.
     */
    suspend fun downloadAndImport(
        url: String,
        expectedSha256: String? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): ImportResult = withContext(Dispatchers.IO) {
        val zipFile = File(context.cacheDir, "model-download.zip")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total: Long
            var downloaded = 0L

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            if (connection.responseCode !in 200..299) {
                return@withContext ImportResult.Failure("Download failed (HTTP ${connection.responseCode})")
            }
            total = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(zipFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            connection.disconnect()

            if (expectedSha256 != null) {
                val actual = digest.digest().joinToString("") {
                    "%02x".format(it.toInt() and 0xFF)
                }
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    return@withContext ImportResult.Failure(
                        "Checksum mismatch (expected $expectedSha256, got $actual)",
                    )
                }
            }

            tempDir.deleteRecursively()
            tempDir.mkdirs()
            zipFile.inputStream().use { extractZip(it, tempDir) }
            val root = findModelRoot(tempDir)
                ?: return@withContext ImportResult.Failure("Downloaded archive contains no Vosk model")
            if (!isStructurallyValid(root)) {
                return@withContext ImportResult.Failure("Downloaded model is incomplete")
            }
            replaceModelDir(root)
            ImportResult.Success(modelDir)
        } catch (t: Throwable) {
            ImportResult.Failure(t.message ?: "Download failed")
        } finally {
            runCatching { zipFile.delete() }
            runCatching { tempDir.deleteRecursively() }
        }
    }

    // ------------------------------------------------------------------ internals

    private fun replaceModelDir(newRoot: File) {
        unload()
        if (modelDir.exists()) modelDir.deleteRecursively()
        modelDir.parentFile?.mkdirs()
        if (!newRoot.renameTo(modelDir)) {
            // Fall back to a copy when rename crosses a filesystem boundary.
            newRoot.copyRecursively(modelDir, overwrite = true)
            newRoot.deleteRecursively()
        }
    }

    private fun extractZip(input: InputStream, destination: File) {
        ZipInputStream(input).use { zip ->
            val canonicalDest = destination.canonicalFile
            var entry = zip.nextEntry
            while (entry != null) {
                val target = File(destination, entry.name)
                // Zip-slip protection.
                if (!target.canonicalPath.startsWith(canonicalDest.path + File.separator) &&
                    target.canonicalPath != canonicalDest.path
                ) {
                    throw SecurityException("Zip entry escapes destination: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    /** Find the directory that directly contains `am/final.mdl`. */
    private fun findModelRoot(root: File): File? {
        if (File(root, "am/final.mdl").exists()) return root
        return root.walkTopDown()
            .maxDepth(3)
            .firstOrNull { it.isDirectory && File(it, "am/final.mdl").exists() }
    }

    /** Structural check plus a lightweight decoder config presence check. */
    private fun isStructurallyValid(dir: File): Boolean {
        val required = listOf(
            "am/final.mdl",
            "conf/mfcc.conf",
        )
        if (!required.all { File(dir, it).isFile && File(dir, it).length() > 0 }) return false
        val hasGraph = File(dir, "graph/HCLG.fst").isFile ||
            File(dir, "graph/HCLr.fst").isFile ||
            File(dir, "HCLG.fst").isFile
        // Small models ship a graph; larger ones may use a dynamic graph word list. Require at
        // least one graph artefact so we never load a half-extracted model.
        return hasGraph
    }

    companion object {
        const val MODEL_DIR_NAME = "model"

        /**
         * Official lightweight English model. Kept here so the setup screen can offer a
         * one-tap download; users may instead import any compatible Vosk model zip.
         */
        const val SMALL_EN_US_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }
}
