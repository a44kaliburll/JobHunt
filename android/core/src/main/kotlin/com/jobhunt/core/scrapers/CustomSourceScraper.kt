package com.jobhunt.core.scrapers

import com.jobhunt.core.CustomSource
import com.jobhunt.core.Fetcher
import com.jobhunt.core.JobPosting
import com.jobhunt.core.SearchQuery
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * A job source defined entirely by the user, no code required. Three kinds:
 *
 * `rss` — any RSS/Atom feed:
 * ```json
 * {"url": "https://example.com/jobs.rss?q={query}"}
 * ```
 *
 * `json` — a JSON API, with dot-paths into the response:
 * ```json
 * {"url": "https://example.com/api?q={query}", "list_path": "results",
 *  "fields": {"id": "id", "title": "name", "company": "company.name",
 *             "location": "location", "url": "links.apply", "posted": "published"}}
 * ```
 *
 * `html` — a results page scraped with CSS selectors:
 * ```json
 * {"url": "https://example.com/jobs?q={query}",
 *  "selectors": {"item": "li.job", "title": ".job-title", "company": ".employer",
 *                "location": ".place", "url": "a", "posted": "time"}}
 * ```
 *
 * `{query}` is replaced with the URL-encoded search keywords and `{location}`
 * with the URL-encoded location. A URL without `{query}` is fetched once and
 * filtered against the keywords locally.
 */
class CustomSourceScraper(
    private val source: CustomSource,
    private val fetcher: Fetcher,
) : Scraper {

    override val name: String = source.name
    override val keyPrefix: String = "u${source.id}"

    private val config: JsonObject =
        runCatching { json.parseToJsonElement(source.configJson) as? JsonObject }
            .getOrNull() ?: JsonObject(emptyMap())

    private val urlTemplate: String = config.stringAt("url")

    override fun search(query: SearchQuery): List<JobPosting> {
        if (urlTemplate.isBlank()) return emptyList()
        val url = urlTemplate
            .replace("{query}", urlEncode(query.keywords))
            .replace("{location}", urlEncode(query.location))
        val body = fetcher.get(url)
        val postings = when (source.kind) {
            "rss" -> parseRss(body)
            "json" -> parseJson(body)
            "html" -> parseHtml(body, url)
            else -> error("Unknown custom source kind: ${source.kind}")
        }
        return applyLocalFilter(postings, query)
    }

    /** Sources whose URL can't carry the query are filtered client-side. */
    private fun applyLocalFilter(
        postings: List<JobPosting>,
        query: SearchQuery,
    ): List<JobPosting> {
        if (urlTemplate.contains("{query}")) return postings
        val keywords = query.keywords.lowercase().split(' ').filter { it.length > 2 }
        if (keywords.isEmpty()) return postings
        return postings.filter { posting ->
            val haystack = "${posting.title} ${posting.description}".lowercase()
            keywords.any { it in haystack }
        }
    }

    internal fun parseRss(body: String): List<JobPosting> {
        val document = Jsoup.parse(body, "", Parser.xmlParser())
        val items = document.select("item, entry")
        return items.mapNotNull { item ->
            val title = item.selectFirst("title")?.text()?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val link = item.selectFirst("link")?.let { linkEl ->
                linkEl.text().trim().ifBlank { linkEl.attr("href").trim() }
            }.orEmpty()
            val guid = item.selectFirst("guid, id")?.text()?.trim()
                ?.ifBlank { null } ?: link.ifBlank { title }
            val posted = item.selectFirst("pubDate, published, updated")?.text().orEmpty()
            val description = item.selectFirst("description, summary, content")
                ?.text()?.take(4000).orEmpty()
            JobPosting(
                key = stableKey(guid),
                title = title,
                url = link,
                source = name,
                posted = normalizeDate(posted),
                description = description,
            )
        }
    }

    internal fun parseJson(body: String): List<JobPosting> {
        val root = json.parseToJsonElement(body)
        val listPath = config.stringAt("list_path")
        val items = (if (listPath.isNotEmpty()) root.atPath(listPath) else root) as? JsonArray
            ?: return emptyList()
        val fields = config["fields"] as? JsonObject ?: JsonObject(emptyMap())
        fun field(name: String, fallback: String) = fields.stringAt(name).ifBlank { fallback }

        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val title = item.stringAt(field("title", "title"))
            if (title.isEmpty()) return@mapNotNull null
            val url = item.stringAt(field("url", "url"))
            val externalId = item.stringAt(field("id", "id"))
            JobPosting(
                key = if (externalId.isNotEmpty()) "$keyPrefix:$externalId"
                else stableKey(url.ifBlank { title }),
                title = title,
                company = item.stringAt(field("company", "company")),
                location = item.stringAt(field("location", "location")),
                url = url,
                source = name,
                posted = normalizeDate(item.stringAt(field("posted", "posted"))),
                description = item.stringAt(field("description", "description")).take(4000),
            )
        }
    }

    internal fun parseHtml(body: String, baseUrl: String): List<JobPosting> {
        val selectors = config["selectors"] as? JsonObject
            ?: error("html source config requires a 'selectors' object")
        val itemSelector = selectors.stringAt("item")
        require(itemSelector.isNotBlank()) { "html source config requires selectors.item" }

        val document = Jsoup.parse(body, baseUrl)
        return document.select(itemSelector).mapNotNull { card ->
            val title = card.textOf(selectors.stringAt("title"))
            if (title.isEmpty()) return@mapNotNull null
            val urlSelector = selectors.stringAt("url")
            val linkEl = when {
                urlSelector.isNotBlank() -> card.selectFirst(urlSelector)
                card.tagName() == "a" -> card
                else -> null
            }
            JobPosting(
                key = stableKey(linkEl?.absUrl("href").orEmpty().ifBlank { title }),
                title = title,
                company = card.textOf(selectors.stringAt("company")),
                location = card.textOf(selectors.stringAt("location")),
                url = linkEl?.absUrl("href").orEmpty(),
                source = name,
                posted = normalizeDate(card.textOf(selectors.stringAt("posted"))),
            )
        }
    }

    private fun Element.textOf(selector: String): String =
        if (selector.isBlank()) "" else selectFirst(selector)?.text()?.trim().orEmpty()

    private fun stableKey(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(parts.joinToString("|").toByteArray())
        return "$keyPrefix:" + digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
