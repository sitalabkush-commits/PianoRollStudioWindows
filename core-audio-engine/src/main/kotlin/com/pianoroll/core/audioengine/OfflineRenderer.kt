package com.pianoroll.core.audioengine

import com.pianoroll.core.audio.AudioSink
import com.pianoroll.core.audio.RenderSettings
import com.pianoroll.core.model.Project

/** Faster-than-realtime render to PCM16 LE. Independent engine instance (never disturbs live playback). */
object OfflineRenderer {
    fun render(project: Project, settings: RenderSettings, out: AudioSink, tailSeconds: Float = 2f): Result<Unit> = runCatching {
        require(settings.bitsPerSample == 16) { "Only 16-bit PCM supported" }
        require(settings.channels == 1 || settings.channels == 2) { "1 or 2 channels only" }
        val engine = SynthEngine(settings.sampleRate, honorLoop = false)
        engine.setProject(project)
        engine.play()
        val frames = 1024
        val buf = FloatArray(frames * 2)
        val bytes = ByteArray(frames * settings.channels * 2)
        var tailLeft = (tailSeconds * settings.sampleRate).toInt()
        var started = false
        var guard = 0L
        val maxFrames = settings.sampleRate.toLong() * 60 * 30   // 30 min safety cap
        while (guard < maxFrames) {
            engine.render(buf, frames)
            guard += frames
            var bi = 0
            for (i in 0 until frames) {
                val l = buf[i * 2]; val r = buf[i * 2 + 1]
                if (settings.channels == 2) {
                    bi = put(bytes, bi, l); bi = put(bytes, bi, r)
                } else bi = put(bytes, bi, (l + r) * 0.5f)
            }
            out.write(bytes, 0, bi)
            if (engine.isPlaying()) started = true
            else if (started || guard > frames) {   // finished (or empty project)
                tailLeft -= frames
                if (tailLeft <= 0) break
            }
        }
        out.close()
    }

    private fun put(b: ByteArray, i: Int, f: Float): Int {
        val v = (f.coerceIn(-1f, 1f) * 32767f).toInt()
        b[i] = (v and 0xFF).toByte()
        b[i + 1] = ((v shr 8) and 0xFF).toByte()
        return i + 2
    }
}
