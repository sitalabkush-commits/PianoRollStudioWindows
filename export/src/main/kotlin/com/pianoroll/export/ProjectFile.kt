package com.pianoroll.export

import com.pianoroll.core.audio.AudioSink
import com.pianoroll.core.model.Project
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `.prs` project file: a small JSON envelope around [Project] so the format can evolve (version field). */
@Serializable
internal data class PrsEnvelope(
    @SerialName("format") val format: String = ProjectFile.FORMAT,
    @SerialName("version") val version: Int = ProjectFile.VERSION,
    @SerialName("project") val project: Project,
)

object ProjectFile {
    const val FORMAT = "pianoroll-prs"
    const val VERSION = 1
    const val EXTENSION = "prs"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    fun encode(project: Project): String = json.encodeToString(PrsEnvelope.serializer(), PrsEnvelope(project = project))

    /** Fails (never throws) on bad JSON, a foreign file, or a file written by a newer app version. */
    fun decode(text: String): Result<Project> = runCatching {
        val env = json.decodeFromString(PrsEnvelope.serializer(), text)
        require(env.format == FORMAT) { "Not a PianoRoll Studio project file." }
        require(env.version <= VERSION) { "Project was saved by a newer app version (file v${env.version}, this app reads up to v$VERSION)." }
        env.project
    }

    fun export(project: Project, out: AudioSink): Result<Unit> = runCatching {
        val bytes = encode(project).toByteArray(Charsets.UTF_8)
        try { out.write(bytes, 0, bytes.size) } finally { out.close() }
    }
}
