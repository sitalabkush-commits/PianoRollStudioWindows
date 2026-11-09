package com.pianoroll.export

import kotlin.test.*

/** Needs the kotlinx.serialization compiler plugin (applied by Gradle). */
class ProjectFileTest {
    @Test fun roundTrip() {
        val p = sampleProject(song = true)
        assertEquals(p, ProjectFile.decode(ProjectFile.encode(p)).getOrThrow())
    }

    @Test fun rejectsGarbageAndForeignAndNewer() {
        assertTrue(ProjectFile.decode("not json").isFailure)
        assertTrue(ProjectFile.decode("""{"format":"other","version":1,"project":{"name":"x"}}""").isFailure)
        assertTrue(ProjectFile.decode("""{"format":"${ProjectFile.FORMAT}","version":99,"project":{"name":"x"}}""").isFailure)
    }

    @Test fun toleratesUnknownKeys() {
        val r = ProjectFile.decode("""{"format":"${ProjectFile.FORMAT}","version":1,"future":true,"project":{"name":"x","extra":1}}""")
        assertEquals("x", r.getOrThrow().name)
    }
}
