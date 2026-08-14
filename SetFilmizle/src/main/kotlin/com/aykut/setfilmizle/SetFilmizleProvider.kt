package com.aykut.setfilmizle

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import java.net.URLEncoder

class SetFilmizleProvider : MainAPI() {

    override var name: String = "SetFilmizle"

    override var mainUrl: String = "https://www.setfilmizle.ltd"

    override var lang: String = "tr"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val hasMainPage: Boolean = false

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery: String =
            URLEncoder.encode(query, "UTF-8")

        val searchUrl: String =
            "$mainUrl/?s=$encodedQuery"

        val document =
            app.get(searchUrl).document

        val results =
            ArrayList<SearchResponse>()

        document
            .select("article, .post, .film, .movie, .item")
            .forEach { element ->

                val linkElement =
                    element.selectFirst("a[href]")

                if (linkElement == null) {
                    return@forEach
                }

                val link: String =
                    linkElement.attr("href")

                if (link.isBlank()) {
                    return@forEach
                }

                val titleElement =
                    element.selectFirst(
                        "h1, h2, h3, h4, .title, .entry-title"
                    )

                val title: String =
                    titleElement?.text()?.trim()
                        ?: linkElement.text().trim()

                if (title.isBlank()) {
                    return@forEach
                }

                val imageElement =
                    element.selectFirst("img")

                val poster: String? =
                    if (imageElement != null) {
                        val dataSrc =
                            imageElement.attr("data-src")

                        if (dataSrc.isNotBlank()) {
                            dataSrc
                        } else {
                            val src =
                                imageElement.attr("src")

                            src.ifBlank {
                                null
                            }
                        }
                    } else {
                        null
                    }

                val isSeries: Boolean =
                    link.contains(
                        "/dizi/",
                        ignoreCase = true
                    ) ||
                    link.contains(
                        "/series/",
                        ignoreCase = true
                    )

                if (isSeries) {

                    results.add(
                        newTvSeriesSearchResponse(
                            name = title,
                            url = link
                        ) {
                            posterUrl = poster
                        }
                    )

                } else {

                    results.add(
                        newMovieSearchResponse(
                            name = title,
                            url = link
                        ) {
                            posterUrl = poster
                        }
                    )
                }
            }

        return results.distinctBy {
            it.url
        }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document =
            app.get(url).document

        val titleElement =
            document.selectFirst(
                "h1.entry-title, h1.title, h1"
            )

        val title: String =
            titleElement?.text()?.trim()
                ?: return null

        val imageElement =
            document.selectFirst(
                "meta[property=og:image]"
            )

        val poster: String? =
            imageElement
                ?.attr("content")
                ?.takeIf {
                    it.isNotBlank()
                }

        val descriptionElement =
            document.selectFirst(
                "meta[property=og:description]"
            )

        val description: String? =
            descriptionElement
                ?.attr("content")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        val isSeries: Boolean =
            url.contains(
                "/dizi/",
                ignoreCase = true
            ) ||
            url.contains(
                "/series/",
                ignoreCase = true
            )

        return if (isSeries) {

            newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = emptyList()
            ) {
                posterUrl = poster
                plot = description
            }

        } else {

            newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = url
            ) {
                posterUrl = poster
                plot = description
            }
        }
    }
}
