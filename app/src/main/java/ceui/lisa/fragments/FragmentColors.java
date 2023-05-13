package ceui.lisa.fragments;

import androidx.databinding.ViewDataBinding;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.ColorAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.core.LocalRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.model.ColorItem;

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
        itemList.add(new ColorItem(0, getString(COLOR_NAME_CODES[0]), "#686bdd", current == 0));
        itemList.add(new ColorItem(1, getString(COLOR_NAME_CODES[1]), "#56baec", current == 1));
        itemList.add(new ColorItem(2, getString(COLOR_NAME_CODES[2]), "#008BF3", current == 2));
        itemList.add(new ColorItem(3, getString(COLOR_NAME_CODES[3]), "#03d0bf", current == 3));
        itemList.add(new ColorItem(4, getString(COLOR_NAME_CODES[4]), "#fee65e", current == 4));
        itemList.add(new ColorItem(5, getString(COLOR_NAME_CODES[5]), "#fe83a2", current == 5));
        itemList.add(new ColorItem(6, getString(COLOR_NAME_CODES[6]), "#f44336", current == 6));
        itemList.add(new ColorItem(7, getString(COLOR_NAME_CODES[7]), "#673AB7", current == 7));
        itemList.add(new ColorItem(8, getString(COLOR_NAME_CODES[8]), "#4CAF50", current == 8));
        itemList.add(new ColorItem(9, getString(COLOR_NAME_CODES[9]), "#E91E63", current == 9));
        return itemList;
    }

    public static int[] COLOR_NAME_CODES = new int[]{
                R.string.color_shiYinPurple, //纪念尹子烨（尹桂祥）
                R.string.color_classicBlue,
                R.string.color_officialBlue,
                R.string.color_scallionGreen,
                R.string.color_summerYellow,
                R.string.color_peachPink,
                R.string.color_activeRed,
                R.string.color_classicPurple,
                R.string.color_classicGreen,
                R.string.color_girlPink
    };
    @Override
    public String getToolbarTitle() {return getString(R.string.string_324);}
}
