package com.pianoroll.app

import android.content.Context
import androidx.room.Room
import com.pianoroll.audio.AudioTrackEngine
import com.pianoroll.core.audio.AudioEngine
import com.pianoroll.core.model.Project
import com.pianoroll.core.model.ProjectRepository
import com.pianoroll.core.model.ProjectStore
import com.pianoroll.data.AppDatabase
import com.pianoroll.export.DefaultExporter
import com.pianoroll.export.Exporter
import com.pianoroll.data.RoomProjectRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun db(@ApplicationContext c: Context): AppDatabase = Room.databaseBuilder(c, AppDatabase::class.java, "pianoroll.db").build()

    @Provides @Singleton
    fun repo(db: AppDatabase): ProjectRepository = RoomProjectRepository(db.projectDao())

    @Provides @Singleton
    fun store(): ProjectStore = ProjectStore(Project.new("Untitled"))

    // Real engine (Claude C): pure-Kotlin synth + AudioTrack.
    @Provides @Singleton
    fun audio(): AudioEngine = AudioTrackEngine()

    // Export (Claude E): MIDI / WAV / stems now. MP3 needs a platform Mp3Encoder (LAME/MediaCodec): pass one here when it exists.
    @Provides @Singleton
    fun exporter(): Exporter = DefaultExporter()
}
