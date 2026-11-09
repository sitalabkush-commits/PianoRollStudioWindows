package com.pianoroll.export

import com.pianoroll.core.audio.AudioEngine
import com.pianoroll.core.audio.AudioSink
import com.pianoroll.core.audio.RenderSettings
import com.pianoroll.core.model.Project
import kotlin.coroutines.cancellation.CancellationException

/**
 * Claude E. Pure Kotlin; audio comes from whichever [AudioEngine] is passed in (Android now, desktop later).
 * - MIDI: [MidiWriter].
 * - WAV / Stems: `engine.renderOffline` -> [WavSink].
 * - MP3: needs a platform [Mp3Encoder]; without one the call fails with UnsupportedOperationException.
 * Every method closes `out` exactly once, on success and on failure.
 *
 * @param onProgress 0..1 for the whole call (stems: across all stems). Called from the render thread.
 */
class DefaultExporter(
    private val settings: RenderSettings = RenderSettings(),
    private val mp3Encoder: Mp3Encoder? = null,
    private val midiOptions: MidiOptions = MidiOptions(),
    private val onProgress: (Float) -> Unit = {},
) : Exporter {

    override suspend fun toMidi(project: Project, out: AudioSink): Result<Unit> = guarded(out) {
        MidiWriter.write(project, out, midiOptions)
        onProgress(1f)
    }

    override suspend fun toWav(project: Project, engine: AudioEngine, out: AudioSink): Result<Unit> {
        val wav = WavSink(out, settings)
        return renderInto(project, engine, wav, wav::discard, 0f, 1f)
    }

    override suspend fun toMp3(project: Project, engine: AudioEngine, kbps: Int, out: AudioSink): Result<Unit> {
        if (kbps !in MP3_BITRATES) return fail(out, IllegalArgumentException("kbps must be one of $MP3_BITRATES"))
        val enc = mp3Encoder ?: return fail(
            out,
            UnsupportedOperationException("No MP3 encoder available on this platform (LAME/MediaCodec not wired yet). Export WAV instead."),
        )
        val sink = try { enc.open(settings, kbps, out) } catch (e: Throwable) { return fail(out, e) }
        return renderInto(project, engine, sink, { runCatching { sink.close() } }, 0f, 1f)
    }

    /** One WAV per audible track name (all patterns merged). [outFactory] gets a unique, filesystem-agnostic name. */
    override suspend fun toStems(project: Project, engine: AudioEngine, outFactory: (trackName: String) -> AudioSink): Result<Unit> {
        val lanes = MidiWriter.lanes(project, MidiOptions(respectMuteSolo = true))
        if (lanes.isEmpty()) return Result.failure(IllegalStateException("Nothing to export: no audible tracks."))
        val used = HashSet<String>()
        for ((i, lane) in lanes.withIndex()) {
            var name = lane.name; var k = 2
            while (!used.add(name.lowercase())) name = "${lane.name} ${k++}"   // case-insensitive: safe on Windows/Android file systems
            val out = try { outFactory(name) } catch (e: Throwable) { return Result.failure(e) }
            val wav = WavSink(out, settings)
            val r = renderInto(stemProject(project, lane.trackIds), engine, wav, wav::discard, i / lanes.size.toFloat(), (i + 1) / lanes.size.toFloat())
            if (r.isFailure) return r
        }
        onProgress(1f)
        return Result.success(Unit)
    }

    // ---- internals ----

    private suspend fun renderInto(
        project: Project, engine: AudioEngine, sink: AudioSink, discard: () -> Unit, from: Float, to: Float,
    ): Result<Unit> {
        val expected = ((Arrangement.lengthSeconds(project) + TAIL_SECONDS) * settings.sampleRate * settings.channels * (settings.bitsPerSample / 8)).toLong()
        val counted = ProgressSink(sink, expected) { onProgress(from + (to - from) * it) }
        return try {
            val r = engine.renderOffline(project, settings, counted)
            if (r.isFailure) discard()
            r
        } catch (e: CancellationException) { discard(); throw e }
        catch (e: Throwable) { discard(); Result.failure(e) }
    }

    private inline fun guarded(out: AudioSink, block: () -> Unit): Result<Unit> =
        try { block(); Result.success(Unit) }
        catch (e: CancellationException) { runCatching { out.close() }; throw e }
        catch (e: Throwable) { runCatching { out.close() }; Result.failure(e) }

    private fun fail(out: AudioSink, e: Throwable): Result<Unit> { runCatching { out.close() }; return Result.failure(e) }

    private fun stemProject(p: Project, ids: Set<String>): Project = p.copy(
        patterns = p.patterns.map { pat -> pat.copy(tracks = pat.tracks.map { t -> if (t.id in ids) t.copy(solo = true, mute = false) else t.copy(solo = false) }) }
    )

    companion object {
        val MP3_BITRATES = listOf(128, 192, 320)
        const val TAIL_SECONDS = 2f   // keep in step with OfflineRenderer's default tail
    }
}
