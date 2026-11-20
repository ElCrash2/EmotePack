package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.EmotePackApplication
import com.example.data.model.SearchOrigin
import com.example.data.model.SearchOriginType
import com.example.data.model.SevenTvEmote
import com.example.data.model.SevenTvEmoteSet
import com.example.data.model.SevenTvUser
import com.example.data.remote.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EmoteGridUiState(
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isSelectingAllPages: Boolean = false,
    val streamer: SevenTvUser? = null,
    val emoteSets: List<SevenTvEmoteSet> = emptyList(),
    val activeSetId: String? = null, // null = All sets
    val searchQuery: String = "",
    val displayedEmotes: List<SevenTvEmote> = emptyList(),
    val totalCount: Int = 0,
    val currentPage: Int = 1,
    val hasMorePages: Boolean = false,
    val errorMessage: String? = null,
    val isNetworkError: Boolean = false
)

class EmoteGridViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as EmotePackApplication
    private val repo = app.sevenTvRepository
    val session = app.selectionSession

    private val _uiState = MutableStateFlow(EmoteGridUiState())
    val uiState: StateFlow<EmoteGridUiState> = _uiState.asStateFlow()

    private var allStreamerEmotes: List<SevenTvEmote> = emptyList()

    fun loadStreamer(userId: String, initialSetId: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, isNetworkError = false) }
            when (val res = repo.getStreamerWithEmoteSets(userId)) {
                is ApiResult.Success -> {
                    val user = res.data
                    val sets = user.emoteSets
                    allStreamerEmotes = sets.flatMap { it.emotes }.distinctBy { it.id }
                    val validSetId = if (initialSetId != null && sets.any { it.id == initialSetId }) {
                        initialSetId
                    } else null

                    val filtered = if (validSetId == null) {
                        allStreamerEmotes
                    } else {
                        sets.firstOrNull { it.id == validSetId }?.emotes.orEmpty()
                    }

                    session.updateOrigin(
                        SearchOrigin(
                            type = SearchOriginType.STREAMER,
                            query = user.displayName,
                            streamerId = user.id,
                            streamerName = user.displayName,
                            streamerUsername = user.username,
                            streamerAvatarUrl = user.avatarUrl,
                            emoteSetId = validSetId
                        )
                    )

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            streamer = user,
                            emoteSets = sets,
                            activeSetId = validSetId,
                            displayedEmotes = filtered,
                            totalCount = filtered.size,
                            hasMorePages = false,
                            errorMessage = null
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = res.message,
                            isNetworkError = res.isNetwork
                        )
                    }
                }
            }
        }
    }

    fun selectEmoteSetFilter(setId: String?) {
        val sets = _uiState.value.emoteSets
        val filtered = if (setId == null) {
            allStreamerEmotes
        } else {
            sets.firstOrNull { it.id == setId }?.emotes.orEmpty()
        }
        val currentOrigin = session.currentOrigin.value
        if (currentOrigin != null && currentOrigin.type == SearchOriginType.STREAMER) {
            session.updateOrigin(currentOrigin.copy(emoteSetId = setId))
        }
        _uiState.update {
            it.copy(
                activeSetId = setId,
                displayedEmotes = filtered,
                totalCount = filtered.size
            )
        }
    }

    fun searchEmotes(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return

        session.updateOrigin(
            SearchOrigin(
                type = SearchOriginType.EMOTE,
                query = trimmed
            )
        )

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    searchQuery = trimmed,
                    currentPage = 1,
                    displayedEmotes = emptyList(),
                    hasMorePages = false,
                    errorMessage = null,
                    isNetworkError = false
                )
            }
            when (val res = repo.searchEmotes(query = trimmed, page = 1, limit = 36)) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            displayedEmotes = res.data.items,
                            totalCount = maxOf(res.data.totalCount, res.data.items.size),
                            currentPage = 1,
                            hasMorePages = res.data.hasMore,
                            errorMessage = null
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = res.message,
                            isNetworkError = res.isNetwork
                        )
                    }
                }
            }
        }
    }

    fun loadNextEmotePage() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMorePages || state.searchQuery.isBlank()) return
        val nextPage = state.currentPage + 1

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            when (val res = repo.searchEmotes(query = state.searchQuery, page = nextPage, limit = 36)) {
                is ApiResult.Success -> {
                    val combined = (state.displayedEmotes + res.data.items).distinctBy { it.id }
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            displayedEmotes = combined,
                            totalCount = maxOf(res.data.totalCount, combined.size),
                            currentPage = nextPage,
                            hasMorePages = res.data.hasMore
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            errorMessage = res.message,
                            isNetworkError = res.isNetwork
                        )
                    }
                }
            }
        }
    }

    /**
     * Global Select All / Deselect All across ALL results (including pending unloaded pages).
     */
    fun toggleSelectAllGlobal() {
        val state = _uiState.value
        val currentDisplayed = state.displayedEmotes
        if (currentDisplayed.isEmpty()) return

        val selectedMap = session.selectedEmotes.value
        val allDisplayedSelected = currentDisplayed.all { selectedMap.containsKey(it.id) } && !state.hasMorePages

        if (allDisplayedSelected) {
            session.deselectAll(currentDisplayed.map { it.id })
            return
        }

        // Select currently loaded items immediately
        session.selectAll(currentDisplayed)

        // If there are more pages in emote search, fetch remaining pages in background to complete global selection
        if (state.hasMorePages && state.searchQuery.isNotBlank() && !state.isSelectingAllPages) {
            viewModelScope.launch {
                _uiState.update { it.copy(isSelectingAllPages = true) }
                var page = state.currentPage + 1
                var hasMore = true
                val accumulated = state.displayedEmotes.toMutableList()
                // Cap at 15 pages (540 emotes) to avoid unbounded memory/API overload while covering full result sets
                while (hasMore && page <= 15) {
                    when (val res = repo.searchEmotes(query = state.searchQuery, page = page, limit = 36)) {
                        is ApiResult.Success -> {
                            val newItems = res.data.items
                            if (newItems.isEmpty()) {
                                hasMore = false
                            } else {
                                accumulated.addAll(newItems)
                                session.selectAll(newItems)
                                hasMore = res.data.hasMore
                                page++
                            }
                        }
                        is ApiResult.Error -> {
                            hasMore = false
                        }
                    }
                }
                val deduped = accumulated.distinctBy { it.id }
                _uiState.update {
                    it.copy(
                        isSelectingAllPages = false,
                        displayedEmotes = deduped,
                        currentPage = page - 1,
                        hasMorePages = false,
                        totalCount = maxOf(it.totalCount, deduped.size)
                    )
                }
            }
        }
    }
}
