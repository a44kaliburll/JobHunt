package com.jobhunt.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Plain-text extraction for the resume formats that need no Android APIs.
 *
 * DOCX is just a zip containing `word/document.xml`, so it is unpacked and
 * de-tagged here rather than pulling in a heavyweight Office library. PDF
 * needs a rendering engine and is handled in the Android module.
 */
object ResumeText {

    val TEXT_EXTENSIONS = setOf("txt", "md", "markdown")

    fun extensionOf(filename: String): String =
        filename.substringAfterLast('.', "").lowercase()

    fun isSupported(filename: String): Boolean =
        extensionOf(filename) in TEXT_EXTENSIONS + "docx" + "pdf"

    fun fromPlainText(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    /** Extract visible text from a .docx, preserving paragraph and row breaks. */
    fun fromDocx(bytes: ByteArray): String = fromDocx(ByteArrayInputStream(bytes))

    fun fromDocx(input: InputStream): String {
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    return docxXmlToText(zip.readBytes().toString(Charsets.UTF_8))
                }
                entry = zip.nextEntry
            }
        }
        error("Not a valid .docx file: word/document.xml is missing")
    }

    internal fun docxXmlToText(xml: String): String {
        val text = xml
            // Pretty-printed XML puts whitespace between tags. Drop it, but never
            // inside a <w:t>, where a lone space is a real word separator.
            .replace(Regex(""">\s+<(?!/w:t>)"""), "><")
            // A paragraph closing a table cell must not also break the line, or
            // every cell of a two-column resume lands on its own row.
            .replace(Regex("""</w:p>\s*(?=</w:tc>)"""), "")
            // Cells become separators so tabular resumes stay readable...
            .replace("</w:tc>", " | ")
            // ...and rows and paragraphs become newlines.
            .replace("</w:tr>", "\n")
            .replace("</w:p>", "\n")
            .replace(Regex("""<w:br\s*/?>"""), "\n")
            .replace(Regex("""<w:tab\s*/?>"""), "    ")
            // Everything else is markup.
            .replace(Regex("""<[^>]+>"""), "")
        return unescapeXml(text)
            .lines()
            .joinToString("\n") { it.trimEnd().trimEnd('|', ' ').trimEnd() }
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private fun unescapeXml(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}
