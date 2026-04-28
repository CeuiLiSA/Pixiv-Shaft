package ceui.lisa.utils;

import android.content.Context;
import android.view.View;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.qmuiteam.qmui.util.QMUIDisplayHelper;
import com.qmuiteam.qmui.widget.popup.QMUIPopup;
import com.qmuiteam.qmui.widget.popup.QMUIPopups;

import java.util.Arrays;
import java.util.List;

import ceui.lisa.R;

/**
 * 用 QMUI 的 listPopup 替代 androidx PopupMenu，统一项目里的下拉菜单视觉。
 */
public final class QMUIMenuPopup {

    public interface OnItemSelected {
        void onItemSelected(int index, CharSequence text);
    }

    private QMUIMenuPopup() {}

    public static QMUIPopup show(@NonNull Context context,
                                 @NonNull View anchor,
                                 @NonNull CharSequence[] titles,
                                 @NonNull OnItemSelected listener) {
        return show(context, anchor, Arrays.asList(titles), listener);
    }

    public static QMUIPopup show(@NonNull Context context,
                                 @NonNull View anchor,
                                 @NonNull List<CharSequence> titles,
                                 @NonNull OnItemSelected listener) {
        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<>(
                context, R.layout.item_qmui_menu, titles);
        QMUIPopup[] holder = new QMUIPopup[1];
        QMUIPopup popup = QMUIPopups.listPopup(
                context,
                QMUIDisplayHelper.dp2px(context, 180),
                QMUIDisplayHelper.dp2px(context, 320),
                adapter,
                (parent, view, position, id) -> {
                    listener.onItemSelected(position, titles.get(position));
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                });
        popup.preferredDirection(QMUIPopup.DIRECTION_BOTTOM)
                .shadow(true)
                .arrow(false)
                .bgColor(ContextCompat.getColor(context, R.color.fragment_center))
                .animStyle(QMUIPopup.ANIM_GROW_FROM_CENTER)
                .edgeProtection(QMUIDisplayHelper.dp2px(context, 12))
                .show(anchor);
        holder[0] = popup;
        return popup;
    }
}
