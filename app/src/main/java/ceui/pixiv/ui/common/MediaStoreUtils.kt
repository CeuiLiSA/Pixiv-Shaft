package ceui.pixiv.ui.common

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import ceui.lisa.R
import ceui.lisa.utils.Common
import com.blankj.utilcode.util.ImageUtils
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
                "${Environment.DIRECTORY_DCIM}/ShaftImages"
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

fun getImageIdInGallery(context: Context, displayName: String): Long? {
    val contentResolver: ContentResolver = context.contentResolver

    // Define the projection to retrieve the URI of the image
    val projection = arrayOf(MediaStore.Images.Media._ID)

    // Define the selection criteria to match the displayName
    val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
    val selectionArgs = arrayOf(displayName)

    // Query MediaStore for the image
    val cursor = contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        selectionArgs,
        null
    )

    // Extract the ID if available
    val imageId = cursor?.use {
        if (it.moveToFirst()) {
            val idColumnIndex = it.getColumnIndex(MediaStore.Images.Media._ID)
            it.getLong(idColumnIndex)
        } else {
            null
        }
    }

    return imageId
}


fun deleteImageById(context: Context, imageId: Long): Boolean {
    val contentResolver: ContentResolver = context.contentResolver

    // Construct the Uri for the image using the imageId
    val imageUri: Uri = Uri.withAppendedPath(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        imageId.toString()
    )

    return try {
        // Delete the image from MediaStore
        val rowsDeleted = contentResolver.delete(imageUri, null, null)
        // Check if deletion was successful
        rowsDeleted > 0
    } catch (e: Exception) {
        // Handle any errors during deletion
        e.printStackTrace()
        false
    }
}
