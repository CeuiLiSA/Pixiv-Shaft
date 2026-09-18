package ceui.pixiv.ui.recommend

import android.app.Application
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ceui.lisa.R
import ceui.lisa.network.ShaftApiV2
import ceui.lisa.network.ShaftApiV2Client
import ceui.pixiv.api.model.Illust
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.services.appServices
import ceui.pixiv.session.SessionManager
import ceui.pixiv.shaftapi.ShaftHmac
import ceui.pixiv.ui.common.IllustFeedFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import com.google.gson.JsonObject

/** A finite daily feed; it shares the normal artwork filters and interactions. */
class DailyRecommendationsFragment : IllustFeedFragment(R.layout.fragment_daily_recommendations) {
    private val dailyModel: DailyRecommendationsModel by viewModels()
    override val feedViewModel by feedViewModels(autoLoad = false) {
        dailyModel.source
    }
    override val applyBottomSafeInset = true
    override val detailContinuationCursor: String? get() = null
    override val emptyStateText: String get() = getString(R.string.daily_recommendations_empty)
    override fun poolableBeansOf(item: FeedItem): List<Illust> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
        view.findViewById<TextView>(R.id.toolbar_title).setText(R.string.daily_recommendations)
        toolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        // This standalone page handles status/navigation insets once, outside the feed.
        toolbar.fitsSystemWindows = false
        val list = view.findViewById<View>(ceui.pixiv.feeds.R.id.feed_list_view)
        val bottomPadding = list.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.updatePadding(top = bars.top)
            list.updatePadding(bottom = bottomPadding + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
        val summary = view.findViewById<TextView>(R.id.daily_summary)
        SessionManager.loggedInAccount.observe(viewLifecycleOwner) { account ->
            val uid = account?.user?.id ?: 0L
            if (dailyModel.changeAccount(uid)) {
                // Remove the previous account's recommendations even if the next request fails.
                feedViewModel.mutateItems { emptyList() }
                feedViewModel.refresh()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dailyModel.response.collect { response ->
                    summary.text = when {
                        response == null -> getString(R.string.daily_recommendations_intro)
                        response.mode == "personalized" -> getString(R.string.daily_recommendations_personalized, response.date)
                        else -> getString(R.string.daily_recommendations_popular, response.date)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (dailyModel.response.value?.refresh_at?.let { it <= System.currentTimeMillis() } == true) {
            feedViewModel.refresh()
        }
    }
}

class DailyRecommendationsModel(application: Application) : AndroidViewModel(application) {
    private val mutableResponse = MutableStateFlow<ShaftApiV2.DailyRecommendationsResponse?>(null)
    val response = mutableResponse.asStateFlow()
    private var accountUid = SessionManager.loggedInAccount.value?.user?.id ?: 0L

    fun changeAccount(uid: Long): Boolean {
        if (uid == accountUid) return false
        accountUid = uid
        mutableResponse.value = null
        return true
    }

    val source = FeedSource<String> { cursor ->
        val uid = accountUid
        if (uid <= 0) throw DailyRecommendationUnavailable(application.getString(R.string.daily_recommendations_login))
        val clientId = application.appServices().eventReporter.currentClientId()
        if (!clientId.matches(Regex("[a-f0-9]{64}")) || !ShaftHmac.isConfigured) {
            throw DailyRecommendationUnavailable(application.getString(R.string.daily_recommendations_unavailable))
        }
        val raw = JsonObject().apply {
            addProperty("uid", uid)
            addProperty("client_id", clientId)
            addProperty("ts", System.currentTimeMillis())
            addProperty("type", "illust")
            cursor?.let { addProperty("cursor", it) }
        }.toString()
        val result = try {
            ShaftApiV2Client.service.dailyRecommendations(
                ShaftHmac.signHex(raw), raw.toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
        } catch (error: HttpException) {
            val message = if (error.code() == 409) R.string.daily_recommendations_expired
                else R.string.daily_recommendations_unavailable
            throw DailyRecommendationUnavailable(application.getString(message))
        }
        val items = withContext(Dispatchers.Default) {
            result.items.mapNotNull { it.toIllustFeedItem(0f, logTag = "DailyRecommendations") }
        }
        if (uid != accountUid || uid != (SessionManager.loggedInAccount.value?.user?.id ?: 0L)) {
            throw CancellationException("Account changed during daily recommendations")
        }
        mutableResponse.value = result
        FeedPage(items, nextCursor = result.next_cursor)
    }
}

private class DailyRecommendationUnavailable(message: String) : Exception(message)
