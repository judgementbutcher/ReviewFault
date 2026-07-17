package cn.reviewfault.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

class CaptureFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = resolve(uri)
        file.parentFile?.mkdirs()
        val flags = if (mode.contains('w')) {
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_TRUNCATE
        } else {
            ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = resolve(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            val values: Array<Any?> = Array(columns.size) { index ->
                when (columns[index]) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            }
            addRow(values)
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        if (resolve(uri).delete()) 1 else 0

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0

    private fun resolve(uri: Uri): File {
        val name = uri.lastPathSegment.orEmpty()
        require(name.matches(Regex("[A-Za-z0-9-]+\\.jpg"))) { "非法拍照临时文件名" }
        val root = File(requireNotNull(context).cacheDir, "capture")
        val file = File(root, name)
        require(file.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            "非法拍照临时路径"
        }
        return file
    }
}
