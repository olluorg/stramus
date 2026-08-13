package stramus.core.db

import io.github.kidx.deleteDatabase
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Clock

/*
 * A copy of the whole database, taken and put back *underneath* kidx: straight over the browser's own
 * IndexedDB API, opening the database without naming a version and reading whatever object stores are
 * actually on disk.
 *
 * Everywhere else in this app the store is the typed one, and rightly so. This one file is not, and the
 * reason is the single case that matters most: the database that will not open. kidx declares a version
 * and verifies the shape it finds (`Schema.kt`), so a database written by a *newer* build — the app
 * updated, a second browser profile, a developer moving between branches — is refused outright, and
 * IndexedDB has no way to go back down a version. At that moment the typed layer can offer the user
 * nothing at all, while the data itself is sitting there, perfectly readable. This reads it.
 *
 * Because it declares nothing, it also carries everything: a store this build has never heard of is
 * copied out with the rest, and put back if the database it is restored into has one. That is what makes
 * the file a real way out rather than a lossy summary — unlike the CSV and bookmarks exports, which are
 * *links*, and deliberately so (see `Export.kt`).
 */

/** What a backup file says it is. A file without it is somebody else's JSON, and is not read as one. */
const val BACKUP_FORMAT: String = "stramus-backup"

/** The layout of the file below, not the schema of the database — see [restoreStramusBackup]. */
const val BACKUP_VERSION: Int = 1

/** What a restore did: rows written, and the stores this build's database has no home for. */
data class BackupRestore(val rows: Int, val stores: Int, val unknownStores: List<String>)

/**
 * Everything in the database, as JSON: `{"format", "version", "database", "takenAt", "stores": {...}}`,
 * where each store is the array of its rows exactly as IndexedDB holds them.
 *
 * `Date` values (kidx stores every `Instant` as one) survive the round trip as `{"@date": millis}` —
 * plain `JSON.stringify` would flatten them to strings that `JSON.parse` has no reason to turn back.
 *
 * Never throws for want of data: a database that is not there yet backs up as an empty one.
 */
suspend fun exportStramusBackup(dbName: String = stramusSchema.databaseName): String {
    val opened = openRaw(dbName)
    val body = js("({})")
    body["format"] = BACKUP_FORMAT
    body["version"] = BACKUP_VERSION
    body["database"] = dbName
    body["takenAt"] = Clock.System.now().toString()
    body["stores"] = if (opened == null) js("({})") else readEveryStore(opened.db)
    if (opened != null) {
        opened.db.close()
        // Opening a database that was not there makes an empty one. Nobody asked for it: put the disk
        // back the way it was found.
        if (opened.created) runCatching { deleteDatabase(dbName) }
    }
    return stringifyWithDates(body)
}

/**
 * Put a backup back. Rows are written by key, so a row already there is replaced and everything else in
 * the database is left alone: restoring into a database that has since been used adds the backup's rows
 * to it rather than emptying it first.
 *
 * A store the backup holds and this database does not (a file taken from a newer build) is reported in
 * [BackupRestore.unknownStores] and skipped — there is nowhere to put it, and inventing a store outside
 * a migration would leave a database whose shape its own schema does not describe.
 *
 * This writes underneath the open connection the app is holding, which will not hear about it: the
 * caller reloads afterwards.
 */
suspend fun restoreStramusBackup(json: String, dbName: String = stramusSchema.databaseName): BackupRestore {
    val parsed = parseWithDates(json)
    require(parsed != null && parsed["format"] == BACKUP_FORMAT) { "not a $BACKUP_FORMAT file" }
    val stores = parsed["stores"] ?: js("({})")

    val opened = openRaw(dbName) ?: error("IndexedDB is not available in this context")
    try {
        val present = storeNames(opened.db).toSet()
        val wanted = objectKeys(stores).filter { rowCount(stores[it]) > 0 }
        val known = wanted.filter { it in present }
        val unknown = wanted.filterNot { it in present }
        if (known.isEmpty()) return BackupRestore(rows = 0, stores = 0, unknownStores = unknown)

        val written = writeEveryStore(opened.db, known, stores)
        return BackupRestore(rows = written, stores = known.size, unknownStores = unknown)
    } finally {
        opened.db.close()
    }
}

/** Whether a file the user picked is one of ours, asked of the text rather than of the file name. */
fun looksLikeStramusBackup(text: String): Boolean =
    "\"format\"" in text.take(200) && BACKUP_FORMAT in text.take(200)

/**
 * Delete the database outright. The way out of the one failure a backup cannot repair — a schema this
 * build cannot open at all — and so the one place the app offers it; the account, if there is one, puts
 * the data back on the next sync, and the backup above puts it back if there is not.
 */
suspend fun deleteStramusDatabase(dbName: String = stramusSchema.databaseName) {
    deleteDatabase(dbName)
}

// ---- The browser's own IndexedDB, with no schema in the way ----

private class RawDatabase(val db: dynamic, val created: Boolean)

private fun indexedDb(): dynamic = js("(typeof indexedDB !== 'undefined' ? indexedDB : null)")

/**
 * Open [name] without naming a version, which is the whole point: IndexedDB then hands over whatever is
 * on disk, at whatever version it is, instead of refusing a database newer than the caller.
 */
private suspend fun openRaw(name: String): RawDatabase? {
    val idb = indexedDb() ?: return null
    var created = false
    return suspendCoroutine { continuation ->
        val request = idb.open(name)
        request.onupgradeneeded = { created = true }
        request.onsuccess = { continuation.resume(RawDatabase(request.result, created)) }
        request.onerror = {
            continuation.resumeWithException(IllegalStateException("could not open database '$name'"))
        }
    }
}

private fun storeNames(db: dynamic): List<String> {
    val names = db.objectStoreNames
    val count = (names.length as Number).toInt()
    return (0 until count).map { names.item(it) as String }
}

/** Every row of every store, in one transaction — reads spread over several would not see one moment. */
private suspend fun readEveryStore(db: dynamic): dynamic {
    val names = storeNames(db)
    val out = js("({})")
    if (names.isEmpty()) return out
    return suspendCoroutine { continuation ->
        val transaction = db.transaction(names.toTypedArray(), "readonly")
        for (name in names) {
            val request = transaction.objectStore(name).getAll()
            request.onsuccess = { out[name] = request.result }
        }
        transaction.oncomplete = { continuation.resume(out) }
        transaction.onerror = { continuation.resumeWithException(IllegalStateException("backup read failed")) }
        transaction.onabort = { continuation.resumeWithException(IllegalStateException("backup read aborted")) }
    }
}

/** Every row back, in one transaction: a half-restored database is not a state this can end in. */
private suspend fun writeEveryStore(db: dynamic, names: List<String>, stores: dynamic): Int {
    var written = 0
    return suspendCoroutine { continuation ->
        val transaction = db.transaction(names.toTypedArray(), "readwrite")
        for (name in names) {
            val store = transaction.objectStore(name)
            val rows = stores[name]
            val count = rowCount(rows)
            for (i in 0 until count) {
                store.put(rows[i])
                written += 1
            }
        }
        transaction.oncomplete = { continuation.resume(written) }
        transaction.onerror = { continuation.resumeWithException(IllegalStateException("restore failed")) }
        transaction.onabort = { continuation.resumeWithException(IllegalStateException("restore aborted")) }
    }
}

private fun rowCount(rows: dynamic): Int = if (rows == null) 0 else (rows.length as Number).toInt()

private fun objectKeys(value: dynamic): List<String> {
    val keys = js("Object").keys(value)
    val count = (keys.length as Number).toInt()
    return (0 until count).map { keys[it] as String }
}

/**
 * `JSON.stringify`, but a `Date` is written as `{"@date": millis}` rather than the ISO string its own
 * `toJSON` would give — a string comes back a string, and kidx would then find text where a field of
 * type `Instant` declares a `Date` (see kidx's `InstantFieldType`).
 */
private val stringifyWithDates: (dynamic) -> String =
    js("(function (value) { return JSON.stringify(value, function (key, v) { var raw = this[key]; return raw instanceof Date ? { '@date': raw.getTime() } : v; }); })")

/** The other half of [stringifyWithDates]: `{"@date": millis}` back into a real `Date`. */
private val parseWithDates: (String) -> dynamic =
    js("(function (text) { return JSON.parse(text, function (key, v) { return (v && typeof v === 'object' && typeof v['@date'] === 'number') ? new Date(v['@date']) : v; }); })")
