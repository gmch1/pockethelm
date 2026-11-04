package com.gmch.codexremote.app

import androidx.compose.runtime.Composable
import com.gmch.codexremote.engines.contract.*

internal data class EngineUiEntry(val host: EngineHost, val content: @Composable (AppViewModel) -> Unit)
internal class EngineUiRegistry(entries: List<EngineUiEntry>) {
    val runtime = EngineRegistry(entries.map { it.host })
    private val screens = entries.associateBy { it.host.descriptor.id }
    @Composable fun Content(id: String, app: AppViewModel) = screens.getValue(id).content(app)
}
