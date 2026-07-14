package stramus.ui

import web.data.DataTransfer
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The largest file the app will take in. Its bytes are held as a `data:` URI — in memory while it is
 * read, in the database afterwards — so this is not a policy but a limit: a file of a few hundred
 * megabytes would be read into a string a third larger again, and the tab would die doing it.
 */
internal const val MAX_FILE_MB = 25
private const val MAX_FILE_BYTES = MAX_FILE_MB * 1024 * 1024L

/** The browser's global FileReader — enough of it to read a picked file (or a fetched icon) as a `data:` URI. */
internal external class FileReader {
    var onload: (() -> Unit)?
    var onerror: (() -> Unit)?
    val result: String?
    fun readAsDataURL(blob: dynamic)
    fun readAsText(blob: dynamic, encoding: String = definedExternally)
}

/** A file the browser handed us — dropped on the page, or picked in a dialog — before it has been read. */
internal class PickedFile(private val file: dynamic) {
    val name: String = file.name as? String ?: ""

    /** Empty for a file the OS could not type: taken as raw bytes, which is what it is. */
    val mime: String = (file.type as? String)?.takeUnless { it.isBlank() } ?: "application/octet-stream"

    /** Beyond what the database will hold — named to the user and passed over. See [MAX_FILE_MB]. */
    val tooLarge: Boolean = ((file.size as? Number)?.toDouble() ?: 0.0) > MAX_FILE_BYTES

    /**
     * The file's bytes as a base64 `data:` URI, or null if it could not be read — which is what a
     * dropped *folder* is: the browser hands it over as a file and then fails to open it.
     */
    suspend fun read(): String? = suspendCoroutine { continuation ->
        val reader = FileReader()
        reader.onload = { continuation.resume(reader.result) }
        reader.onerror = { continuation.resume(null) }
        reader.readAsDataURL(file)
    }
}

/**
 * Whether this drag is carrying files from outside the browser. It is what tells a drop from the
 * desktop apart from the app's own drags (a card, a tab, a section), which carry only `text/plain` —
 * and it can be asked during the drag itself, while the files themselves cannot be reached until the
 * drop.
 */
internal fun draggingFiles(dataTransfer: DataTransfer): Boolean {
    val types = dataTransfer.asDynamic().types ?: return false
    val count = types.length as? Int ?: return false
    return (0 until count).any { types[it] == "Files" }
}

/**
 * The files of a drop, in the order they were dropped. Read out of the event synchronously — the
 * browser empties the DataTransfer the moment the handler returns, so the handles have to be taken
 * now even though the bytes are fetched later.
 */
internal fun droppedFiles(dataTransfer: DataTransfer): List<PickedFile> {
    val files = dataTransfer.asDynamic().files ?: return emptyList()
    val count = files.length as? Int ?: return emptyList()
    return (0 until count).map { PickedFile(files[it]) }
}

/**
 * Read the first file selected in an `<input type="file">` as a base64 `data:` URI and hand its
 * name, MIME type, and data-URI to [onDone]. [input] is the change event's target. No-op if nothing
 * was picked.
 */
internal fun readPickedFile(input: dynamic, onDone: (name: String, mime: String, dataUri: String) -> Unit) {
    val files = input.files
    if (files == null || files.length == 0) return
    val handle = files[0]
    val file = PickedFile(handle)
    val reader = FileReader()
    reader.onload = { reader.result?.let { data -> onDone(file.name, file.mime, data) } }
    reader.readAsDataURL(handle)
}

/**
 * Read the first file selected in an `<input type="file">` as UTF-8 text and hand its name and
 * contents to [onDone] — what an import reads, where a saved file is read as bytes. The input is
 * cleared afterwards: a file picked twice in a row (fixed up and picked again) is a second import,
 * but to the browser it is the same value and would raise no change event.
 */
internal fun readPickedText(input: dynamic, onDone: (name: String, text: String) -> Unit) {
    val files = input.files
    if (files == null || files.length == 0) return
    val handle = files[0]
    val name = handle.name as? String ?: ""
    val reader = FileReader()
    reader.onload = { reader.result?.let { text -> onDone(name, text) } }
    reader.readAsText(handle, "utf-8")
    input.value = ""
}
