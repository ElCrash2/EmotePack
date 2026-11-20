package com.example.ui.state

import com.example.data.local.CollectionWithDetails
import com.example.data.model.SearchOrigin
import com.example.data.model.SearchOriginType
import com.example.data.model.SevenTvEmote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Shared selection session preserving selected emotes across screen transitions,
 * set filter switches, and collection edits.
 */
class SelectionSessionManager {

    private val _selectedEmotes = MutableStateFlow<Map<String, SevenTvEmote>>(emptyMap())
    val selectedEmotes: StateFlow<Map<String, SevenTvEmote>> = _selectedEmotes.asStateFlow()

    private val _editingCollectionId = MutableStateFlow<String?>(null)
    val editingCollectionId: StateFlow<String?> = _editingCollectionId.asStateFlow()

    private val _editingCollectionName = MutableStateFlow<String?>(null)
    val editingCollectionName: StateFlow<String?> = _editingCollectionName.asStateFlow()

    private val _currentOrigin = MutableStateFlow<SearchOrigin?>(null)
    val currentOrigin: StateFlow<SearchOrigin?> = _currentOrigin.asStateFlow()

    fun startFreshSession(origin: SearchOrigin) {
        _editingCollectionId.value = null
        _editingCollectionName.value = null
        _currentOrigin.value = origin
        _selectedEmotes.value = emptyMap()
    }

    fun startEditSession(details: CollectionWithDetails): SearchOrigin {
        val col = details.collection
        val type = runCatching { SearchOriginType.valueOf(col.searchType) }
            .getOrDefault(SearchOriginType.EMOTE)

        val origin = SearchOrigin(
            type = type,
            query = col.searchQuery,
            streamerId = col.streamerId,
            streamerName = col.streamerName,
            streamerUsername = col.streamerUsername,
            streamerAvatarUrl = col.streamerAvatarUrl,
            emoteSetId = col.sourceEmoteSetId
        )

        val initialMap = details.items.associate { item ->
            item.emoteId to SevenTvEmote(
                id = item.emoteId,
                name = item.emoteName,
                animated = item.isAnimated,
                flags = 0,
                ownerName = col.streamerName,
                previewUrl = item.previewUrl,
                highResUrl = item.highResUrl,
                fallbackUrls = item.fallbackUrlsCsv.split("|").filter { it.isNotBlank() },
                sourceSetId = item.sourceSetId,
                sourceSetName = item.sourceSetName
            )
        }

        _editingCollectionId.value = col.id
        _editingCollectionName.value = col.name
        _currentOrigin.value = origin
        _selectedEmotes.value = initialMap
        return origin
    }

    fun updateOrigin(origin: SearchOrigin) {
        _currentOrigin.value = origin
    }

    fun toggleEmote(emote: SevenTvEmote) {
        _selectedEmotes.update { current ->
            val mutable = current.toMutableMap()
            if (mutable.containsKey(emote.id)) {
                mutable.remove(emote.id)
            } else {
                mutable[emote.id] = emote
            }
            mutable
        }
    }

    fun selectAll(emotes: List<SevenTvEmote>) {
        _selectedEmotes.update { current ->
            val mutable = current.toMutableMap()
            emotes.forEach { mutable[it.id] = it }
            mutable
        }
    }

    fun deselectAll(emoteIds: Collection<String>) {
        _selectedEmotes.update { current ->
            val mutable = current.toMutableMap()
            emoteIds.forEach { mutable.remove(it) }
            mutable
        }
    }

    fun removeSelected(emoteId: String) {
        _selectedEmotes.update { current ->
            val mutable = current.toMutableMap()
            mutable.remove(emoteId)
            mutable
        }
    }

    fun clearSelection() {
        _selectedEmotes.value = emptyMap()
        _editingCollectionId.value = null
        _editingCollectionName.value = null
    }
}
