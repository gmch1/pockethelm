package com.gmch.codexremote

// Pure state machine for the remote directory picker. The ViewModel owns RPCs, epochs and
// job cancellation; this reducer owns every state transition so they can be unit tested on
// the JVM. The effect tells the ViewModel exactly which network call to issue, if any.
data class ZcodeBrowserState(
    val open: Boolean = false, val loading: Boolean = false, val adding: Boolean = false,
    val requested: String? = null, val current: String = "", val parent: String = "",
    val hasParent: Boolean = false, val entries: List<ZDirectoryEntry> = emptyList(),
    val filter: String = "", val showHidden: Boolean = false, val manual: String = "",
    val readError: String? = null, val addError: String? = null
)

sealed class ZcodeBrowserEvent {
    data class Open(val start: String?) : ZcodeBrowserEvent()
    object Close : ZcodeBrowserEvent()
    object Disconnect : ZcodeBrowserEvent()
    data class Browse(val path: String?) : ZcodeBrowserEvent()
    data class ReadOk(val requested: String?, val listing: ZDirectoryListing) : ZcodeBrowserEvent()
    data class ReadFail(val requested: String?, val message: String) : ZcodeBrowserEvent()
    object AddStart : ZcodeBrowserEvent()
    object AddOk : ZcodeBrowserEvent()
    data class AddFail(val message: String) : ZcodeBrowserEvent()
    data class Filter(val value: String) : ZcodeBrowserEvent()
    data class Manual(val value: String) : ZcodeBrowserEvent()
    data class Hidden(val value: Boolean) : ZcodeBrowserEvent()
}

sealed class ZcodeBrowserEffect {
    object None : ZcodeBrowserEffect()
    data class Read(val requested: String?) : ZcodeBrowserEffect()
    object Add : ZcodeBrowserEffect()
}

object ZcodeBrowser {
    fun reduce(state: ZcodeBrowserState, event: ZcodeBrowserEvent): Pair<ZcodeBrowserState, ZcodeBrowserEffect> {
        return when (event) {
            is ZcodeBrowserEvent.Open ->
                ZcodeBrowserState(open = true, loading = true, requested = event.start) to ZcodeBrowserEffect.Read(event.start)
            ZcodeBrowserEvent.Close, ZcodeBrowserEvent.Disconnect -> ZcodeBrowserState() to ZcodeBrowserEffect.None
            is ZcodeBrowserEvent.Browse -> if (state.adding) state to ZcodeBrowserEffect.None else
                state.copy(loading = true, requested = event.path, filter = "",
                    readError = null, addError = null) to ZcodeBrowserEffect.Read(event.path)
            is ZcodeBrowserEvent.ReadOk -> {
                if (!state.loading || state.requested != event.requested) state to ZcodeBrowserEffect.None
                else state.copy(loading = false, current = event.listing.path, parent = event.listing.parent,
                    hasParent = event.listing.hasParent, entries = event.listing.directories,
                    readError = null) to ZcodeBrowserEffect.None
            }
            is ZcodeBrowserEvent.ReadFail -> {
                if (!state.loading || state.requested != event.requested) state to ZcodeBrowserEffect.None
                else state.copy(loading = false, readError = event.message) to ZcodeBrowserEffect.None
            }
            ZcodeBrowserEvent.AddStart -> {
                val ready = state.open && !state.loading && !state.adding &&
                    state.readError == null && state.current.isNotBlank()
                if (ready) state.copy(adding = true, addError = null) to ZcodeBrowserEffect.Add
                else state to ZcodeBrowserEffect.None
            }
            ZcodeBrowserEvent.AddOk -> ZcodeBrowserState() to ZcodeBrowserEffect.None
            is ZcodeBrowserEvent.AddFail ->
                if (state.adding) state.copy(adding = false, addError = event.message) to ZcodeBrowserEffect.None
                else state to ZcodeBrowserEffect.None
            is ZcodeBrowserEvent.Filter -> state.copy(filter = event.value) to ZcodeBrowserEffect.None
            is ZcodeBrowserEvent.Manual -> state.copy(manual = event.value) to ZcodeBrowserEffect.None
            is ZcodeBrowserEvent.Hidden ->
                if (state.adding || event.value == state.showHidden) state to ZcodeBrowserEffect.None
                else state.copy(showHidden = event.value, loading = true,
                    readError = null, addError = null) to
                    ZcodeBrowserEffect.Read(state.requested)
        }
    }
}
