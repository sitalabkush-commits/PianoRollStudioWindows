package com.pianoroll.core.music

/** One place to get the default (stateless) implementations. Safe to share between threads. */
object MusicEngine {
    val chords: DefaultChordGenerator = DefaultChordGenerator()
    val arpeggiator: DefaultArpeggiator = DefaultArpeggiator()
    val drums: DefaultDrumGenerator = DefaultDrumGenerator()
    val transpose: DefaultTransposeEngine = DefaultTransposeEngine()
    val chop: DefaultChopEngine = DefaultChopEngine()
}
