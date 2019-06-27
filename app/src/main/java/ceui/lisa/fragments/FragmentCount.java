//package ceui.lisa.fragments;
//
//import ceui.lisa.adapters.UserAdapter;
//import ceui.lisa.http.CountApi;
//import ceui.lisa.http.Retro;
//import ceui.lisa.http.Sign;
//import ceui.lisa.model.ListUserResponse;
//import ceui.lisa.model.UserBean;
//import ceui.lisa.model.UserPreviewsBean;
//import io.reactivex.Observable;
//
//public class FragmentCount extends BaseListFragment<ListUserResponse, UserAdapter, UserPreviewsBean> {
//
//    @Override
//    Observable<ListUserResponse> initApi() {
//        return Retro.create(Retro.TENCENT_API, CountApi.class).getRealTimeData(new Sign().buildKey(
//            "GET&/ctr_user_basic/get_realtime_data&app_id=3104085115&idx=10101,10102,10103,10104,10105&end_date=2015-08-17&start_date=2015-07-01"
//        ));
//    }
//
//    @Override
//    Observable<ListUserResponse> initNextApi() {
//        return null;
//    }
//
//    @Override
//    void initAdapter() {
//
//    }
//}
