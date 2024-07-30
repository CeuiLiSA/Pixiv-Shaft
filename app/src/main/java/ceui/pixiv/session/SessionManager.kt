package ceui.pixiv.session

import androidx.lifecycle.MutableLiveData
import ceui.loxia.AccountResponse
import com.google.gson.Gson
import com.tencent.mmkv.MMKV

object SessionManager {

    private const val LoggedInUserJsonKey = "LoggedInUserJsonKey"

    private val _loggedInAccount = MutableLiveData<AccountResponse>()
    private val gson = Gson()

    private val prefStore: MMKV by lazy {
        MMKV.defaultMMKV()
    }

    val isLoggedIn: Boolean get() {
        return _loggedInAccount.value != null
    }

    fun load() {
        val json = prefStore.getString(LoggedInUserJsonKey, "")
        if (json?.isNotEmpty() == true) {
            try {
                _loggedInAccount.value = gson.fromJson(json, AccountResponse::class.java)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    fun updateSession(accountResponse: AccountResponse) {
        prefStore.putString(LoggedInUserJsonKey, gson.toJson(accountResponse))
        _loggedInAccount.value = accountResponse
    }

    suspend fun refreshAccessToken() {

    }

    fun getAccessToken(): String {
        val account = _loggedInAccount.value ?: throw RuntimeException("account not found")
        return account.access_token ?: throw RuntimeException("access_token not exist")
    }



}