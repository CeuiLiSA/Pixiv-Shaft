package ceui.lisa.cache

import com.google.gson.Gson
import com.tencent.mmkv.MMKV

class MMKVOperator: IOperate {

    companion object {
        const val MMKVOperatorTag = "MMKVOperatorTag"
    }

    private val impl: MMKV by lazy { MMKV.mmkvWithID(MMKVOperatorTag) }

    override fun <T : Any> getModel(key: String, pClass: Class<T>): T? {
        val stored = impl.getString(key, null)
        return if (stored != null) {
            try {
                Gson().fromJson(stored, pClass)
            } catch (ex: Exception) {
                null
            }
        } else {
            null
        }
    }

    override fun <T : Any?> saveModel(ket: String, pT: T) {
        val result = Gson().toJson(pT)
        impl.putString(ket, result)
    }

    override fun clearAll() {
        impl.clearAll()
    }

    override fun clear(key: String) {
        impl.putString(key, null)
    }
}