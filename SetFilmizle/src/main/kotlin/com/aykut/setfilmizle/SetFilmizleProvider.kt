package com.aykut.setfilmizle

import com.lagradost.cloudstream3.*
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

    // ---------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery =
            URLEncoder.encode(query, "UTF-8")

        val searchUrl =
            "$mainUrl/?s=$encodedQuery"

        val document =
            app.get(searchUrl).document

        val results =
            ArrayList<SearchResponse>()

        /*
         * SetFilmizle'deki sonuç kartlarını yakalamaya
         * çalışıyoruz.
         */
        val cards = document.select(
            "article, .post-item, .item, .film, .movie-item, " +
            ".film-item, .film-card, .movie, .item-film"
        )

        cards.forEach { card ->

            val linkElement =
                card.selectFirst("a[href]")

            if (linkElement == null) {
                return@forEach
            }

            val href =
                linkElement.attr("href").trim()

            if (href.isBlank()) {
                return@forEach
            }

            val titleElement =
                card.selectFirst(
                    "h1, h2, h3, h4, h5, " +
                    ".title, .film-title, .movie-title, " +
                    ".post-title, .entry-title"
                )

            val title =
                titleElement
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: linkElement
                        .text()
                        .trim()

            if (title.isBlank()) {
                return@forEach
            }

            val image =
                card.selectFirst("img")

            val poster: String? =
                if (image != null) {

                    val dataSrc =
                        image.attr("data-src")

                    if (dataSrc.isNotBlank()) {
                        dataSrc
                    } else {

                        val lazySrc =
                            image.attr("data-lazy-src")

                        if (lazySrc.isNotBlank()) {
                            lazySrc
                        } else {

                            val src =
                                image.attr("src")

                            src.ifBlank {
                                null
                            }
                        }
                    }

                } else {
                    null
                }

            val fullUrl =
                when {
                    href.startsWith("http://") ->
                        href

                    href.startsWith("https://") ->
                        href

                    href.startsWith("/") ->
                        mainUrl + href

                    else ->
                        "$mainUrl/$href"
                }

            val text =
                card.text().lowercase()

            val isSeries =
                text.contains("dizi") ||
                text.contains("sezon") ||
                text.contains("bölüm") ||
                fullUrl.contains(
                    "/dizi/",
                    ignoreCase = true
                ) ||
                fullUrl.contains(
                    "/series/",
                    ignoreCase = true
                )

            if (isSeries) {

                results.add(
                    newTvSeriesSearchResponse(
                        name = title,
                        url = fullUrl
                    ) {
                        posterUrl = poster
                    }
                )

            } else {

                results.add(
                    newMovieSearchResponse(
                        name = title,
                        url = fullUrl
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

    // ---------------------------------------------------------
    // LOAD
    // ---------------------------------------------------------

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document =
            app.get(url).document

        val title =
            document
                .selectFirst(
                    "h1.entry-title, " +
                    "h1.post-title, " +
                    "h1.title, " +
                    "h1"
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
                ?.takeIf {
                    it.isNotBlank()
                }

        val description =
            document
                .selectFirst(
                    "meta[property=og:description]"
                )
                ?.attr("content")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        val year =
            document
                .selectFirst(
                    ".year, .release-year, " +
                    ".film-year, .movie-year"
                )
                ?.text()
                ?.trim()
                ?.toIntOrNull()

        val genres =
            document
                .select(
                    ".genre a, .genres a, " +
                    ".category a, .categories a"
                )
                .map {
                    it.text().trim()
                }
                .filter {
                    it.isNotBlank()
                }
                .distinct()

        val isSeries =
            url.contains(
                "/dizi/",
                ignoreCase = true
            ) ||
            url.contains(
                "/series/",
                ignoreCase = true
            ) ||
            document
                .text()
                .contains(
                    "sezon",
                    ignoreCase = true
                )

        if (!isSeries) {

            return newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = url
            ) {

                posterUrl = poster

                plot = description

                this.year = year

                this.tags = genres
            }
        }

        // -----------------------------------------------------
        // TV SERIES
        // -----------------------------------------------------

        val episodes =
            ArrayList<ExtractorLink>()

        /*
         * Bölümleri bulmak için sayfadaki bağlantıları
         * tarıyoruz.
         *
         * Burada video/stream linklerini çözmüyoruz.
         */
        val episodeLinks =
            document.select(
                "a[href]"
            )

        val parsedEpisodes =
            ArrayList<Episode>()

        episodeLinks.forEach { element ->

            val href =
                element.attr("href").trim()

            val text =
                element.text().trim()

            if (
                href.isBlank() ||
                text.isBlank()
            ) {
                return@forEach
            }

            val combined =
                "$text $href"

            val seasonMatch =
                Regex(
                    "(?i)(?:sezon|season)[\\s._-]*(\\d+)"
                ).find(combined)

            val episodeMatch =
                Regex(
                    "(?i)(?:bölüm|bolum|episode|ep)[\\s._-]*(\\d+)"
                ).find(combined)

            if (
                seasonMatch != null &&
                episodeMatch != null
            ) {

                val season =
                    seasonMatch
                        .groupValues[1]
                        .toIntOrNull()
                        ?: return@forEach

                val episode =
                    episodeMatch
                        .groupValues[1]
                        .toIntOrNull()
                        ?: return@forEach

                val episodeUrl =
                    when {

                        href.startsWith("http://") ->
                            href

                        href.startsWith("https://") ->
                            href

                        href.startsWith("/") ->
                            mainUrl + href

                        else ->
                            "$mainUrl/$href"
                    }

                parsedEpisodes.add(
                    newEpisode(
                        episodeUrl
                    ) {

                        name = text

                        season = season

                        episode = episode
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.TvSeries,
            episodes = parsedEpisodes
        ) {

            posterUrl = poster

            plot = description

            this.year = year

            this.tags = genres
        }
    }
}
