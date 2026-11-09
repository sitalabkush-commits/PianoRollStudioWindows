package com.pianoroll.desktop

import com.pianoroll.core.audio.AudioSink
import com.pianoroll.core.model.Project
import com.pianoroll.export.ProjectFile
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * [AudioSink] that writes a file safely: bytes go to `<name>.tmp`, and `close()` moves it over the target.
 * - close() with nothing written does NOT touch the target (the exporter closes the sink without data when a render fails).
 * - after a write error, close() deletes the temp file and keeps the old target.
 * - close() is idempotent.
 */
class FileSink(private val target: File) : AudioSink {
    private val tmp = File(target.absoluteFile.parentFile, target.name + ".tmp")
    private var stream: OutputStream? = null
    private var failed = false
    private var closed = false

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (closed) return
        try {
            val s = stream ?: tmp.outputStream().buffered().also { stream = it }
            s.write(buffer, offset, length)
        } catch (e: IOException) {
            failed = true
            throw e
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val s = stream ?: return                       // nothing was written: leave the target alone
        if (failed) {                                  // a write already threw (the caller reports that error)
            runCatching { s.close() }
            tmp.delete()
            return
        }
        try {
            s.close()
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/** `.prs` save / load on plain files (no Room). Uses export/ProjectFile for the format. */
object ProjectIO {
    fun save(project: Project, file: File): Result<Unit> = ProjectFile.export(project, FileSink(file))

    fun load(file: File): Result<Project> = runCatching {
        require(file.isFile) { "File not found: ${file.name}" }
        file.readText(Charsets.UTF_8)
    }.mapCatching { ProjectFile.decode(it).getOrThrow() }
}

object FileNames {
    private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }

    /** Makes [name] safe as a Windows/Linux/macOS file name (without extension). Never returns an empty string. */
    fun sanitize(name: String, maxLength: Int = 80): String {
        var s = name.map { c -> if (c in "<>:\"/\\|?*" || c.code < 32) '_' else c }.joinToString("")
        s = s.trim().trimEnd('.', ' ')
        if (s.isEmpty()) s = "untitled"
        if (s.length > maxLength) s = s.take(maxLength).trimEnd('.', ' ')
        if (s.substringBefore('.').uppercase() in RESERVED) s = "_$s"
        return s
    }

    /** Adds `.ext` unless the file already has it (case-insensitive). */
    fun withExtension(file: File, ext: String): File =
        if (file.name.endsWith(".$ext", ignoreCase = true)) file
        else File(file.absoluteFile.parentFile, file.name + "." + ext)

    /** Case-insensitive unique name: "Bass", "Bass 2", "Bass 3" ... [used] is updated. */
    fun unique(name: String, used: MutableSet<String>): String {
        var candidate = name
        var k = 2
        while (!used.add(candidate.lowercase())) candidate = "$name ${k++}"
        return candidate
    }
}
