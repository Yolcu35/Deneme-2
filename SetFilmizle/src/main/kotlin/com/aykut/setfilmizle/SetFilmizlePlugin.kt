package com.aykut.setfilmizle

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class SetFilmizlePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SetFilmizleProvider())
    }
}
