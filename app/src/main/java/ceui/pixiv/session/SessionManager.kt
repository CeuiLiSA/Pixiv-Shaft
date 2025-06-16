package ceui.pixiv.session

import android.net.Uri
import android.text.TextUtils
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.lisa.feature.HostManager
import ceui.lisa.fragments.FragmentLogin
import ceui.lisa.models.UserModel
import ceui.loxia.AccountResponse
import ceui.loxia.Client
import ceui.loxia.Event
import ceui.loxia.ObjectPool
import ceui.loxia.isJson
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber

object SessionManager {

    private const val USER_KEY = "LoggedInUserJsonKey"
    const val USE_NEW_UI_KEY = "use-v5-ui"
    const val COOKIE_KEY = "web-api-cookie"
    const val CONTENT_LANGUAGE_KEY = "content-language"

    private val _loggedInAccount = MutableLiveData<AccountResponse>()
    private val gson = Gson()

    val loggedInAccount: LiveData<AccountResponse> = _loggedInAccount

    private val _newTokenEvent = MutableLiveData<Event<Long>>()
    val newTokenEvent: LiveData<Event<Long>> = _newTokenEvent

    private val prefStore: MMKV by lazy {
        MMKV.mmkvWithID("shaft-session")
    }

    val isLoggedIn: Boolean
        get() {
            return _loggedInAccount.value != null
        }

    val loggedInUid: Long
        get() {
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
            MMKV.defaultMMKV().clearAll()
        } else {
            val javaJson = gson.toJson(userModel)
            val accountResponse = gson.fromJson(javaJson, AccountResponse::class.java)
            prefStore.putString(USER_KEY, gson.toJson(accountResponse))
            _loggedInAccount.value = accountResponse
        }
    }

    fun updateSessionWithAccountResponse(accountResponse: AccountResponse?) {
        if (accountResponse == null) {
            prefStore.putString(USER_KEY, "")
            _loggedInAccount.value = AccountResponse()
            MMKV.defaultMMKV().clearAll()
        } else {
            prefStore.putString(USER_KEY, gson.toJson(accountResponse))
            _loggedInAccount.value = accountResponse
        }
    }


    fun postUpdateSession(userModel: UserModel?) {
        if (userModel == null) {
            _loggedInAccount.postValue(AccountResponse())
            prefStore.clearAll()
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
                val refreshToken = _loggedInAccount.value?.refresh_token
                    ?: throw RuntimeException("refresh_token not exist")
                refreshAccessTokenInternal(refreshToken)
            } catch (ex: Exception) {
                Timber.e(ex)
                null
            }
        }
    }

    private suspend fun refreshAccessTokenInternal(refreshToken: String): String? {
        _newTokenEvent.postValue(Event(System.currentTimeMillis()))
        val accountResponse = Client.authApi.newRefreshToken2(
            FragmentLogin.CLIENT_ID,
            FragmentLogin.CLIENT_SECRET,
            FragmentLogin.REFRESH_TOKEN,
            refreshToken,
            true
        ).execute().body()
        Timber.d("dasadsdsasd2 ${gson.toJson(accountResponse)}")
        delay(500L)
        return if (accountResponse != null) {
            withContext(Dispatchers.Main) {
                updateSessionWithAccountResponse(accountResponse)
            }
            accountResponse.access_token
        } else {
            throw RuntimeException("newRefreshToken failed")
        }
    }

    fun getAccessToken(): String {
        val account = _loggedInAccount.value ?: throw RuntimeException("account not found")
        return account.access_token ?: throw RuntimeException("access_token not exist")
    }

    fun loginWithUrl(uri: Uri, block: () -> Unit) {
        MainScope().launch {
            try {
                val accountResponse = withContext(Dispatchers.IO) {
                    _newTokenEvent.postValue(Event(System.currentTimeMillis()))
                    Client.authApi.newLogin(
                        FragmentLogin.CLIENT_ID,
                        FragmentLogin.CLIENT_SECRET,
                        FragmentLogin.AUTH_CODE,
                        uri.getQueryParameter("code"),
                        HostManager.get().getPkce().verify,
                        FragmentLogin.CALL_BACK,
                        true
                    ).execute().body()
                }

                if (accountResponse != null) {
                    Timber.d("Login with url success: $accountResponse")
                    prefStore.putString(USER_KEY, gson.toJson(accountResponse))
                    _loggedInAccount.value = accountResponse
                    block()
                }
            } catch (ex: Exception) {
                Timber.e(ex)
            }
        }
    }

    fun loginWithToken(tokenString: String, block: () -> Unit) {
        MainScope().launch {
            try {
                val accessToken = withContext(Dispatchers.IO) {
                    val refreshToken = if (tokenString.isJson()) {
                        val accountResponse =
                            gson.fromJson(tokenString, AccountResponse::class.java)
                        accountResponse?.refresh_token
                    } else {
                        tokenString
                    }
                    if (refreshToken?.isNotEmpty() == true) {
                        refreshAccessTokenInternal(refreshToken)
                    } else {
                        ""
                    }
                }

                if (accessToken?.isNotEmpty() == true) {
                    withContext(Dispatchers.Main) {
                        block()
                    }
                }
            } catch (ex: Exception) {
                Timber.e(ex)
            }
        }
    }
}