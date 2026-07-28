package com.jobhunt.android.resume

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.jobhunt.core.ResumeText
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.IOException

/**
 * Turns a document a user picked into plain text.
 *
 * PDF needs a real parser, so it goes through PDFBox-Android; DOCX and plain
 * text are handled by the shared core module.
 */
class AndroidResumeReader(private val context: Context) {

    class UnsupportedFormat(message: String) : IOException(message)

    /** The display name of a picked document, e.g. "Jane Doe Resume.pdf". */
    fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "resume"
    }

    @Throws(IOException::class)
    fun extractText(uri: Uri, filename: String = displayName(uri)): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Could not open $filename")
        return extractText(filename, bytes)
    }

    @Throws(IOException::class)
    fun extractText(filename: String, bytes: ByteArray): String =
        when (val extension = ResumeText.extensionOf(filename)) {
            "pdf" -> fromPdf(bytes)
            "docx" -> ResumeText.fromDocx(bytes)
            in ResumeText.TEXT_EXTENSIONS -> ResumeText.fromPlainText(bytes)
            else -> throw UnsupportedFormat(
                "Can't read .$extension files — use PDF, DOCX, Markdown or TXT.",
            )
        }

    private fun fromPdf(bytes: ByteArray): String =
        PDDocument.load(bytes).use { document ->
            if (document.isEncrypted) {
                throw IOException("That PDF is password-protected, so its text can't be read.")
            }
            PDFTextStripper().getText(document)
        }
}
