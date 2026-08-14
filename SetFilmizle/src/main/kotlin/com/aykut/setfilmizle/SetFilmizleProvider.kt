package com.aykut.setfilmizle

import com.lagradost.cloudstream3.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class SetFilmizleProvider : MainAPI() {

    override var mainUrl = "https://www.setfilmizle.ltd"

    override var name = "SetFilmizle"

    override var lang = "tr"

    override val hasMainPage = false

    override val hasQuickSearch = false

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val mainPage =
            app.get(mainUrl).document

        val html =
            mainPage.html()

        val nonce =
            Regex(
                """nonce\s*:\s*['"]([^'"]+)['"]"""
            )
                .find(html)
                ?.groupValues
                ?.get(1)
                ?: return emptyList()

        val response =
            app.post(
                url = "$mainUrl/wp-admin/admin-ajax.php",
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest"
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

        val json =
            try {
                JSONObject(response.text)
            } catch (e: Exception) {
                return emptyList()
            }

        val resultHtml =
            json.optString("html")

        if (resultHtml.isBlank()) {
            return emptyList()
        }

        val document =
            Jsoup.parse(resultHtml)

        return document
            .select("div.items article")
            .mapNotNull {
                it.toSearchResult()
            }
            .distinctBy {
                it.url
            }
    }

    private fun Element.toSearchResult():
        SearchResponse? {

        val linkElement =
            selectFirst("a")
                ?: return null

        val href =
            fixUrlNull(
                linkElement.attr("href")
            )
                ?: return null

        val title =
            selectFirst("h2")
                ?.text()
                ?.trim()
                ?: linkElement
                    .text()
                    .trim()

        if (title.isBlank()) {
            return null
        }

        val poster =
            fixUrlNull(
                selectFirst("img")
                    ?.attr("data-src")
            )

        val isSeries =
            href.contains(
                "/dizi/",
                ignoreCase = true
            )

        return if (isSeries) {

            newTvSeriesSearchResponse(
                name = title,
                url = href,
                type = TvType.TvSeries
            ) {
                posterUrl = poster
            }

        } else {

            newMovieSearchResponse(
                name = title,
                url = href,
                type = TvType.Movie
            ) {
                posterUrl = poster
            }
        }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document =
            app.get(url).document

        val title =
            document
                .selectFirst("h1")
                ?.text()
                ?.substringBefore(" izle")
                ?.trim()
                ?: return null

        val poster =
            fixUrlNull(
                document
                    .selectFirst(
                        "div.poster img"
                    )
                    ?.attr("src")
            )

        val description =
            document
                .selectFirst(
                    "div.wp-content p"
                )
                ?.text()
                ?.trim()

        val year =
            document
                .selectFirst(
                    "div.extra span.C a"
                )
                ?.text()
                ?.trim()
                ?.toIntOrNull()

        val tags =
            document
                .select("div.sgeneros a")
                .map {
                    it.text().trim()
                }
                .filter {
                    it.isNotBlank()
                }
                .distinct()

        val recommendations =
            document
                .select(
                    "div.srelacionados article"
                )
                .mapNotNull {
                    it.toRecommendationResult()
                }

        val actors =
            document
                .select("span.valor a")
                .map {
                    Actor(it.text().trim())
                }

        val trailer =
            Regex(
                """embed/(.*?)(?:\?rel|["'])"""
            )
                .find(document.html())
                ?.groupValues
                ?.getOrNull(1)
                ?.let {
                    "https://www.youtube.com/embed/$it"
                }

        val isSeries =
            url.contains(
                "/dizi/",
                ignoreCase = true
            )

        if (isSeries) {

            val episodes =
                document
                    .select(
                        "div#episodes ul.episodios li"
                    )
                    .mapNotNull {

                        val episodeLink =
                            it.selectFirst(
                                "h4.episodiotitle a"
                            )
                                ?: return@mapNotNull null

                        val episodeUrl =
                            fixUrlNull(
                                episodeLink.attr("href")
                            )
                                ?: return@mapNotNull null

                        val text =
                            episodeLink.text().trim()

                        val season =
                            Regex(
                                """(?i)(\d+)\.\s*Sezon"""
                            )
                                .find(text)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.toIntOrNull()
                                ?: 1

                        val episode =
                            Regex(
                                """(?i)Bölüm\s+(\d+)"""
                            )
                                .find(text)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.toIntOrNull()
                                ?: 1

                        newEpisode(
                            episodeUrl
                        ) {
                            this.name = text
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
                this.recommendations = recommendations

                addActors(actors)
                addTrailer(trailer)
            }
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
            this.recommendations = recommendations

            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun Element.toRecommendationResult():
        SearchResponse? {

        val linkElement =
            selectFirst("a")
                ?: return null

        val href =
            fixUrlNull(
                linkElement.attr("href")
            )
                ?: return null

        val image =
            linkElement.selectFirst("img")

        val title =
            image
                ?.attr("alt")
                ?.trim()
                ?: linkElement
                    .text()
                    .trim()

        if (title.isBlank()) {
            return null
        }

        val poster =
            fixUrlNull(
                image?.attr("data-src")
            )

        return if (
            href.contains(
                "/dizi/",
                ignoreCase = true
            )
        ) {

            newTvSeriesSearchResponse(
                name = title,
                url = href,
                type = TvType.TvSeries
            ) {
                posterUrl = poster
            }

        } else {

            newMovieSearchResponse(
                name = title,
                url = href,
                type = TvType.Movie
            ) {
                posterUrl = poster
            }
        }
    }
}
