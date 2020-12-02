package ceui.lisa.fragments;

import android.graphics.Color;
import android.view.View;

import androidx.databinding.ViewDataBinding;

import com.blankj.utilcode.util.LanguageUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.ColorAdapter;
import ceui.lisa.adapters.ViewHolder;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.core.LocalRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyColorBinding;
import ceui.lisa.helper.ThemeHelper;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.ColorItem;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;

import static ceui.lisa.utils.Settings.ALL_LANGUAGE;

public class FragmentColors extends LocalListFragment<FragmentBaseListBinding, ColorItem> {

    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        return new ColorAdapter(allItems, mContext);
    }

    @Override
    public BaseRepo repository() {
        return new LocalRepo<List<ColorItem>>() {
            @Override
            public List<ColorItem> first() {
                return getList();
            }

            @Override
            public List<ColorItem> next() {
                return null;
            }
        };
    }

    public List<ColorItem> getList() {
        List<ColorItem> itemList = new ArrayList<>();
        int current = Shaft.sSettings.getThemeIndex();
        itemList.add(new ColorItem(0, "矢尹紫", "#686bdd", current == 0));
        itemList.add(new ColorItem(1, "经典蓝", "#56baec", current == 1));
        itemList.add(new ColorItem(2, "官方蓝", "#008BF3", current == 2));
        itemList.add(new ColorItem(3, "浅葱绿", "#03d0bf", current == 3));
        itemList.add(new ColorItem(4, "盛夏黄", "#fee65e", current == 4));
        itemList.add(new ColorItem(5, "樱桃粉", "#fe83a2", current == 5));
        itemList.add(new ColorItem(6, "元气红", "#f44336", current == 6));
        itemList.add(new ColorItem(7, "基佬紫", "#673AB7", current == 7));
        itemList.add(new ColorItem(8, "老实绿", "#4CAF50", current == 8));
        itemList.add(new ColorItem(9, "少女粉", "#E91E63", current == 9));
        return itemList;
    }

    @Override
    public String getToolbarTitle() {
        return "主题颜色";
    }
}
