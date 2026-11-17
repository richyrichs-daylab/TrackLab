package com.example.audiomix.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

class ProjectEditor(
    initial: Project,
    private val onSave: (Project) -> Unit
) {
    var project by mutableStateOf(initial)
        private set

    var selectedTrackIds by mutableStateOf<List<String>>(emptyList())
        private set

    var selectedClipId by mutableStateOf<String?>(null)
        private set

    /** Die ausgewählte Zeile, aber nur wenn genau eine ausgewählt ist. */
    val selectedTrackId: String? get() = selectedTrackIds.singleOrNull()

    /** Cursorposition in Takten. */
    var cursorBar by mutableDoubleStateOf(0.0)

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private val undoStack = ArrayList<List<Track>>()
    private val redoStack = ArrayList<List<Track>>()

    /** Zustand vor fortlaufenden Änderungen (Musikrolle, Ein-/Ausblenden): zusammen ein Schritt. */
    private var patternBase: List<Track>? = null

    val tracks: List<Track> get() = project.tracks

    fun endBar(): Double = tracks.flatMap { it.clips }.maxOfOrNull { it.endBar } ?: 0.0

    fun selectedBlock(): Pair<Track, Clip>? {
        val t = tracks.firstOrNull { it.id == selectedTrackId } ?: return null
        val c = t.clips.firstOrNull { it.id == selectedClipId } ?: return null
        return Pair(t, c)
    }

    // ---------- Auswahl ----------

    fun selectTrack(id: String) {
        selectedTrackIds = listOf(id)
        selectedClipId = null
    }

    fun addToSelection(id: String) {
        if (id !in selectedTrackIds) selectedTrackIds = selectedTrackIds + id
        selectedClipId = null
    }

    fun toggleSelection(id: String) {
        selectedTrackIds = if (id in selectedTrackIds) {
            selectedTrackIds.filter { it != id }
        } else {
            selectedTrackIds + id
        }
        selectedClipId = null
    }

    fun selectClip(id: String?) {
        selectedClipId = id
    }

    fun clearSelection() {
        selectedTrackIds = emptyList()
        selectedClipId = null
    }

    // ---------- Namen, Stumm, Lautstärke ----------

    fun rename(newName: String) {
        val name = newName.trim().ifBlank { project.name }
        update(project.copy(name = name))
    }

    fun renameTrack(trackId: String, newName: String) {
        val name = newName.trim().ifBlank { null }
        update(project.copy(tracks = tracks.map { if (it.id == trackId) it.copy(name = name) else it }))
    }

    /** Schaltet alle ausgewählten Zeilen stumm (oder wieder laut). Steht im Rückgängig-Verlauf. */
    fun toggleMute() {
        val selected = tracks.filter { it.id in selectedTrackIds }
        if (selected.isEmpty()) return
        val mute = !selected.all { it.muted }
        commitTracks(tracks.map { if (it.id in selectedTrackIds) it.copy(muted = mute) else it })
    }

    fun setVolume(percent: Int) {
        val p = percent.coerceIn(0, 200)
        update(
            project.copy(
                tracks = tracks.map { if (it.id in selectedTrackIds) it.copy(volume = p) else it }
            )
        )
    }

    // ---------- Effekte (gelten für alle ausgewählten Zeilen) ----------

    private fun mapSelected(change: (Track) -> Track) {
        update(
            project.copy(
                tracks = tracks.map { if (it.id in selectedTrackIds) change(it) else it }
            )
        )
    }

    /** Equalizer-Band (0 Treble, 1 Mid, 2 Bass) in dB. */
    fun setEqGain(band: Int, db: Int) = mapSelected { t ->
        t.copy(eqGain = t.eqGain.toMutableList().also { it[band] = db.coerceIn(-12, 12) })
    }

    /** Verlauf eines Equalizer-Bands (Takte, bezogen auf die ganze Zeile). */
    fun setEqFade(band: Int, fadeInBars: Double, fadeOutBars: Double) = mapSelected { t ->
        t.copy(
            eqFadeIn = t.eqFadeIn.toMutableList().also { it[band] = fadeInBars },
            eqFadeOut = t.eqFadeOut.toMutableList().also { it[band] = fadeOutBars }
        )
    }

    /** Delay-Regler (0 Decay, 1 Time, 2 Mix), Wert 0 bis 1. */
    fun setDelay(param: Int, value: Double) = mapSelected { t ->
        t.copy(delay = t.delay.toMutableList().also { it[param] = value.coerceIn(0.0, 1.0) })
    }

    fun setDelayFade(param: Int, fadeInBars: Double, fadeOutBars: Double) = mapSelected { t ->
        t.copy(
            delayFadeIn = t.delayFadeIn.toMutableList().also { it[param] = fadeInBars },
            delayFadeOut = t.delayFadeOut.toMutableList().also { it[param] = fadeOutBars }
        )
    }

    fun setReverbLevel(level: Double) = mapSelected { t ->
        t.copy(reverbLevel = level.coerceIn(0.0, 1.0))
    }

    fun setReverbFade(fadeInBars: Double, fadeOutBars: Double) = mapSelected { t ->
        t.copy(reverbFadeIn = fadeInBars, reverbFadeOut = fadeOutBars)
    }

    fun setReverbPreset(index: Int) = mapSelected { t ->
        t.copy(reverbPreset = index.coerceIn(0, 5))
    }

    /** Radio-Regler (0 Gain, 1 Tone), Wert 0 bis 1. */
    fun setRadio(param: Int, value: Double) = mapSelected { t ->
        t.copy(radio = t.radio.toMutableList().also { it[param] = value.coerceIn(0.0, 1.0) })
    }

    fun setRadioFade(param: Int, fadeInBars: Double, fadeOutBars: Double) = mapSelected { t ->
        t.copy(
            radioFadeIn = t.radioFadeIn.toMutableList().also { it[param] = fadeInBars },
            radioFadeOut = t.radioFadeOut.toMutableList().also { it[param] = fadeOutBars }
        )
    }

    /** 3D-Position: 0 Mitte, 1 vorne, 2 rechts, 3 links, 4 hinten. */
    fun setThreeDPos(pos: Int) = mapSelected { t -> t.copy(threeDPos = pos.coerceIn(0, 4)) }

    fun setThreeDAcid(value: Double) = mapSelected { t -> t.copy(threeDAcid = value.coerceIn(0.0, 1.0)) }

    /** Schaltet einen Effekt ("pitch", "delay", "reverb", "radio", "3d") an oder aus (Standard: an). */
    fun setFxEnabled(key: String, enabled: Boolean) = mapSelected { t ->
        t.copy(fxOff = if (enabled) t.fxOff - key else t.fxOff + key)
    }

    /** Setzt einen Effekt auf die Standardeinstellungen zurück (und schaltet ihn an). */
    fun resetEffect(key: String) = mapSelected { t ->
        when (key) {
            "pitch" -> t.copy(
                eqGain = listOf(0, 0, 0),
                eqFadeIn = listOf(0.0, 0.0, 0.0),
                eqFadeOut = listOf(0.0, 0.0, 0.0),
                fxOff = t.fxOff - key
            )
            "delay" -> t.copy(
                delay = FxDefaults.DELAY,
                delayFadeIn = listOf(0.0, 0.0, 0.0),
                delayFadeOut = listOf(0.0, 0.0, 0.0),
                fxOff = t.fxOff - key
            )
            "reverb" -> t.copy(
                reverbLevel = 0.0,
                reverbFadeIn = 0.0,
                reverbFadeOut = 0.0,
                reverbPreset = FxDefaults.REVERB_PRESET,
                fxOff = t.fxOff - key
            )
            "radio" -> t.copy(
                radio = FxDefaults.RADIO,
                radioFadeIn = listOf(0.0, 0.0),
                radioFadeOut = listOf(0.0, 0.0),
                fxOff = t.fxOff - key
            )
            "3d" -> t.copy(
                threeDPos = 0,
                threeDAcid = 0.0,
                fxOff = t.fxOff - key
            )
            else -> t
        }
    }

    /** Ein-/Ausblenden eines Blocks. Alle Änderungen bis [endPatternEdit] sind ein Rückgängig-Schritt. */
    fun setClipFade(trackId: String, clipId: String, fadeIn: Double, fadeOut: Double) {
        if (patternBase == null) patternBase = project.tracks
        applyTracks(
            tracks.map { t ->
                if (t.id != trackId) {
                    t
                } else {
                    t.copy(
                        clips = t.clips.map {
                            if (it.id == clipId) it.copy(fadeIn = fadeIn, fadeOut = fadeOut) else it
                        }
                    )
                }
            }
        )
    }

    // ---------- Tempo ----------

    private fun scaleFades(t: Track, factor: Double): Track = t.copy(
        eqFadeIn = t.eqFadeIn.map { it * factor },
        eqFadeOut = t.eqFadeOut.map { it * factor },
        delayFadeIn = t.delayFadeIn.map { it * factor },
        delayFadeOut = t.delayFadeOut.map { it * factor },
        reverbFadeIn = t.reverbFadeIn * factor,
        reverbFadeOut = t.reverbFadeOut * factor,
        radioFadeIn = t.radioFadeIn.map { it * factor },
        radioFadeOut = t.radioFadeOut.map { it * factor }
    )

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
                scaleFades(t.copy(bpm = r2, clips = rescale(t.clips, factor)), factor)
            }
        }
        patternBase = null
        undoStack.clear()
        redoStack.clear()
        refreshFlags()
        update(project.copy(bpm = newBpm, tracks = newTracks, durationMs = durationFor(newTracks, newBpm)))
    }

    fun applyRowBpm(trackId: String, newBpm: Double) {
        val newTracks = tracks.map { t ->
            if (t.id == trackId && t.type == TrackType.AUDIO) {
                val factor = t.tempo(project.bpm) / newBpm
                scaleFades(t.copy(bpm = newBpm, clips = rescale(t.clips, factor)), factor)
            } else {
                t
            }
        }
        patternBase = null
        undoStack.clear()
        redoStack.clear()
        refreshFlags()
        update(project.copy(tracks = newTracks, durationMs = durationFor(newTracks, project.bpm)))
    }

    // ---------- Blöcke und Zeilen ----------

    /** Legt einen leeren Effekt- oder Synth-Block (1 Takt) am Cursor an und wählt ihn aus. */
    fun addPatternClip(type: TrackType) {
        val clip = Clip(UUID.randomUUID().toString(), cursorBar, 1.0)
        val trackId = insertClip(type, clip)
        selectedTrackIds = listOf(trackId)
        selectedClipId = clip.id
    }

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
            sourceFile = fileName,
            sourceLengthBar = lengthBar,
            sourceBpm = sourceBpm
        )
        val selected = tracks.firstOrNull { it.id == selectedTrackId }
        val newTracks = if (joinSelectedRow && selected != null && selected.type == TrackType.AUDIO) {
            tracks.map { if (it.id == selected.id) it.copy(clips = overwrite(it.clips, clip)) else it }
        } else {
            tracks + Track(UUID.randomUUID().toString(), TrackType.AUDIO, listOf(clip), bpm = rowBpm)
        }
        commitTracks(newTracks)
    }

    /** Fügt einen Block in die ausgewählte Zeile gleicher Art oder in eine neue Zeile ein. Gibt die Zeilen-ID zurück. */
    fun insertClip(type: TrackType, clip: Clip): String {
        val selected = tracks.firstOrNull { it.id == selectedTrackId }
        val targetId: String
        val newTracks = if (selected != null && selected.type == type) {
            targetId = selected.id
            tracks.map { if (it.id == selected.id) it.copy(clips = overwrite(it.clips, clip)) else it }
        } else {
            val newId = UUID.randomUUID().toString()
            targetId = newId
            tracks + Track(newId, type, listOf(clip))
        }
        commitTracks(newTracks)
        return targetId
    }

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

    /** Hängt an den Block genau eine weitere volle Wiederholung an. */
    fun addRepeat(trackId: String, clipId: String) {
        val clip = tracks.firstOrNull { it.id == trackId }?.clips?.firstOrNull { it.id == clipId } ?: return
        replaceClip(trackId, clip.copy(repeatCount = clip.repeatCount + 1, repeatPartial = 0.0))
    }

    /** Teilt den ausgewählten Block am Cursor. Gibt eine Fehlermeldung zurück oder null. */
    fun splitSelectedClip(): String? {
        val (track, clip) = selectedBlock() ?: return "Erst einen Block auswählen"
        val tiles = clip.tiles(true)
        val cursor = cursorBar
        val target = tiles.firstOrNull { cursor > it.startBar + 1e-6 && cursor < it.endBar - 1e-6 }
            ?: return "Der Cursor muss im ausgewählten Block stehen"

        val k = ((cursor - target.startBar) * 16.0 * target.stretch).roundToInt()
        val (leftPattern, rightPattern) = splitPattern(target.pattern, k)
        val left = target.copy(lengthBar = cursor - target.startBar, pattern = leftPattern, fadeOut = 0.0)
        val right = target.copy(
            id = UUID.randomUUID().toString(),
            startBar = cursor,
            lengthBar = target.endBar - cursor,
            offsetBar = target.offsetBar + (cursor - target.startBar),
            pattern = rightPattern,
            fadeIn = 0.0
        )
        val parts = tiles.flatMap { if (it.id == target.id) listOf(left, right) else listOf(it) }
        commitTracks(
            tracks.map { t ->
                if (t.id == track.id) {
                    t.copy(clips = (t.clips.filter { it.id != clip.id } + parts).sortedBy { it.startBar })
                } else {
                    t
                }
            }
        )
        selectedClipId = left.id
        return null
    }

    private fun splitPattern(p: Set<Int>, k: Int): Pair<Set<Int>, Set<Int>> {
        val left = HashSet<Int>()
        val right = HashSet<Int>()
        for (code in p) {
            val lane = code / CELL_BASE
            val cell = code % CELL_BASE
            if (cell < k) left.add(code) else right.add(lane * CELL_BASE + (cell - k))
        }
        return Pair(left, right)
    }

    fun toggleClipMute() {
        val (track, clip) = selectedBlock() ?: return
        commitTracks(
            tracks.map { t ->
                if (t.id == track.id) {
                    t.copy(clips = t.clips.map { if (it.id == clip.id) it.copy(muted = !it.muted) else it })
                } else {
                    t
                }
            }
        )
    }

    /** Schaltet ein Feld der Musikrolle um. Gibt zurück, ob es jetzt gesetzt ist. */
    fun togglePatternCell(trackId: String, clipId: String, lane: Int, cell: Int): Boolean {
        if (patternBase == null) patternBase = project.tracks
        val code = lane * CELL_BASE + cell
        var nowOn = false
        val newTracks = tracks.map { t ->
            if (t.id != trackId) {
                t
            } else {
                t.copy(
                    clips = t.clips.map { c ->
                        if (c.id != clipId) {
                            c
                        } else {
                            nowOn = code !in c.pattern
                            c.copy(pattern = if (nowOn) c.pattern + code else c.pattern - code)
                        }
                    }
                )
            }
        }
        applyTracks(newTracks)
        return nowOn
    }

    /** Beendet fortlaufende Änderungen: alles davor zählt als ein Rückgängig-Schritt. */
    fun endPatternEdit() {
        val base = patternBase ?: return
        patternBase = null
        if (base != project.tracks) {
            undoStack.add(base)
            if (undoStack.size > 100) undoStack.removeAt(0)
            redoStack.clear()
            refreshFlags()
        }
    }

    /** Fügt den kopierten Block am Cursor ein (die Audiodatei muss schon im Projekt liegen). */
    fun pasteClip(entry: ClipboardEntry) {
        val isAudio = entry.type == TrackType.AUDIO
        val scale = if (isAudio) project.bpm.toDouble() / entry.projectBpm else 1.0
        val c = entry.clip
        val clip = c.copy(
            id = UUID.randomUUID().toString(),
            startBar = cursorBar,
            lengthBar = c.lengthBar * scale,
            offsetBar = c.offsetBar * scale,
            sourceLengthBar = c.sourceLengthBar * scale,
            repeatPartial = c.repeatPartial * scale,
            fadeIn = c.fadeIn * scale,
            fadeOut = c.fadeOut * scale
        )
        val selected = tracks.firstOrNull { it.id == selectedTrackId }
        val join = selected != null && selected.type == entry.type &&
            (!isAudio || abs(selected.tempo(project.bpm) - entry.trackTempo) < 0.0005)
        val newTracks = if (join && selected != null) {
            tracks.map { if (it.id == selected.id) it.copy(clips = overwrite(it.clips, clip)) else it }
        } else {
            tracks + Track(
                UUID.randomUUID().toString(),
                entry.type,
                listOf(clip),
                bpm = if (isAudio) entry.trackTempo else 0.0
            )
        }
        commitTracks(newTracks)
    }

    fun duplicateTrack(trackId: String) {
        val index = tracks.indexOfFirst { it.id == trackId }
        if (index < 0) return
        val original = tracks[index]
        val copy = original.copy(
            id = UUID.randomUUID().toString(),
            clips = original.clips.map { it.copy(id = UUID.randomUUID().toString()) }
        )
        val newTracks = tracks.toMutableList()
        newTracks.add(index + 1, copy)
        commitTracks(newTracks)
    }

    /** Löscht den ausgewählten Block (bei einer Zeile), sonst alle ausgewählten Zeilen. */
    fun deleteSelection() {
        val ids = selectedTrackIds
        if (ids.isEmpty()) return
        val clipId = selectedClipId
        if (ids.size == 1 && clipId != null) {
            val trackId = ids[0]
            commitTracks(
                tracks.map {
                    if (it.id == trackId) it.copy(clips = it.clips.filter { c -> c.id != clipId }) else it
                }
            )
            selectedClipId = null
        } else {
            commitTracks(tracks.filter { it.id !in ids })
            clearSelection()
        }
    }

    fun undo() {
        endPatternEdit()
        if (undoStack.isEmpty()) return
        redoStack.add(project.tracks)
        applyTracks(withCurrentMeta(undoStack.removeAt(undoStack.lastIndex)))
        fixSelection()
        refreshFlags()
    }

    fun redo() {
        endPatternEdit()
        if (redoStack.isEmpty()) return
        undoStack.add(project.tracks)
        applyTracks(withCurrentMeta(redoStack.removeAt(redoStack.lastIndex)))
        fixSelection()
        refreshFlags()
    }

    // ---------- intern ----------

    /** Beim Rückgängig-Machen bleiben Zeilenname, Lautstärke und alle Effekte, wie sie gerade sind. */
    private fun withCurrentMeta(snapshot: List<Track>): List<Track> =
        snapshot.map { s ->
            val current = tracks.firstOrNull { it.id == s.id }
            if (current != null) {
                s.copy(
                    name = current.name,
                    volume = current.volume,
                    eqGain = current.eqGain,
                    eqFadeIn = current.eqFadeIn,
                    eqFadeOut = current.eqFadeOut,
                    fxOff = current.fxOff,
                    delay = current.delay,
                    delayFadeIn = current.delayFadeIn,
                    delayFadeOut = current.delayFadeOut,
                    reverbLevel = current.reverbLevel,
                    reverbFadeIn = current.reverbFadeIn,
                    reverbFadeOut = current.reverbFadeOut,
                    reverbPreset = current.reverbPreset,
                    radio = current.radio,
                    radioFadeIn = current.radioFadeIn,
                    radioFadeOut = current.radioFadeOut,
                    threeDPos = current.threeDPos,
                    threeDAcid = current.threeDAcid
                )
            } else {
                s
            }
        }

    private fun durationFor(list: List<Track>, bpm: Int): Long {
        val end = list.flatMap { it.clips }.maxOfOrNull { it.endBar } ?: 0.0
        return (end * 4.0 * 60_000.0 / bpm).toLong()
    }

    private fun rescale(clips: List<Clip>, factor: Double): List<Clip> {
        if (abs(factor - 1.0) < 1e-9) return clips
        var result: List<Clip> = emptyList()
        for (c in clips.sortedBy { it.startBar }) {
            val scaled = c.copy(
                lengthBar = c.lengthBar * factor,
                offsetBar = c.offsetBar * factor,
                sourceLengthBar = c.sourceLengthBar * factor,
                repeatPartial = c.repeatPartial * factor,
                fadeIn = c.fadeIn * factor,
                fadeOut = c.fadeOut * factor
            )
            result = overwrite(result, scaled)
        }
        return result
    }

    /** Legt den Block in die Zeile; was darunter liegt, wird zugeschnitten oder entfernt. */
    private fun overwrite(clips: List<Clip>, newClip: Clip): List<Clip> {
        val s = newClip.startBar
        val e = newClip.endBar
        val result = ArrayList<Clip>()
        for (original in clips) {
            if (original.endBar <= s || original.startBar >= e) {
                result.add(original)
                continue
            }
            // überschriebene Wiederholungen werden zu einzelnen Blöcken
            val parts = if (original.repeatCount > 0) original.tiles(true) else listOf(original)
            for (c in parts) {
                if (c.endBar <= s || c.startBar >= e) {
                    result.add(c)
                    continue
                }
                if (c.startBar < s) {
                    result.add(c.copy(lengthBar = s - c.startBar, fadeOut = 0.0))
                }
                if (c.endBar > e) {
                    val cut = e - c.startBar
                    val rightPattern = if (c.pattern.isEmpty()) {
                        c.pattern
                    } else {
                        splitPattern(c.pattern, (cut * 16.0 * c.stretch).roundToInt()).second
                    }
                    result.add(
                        c.copy(
                            id = if (c.startBar < s) UUID.randomUUID().toString() else c.id,
                            startBar = e,
                            lengthBar = c.endBar - e,
                            offsetBar = c.offsetBar + cut,
                            pattern = rightPattern,
                            fadeIn = 0.0
                        )
                    )
                }
            }
        }
        result.add(newClip)
        return result.sortedBy { it.startBar }
    }

    private fun commitTracks(newTracks: List<Track>) {
        endPatternEdit()
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
        val existing = tracks.map { it.id }.toSet()
        selectedTrackIds = selectedTrackIds.filter { it in existing }
        val single = selectedTrackId
        if (single == null) {
            selectedClipId = null
        } else {
            val track = tracks.firstOrNull { it.id == single }
            if (track == null || track.clips.none { it.id == selectedClipId }) selectedClipId = null
        }
    }

    private fun refreshFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }
}
