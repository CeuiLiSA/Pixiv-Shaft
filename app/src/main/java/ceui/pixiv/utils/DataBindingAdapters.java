package ceui.pixiv.utils;

import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.databinding.BindingAdapter;

import ceui.pixiv.api.model.Illust;
import ceui.pixiv.ui.user.UserImageBindingAdaptersKt;

/**
 * :app 布局里自定义 DataBinding 属性的唯一登记处。
 *
 * <p>必须写在 Java 里：:app 不用 kapt，DataBinding 处理器跑在 javac 上，只扫描 Java 源码里的
 * {@code @BindingAdapter}，写在 Kotlin 里的注解会被静默忽略，布局编译时报
 * 「Cannot find a setter」。需要较多逻辑的实现放在 Kotlin，这里只做转发。
 */
public final class DataBindingAdapters {

    private DataBindingAdapters() {
    }

    /** 标签卡的三格预览图（cell_item_pinned_tag / prime_tag / corpus_tag）。 */
    @BindingAdapter("loadSquareMedia")
    public static void loadSquareMedia(ImageView view, @Nullable Illust illust) {
        UserImageBindingAdaptersKt.binding_loadSquareMedia(view, illust);
    }

    @BindingAdapter("visibleOrGone")
    public static void visibleOrGone(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
