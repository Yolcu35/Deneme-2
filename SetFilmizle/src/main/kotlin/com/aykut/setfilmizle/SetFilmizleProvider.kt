package com.aykut.setfilmizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.Jsoup
import java.net.URLEncoder

class SetFilmizleProvider : MainAPI() {

    override var name = "SetFilmizle"

    override var mainUrl = "https://www.setfilmizle.ltd"

    override val lang = "tr"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val hasMainPage = false

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery = URLEncoder.encode(
            query,
            "UTF-8"
        )

        val searchUrl =
            "$mainUrl/?s=$encodedQuery"

        val response = app.get(searchUrl)

        val document = response.document

        return document
            .select("article, .post, .film, .movie, .item")
            .mapNotNull { element ->

                val link = element
                    .selectFirst("a[href]")
                    ?.attr("href")
                    ?: return@mapNotNull null

                val title = element
                    .selectFirst(
                        "h1, h2, h3, h4, .title, .entry-title"
                    )
                    ?.text()
                    ?.trim()
                    ?: element
                        .selectFirst("a[href]")
                        ?.text()
                        ?.trim()
                        ?: return@mapNotNull null

                if (title.isBlank()) {
                    return@mapNotNull null
                }

                val poster = element
                    .selectFirst("img")
                    ?.let { img ->
                        img.attr("data-src")
                            .ifBlank {
                                img.attr("src")
                            }
                    }
                    ?.takeIf { it.isNotBlank() }

                val isSeries =
                    link.contains(
                        "/dizi/",
                        ignoreCase = true
                    ) ||
                    link.contains(
                        "/series/",
                        ignoreCase = true
                    ) ||
                    title.contains(
                        "dizi",
                        ignoreCase = true
                    )

                if (isSeries) {
                    newTvSeriesSearchResponse(
                        name = title,
                        url = link
                    ) {
                        posterUrl = poster
                    }
                } else {
                    newMovieSearchResponse(
                        name = title,
                        url = link
                    ) {
                        posterUrl = poster
                    }
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(url).document

        val title =
            document
                .selectFirst(
                    "h1.entry-title, h1.title, h1"
                )
                ?.text()
                ?.trim()
                ?: return null

        val poster =
            document
                .selectFirst(
                    "meta[property=og:image]"
                )
                ?.attr("content")
                ?.takeIf { it.isNotBlank() }

        val description =
            document
                .selectFirst(
                    "meta[property=og:description]"
                )
                ?.attr("content")
                ?.trim()

        val isSeries =
            url.contains(
                "/dizi/",
                ignoreCase = true
            ) ||
            url.contains(
                "/series/",
                ignoreCase = true
            )

        if (isSeries) {

            return newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = emptyList()
            ) {
                posterUrl = poster
                plot = description
            }

        } else {

            return newMovieLoadResponse(
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (
            SubtitleFile
        ) -> Unit,
        callback: (
            ExtractorLink
        ) -> Unit
    ): Boolean {

        /*
         * Stream çözümleme burada özellikle
         * uygulanmıyor.
         *
         * Yetkili/açık bir video kaynağı
         * kullanıyorsan bu bölüm ayrıca
         * uygulanabilir.
         */

        return false
    }
}
