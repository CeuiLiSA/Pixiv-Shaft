package ceui.lisa.utils;


import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.ZipUtils;
import ceui.pixiv.witstudio.dialog.WitTipDialog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.VActivity;
import ceui.lisa.cache.UgoiraMetadataCache;
import ceui.lisa.core.Container;
import ceui.lisa.core.PageData;
import ceui.lisa.core.JavaAsync;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustHistoryEntity;
import ceui.lisa.database.MuteEntity;
import ceui.lisa.database.SearchEntity;
import ceui.lisa.file.LegacyFile;
import ceui.lisa.file.OutPut;

import ceui.pixiv.login.PixivOAuthConfig;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.Back;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.FramesBean;
import ceui.lisa.models.GifResponse;
import ceui.lisa.models.MarkedNovelItem;
import ceui.loxia.Novel;
import ceui.lisa.models.TagsBean;
import ceui.pixiv.api.model.AccountResponse;
import ceui.pixiv.api.model.Illust;
import ceui.loxia.User;
import ceui.lisa.viewmodel.AppLevelState;
import ceui.pixiv.services.ServicesProvider;
import ceui.pixiv.utils.IllustDetailSupportKt;
import ceui.pixiv.cache.ObjectPool;
import retrofit2.Call;
import retrofit2.Callback;

import ceui.pixiv.session.SessionManager;
import ceui.pixiv.actions.PixivActions;
import ceui.pixiv.ui.common.IllustMuteStore;

import static com.blankj.utilcode.util.ColorUtils.getColor;
import static com.blankj.utilcode.util.StringUtils.getString;
import ceui.pixiv.ui.navigation.TemplateRoute;

/**
 * A class about Pixiv operations.
 */
public class PixivOperate {

    private static final Map<Long, Back> sBack = new HashMap<>();
    private static final Map<Long, Long> gifEncodingWorkSet = new HashMap<>();
    private static final long reEncodeTimeThresholdMillis = 60 * 1000;

    public static void refreshUserData(Callback<AccountResponse> callback) {
        String refreshToken = SessionManager.INSTANCE.getRefreshToken();
        if (refreshToken == null) return;
        Call<AccountResponse> call = Retro.getAccountTokenApi().newRefreshToken(
                PixivOAuthConfig.PIXIV_ANDROID.getClientId(),
                PixivOAuthConfig.PIXIV_ANDROID.getClientSecret(),
                "refresh_token",
                refreshToken,
                Boolean.TRUE);
        call.enqueue(callback);
    }

    /**
     * 关注。**只是 {@link PixivActions#setUserFollow} 的 legacy 封装**——本地状态（ObjectPool、
     * AppLevelState、LIKED_USER 广播）当帧生效，真正的请求由 PixivActionQueue 限流后发出。
     * <p>
     * 之所以不再自己打接口：这个方法和 PixivActions 都拿 ObjectPool 的关注态当真源，两条路并存时
     * 只要队列还在冷却，「详情页关注 → 列表里取关」就会以相反的顺序落到服务端，最终状态与用户
     * 最后一次操作相反（见 NovelReaderV3ViewModel.toggleBookmark 上那段同类问题的注释）。
     * <p>
     * 成功 toast 一并去掉了：这一刻请求还没发出去，报成功是骗用户，而失败时队列几分钟后还会补一个
     * 「操作失败」的 toast 自相矛盾。反馈由按钮自己的关注态承担，失败时队列会把它拨回去并广播。
     * 埋点与发现画像同样挪到了队列的成功回调里（{@code PixivActionQueue.report}）——发出去就撤不回来的
     * 东西必须等服务端确认。
     */
    public static void postFollowUser(long userID, String followType) {
        PixivActions.setUserFollow(userID, true, followType);
    }

    public static void postUnFollowUser(long userID) {
        PixivActions.setUserFollow(userID, false, Params.TYPE_PUBLIC);
    }

    public static void postLikeDefaultStarType(Illust illustsBean) {
        postLike(illustsBean, PixivActions.defaultBookmarkRestrict(), false, 0);
    }

    public static void postLike(Illust illustsBean, String starType) {
        postLike(illustsBean, starType, false, 0);
    }

    public static void postLike(Illust illustsBean, String starType, boolean showRelated, int index) {
        postLike(illustsBean, starType, showRelated, index, null);
    }

    /**
     * 收藏 / 取消收藏（按 {@code illustsBean} 当前状态取反）。
     *
     * <p>**只是 {@link PixivActions#setIllustBookmark(Illust, boolean, String)} 的 legacy 封装**：
     * bean、ObjectPool 里的两个表示和 LIKED_ILLUST 广播当帧全部改掉，真正的请求由
     * PixivActionQueue 串行限流后发出，撞 429 整队冷却并自动重试，进程被杀后下次启动继续发。
     * 收藏后自动关注作者也由那一层负责（同样进队列）。
     *
     * <p>不再自己打接口的理由与 {@link #postFollowUser(int, String)} 相同：两条并行的写路径都拿
     * ObjectPool 当真源，队列还在冷却时它们会以相反的顺序落到服务端。而且这里原本是「点一次发一次」，
     * 正是连点爱心撞 429 的来源。
     *
     * <p>成功 toast、埋点、发现画像的处理见 {@link #postFollowUser(int, String)} 的说明，同理。
     *
     * @param showRelated    收藏成功后顺带拉相关作品并广播 FRAGMENT_ADD_RELATED_DATA。这是一次**读**
     *                       请求，不受收藏队列的限流约束，仍然当场发出。
     * @param index          legacy adapter 位置语义，随广播回传（feeds 接收器改按作品 id 锚定）。
     * @param sourcePageUuid 发起收藏的列表页 uuid，随 FRAGMENT_ADD_RELATED_DATA 广播回传，
     *                       让相关作品只被发起收藏的那张列表认领（feeds 页传，legacy 调用点传 null
     *                       走宽松的按 id 锚定兜底）。
     */
    public static void postLike(Illust illustsBean, String starType, boolean showRelated,
                                int index, String sourcePageUuid) {
        if (illustsBean == null) {
            return;
        }

        final boolean willBookmark = !illustsBean.isBookmarked();
        PixivActions.setIllustBookmark(illustsBean, willBookmark, starType);

        // 收藏的时候，顺便请求这个作品的相关作品
        if (willBookmark && showRelated) {
            PixivOps.relatedIllust(illustsBean.getId(), listIllust -> {
                Intent intent = new Intent(Params.FRAGMENT_ADD_RELATED_DATA);
                intent.putExtra(Params.CONTENT, listIllust);
                intent.putExtra(Params.INDEX, index);
                // feeds 版推荐页按被收藏作品 id 锚定插入位置（index 是 legacy
                // adapter 位置语义，跨列表广播时不可靠）
                intent.putExtra(Params.ID, (int) illustsBean.getId());
                if (sourcePageUuid != null) {
                    intent.putExtra(Params.PAGE_UUID, sourcePageUuid);
                }
                LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);

                // 寄生收集：收藏时的相关作品进发现池
                Common.showLog("Discovery/Hook postLike star_related illust=" + illustsBean.getId() + " got " + (listIllust.getIllusts() != null ? listIllust.getIllusts().size() : 0) + " related");
                ((ceui.pixiv.services.ServicesProvider) Shaft.getContext()).getDiscoveryPool().collect(
                        listIllust.getIllusts(), "star_related:" + illustsBean.getId());
            });
        }
        PixivOperate.insertIllustViewHistory(illustsBean);
    }

    // postLikeNovel(Novel, String, View) 已删除：全仓无调用方（小说收藏的现役入口是
    // PixivActions.setNovelBookmark / toggleNovelBookmark，走限流队列），而它还留着一整条
    // 自己打接口 + 自己发广播 + 自己做自动关注的老路径。留着只会给下一个人一个绕开队列的
    // 现成入口——两条写路径都拿 ObjectPool 当真源，队列冷却时会以相反顺序落到服务端。

    /**
     * @param userModel The model of current user
     * @param illustID  The id of illustration user searching for
     * @param context   (In doubt)The current activity
     */
    public static void getIllustByID(long illustID, Context context) {
        //Show "Loading" icon
        WitTipDialog tipDialog = new WitTipDialog.Builder(context)
                .setTipWord(getString(R.string.string_429))
                .create();
        tipDialog.show();
        //Get response data
        PixivOps.getIllustByID(illustID, illustSearchResponse -> {
            Illust illust = illustSearchResponse.getIllust();
            if (illust == null) {
                return;
            }
            //Update the illustration object in ObjectPool
            ObjectPool.INSTANCE.updateIllust(illust);
            //Check the permission to view the illustration
            if (illust.getId() == 0 || !Boolean.TRUE.equals(illust.getVisible())) {
                // #592: app-api 屏蔽(visible=false)的作品走网页 ajax 兜底
                IllustDetailSupportKt.fetchWebIllustFallbackAsync(illustID, webIllust -> {
                    if (webIllust != null) {
                        openIllustDetail(context, webIllust);
                    } else {
                        Common.showToast(R.string.string_206);
                    }
                });
                return;
            }
            openIllustDetail(context, illust);
        }, null, () -> {
            try {
                tipDialog.dismiss();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public static void getIllustByID(long illustID, Context context,
                                     ceui.lisa.interfaces.Callback<Void> success, ceui.lisa.interfaces.Callback<Void> fail) {
        PixivOps.getIllustByID(illustID, illustSearchResponse -> {
            Illust illust = illustSearchResponse.getIllust();
            if (illust == null) {
                return;
            }
            if (illust.getId() == 0 || !Boolean.TRUE.equals(illust.getVisible())) {
                // #592: 深链接/搜索打开被 app-api 屏蔽的作品,原先会直接进
                // 占位图页;改走网页 ajax 兜底,拿不到才报「无法显示」
                IllustDetailSupportKt.fetchWebIllustFallbackAsync(illustID, webIllust -> {
                    if (webIllust != null) {
                        openIllustDetail(context, webIllust);
                        if (success != null) {
                            success.doSomething(null);
                        }
                    } else {
                        Common.showToast(R.string.string_206);
                        if (fail != null) {
                            fail.doSomething(null);
                        }
                    }
                });
                return;
            }
            openIllustDetail(context, illust);
            if (success != null) {
                success.doSomething(null);
            }
        }, e -> {
            // 失败：先弹 pixiv 业务错误文案（对齐 legacy NullCtrl.error），再回 fail
            ErrorCtrl.handleError(e);
            if (fail != null) fail.doSomething(null);
        });
    }

    /** 按 ID 打开作品详情页的公共尾段:同步关注态 → 建单作品 PageData → 进 VActivity。 */
    private static void openIllustDetail(Context context, Illust illust) {
        User user = illust.getUser();
        if (user != null) {
            ((ServicesProvider) Shaft.getContext()).getAppLevelState().updateFollowUserStatus(user.getId(), Boolean.TRUE.equals(user.is_followed()) ? AppLevelState.FollowUserStatus.FOLLOWED : AppLevelState.FollowUserStatus.NOT_FOLLOW);
        }

        final PageData pageData = new PageData(Collections.singletonList(illust));
        Container.get().addPageToMap(pageData);

        Intent intent = new Intent(context, VActivity.class);
        intent.putExtra(Params.POSITION, 0);
        intent.putExtra(Params.PAGE_UUID, pageData.getUUID());
        context.startActivity(intent);
    }

    public static void getNovelByID(long novel, Context context,
                                    ceui.lisa.interfaces.Callback<Void> callback) {
        getNovelByID(novel, context, callback, null);
    }

    public static void getNovelByID(long novel, Context context,
                                    ceui.lisa.interfaces.Callback<Void> callback,
                                    ceui.lisa.interfaces.Callback<Void> fail) {
        PixivOps.getNovelByID(novel, novelSearchResponse -> {
            if (novelSearchResponse.getNovel() != null) {
                Intent intent = new Intent(context, TemplateActivity.class);
                intent.putExtra(Params.CONTENT, novelSearchResponse.getNovel());
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NOVEL_DETAIL.key);
                intent.putExtra("hideStatusBar", true);
                context.startActivity(intent);

                if (callback != null) {
                    callback.doSomething(null);
                }
            } else {
                Common.showToast("NovelSearchResponse 为空");
                if (fail != null) {
                    fail.doSomething(null);
                }
            }
        }, e -> {
            ErrorCtrl.handleError(e);
            if (fail != null) fail.doSomething(null);
        });
    }

    /**
     * 拉动图 metadata，成功回主线程 {@code onSuccess}；失败弹 pixiv 业务错误文案（对齐 legacy ErrorCtrl）。
     */
    public static void getGifInfo(Illust illust, JavaAsync.Consumer<GifResponse> onSuccess) {
        PixivOps.getGifPackage(illust.getId(), onSuccess);
    }

    public static void muteTag(TagsBean tagsBean) {
        MuteEntity muteEntity = new MuteEntity();
        String tagName = tagsBean.getName();
        muteEntity.setType(Params.MUTE_TAG);
        muteEntity.setId(tagName.hashCode());
        muteEntity.setTagJson(Shaft.sGson.toJson(tagsBean));
        muteEntity.setSearchTime(System.currentTimeMillis());
        AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().insertMuteTag(muteEntity);
    }

    public static void updateTag(TagsBean tagsBean) {
        MuteEntity muteEntity = new MuteEntity();
        String tagName = tagsBean.getName();
        muteEntity.setType(Params.MUTE_TAG);
        muteEntity.setId(tagName.hashCode());
        muteEntity.setTagJson(Shaft.sGson.toJson(tagsBean));
        muteEntity.setSearchTime(System.currentTimeMillis());
        if (tagsBean.isEffective()) {
            Shaft.getContext().getResources().getString(R.string.string_356);
        } else {
            Shaft.getContext().getResources().getString(R.string.string_357);
        }
        AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().updateMuteTag(muteEntity);
    }

    public static void muteUser(User user) {
        muteUser(user, true);
    }

    /**
     * {@code showToast=false} 给「屏蔽设定」sheet 用:它一次保存可能同时动标签和作者,
     * 逐项弹 toast 会连着刷好几条,由调用方在最后统一发一条(同 {@link #unMuteTag(TagsBean, boolean)})。
     */
    public static void muteUser(User user, boolean showToast) {
        MuteEntity muteEntity = new MuteEntity();
        muteEntity.setType(Params.MUTE_USER);
        muteEntity.setId(user.getId());
        muteEntity.setTagJson(Shaft.sGson.toJson(user));
        muteEntity.setSearchTime(System.currentTimeMillis());
        AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().insertMuteTag(muteEntity);
        if (showToast) {
            Common.showToast(Shaft.getContext().getString(R.string.string_382));
        }
    }

    public static void unMuteUser(User user) {
        unMuteUser(user, true);
    }

    /** 见 {@link #muteUser(User, boolean)}。 */
    public static void unMuteUser(User user, boolean showToast) {
        MuteEntity muteEntity = new MuteEntity();
        muteEntity.setType(Params.MUTE_USER);
        muteEntity.setId(user.getId());
        muteEntity.setTagJson(Shaft.sGson.toJson(user));
        muteEntity.setSearchTime(System.currentTimeMillis());
        AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().unMuteTag(muteEntity);
        if (showToast) {
            Common.showToast(Shaft.getContext().getString(R.string.string_383));
        }
    }

    // blockUser / unBlockUser 已删除：全仓无调用方（拉黑的现役入口是 EntityWrapper.blockUser）。

    /**
     * 屏蔽单件插画。写库和内存名单一并交给 {@link IllustMuteStore}（顺带把落库挪出主线程）——
     * 瀑布流卡片的遮罩判定读的是它的内存镜像，这里若直接 insertMuteTag，已经在跑的列表要等
     * 进程重启才认这条记录。
     */
    public static void muteIllust(Illust illust) {
        IllustMuteStore.INSTANCE.setMuted(illust.getId(), true, () -> illust);
        Common.showToast(Shaft.getContext().getString(R.string.string_384));
    }

    // muteNovel / muteTags 已删除：全仓无调用方（小说屏蔽走 NovelMuteStore，批量屏蔽标签由调用方循环 muteTag）。

    public static void unMuteTag(TagsBean tagsBean) {
        unMuteTag(tagsBean, true);
    }

    /**
     * 批量取消屏蔽时把 toast 关掉（{@code showToast=false}）——一次解开多个标签会连弹好几条，
     * 由调用方在末尾统一发一条即可。见 {@code ceui.pixiv.ui.muted.MuteTagSheet.save()}。
     */
    public static void unMuteTag(TagsBean tagsBean, boolean showToast) {
        MuteEntity muteEntity = new MuteEntity();
        String tagName = tagsBean.getName();
        muteEntity.setType(Params.MUTE_TAG);
        muteEntity.setId(tagName.hashCode());
        muteEntity.setTagJson(Shaft.sGson.toJson(tagsBean));
        muteEntity.setSearchTime(System.currentTimeMillis());
        AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().unMuteTag(muteEntity);
        if (showToast) {
            Common.showToast(Shaft.getContext().getString(R.string.string_135));
        }
    }

    public static void insertIllustViewHistory(Illust illust) {
        if (illust == null) {
            return;
        }

        if (illust.getId() > 0) {
            JavaAsync.fireAndForget(() -> {
                IllustHistoryEntity illustHistoryEntity = new IllustHistoryEntity();
                illustHistoryEntity.setType(0);
                illustHistoryEntity.setIllustID((int) illust.getId());
                illustHistoryEntity.setIllustJson(Shaft.sGson.toJson(illust));
                illustHistoryEntity.setTime(System.currentTimeMillis());
                Common.showLog("插入了 " + illustHistoryEntity.getIllustID() + " time " + illustHistoryEntity.getTime());
                AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insert(illustHistoryEntity);
                // dual-write: report to pixshaft-api (same Illust payload the
                // history list deserializes). illust tab covers illust + manga.
                // Fully isolated: any failure here must never affect browsing.
                try {
                    String reportType = "manga".equals(illust.getType()) ? "manga" : "illust";
                    ceui.pixiv.db.HistoryReporter.INSTANCE.enqueue(reportType, (long) illust.getId(), Shaft.sGson.toJsonTree(illust));
                } catch (Throwable t) {
                    Common.showLog("history report enqueue failed: " + t);
                }
            });
        }
    }

    public static void insertNovelViewHistory(Novel novelBean) {
        if (novelBean == null) {
            return;
        }

        if (novelBean.getId() > 0) {
            JavaAsync.fireAndForget(() -> {
                IllustHistoryEntity illustHistoryEntity = new IllustHistoryEntity();
                illustHistoryEntity.setIllustID((int) novelBean.getId());
                illustHistoryEntity.setType(1);
                illustHistoryEntity.setIllustJson(Shaft.sGson.toJson(novelBean));
                illustHistoryEntity.setTime(System.currentTimeMillis());
                Common.showLog("插入了 " + illustHistoryEntity.getIllustID() + " time " + illustHistoryEntity.getTime());
                AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insert(illustHistoryEntity);
                try {
                    ceui.pixiv.db.HistoryReporter.INSTANCE.enqueue("novel", (long) novelBean.getId(), Shaft.sGson.toJsonTree(novelBean));
                } catch (Throwable t) {
                    Common.showLog("history report enqueue failed: " + t);
                }
            });
        }
    }

    /**
     * @param key
     * @param searchType The type of search.
     * @see ceui.lisa.database.SearchEntity
     * */
    public static void insertSearchHistory(String key, int searchType) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        SearchEntity searchEntity = new SearchEntity();
        searchEntity.setKeyword(key);
        searchEntity.setSearchType(searchType);
        searchEntity.setSearchTime(System.currentTimeMillis());
        searchEntity.setId(searchEntity.getKeyword().hashCode() + searchEntity.getSearchType());
        Common.showLog("insertSearchHistory " + searchType + " " + searchEntity.getId());
        //If the search history already exists,set it as pinned
        SearchEntity existEntity = AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().getSearchEntity(searchEntity.getId());
        if (existEntity != null) {
            searchEntity.setPinned(existEntity.isPinned());
            // DAO 是 REPLACE,不带 previewIllustsJson 回去会把详情页保存的固定预览 illust 抹掉。
            searchEntity.setPreviewIllustsJson(existEntity.getPreviewIllustsJson());
        }
        AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().insert(searchEntity);
    }

    public static void insertPinnedSearchHistory(String key, int searchType, boolean pinned) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        // 3 参版本没机会带 illust：pinned=true 时保留 DB 已有 json（别让 FragmentSearch 等
        // 旧路径切 pinned 时把详情页存的 illust 抹掉）；pinned=false 时清掉。
        int id = key.hashCode() + searchType;
        SearchEntity existing = AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().getSearchEntity(id);
        String preserved = (pinned && existing != null) ? existing.getPreviewIllustsJson() : null;
        insertPinnedSearchHistory(key, searchType, pinned, preserved);
    }

    // 4 参显式版本：pinned=true 时把 previewJson 写入 search_table.previewIllustsJson；
    // pinned=false 时无论 previewJson 如何，字段都置 null（取消固定就别留 stale json）。
    public static void insertPinnedSearchHistory(String key, int searchType, boolean pinned, String previewJson) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        SearchEntity searchEntity = new SearchEntity();
        searchEntity.setKeyword(key);
        searchEntity.setSearchType(searchType);
        searchEntity.setSearchTime(System.currentTimeMillis());
        searchEntity.setId(searchEntity.getKeyword().hashCode() + searchEntity.getSearchType());
        searchEntity.setPinned(pinned);
        searchEntity.setPreviewIllustsJson(pinned ? previewJson : null);
        Common.showLog("insertSearchHistory " + searchType + " " + searchEntity.getId());
        AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().insert(searchEntity);
    }

    public static SearchEntity getSearchHistory(String key, int searchType) {
        int id = key.hashCode() + searchType;
        return AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().getSearchEntity(id);
    }

    //筛选作品，只留下未收藏的作品
    public static List<Illust> getListWithoutBooked(ListIllust response) {
        List<Illust> result = new ArrayList<>();
        if (response == null) {
            return result;
        }

        if (response.getList() == null || response.getList().size() == 0) {
            return result;
        }

        for (Illust illustsBean : response.getList()) {
            if (!illustsBean.isBookmarked()) {
                result.add(illustsBean);
            }
        }

        return result;
    }

    //筛选作品，只留下收藏数达到标准的作品
    /** starSizeMax <= 0 表示上限不限（收藏量区间筛选只设了下限，或只有 users入り 关键字桶）。 */
    public static List<Illust> getListWithStarSize(ListIllust response, int starSize, int starSizeMax) {
        List<Illust> result = new ArrayList<>();
        if (response == null || response.getList() == null || response.getList().size() == 0) {
            return result;
        }

        for (Illust illustsBean : response.getList()) {
            int stars = illustsBean.getTotal_bookmarks() == null ? 0 : illustsBean.getTotal_bookmarks();
            if (stars >= starSize && (starSizeMax <= 0 || stars <= starSizeMax)) {
                result.add(illustsBean);
            }
        }

        return result;
    }

    public static void justUnzipFile(File fromZipFile, File toFolder) {
        try {
            ZipUtils.unzipFile(fromZipFile, toFolder);
            Common.showLog("justUnzipFile 解压成功");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * GIF 编码专用线程池：分钟级 CPU 重活，不能占共享的 Dispatchers.IO；而且
     * 编码线程要降到 BACKGROUND 优先级，降档作用在 Linux tid 上、不随任务结束还原——
     * 放共享池会把池线程永久降档，之后复用它的搜索/DB 过滤全被限成 cgroup background。
     * 独占线程 + 工厂里直接降档，对齐旧 RxRun 的 Schedulers.newThread() 语义。
     */
    private static final java.util.concurrent.ExecutorService GIF_ENCODE_EXECUTOR =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(() -> {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }, "shaft-gif-encode");
                t.setDaemon(true);
                return t;
            });

    /**
     * 后台把解压好的帧序列编成 GIF（可选 autoSave 落到用户存储）。整段跑在 {@link #GIF_ENCODE_EXECUTOR}；
     * 失败只清掉半成品文件，不弹提示（对齐旧 RxRun + TryCatchObserverImpl 的静默行为）。
     */
    public static void encodeGifV2(Context context, File parentFile, Illust illustsBean, boolean autoSave) {
        GIF_ENCODE_EXECUTOR.execute(() -> {
            try {
                long currentTimeMillis = System.currentTimeMillis();
                if (gifEncodingWorkSet.containsKey(illustsBean.getId())
                        && (currentTimeMillis - gifEncodingWorkSet.get(illustsBean.getId())) < reEncodeTimeThresholdMillis) {
                    return;
                }
                gifEncodingWorkSet.put(illustsBean.getId(), currentTimeMillis);
                Common.showLog("encodeGif 开始生成gif图");
                final File[] listfile = parentFile.listFiles();

                List<File> allFiles = Arrays.asList(listfile);
                Collections.sort(allFiles, new Comparator<File>() {
                    @Override
                    public int compare(File o1, File o2) {
                        if (Integer.parseInt(o1.getName().substring(0, o1.getName().length() - 4)) >
                                Integer.parseInt(o2.getName().substring(0, o2.getName().length() - 4))) {
                            return 1;
                        } else {
                            return -1;
                        }
                    }
                });

                File gifFile = LegacyFile.gifResultFile(context, illustsBean);
                Common.showLog("gifFile " + gifFile.getPath());

                AnimatedGifEncoder animatedGifEncoder = new AnimatedGifEncoder();
                FileOutputStream outStream = new FileOutputStream(gifFile.getPath());
                animatedGifEncoder.start(outStream);
                animatedGifEncoder.setRepeat(0); // 无限循环

                int frameCount = allFiles.size();

                GifResponse gifResponse = UgoiraMetadataCache.get(illustsBean.getId());
                int delayMs = 60;
                if (gifResponse != null) {
                    List<FramesBean> framesBeans = gifResponse.getUgoira_metadata().getFrames();
                    if (frameCount == framesBeans.size()) {
                        Common.showLog("使用返回的delay 00");

                        Handler mainHandler = new Handler(Looper.getMainLooper());
                        for (int i = 0; i < frameCount; i++) {
                            Bitmap bitmap = BitmapFactory.decodeFile(allFiles.get(i).getPath());
                            Common.showLog("编码中 00 " + frameCount + " " + (i + 1));
                            animatedGifEncoder.setDelay(framesBeans.get(i).getDelay());
                            animatedGifEncoder.addFrame(bitmap);
                            if (bitmap != null) bitmap.recycle();

                            Back back = sBack.get(illustsBean.getId());
                            if (back != null) {
                                float proc = i / (float) (frameCount - 1);
                                mainHandler.post(() -> back.invoke(proc));
                            }
                        }
                        sBack.remove(illustsBean.getId());
                    } else {
                        delayMs = gifResponse.getDelay();
                        Common.showLog("使用返回的delay 11");
                        for (int i = 0; i < frameCount; i++) {
                            Bitmap bitmap = BitmapFactory.decodeFile(allFiles.get(i).getPath());
                            Common.showLog("编码中 00 " + frameCount);
                            animatedGifEncoder.setDelay(delayMs);
                            animatedGifEncoder.addFrame(bitmap);
                            if (bitmap != null) bitmap.recycle();
                        }
                    }
                } else {
                    Common.showLog("使用返回的delay 22");
                    for (int i = 0; i < frameCount; i++) {
                        Common.showLog("编码中 00 " + frameCount);
                        Bitmap bitmap = BitmapFactory.decodeFile(allFiles.get(i).getPath());
                        animatedGifEncoder.setDelay(delayMs);
                        animatedGifEncoder.addFrame(bitmap);
                        if (bitmap != null) bitmap.recycle();
                    }
                }

                Common.showLog("allFiles size " + frameCount);

                animatedGifEncoder.finish();
                outStream.close();

                if (autoSave) {
                    // 这条老链路只产 GIF,所以 asVideo 恒为 false —— 名字/mime 必须跟着
                    // **实际产物**走,不能跟着「动图保存格式」设置走。设置成 mp4 时
                    // IllustDownload.downloadGif 会先走播放 pipeline 出 mp4,压根到不了这里。
                    OutPut.outPutUgoira(context, gifFile, illustsBean, false);
                }

                Common.showLog("gifFile gifFile " + FileUtils.getSize(gifFile));
                gifEncodingWorkSet.remove(illustsBean.getId());

                // 旧的 FragmentSingleUgora 靠 PLAY_GIF 广播刷新播放,页面已删、无接收方;
                // 内联播放走 UgoiraEngine(collect 进度,不靠广播),故不再发。
            } catch (Exception e) {
                Common.showLog("encodeGifV2 error: " + e.getClass().getName() + " " + e.getMessage());
                e.printStackTrace();
                gifEncodingWorkSet.remove(illustsBean.getId());
                try {
                    File gifFile = LegacyFile.gifResultFile(context, illustsBean);
                    if (gifFile.exists()) {
                        gifFile.delete();
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });
    }

    public static void unzipAndPlay(Context context, Illust illustsBean) {
        unzipAndPlay(context, illustsBean, false);
    }

    public static void unzipAndPlay(Context context, Illust illustsBean, boolean autoSave) {
        try {
            File fromZip = LegacyFile.gifZipFile(context, illustsBean);
            File toFolder = LegacyFile.gifUnzipFolder(context, illustsBean);
            justUnzipFile(fromZip, toFolder);

            // 旧的 FragmentSingleUgora 靠 PLAY_GIF 广播启动逐帧播放,页面已删、无接收方;
            // 内联播放走 UgoiraEngine,不靠广播,故不再发。编码 GIF 供导出/下载用。
            encodeGifV2(context, toFolder, illustsBean, autoSave);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void setBack(long illustId, Back back) {
        sBack.put(illustId, back);
    }

    public static void clearBack() {
        sBack.clear();
    }

    // postNovelMarker(NovelDetail.NovelMarkerBean, ...) 已删除：全仓无调用方（旧小说详情页书签按钮已下线）。

    // For markers page
    public static void postNovelMarker(MarkedNovelItem.NovelMarker marker, int novelId, View view) {
        int page = marker.getPage();
        if (marker.isCancelled()) {
            marker.setCancelled(false);
            PixivOps.postAddNovelMarker(novelId, page, nullResponse -> {
                if (view instanceof ImageView) {
                    ((ImageView) view).setImageTintList(ColorStateList.valueOf(getColor(R.color.novel_marker_add)));
                }
                Common.showToast(getString(R.string.string_368, page));
            });
        } else {
            marker.setCancelled(true);
            PixivOps.postDeleteNovelMarker(novelId, nullResponse -> {
                if (view instanceof ImageView) {
                    ((ImageView) view).setImageTintList(ColorStateList.valueOf(getColor(R.color.novel_marker_none)));
                }
                Common.showToast(getString(R.string.string_369));
            });
        }
    }

    // postNovelWatchlist(NovelSeriesItem, Button) 已删除：全仓无调用方（追更开关现役入口是
    // NovelSeriesFragment 直调 AppApi.postWatchlistNovelAdd/Delete）。
}
