package ceui.pixiv.task

import ceui.lisa.utils.Common
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.KListShow
import ceui.pixiv.ui.common.getFileSize
import com.blankj.utilcode.util.PathUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class FetchAllTask<Item, ResponseT: KListShow<Item>>(
    initialLoader: suspend () -> KListShow<Item>
) {

    private val results = mutableListOf<Item>()
    private val gson = Gson()

    init {
        MainScope().launch {
            withContext(Dispatchers.IO) {
                try {
                    if (results.isNotEmpty()) {
                        results.clear()
                    }
                    var nextPageUrl: String? = null

                    Common.showLog("FetchAllTask initialLoader start ${results.size}")

                    // Load initial page
                    val resp = initialLoader()
                    if (resp.displayList.isNotEmpty()) {
                        results.addAll(resp.displayList)
                        Common.showLog("FetchAllTask initialLoader end ${results.size}")
                        nextPageUrl = resp.nextPageUrl
                    }

                    // Fetch subsequent pages
                    while (!nextPageUrl.isNullOrEmpty()) {
                        delay(3000L)
                        val responseClass = resp::class.java
                        Common.showLog("FetchAllTask subsequent start ${results.size}")
                        val responseBody = Client.appApi.generalGet(nextPageUrl)
                        val responseJson = responseBody.string()
                        val response = gson.fromJson(responseJson, responseClass) as ResponseT

                        if (response.displayList.isNotEmpty()) {
                            results.addAll(response.displayList)
                        }
                        Common.showLog("FetchAllTask subsequent end ${results.size}")
                        nextPageUrl = response.nextPageUrl
                    }

                    onEnd()
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    // Handle exceptions accordingly
                }
            }
        }
    }

    private fun onEnd() {
        Common.showLog("FetchAllTask all end ${results.size}")
        // Serialize results to JSON and write to cache file
        val json = gson.toJson(results)
        val cacheFile = File(PathUtils.getInternalAppCachePath(), "task-result.text")

        BufferedWriter(OutputStreamWriter(FileOutputStream(cacheFile), "UTF-8")).use { writer ->
            writer.write(json)
        }

        val fileSize = getFileSize(cacheFile)
        Common.showLog("FetchAllTask fileSize ${fileSize}")
    }
}

fun loadIllustsFromCache(): List<Illust>? {
    val cacheFile = File(PathUtils.getInternalAppCachePath(), "task-result.text")
    return if (cacheFile.exists()) {
        try {
            val json = BufferedReader(InputStreamReader(FileInputStream(cacheFile), "UTF-8")).use { reader ->
                reader.readText()
            }
            val type = object : TypeToken<List<Illust>>() {}.type
            Gson().fromJson<List<Illust>>(json, type)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    } else {
        null
    }
}
