package ceui.lisa.utils;

import android.content.Context;
import android.content.Intent;

import ceui.lisa.activities.TemplateFragmentActivity;
import io.reactivex.disposables.Disposable;
import okhttp3.ResponseBody;
import retrofit2.Response;

public class ReverseWebviewCallback implements ReverseImage.Callback {

    private final Context mContext;

    public ReverseWebviewCallback(Context context) {
        mContext = context;
    }

    @Override
    public void onSubscribe(Disposable d) {
//        TODO 使用Notification
        Common.showToast("Loading");
    }

    @Override
    public void onNext(Response<ResponseBody> response) {
        Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
        intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "以图搜图");
        intent.putExtra("result", new ReverseResult(response));
        mContext.startActivity(intent);
    }

    @Override
    public void onError(Throwable e) {
        Common.showToast(e.getMessage());
    }
}
