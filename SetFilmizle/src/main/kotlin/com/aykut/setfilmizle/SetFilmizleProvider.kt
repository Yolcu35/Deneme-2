package com.aykut.setfilmizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.toRatingInt
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

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

        val nonce =
            Regex("""nonce:\s*['"]([^'"]+)['"]""")
                .find(mainPage.html())
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
            JSONObject(response.text)

        val html =
            json.optString("html")

        if (html.isBlank()) {
            return emptyList()
        }

        val document =
            Jsoup.parse(html)

        return document
            .select("div.items article")
            .mapNotNull {
                it.toSearchResult()
            }
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val title =
            selectFirst("h2")
                ?.text()
                ?.trim()
                ?: return null

        val href =
            fixUrlNull(
                selectFirst("a")
                    ?.attr("href")
            )
                ?: return null

        val poster =
            fixUrlNull(
                selectFirst("img")
                    ?.attr("data-src")
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

        var year =
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

        val rating =
            document
                .selectFirst(
                    "span.dt_rating_vgs"
                )
                ?.text()
                ?.trim()
                ?.toRatingInt()

        var duration =
            document
                .selectFirst(
                    "span.runtime"
                )
                ?.text()
                ?.split(" ")
                ?.first()
                ?.trim()
                ?.toIntOrNull()

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
                    Actor(it.text())
                }

        val trailer =
            Regex(
                """embed/(.*)\?rel"""
            )
                .find(document.html())
                ?.groupValues
                ?.get(1)
                ?.let {
                    "https://www.youtube.com/embed/$it"
                }

        // DİZİ
        if (
            url.contains(
                "/dizi/",
                ignoreCase = true
            )
        ) {

            year =
                document
                    .selectFirst(
                        "a[href*='/yil/']"
                    )
                    ?.text()
                    ?.trim()
                    ?.toIntOrNull()

            duration =
                document
                    .selectFirst(
                        "div#info span:containsOwn(Dakika)"
                    )
                    ?.text()
                    ?.split(" ")
                    ?.first()
                    ?.trim()
                    ?.toIntOrNull()

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

                        val episodeName =
                            episodeLink
                                .ownText()
                                .trim()

                        val episodeDetail =
                            episodeLink
                                .ownText()
                                .trim()

                        val season =
                            Regex(
                                """(\d+)\.\s*Sezon"""
                            )
                                .find(
                                    episodeDetail
                                )
                                ?.groupValues
                                ?.get(1)
                                ?.toIntOrNull()

                        val episode =
                            Regex(
                                """Sezon\s+\d+\.\s*Bölüm\s+(\d+)"""
                            )
                                .find(
                                    episodeDetail
                                )
                                ?.groupValues
                                ?.get(1)
                                ?.toIntOrNull()

                        newEpisode(
                            episodeUrl
                        ) {
                            this.name = episodeName
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
                this.rating = rating
                this.duration = duration
                this.recommendations = recommendations

                addActors(actors)
                addTrailer(trailer)
            }
        }

        // FİLM
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
            this.rating = rating
            this.duration = duration
            this.recommendations = recommendations

            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun Element.toRecommendationResult():
        SearchResponse? {

        val title =
            selectFirst("a img")
                ?.attr("alt")
                ?: return null

        val href =
            fixUrlNull(
                selectFirst("a")
                    ?.attr("href")
            )
                ?: return null

        val poster =
            fixUrlNull(
                selectFirst("a img")
                    ?.attr("data-src")
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
