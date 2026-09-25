package ceui.lisa.helper;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.OnBackPressedDispatcher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import ceui.lisa.activities.ImageDetailActivity;
import ceui.lisa.activities.MainActivity;
import ceui.lisa.activities.RankActivity;
import ceui.lisa.activities.SearchActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.VActivity;

/**
 * 「预测性返回」逐页开关的降级实现：被关掉的页面吃掉系统预测动画，退回传统返回。
 *
 * <p>机制：只要 {@code OnBackPressedDispatcher} 里存在一个 enabled 的
 * {@link OnBackPressedCallback}，AndroidX 就会向 {@code WindowOnBackInvokedDispatcher}
 * 注册 {@code OnBackInvokedCallback}；系统一旦发现 App 自己注册了回调，就放弃它自己的
 * 跨 Activity / 回桌面预测动画，手势落下后只是干巴巴地回调。因此「挂一个常开回调、
 * 在回调里交回默认返回」等价于关掉预测动画。
 *
 * <p>这是本项目验证过的既有手法，反面教训见 {@code TemplateActivity#initView}：那里原本
 * 就有一个常开兜底 callback，把全 app 几乎所有页面的预测式返回都掐死了，后来被移除。
 * 本类把这个能力收进「设置 · 界面 · 预测性返回」弹窗，按 Activity 逐项控制，<b>默认全不开</b>。
 *
 * <p>[实验]「抑制从侧边栏进入任意页返回的闪烁」打开时有两个附加行为：范围从
 * {@link #TARGETS} 扩到所有 {@code ComponentActivity}；抽屉行点击会经
 * {@link #armDrawerLaunch()} 登记，紧接其后创建的那个 Activity 记入
 * {@link #DRAWER_LAUNCHED}，该实例整条生命周期都按抑制处理。默认关。
 *
 * <p>三个必须守住的点：
 * <ul>
 *   <li><b>幂等</b>：{@code addCallback} 不去重，同一 Activity 挂两个 enabled 回调会让一次
 *       手势连续 finish 两次（表现为返回穿透两层），所以用 {@link #INSTALLED} 判存。</li>
 *   <li><b>优先级</b>：dispatcher 只执行「最后一个 enabled」的回调。这里在
 *       {@code onActivityPreCreated} 挂载，位于栈底；Fragment / Activity 自己后注册的拦截
 *       （草稿保护、退出确认、选择态返回）优先级更高，语义不受影响。</li>
 *   <li><b>泄漏</b>：{@link WeakHashMap} 的 key 弱引用 Activity，但 value 的回调持有了
 *       Activity，value 反过来强引用 key，条目永远不会被回收——必须在
 *       {@code onActivityDestroyed} 里显式移除。</li>
 * </ul>
 *
 * <p>回调对 {@link #TARGET_ORDER} 里每个 Activity <b>始终注册</b>，只按用户逐项设置切
 * enabled：该项开着（默认）时回调是 disabled 的，既不参与返回分发，也不会让
 * {@code hasEnabledCallbacks()} 变 true（PlazaNavigationTest 依赖这点）；用户关掉某一项后
 * 它立刻 enabled，系统当帧就放弃动画，不必重建 Activity。
 *
 * <p>只在 {@code onActivityPreCreated}（API 29+）挂载：预测性返回本身要 Android 13+，
 * 而 preCreated 是唯一能保证「早于 Fragment 注册回调」的时机，两者的覆盖范围正好一致。
 */
public final class PredictiveBackSuppressor implements Application.ActivityLifecycleCallbacks {

    /**
     * 与 AndroidManifest 中显式声明 {@code enableOnBackInvokedCallback="true"} 的 Activity
     * 一一对应。用 Class 精确匹配而不是 {@code instanceof}：{@code MuzeiSettingsActivity}
     * 继承自 {@link TemplateActivity} 但并未声明该属性，不该被牵连。
     */
    public static final List<Class<?>> TARGET_ORDER = Collections.unmodifiableList(Arrays.asList(
            TemplateActivity.class,
            RankActivity.class,
            ImageDetailActivity.class,
            SearchActivity.class,
            VActivity.class,
            MainActivity.class));

    private static final Set<Class<?>> TARGETS = new HashSet<>(TARGET_ORDER);

    private static final WeakHashMap<Activity, OnBackPressedCallback> INSTALLED = new WeakHashMap<>();

    /** [实验] 是否把所有 Activity 都纳入(而非只纳入白名单)。 */
    private static boolean isAnyPageMode() {
        return Shaft.sSettings != null && Shaft.sSettings.isSuppressBackFlickerAnyPage();
    }

    /** [实验] 侧边栏点击后,多久之内创建的 Activity 算"从侧边栏进入"。 */
    private static final long DRAWER_ARM_WINDOW_MS = 2000L;

    private static long drawerArmedAt = 0L;

    /** [实验] 已被判定为"从侧边栏进入"的 Activity;整条生命周期按抑制处理,销毁时移除。 */
    private static final WeakHashMap<Activity, Boolean> DRAWER_LAUNCHED = new WeakHashMap<>();

    /**
     * 「本进程启动后是否发生过导航」—— 用来近似判断当前 ROM 的预测返回有没有 primed。
     *
     * <p>某些 ROM 只在系统侧存在有效预测返回目标时才投递真实 progress;而任何一次导航
     * (侧边栏选项 / 详情页 / 搜索页都算)都会让它进入 primed。所以在 MainActivity 上做抽屉
     * 手势时,「本进程还没创建过 MainActivity 以外的 Activity」≈「ROM 处于未 primed 窗口」
     * ≈「这一场手势只会拿到 progress 恒为 0 的 stub」。
     *
     * <p>这是启发式而非已证实的机制,所以使用方必须能自愈:一旦真收到 progress > 0 就立刻
     * 放弃兜底(见 {@code DrawerPredictiveBack#onProgressed})。判错的最坏后果是手势开头
     * 闪一帧固定位移。
     *
     * <p>只在进程内单向置位、不重置:实测「进一次页面再回来」的 primed 是持久的。
     */
    private static volatile boolean navigatedSinceProcessStart = false;

    /** 见 {@link #navigatedSinceProcessStart}。 */
    public static boolean hasNavigatedSinceProcessStart() {
        return navigatedSinceProcessStart;
    }

    /**
     * [实验] 侧边栏(抽屉)点了某项时调用 —— 挂在 MainActivity#addDrawerSection 的行点击里,
     * 而不是 handleDrawerAction 里:后者与 MeFragment / FragmentCenter 共用,只有前者
     * 才真的算"从侧边栏进入"。
     * 只登记一个时间戳,紧接着创建的那个 Activity 会被认作"从侧边栏进入"。
     * 开关关着时是空操作。
     */
    public static void armDrawerLaunch() {
        if (Shaft.sSettings == null || !Shaft.sSettings.isSuppressBackFlickerAnyPage()) {
            return;
        }
        drawerArmedAt = System.currentTimeMillis();
    }

    private static boolean isDrawerArmFresh() {
        return drawerArmedAt > 0L
                && System.currentTimeMillis() - drawerArmedAt <= DRAWER_ARM_WINDOW_MS;
    }

    /** 这个 Activity 是否被用户改成走传统返回。sSettings 在 Application.onCreate 里已就位。 */
    private static boolean shouldSuppress(Class<?> activityClass) {
        return Shaft.sSettings != null
                && Shaft.sSettings.getPredictiveBackDisabledActivities().contains(activityClass.getName());
    }

    /**
     * 这个**实例**是否要抑制 —— 抑制 = 我们的回调保持 enabled,系统因此放弃自己的预测动画。
     * = 用户逐页关掉的类 ∪ [实验]"从侧边栏进入"的那一页。
     *
     * <p>后者必须在这里也算上,否则用户按一次"确定"触发 syncAll 时就会把它重新关掉。
     */
    private static boolean shouldSuppressActivity(Activity activity) {
        return shouldSuppress(activity.getClass())
                || (isAnyPageMode() && DRAWER_LAUNCHED.containsKey(activity));
    }

    /** 设置项变化时调用：把新值立刻套到已经存在的 Activity 上，不必等重建。 */
    public static void syncAll() {
        synchronized (INSTALLED) {
            for (Map.Entry<Activity, OnBackPressedCallback> entry : INSTALLED.entrySet()) {
                Activity activity = entry.getKey();
                OnBackPressedCallback callback = entry.getValue();
                if (activity != null && callback != null) {
                    callback.setEnabled(shouldSuppressActivity(activity));
                }
            }
        }
    }

    @Override
    public void onActivityPreCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        // 必须放在下面所有 early-return 之前:创建过 MainActivity 以外的 Activity
        // 就算发生过一次导航,也就是 ROM 已 primed(见 navigatedSinceProcessStart)。
        if (activity.getClass() != MainActivity.class) {
            navigatedSinceProcessStart = true;
        }
        if (!(activity instanceof ComponentActivity)) {
            return;
        }
        // [实验] 打开「抑制任意页闪烁」时,未声明 enableOnBackInvokedCallback 的页面也要
        // 注册回调 —— 否则这一页没有可切换的回调,抑制无从施加。
        if (!TARGETS.contains(activity.getClass()) && !isAnyPageMode()) {
            return;
        }
        synchronized (INSTALLED) {
            if (INSTALLED.containsKey(activity)) {
                return;
            }
            OnBackPressedDispatcher dispatcher = ((ComponentActivity) activity).getOnBackPressedDispatcher();
            OnBackPressedCallback callback = new OnBackPressedCallback(false) {
                @Override
                public void handleOnBackPressed() {
                    // 只有没有任何更高优先级的拦截时才会走到这里。这里只负责「不让系统播动画」,
                    // 返回本身交回 Activity 默认实现,不能自己 finish():launcher 根页(MainActivity
                    // 「再按一次退出」窗口内)默认是 moveTaskToBack,finish 会把主页销毁掉。
                    setEnabled(false);
                    dispatcher.onBackPressed();
                    setEnabled(true);
                }
            };
            dispatcher.addCallback(callback);
            INSTALLED.put(activity, callback);
            // [实验] 紧跟在侧边栏点击之后创建的 Activity 认作"从侧边栏进入"。
            // 标记必须在算 suppress 之前打 —— shouldSuppressActivity 会把它算进去,
            // 该实例整条生命周期都按"抑制"处理。
            if (isAnyPageMode() && isDrawerArmFresh()) {
                drawerArmedAt = 0L;
                DRAWER_LAUNCHED.put(activity, Boolean.TRUE);
            }
            callback.setEnabled(shouldSuppressActivity(activity));
        }
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        OnBackPressedCallback callback;
        synchronized (INSTALLED) {
            callback = INSTALLED.remove(activity);
            DRAWER_LAUNCHED.remove(activity);
        }
        if (callback != null) {
            callback.remove();
        }
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        // 抑制只在 onActivityPreCreated 里设定一次,这里不需要做事。
        // (曾在此注入过 sendCancelIfRunning 想做「主动预热」,实测无效,已否证。)
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }
}
