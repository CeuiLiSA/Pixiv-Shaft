package ceui.pixiv.ui.library

import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentBookmarkLibraryBinding
import ceui.lisa.utils.DensityUtil
import ceui.lisa.utils.Params
import ceui.pixiv.db.mirror.AgeFilter
import ceui.pixiv.db.mirror.AiFilter
import ceui.pixiv.db.mirror.BookmarkFilter
import ceui.pixiv.db.mirror.BookmarkMirrorStateEntity
import ceui.pixiv.db.mirror.BookmarkShelf
import ceui.pixiv.db.mirror.BookmarkSort
import ceui.pixiv.db.mirror.MirrorContentType
import ceui.pixiv.db.mirror.MirrorPhase
import ceui.pixiv.db.mirror.MirrorRestrict
import ceui.pixiv.db.mirror.PageFilter
import ceui.pixiv.db.mirror.ValidityFilter
import ceui.pixiv.feeds.FeedUiState
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.LoadState
import ceui.pixiv.services.appServices
import ceui.pixiv.ui.debug.DebugMirrorRebuildSim
import ceui.pixiv.ui.navigation.TemplateRoute
import com.blankj.utilcode.util.BarUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale

/**
 * 鏀惰棌搴撻〉闈㈢殑鍏ㄩ儴鎺ョ嚎锛屾彃鐢荤増涓庡皬璇寸増**鍏辩敤鍚屼竴浠?*銆?
 *
 * 涓轰粈涔堝崟鐙垚涓€涓被鑰屼笉鏄斁杩涙煇涓熀绫伙細鎻掔敾鍒楄〃缁ф壙 [ceui.pixiv.ui.common.IllustFeedFragment]銆?
 * 灏忚鍒楄〃缁ф壙 [ceui.pixiv.ui.common.NovelFeedFragment]锛堜袱濂楀畬鍏ㄤ笉鍚岀殑鍗＄墖銆佺偣鍑昏涔夈€?
 * 楠ㄦ灦鍥惧拰 LayoutManager锛夛紝Kotlin 鍙堟病鏈夊缁ф壙銆傝涔堟妸杩欎笁鐧惧琛屽鍒朵袱浠姐€佷粠姝ゅ悇鑷紓绉伙紝
 * 瑕佷箞鎶芥垚涓€涓彧渚濊禆銆宐inding + 鍒楄〃 VM + feed VM銆嶇殑鏅€氱被 鈥斺€?鍚庤€呮樉鐒舵洿鍒掔畻锛?
 * 涓や釜椤甸潰鐨勫樊寮傚叾瀹炲彧鏈夈€屼竴琛?payload 瑙ｆ瀽鎴愭彃鐢诲崱杩樻槸灏忚鍗°€嶈繖涓€澶勩€?
 *
 * 鐢熷懡鍛ㄦ湡璺熺潃 **view** 璧帮細瀹夸富鍦?onViewCreated 寤恒€乷nDestroyView 璋?[destroy]銆?
 * 鎵€鏈夊彲鍙樼殑瑙嗗浘鎬侊紙chip 寮曠敤銆佺姸鎬佸揩鐓с€佸緟閲嶇疆浠ｅ彿锛夐兘鏀跺湪杩欓噷锛屽涓昏嚜宸变笉鐣欍€?
 */
internal class BookmarkLibraryUi(
    private val fragment: Fragment,
    private val binding: FragmentBookmarkLibraryBinding,
    private val listView: RecyclerView,
    private val viewModel: BookmarkLibraryViewModel,
    private val feedViewModel: FeedViewModel<String>,
    private val contentType: MirrorContentType,
    /** 褰撳墠鍒楄〃涓婃湁澶氬皯鏉＄洰銆傚垽鏂€屽睆骞曚笂杩欎唤鏄笉鏄凡缁忚繃鏈熴€嶈鐢紝瑙?[refreshIfStale]銆?*/
    private val itemCount: () -> Int,
) {

    private var sortChip: TextView? = null
    private var reverseChip: TextView? = null
    private var randomChip: TextView? = null
    private var filterChip: TextView? = null
    private var clearChip: TextView? = null

    /** 琛ラ綈瀹屾垚鍓?*椤舵浛鏁磋**鐨勯偅鏋氳鏄?chip锛岃 [renderChipsWhileIncomplete]銆?*/
    private var syncingChip: TextView? = null

    /** 銆愪复鏃堵疯皟璇曘€戙€屾ā鎷熼噸寤恒€嶈彍鍗曢」锛涗粎 debug 鍖呭垱寤猴紝瑙?[renderRebuildSimItem]銆?*/
    private var rebuildSimItem: MenuItem? = null

    private var pendingSearch: Runnable? = null
    private var destroyed = false

    /**
     * 瑙﹀彂鎹㈡潯浠舵椂鍒楄〃鎵€澶勭殑銆屼唬鍙枫€嶃€傜瓑鍒版彁浜や笂鏉ョ殑浠ｅ彿**鍙樹簡**锛? 鏂颁竴浠ｇ湡鐨勮惤鍦颁簡锛?
     * 鎵嶆妸鍒楄〃鎷ㄥ洖椤堕儴锛岃 [applyFilterChange] / [onListCommitted]銆?
     */
    private var resetAfterGeneration: Int? = null

    /**
     * 涓婁竴娆＄湅鍒扮殑搴撳唴琛屾暟锛岀敤鏉ヨ鍑恒€屽簱閲屽鍑轰簡涓滆タ銆嶃€?
     *
     * 瀹冩槸**褰撳墠杩欎釜涔︽灦**鐨勮鏁帮紝鎵€浠ユ崲涔︽灦鏃跺繀椤绘竻闆讹紙瑙?[switchShelf]锛夛細涓嶆竻鐨勮瘽锛?
     * 鏂颁功鏋剁殑绗竴娆¤鏁颁細琚綋鎴愩€屽鍑烘潵鐨勪笢瑗裤€嶁€斺€斾粠 31 琛岀殑鎮勬倓鏀惰棌鍒囧埌 1012 琛岀殑鍏紑
     * 鏀惰棌锛岀湅璧锋潵灏辨槸銆屽嚟绌哄浜?981 鏉°€嶏紝浜庢槸鍒氬垏瀹岀珛鍒诲張閲嶆煡涓€閬嶃€?
     */
    private var lastKnownStored: Int? = null

    /** 鏈€杩戜竴娆℃嬁鍒扮殑鍏ㄩ儴涔︽灦鐘舵€侊紱鍒囦功鏋舵椂鎸夊綋鍓?shelfKey 閲嶆柊鎸戜竴鏉″嚭鏉ャ€?*/
    private var latestStates: List<BookmarkMirrorStateEntity> = emptyList()

    private val context get() = fragment.requireContext()

    private val isIllust get() = contentType == MirrorContentType.ILLUST

    /**
     * 杩欎釜涔︽灦鍦ㄦ湰鍦?*琛ラ綈瀹屾垚**浜嗗悧 鈥斺€?涔熷氨鏄€岃繖浠借〃鑳戒笉鑳藉綋鍏ㄩ噺鐢ㄣ€嶃€?
     *
     * 娉ㄦ剰杩欒窡銆岃兘涓嶈兘杩涜繖涓〉闈€嶆槸涓や欢浜嬶細杩涢棬鐢?`BookmarkMirrorService.isShelfRegistered`
     * 鍐冲畾锛堟敞鍐岃繃灏辫繘锛屽摢鎬曞彧鏈変竴椤碉級锛岃繖閲屽喅瀹氱殑鏄?*绛涢€?/ 鎺掑簭 / 鎼滅储鑳戒笉鑳藉紑**銆?
     *
     * 琛ラ綈鏈熼棿琛ㄩ噷鍙湁"鏈€鏂扮殑涓€娈?锛屾寜瀹冪瓫鍑烘潵鐨勪笉鏄?灏戝嚑鏉?鑰屾槸**閿欑殑**锛堝€掑簭灏ゅ叾浼氱粰鍑轰竴涓?
     * 鐪嬭捣鏉ュ緢鏉冨▉鐨勯敊璇瓟妗堬級锛屾墍浠ラ偅娈垫椂闂寸瓫閫夊叆鍙ｆ暣涓敹璧帮紝鍙暀榛樿鎺掑簭娴忚 + 椤堕儴杩涘害鏉?鈥斺€?
     * 瑙?[renderChipsWhileIncomplete] 涓?[resetConditionsForSyncing]銆?
     *
     * state 鏈埌锛堥甯э級鏃惰繑鍥?false锛屼絾璋冪敤鏂硅鍏堝垽 null 鍐嶅喅瀹氬姩涓嶅姩 UI锛堣 [renderChips]锛夈€?
     */
    private fun isShelfComplete(): Boolean = viewModel.mirrorState.value?.isFirstSyncDone == true

    // 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€ 瑁呴厤 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    fun install() {
        setUpToolbar()
        setUpMenu()
        setUpShelfSwitch()
        setUpSearch()
        setUpChips()
        observeState()

        setUpPullToRefresh()

        // 鎵撳紑鏀惰棌搴撴湰韬氨鏄竴娆°€岀敤鎴峰湪鐪嬭繖涓功鏋躲€嶇殑淇″彿锛氳寮曟搸椹笂琛ヤ竴娆″閲忥紝
        // 鑰屼笉鏄瓑涓嬩竴涓緥琛岀獥鍙ｃ€傝ˉ鐨勮繃绋嬮潤榛橈紝椤甸潰鐓у父鐢ㄦ湰鍦版暟鎹€?
        context.appServices().bookmarkMirror.ensureShelf(viewModel.shelf, reason = "鎵撳紑鏀惰棌搴?)
    }

    /**
     * 椤甸潰閲嶆柊鍥炲埌鍓嶅彴銆?*蹇呴』鍦?onResume 涓婂啀瀵逛竴娆?*锛屼笉鑳藉彧鍦?onViewCreated锛?
     * 銆屽湪缃戦〉绔敹钘忓嚑寮?鈫?鍒囧洖 app 鐪嬬湅銆嶆槸鏈€鍏稿瀷鐨勫姩浣滐紝鑰岄偅涓潵鍥炴牴鏈笉浼氶攢姣佹湰椤电殑
     * view 鈥斺€?鍙寕鍦?install 涓婄殑璇濓紝鍥炴潵鐪嬪埌鐨勮繕鏄蛋涔嬪墠閭ｄ唤锛岀敤鎴风殑缁撹灏辨槸
     * 銆岀綉椤电鏀惰棌鐨勪笢瑗?app 閲岀湅涓嶅埌銆嶃€?
     */
    fun onResumed() {
        if (destroyed) return
        context.appServices().bookmarkMirror.ensureShelf(viewModel.shelf, reason = "鍥炲埌鏀惰棌搴?)
    }

    /**
     * 涓嬫媺鍒锋柊 = **鍘绘湇鍔＄瀵逛竴娆¤〃澶?* + 閲嶆煡鏈湴銆?
     *
     * 妗嗘灦榛樿鍙仛鍚庤€咃紙`feedViewModel.refresh()`锛夛紝瀵规湰椤垫潵璇寸瓑浜庝粈涔堥兘娌″仛锛氭湰鍦版簮
     * 璇荤殑灏辨槸闀滃儚琛紝闀滃儚涓嶅姩锛屽啀鏌ヤ竴鐧炬涔熻繕鏄悓涓€鎵广€傝€屻€屼笅鎷夊埛鏂般€嶆伆鎭版槸鐢ㄦ埛鎯宠
     * 銆屾垜鍦ㄥ埆澶勬敹钘忎簡涓滆タ锛屽幓鐪嬬湅銆嶆椂鍞竴浼氬仛鐨勫姩浣?鈥斺€?瀹冨繀椤荤湡鐨勫幓鍚屾锛屽惁鍒欒繖濂?
     * 闀滃儚瀵广€岀綉椤电/鍒殑璁惧涓婄殑鏀惰棌銆嶅氨鏄釜姝昏儭鍚屻€?
     *
     * 鍚屾鏄紓姝ョ殑锛堝彈鍏ㄥ眬闄愰€燂紝绾?10 绉掍袱椤碉級锛屾墍浠ヤ笅鎷夌殑杞湀浼氬厛闅忔湰鍦伴噸鏌ュ仠涓嬶紱
     * 鏂版敹钘忚惤搴撳悗鐢?[refreshIfStale] 鎺ユ墜涓婂睆銆?
     */
    private fun setUpPullToRefresh() {
        val refreshLayout = binding.feedRoot.feedRefreshLayout
        refreshLayout.setOnRefreshListener {
            context.appServices().bookmarkMirror.syncNow(viewModel.shelf, reason = "涓嬫媺鍒锋柊")
            applyFilterChange()
        }
    }

    fun destroy() {
        destroyed = true
        pendingSearch?.let { binding.searchInput.removeCallbacks(it) }
        pendingSearch = null
        sortChip = null
        reverseChip = null
        randomChip = null
        filterChip = null
        clearChip = null
    }

    // 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€ 瀵瑰 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    /**
     * 鎹㈢瓫閫?/ 鎺掑簭 / 涔︽灦涔嬪悗閲嶆柊鍑哄垪琛ㄣ€?
     *
     * **涓嶈兘鐢?`FeedFragment.forceRefresh()`**锛氬畠鍏?`smoothScrollToPosition(0)` 鍐嶅埛鏂帮紝
     * 鑰屽钩婊戞粴鍔ㄥ甫鍔ㄧ敾銆佺粨鏉熸椂鏈鸿窡鏂颁竴浠ｆ暟鎹惤鍦版槸涓ゅ洖浜嬶紱鏇磋绱х殑鏄鏋跺彧鍦ㄣ€屾柊鏃т袱浠?
     * 鐪熺殑浼氭挄銆嶆椂鎵嶉『甯︽竻 LayoutManager 鐨勮法浠ｆ畫鐣欙紝璧?DiffUtil 閭ｆ潯鍒嗘敮灏变笉娓呫€傝€屾湰椤?
     * 姣忔鎹㈡潯浠堕兘鏄暣浠ｆ浛鎹紝涓婁竴浠ｇ暀鍦?SGLM 閲岀殑 span 鍋忕Щ浼氳鏂颁竴浠ｉ灞?*宸﹀垪椤堕儴绌哄嚭
     * 涓€澶у潡**锛堢湡鏈烘埅鍥惧鐜拌繃锛夈€傛墍浠ヨ繖閲岃涓嬪綋鍓嶄唬鍙凤紝绛夋柊涓€浠ｇ湡姝ｆ彁浜ゅ畬鍐嶇‘瀹氭€у湴澶嶄綅銆?
     */
    fun applyFilterChange() {
        resetAfterGeneration = feedViewModel.uiState.value.refreshGeneration
        feedViewModel.refresh()
    }

    fun onListCommitted(state: FeedUiState) {
        // 璁や唬鍙疯€屼笉鏄涓€涓竷灏旀爣璁帮細onListCommitted 姣忔鎻愪氦閮戒細鏉ワ紝**鍖呮嫭寰€涓嬫粦杩藉姞鐨勯〉**銆?
        // 鐢ㄥ竷灏旂殑璇濓紝鍙鏈変竴娆°€岀疆浜嗘爣璁颁絾閭ｄ竴浠ｅ埛鏂版病鎻愪氦涓婃潵銆嶏紙杩炵画蹇垏鏃跺墠涓€娆?refresh
        // 浼氳鍚庝竴娆?cancel锛夛紝娈嬬暀鐨?true 灏变細琚笅涓€娆¤拷鍔犻〉娑堣垂鎺?鈥斺€?鐢ㄦ埛姝ｆ粦鍒颁竴鍗婏紝
        // 鍒楄〃绐佺劧琚嫧鍥為《閮ㄣ€備唬鍙峰彉浜嗘墠鍔ㄦ墜锛岃拷鍔犻〉鐨勪唬鍙蜂笉鍙橈紝缁濅笉浼氳浼ゃ€?
        val target = resetAfterGeneration ?: return
        if (state.refreshGeneration == target) return
        resetAfterGeneration = null
        when (val manager = listView.layoutManager) {
            is StaggeredGridLayoutManager -> {
                // invalidateSpanAssignments 鏄敮涓€鑳芥竻鎺変笂涓€浠?span 鍋忕Щ鐨勫叕寮€ API锛?
                // scrollToPositionWithOffset(0,0) 鎶婂亸绉讳篃瀹氭锛屽惁鍒欎細鎷挎棫 anchor 鍑戜竴涓?
                // 鍋忕Щ鍑烘潵锛屽埛瀹屽仠鍦ㄣ€岄《閮ㄥ亸涓嬨€嶃€?
                manager.invalidateSpanAssignments()
                manager.scrollToPositionWithOffset(0, 0)
            }
            is LinearLayoutManager -> manager.scrollToPositionWithOffset(0, 0)
            else -> manager?.scrollToPosition(0)
        }
    }

    /**
     * 绌烘€佽鍒嗘竻涓夌銆岀┖銆嶏紝涓嶇劧鐢ㄦ埛娌℃硶鐭ラ亾璇ョ瓑杩樻槸璇ユ敼鏉′欢锛?
     * 鏉′欢绛涙病浜?/ 闀滃儚杩樻病琛ュ埌杩欎釜涔︽灦 / 鏄湡鐨勪竴浠堕兘娌℃敹钘忋€?
     */
    fun emptyStateText(): CharSequence = when {
        viewModel.filter.value.hasAnyCondition ->
            context.getString(R.string.bookmark_library_empty_filtered)
        viewModel.mirrorState.value?.isFirstSyncDone == false ->
            context.getString(R.string.bookmark_library_empty_syncing)
        else -> context.getString(R.string.bookmark_library_empty)
    }

    // 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€ 鎺ョ嚎 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    /**
     * fragment_toolbar_feed 閭ｅ AppCompat toolbar 鐨勮嚜瑁呯増锛坄setUpToolbar` 鍙悆
     * FragmentToolbarFeedBinding锛屾湰椤垫湁鑷繁鐨勯鏋讹級銆?
     * BaseActivity 寮€浜?EdgeToEdge锛氱姸鎬佹爮 inset 璧?BarUtils 鎵嬪姩 padding锛屼笉鐢?
     * fitsSystemWindows锛堜細鎶?status + nav 涓や釜 inset 閮藉綋 padding 濂椾笂锛夛紱
     * 搴曢儴瀵艰埅鏍忕殑楂樺害璁╃粰鍒楄〃锛屼笉鐒舵渶鍚庝竴鎺掑崱鐗囧帇鍦ㄦ墜鍔挎潯搴曚笅銆?
     */
    private fun setUpToolbar() {
        binding.toolbar.updatePadding(top = BarUtils.getStatusBarHeight())
        binding.toolbar.setNavigationOnClickListener { fragment.requireActivity().finish() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            listView.updatePadding(0, 0, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setUpMenu() {
        // 銆屽師濮嬫敹钘忓垪琛ㄣ€嶆槸鐣欑粰銆屾垜瑕佺湅鏈嶅姟绔師鏈殑鏍峰瓙銆嶇殑閫€璺細鏈〉灞曠ず鐨勬槸鏈湴闀滃儚
        //锛堥『搴忔槸鏈湴閲嶆帓鐨勶紝鍐呭鍙栫殑鏄暅鍍忔椂鍐荤粨鐨勫揩鐓э級銆傚叕寮€/绉佷汉鏈〉宸茬粡鑳界洿鎺ュ垏锛?
        // 鎵€浠ヨ繖鏉￠€€璺彧涓恒€屽師搴?+ 鏈€鏂般€嶈繖涓ょ偣瀛樺湪銆?
        binding.toolbar.menu.add(0, MENU_CLASSIC, 0, R.string.bookmark_library_open_classic)
        binding.toolbar.menu.add(0, MENU_REBUILD, 1, R.string.bookmark_library_rebuild)
        // 銆愪复鏃堵疯皟璇曘€戞ā鎷熼噸寤猴紝浠?debug 鍖咃細涓嶆媺 pixiv锛屽彧鎶婅鎼埌澶囦唤 key 涓嬶紝鐢ㄦ潵楠?
        // 銆岃ˉ榻愪腑銆嶉偅濂?UI锛堣繘搴︽潯 + 绛涢€夋敹璧?+ 灏鹃儴缁〉锛夈€傚啀鐐逛竴娆¤繕鍘熴€?
        // 鍙湪銆屾紨寰楀姩銆嶇殑鏃跺€欓湶鍑烘潵锛氬凡琛ラ綈瀹屾垚鐨勪功鏋讹紙鍙互婕旀竻绌猴級鎴栨澶勫湪妯℃嫙鎬侊紙鍙互杩樺師锛夈€?
        if (ceui.lisa.BuildConfig.DEBUG) {
            rebuildSimItem = binding.toolbar.menu.add(
                0, MENU_REBUILD_SIM, 2, R.string.bookmark_library_rebuild_sim,
            )
        }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_CLASSIC -> {
                    openClassicCollection()
                    true
                }
                MENU_REBUILD -> {
                    rebuildMirror()
                    true
                }
                MENU_REBUILD_SIM -> {
                    toggleSimulatedRebuild()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 銆愪复鏃堵疯皟璇曘€戞ā鎷熼噸寤?/ 杩樺師鐨勫垏鎹€傚垽鏂緷鎹槸 state 琛岄噷鐨?phase锛堣
     * [DebugMirrorRebuildSim.isSimulated]锛夛紝鎵€浠ヨ繘绋嬮噸鍚€侀€€鍑洪噸杩涢兘涓嶄細璁ら敊銆?
     */
    private fun toggleSimulatedRebuild() {
        val shelf = viewModel.shelf
        // 鍒绘剰涓?kick 寮曟搸锛氳繖涓伐鍏蜂粠澶村埌灏?*涓嶄骇鐢熶换浣曠綉缁滆姹?*锛堟ā鎷熸槸涓嶆媺 pixiv锛?
        // 杩樺師涔熷彧鏄妸琛屾惉鍥炴潵锛夈€傝繕鍘熷悗瑕佷笉瑕佸仛澧為噺缁存姢锛屼氦缁欏紩鎿庤嚜宸辩殑渚嬭鑺傚銆?
        // onChanged锛氬亣鍥炲～姣忔惉鍥炰竴鎷嶏紙浠ュ強缁撴潫锛夐兘鍥炶皟涓€娆★紝鎶婂彉鍖栧杺鍥炶鏁?鍒楄〃锛?
        // 璁╄繘搴︿笌鍒楄〃璺熺潃娑?鈥斺€?涓嶄緷璧?Room 澶辨晥鐨勬椂鏈猴紝鑺傚鏇寸ǔ銆?
        val onChanged: () -> Unit = {
            binding.root.post {
                if (!destroyed) {
                    viewModel.onMirrorChanged()
                }
            }
        }
        if (DebugMirrorRebuildSim.isSimulated(viewModel.mirrorState.value)) {
            Timber.tag(TAG).i("銆愯皟璇曘€戣繕鍘熸ā鎷熼噸寤?%s", shelf.label)
            DebugMirrorRebuildSim.restore(context, shelf, onChanged)
        } else {
            Timber.tag(TAG).i("銆愯皟璇曘€戞ā鎷熼噸寤?%s锛堜笉鎷?pixiv锛?, shelf.label)
            DebugMirrorRebuildSim.simulate(context, shelf, onChanged)
        }
    }

    /**
     * 鑿滃崟閲岄偅鏉¤皟璇曞叆鍙ｇ殑鏂囨涓庡彲瑙佹€с€傛斁鍦?[applyMirrorState] 涔嬪悗璋冿紝鐘舵€佷竴鍙樺氨璺熺潃缈伙細
     * 琛ラ綈涓笖娌″湪妯℃嫙鎬?鈫?钘忚捣鏉ワ紙娌℃湁鍙紨鐨勪笢瑗匡級锛涘叾浣?鈫?銆屾ā鎷熼噸寤恒€?銆岃繕鍘熴€嶄簩閫変竴銆?
     */
    private fun renderRebuildSimItem() {
        val item = rebuildSimItem ?: return
        val simulated = DebugMirrorRebuildSim.isSimulated(viewModel.mirrorState.value)
        item.isVisible = simulated || isShelfComplete()
        item.title = context.getString(
            if (simulated) {
                R.string.bookmark_library_rebuild_sim_restore
            } else {
                R.string.bookmark_library_rebuild_sim
            }
        )
    }

    /**
     * 鎵撳紑鍘熷鐨勫弻 tab 鏀惰棌椤点€傚甫 [Params.FLAG] 鏍囪锛岄偅杈规嵁姝?*涓嶅啀**鎶婂叆鍙ｉ噸瀹氬悜鍥炴湰椤碉紝
     * 鍚﹀垯鐢ㄦ埛浠庢湰椤电偣杩涘幓浼氳绔嬪埢寮瑰洖鏉ワ紝涓や釜椤甸潰浜掔浉韪㈢毊鐞冦€?
     */
    private fun openClassicCollection() {
        val route = if (isIllust) TemplateRoute.MY_ILLUST_COLLECTION else TemplateRoute.MY_NOVEL_COLLECTION
        fragment.startActivity(
            Intent(context, TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, route.key)
                putExtra(Params.FLAG, true)
            }
        )
    }

    private fun rebuildMirror() {
        val shelf = viewModel.shelf
        Timber.tag(TAG).i("鐢ㄦ埛鎵嬪姩閲嶅缓闀滃儚 %s", shelf.label)
        context.appServices().bookmarkMirror.rebuildShelf(shelf)
        // **鍒绘剰涓嶅湪杩欓噷鍒锋柊**锛氭竻绌哄彂鐢熷湪 rebuildShelf 鑷繁鐨勫崗绋嬮噷锛岃繖閲岀珛鍒诲埛鍙細
        // 璇诲埌娓呯┖鍓嶇殑鏁版嵁锛堢湡鏈哄鐜拌繃锛氭竻绌?2 琛屼箣鍚?2ms锛岄偅娆℃煡璇㈣鍒扮殑杩樻槸 2 琛岋級銆?
        // 浜ょ粰 [refreshIfStale] 鈥斺€?娓呯┖钀藉簱鍚?totalCount 浼氭帀鍒?0锛屽畠鑷劧浼氭妸灞忓箷瀵归綈锛?
        // 鍥炲～琛ヨ繘鏉ヤ箣鍚庡啀瀵归綈涓€娆°€傚皯涓€娆℃姠璺戠殑鍒锋柊锛屼篃灏卞皯涓€涓渶瑕佸厹鐨勬椂搴忋€?
    }

    /**
     * 鍏紑 / 鎮勬倓鏀惰棌鍒囨崲銆傜偣銆屾倓鎮勬敹钘忋€嶆湰韬氨鏄竴娆℃槑纭殑鐢ㄦ埛鎰忓浘锛屾墍浠ラ『甯︽妸閭ｄ釜涔︽灦
     * 娉ㄥ唽杩涢暅鍍忥紙`trackBookmarkShelfVisit` 鍚屾鐨勯殣绉佽竟鐣岋細娌′富鍔ㄧ湅杩囧氨涓嶄細鍘绘媺锛夈€?
     */
    private fun setUpShelfSwitch() {
        binding.shelfPublic.setText(if (isIllust) R.string.public_like_illust else R.string.public_like_novel)
        binding.shelfPrivate.setText(if (isIllust) R.string.private_like_illust else R.string.private_like_novel)
        binding.shelfPublic.setOnClickListener { switchShelf(MirrorRestrict.PUBLIC) }
        binding.shelfPrivate.setOnClickListener { switchShelf(MirrorRestrict.PRIVATE) }
        renderShelfSwitch()
    }

    private fun switchShelf(restrict: MirrorRestrict) {
        val current = viewModel.shelf
        if (current.restrict == restrict) return
        val next = current.copy(restrict = restrict)
        if (!viewModel.switchShelf(next)) return
        // 琛屾暟鍩哄噯璺熺潃涔︽灦璧帮細鎹簡涔︽灦锛屼笂涓€涓功鏋剁殑琛屾暟灏变笉鍐嶆槸姣旇緝鐨勫弬鐓х墿
        lastKnownStored = null
        binding.searchInput.setText("")
        context.appServices().bookmarkMirror
            .ensureShelf(next, reason = "鏀惰棌搴撳垏鎹㈠埌${next.restrict.apiValue}")
        renderShelfSwitch()
        renderChips()
        applyMirrorState()
        // 鍒囧埌鐨勪功鏋跺鏋滆繕娌¤ˉ榻愶紙鍚?鍒氭敞鍐屻€佸洖濉兘杩樻病璺?锛夛細鎺掑簭涔熻鍥為粯璁ゃ€?
        // BookmarkLibraryViewModel.switchShelf 鍒绘剰淇濈暀鎺掑簭锛屼絾琛ラ綈鏈熼棿杩炴帓搴忛兘寰楅攣姝伙紝
        // 鐞嗙敱瑙?resetConditionsForSyncing锛涙潯浠堕偅杈?VM 宸茬粡娓呭共鍑€浜嗭紝杩欓噷涓嶇敤鍐嶅姩銆?
        // 鏀惧湪 applyMirrorState() 涔嬪悗锛氳繖涓€姝ユ墠鎷垮埌鏂颁功鏋剁殑鐘舵€併€?
        if (!isShelfComplete()) {
            viewModel.updateFilter { it.copy(sort = BookmarkSort.BOOKMARK_NEWEST) }
        }
        applyFilterChange()
    }

    private fun renderShelfSwitch() {
        val isPublic = viewModel.shelf.restrict == MirrorRestrict.PUBLIC
        // 鍒嗘鎸夐挳鐨?drawable / 瀛楄壊閫夋嫨鍣ㄨ鐨勬槸 state_selected锛屼笉鏄?activated
        binding.shelfPublic.isSelected = isPublic
        binding.shelfPrivate.isSelected = !isPublic
    }

    private fun setUpSearch() {
        val input = binding.searchInput
        input.setText(viewModel.filter.value.keyword)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val keyword = s?.toString().orEmpty()
                pendingSearch?.let { input.removeCallbacks(it) }
                val task = Runnable {
                    if (destroyed) return@Runnable
                    if (viewModel.updateFilter { it.copy(keyword = keyword) }) applyFilterChange()
                }
                pendingSearch = task
                input.postDelayed(task, SEARCH_DEBOUNCE_MS)
            }
        })
    }

    private fun setUpChips() {
        val row = binding.chipRow
        row.removeAllViews()

        sortChip = addChip(row, "") { BookmarkFilterSheet.show(fragment) }
        // 鍊掑簭鏄湰椤靛瓨鍦ㄧ殑鐩存帴鐞嗙敱锛?1323锛夛紝蹇呴』鏄竴閿紝涓嶈兘鍩嬭繘闈㈡澘
        reverseChip = addChip(row, context.getString(R.string.bookmark_chip_oldest_first)) {
            val next = if (viewModel.filter.value.sort == BookmarkSort.BOOKMARK_OLDEST) {
                BookmarkSort.BOOKMARK_NEWEST
            } else {
                BookmarkSort.BOOKMARK_OLDEST
            }
            if (viewModel.updateFilter { it.copy(sort = next) }) applyFilterChange()
        }
        randomChip = addChip(row, context.getString(R.string.bookmark_chip_random)) {
            // 宸茬粡鍦ㄩ殢鏈烘€佹椂鍐嶇偣 = 閲嶆柊娲楃墝锛屾墍浠ョ瀛愭瘡娆￠兘鎹?
            val alreadyRandom = viewModel.filter.value.sort.isRandom
            val changed = viewModel.updateFilter {
                if (alreadyRandom) {
                    it.copy(randomSeed = System.currentTimeMillis())
                } else {
                    it.copy(sort = BookmarkSort.RANDOM, randomSeed = System.currentTimeMillis())
                }
            }
            if (changed) applyFilterChange()
        }
        filterChip = addChip(row, context.getString(R.string.bookmark_chip_filter)) {
            BookmarkFilterSheet.show(fragment)
        }
        clearChip = addChip(row, context.getString(R.string.bookmark_chip_clear)) {
            binding.searchInput.setText("")
            if (viewModel.clearConditions()) applyFilterChange()
        }
        // 琛ラ綈瀹屾垚鍓嶉《鏇挎暣琛岀殑閭ｆ灇璇存槑銆備笉鍙偣锛堝畠涓嶆槸鎿嶄綔锛屾槸涓€鍙ョ姸鎬佽鏄庯級锛岄粯璁ら殣钘忋€?
        syncingChip = addChip(row, context.getString(R.string.bookmark_chip_syncing)) {}.apply {
            isClickable = false
            isFocusable = false
            visibility = View.GONE
        }
        renderChips()
    }

    private fun addChip(row: ViewGroup, text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(context.resources.getColorStateList(R.color.bookmark_chip_text, null))
            setBackgroundResource(R.drawable.bg_bookmark_chip)
            updatePadding(
                left = DensityUtil.dp2px(14f), right = DensityUtil.dp2px(14f),
                top = DensityUtil.dp2px(7f), bottom = DensityUtil.dp2px(7f),
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.rightMargin = DensityUtil.dp2px(8f) }
            row.addView(this)
        }

    private fun renderChips() {
        // 鐘舵€佽繕娌″埌锛堥甯э級锛氫粈涔堥兘涓嶅姩锛屼繚鎸?setUpChips 鐢诲嚭鏉ョ殑榛樿琛屻€?
        // 涓嶅厛鎸?琛ラ綈涓?鐢讳竴娆★紝鏄洜涓虹粷澶у鏁版墦寮€鐨勬槸**宸茬粡琛ラ綈**鐨勪功鏋?鈥斺€?璁╁畠浠棯涓€涓?
        // "琛ラ綈涓?姣旇缃曡鐨勯甯у鍑犳绉掑彲鐢ㄧ獥鍙ｆ洿绯燂紙杩欐鏃堕棿鐢ㄦ埛涓嶅彲鑳界偣寮€闈㈡澘鏀规潯浠讹級銆?
        val mirrorState = viewModel.mirrorState.value ?: return
        if (!mirrorState.isFirstSyncDone) {
            renderChipsWhileIncomplete()
            return
        }
        syncingChip?.visibility = View.GONE
        binding.searchInput.isEnabled = true

        val filter = viewModel.filter.value
        // 鏉′欢琚埆澶勬竻绌轰簡锛堢瓫閫夐潰鏉块噷鐨勩€屾竻绌恒€嶏級锛屾悳绱㈡瑕佽窡鐫€绌烘帀銆?
        // 鍙仛銆屾竻绌恒€嶈繖涓€涓柟鍚戙€佷笖鍙湪妗嗛噷纭疄杩樻湁瀛楁椂鍔ㄦ墜锛氱粷涓嶆嬁 filter 鍘昏鐩栫敤鎴?
        // 姝ｅ湪鏁茬殑鍐呭 鈥斺€?杈撳叆闃叉姈鏈熼棿 filter 鏍规湰涓嶄細鍙戝皠锛屾墍浠ヨ繖閲屼篃涓嶄細鍜屾墦瀛楁墦鏋躲€?
        val input = binding.searchInput
        if (filter.keyword.isEmpty() && input.text.isNotEmpty()) input.setText("")

        sortChip?.visibility = View.VISIBLE
        reverseChip?.visibility = View.VISIBLE
        randomChip?.visibility = View.VISIBLE
        filterChip?.visibility = View.VISIBLE
        sortChip?.text = context.getString(sortLabelRes(filter.sort))
        // 鎺掑簭 chip 鍙湪銆屼笉鏄粯璁ゆ帓搴忋€嶆椂鐐逛寒锛氶粯璁ゆ€佺偣浜竴鐗囷紝閫変腑鎬佸氨涓嶅啀鏄俊鎭簡
        sortChip?.isActivated = filter.sort != BookmarkSort.BOOKMARK_NEWEST
        reverseChip?.isActivated = filter.sort == BookmarkSort.BOOKMARK_OLDEST
        randomChip?.isActivated = filter.sort.isRandom

        val conditions = countConditions(filter)
        filterChip?.text = if (conditions > 0) {
            context.getString(R.string.bookmark_chip_filter_count, conditions)
        } else {
            context.getString(R.string.bookmark_chip_filter)
        }
        filterChip?.isActivated = conditions > 0
        // 銆屾竻绌恒€嶅彧鍦ㄧ湡鏈変笢瑗垮彲娓呮椂鍑虹幇锛氬父椹讳竴涓案杩滅伆鐫€鐨勬寜閽彧鏄櫔闊?
        clearChip?.visibility = if (filter.hasAnyCondition) View.VISIBLE else View.GONE
    }

    /**
     * 琛ラ綈鏈熼棿閭ｄ竴琛?chip锛氱瓫閫?/ 鎺掑簭 / 娓呯┖鍏ㄩ儴鏀惰蛋锛屽彧鐣欎竴鏋氳鏄庯紝鎼滅储妗嗙鐢ㄣ€?
     *
     * 涓嶇敤"缃伆"锛歚bookmark_chip_text` 杩欎釜 selector 娌℃湁 disabled 鎬侊紙鍙湁 activated锛夛紝缃伆
     * 绛変簬瑙嗚涓婃病鍙樺寲 鈥斺€?鐐逛簡娌″弽搴斿嵈鐪嬩笉鍑轰负浠€涔堬紝鏄渶绯熺殑涓€绉嶇鐢ㄣ€?
     *
     * 鏀惰蛋瀹冧滑鐨勫師鍥犱笉鏄?缁撴灉浼氬皯鍑犳潯"锛岃€屾槸**缁撴灉浼氭槸閿欑殑**锛氬€掑簭鐨勬壙璇烘槸"鏈€鏃╂敹钘忕殑"锛?
     * 鑰岃ˉ榻愭湡闂村畠鍙兘缁欏嚭"宸查暅鍍忛儴鍒嗛噷鏈€鏃╃殑"锛岃繕浼氶殢鐫€鍥炲～姣?5 绉掑彉涓€娆°€?
     */
    private fun renderChipsWhileIncomplete() {
        listOfNotNull(sortChip, reverseChip, randomChip, filterChip, clearChip)
            .forEach { it.visibility = View.GONE }
        syncingChip?.visibility = View.VISIBLE
        val input = binding.searchInput
        if (input.text.isNotEmpty()) input.setText("")
        input.isEnabled = false
    }

    /**
     * 鍥炲埌銆岃ˉ榻愭湡闂寸殑鍞竴鍚堟硶瑙嗗浘銆嶏細榛樿鎺掑簭锛堟敹钘忔椂闂?鏂扳啋鏃э級+ 绌烘潯浠躲€?
     *
     * 涓ゅ閮借鍔紝鍥犱负 [BookmarkLibraryViewModel.clearConditions] 鍒绘剰**淇濈暀鎺掑簭**锛堟帓搴忔槸
     * "鎴戞兂鎬庝箞鐪?锛夛紱鑰岃ˉ榻愭湡闂磋繛鎺掑簭閮藉緱閿佹鍦ㄩ粯璁ら偅涓€妗ｏ細
     * [BookmarkSort.BOOKMARK_NEWEST] 璧?`bookmarkSeq DESC`锛屽畠鏄叏搴忎笖鍞竴閿紝鍥炲～鐨勬柊琛屽彧浼?
     * 杩藉姞鍦?*灏鹃儴** 鈫?鏈湴 offset 鍒嗛〉鐨勫凡鍔犺浇鍓嶇紑涓嶄細婕傘€傛崲鎴愬€掑簭 / 鏃堕棿 / 浜烘皵浠讳綍涓€妗ｏ紝
     * 琛ラ綈鏈熼棿鎻掑叆鐨勮浼氭墦涔?offset锛岃〃鐜颁负鐢ㄦ埛寰€涓嬬炕鏃跺垪琛?璺宠繃"鍐呭銆?
     *
     * 銆屽氨缁?鈫?鏈氨缁€嶈繖浠朵簨鏈変袱涓潵婧愶細鈶?鍙充笂瑙掋€岄噸寤烘湰鍦伴暅鍍忋€嶆竻浜?firstCompletedAt锛堣蛋鏈柟娉曪級锛?
     * 鈶?鎹㈠埌涓€涓繕娌″氨缁殑涔︽灦锛圼switchShelf] 鑷繁琛ヤ竴鍙ユ帓搴忓浣?鈥斺€?閭ｈ竟鏉′欢宸茶 VM 娓呮帀锛?
     * 鍙樊鎺掑簭锛夈€?
     */
    private fun resetConditionsForSyncing() {
        val current = viewModel.filter.value
        if (!current.hasAnyCondition && current.sort == BookmarkSort.BOOKMARK_NEWEST) return
        Timber.tag(TAG).d("琛ラ綈涓細鏉′欢涓庢帓搴忓洖榛樿锛堝師 sort=%s锛?, current.sort)
        // 涓€鏉?update 鍚屾椂娓呮潯浠?+ 鍥為粯璁ゆ帓搴忥細鍒嗕袱姝ヤ細鍚勮Е鍙戜竴娆¤鏁般€佸悇鍒蜂竴娆″垪琛ㄣ€?
        val changed = viewModel.updateFilter {
            BookmarkFilter(shelfKey = it.shelfKey, sort = BookmarkSort.BOOKMARK_NEWEST)
        }
        if (changed) applyFilterChange()
    }

    /**
     * 杩樿兘涓嶈兘鑷姩寰€鍚庣画椤点€備袱涓椄闂ㄩ兘寰楃湅锛?
     * - `append` 涓嶆槸 Idle锛氬凡缁忔湁涓€椤靛湪椋烇紝鍒彃闃燂紙[FeedViewModel.loadMore] 鑷繁涔熶細鎸★級锛?
     * - `appendPaused`锛氱敤鎴峰凡缁忚繛鐫€缈绘弧涓€浠介绠楋紙[BookmarkLibraryFeedSource] 缁欑殑鏄?30 椤?
     *   鈮?1800 浠讹級锛屽墿涓嬬殑蹇呴』鐢辩敤鎴风偣 footer 鎵嶇户缁?鈥斺€?涓嶇劧鍑犱竾鏉′細鎶婂唴瀛樺悆绌裤€?
     */
    private fun canAutoAppend(): Boolean {
        val state = feedViewModel.uiState.value
        return state.append is LoadState.Idle && !state.appendPaused
    }

    /** chip 涓婇偅涓暟瀛楋細鐢ㄦ埛寮€浜嗗嚑涓瓫閫夌淮搴︺€傛帓搴忎笉绠椻€斺€斿畠涓嶅噺灏戠粨鏋溿€?*/
    private fun countConditions(filter: BookmarkFilter): Int {
        var count = 0
        if (filter.keyword.isNotBlank()) count++
        if (filter.tagNames.isNotEmpty()) count++
        if (filter.excludedTagNames.isNotEmpty()) count++
        if (filter.authorIds.isNotEmpty()) count++
        if (filter.workTypes.isNotEmpty()) count++
        if (filter.orientations.isNotEmpty()) count++
        if (filter.ai != AiFilter.ANY) count++
        if (filter.age != AgeFilter.ANY) count++
        if (filter.pages != PageFilter.ANY) count++
        if (filter.validity != ValidityFilter.ANY) count++
        if (filter.minBookmarks != null || filter.maxBookmarks != null) count++
        if (filter.minTextLength != null || filter.maxTextLength != null) count++
        if (filter.createdFromMs != null || filter.createdToMs != null) count++
        if (filter.seriesOnly) count++
        return count
    }

    private fun observeState() {
        val uid = viewModel.shelf.ownerUid
        val mirror = context.appServices().bookmarkMirror
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.filter.collectLatest { renderChips() } }
                launch {
                    // 杩涘害鏉′笂鐨勩€屽凡 N 浠躲€嶈窡鐫€闀滃儚琛屾暟璧帮紱椤哄甫鍦ㄨ繖閲屽垽銆屽睆骞曚笂鐨勫唴瀹规槸涓嶆槸
                    // 宸茬粡杩囨湡銆嶁€斺€斿繀椤绘寕鍦?totalCount 涓婅€屼笉鏄?observeOwnerCount 涓婏紝鍥犱负
                    // 鍚庤€呭彂灏勬椂 refreshCounts 鎵嶅垰鍚姩锛岃鍒扮殑杩樻槸鏃ц鏁般€?
                    viewModel.totalCount.collectLatest {
                        renderSyncBanner()
                        refreshIfStale()
                    }
                }
                launch {
                    mirror.observeState(uid).collectLatest { states ->
                        latestStates = states
                        applyMirrorState()
                    }
                }
                launch {
                    // 鎸?owner 璁㈤槄锛氶〉闈㈠彲浠ュ氨鍦板垏涔︽灦锛屾寜 shelfKey 璁㈢殑璇濇瘡鍒囦竴娆￠兘瑕侀噸璁?
                    mirror.observeOwnerCount(uid).collectLatest {
                        viewModel.onMirrorChanged()
                        renderSyncBanner()
                    }
                }
            }
        }
        // 鎺夌綉 / 鎭㈠鑱旂綉閮借閲嶇敾杩涘害鏉★細鏂囨閲屻€屾鍦ㄨˉ榻?/ 褰撳墠鏃犵綉缁溿€嶈繖涓ゅ彞鐨勭湡鍋囧彧鍙栧喅浜?
        // 缃戠粶锛岃€岀綉缁滃彉鍖栦笉浼氬紩璧烽暅鍍忚〃鎴栫姸鎬佽〃鐨勪换浣曞啓鍏?鈥斺€?涓嶅崟鐙湅鐫€瀹冿紝鐢ㄦ埛鎺夌嚎鍚?
        // 閭ｅ彞銆屾鍦ㄥ悗鍙拌ˉ榻愩€嶄細涓€鐩存寕鍦ㄩ偅鍎块獥浜猴紝鐩村埌鍒殑浜嬩欢纰板阀瑙﹀彂涓€娆￠噸缁樸€?
        context.appServices().networkStateManager.networkState
            .observe(fragment.viewLifecycleOwner) { renderSyncBanner() }
    }

    /**
     * 灞忓箷涓婅繖浠藉垪琛ㄥ拰搴撻噷瀵逛笉涓婁簡 鈫?鑷姩閲嶆煡涓€娆°€?
     *
     * 鍒ゆ嵁鍒绘剰鍙槸**涓よ竟鏉℃暟鐨勫璐?*锛屼笉鎺恒€岄暅鍍忓悓姝ュ埌鍝竴姝ヤ簡銆嶏細
     *
     * - `搴撻噷琚竻绌鸿€屽睆骞曚笂杩樻湁涓滆タ`锛氬垰鐐逛簡銆岄噸寤烘湰鍦伴暅鍍忋€嶁€斺€?`rebuildShelf` 鏄?
     *   fire-and-forget 鐨勶紝娓呯┖鍦ㄥ畠鑷繁鐨勫崗绋嬮噷锛岄〉闈㈣繖杈瑰彧鑳介潬璁℃暟鎺夊埌 0 璁ゅ嚭鏉ャ€?
     *   **涓嶈兘鏀惧鎴愩€屽簱閲?< 灞忓箷銆?*锛氬彇娑堟敹钘忎細鍗虫椂鍒犳帀闀滃儚琛岃€屽崱鐗囩暀鍦ㄥ睆骞曚笂锛堜笌鍘熸敹钘忛〉
     *   涓€鑷达紝鍙炕绌洪偅棰楀績锛夛紝鍒楄〃涓€鏃﹀凡缁忓叏閮ㄥ姞杞藉畬锛堝嚑鍗佹潯鐨勫皬涔︽灦銆佹垨婊戝埌浜嗗簳锛夛紝姣忓彇娑堜竴娆?
     *   閮戒細琚垽鎴愬涓嶄笂锛岀劧鍚?[applyFilterChange] 鎶婄敤鎴蜂粠鍒楄〃娣卞鎷藉洖椤堕儴銆傞偅鍑犲紶鍗＄墖鐣欑潃
     *   灏辨槸鍘熸敹钘忛〉鐨勬棦鏈夎涓猴紝涓嶅€煎緱涓哄畠閲嶆帓鏁翠釜鍒楄〃銆?
     * - `灞忓箷涓虹┖鑰屽簱閲屾湁璐锛氭湰鍦版簮鏌ュ埌 0 琛屾椂杩斿洖鐨?`nextCursor` 鏄?null锛宖eeds 妗嗘灦鎹
     *   鍒ゅ畾銆屽埌搴曚簡銆嶏紝浠庢涓嶅啀闂暟鎹簮瑕佷换浣曚笢瑗裤€傛墍浠ラ暅鍍忚ˉ杩涚涓€鎵逛箣鍚庯紝寰楁湁浜烘帹瀹冧竴鎶娿€?
     *
     * **涓嶈**鍐嶆嬁 `isFirstSyncDone` 褰撴潯浠讹紙涓婁竴鐗堝氨鏍藉湪杩欏効锛夛細瀹冩伆濂藉湪鏈€鍚庝竴椤佃惤搴撶殑
     * 鍚屼竴鏃跺埢缈绘垚 true锛屼簬鏄€岃ˉ榻愪腑涓旂┖銆嶈繖鏉″垽鎹湪鏈€闇€瑕佸畠鐨勯偅涓€鐬棿澶辨晥 鈥斺€?鍥炲～鏄庢槑
     * 瀹屾垚浜嗭紝椤甸潰鍗存案杩滃仠鍦ㄣ€屾鍦ㄨˉ榻愨€︺€嶇殑绌烘€佷笂锛堢湡鏈哄鐜帮級銆傛潯鏁板璐︽病鏈夎繖涓椂搴忕紳闅欍€?
     *
     * 鍙湪涓よ竟鐪熺殑瀵逛笉涓婃椂鍔ㄦ墜锛岀敤鎴风殑婊氬姩浣嶇疆涓嶄細琚棤璋撳湴鎷藉洖椤堕儴銆傛湁绛涢€夋潯浠舵椂涓€寰嬩笉绠★細
     * 閭ｇ銆岀┖銆嶆槸鏉′欢娌＄瓫鍒般€?
     */
    private fun refreshIfStale() {
        if (destroyed) return
        // 宸茬粡鏈変竴娆″埛鏂板湪璺笂锛堟崲涔︽灦 / 鎹㈢瓫閫夊垰鍙戝嚭鍘荤殑閭ｆ锛夛細姝ゅ埢灞忓箷涓婃湰鏉ュ氨鏄棫鐨勶紝
        // 鑰屼笖姝ｅ湪琚慨銆傛嬁杩欎唤蹇呯劧杩囨湡鐨?shown 鍘诲拰鏂板簱姣旓紝鍙細寰楀嚭涓€涓亽鐪熺殑缁撹锛岀劧鍚?
        // 鍐嶆帓涓€娆″浣欑殑鍒锋柊鈥斺€旂湡鏈哄疄娴嬨€屽垏鍒版洿灏忕殑涔︽灦銆嶆瘡娆￠兘鍥犳杩炴煡涓ら亶銆?
        if (resetAfterGeneration != null) return
        if (viewModel.filter.value.hasAnyCondition) return
        val shown = itemCount()
        val stored = viewModel.totalCount.value ?: return
        val previous = lastKnownStored
        lastKnownStored = stored

        val clearedWhileShown = stored == 0 && shown > 0
        val emptyButStored = shown == 0 && stored > 0
        // 搴撻噷澶氬嚭浜嗕笢瑗匡紝鑰岀敤鎴锋鍋滃湪鍒楄〃椤堕儴 鈫?鐩存帴璁╁畠涓婂睆銆?
        // 涓変釜鏉′欢缂轰竴涓嶅彲锛?
        // - **澶氬嚭鏉?*锛堣€屼笉鏄彉灏戯級锛氬彉灏戞槸鍙栨秷鏀惰棌锛屽崱鐗囧師鍦扮暀鐫€锛堣绫绘枃妗ｏ級锛?
        // - **鍋滃湪椤堕儴**锛氶粯璁ゆ帓搴忎笅鏂版敹钘忓氨鎺掑湪鏈€涓婇潰锛岀敤鎴锋鐪嬬潃閭ｅ効锛屾彃杩涘幓鏄粬鏈熷緟鐨勶紱
        //   婊氬埌涓嬮潰鏃朵竴寰嬩笉鍔?鈥斺€?鎶婃鍦ㄦ祻瑙堢殑浜烘嫿鍥為《閮ㄦ瘮鏅氱湅鍒板嚑鏉＄碂寰楀锛?
        // - **宸茬粡琛ラ綈杩囦竴娆?*锛氶娆″洖濉湡鏂拌鏄粠鏂板線鏃т竴璺線**鏈熬**鍔犵殑锛岄《閮ㄦ牴鏈笉浼氬彉锛?
        //   璺熺潃鍒峰彧鏄瘡 5 绉掓妸鏁翠釜鍒楄〃閲嶆帓涓€閬嶇殑鏃犵敤鍔熴€?
        val grewWhileAtTop = previous != null &&
            stored > previous &&
            viewModel.mirrorState.value?.isFirstSyncDone == true &&
            !listView.canScrollVertically(-1)

        // 琛ラ綈鏈熼棿锛氱敤鎴峰凡缁忔粦鍒般€屽凡闀滃儚鐨勬湯灏俱€嶏紝鑰屽簱閲岃繕鍦ㄩ暱锛堟瘡 5 绉掍竴椤碉級銆?
        // 杩欐椂鍒楄〃鐨?鍒板簳"鏄?*鍋囩殑**锛欱ookmarkLibraryFeedSource 鍦ㄤ笉瓒充竴椤碉紙60 鏉★級鏃惰繑鍥?
        // nextCursor=null锛宖eeds 妗嗘灦鏀跺埌灏?latch reachedEnd锛屼箣鍚?loadMore() 涓€寰嬫棭閫€
        // 锛團eedViewModel.kt:238锛夈€俛doptCursor 鏄敮涓€鑳芥妸瀹冭В寮€鐨勫叕寮€鍏ュ彛锛氬畠鍚屾椂鎶婃父鏍囪鎴?
        // "宸插睍绀烘潯鏁?骞舵竻鎺?reachedEnd锛屼簬鏄拷鍔犱粠褰撳墠鏈熬缁х画 鈥斺€?涓嶉噸鎺掋€佷笉鍥為《锛?
        // 鐢ㄦ埛鎺ョ潃寰€涓嬫粦灏辫兘鐪嬪埌鍒氳ˉ杩涙潵鐨勯偅鍑犻〉銆?
        //
        // canAutoAppend() 閭ｄ釜棰勭畻瀹堝崼涓嶈兘鐪侊細adoptCursor 浼氶『鎵嬫妸 appendPaused 涔熸竻鎺夛紝
        // 鏃犺剳璋冪敤绛変簬缁曡繃 maxAutoPages 鐨勫唴瀛橀椄锛堣閭ｈ竟鐨勬敞閲婏級銆?
        val tailStillGrowing = !isShelfComplete() && stored > shown && !listView.canScrollVertically(1)
        if (tailStillGrowing && canAutoAppend()) {
            Timber.tag(TAG).d("琛ラ綈涓笖宸插湪鏈熬锛岀画涓€椤碉紙搴撳唴 %d 琛?/ 灞忓箷 %d 鏉★級", stored, shown)
            feedViewModel.adoptCursor(shown.toString())
            feedViewModel.loadMore()
            return
        }

        if (!clearedWhileShown && !emptyButStored && !grewWhileAtTop) return
        Timber.tag(TAG).d(
            "鍒楄〃涓庡簱瀵逛笉涓婏紝鑷姩閲嶆煡锛堝簱鍐?%d 琛?/ 灞忓箷 %d 鏉★紝鏂板涓婂睆=%b锛?,
            stored, shown, grewWhileAtTop,
        )
        applyFilterChange()
    }

    /** 浠庢渶杩戜竴浠界姸鎬佸垪琛ㄩ噷鎸戝嚭**褰撳墠**涔︽灦閭ｆ潯锛屽杺缁?VM锛屽啀閲嶇敾杩涘害鏉′笌 chip 琛岀殑鍙敤鎬с€?*/
    private fun applyMirrorState() {
        val key = viewModel.shelf.key
        val state = latestStates.firstOrNull { it.shelfKey == key }
        viewModel.setMirrorState(state)
        // 灏辩华 鈫?鏈氨缁紙鍏稿瀷鍦烘櫙锛氬彸涓婅銆岄噸寤烘湰鍦伴暅鍍忋€嶏紝firstCompletedAt 琚竻闆讹級锛氭潯浠朵笌
        // 鎺掑簭杩樼暀鍦?VM 閲岋紝鑰岀瓫閫夊叆鍙ｅ凡缁忚鏀惰蛋 鈥斺€?鐢ㄦ埛浼氱湅鍒颁竴涓?琚瓫杩囥€佸嵈娌℃湁浠讳綍鎺т欢
        // 鑳芥竻鎺?鐨勫垪琛ㄣ€傛墍浠ュ湪杩欎竴鍒诲己鍒跺洖榛樿銆?
        if (state != null && !state.isFirstSyncDone) resetConditionsForSyncing()
        renderSyncBanner()
        renderChips()
        renderRebuildSimItem()
    }

    private fun renderSyncBanner() {
        val state = viewModel.mirrorState.value
        val total = viewModel.totalCount.value ?: 0
        // 琛ラ綈杩囦竴娆′箣鍚庤繖鏉″氨姘歌繙涓嶅啀鍑虹幇 鈥斺€?銆屽悓姝ュ畬鎴愯繃涓€娆★紝浠ュ悗鍙淮鎶ゃ€嶇殑鐣岄潰琛ㄨ揪銆?
        val syncing = state != null && !state.isFirstSyncDone
        binding.syncBanner.visibility = if (syncing) View.VISIBLE else View.GONE
        if (!syncing || state == null) return
        // 绂荤嚎鏃跺紩鎿庢瘡涓?tick 閮界洿鎺ヨ繑鍥?Idle锛堣繛搴撻兘涓嶆煡锛夛紝涓€椤甸兘涓嶄細琛ャ€?
        // 杩欐椂鍊欒繕鎸傜潃銆屾鍦ㄥ悗鍙拌ˉ榻愩€嶅氨鏄湪楠椾汉锛氱敤鎴蜂細浠ヤ负绛変竴浼氬効灏卞ソ锛屽疄闄呰绛夊埌鏈夌綉銆?
        val offline = context.appServices().networkStateManager.networkState.value?.isOnline != true
        binding.syncText.text = when {
            offline -> context.getString(R.string.bookmark_library_sync_offline)
            state.cooldownUntil > System.currentTimeMillis() ->
                context.getString(R.string.bookmark_library_sync_cooldown)
            // 鍒ゆ嵁鏄€?*杩樻病寮€濮?*銆嶈€屼笉鏄€屾鍦ㄥ洖濉€嶏細杩欐潯妯箙鍙湪 firstCompletedAt == 0 鏃?
            // 鎵嶅彲鑳藉嚭鐜帮紝閭ｄ釜绐楀彛閲屾湁鎰忎箟鐨?phase 鍙湁涓や釜 鈥斺€?NEVER锛堟敞鍐屼簡浣嗗紩鎿庤繕娌￠鍒版椿锛?
            // 涔熷氨鏄湡路鎺掗槦锛夊拰 BACKFILLING锛堟鍦ㄤ竴椤甸〉缈伙級銆傚啓鎴?`!= NEVER` 鑰屼笉鏄?
            // `== BACKFILLING`锛屾槸涓轰簡璁?鏈畬鎴愪絾涓嶆槸鎺掗槦"鐨勭姸鎬佷竴寰嬭銆屾鍦ㄨˉ榻愩€嶏細鍚﹀垯浠讳綍
            // 寮曟搸涓嶈鐨?phase锛堜緥濡傝皟璇曞伐鍏峰杩涘幓鐨勬ā鎷熸€侊級閮戒細鎺夎繘銆屽凡鎺掗槦銆嶅苟姘歌繙鍗″湪閭ｅ効銆?
            state.phase != MirrorPhase.NEVER ->
                context.getString(R.string.bookmark_library_syncing, formatCount(total))
            else -> context.getString(R.string.bookmark_library_sync_queued)
        }
        // 杞湀鍙湪鐪熺殑鍦ㄨˉ鐨勬椂鍊欒浆锛涚绾?鍐峰嵈鏃跺仠涓嬫潵锛屽埆璁╀竴涓案杩滆浆鐫€鐨勫湀鏆楃ず銆岄┈涓婂氨濂姐€?
        binding.syncSpinner.visibility =
            if (offline || state.cooldownUntil > System.currentTimeMillis()) View.INVISIBLE else View.VISIBLE
    }

    private fun sortLabelRes(sort: BookmarkSort): Int = when (sort) {
        BookmarkSort.BOOKMARK_NEWEST -> R.string.bookmark_sort_bookmark_newest
        BookmarkSort.BOOKMARK_OLDEST -> R.string.bookmark_sort_bookmark_oldest
        BookmarkSort.CREATED_NEWEST -> R.string.bookmark_sort_created_newest
        BookmarkSort.CREATED_OLDEST -> R.string.bookmark_sort_created_oldest
        BookmarkSort.POPULAR_DESC -> R.string.bookmark_sort_popular_desc
        BookmarkSort.POPULAR_ASC -> R.string.bookmark_sort_popular_asc
        BookmarkSort.VIEWS_DESC -> R.string.bookmark_sort_views_desc
        BookmarkSort.PAGES_DESC -> R.string.bookmark_sort_pages_desc
        BookmarkSort.RATIO_TALLEST -> R.string.bookmark_sort_ratio_tallest
        BookmarkSort.RATIO_WIDEST -> R.string.bookmark_sort_ratio_widest
        BookmarkSort.LENGTH_DESC -> R.string.bookmark_sort_length_desc
        BookmarkSort.LENGTH_ASC -> R.string.bookmark_sort_length_asc
        BookmarkSort.TITLE_ASC -> R.string.bookmark_sort_title_asc
        BookmarkSort.RANDOM -> R.string.bookmark_sort_random
    }

    private fun formatCount(value: Int): String = String.format(Locale.getDefault(), "%,d", value)

    companion object {
        private const val TAG = "BookmarkLibrary"
        private const val SEARCH_DEBOUNCE_MS = 280L
        private const val MENU_REBUILD = 1
        private const val MENU_CLASSIC = 2

        /** 銆愪复鏃堵疯皟璇曘€戞ā鎷熼噸寤猴紙浠?debug 鍖呭垱寤鸿繖涓彍鍗曢」锛夈€?*/
        private const val MENU_REBUILD_SIM = 3

        /** 鍐呭绫诲瀷鍏ュ弬銆?*蹇呴』鐢辫矾鐢辨柟寮曠敤杩欎釜甯搁噺**锛屽埆鍦ㄥ埆澶勫啀鎶勪竴閬嶅瓧闈㈤噺銆?*/
        const val ARG_CONTENT_TYPE = "bookmark_library_content_type"

        /** 浠庡叆鍙傝В鍑烘湰椤佃鐪嬬殑涔︽灦銆?*/
        fun shelfFromArguments(args: android.os.Bundle, ownerUid: Long): BookmarkShelf = BookmarkShelf(
            ownerUid = Params.getUserId(args).takeIf { it > 0L } ?: ownerUid,
            contentType = MirrorContentType.of(args.getInt(ARG_CONTENT_TYPE, 0))
                ?: MirrorContentType.ILLUST,
            restrict = MirrorRestrict.ofApiValue(args.getString(Params.STAR_TYPE)),
        )
    }
}
