package com.vknxworks.galleryctrl.ui.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.vknxworks.galleryctrl.media.Album
import com.vknxworks.galleryctrl.media.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The last few results of a heavy computation over the library (grouping by day, albums), by their inputs. Lists
 * are matched by identity (the same list object, as the library hands out until it changes), so a lookup never
 * compares tens of thousands of items. Coming back to a grid (from the viewer, another tab) finds its grouping here
 * instead of doing it again on the main thread.
 */
internal class ComputedMemo<T>(private val max: Int = 4) {
    private val entries = ArrayDeque<Pair<List<Any?>, T>>()

    private fun same(a: List<Any?>, b: List<Any?>): Boolean = a.size == b.size && a.indices.all { i ->
        val x = a[i]
        val y = b[i]
        if (x is Collection<*> || y is Collection<*>) x === y else x == y
    }

    @Synchronized
    fun get(key: List<Any?>): T? = entries.firstOrNull { same(it.first, key) }?.second

    @Synchronized
    fun put(key: List<Any?>, value: T) {
        entries.removeAll { same(it.first, key) }
        entries.addFirst(key to value)
        while (entries.size > max) entries.removeLast()
    }
}

internal val GridGroupMemo = ComputedMemo<Pair<List<Pair<Any, List<MediaItem>>>, LongArray>>()
internal val AlbumsMemo = ComputedMemo<List<Album>>()

/**
 * [compute] for [keys]: the remembered result when these inputs were seen before, otherwise worked out on a
 * background thread while the previous result stays on screen. Only the very first time a screen is shown (with
 * nothing to show yet) is it worked out right away, as before, so a grid's scroll position is restored correctly.
 */
@Composable
internal fun <T> rememberComputed(memo: ComputedMemo<T>, vararg keys: Any?, compute: () -> T): T {
    val key = keys.toList()
    val initial = remember { memo.get(key) ?: compute().also { memo.put(key, it) } }
    val state = produceState(initial, *keys) {
        val known = memo.get(key)
        value = known ?: withContext(Dispatchers.Default) { compute() }.also { memo.put(key, it) }
    }
    return state.value
}
