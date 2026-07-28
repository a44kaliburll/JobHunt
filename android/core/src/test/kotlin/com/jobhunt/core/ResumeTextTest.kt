package com.jobhunt.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val DOCUMENT_XML = """<?xml version="1.0" encoding="UTF-8"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>
<w:p><w:r><w:t>Jane Doe</w:t></w:r></w:p>
<w:p><w:r><w:t>Skills</w:t></w:r></w:p>
<w:p><w:r><w:t>Instructional Design</w:t></w:r><w:r><w:t> &amp; Canvas</w:t></w:r></w:p>
<w:p><w:r><w:t>Experience</w:t></w:r></w:p>
<w:tbl><w:tr><w:tc><w:p><w:r><w:t>2019-2024</w:t></w:r></w:p></w:tc>
<w:tc><w:p><w:r><w:t>Director of Online Learning</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
</w:body></w:document>
"""

private fun docxBytes(documentXml: String = DOCUMENT_XML): ByteArray {
    val buffer = ByteArrayOutputStream()
    ZipOutputStream(buffer).use { zip ->
        zip.putNextEntry(ZipEntry("[Content_Types].xml"))
        zip.write("<Types/>".toByteArray())
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("word/document.xml"))
        zip.write(documentXml.toByteArray())
        zip.closeEntry()
    }
    return buffer.toByteArray()
}

class ResumeTextTest {

    @Test
    fun `docx paragraphs runs and tables become readable text`() {
        val text = ResumeText.fromDocx(docxBytes())

        assertContains(text, "Jane Doe")
        // Runs inside one paragraph must join without a break, and XML entities decode.
        assertContains(text, "Instructional Design & Canvas")
        // Table cells stay on one line so a two-column resume still parses.
        assertContains(text, "2019-2024 | Director of Online Learning")
    }

    @Test
    fun `docx text feeds the parser end to end`() {
        val parsed = ResumeParser.parse(ResumeText.fromDocx(docxBytes()))

        assertContains(parsed.skills.map { it.lowercase() }, "instructional design")
        assertTrue(
            parsed.titles.any { "Director of Online Learning" in it },
            "got: ${parsed.titles}",
        )
    }

    @Test
    fun `a non-docx zip is rejected with a clear message`() {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("not a resume".toByteArray())
            zip.closeEntry()
        }
        val error = assertFailsWith<IllegalStateException> {
            ResumeText.fromDocx(buffer.toByteArray())
        }
        assertContains(error.message.orEmpty(), "word/document.xml")
    }

    @Test
    fun `supported formats are recognized by extension`() {
        assertTrue(ResumeText.isSupported("resume.PDF"))
        assertTrue(ResumeText.isSupported("resume.docx"))
        assertTrue(ResumeText.isSupported("notes.md"))
        assertTrue(!ResumeText.isSupported("resume.pages"))
        assertEquals("docx", ResumeText.extensionOf("My Resume v2.docx"))
    }
}
