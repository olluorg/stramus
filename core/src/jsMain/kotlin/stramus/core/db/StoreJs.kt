package stramus.core.db

import io.github.kidx.openDatabase

/**
 * Opens the stramus database the app runs on: a typed layer directly over the browser's IndexedDB
 * (see `stramusSchema` in `Schema.kt`), with the schema brought up to date and a first install seeded.
 *
 * This is a different IndexedDB database from the one an older build of the app used — that one was a
 * SQLite file (wa-sqlite's VFS) stored *inside* IndexedDB under the name `"stramus"`, which a native
 * IndexedDB layer cannot read at all. `stramusSchema` names its own database (`"stramus-kidx"`), so
 * opening it here never touches the old one: a browser upgrading from that build starts this database
 * empty, seeded exactly as a first install always has been. The old database is simply never opened
 * again — orphaned on disk until the browser evicts it or the user clears site data.
 */
suspend fun openStramusStore(seed: StoreSeed = StoreSeed.Default): StramusStore {
    val db = openDatabase(stramusSchema)
    return openStramusStore(db, seed)
}
