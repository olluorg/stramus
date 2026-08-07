package stramus.core.db

import kotlin.js.js

/**
 * There is no real IndexedDB under Node, so `fake-indexeddb` is installed on first use — the same
 * mechanism kidx uses for its own Node test suite (`src/jsTest/kotlin/IndexedDbHost.js.kt`), duplicated
 * here because that one is internal to kidx.
 */
internal fun installIndexedDb() {
    js("if (!globalThis.indexedDB) { require('fake-indexeddb/auto'); }")
}
