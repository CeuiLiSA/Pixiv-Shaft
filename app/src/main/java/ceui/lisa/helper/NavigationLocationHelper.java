package ceui.lisa.helper;

import android.content.res.Resources;

import java.util.LinkedHashMap;
import java.util.Map;

import androidx.fragment.app.Fragment;
import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.fragments.FragmentCenter;
import ceui.lisa.fragments.FragmentLeft;
import ceui.lisa.fragments.FragmentRight;
import ceui.lisa.fragments.FragmentViewPager;
import ceui.lisa.utils.Params;

public class NavigationLocationHelper {
    private static Resources resources = Shaft.getContext().getResources();

    public static final String LATEST = "LATEST"; // 上次位置
    public static final String TUIJIAN = "TUIJIAN";
    public static final String FAXIAN = "FAXIAN";
    public static final String DONGTAI = "DONGTAI";
    public static final String R18 = "R18";

    public static final Map<String, NavigationItem> NAVIGATION_MAP = new LinkedHashMap<String, NavigationItem>() {
        {
            put(TUIJIAN, new NavigationItem(R.string.recommend, R.drawable.ic_tuijian, FragmentLeft.class));
            put(FAXIAN, new NavigationItem(R.string.discover, R.drawable.ic_discover, FragmentCenter.class));
            put(DONGTAI, new NavigationItem(R.string.whats_new, R.drawable.ic_dongtai, FragmentRight.class));
            put(R18, new NavigationItem(R.string.string_r, R.drawable.ic_xiongbu, FragmentViewPager.class));
        }
    };

    public static final Map<String, String> SETTING_NAME_MAP = new LinkedHashMap<String, String>() {
        {
            put(LATEST, resources.getString(R.string.string_427));
            put(TUIJIAN, resources.getString(R.string.recommend));
            put(FAXIAN, resources.getString(R.string.discover));
            put(DONGTAI, resources.getString(R.string.whats_new));
            put(R18, resources.getString(R.string.string_r));
        }
    };

    public static class NavigationItem {
        private int titleResId;
        private int iconResId;
        private Class instanceClass;

        public NavigationItem(int titleResId, int iconResId, Class instanceClass) {
            this.titleResId = titleResId;
            this.iconResId = iconResId;
            this.instanceClass = instanceClass;
        }

        public int getTitleResId() {
            return titleResId;
        }

        public int getIconResId() {
            return iconResId;
        }

        public Class getInstanceClass() {
            return instanceClass;
        }

        public Fragment getFragment() {
            if (instanceClass == null) {
                return new Fragment();
            }
            if (instanceClass == FragmentLeft.class) {
                return new FragmentLeft();
            } else if (instanceClass == FragmentCenter.class) {
                return new FragmentCenter();
            } else if (instanceClass == FragmentRight.class) {
                return new FragmentRight();
            } else if (instanceClass == FragmentViewPager.class) {
                return FragmentViewPager.newInstance(Params.VIEW_PAGER_R18);
            }
            return new Fragment();
        }
    }
}
