package ceui.lisa.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import java.util.HashSet;
import java.util.Set;

import com.jaredrummler.android.colorpicker.ColorPickerDialogListener;

import ceui.lisa.BuildConfig;
import ceui.lisa.R;
import ceui.lisa.databinding.ActivityFragmentBinding;
import ceui.pixiv.ui.comic.reader.ComicReaderV3Fragment;
import ceui.pixiv.ui.navigation.TemplateRoute;
import ceui.pixiv.ui.navigation.TemplateRouteFactory;
import ceui.pixiv.ui.novel.reader.NovelReaderV3Fragment;
import timber.log.Timber;

public class TemplateActivity extends BaseActivity<ActivityFragmentBinding> implements ColorPickerDialogListener {

    public static final String EXTRA_FRAGMENT = "dataType";
    public static final String EXTRA_KEYWORD = "keyword";
    /** For dataType=="聊天室": pixiv uid of the chat peer for 1v1. Absent / 0 = global room. */
    public static final String EXTRA_CHAT_PEER_UID = "chatPeerUid";
    protected Fragment childFragment;
    private String dataType;
    /** debug 下认不得的路由 key 攒到 onCreate 末尾再抛，见 {@link #onCreate}。 */
    private IllegalArgumentException unknownRouteError;
    // 阅读器消费过 ACTION_DOWN 的音量键 keyCode 集合，待配对吃掉对应 ACTION_UP (issue #874)。
    // 用 Set 而非单个 int，是因为 VOL_UP / VOL_DOWN 可能近乎同时按下，单字段会被后者覆盖导致前者 UP 泄漏到系统。
    private final Set<Integer> volumeKeysAwaitingUp = new HashSet<>(2);

    @Override
    protected void initBundle(Bundle bundle) {
        dataType = bundle.getString(EXTRA_FRAGMENT);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // BaseActivity.onCreate 把 initData() 整个包在 catch(Exception) 里，createNewFragment
        // 里直接 throw 会被吞成一张白页——debug 要真崩，只能等 super.onCreate 返回后再抛。
        if (unknownRouteError != null) {
            throw unknownRouteError;
        }
    }

    /**
     * 按 {@link #EXTRA_FRAGMENT} 建子 Fragment。路由表在 {@link TemplateRoute}，
     * 建页逻辑在 {@link TemplateRouteFactory}（对枚举穷举的 when，漏一个编译不过）。
     * 未知 key：debug 抛 {@link IllegalArgumentException}（写错 key 是编码错误，不该拖到线上），
     * release 记日志并 finish——此前是静默塞一个空 Fragment 给用户看白屏。
     */
    protected Fragment createNewFragment() {
        if (TextUtils.isEmpty(dataType)) {
            return null;
        }
        TemplateRoute route = TemplateRoute.fromKey(dataType);
        if (route == null) {
            IllegalArgumentException error = new IllegalArgumentException(
                    "Unknown TemplateActivity route: '" + dataType + "'");
            if (BuildConfig.DEBUG) {
                unknownRouteError = error;
            } else {
                Timber.e(error);
                finish();
            }
            return null;
        }
        return TemplateRouteFactory.create(route, getIntent());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 音量键翻页：在 dispatchKeyEvent 这一最早入口拦截，避免 Android 16 / HyperOS 3+ 的
        // 多应用音量面板在事件抵达 onKeyDown 之前就被 SystemUI 弹出 (issue #874)。
        // 同时配对消费 ACTION_UP，防止部分 ROM 在 UP 时再次触发系统音量 UI。
        final int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        final int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            boolean handled = false;
            if (childFragment instanceof NovelReaderV3Fragment) {
                handled = ((NovelReaderV3Fragment) childFragment).handleVolumeKey(keyCode);
            } else if (childFragment instanceof ComicReaderV3Fragment) {
                handled = ((ComicReaderV3Fragment) childFragment).handleVolumeKey(keyCode);
            }
            if (handled) {
                volumeKeysAwaitingUp.add(keyCode);
                return true;
            }
        } else if (action == KeyEvent.ACTION_UP && volumeKeysAwaitingUp.remove(keyCode)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected int initLayout() {
        return R.layout.activity_fragment;
    }

    @Override
    protected void initView() {
        // 返回键/返回手势:这里故意不挂任何 OnBackPressedCallback。
        //
        // 预测式返回(targetSdk 35+ 默认开启)的跨 Activity / 回桌面动画只在「app 没向系统
        // 注册任何返回回调」时才会播:只要 OnBackPressedDispatcher 里有一个 enabled 的
        // callback,AndroidX 就会向 WindowOnBackInvokedDispatcher 注册 OnBackInvokedCallback,
        // 系统随即放弃自己的动画,手势落下后只是干巴巴地回调 → 以前这里那个常开的兜底
        // callback 把全 app 几乎所有页面的预测式返回都掐死了。
        //
        // 没有 callback 时系统走 Activity 默认返回(finishAfterTransition),动画由系统负责。
        // 需要拦返回的页面(网页历史后退、阅读器收顶底栏、未保存确认…)各自在 Fragment 里
        // 注册 callback,并且只在「真的有东西可拦」时才 setEnabled(true) —— 必须提前维护好
        // enabled,系统在手势开始那一刻就决定播不播动画,handleOnBackPressed 里再判断已经晚了。
        // 子 Fragment 返回栈由 FragmentManager 自带的 callback 处理(本 app 没有 addToBackStack)。
    }

    @Override
    protected void initData() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment fragment = fragmentManager.findFragmentById(R.id.fragment_container);

        if (fragment == null) {
            fragment = createNewFragment();
            if (fragment != null) {
                fragmentManager.beginTransaction()
                        .add(R.id.fragment_container, fragment)
                        .commit();
                childFragment = fragment;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (childFragment != null) {
            childFragment.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public boolean hideStatusBar() {
        if (TemplateRoute.COMMENTS.key.equals(dataType)) {
            return false;
        } else {
            return getIntent().getBooleanExtra("hideStatusBar", true);
        }
    }

    @Override
    public void onColorSelected(int dialogId, int color) {
        // 旧版小说阅读器 FragmentNovelHolder 已删除（改用 NovelReaderV3 的设置面板），
        // 这里的颜色回调不再有承接对象，保留空实现满足接口。
    }

    @Override
    public void onDialogDismissed(int dialogId) {

    }

    public void onFontSizeSelected(int size) {
        // 同上：旧阅读器字号回调已随 FragmentNovelHolder 一并废弃。
    }
}
