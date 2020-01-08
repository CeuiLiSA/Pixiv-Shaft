package ceui.lisa.gif;

import android.widget.ProgressBar;

import com.liulishuo.okdownload.DownloadTask;

import org.greenrobot.eventbus.EventBus;

import java.io.File;

import ceui.lisa.database.IllustTask;
import ceui.lisa.download.FileCreator;
import ceui.lisa.download.GifDownload;
import ceui.lisa.download.GifListener;
import ceui.lisa.download.GifQueue;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.GifResponse;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;

/**
 * 等待重构
 */
public class GifManager {

    public void getZipUrl(IllustsBean illust, ProgressBar progressBar) {
        Common.showToast("正在获取GIF资源");
        Retro.getAppApi().getGifPackage(sUserModel.getResponse().getAccess_token(), illust.getId())
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<GifResponse>() {
                    @Override
                    public void onNext(GifResponse gifResponse) {
                        File parentFile = FileCreator.createGifParentFile(illust);
                        if (parentFile.exists() && parentFile.length() > 1000) {
                            Channel channel = new Channel();
                            channel.setReceiver("FragmentSingleIllust");
                            channel.setObject(illust.getId());
                            channel.setValue(gifResponse.getUgoira_metadata().getFrames().get(0).getDelay());
                            EventBus.getDefault().post(channel);
                        } else {
                            downloadGif(gifResponse, illust, progressBar);
                        }
                    }
                });
    }


    private static final String MAP_KEY = "Referer";
    private static final String IMAGE_REFERER = "https://app-api.pixiv.net/";


    public static void downloadGif(GifResponse response, IllustsBean illustsBean, ProgressBar progressBar) {
        if (response == null) {
            return;
        }

        File file = FileCreator.createGifFile(illustsBean);
        DownloadTask.Builder builder = new DownloadTask.Builder(
                response.getUgoira_metadata().getZip_urls().getMedium(),
                file.getParentFile())
                .setFilename(file.getName())
                .setMinIntervalMillisCallbackProcess(30)
                .setPassIfAlreadyCompleted(true);
        builder.addHeader(MAP_KEY, IMAGE_REFERER);
        DownloadTask task = builder.build();

        IllustTask illustTask = new IllustTask();
        illustTask.setDownloadTask(task);
        illustsBean.setGifDelay(response.getUgoira_metadata().getFrames().get(0).getDelay());

        illustTask.setIllustsBean(illustsBean);
        GifQueue.get().addTask(illustTask);

        task.enqueue(new GifListener(illustsBean, progressBar));
        Common.showToast("图组ZIP已加入下载队列");
    }
}
