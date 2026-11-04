package com.example.audiomix.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import kotlin.math.abs

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

    // ---------- Namen ----------

    fun rename(newName: String) {
        val name = newName.trim().ifBlank { project.name }
        update(project.copy(name = name))
    }

    /** Leerer Name setzt den Standardnamen "Musikzeile N" wieder ein. */
    fun renameTrack(trackId: String, newName: String) {
        val name = newName.trim().ifBlank { null }
        update(
            project.copy(
                tracks = tracks.map { if (it.id == trackId) it.copy(name = name) else it }
            )
        )
    }

    // ---------- Tempo ----------

    /**
     * Setzt das Projekt-Tempo. Die Zeilen in [changedRowIds] bekommen das neue Tempo,
     * alle anderen Audiozeilen behalten ihr eigenes (ihre Blöcke werden in Takten umgerechnet).
     * Die gedehnten Audio-Dateien müssen vorher bereitliegen.
     */
    fun applyProjectBpm(newBpm: Int, changedRowIds: Set<String>) {
        val p = project.bpm.toDouble()
        val p2 = newBpm.toDouble()
        val newTracks = tracks.map { t ->
            if (t.type != TrackType.AUDIO) {
                t
            } else {
                val r = t.tempo(project.bpm)
                val r2 = if (t.id in changedRowIds) p2 else r
                val factor = (p2 / r2) / (p / r)
                t.copy(bpm = r2, clips = rescale(t.clips, factor))
            }
        }
        undoStack.clear()
        redoStack.clear()
        refreshFlags()
        update(
            project.copy(
                bpm = newBpm,
                tracks = newTracks,
                durationMs = durationFor(newTracks, newBpm)
            )
        )
    }

    /** Setzt das Tempo einer einzelnen Audiozeile (die gedehnten Dateien liegen vorher bereit). */
    fun applyRowBpm(trackId: String, newBpm: Double) {
        val newTracks = tracks.map { t ->
            if (t.id == trackId && t.type == TrackType.AUDIO) {
                val r = t.tempo(project.bpm)
                val factor = r / newBpm
                t.copy(bpm = newBpm, clips = rescale(t.clips, factor))
            } else {
                t
            }
        }
        undoStack.clear()
        redoStack.clear()
        refreshFlags()
        update(
            project.copy(
                tracks = newTracks,
                durationMs = durationFor(newTracks, project.bpm)
            )
        )
    }

    // ---------- Blöcke ----------

    fun addDrumClip() {
        val clip = Clip(
            id = UUID.randomUUID().toString(),
            startBar = cursorBar,
            lengthBar = 1.0
        )
        insertClip(TrackType.DRUM, clip)
    }

    /**
     * Fügt eine MP3 ein. [rowBpm] ist das Tempo, in dem sie klingt, [sourceBpm] das Tempo der Datei.
     * Bei [joinSelectedRow] kommt sie in die ausgewählte Audiozeile, sonst in eine neue Zeile.
     */
    fun addAudioClip(
        fileName: String,
        durationSec: Double,
        startBar: Double,
        sourceBpm: Double,
        rowBpm: Double,
        joinSelectedRow: Boolean
    ) {
        val lengthBar = durationSec * sourceBpm * project.bpm / (240.0 * rowBpm)
        val clip = Clip(
            id = UUID.randomUUID().toString(),
            startBar = startBar,
            lengthBar = lengthBar,
            offsetBar = 0.0,
            sourceFile = fileName,
            sourceLengthBar = lengthBar,
            sourceBpm = sourceBpm
        )
        val selected = tracks.firstOrNull { it.id == selectedTrackId }
        val newTracks = if (joinSelectedRow && selected != null && selected.type == TrackType.AUDIO) {
            tracks.map {
                if (it.id == selected.id) it.copy(clips = overwrite(it.clips, clip)) else it
            }
        } else {
            tracks + Track(
                id = UUID.randomUUID().toString(),
                type = TrackType.AUDIO,
                clips = listOf(clip),
                bpm = rowBpm
            )
        }
        commitTracks(newTracks)
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
        applyTracks(withCurrentNames(undoStack.removeAt(undoStack.lastIndex)))
        fixSelection()
        refreshFlags()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(project.tracks)
        applyTracks(withCurrentNames(redoStack.removeAt(redoStack.lastIndex)))
        fixSelection()
        refreshFlags()
    }

    // ---------- intern ----------

    /** Beim Rückgängig-Machen bleiben die aktuellen Zeilennamen erhalten. */
    private fun withCurrentNames(snapshot: List<Track>): List<Track> =
        snapshot.map { s ->
            val current = tracks.firstOrNull { it.id == s.id }
            if (current != null) s.copy(name = current.name) else s
        }

    private fun durationFor(list: List<Track>, bpm: Int): Long {
        val end = list.flatMap { it.clips }.maxOfOrNull { it.endBar } ?: 0.0
        return (end * 4.0 * 60_000.0 / bpm).toLong()
    }

    /** Rechnet Längen und Versatz um; Überlappungen werden vom späteren Block überschrieben. */
    private fun rescale(clips: List<Clip>, factor: Double): List<Clip> {
        if (abs(factor - 1.0) < 1e-9) return clips
        var result: List<Clip> = emptyList()
        for (c in clips.sortedBy { it.startBar }) {
            val scaled = c.copy(
                lengthBar = c.lengthBar * factor,
                offsetBar = c.offsetBar * factor,
                sourceLengthBar = c.sourceLengthBar * factor
            )
            result = overwrite(result, scaled)
        }
        return result
    }

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
        update(project.copy(tracks = newTracks, durationMs = durationFor(newTracks, project.bpm)))
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
