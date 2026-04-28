package ceui.pixiv.ui.comic.reader

import androidx.lifecycle.ViewModel
import ceui.lisa.database.ComicBookmarkEntity
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Activity 范围共享的事件总线。BottomSheet 不再用 `parentFragment as? Callback` 这种
 * 编译期不可见的反射式握手——sheet 直接 emit 事件，Fragment collect。
 *
 * 用 [activityViewModels] 注入，sheet 与宿主 Fragment 自然共享同一个实例。
 */
class ComicReaderEventBus : ViewModel() {

    sealed class Event {
        data class JumpToPage(val pageIndex: Int) : Event()
        data class JumpToBookmark(val entry: ComicBookmarkEntity) : Event()
        object AddBookmarkAtCurrent : Event()
    }

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun post(event: Event) { _events.tryEmit(event) }
}
