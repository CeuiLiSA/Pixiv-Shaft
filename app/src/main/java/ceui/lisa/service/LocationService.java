//package ceui.lisa.service;
//
//import com.baidu.location.BDAbstractLocationListener;
//import com.baidu.location.LocationClient;
//import com.baidu.location.LocationClientOption;
//import com.baidu.location.LocationClientOption.LocationMode;
//import android.content.Context;
//
///**
// *
// * @author baidu
// *
// */
//public class LocationService {
//	private LocationClient client = null;
//	private LocationClientOption mOption,DIYoption;
//	private Object  objLock = new Object();
//
//	/***
//	 *
//	 * @param locationContext
//	 */
//	public LocationService(Context locationContext){
//		synchronized (objLock) {
//			if(client == null){
//				client = new LocationClient(locationContext);
//				client.setLocOption(getDefaultLocationClientOption());
//			}
//		}
//	}
//
//	/***
//	 *
//	 * @param listener
//	 * @return
//	 */
//
//	public boolean registerListener(BDAbstractLocationListener listener){
//		boolean isSuccess = false;
//		if(listener != null){
//			client.registerLocationListener(listener);
//			isSuccess = true;
//		}
//		return  isSuccess;
//	}
//
//	public void unregisterListener(BDAbstractLocationListener listener){
//		if(listener != null){
//			client.unRegisterLocationListener(listener);
//		}
//	}
//
//	/***
//	 *
//	 * @param option
//	 * @return isSuccessSetOption
//	 */
//	public boolean setLocationOption(LocationClientOption option){
//		boolean isSuccess = false;
//		if(option != null){
//			if(client.isStarted())
//				client.stop();
//			DIYoption = option;
//			client.setLocOption(option);
//		}
//		return isSuccess;
//	}
//
//	/***
//	 *
//	 * @return DefaultLocationClientOption  默认O设置
//	 */
//	public LocationClientOption getDefaultLocationClientOption(){
//		if(mOption == null){
//			mOption = new LocationClientOption();
//			mOption.setLocationMode(LocationMode.Hight_Accuracy);//可选，默认高精度，设置定位模式，高精度，低功耗，仅设备
//			mOption.setCoorType("bd09ll");//可选，默认gcj02，设置返回的定位结果坐标系，如果配合百度地图使用，建议设置为bd09ll;
//			mOption.setScanSpan(3000);//可选，默认0，即仅定位一次，设置发起连续定位请求的间隔需要大于等于1000ms才是有效的
//		    mOption.setIsNeedAddress(true);//可选，设置是否需要地址信息，默认不需要
//		    mOption.setIsNeedLocationDescribe(true);//可选，设置是否需要地址描述
//		    mOption.setNeedDeviceDirect(false);//可选，设置是否需要设备方向结果
//		    mOption.setLocationNotify(false);//可选，默认false，设置是否当gps有效时按照1S1次频率输出GPS结果
//		    mOption.setIgnoreKillProcess(true);//可选，默认true，定位SDK内部是一个SERVICE，并放到了独立进程，设置是否在stop的时候杀死这个进程，默认不杀死
//		    mOption.setIsNeedLocationDescribe(true);//可选，默认false，设置是否需要位置语义化结果，可以在BDLocation.getLocationDescribe里得到，结果类似于“在北京天安门附近”
//		    mOption.setIsNeedLocationPoiList(true);//可选，默认false，设置是否需要POI结果，可以在BDLocation.getPoiList里得到
//		    mOption.SetIgnoreCacheException(false);//可选，默认false，设置是否收集CRASH信息，默认收集
//			mOption.setOpenGps(true);//可选，默认false，设置是否开启Gps定位
//		    mOption.setIsNeedAltitude(false);//可选，默认false，设置定位时是否需要海拔信息，默认不需要，除基础定位版本都可用
//
//		}
//		return mOption;
//	}
//
//
//	/**
//	 *
//	 * @return DIYOption 自定义Option设置
//	 */
//	public LocationClientOption getOption(){
//		if(DIYoption == null) {
//			DIYoption = new LocationClientOption();
//		}
//		return DIYoption;
//	}
//
//	public void start(){
//		synchronized (objLock) {
//			if(client != null && !client.isStarted()){
//				client.start();
//			}
//		}
//	}
//
//	public void requestLocation(){
//		synchronized (objLock) {
//			if(client != null){
//				client.requestLocation();
//			}
//		}
//	}
//
//	public void stop(){
//		synchronized (objLock) {
//			if(client != null && client.isStarted()){
//				client.stop();
//			}
//		}
//	}
//
//	public boolean isStart() {
//		return client.isStarted();
//	}
//
//	public boolean requestHotSpotState(){
//		return client.requestHotSpotState();
//	}
//
//}
