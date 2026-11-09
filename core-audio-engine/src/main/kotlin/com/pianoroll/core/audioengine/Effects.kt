package com.pianoroll.core.audioengine

import com.pianoroll.core.model.EffectSpec
import com.pianoroll.core.model.EffectType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Per-track stereo insert effects. In-place, allocation-free in process().
 * Params (EffectSpec.params keys, all optional):
 *  REVERB: mix(.25) room(.8) damp(.4) | DELAY: time(.3s) feedback(.35) mix(.3) | CHORUS: rate(.8Hz) depth(.003s) mix(.5)
 *  DISTORTION: drive(6) mix(1) | EQ: lowDb(0) highDb(0) | FILTER: cutoff(8000Hz) type(0=LP,1=HP)
 *  COMPRESSOR: thresholdDb(-18) ratio(4) attackMs(5) releaseMs(80) makeupDb(0)
 */
internal interface Fx { fun process(l: FloatArray, r: FloatArray, n: Int) }

internal object FxFactory {
    fun create(spec: EffectSpec, sr: Int): Fx? {
        if (spec.bypass) return null
        val p = spec.params
        fun g(k: String, d: Float) = p[k] ?: d
        return when (spec.type) {
            EffectType.REVERB -> ReverbFx(sr, g("mix", 0.25f), g("room", 0.8f), g("damp", 0.4f))
            EffectType.DELAY -> DelayFx(sr, g("time", 0.3f), g("feedback", 0.35f), g("mix", 0.3f))
            EffectType.CHORUS -> ChorusFx(sr, g("rate", 0.8f), g("depth", 0.003f), g("mix", 0.5f))
            EffectType.DISTORTION -> DistortionFx(g("drive", 6f), g("mix", 1f))
            EffectType.EQ -> EqFx(sr, g("lowDb", 0f), g("highDb", 0f))
            EffectType.FILTER -> FilterFx(sr, g("cutoff", 8000f), g("type", 0f) >= 0.5f)
            EffectType.COMPRESSOR -> CompressorFx(sr, g("thresholdDb", -18f), g("ratio", 4f), g("attackMs", 5f), g("releaseMs", 80f), g("makeupDb", 0f))
        }
    }
}

internal class DelayFx(sr: Int, time: Float, private val fb: Float, private val mix: Float) : Fx {
    private val len = (time.coerceIn(0.01f, 2f) * sr).toInt()
    private val bl = FloatArray(len)
    private val br = FloatArray(len)
    private var pos = 0
    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            val dl = bl[pos]; val dr = br[pos]
            bl[pos] = l[i] + dl * fb
            br[pos] = r[i] + dr * fb
            l[i] += dl * mix; r[i] += dr * mix
            if (++pos >= len) pos = 0
        }
    }
}

private class Comb(val size: Int, val feedback: Float, val damp: Float) {
    val buf = FloatArray(size)
    var idx = 0
    var store = 0f
    fun tick(x: Float): Float {
        val o = buf[idx]
        store = o * (1f - damp) + store * damp
        buf[idx] = x + store * feedback
        if (++idx >= size) idx = 0
        return o
    }
}

private class Allpass(val size: Int) {
    val buf = FloatArray(size)
    var idx = 0
    fun tick(x: Float): Float {
        val b = buf[idx]
        val o = -x + b
        buf[idx] = x + b * 0.5f
        if (++idx >= size) idx = 0
        return o
    }
}

/** Freeverb-style Schroeder reverb. */
internal class ReverbFx(sr: Int, private val mix: Float, room: Float, damp: Float) : Fx {
    private val k = sr / 44100f
    private val fbk = 0.7f + 0.28f * room.coerceIn(0f, 1f)
    private val combL = intArrayOf(1116, 1188, 1277, 1356).map { Comb((it * k).toInt(), fbk, damp.coerceIn(0f, 0.95f)) }
    private val combR = intArrayOf(1139, 1211, 1300, 1379).map { Comb((it * k).toInt(), fbk, damp.coerceIn(0f, 0.95f)) }
    private val apL = intArrayOf(556, 441).map { Allpass((it * k).toInt()) }
    private val apR = intArrayOf(579, 464).map { Allpass((it * k).toInt()) }
    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            val inp = (l[i] + r[i]) * 0.5f * 0.2f
            var wl = 0f; var wr = 0f
            for (c in combL) wl += c.tick(inp)
            for (c in combR) wr += c.tick(inp)
            for (a in apL) wl = a.tick(wl)
            for (a in apR) wr = a.tick(wr)
            l[i] += wl * mix; r[i] += wr * mix
        }
    }
}

internal class ChorusFx(private val sr: Int, private val rate: Float, depth: Float, private val mix: Float) : Fx {
    private val size = (0.05f * sr).toInt()
    private val bl = FloatArray(size)
    private val br = FloatArray(size)
    private val base = 0.015f * sr
    private val dep = depth * sr
    private var w = 0
    private var lfo = 0.0
    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            bl[w] = l[i]; br[w] = r[i]
            lfo += rate / sr; if (lfo >= 1.0) lfo -= 1.0
            val dL = base + dep * sin(2 * PI * lfo).toFloat()
            val dR = base + dep * sin(2 * PI * (lfo + 0.25)).toFloat()
            l[i] += read(bl, dL) * mix
            r[i] += read(br, dR) * mix
            if (++w >= size) w = 0
        }
    }
    private fun read(b: FloatArray, d: Float): Float {
        var rp = w - d
        while (rp < 0f) rp += size
        val i0 = rp.toInt() % size
        val i1 = (i0 + 1) % size
        val f = rp - rp.toInt()
        return b[i0] * (1f - f) + b[i1] * f
    }
}

internal class DistortionFx(private val drive: Float, private val mix: Float) : Fx {
    private val comp = 1f / tanh(drive.coerceAtLeast(1f))
    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        val d = drive.coerceAtLeast(1f)
        for (i in 0 until n) {
            l[i] = l[i] * (1f - mix) + tanh(l[i] * d) * comp * 0.7f * mix
            r[i] = r[i] * (1f - mix) + tanh(r[i] * d) * comp * 0.7f * mix
        }
    }
}

internal class FilterFx(sr: Int, cutoff: Float, private val highpass: Boolean) : Fx {
    private val a = (1.0 - exp(-2.0 * PI * cutoff.coerceIn(20f, sr * 0.45f) / sr)).toFloat()
    private var zl = 0f
    private var zr = 0f
    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            zl += a * (l[i] - zl); zr += a * (r[i] - zr)
            if (highpass) { l[i] -= zl; r[i] -= zr } else { l[i] = zl; r[i] = zr }
        }
    }
}

internal class EqFx(sr: Int, lowDb: Float, highDb: Float) : Fx {
    private val gl = 10f.pow(lowDb / 20f) - 1f
    private val gh = 10f.pow(highDb / 20f) - 1f
    private val aLow = (1.0 - exp(-2.0 * PI * 200.0 / sr)).toFloat()
    private val aHigh = (1.0 - exp(-2.0 * PI * 4000.0 / sr)).toFloat()
    private var lowL = 0f; private var lowR = 0f; private var hiL = 0f; private var hiR = 0f
    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            lowL += aLow * (l[i] - lowL); lowR += aLow * (r[i] - lowR)
            hiL += aHigh * (l[i] - hiL); hiR += aHigh * (r[i] - hiR)
            l[i] += gl * lowL + gh * (l[i] - hiL)
            r[i] += gl * lowR + gh * (r[i] - hiR)
        }
    }
}

internal class CompressorFx(sr: Int, private val thDb: Float, private val ratio: Float, atkMs: Float, relMs: Float, makeupDb: Float) : Fx {
    private val atk = exp(-1.0 / (atkMs.coerceAtLeast(0.1f) * 0.001 * sr)).toFloat()
    private val rel = exp(-1.0 / (relMs.coerceAtLeast(1f) * 0.001 * sr)).toFloat()
    private val makeup = 10f.pow(makeupDb / 20f)
    private var env = 0f
    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            val x = maxOf(abs(l[i]), abs(r[i]))
            env = if (x > env) atk * env + (1f - atk) * x else rel * env + (1f - rel) * x
            val db = 20f * log10(env.coerceAtLeast(1e-6f))
            val over = db - thDb
            val gainDb = if (over > 0f) -over * (1f - 1f / ratio.coerceAtLeast(1f)) else 0f
            val g = 10f.pow(gainDb / 20f) * makeup
            l[i] *= g; r[i] *= g
        }
    }
}
