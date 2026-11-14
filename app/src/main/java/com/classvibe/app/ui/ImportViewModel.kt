package com.classvibe.app.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.classvibe.app.data.AppSettings
import com.classvibe.app.data.ClassVibeRepository
import com.classvibe.app.parser.ClassDraft
import com.classvibe.app.parser.CsvTimetableParser
import com.classvibe.app.parser.ImagePreprocessor
import com.classvibe.app.parser.KnownData
import com.classvibe.app.parser.OcrTimetableParser
import com.classvibe.app.parser.ParseResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ImportStage { PICK, CROP, PARSING, REVIEW }

data class ImportUiState(
    val stage: ImportStage = ImportStage.PICK,
    /** Images waiting for crop/rotate before OCR. */
    val pendingImages: List<Bitmap> = emptyList(),
    val cropIndex: Int = 0,
    val drafts: List<ClassDraft> = emptyList(),
    val warnings: List<String> = emptyList(),
    val source: String = "",
    val rawText: String? = null,
    /** Processed images; each draft's region refers to these. */
    val displayImages: List<Bitmap> = emptyList(),
    val replaceExisting: Boolean = true,
    val showGrid: Boolean = false,
    val error: String? = null
) {
    val invalidCount: Int get() = drafts.count { !it.isValid }
    val checkCount: Int get() = drafts.count { it.isValid && it.flags.isNotEmpty() }
}

class ImportViewModel(app: Application) : AndroidViewModel(app) {
    /** Fire-and-forget coroutine that returns Unit (so actions can be passed as function references). */
    private fun io(
        context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
        block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit
    ) {
        viewModelScope.launch(context, block = block)
    }


    private val repo = ClassVibeRepository(app)
    private val context: Context get() = getApplication()

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var cameraFile: File? = null

    // ---------------- picking ----------------

    fun importUris(uris: List<Uri>) = io {
        if (uris.isEmpty()) return@io
        val images = uris.filter { isImage(it) }
        val csv = uris.firstOrNull { !isImage(it) }
        if (csv != null && images.isEmpty()) { importCsv(csv); return@io }
        _state.update { it.copy(stage = ImportStage.PARSING, error = null) }
        try {
            val bitmaps = withContext(Dispatchers.IO) { images.map { ImagePreprocessor.load(context, it) } }
            _state.update { it.copy(stage = ImportStage.CROP, pendingImages = bitmaps, cropIndex = 0) }
        } catch (e: Exception) {
            _state.update { it.copy(stage = ImportStage.PICK, error = "Couldn't open the image: ${e.message}") }
        }
    }

    fun newCameraUri(): Uri {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "timetable_${System.currentTimeMillis()}.jpg")
        cameraFile = file
        return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }

    fun onCameraResult(success: Boolean) {
        val file = cameraFile ?: return
        if (success && file.exists()) importUris(listOf(Uri.fromFile(file)))
    }

    private fun isImage(uri: Uri): Boolean {
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
        if (mime.startsWith("image/")) return true
        val name = displayName(uri).lowercase()
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp")
    }

    private fun displayName(uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0).orEmpty() else ""
        } ?: uri.lastPathSegment.orEmpty()
    } catch (e: Exception) {
        uri.lastPathSegment.orEmpty()
    }

    private suspend fun importCsv(uri: Uri) {
        try {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("Couldn't open the file.")
            }
            showResult(CsvTimetableParser.parse(text).let { r -> r.copy(drafts = OcrTimetableParser.refine(r.drafts, known())) }, emptyList())
        } catch (e: Exception) {
            _state.update { it.copy(stage = ImportStage.PICK, error = e.message ?: "Couldn't read that file.") }
        }
    }

    /** CSV text pasted from the clipboard (e.g. scanned from a friend's QR code). */
    fun importText(text: String) {
        if (text.isBlank()) { _messages.tryEmit("Clipboard is empty"); return }
        showResult(CsvTimetableParser.parse(text).copy(source = "Pasted text"), emptyList())
    }

    fun loadSample() = showResult(CsvTimetableParser.parse(SAMPLE_CSV).copy(source = "Sample timetable"), emptyList())

    // ---------------- crop step ----------------

    fun replacePending(index: Int, bitmap: Bitmap) = _state.update { s ->
        s.copy(pendingImages = s.pendingImages.mapIndexed { i, b -> if (i == index) bitmap else b })
    }

    fun nextCrop() {
        val s = _state.value
        if (s.cropIndex < s.pendingImages.size - 1) _state.update { it.copy(cropIndex = it.cropIndex + 1) }
        else runOcr()
    }

    fun runOcr() = io {
        val images = _state.value.pendingImages
        if (images.isEmpty()) return@io
        _state.update { it.copy(stage = ImportStage.PARSING) }
        try {
            val output = OcrTimetableParser.parseImages(images, known())
            showResult(output.result, output.displayImages)
            _state.update { it.copy(pendingImages = emptyList()) }
        } catch (e: Exception) {
            _state.update { it.copy(stage = ImportStage.PICK, error = "OCR failed: ${e.message}") }
        }
    }

    private suspend fun known(): KnownData {
        val classes = repo.allClasses()
        val prefs = AppSettings.get(context)
        return KnownData(
            courses = classes.map { it.course }.toSet() + prefs.customSubjects,
            instructors = classes.map { it.instructor }.filter { it.isNotBlank() }.toSet(),
            rooms = classes.map { it.room }.filter { it.isNotBlank() }.toSet(),
            slotTimes = classes.flatMap { listOf(it.startMinutes, it.endMinutes) }.toSet(),
            abbreviations = prefs.abbreviations
        )
    }

    private fun showResult(result: ParseResult, images: List<Bitmap>) {
        _state.update {
            it.copy(
                stage = ImportStage.REVIEW,
                drafts = result.drafts,
                warnings = result.warnings,
                source = result.source,
                rawText = result.rawText,
                displayImages = images,
                error = if (result.drafts.isEmpty()) "No classes found. You can add rows manually below." else null
            )
        }
    }

    // ---------------- review ----------------

    fun updateDraft(draft: ClassDraft) =
        _state.update { s -> s.copy(drafts = s.drafts.map { if (it.key == draft.key) draft.copy(flags = it.flags.filterNot { f -> f.startsWith("Two scans") }) else it }) }

    /** Marks a yellow row as checked (clears its warnings). */
    fun confirmDraft(key: Long) =
        _state.update { s -> s.copy(drafts = s.drafts.map { if (it.key == key) it.copy(flags = emptyList()) else it }) }

    fun removeDraft(key: Long) = _state.update { s -> s.copy(drafts = s.drafts.filterNot { it.key == key }) }

    fun addEmptyDraft() = _state.update { s ->
        s.copy(stage = ImportStage.REVIEW, drafts = s.drafts + ClassDraft(), error = null)
    }

    fun setReplaceExisting(v: Boolean) = _state.update { it.copy(replaceExisting = v) }

    fun toggleGrid() = _state.update { it.copy(showGrid = !it.showGrid) }

    fun discard() { _state.value = ImportUiState() }

    fun save(onSaved: () -> Unit) = io {
        val s = _state.value
        if (s.drafts.isEmpty()) { _messages.emit("Nothing to save yet 🤷"); return@io }
        if (s.invalidCount > 0) { _messages.emit("Fix ${s.invalidCount} red row(s) first ✏️"); return@io }
        // Learn from corrections: OCR text → what the user saved.
        s.drafts.forEach { d ->
            if (d.rawCourse.isNotBlank() && !d.rawCourse.equals(d.course.trim(), ignoreCase = true)) {
                AppSettings.learnAbbreviation(context, d.rawCourse, d.course)
            }
        }
        val entities = s.drafts.mapNotNull { it.toEntityOrNull() }
        repo.saveClasses(entities, replace = s.replaceExisting)
        _state.value = ImportUiState()
        _messages.emit("Saved ${entities.size} classes & armed the reminders 🎉")
        onSaved()
    }

    companion object {
        val SAMPLE_CSV = """
            Day,Start Time,End Time,Course,Class Type,Room,Instructor
            Monday,9:30,10:30,Artificial Intelligence and Deep Learning,Lecture,1205-BL1-SF,Ms. Jyoshmadevi Kusumba
            Monday,10:30,11:30,Algorithms,Lecture,1205-BL1-SF,Dr. Sharmila Banu
            Monday,13:30,15:30,Software Engineering and System Design,Lab,1114-B_BL1-FF,Mr. Ashok Rachapalli
            Tuesday,9:30,10:30,Quantitative Aptitude & Logical Reasoning,Lecture,1205-BL1-SF,Mr. Gotte Ranjit Kumar
            Tuesday,11:30,12:30,Software Engineering and System Design,Lecture,1205-BL1-SF,Mr. Ashok Rachapalli
            Tuesday,13:30,15:30,Artificial Intelligence and Deep Learning,Lab,1114-B_BL1-FF,Ms. Jyoshmadevi Kusumba
            Wednesday,9:30,10:30,Algorithms,Tutorial,1205-BL1-SF,Dr. Sharmila Banu
            Wednesday,10:30,11:30,Critical Thinking and Interpersonal Skills,Lecture,1205-BL1-SF,Mr. Gotte Ranjit Kumar
            Wednesday,13:30,15:30,Algorithms,Lab,1114-B_BL1-FF,Dr. Sharmila Banu
            Thursday,9:30,10:30,Software Engineering and System Design,Tutorial,1205-BL1-SF,Mr. Ashok Rachapalli
            Thursday,11:30,12:30,Artificial Intelligence and Deep Learning,Lecture,1205-BL1-SF,Ms. Jyoshmadevi Kusumba
            Thursday,13:30,14:30,Quantitative Aptitude & Logical Reasoning,Tutorial,1205-BL1-SF,Mr. Gotte Ranjit Kumar
            Friday,9:30,10:30,Critical Thinking and Interpersonal Skills,Tutorial,1205-BL1-SF,Mr. Gotte Ranjit Kumar
            Friday,11:30,12:30,Algorithms,Lecture,1205-BL1-SF,Dr. Sharmila Banu
            Friday,13:30,15:30,Software Engineering and System Design,Lab,1114-B_BL1-FF,Mr. Ashok Rachapalli
        """.trimIndent()
    }
}
