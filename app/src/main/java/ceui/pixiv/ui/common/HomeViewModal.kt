package ceui.pixiv.ui.common

import android.content.res.AssetManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.loxia.IllustResponse
import ceui.loxia.ObjectPool
import ceui.pixiv.ui.task.LoadTask
import ceui.pixiv.ui.task.NamedUrl
import ceui.pixiv.ui.works.buildPixivWorksFileName
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class HomeViewModel(private val assets: AssetManager) : ViewModel() {


    private val _illustResponse = MutableLiveData<IllustResponse>()
    private val _landingBackgroundFile = MutableLiveData<File>()
    val landingBackgroundFile: LiveData<File> = _landingBackgroundFile

    private val _grayDisplay = MutableLiveData(false)
    val grayDisplay: LiveData<Boolean> = _grayDisplay

    private val navStack = mutableListOf<Int>()

    private val _currentScale = MutableLiveData(1f)
    val currentScale: LiveData<Float> = _currentScale

    fun toggleGrayModeImpl() {
        _grayDisplay.value = _grayDisplay.value?.not() == true
    }

    fun onDestinationChanged(destId: Int) {
        val lastId = navStack.lastOrNull()

        when {
            lastId == null -> {
                // 初始化导航堆栈
                navStack.add(destId)
            }

            lastId == destId -> {
                // 重复导航，无需处理
            }

            navStack.contains(destId) -> {
                // Pop 操作
                while (navStack.isNotEmpty() && navStack.last() != destId) {
                    navStack.removeAt(navStack.size - 1)
                }
                _currentScale.value = (_currentScale.value ?: 1f) / 1.1f
            }

            else -> {
                // Push 操作
                navStack.add(destId)
                _currentScale.value = (_currentScale.value ?: 1f) * 1.1f
            }
        }
    }

    fun reset() {
        _currentScale.value = 1F
    }


    private fun loadFromLocal() {
        val jsonString =
            assets.open("landing_bg.json").bufferedReader().use { it.readText() }
        val raw = Gson().fromJson(jsonString, IllustResponse::class.java)
        val list = raw.displayList
        _illustResponse.value = raw.copy(illusts = list.shuffled())
    }

    private var currentIndex = 0
    private var _job: Job? = null

    fun startTask() {
        val list = _illustResponse.value?.displayList.orEmpty()
        if (list.isEmpty()) return

        _job = viewModelScope.launch {
            val illust = list[currentIndex]
            currentIndex = (currentIndex + 1) % list.size  // 顺序循环

            ObjectPool.update(illust)
            val url = illust.meta_single_page?.original_image_url
                ?: illust.meta_pages?.getOrNull(0)?.image_urls?.original

            if (url != null) {
                object : LoadTask(
                    NamedUrl(buildPixivWorksFileName(illust.id, 0), url),
                    viewModelScope,
                    true
                ) {
                    override fun onEnd(resultT: File) {
                        super.onEnd(resultT)
                        _landingBackgroundFile.postValue(resultT)

                        _job = viewModelScope.launch {
                            delay(8000)
                            startTask()
                        }
                    }
                }
            }
        }
    }

    fun endTask() {
        _job?.cancel()
        _job = null
    }


    init {
        loadFromLocal()
    }
}
