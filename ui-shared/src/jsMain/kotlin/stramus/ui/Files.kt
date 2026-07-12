package stramus.ui

/** The browser's global FileReader — enough of it to read a picked file (or a fetched icon) as a `data:` URI. */
internal external class FileReader {
    var onload: (() -> Unit)?
    var onerror: (() -> Unit)?
    val result: String?
    fun readAsDataURL(blob: dynamic)
}

/**
 * Read the first file selected in an `<input type="file">` as a base64 `data:` URI and hand its
 * name, MIME type, and data-URI to [onDone]. [input] is the change event's target. No-op if nothing
 * was picked.
 */
internal fun readPickedFile(input: dynamic, onDone: (name: String, mime: String, dataUri: String) -> Unit) {
    val files = input.files
    if (files == null || files.length == 0) return
    val file = files[0]
    val reader = FileReader()
    reader.onload = {
        val data = reader.result
        if (data != null) {
            val mime = (file.type as? String).takeUnless { it.isNullOrBlank() } ?: "application/octet-stream"
            onDone(file.name as String, mime, data)
        }
    }
    reader.readAsDataURL(file)
}
