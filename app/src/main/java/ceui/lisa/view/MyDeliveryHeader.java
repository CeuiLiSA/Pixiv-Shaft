package ceui.lisa.view;

import android.content.Context;
import com.scwang.smartrefresh.header.DeliveryHeader;
import ceui.lisa.R;
import ceui.lisa.activities.Shaft;

public class MyDeliveryHeader extends DeliveryHeader{

    // protected static is guilty
    public static MyDeliveryHeader getNewInstance(Context context){
        cloudColors[0] = Shaft.getContext().getResources().getColor(R.color.delivery_header_cloud);
        return new MyDeliveryHeader(context);
    }

    public MyDeliveryHeader(Context context) {
        super(context);
    }
}
