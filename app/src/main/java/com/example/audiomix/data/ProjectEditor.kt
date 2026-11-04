package com.example.audiomix.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

class ProjectEditor(
    initial: Project,
    private val onSave: (Project) -> Unit
) {
    var project by mutableStateOf(initial)
        private set

    var selectedTrackId by mutableStateOf<String?>(null)
        private set
    var selectedClipId by mutableStateOf<String?>(null)
        private set

    /** Cursorposition in Takten. */
    var cursorBar by mutableDoubleStateOf(0.0)

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private val undoStack = ArrayList<List<Track>>()
    private val redoStack = ArrayList<List<Track>>()

    val tracks: List<Track> get() = project.tracks

    fun endBar(): Double = tracks.flatMap { it.clips }.maxOfOrNull { it.endBar } ?: 0.0

    // ---------- Auswahl ----------

    fun selectTrack(id: String) {
        selectedTrackId = id
        selectedClipId = null
    }

    fun selectClip(id: String?) {
        selectedClipId = id
    }

    fun clearSelection() {
        selectedTrackId = null
        selectedClipId = null
    }

    // ---------- Änderungen ----------

    fun rename(newName: String) {
        val name = newName.trim().ifBlank { project.name }
        update(project.copy(name = name))
    }

    /** Setzt das Projekt-Tempo (die gedehnten Audio-Dateien müssen vorher bereitliegen). */
    fun setBpm(newBpm: Int) {
        val durationMs = (endBar() * 4.0 * 60_000.0 / newBpm).toLong()
        update(project.copy(bpm = newBpm, durationMs = durationMs))
    }

    fun addDrumClip() {
        val clip = Clip(
            id = UUID.randomUUID().toString(),
            startBar = cursorBar,
            lengthBar = 1.0
        )
        insertClip(TrackType.DRUM, clip)
    }

    fun addAudioClip(fileName: String, lengthBar: Double, startBar: Double, sourceBpm: Double) {
        val clip = Clip(
            id = UUID.randomUUID().toString(),
            startBar = startBar,
            lengthBar = lengthBar,
            offsetBar = 0.0,
            sourceFile = fileName,
            sourceLengthBar = lengthBar,
            sourceBpm = sourceBpm
        )
        insertClip(TrackType.AUDIO, clip)
    }

    /** Fügt einen Clip in die ausgewählte Zeile (gleiche Audioart) oder in eine neue Zeile ein. */
    fun insertClip(type: TrackType, clip: Clip) {
        val selected = tracks.firstOrNull { it.id == selectedTrackId }
        val newTracks = if (selected != null && selected.type == type) {
            tracks.map {
                if (it.id == selected.id) it.copy(clips = overwrite(it.clips, clip)) else it
            }
        } else {
            tracks + Track(UUID.randomUUID().toString(), type, listOf(clip))
        }
        commitTracks(newTracks)
    }

    /** Ersetzt einen vorhandenen Clip (gekürzt, verlängert oder verschoben). */
    fun replaceClip(trackId: String, newClip: Clip) {
        val newTracks = tracks.map { t ->
            if (t.id == trackId) {
                val others = t.clips.filter { it.id != newClip.id }
                t.copy(clips = overwrite(others, newClip))
            } else {
                t
            }
        }
        commitTracks(newTracks)
    }

    /** Löscht den ausgewählten Clip, sonst die ausgewählte Zeile. */
    fun deleteSelection() {
        val trackId = selectedTrackId ?: return
        val clipId = selectedClipId
        if (clipId != null) {
            commitTracks(
                tracks.map {
                    if (it.id == trackId) it.copy(clips = it.clips.filter { c -> c.id != clipId }) else it
                }
            )
            selectedClipId = null
        } else {
            commitTracks(tracks.filter { it.id != trackId })
            clearSelection()
        }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(project.tracks)
        applyTracks(undoStack.removeAt(undoStack.lastIndex))
        fixSelection()
        refreshFlags()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(project.tracks)
        applyTracks(redoStack.removeAt(redoStack.lastIndex))
        fixSelection()
        refreshFlags()
    }

    // ---------- intern ----------

    private fun overwrite(clips: List<Clip>, newClip: Clip): List<Clip> {
        val s = newClip.startBar
        val e = newClip.endBar
        val result = ArrayList<Clip>()
        for (c in clips) {
            if (c.endBar <= s || c.startBar >= e) {
                result.add(c)
                continue
            }
            if (c.startBar < s) {
                result.add(c.copy(lengthBar = s - c.startBar))
            }
            if (c.endBar > e) {
                result.add(
                    c.copy(
                        id = if (c.startBar < s) UUID.randomUUID().toString() else c.id,
                        startBar = e,
                        lengthBar = c.endBar - e,
                        offsetBar = c.offsetBar + (e - c.startBar)
                    )
                )
            }
        }
        result.add(newClip)
        return result.sortedBy { it.startBar }
    }

    private fun commitTracks(newTracks: List<Track>) {
        undoStack.add(project.tracks)
        if (undoStack.size > 100) undoStack.removeAt(0)
        redoStack.clear()
        applyTracks(newTracks)
        refreshFlags()
    }

    private fun applyTracks(newTracks: List<Track>) {
        val end = newTracks.flatMap { it.clips }.maxOfOrNull { it.endBar } ?: 0.0
        val durationMs = (end * 4.0 * 60_000.0 / project.bpm).toLong()
        update(project.copy(tracks = newTracks, durationMs = durationMs))
    }

    private fun update(p: Project) {
        project = p.copy(lastModified = System.currentTimeMillis())
        onSave(project)
    }

    private fun fixSelection() {
        val track = tracks.firstOrNull { it.id == selectedTrackId }
        if (track == null) {
            clearSelection()
        } else if (track.clips.none { it.id == selectedClipId }) {
            selectedClipId = null
        }
    }

    private fun refreshFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }
}
