package xyz.chouxuewei.mobile_agent.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SkillPackageParserTest {
    @Test fun parsesMarkdownManifestAndInstructions() {
        val value = SkillPackageParser.parseMarkdown(
            """
            ---
            name: release-note
            description: Write a concise release note.
            ---

            Summarize user-visible changes and known limitations.
            """.trimIndent(),
        )
        assertEquals("release-note", value.slashName)
        assertEquals("Write a concise release note.", value.description)
        assertEquals("Summarize user-visible changes and known limitations.", value.instructions)
    }

    @Test fun zipRequiresOneSafeSkillManifest() {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("../SKILL.md"))
                zip.write("---\nname: bad\ndescription: bad\n---\nbad".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            SkillPackageParser.parse("bad.zip", ByteArrayInputStream(bytes))
        }
    }
}
