package com.aykut.setfilmizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class SetFilmizleProvider : MainAPI() {

    override var mainUrl: String = "https://www.setfilmizle.ltd"
    override var name: String = "SetFilmizle"
    override var lang: String = "tr"

    override val hasMainPage: Boolean = true
    override val hasQuickSearch: Boolean = false

    override val supportedTypes: Set<TvType> = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get("$mainUrl/page/$page/").document

        val homeList = ArrayList<SearchResponse>()

        val elements = document.select(
            "div.items article, .item, div.post"
        )

        for (element in elements) {

            val link = element.selectFirst("a[href]") ?: continue

            val rawUrl = link.attr("href").trim()
            if (rawUrl.isBlank()) continue

            val url = absoluteUrl(rawUrl)

            val title = element
                .selectFirst("h2, .title")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: link.text().trim()

            if (title.isBlank()) continue

            val poster = getImageUrl(element)

            if (isSeriesUrl(url)) {

                homeList.add(
                    newTvSeriesSearchResponse(
                        title,
                        url,
                        TvType.TvSeries
                    ) {
                        posterUrl = poster
                    }
                )

            } else {

                homeList.add(
                    newMovieSearchResponse(
                        title,
                        url,
                        TvType.Movie
                    ) {
                        posterUrl = poster
                    }
                )
            }
        }

        return HomePageResponse(
            listOf(
                HomePageList(
                    "Ana Sayfa",
                    homeList
                )
            )
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val home = app.get(mainUrl)

        if (!home.isSuccessful) return emptyList()

        val nonce = findNonce(home.text) ?: return emptyList()

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

        if (!response.isSuccessful) return emptyList()

        val json = try {
            JSONObject(response.text)
        } catch (_: Exception) {
            return emptyList()
        }

        val html = json.optString("html")

        if (html.isBlank()) return emptyList()

        val document = Jsoup.parse(html)

        val results = ArrayList<SearchResponse>()

        for (article in document.select("div.items article, .item")) {

            val link = article.selectFirst("a[href]")
                ?: continue

            val rawUrl = link.attr("href").trim()

            if (rawUrl.isBlank()) continue

            val url = absoluteUrl(rawUrl)

            val title = article
                .selectFirst("h2, .title")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: link.text().trim()

            if (title.isBlank()) continue

            val poster = getImageUrl(article)

            if (isSeriesUrl(url)) {

                results.add(
                    newTvSeriesSearchResponse(
                        title,
                        url,
                        TvType.TvSeries
                    ) {
                        posterUrl = poster
                    }
                )

            } else {

                results.add(
                    newMovieSearchResponse(
                        title,
                        url,
                        TvType.Movie
                    ) {
                        posterUrl = poster
                    }
                )
            }
        }

        return results.distinctBy { it.url }
    }

    private fun findNonce(html: String): String? {

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

    private fun absoluteUrl(url: String): String {

        val cleanUrl = url.trim()

        return when {

            cleanUrl.startsWith("http://") ||
            cleanUrl.startsWith("https://") -> cleanUrl

            cleanUrl.startsWith("//") ->
                "https:$cleanUrl"

            cleanUrl.startsWith("/") ->
                mainUrl + cleanUrl

            else ->
                "$mainUrl/$cleanUrl"
        }
    }

    private fun getImageUrl(element: Element): String? {

        val image = element.selectFirst("img")
            ?: return null

        val attributes = listOf(
            "data-src",
            "data-lazy-src",
            "data-original",
            "src"
        )

        for (attribute in attributes) {

            val value = image.attr(attribute)
                .trim()

            if (value.isNotBlank()) {
                return absoluteUrl(value)
            }
        }

        return null
    }

    override suspend fun load(url: String): LoadResponse? {

        val response = app.get(url)

        if (!response.isSuccessful) return null

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

        val posterElement = document.selectFirst(
            "div.poster img, .foto img"
        )

        val poster = if (posterElement != null) {

            val src = posterElement.attr("src").trim()
            val dataSrc = posterElement.attr("data-src").trim()

            when {

                src.isNotBlank() ->
                    absoluteUrl(src)

                dataSrc.isNotBlank() ->
                    absoluteUrl(dataSrc)

                else ->
                    null
            }

        } else {

            document
                .selectFirst("meta[property='og:image']")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { absoluteUrl(it) }
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
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        if (isSeriesUrl(url)) {

            val episodes = ArrayList<Episode>()

            for (element in document.select(
                "div#episodes ul.episodios li, .episodios li"
            )) {

                val link =
                    element.selectFirst(
                        "h4.episodiotitle a[href]"
                    )
                        ?: element.selectFirst("a[href]")
                        ?: continue

                val rawEpisodeUrl =
                    link.attr("href").trim()

                if (rawEpisodeUrl.isBlank()) continue

                val episodeUrl =
                    absoluteUrl(rawEpisodeUrl)

                val episodeText =
                    link.text().trim()

                val season =
                    Regex(
                        """(?i)(\d+)\.\s*Sezon"""
                    )
                        .find(episodeText)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: 1

                val episode =
                    Regex(
                        """(?i)Bölüm\s*(\d+)"""
                    )
                        .find(episodeText)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: 1

                episodes.add(
                    newEpisode(episodeUrl) {

                        name = episodeText

                        this.season = season
                        this.episode = episode
                    }
                )
            }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {

                posterUrl = poster
                plot = description
                this.year = year
                this.tags = tags
            }
        }

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {

            posterUrl = poster
            plot = description
            this.year = year
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val response = app.get(
            data,
            headers = mapOf(
                "Referer" to mainUrl
            )
        )

        if (!response.isSuccessful) {
            return false
        }

        val document = response.document

        val urls = LinkedHashSet<String>()

        /*
         * 1. Normal iframe
         */

        document.select(
            "iframe[src], iframe[data-src]"
        ).forEach { iframe ->

            val src =
                iframe.attr("src")
                    .ifBlank {
                        iframe.attr("data-src")
                    }
                    .trim()

            if (src.isNotBlank()) {
                urls.add(absoluteUrl(src))
            }
        }

        /*
         * 2. Link / button tabanlı player
         */

        document.select(
            "a[href], button, [data-src], [data-url], [data-link], [data-embed]"
        ).forEach { element ->

            val candidates = listOf(
                element.attr("href"),
                element.attr("data-src"),
                element.attr("data-url"),
                element.attr("data-link"),
                element.attr("data-embed"),
                element.attr("onclick")
            )

            candidates.forEach { value ->

                extractUrls(value).forEach { found ->

                    if (isPossibleVideoUrl(found)) {
                        urls.add(found)
                    }
                }
            }
        }

        /*
         * 3. Script içerisindeki player URL'leri
         */

        document.select("script").forEach { script ->

            val scriptText = script.data()

            extractUrls(scriptText).forEach { found ->

                if (isPossibleVideoUrl(found)) {
                    urls.add(found)
                }
            }
        }

        /*
         * 4. Kaynakları Extractor sistemine bırak
         */

        for (url in urls) {

            try {

                loadExtractor(
                    url,
                    data,
                    subtitleCallback,
                    callback
                )

            } catch (_: Exception) {
                // Bir extractor patlarsa diğer kaynakları denemeye devam et.
            }
        }

        return urls.isNotEmpty()
    }

    private fun extractUrls(text: String): List<String> {

        if (text.isBlank()) {
            return emptyList()
        }

        val regex = Regex(
            """https?://[^"'\\\s<>]+"""
        )

        return regex
            .findAll(text)
            .map { match ->
                match.value
                    .trim()
                    .trimEnd(
                        '\'',
                        '"',
                        '`',
                        ')',
                        ';',
                        ','
                    )
            }
            .toList()
    }

    private fun isPossibleVideoUrl(url: String): Boolean {

        if (
            !url.startsWith("http://") &&
            !url.startsWith("https://")
        ) {
            return false
        }

        val lower = url.lowercase()

        /*
         * Reklam / analytics / site içi URL'leri
         * extractor'a göndermeyelim.
         */

        val blocked = listOf(
            "google-analytics",
            "googletagmanager",
            "doubleclick",
            "facebook.com/tr",
            "twitter.com",
            "setfilmizle.ltd/wp-",
            "setfilmizle.ltd/category/",
            "setfilmizle.ltd/tag/"
        )

        if (blocked.any { lower.contains(it) }) {
            return false
        }

        /*
         * SetFilmizle ana sayfasını veya içerik
         * sayfasını yanlışlıkla extractor'a gönderme.
         */

        if (
            lower.startsWith(mainUrl.lowercase()) &&
            !lower.contains("embed") &&
            !lower.contains("player") &&
            !lower.contains("video")
        ) {
            return false
        }

        return true
    }

    private fun isSeriesUrl(url: String): Boolean {

        val lower = url.lowercase()

        return lower.contains("/dizi/") ||
                lower.contains("/series/") ||
                lower.contains("/sezon-") ||
                lower.contains("/sezon/") ||
                lower.contains("/bolum-") ||
                lower.contains("/episode/")
    }
}
