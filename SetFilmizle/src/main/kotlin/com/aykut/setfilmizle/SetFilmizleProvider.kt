package com.aykut.setfilmizle

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType

class SetFilmizleProvider : MainAPI() {

    override var mainUrl = "https://www.setfilmizle.ltd"
    override var name = "SetFilmizle"
    override var lang = "tr"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override suspend fun search(
        query: String
    ): List<SearchResponse> {
        return emptyList()
    }
}
