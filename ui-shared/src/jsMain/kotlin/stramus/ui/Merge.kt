@file:OptIn(ExperimentalUuidApi::class)

package stramus.ui

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffectOnce
import react.useState
import stramus.core.db.MergeResult
import stramus.core.db.StramusStore
import stramus.core.db.applyMerge
import stramus.core.db.undoMerge
import stramus.core.merge.MergePlan
import stramus.core.merge.keeping
import stramus.core.merge.planMerge
import web.cssom.ClassName

/**
 * Joining the things that are the same thing.
 *
 * Signing a browser that already holds collections into an account that also holds them merges by row id,
 * which is all a server can honestly do — so a section built on two machines ends up in the sidebar twice.
 * `stramus.core.merge` works out which rows are one row; this is where the user reads that answer, argues
 * with it, and only then lets it happen.
 *
 * The preview is the safety. Nothing here is a guess the app is confident about — it is a proposal, per
 * section and per collection, with every one of them tickable off. What follows the merge is an undo that
 * lasts as long as this window stands, rather than the thirty seconds an ordinary deletion gets: a merge
 * touches hundreds of rows, and nobody checks hundreds of rows in half a minute.
 */
external interface MergeModalProps : Props {
    var store: StramusStore
    var strings: Strings
    /** Redraw whatever is on screen: the merge has moved collections out from under the sidebar. */
    var onMerged: () -> Unit
    var onClose: () -> Unit
}

/** One scope for the window, in the file, as the account dialog keeps its own — see `Account.kt`. */
private val mergeScope = MainScope()

val MergeModal = FC<MergeModalProps> { props ->
    val t = props.strings
    val scope = mergeScope

    var plan by useState<MergePlan?>(null)
    var scanning by useState(true)
    /** The winner ids whose row the user has unticked. Empty means "all of it", which is the offer made. */
    var excluded by useState(emptySet<Uuid>())
    var busy by useState(false)
    var done by useState<MergeResult?>(null)
    var undone by useState(false)

    useEffectOnce {
        scope.launch {
            val store = props.store
            val collections = store.collections.all()
            val cardSections = collections.flatMap { store.cardSections.byCollection(it.id) }
            val cards = collections.flatMap { store.cards.byCollection(it.id) }
            plan = planMerge(store.sections.all(), collections, cardSections, cards)
            scanning = false
        }
    }

    fun toggle(id: Uuid) {
        excluded = if (id in excluded) excluded - id else excluded + id
    }

    modalShell(props.onClose, "modal triage-modal") {
        div {
            className = ClassName("modal-head")
            h3 { +t.mergeTitle }
            button { className = ClassName("icon del"); onClick = { props.onClose() }; icon("x") }
        }

        val found = plan
        val result = done

        div {
            className = ClassName("triage-body")

            when {
                scanning -> div { className = ClassName("triage-progress"); +t.mergeScanning }

                undone -> p { +t.mergeUndone }

                result != null -> {
                    p { +t.mergeDone(result.sections, result.collections, result.cards) }
                    p { className = ClassName("muted"); +t.mergeUndoHint }
                }

                found == null || found.empty -> {
                    p { +t.mergeNothing }
                    skippedNote(t, found)
                }

                else -> {
                    p { className = ClassName("triage-step-hint"); +t.mergeHint }

                    found.sections.forEach { sectionPlan ->
                        val sectionId = sectionPlan.section.winner
                        val sectionOff = sectionId in excluded
                        div {
                            className = ClassName("triage-branch")
                            div {
                                className = ClassName("triage-branch-head")
                                // A section that fuses nothing is still drawn: the collections *inside* it
                                // can be doubled without it being doubled, which is what a repeated import
                                // leaves behind. Its tick then governs only what is under it.
                                tickbox(sectionPlan.section.title, !sectionOff) { toggle(sectionId) }
                                if (sectionPlan.section.fuses) {
                                    span { className = ClassName("triage-new"); +t.mergeFuseBadge(sectionPlan.section.ids.size) }
                                }
                            }
                            sectionPlan.collections.forEach { collectionPlan ->
                                val collectionId = collectionPlan.collection.winner
                                div {
                                    className = ClassName("triage-group")
                                    div {
                                        className = ClassName("triage-group-head")
                                        tickbox(
                                            collectionPlan.collection.title,
                                            !sectionOff && collectionId !in excluded,
                                            enabled = !sectionOff,
                                        ) { toggle(collectionId) }
                                        if (collectionPlan.collection.fuses) {
                                            span {
                                                className = ClassName("triage-new")
                                                +t.mergeFuseBadge(collectionPlan.collection.ids.size)
                                            }
                                        }
                                        span {
                                            className = ClassName("count")
                                            +t.mergeCollectionCounts(
                                                collectionPlan.duplicateCards,
                                                collectionPlan.cardSections.size,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    skippedNote(t, found)
                    p { className = ClassName("muted"); +t.mergeOneDevice }
                }
            }
        }

        div {
            className = ClassName("modal-actions")
            when {
                result != null && !undone -> {
                    button {
                        className = ClassName("btn")
                        disabled = busy
                        onClick = {
                            busy = true
                            scope.launch {
                                props.store.undoMerge(result.undo)
                                undone = true
                                busy = false
                                props.onMerged()
                            }
                        }
                        +t.mergeUndo
                    }
                    button { className = ClassName("btn primary"); onClick = { props.onClose() }; +t.close }
                }

                found != null && !found.empty && !scanning -> {
                    button { className = ClassName("btn"); onClick = { props.onClose() }; +t.cancel }
                    button {
                        className = ClassName("btn primary")
                        disabled = busy || found.keeping(excluded).empty
                        onClick = {
                            busy = true
                            scope.launch {
                                done = props.store.applyMerge(found.keeping(excluded))
                                busy = false
                                props.onMerged()
                            }
                        }
                        +t.mergeApply
                    }
                }

                else -> button { className = ClassName("btn primary"); onClick = { props.onClose() }; +t.close }
            }
        }
    }
}

/**
 * A row of the preview: a box, and the name of the thing it governs.
 *
 * [enabled] false is a collection under a section the user has already unticked. It is drawn ticked-off
 * rather than hidden, so that unticking a section visibly takes what is under it — the collections were
 * matched *across* the sections being joined, and that match does not survive them staying apart.
 */
private fun react.ChildrenBuilder.tickbox(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit,
) {
    label {
        className = ClassName("triage-group-title")
        input {
            type = CHECKBOX_INPUT
            this.checked = checked
            this.disabled = !enabled
            onChange = { onToggle() }
        }
        span { +" $title" }
    }
}

/** What was left out and why — said plainly, rather than a preview that quietly shows less than it found. */
private fun react.ChildrenBuilder.skippedNote(t: Strings, plan: MergePlan?) {
    val skipped = plan?.skipped ?: return
    if (skipped.lockedSections == 0 && skipped.readOnlyCollections == 0) return
    p {
        className = ClassName("muted")
        +t.mergeSkipped(skipped.lockedSections, skipped.readOnlyCollections)
    }
}
