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
import org.jsoup.nodes.Element

class SetFilmizleProvider : MainAPI() {

    override var mainUrl: String =
        "https://www.setfilmizle.ltd"

    override var name: String =
        "SetFilmizle"

    override var lang: String =
        "tr"

    override val hasMainPage: Boolean =
        false

    override val hasQuickSearch: Boolean =
        false

    override val supportedTypes: Set<TvType> =
        setOf(
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

        val homeHtml = home.text

        val nonce = findNonce(homeHtml)
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

        val document = Jsoup.parse(html)

        return document
            .select("div.items article")
            .mapNotNull { article ->
                parseSearchResult(article)
            }
            .distinctBy { result ->
                result.url
            }
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
            val match = Regex(pattern)
                .find(html)

            if (match != null) {
                return match.groupValues[1]
            }
        }

        return null
    }

    private fun parseSearchResult(
        element: Element
    ): SearchResponse? {

        val link = element
            .selectFirst("a[href]")
            ?: return null

        val url = fixUrlNull(
            link.attr("href")
        ) ?: return null

        val title = element
            .selectFirst("h2")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: link.text()
                .trim()
                .takeIf { it.isNotBlank() }
            ?: return null

        val poster = getImageUrl(element)

        val isSeries = isSeriesUrl(url)

        return if (isSeries) {

            newTvSeriesSearchResponse(
                name = title,
                url = url,
                type = TvType.TvSeries
            ) {
                posterUrl = poster
            }

        } else {

            newMovieSearchResponse(
                name = title,
                url = url,
                type = TvType.Movie
            ) {
                posterUrl = poster
            }
        }
    }

    private fun getImageUrl(
        element: Element
    ): String? {

        val image = element
            .selectFirst("img")
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
                return fixUrlNull(value)
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

        val document = response.document

        val title = document
            .selectFirst(
                "h1.entry-title, h1.post-title, h1"
            )
            ?.text()
            ?.trim()
            ?.removeSuffix(" izle")
            ?.trim()
            ?: return null

        val poster = document
            .selectFirst(
                "div.poster img, meta[property='og:image']"
            )
            ?.let { element ->

                if (element.tagName() == "meta") {
                    element.attr("content")
                } else {
                    element.attr("src")
                        .ifBlank {
                            element.attr("data-src")
                        }
                }

            }
            ?.trim()
            ?.let { image ->
                fixUrlNull(image)
            }

        val description = document
            .selectFirst(
                "div.wp-content p, .description, .plot"
            )
            ?.text()
            ?.trim()

        val year = document
            .selectFirst(
                "div.extra span.C a, a[href*='/yil/']"
            )
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        val tags = document
            .select(
                "div.sgeneros a, .genres a, .genre a"
            )
            .map {
                it.text().trim()
            }
            .filter {
                it.isNotBlank()
            }
            .distinct()

        return if (isSeriesUrl(url)) {

            loadSeries(
                url = url,
                title = title,
                poster = poster,
                description = description,
                year = year,
                tags = tags,
                document = document
            )

        } else {

            newMovieLoadResponse(
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
    }

    private fun loadSeries(
        url: String,
        title: String,
        poster: String?,
        description: String?,
        year: Int?,
        tags: List<String>,
        document: org.jsoup.nodes.Document
    ): LoadResponse {

        val episodes = document
            .select(
                "div#episodes ul.episodios li"
            )
            .mapNotNull { element ->

                val link = element
                    .selectFirst(
                        "h4.episodiotitle a[href]"
                    )
                    ?: element.selectFirst(
                        "a[href]"
                    )
                    ?: return@mapNotNull null

                val episodeUrl = fixUrlNull(
                    link.attr("href")
                ) ?: return@mapNotNull null

                val episodeText = link
                    .text()
                    .trim()

                val season = Regex(
                    """(?i)(\d+)\.\s*Sezon"""
                )
                    .find(episodeText)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: 1

                val episode = Regex(
                    """(?i)Bölüm\s*(\d+)"""
                )
                    .find(episodeText)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: 1

                newEpisode(
                    data = episodeUrl
                ) {
                    name = episodeText
                    this.season = season
                    this.episode = episode
                }
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
