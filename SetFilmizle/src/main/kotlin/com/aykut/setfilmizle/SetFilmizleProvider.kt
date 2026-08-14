package com.aykut.setfilmizle

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class SetFilmizleProvider : MainAPI() {

    override var mainUrl: String = "https://www.setfilmizle.ltd"

    override var name: String = "SetFilmizle"

    override var lang: String = "tr"

    override val hasMainPage: Boolean = false

    override val hasQuickSearch: Boolean = false

    override val supportedTypes: Set<TvType> = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val home = app.get(mainUrl)

        if (!home.isSuccessful) {
            return emptyList()
        }

        val nonce = findNonce(home.text)
            ?: return emptyList()

        val response = app.post(
            url = "$mainUrl/wp-admin/admin-ajax.php",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to mainUrl
            ),
            data = mapOf(
                "action" to "ajax_search",
                "nonce" to nonce,
                "search" to query
            )
        )

        if (!response.isSuccessful) {
            return emptyList()
        }

        val json = try {
            JSONObject(response.text)
        } catch (_: Exception) {
            return emptyList()
        }

        val html = json.optString("html")

        if (html.isBlank()) {
            return emptyList()
        }

        val document: Document = Jsoup.parse(html)

        val results = ArrayList<SearchResponse>()

        for (article in document.select("div.items article")) {

            val link: Element =
                article.selectFirst("a[href]")
                    ?: continue

            val rawUrl: String =
                link.attr("href").trim()

            if (rawUrl.isBlank()) {
                continue
            }

            val url: String =
                absoluteUrl(rawUrl)

            val title: String =
                article.selectFirst("h2")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: link.text().trim()

            if (title.isBlank()) {
                continue
            }

            val poster: String? =
                getImageUrl(article)

            if (isSeriesUrl(url)) {

                results.add(
                    newTvSeriesSearchResponse(
                        name = title,
                        url = url,
                        type = TvType.TvSeries
                    ) {
                        posterUrl = poster
                    }
                )

            } else {

                results.add(
                    newMovieSearchResponse(
                        name = title,
                        url = url,
                        type = TvType.Movie
                    ) {
                        posterUrl = poster
                    }
                )
            }
        }

        return results.distinctBy { it.url }
    }

    private fun findNonce(
        html: String
    ): String? {

        val patterns = listOf(
            """nonce\s*:\s*['"]([^'"]+)['"]""",
            """nonce\s*=\s*['"]([^'"]+)['"]""",
            """"nonce"\s*:\s*"([^"]+)""""
        )

        for (pattern in patterns) {

            val match = Regex(pattern).find(html)

            if (match != null) {
                return match.groupValues[1]
            }
        }

        return null
    }

    private fun absoluteUrl(
        url: String
    ): String {

        val cleanUrl = url.trim()

        return when {

            cleanUrl.startsWith("http://") ->
                cleanUrl

            cleanUrl.startsWith("https://") ->
                cleanUrl

            cleanUrl.startsWith("//") ->
                "https:$cleanUrl"

            cleanUrl.startsWith("/") ->
                mainUrl + cleanUrl

            else ->
                "$mainUrl/$cleanUrl"
        }
    }

    private fun getImageUrl(
        element: Element
    ): String? {

        val image: Element =
            element.selectFirst("img")
                ?: return null

        val attributes = listOf(
            "data-src",
            "data-lazy-src",
            "data-original",
            "src"
        )

        for (attribute in attributes) {

            val value = image
                .attr(attribute)
                .trim()

            if (value.isNotBlank()) {
                return absoluteUrl(value)
            }
        }

        return null
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val response = app.get(url)

        if (!response.isSuccessful) {
            return null
        }

        val document: Document =
            response.document

        val titleElement: Element =
            document.selectFirst(
                "h1.entry-title, h1.post-title, h1"
            )
                ?: return null

        val title: String =
            titleElement
                .text()
                .trim()
                .removeSuffix(" izle")
                .trim()

        if (title.isBlank()) {
            return null
        }

        val posterElement: Element? =
            document.selectFirst(
                "div.poster img"
            )

        val poster: String? =
            if (posterElement != null) {

                val src = posterElement
                    .attr("src")
                    .trim()

                if (src.isNotBlank()) {
                    absoluteUrl(src)
                } else {

                    val dataSrc = posterElement
                        .attr("data-src")
                        .trim()

                    if (dataSrc.isNotBlank()) {
                        absoluteUrl(dataSrc)
                    } else {
                        null
                    }
                }

            } else {

                val ogImage: Element? =
                    document.selectFirst(
                        "meta[property='og:image']"
                    )

                val content =
                    ogImage
                        ?.attr("content")
                        ?.trim()
                        ?: ""

                if (content.isNotBlank()) {
                    absoluteUrl(content)
                } else {
                    null
                }
            }

        val description: String? =
            document.selectFirst(
                "div.wp-content p, .description, .plot"
            )
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        val year: Int? =
            document.selectFirst(
                "div.extra span.C a, a[href*='/yil/']"
            )
                ?.text()
                ?.trim()
                ?.toIntOrNull()

        val tags: List<String> =
            document.select(
                "div.sgeneros a, .genres a, .genre a"
            )
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()

        if (isSeriesUrl(url)) {

            return loadSeries(
                url = url,
                title = title,
                poster = poster,
                description = description,
                year = year,
                tags = tags,
                document = document
            )
        }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.Movie,
            dataUrl = url
        ) {

            posterUrl = poster
            plot = description
            this.year = year
            this.tags = tags
        }
    }

    private suspend fun loadSeries(
        url: String,
        title: String,
        poster: String?,
        description: String?,
        year: Int?,
        tags: List<String>,
        document: Document
    ): LoadResponse {

        val episodes = ArrayList<com.lagradost.cloudstream3.Episode>()

        for (element in document.select(
            "div#episodes ul.episodios li"
        )) {

            val link: Element =
                element.selectFirst(
                    "h4.episodiotitle a[href]"
                )
                    ?: element.selectFirst("a[href]")
                    ?: continue

            val rawEpisodeUrl: String =
                link.attr("href").trim()

            if (rawEpisodeUrl.isBlank()) {
                continue
            }

            val episodeUrl =
                absoluteUrl(rawEpisodeUrl)

            val episodeText =
                link.text().trim()

            if (episodeText.isBlank()) {
                continue
            }

            val season: Int =
                Regex(
                    """(?i)(\d+)\.\s*Sezon"""
                )
                    .find(episodeText)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: 1

            val episode: Int =
                Regex(
                    """(?i)Bölüm\s*(\d+)"""
                )
                    .find(episodeText)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: 1

            episodes.add(
                newEpisode(
                    data = episodeUrl
                ) {
                    name = episodeText
                    this.season = season
                    this.episode = episode
                }
            )
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.TvSeries,
            episodes = episodes
        ) {

            posterUrl = poster
            plot = description
            this.year = year
            this.tags = tags
        }
    }

    private fun isSeriesUrl(
        url: String
    ): Boolean {

        return url.contains(
            "/dizi/",
            ignoreCase = true
        ) ||
        url.contains(
            "/series/",
            ignoreCase = true
        )
    }
}
