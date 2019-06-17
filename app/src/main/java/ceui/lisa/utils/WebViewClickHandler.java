package ceui.lisa.utils;

import android.content.Context;
import android.view.MenuItem;
import android.view.View;

public class WebViewClickHandler implements MenuItem.OnMenuItemClickListener {
    public static final int OPEN_IN_BROWSER = 0x0;
    public static final int OPEN_IMAGE = 0x1;
    public static final int COPY_LINK_ADDRESS = 0x2;
    public static final int COPY_LINK_TEXT = 0x3;
    public static final int DOWNLOAD_LINK = 0x4;
    public static final int SEARCH_GOOGLE = 0x5;
    public static final int SHARE_LINK = 0x6;

    public WebViewClickHandler(Context context, View Parent) {

    }


    public boolean onMenuItemClick(MenuItem item) {
        switch (item.getItemId()) {

            case OPEN_IN_BROWSER: {
                break;
            }
            case OPEN_IMAGE: {
                break;
            }
            case COPY_LINK_ADDRESS: {
                break;
            }
            case COPY_LINK_TEXT: {
                break;
            }
            case DOWNLOAD_LINK: {
                break;
            }
            case SEARCH_GOOGLE: {
                break;
            }
            case SHARE_LINK: {
                break;
            }
        }

        return true;
    }
}
