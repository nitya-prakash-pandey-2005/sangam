package com.sangam.app.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Downloads Gemma once, through Android's DownloadManager: it resumes after network drops,
 * keeps going when the app is closed, and shows a system notification. After that the model
 * runs fully offline. This is the only thing Sangam ever fetches from the internet.
 */
class ModelDownloader(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onInstalled: () -> Unit,
) {
    sealed interface State {
        data object Idle : State
        data class Running(val done: Long, val total: Long, val bytesPerSec: Long, val note: String? = null) : State
        data class Verifying(val fraction: Float) : State
        data class Failed(val message: String) : State
        data object Done : State
    }

    /**
     * Google publishes Gemma 4 E2B as a GPU build and a standard (CPU) build. Each entry is pinned
     * to one published revision with its SHA-256, so a damaged or altered download is never used.
     */
    enum class Variant(val file: String, val sha256: String, val size: Long) {
        GPU("gemma-4-E2B-it-gpu.litertlm", "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5", 2_008_432_640L),
        CPU("gemma-4-E2B-it.litertlm", "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", 2_588_147_712L);

        val url get() = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/$REVISION/$file"
        fun matches(name: String) = name == file

        companion object {
            /** The GPU build needs the phone's OpenCL driver; without it, the CPU build is the one that runs. */
            fun forThisPhone(): Variant =
                if (listOf("/vendor/lib64/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so", "/system/lib64/libOpenCL.so").any { File(it).exists() }) GPU else CPU
        }
    }

    companion object {
        private const val REVISION = "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1"
        private const val TAG = "SangamModel"
    }

    /** The build this phone will download unless told otherwise. */
    val recommended: Variant = Variant.forThisPhone()

    private val dm = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("model_download", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var poller: Job? = null

    init {
        // A download started earlier keeps running in the system; pick its progress back up.
        if (downloadId() >= 0) watch()
    }

    private fun downloadId() = prefs.getLong("id", -1)
    private fun forget() = prefs.edit { remove("id"); remove("variant") }
    private fun variant() = prefs.getString("variant", null)?.let { runCatching { Variant.valueOf(it) }.getOrNull() } ?: recommended
    private fun partFile() = File(Models.dir(context), "${variant().file}.download")

    /** Partial files from earlier attempts (DownloadManager renames to "...-1.download" if a name is taken). */
    private fun removeStrayParts(keep: File? = null) =
        Models.dir(context).listFiles { f -> f.name.endsWith(".download") && f.name != keep?.name }?.forEach { it.delete() }

    fun start(variant: Variant = recommended) {
        if (Models.llm(context) != null) {
            _state.value = State.Done
            onInstalled()
            return
        }
        if (downloadId() >= 0) return watch()
        val free = Models.dir(context).usableSpace
        val needed = variant.size + 300_000_000L
        if (free < needed) {
            _state.value = State.Failed("Not enough storage: Gemma needs about ${gb(needed)} free, and this phone has ${gb(free)}.")
            return
        }
        prefs.edit { putString("variant", variant.name) }
        removeStrayParts()
        val request = DownloadManager.Request(Uri.parse(variant.url))
            .setTitle("Gemma for Sangam")
            .setDescription("On-device AI model, ${gb(variant.size)}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, null, "models/${variant.file}.download")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val id = runCatching { dm.enqueue(request) }.getOrElse {
            Log.e(TAG, "Could not start the download", it)
            _state.value = State.Failed("Could not start the download: ${it.message}")
            return
        }
        prefs.edit { putLong("id", id) }
        _state.value = State.Running(0, variant.size, 0, "Starting…")
        watch()
    }

    fun cancel() {
        poller?.cancel()
        downloadId().takeIf { it >= 0 }?.let { dm.remove(it) }
        forget()
        removeStrayParts()
        _state.value = State.Idle
    }

    private fun watch() {
        poller?.cancel()
        poller = scope.launch(Dispatchers.IO) {
            var lastBytes = -1L
            var lastAt = 0L
            var speed = 0L
            while (isActive) {
                val id = downloadId()
                if (id < 0) break
                val c = dm.query(DownloadManager.Query().setFilterById(id))
                if (c == null || !c.moveToFirst()) {
                    c?.close()
                    forget()
                    _state.value = State.Failed("The download was cancelled.")
                    break
                }
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: variant().size
                // The file DownloadManager actually wrote, which may differ from the name we asked for.
                val local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    ?.let { Uri.parse(it).path }?.let(::File)
                c.close()

                val now = System.currentTimeMillis()
                if (lastBytes >= 0 && now > lastAt) {
                    val instant = (done - lastBytes) * 1000 / (now - lastAt)
                    speed = if (speed == 0L) instant else (speed * 7 + instant * 3) / 10 // smoothed
                }
                lastBytes = done
                lastAt = now

                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> { finish(total, local ?: partFile()); break }
                    DownloadManager.STATUS_FAILED -> {
                        dm.remove(id)
                        forget()
                        removeStrayParts()
                        Log.e(TAG, "Download failed, reason $reason")
                        _state.value = State.Failed(failureText(reason))
                        break
                    }
                    DownloadManager.STATUS_PAUSED -> _state.value = State.Running(done, total, 0, pausedText(reason))
                    DownloadManager.STATUS_PENDING -> _state.value = State.Running(done, total, 0, "Starting…")
                    else -> _state.value = State.Running(done, total, speed)
                }
                delay(500)
            }
        }
    }

    private fun finish(total: Long, part: File) {
        val variant = variant()
        forget()
        removeStrayParts(keep = part)
        if (!part.exists() || part.length() != total) {
            Log.e(TAG, "Incomplete download: ${part.length()} of $total bytes at ${part.path}")
            part.delete()
            _state.value = State.Failed("The download was incomplete. Please try again.")
            return
        }
        val hash = sha256(part)
        if (hash != variant.sha256) {
            Log.e(TAG, "Checksum mismatch: got $hash")
            part.delete()
            _state.value = State.Failed("The download was damaged (checksum mismatch). Please try again.")
            return
        }
        if (!part.renameTo(File(Models.dir(context), variant.file))) {
            _state.value = State.Failed("Downloaded, but the file could not be saved. Please try again.")
            return
        }
        Log.i(TAG, "Gemma downloaded and verified: $total bytes")
        _state.value = State.Done
        onInstalled()
    }

    /** Streams the file once, reporting progress so the user sees why there's a short wait. */
    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val total = file.length().coerceAtLeast(1)
        val buffer = ByteArray(1 shl 20)
        var read = 0L
        var lastShown = -1
        _state.value = State.Verifying(0f)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                read += n
                val pct = (read * 100 / total).toInt()
                if (pct != lastShown) { lastShown = pct; _state.value = State.Verifying(read.toFloat() / total) }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun pausedText(reason: Int) = when (reason) {
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for an internet connection…"
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi…"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Connection dropped. Retrying…"
        else -> "Paused"
    }

    private fun failureText(reason: Int) = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage on this phone."
        DownloadManager.ERROR_CANNOT_RESUME -> "The download couldn't resume. Please try again."
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "The connection failed. Check your internet and try again."
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "The server redirected too many times. Try again later."
        in 400..599 -> "The server returned an error (HTTP $reason). Try again later."
        else -> "The download failed (code $reason). Check your internet and try again."
    }
}

/** Decimal units, as download sites show them: 2_008_432_640 → "2.0 GB"; 734_000_000 → "734 MB". */
fun gb(bytes: Long): String =
    if (bytes >= 1_000_000_000L) String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)
    else "${bytes / 1_000_000L} MB"
