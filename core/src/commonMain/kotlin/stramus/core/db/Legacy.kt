package stramus.core.db

import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table

/*
 * The old shape of the four ordered tables — the one with an integer `position` — read once, by the
 * migration in Store.kt, to work out what order the user's rows are already in before the column is
 * taken away. Nothing else may use these: they describe a schema that stops existing the moment the
 * migration commits.
 *
 * They name the same tables as [Sections] and friends, and carry only the columns the migration reads:
 * a row's id, the group it belongs to, and its place in that group. Kormium selects the columns a table
 * declares, so declaring fewer of them is how this reads a column that the real table no longer has.
 */

internal class LegacySectionRow : Entity() {
    var id by LegacySections.id
    var position by LegacySections.position
}

internal object LegacySections : Table<StramusDb, LegacySectionRow>("sections", ::LegacySectionRow) {
    val id by Column.UUID().primaryKey()
    val position by Column.Int()

    init { id; position }
}

internal class LegacyCollectionRow : Entity() {
    var id by LegacyCollections.id
    var sectionId by LegacyCollections.sectionId
    var position by LegacyCollections.position
}

internal object LegacyCollections : Table<StramusDb, LegacyCollectionRow>("collections", ::LegacyCollectionRow) {
    val id by Column.UUID().primaryKey()
    val sectionId by Column.UUID()
    val position by Column.Int()

    init { id; sectionId; position }
}

internal class LegacyCardSectionRow : Entity() {
    var id by LegacyCardSections.id
    var collectionId by LegacyCardSections.collectionId
    var position by LegacyCardSections.position
}

internal object LegacyCardSections :
    Table<StramusDb, LegacyCardSectionRow>("card_sections", ::LegacyCardSectionRow) {
    val id by Column.UUID().primaryKey()
    val collectionId by Column.UUID()
    val position by Column.Int()

    init { id; collectionId; position }
}

internal class LegacyCardRow : Entity() {
    var id by LegacyCards.id
    var collectionId by LegacyCards.collectionId
    var cardSectionId by LegacyCards.cardSectionId
    var position by LegacyCards.position
}

internal object LegacyCards : Table<StramusDb, LegacyCardRow>("cards", ::LegacyCardRow) {
    val id by Column.UUID().primaryKey()
    val collectionId by Column.UUID()
    val cardSectionId by Column.UUID().nullable()
    val position by Column.Int()

    init { id; collectionId; cardSectionId; position }
}
