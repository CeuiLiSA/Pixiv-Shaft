package ceui.pixiv.session

import android.text.TextUtils
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.lisa.fragments.FragmentLogin
import ceui.lisa.models.UserModel
import ceui.loxia.AccountResponse
import ceui.loxia.Client
import ceui.loxia.Event
import ceui.loxia.ObjectPool
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber

object SessionManager {

    private const val USER_KEY = "LoggedInUserJsonKey"
    const val COOKIE_KEY = "web-api-cookie"
    const val CONTENT_LANGUAGE_KEY = "content-language"

    private val _loggedInAccount = MutableLiveData<AccountResponse>()
    private val gson = Gson()

    val loggedInAccount: LiveData<AccountResponse> = _loggedInAccount

    private val _newTokenEvent = MutableLiveData<Event<Long>>()
    val newTokenEvent: LiveData<Event<Long>> = _newTokenEvent

    fun testRenewAnim() {
        _newTokenEvent.postValue(Event(System.currentTimeMillis()))
    }

    private val prefStore: MMKV by lazy {
        MMKV.defaultMMKV()
    }

    val isLoggedIn: Boolean get() {
        return _loggedInAccount.value != null
    }

    val loggedInUid: Long get() {
        return _loggedInAccount.value?.user?.id ?: 0L
    }

    fun initialize() {
        val json = prefStore.getString(USER_KEY, "")
        if (json?.isNotEmpty() == true) {
            try {
                val accountResponse = gson.fromJson(json, AccountResponse::class.java)
                _loggedInAccount.value = accountResponse
                accountResponse.user?.let {
                    ObjectPool.update(it)
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    fun updateSession(userModel: UserModel?) {
        if (userModel == null) {
            prefStore.putString(USER_KEY, "")
            _loggedInAccount.value = AccountResponse()
        } else {
            val javaJson = gson.toJson(userModel)
            val accountResponse = gson.fromJson(javaJson, AccountResponse::class.java)
            prefStore.putString(USER_KEY, gson.toJson(accountResponse))
            _loggedInAccount.value = accountResponse
        }
    }

    fun postUpdateSession(userModel: UserModel?) {
        if (userModel == null) {
            prefStore.putString(USER_KEY, "")
            _loggedInAccount.postValue(AccountResponse())
        } else {
            val javaJson = gson.toJson(userModel)
            val accountResponse = gson.fromJson(javaJson, AccountResponse::class.java)
            prefStore.putString(USER_KEY, gson.toJson(accountResponse))
            _loggedInAccount.postValue(accountResponse)
        }
    }


    fun refreshAccessToken(tokenForThisRequest: String): String? {
        val freshAccessToken = getAccessToken()
        if (!TextUtils.equals(freshAccessToken, tokenForThisRequest)) {
            return freshAccessToken
        }

        return runBlocking(Dispatchers.IO) {
            try {
                _newTokenEvent.postValue(Event(System.currentTimeMillis()))
                val refreshToken = _loggedInAccount.value?.refresh_token ?: throw RuntimeException("refresh_token not exist")
                val userModel = Client.authApi.newRefreshToken(
                    FragmentLogin.CLIENT_ID,
                    FragmentLogin.CLIENT_SECRET,
                    FragmentLogin.REFRESH_TOKEN,
                    refreshToken,
                    true
                ).execute().body()
                delay(500L)
                if (userModel != null) {
                    withContext(Dispatchers.Main) {
                        updateSession(userModel)
                    }
                    userModel.rawAccessToken
                } else {
                    throw RuntimeException("newRefreshToken failed")
                }
            } catch (ex: Exception) {
                Timber.e(ex)
                null
            }
        }
    }

    fun getAccessToken(): String {
        val account = _loggedInAccount.value ?: throw RuntimeException("account not found")
        return account.access_token ?: throw RuntimeException("access_token not exist")
    }



}