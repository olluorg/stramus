package stramus.core.topics

import java.io.File
import kotlin.test.Test

/**
 * The title grouping run over a real window of tabs, printed for a person to judge.
 *
 * Not an assertion of anything: what "good" is here is whether someone recognises their own afternoon in
 * the groups, which no `assertEquals` can say. It reads a dump of open tabs — one tab a line, title, URL
 * and last-access time separated by tabs — from the path in the `stramus.topics.sample` system property,
 * and does nothing at all without one, so an ordinary test run (and CI) never notices it.
 *
 *     ./gradlew :core:jvmTest --tests '*TitleTopicsSample*' -Dstramus.topics.sample=/tmp/tabs.tsv
 *
 * A property rather than an environment variable: the build runs in a daemon started long before the
 * command, and the daemon's environment is its own — a variable set in front of `./gradlew` never
 * reaches it, which cost one silent, empty run to find out.
 */
class TitleTopicsSample {

    @Test
    fun `group a real window`() {
        val path = System.getProperty("stramus.topics.sample")?.takeIf { it.isNotBlank() }
            ?: run {
                println("[sample] no -Dstramus.topics.sample=<file>, nothing to group")
                return
            }
        val file = File(path)
        if (!file.exists()) {
            println("[sample] no such file: $path")
            return
        }
        val tabs = file.readLines()
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 2 || parts[1].isBlank()) return@mapNotNull null
                parts[0].trim() to parts[1].trim()
            }
            .filter { it.second.startsWith("http") }
            .mapIndexed { i, (title, url) -> TitledTab(i, title, url) }

        fun titlesOf(ids: List<Int>) = tabs.filter { it.id in ids.toSet() }

        // What the app itself would offer, and nothing else: the collections, each under the sidebar group
        // its addresses put it on, then whatever is left. The three-strictness experiment that used to be
        // printed here is gone — a loose pass brings back the coincidences ("Думал", "Моя") that the real
        // thresholds exist to keep out, and a stand that prints something the product never does is a
        // stand that gets judged instead of the product.
        // Exactly what the app offers, in the order it offers it: the topics the words found, and then
        // what was left of the window gathered by site — see `siteTopics`.
        val byWords = titleTopics(tabs)
        val topics = byWords + siteTopics(tabs, byWords.flatMap { it.tabIds }.toSet())
        val placed = topics.flatMap { it.tabIds }.toSet()
        println("[sample] ${tabs.size} tabs → ${topics.size} collections, ${placed.size} placed, ${tabs.size - placed.size} left over")

        topics.groupBy { topic ->
            siteGroupFor(titlesOf(topic.tabIds).map { it.url })
        }.forEach { (group, inGroup) ->
            println()
            println("== ГРУППА ${group?.name ?: "(по умолчанию)"}")
            inGroup.forEach { collection ->
                val hosts = titlesOf(collection.tabIds).map { stramus.core.url.hostOf(it.url) }.distinct()
                val how = if (collection.terms.isEmpty()) " [по сайту]" else ""
                println("   -- ${collection.title} (${collection.tabIds.size})$how — ${hosts.joinToString(", ")}")
                collection.tabIds.forEach { id -> println("      ${tabs.first { it.id == id }.title.take(100)}") }
            }
        }

        // The other way to a group, as far as it goes without a browser: the question the model is asked.
        println()
        println("###### ЧТО СПРОСИМ У МОДЕЛИ")
        println(groupingPrompt(topics.map { it.title }, language = "Russian"))

        println()
        println("== left over")
        tabs.filterNot { it.id in placed }.forEach { println("   ${it.title.take(110)}") }
    }
}
