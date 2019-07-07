//package ceui.lisa.fragments;
//
//import android.location.Geocoder;
//import android.os.Bundle;
//import android.view.View;
//import android.widget.Button;
//
//import com.baidu.location.BDAbstractLocationListener;
//import com.baidu.location.BDLocation;
//import com.baidu.location.LocationClient;
//import com.baidu.location.LocationClientOption;
//
//import ceui.lisa.R;
//import ceui.lisa.activities.Shaft;
//import ceui.lisa.service.LocationService;
//import ceui.lisa.utils.Common;
//
//public class FragmentLocation extends BaseFragment {
//
//    private LocationService locationService;
//
//    @Override
//    public void onCreate(Bundle savedInstanceState) {
//        super.onCreate(savedInstanceState);
//    }
//
//    @Override
//    public void onStart() {
//        super.onStart();
//
//        locationService = ((Shaft) getActivity().getApplication()).locationService;
//        //获取locationservice实例，建议应用中只初始化1个location实例，然后使用，可以参考其他示例的activity，都是通过此种方式获取locationservice实例的
//        locationService.registerListener(new BDAbstractLocationListener() {
//            @Override
//            public void onReceiveLocation(BDLocation bdLocation) {
//                String addr = bdLocation.getAddrStr();    //获取详细地址信息
//                String country = bdLocation.getCountry();    //获取国家
//                String province = bdLocation.getProvince();    //获取省份
//                String city = bdLocation.getCity();    //获取城市
//                String district = bdLocation.getDistrict();    //获取区县
//                String street = bdLocation.getStreet();
//
//
//                Common.showLog(className + "addr " + addr);
//                Common.showLog(className + "country " + country);
//                Common.showLog(className + "province " + province);
//                Common.showLog(className + "city " + city);
//                Common.showLog(className + "district " + district);
//                Common.showLog(className + "street " + street);
//            }
//        });
//        locationService.start();
//
//    }
//
//    @Override
//    public void onStop() {
//        locationService.stop();
//        super.onStop();
//    }
//
//    @Override
//    void initLayout() {
//        mLayoutID = R.layout.fragment_location;
//    }
//
//    @Override
//    View initView(View v) {
//        Button button = v.findViewById(R.id.locate);
//        button.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//
//            }
//        });
//        return v;
//    }
//
//    @Override
//    void initData() {
//    }
//}
