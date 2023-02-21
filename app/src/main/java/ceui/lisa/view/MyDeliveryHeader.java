package ceui.lisa.view;

import android.content.Context;
import ceui.lisa.R;
import ceui.lisa.activities.Shaft;

public class MyDeliveryHeader extends DeliveryHeader {

    // protected static is guilty
    // run one time to change default color
    static{
        cloudColors[0] = Shaft.getContext().getResources().getColor(R.color.delivery_header_cloud);
    }

    // invoked when App Level Configuration Changed
    public static void changeCloudColor(Context context){
        cloudColors[0] = context.getResources().getColor(R.color.delivery_header_cloud);
    }

    public MyDeliveryHeader(Context context) {
        super(context);
    }
}
