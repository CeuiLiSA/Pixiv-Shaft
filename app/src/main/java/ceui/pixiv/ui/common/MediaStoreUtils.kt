package ceui.pixiv.ui.common

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.IOException


fun saveImageToGallery(context: Context, imageFile: File, displayName: String) {
    try {
        // Create content values for the image
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            // Specify the directory path in the Pictures folder
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/ShaftImages"
            )
        }

        val contentResolver = context.contentResolver ?: return

        // Insert the image into MediaStore and get the URI
        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: return

        // Open output stream to write the image data
        contentResolver.openOutputStream(uri)?.use { outputStream ->
            FileInputStream(imageFile).use { inputStream ->
                // Copy the image file to the output stream
                inputStream.copyTo(outputStream)
            }
            outputStream.flush()
        }
    } catch (e: IOException) {
        // Handle IO exceptions, e.g., file not found or I/O error
        e.printStackTrace()
    } catch (e: SecurityException) {
        // Handle security exceptions, e.g., lack of permissions
        e.printStackTrace()
    } catch (e: Exception) {
        // Handle any other unexpected exceptions
        e.printStackTrace()
    }
}

fun isImageInGallery(context: Context, displayName: String): Boolean {
    val projection = arrayOf(MediaStore.Images.Media._ID)
    val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
    val selectionArgs = arrayOf(displayName)

    val cursor: Cursor? = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        selectionArgs,
        null
    )

    return cursor?.use {
        it.count > 0
    } ?: false
}

