package com.droiddeck.launcher.session

import android.content.Context
import android.os.Environment
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import java.io.File
import java.io.IOException

enum class LibraryState { AVAILABLE, LOW_SPACE, READ_ONLY, NOT_WRITABLE, MISSING }

object LibraryAvailability {
    const val LOW_SPACE_BYTES = 5L * 1024 * 1024 * 1024
    const val SPACE_UNKNOWN = -1L

    class Status(val state: LibraryState, val freeBytes: Long) {
        val usable get() = state == LibraryState.AVAILABLE || state == LibraryState.LOW_SPACE
    }

    // MEDIA_UNKNOWN is deliberately absent: drives found only through /proc/mounts are not
    // StorageManager volumes, so Android reports them as unknown even while mounted.
    private val GONE = setOf(
        Environment.MEDIA_UNMOUNTED, Environment.MEDIA_REMOVED, Environment.MEDIA_BAD_REMOVAL,
        Environment.MEDIA_EJECTING, Environment.MEDIA_CHECKING, Environment.MEDIA_NOFS,
        Environment.MEDIA_UNMOUNTABLE, Environment.MEDIA_SHARED,
    )

    internal fun classify(mediaState: String?, folderExists: Boolean, parentExists: Boolean, writable: Boolean?, freeBytes: Long): LibraryState = when {
        mediaState in GONE -> LibraryState.MISSING
        !folderExists && !parentExists -> LibraryState.MISSING
        mediaState == Environment.MEDIA_MOUNTED_READ_ONLY -> LibraryState.READ_ONLY
        writable == false -> LibraryState.NOT_WRITABLE
        freeBytes in 0 until LOW_SPACE_BYTES -> LibraryState.LOW_SPACE
        else -> LibraryState.AVAILABLE
    }

    internal fun readOnlyError(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
        it.message?.contains("EROFS") == true || it.message?.contains("Read-only file system", ignoreCase = true) == true
    }

    /** Checks the folder without creating it; only a probe file is written and removed. */
    fun check(path: String, mediaState: (File) -> String? = ::mediaStateOf): Status {
        val folder = File(path)
        val parent = folder.absoluteFile.parentFile
        val folderExists = folder.isDirectory
        val parentExists = parent?.isDirectory == true
        val state = mediaState(folder)
        if (state in GONE || (!folderExists && !parentExists)) return Status(LibraryState.MISSING, SPACE_UNKNOWN)
        val target = if (folderExists) folder else parent!!
        var readOnly = false
        val writable = try {
            File.createTempFile(".droiddeck-probe", null, target).delete()
            true
        } catch (e: IOException) {
            readOnly = readOnlyError(e)
            false
        } catch (e: SecurityException) {
            false
        }
        val free = runCatching { target.usableSpace }.getOrNull()?.takeIf { it > 0L } ?: SPACE_UNKNOWN
        val result = classify(if (readOnly) Environment.MEDIA_MOUNTED_READ_ONLY else state, folderExists, parentExists, writable, free)
        return Status(result, free)
    }

    private fun mediaStateOf(file: File): String? = runCatching { Environment.getExternalStorageState(file) }.getOrNull()

    fun problem(context: Context, label: String, status: Status): String? = when (status.state) {
        LibraryState.MISSING -> context.getString(R.string.gstore_drive_missing, label)
        LibraryState.READ_ONLY -> context.getString(R.string.gstore_drive_read_only, label)
        LibraryState.NOT_WRITABLE -> context.getString(R.string.gstore_drive_not_writable, label)
        LibraryState.AVAILABLE, LibraryState.LOW_SPACE -> null
    }

    fun summary(context: Context, status: Status): String = when (status.state) {
        LibraryState.AVAILABLE -> if (status.freeBytes >= 0) context.getString(R.string.gstore_status_available_free, FileUtils.sizeToString(status.freeBytes))
            else context.getString(R.string.gstore_status_available)
        LibraryState.LOW_SPACE -> context.getString(R.string.gstore_status_low_space, FileUtils.sizeToString(status.freeBytes))
        LibraryState.READ_ONLY -> context.getString(R.string.gstore_status_read_only)
        LibraryState.NOT_WRITABLE -> context.getString(R.string.gstore_status_not_writable)
        LibraryState.MISSING -> context.getString(R.string.storage_default_folder_missing)
    }
}
