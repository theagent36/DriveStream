package com.example.drivestream

import android.os.SystemClock
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.ImageResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/**
 * Controller to track fast flings and coordinate image request pausing.
 */
object ThumbnailScrollController {
    private val _isFlinging = MutableStateFlow(false)
    val isFlinging: StateFlow<Boolean> = _isFlinging.asStateFlow()

    fun setFlinging(flinging: Boolean) {
        _isFlinging.value = flinging
    }
}

/**
 * Coil Interceptor that temporarily pauses fetching or decoding new thumbnails
 * while the user initiates a rapid fling / fast scroll.
 * Images already present in the in-memory cache are passed through immediately.
 * Requests resume as soon as the scroll settles back to idle.
 */
class FlingPauseInterceptor(
    private val memoryCacheProvider: () -> MemoryCache?
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val cache = memoryCacheProvider()
        val cacheKey = request.memoryCacheKey ?: (request.data as? String)?.let { MemoryCache.Key(it) }
        val isInMemory = if (cache != null && cacheKey != null) {
            cache[cacheKey] != null
        } else {
            false
        }

        // If not already in memory cache and the list is undergoing a fast fling,
        // suspend off-thread until the scroll settles back to idle.
        if (!isInMemory && ThumbnailScrollController.isFlinging.value) {
            ThumbnailScrollController.isFlinging.first { !it }
        }

        return chain.proceed(request)
    }
}

/**
 * Hooks into LazyListState to detect rapid flings vs gentle scrolling.
 * Pauses background image decoding during rapid flings and resumes when idle.
 */
@Composable
fun TrackScrollStateForFling(listState: LazyListState) {
    LaunchedEffect(listState) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousOffset = listState.firstVisibleItemScrollOffset
        var previousTime = SystemClock.uptimeMillis()

        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }.collect { (isScrolling, currentIndex, currentOffset) ->
            if (!isScrolling) {
                ThumbnailScrollController.setFlinging(false)
                previousIndex = currentIndex
                previousOffset = currentOffset
                previousTime = SystemClock.uptimeMillis()
            } else {
                val now = SystemClock.uptimeMillis()
                val dt = (now - previousTime).coerceAtLeast(1)
                val indexDelta = abs(currentIndex - previousIndex)
                val offsetDelta = abs(currentOffset - previousOffset)

                // Detect rapid scroll: skipped items or velocity > 1.2 px/ms (~1200 px/sec)
                val isRapidFling = indexDelta >= 1 || (offsetDelta.toFloat() / dt) > 1.2f
                if (isRapidFling) {
                    ThumbnailScrollController.setFlinging(true)
                }
                previousIndex = currentIndex
                previousOffset = currentOffset
                previousTime = now
            }
        }
    }
}

/**
 * Lookahead pre-loader that monitors scroll state and asynchronously pre-loads
 * the upcoming 4-5 items' thumbnails into the Coil in-memory cache before they scroll onto screen.
 */
@Composable
fun ThumbnailLookaheadPreloader(
    listState: LazyListState,
    items: List<DriveFile>,
    lookaheadCount: Int = 5
) {
    val context = LocalContext.current
    val imageLoader = context.imageLoader

    LaunchedEffect(listState, items) {
        var previousFirstIndex = listState.firstVisibleItemIndex

        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) -1 to -1
            else visible.first().index to visible.last().index
        }.collect { (firstVisible, lastVisible) ->
            if (firstVisible == -1 || items.isEmpty()) return@collect

            val isScrollingDown = firstVisible >= previousFirstIndex
            previousFirstIndex = firstVisible

            val rangeToPreload = if (isScrollingDown) {
                (lastVisible + 1..(lastVisible + lookaheadCount).coerceAtMost(items.size - 1))
            } else {
                ((firstVisible - lookaheadCount).coerceAtLeast(0) until firstVisible)
            }

            for (index in rangeToPreload) {
                if (index in items.indices) {
                    val file = items[index]
                    val url = file.thumbnailUrl ?: file.albumArtUrl
                    if (!url.isNullOrEmpty()) {
                        val request = ImageRequest.Builder(context)
                            .data(url)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build()
                        imageLoader.enqueue(request)
                    }
                }
            }
        }
    }
}
