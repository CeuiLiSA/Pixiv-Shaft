package ceui.lisa.utils;

import android.content.Context;
import android.content.Intent;

import ceui.lisa.activities.OutReversActivity;
import ceui.lisa.activities.TemplateActivity;
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
        Common.showToast("Loading");
    }

    @Override
    public void onNext(Response<ResponseBody> response) {
        Intent intent = new Intent(mContext, TemplateActivity.class);
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "以图搜图");
        intent.putExtra("result", new ReverseResult(response));
        mContext.startActivity(intent);
        if (mContext instanceof OutReversActivity) {
            ((OutReversActivity) mContext).finish();
        }
    }

    @Override
    public void onError(Throwable e) {
        e.printStackTrace();
        Common.showToast(e.getMessage());
    }
}
