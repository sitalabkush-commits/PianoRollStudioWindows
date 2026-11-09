package com.pianoroll.core.audioengine

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

internal enum class Wave { SINE, TRI, SAW, SQUARE, ORGAN }

internal class Profile(
    val wave: Wave,
    val attack: Float,
    val decay: Float,      // seconds, time constant toward sustain
    val sustain: Float,
    val release: Float,
    val detune: Float = 0f,
    val lp: Float = 1f,    // one-pole smoothing 0..1 (1 = bright)
    val bell: Float = 0f,  // amount of 2nd/3rd harmonic sine
)

internal object Profiles {
    private val PIANO = Profile(Wave.TRI, 0.004f, 0.9f, 0.25f, 0.25f, lp = 0.6f, bell = 0.25f)
    private val EPIANO = Profile(Wave.SINE, 0.004f, 0.7f, 0.35f, 0.25f, bell = 0.35f)
    private val PERC = Profile(Wave.SINE, 0.002f, 0.35f, 0f, 0.2f, bell = 0.5f)
    private val ORGAN = Profile(Wave.ORGAN, 0.01f, 0.1f, 1f, 0.08f)
    private val GUITAR = Profile(Wave.SAW, 0.003f, 0.45f, 0.12f, 0.2f, lp = 0.25f)
    private val BASS = Profile(Wave.SAW, 0.005f, 0.4f, 0.75f, 0.12f, lp = 0.12f)
    private val STRINGS = Profile(Wave.SAW, 0.15f, 0.3f, 0.9f, 0.35f, detune = 0.004f, lp = 0.25f)
    private val BRASS = Profile(Wave.SAW, 0.05f, 0.2f, 0.8f, 0.12f, lp = 0.3f)
    private val FLUTE = Profile(Wave.TRI, 0.06f, 0.2f, 0.85f, 0.15f, bell = 0.1f)
    private val LEAD = Profile(Wave.SQUARE, 0.005f, 0.2f, 0.8f, 0.1f, detune = 0.003f, lp = 0.45f)
    private val PAD = Profile(Wave.SAW, 0.4f, 0.3f, 0.9f, 0.7f, detune = 0.006f, lp = 0.18f)
    private val DEFAULT = Profile(Wave.TRI, 0.005f, 0.5f, 0.6f, 0.15f)

    /** "gm:<program>" | "gm:drums" | "sf2:..." -> profile (sf2 falls back until FluidSynth lands). */
    fun forInstrument(id: String): Profile {
        if (!id.startsWith("gm:")) return PIANO
        val prog = id.removePrefix("gm:").toIntOrNull() ?: return DEFAULT
        return when (prog) {
            4, 5 -> EPIANO
            in 0..7 -> PIANO
            in 8..15 -> PERC
            in 16..23 -> ORGAN
            in 24..31 -> GUITAR
            in 32..39 -> BASS
            in 40..55 -> STRINGS
            in 56..71 -> BRASS
            in 72..79 -> FLUTE
            in 80..87 -> LEAD
            in 88..103 -> PAD
            else -> DEFAULT
        }
    }
    fun isDrums(id: String) = id == "gm:drums"
}

/** One polyphonic voice: melodic (ADSR + oscillators) or one-shot drum. Allocation-free in render(). */
internal class Voice {
    var active = false
    var track = -1
    var pitch = 0
    var releasing = false
    var startOrder = 0L
    var holdLeft = -1       // preview auto-release countdown (samples), -1 = none

    private var drum = false
    private var p: Profile = Profiles.forInstrument("gm:0")
    private var gain = 0f
    private var freq = 440.0
    private var ph1 = 0.0
    private var ph2 = 0.0
    private var env = 0f
    private var stage = 0           // 0 attack, 1 decay->sustain, 2 release
    private var atkInc = 0f
    private var decMul = 0f
    private var relMul = 0f
    private var lpState = 0f
    private var age = 0L
    private var seed = 22222
    private var hp = 0f

    fun start(trackIdx: Int, pitch: Int, vel: Int, instrument: String, sr: Int, order: Long) {
        this.track = trackIdx; this.pitch = pitch; this.startOrder = order
        active = true; releasing = false; holdLeft = -1
        drum = Profiles.isDrums(instrument)
        gain = (vel / 127f).pow(1.3f) * 0.5f
        age = 0; ph1 = 0.0; ph2 = 0.0; lpState = 0f; hp = 0f; seed = 12345 + pitch * 77
        if (drum) { env = 1f; return }
        p = Profiles.forInstrument(instrument)
        freq = 440.0 * 2.0.pow((pitch - 69) / 12.0)
        env = 0f; stage = 0
        atkInc = 1f / (maxOf(p.attack, 0.001f) * sr)
        decMul = exp(-1.0 / (p.decay * sr)).toFloat()
        relMul = exp(-1.0 / (p.release * sr)).toFloat()
    }

    fun release(sr: Int) {
        if (drum || releasing) return
        releasing = true; stage = 2
    }

    private fun noise(): Float {
        seed = seed * 1664525 + 1013904223
        return (seed shr 8) / 8388608f - 1f
    }

    private fun osc(w: Wave, ph: Double): Float {
        val x = ph - Math.floor(ph)
        return when (w) {
            Wave.SINE -> sin(2 * PI * x).toFloat()
            Wave.TRI -> (4 * Math.abs(x - 0.5) - 1).toFloat()
            Wave.SAW -> (2 * x - 1).toFloat()
            Wave.SQUARE -> if (x < 0.5) 0.6f else -0.6f
            Wave.ORGAN -> ((sin(2 * PI * x) + 0.5 * sin(4 * PI * x) + 0.25 * sin(6 * PI * x)) / 1.75).toFloat()
        }
    }

    /** Adds n samples into l/r (mono -> both). `rate` scales pitch (tape effects). */
    fun render(l: FloatArray, r: FloatArray, n: Int, rate: Float, sr: Int) {
        if (!active) return
        for (i in 0 until n) {
            val s = if (drum) drumSample(sr, rate) else toneSample(sr, rate)
            if (!active) break
            l[i] += s; r[i] += s
            if (holdLeft > 0 && --holdLeft == 0) release(sr)
        }
    }

    private fun toneSample(sr: Int, rate: Float): Float {
        when (stage) {
            0 -> { env += atkInc; if (env >= 1f) { env = 1f; stage = 1 } }
            1 -> env = p.sustain + (env - p.sustain) * decMul
            else -> { env *= relMul; if (env < 0.0006f) { active = false; return 0f } }
        }
        val inc = freq * rate / sr
        ph1 += inc
        var x = osc(p.wave, ph1)
        if (p.detune > 0f) { ph2 += inc * (1.0 + p.detune); x = 0.5f * (x + osc(p.wave, ph2)) }
        if (p.bell > 0f) x += p.bell * (sin(4 * PI * ph1).toFloat() * 0.6f + sin(6 * PI * ph1).toFloat() * 0.3f)
        if (p.lp < 1f) { lpState += p.lp * (x - lpState); x = lpState }
        return x * env * gain
    }

    private fun drumSample(sr: Int, rate: Float): Float {
        val t = age / sr.toFloat()
        age++
        val out: Float
        when (pitch) {
            35, 36 -> {   // kick
                val f = 45.0 + 110.0 * exp(-t * 30.0)
                ph1 += f / sr
                val e = exp(-t * 7.0).toFloat()
                out = sin(2 * PI * ph1).toFloat() * e * 1.6f
                if (e < 0.002f) active = false
            }
            37, 38, 40 -> {   // snare / rim
                val e = exp(-t * 18.0).toFloat()
                ph1 += 190.0 / sr
                val n = noise()
                hp = n - hp * 0.5f
                out = (hp * 0.9f + sin(2 * PI * ph1).toFloat() * 0.5f * exp(-t * 35.0).toFloat()) * e
                if (e < 0.003f) active = false
            }
            39 -> {   // clap
                val burst = if ((t % 0.012f) < 0.006f && t < 0.04f) 1f else 0.5f
                val e = exp(-t * 20.0).toFloat()
                out = noise() * e * burst
                if (e < 0.003f) active = false
            }
            42, 44, 46, 49, 51, 52, 53, 55, 57, 59 -> {   // hats / cymbals
                val k = when (pitch) { 42, 44 -> 70.0; 46 -> 12.0; 49, 57, 52, 55 -> 3.0; else -> 6.0 }
                val e = exp(-t * k).toFloat()
                val n = noise()
                hp = n - hp * 0.2f
                out = hp * e * 0.6f
                if (e < 0.003f) active = false
            }
            41, 43, 45, 47, 48, 50 -> {   // toms
                val base = 70.0 + (pitch - 41) * 18.0
                ph1 += (base + 60.0 * exp(-t * 20.0)) / sr
                val e = exp(-t * 9.0).toFloat()
                out = sin(2 * PI * ph1).toFloat() * e
                if (e < 0.003f) active = false
            }
            56 -> {   // cowbell
                ph1 += 560.0 / sr; ph2 += 845.0 / sr
                val e = exp(-t * 14.0).toFloat()
                out = (osc(Wave.SQUARE, ph1) + osc(Wave.SQUARE, ph2)) * 0.5f * e
                if (e < 0.003f) active = false
            }
            else -> {
                val e = exp(-t * 40.0).toFloat()
                out = noise() * e
                if (e < 0.003f) active = false
            }
        }
        return out * gain
    }
}
