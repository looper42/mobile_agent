package xyz.chouxuewei.mobile_agent.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import xyz.chouxuewei.mobile_agent.core.SkillDraft
import xyz.chouxuewei.mobile_agent.core.SkillLimits
import xyz.chouxuewei.mobile_agent.core.localizedText

object SkillPackageParser {
    fun parse(fileName: String, input: InputStream): SkillDraft {
        val raw = input.readBounded(SkillLimits.MAX_IMPORT_BYTES)
        val markdown = if (fileName.lowercase(Locale.ROOT).endsWith(".zip")) {
            skillMarkdownFromZip(raw)
        } else {
            raw.toString(Charsets.UTF_8)
        }
        return parseMarkdown(markdown)
    }

    fun parseMarkdown(markdown: String): SkillDraft {
        val normalized = markdown.removePrefix("\uFEFF").replace("\r\n", "\n")
        require(normalized.startsWith("---\n")) {
            localizedText("SKILL.md 缺少 YAML 头部", "SKILL.md is missing YAML front matter.")
        }
        val end = normalized.indexOf("\n---\n", startIndex = 4)
        require(end >= 0) { localizedText("SKILL.md 的 YAML 头部未闭合", "SKILL.md has unterminated YAML front matter.") }
        val metadata = normalized.substring(4, end).lineSequence().mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null else line.substring(0, separator).trim() to
                line.substring(separator + 1).trim().trim('"', '\'')
        }.toMap()
        val name = metadata["name"].orEmpty()
        val description = metadata["description"].orEmpty()
        val body = normalized.substring(end + 5).trim()
        require(name.isNotBlank() && description.isNotBlank() && body.isNotBlank()) {
            localizedText("SKILL.md 必须包含 name、description 和正文", "SKILL.md must contain name, description, and instruction body.")
        }
        return SkillDraft(name, name, description, body)
    }

    private fun skillMarkdownFromZip(bytes: ByteArray): String {
        var entries = 0
        var totalBytes = 0L
        var skillBytes: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= SkillLimits.MAX_ZIP_ENTRIES) {
                    localizedText("技能压缩包文件过多", "The Skill archive contains too many files.")
                }
                val normalized = entry.name.replace('\\', '/').trimEnd('/')
                require(normalized.isNotEmpty() && !normalized.startsWith('/') && normalized.split('/').none { it == ".." || it.isEmpty() }) {
                    localizedText("技能压缩包包含不安全路径", "The Skill archive contains an unsafe path.")
                }
                if (!entry.isDirectory) {
                    val content = zip.readBounded(
                        SkillLimits.MAX_ZIP_UNCOMPRESSED_BYTES - totalBytes,
                    )
                    totalBytes += content.size
                    require(totalBytes <= SkillLimits.MAX_ZIP_UNCOMPRESSED_BYTES) {
                        localizedText("技能解压后内容过大", "The uncompressed Skill archive is too large.")
                    }
                    if (normalized.substringAfterLast('/').equals("SKILL.md", ignoreCase = true)) {
                        require(skillBytes == null) {
                            localizedText("技能压缩包只能包含一个 SKILL.md", "The Skill archive must contain exactly one SKILL.md.")
                        }
                        skillBytes = content
                    }
                }
                zip.closeEntry()
            }
        }
        return requireNotNull(skillBytes) {
            localizedText("技能压缩包中找不到 SKILL.md", "The Skill archive does not contain SKILL.md.")
        }.toString(Charsets.UTF_8)
    }

    private fun InputStream.readBounded(maxBytes: Long): ByteArray {
        require(maxBytes > 0) { localizedText("技能内容过大", "The Skill content is too large.") }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { localizedText("技能内容过大", "The Skill content is too large.") }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
