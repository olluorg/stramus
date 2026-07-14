package stramus.core.db

import io.github.kormium.database.SuspendDatabase
import io.github.kormium.sqlite.js.createSqliteJsDatabase

/**
 * Opens the stramus database the app runs on: SQLite compiled to WASM, persisted in the browser's
 * IndexedDB under [name], with the schema brought up to date and a first install seeded.
 *
 * The whole of that work is in the common [openStramusStore] — this is only where the engine comes
 * from, and it is the one piece of the store that a browser is needed for.
 */
suspend fun openStramusStore(name: String = "stramus", seed: StoreSeed = StoreSeed.Default): StramusStore {
    val db: SuspendDatabase<StramusDb> = createSqliteJsDatabase(name)
    return openStramusStore(db, seed)
}
