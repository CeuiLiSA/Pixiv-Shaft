package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.io.File;

import androidx.databinding.DataBindingUtil;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.database.DownloadEntity;
import ceui.lisa.databinding.RecyDownloadedBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.NovelBean;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;

//已下载
public class DownloadedAdapter extends BaseAdapter<DownloadEntity, RecyDownloadedBinding> {

    private final int imageSize;
    private final int novelImageSize;
    private final SimpleDateFormat mTime = new SimpleDateFormat(
            mContext.getResources().getString(R.string.string_350),
            Locale.getDefault());

    public DownloadedAdapter(List<DownloadEntity> targetList, Context context) {
        super(targetList, context);
        imageSize = DensityUtil.dp2px(140.0f);
        novelImageSize = DensityUtil.dp2px(110.0f);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_downloaded;
    }

    @Override
    public void bindData(DownloadEntity target,
                         ViewHolder<RecyDownloadedBinding> bindView, int position) {

        String fileName = allItems.get(position).getFileName();
        if (!TextUtils.isEmpty(fileName) && fileName.contains(Params.NOVEL_KEY)) {
            ViewGroup.LayoutParams params = bindView.baseBind.illustImage.getLayoutParams();
            params.width = novelImageSize;
            bindView.baseBind.illustImage.setLayoutParams(params);

            NovelBean current = Shaft.sGson.fromJson(allItems.get(position).getIllustGson(), NovelBean.class);
            bindView.baseBind.illustImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Glide.with(mContext)
                    .load(GlideUtil.getUrl(current.getImage_urls().getMedium()))
                    .placeholder(R.color.light_bg)
                    .into(bindView.baseBind.illustImage);
            bindView.baseBind.title.setText(current.getTitle());
            bindView.baseBind.author.setText(String.format("by: %s", current.getUser().getName()));
            bindView.baseBind.time.setText(mTime.format(allItems.get(position).getDownloadTime()));

            bindView.baseBind.pSize.setText(R.string.string_171);

            if (mOnItemClickListener != null) {
                bindView.itemView.setOnClickListener(v -> {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.CONTENT, current);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情");
                    intent.putExtra("hideStatusBar", true);
                    mContext.startActivity(intent);
                });
                bindView.baseBind.deleteItem.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 2));
            }
        } else {
            ViewGroup.LayoutParams params = bindView.baseBind.illustImage.getLayoutParams();
            params.height = imageSize;
            params.width = imageSize;
            bindView.baseBind.illustImage.setLayoutParams(params);

            IllustsBean currentIllust = Shaft.sGson.fromJson(allItems.get(position).getIllustGson(), IllustsBean.class);
            if (!TextUtils.isEmpty(allItems.get(position).getFileName()) &&
                    allItems.get(position).getFileName().contains(".zip")) {
                bindView.baseBind.illustImage.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                Glide.with(mContext)
                        .load(R.mipmap.zip)
                        .placeholder(R.color.light_bg)
                        .into(bindView.baseBind.illustImage);
            } else {
                if (currentIllust.isGif()) {
                    bindView.baseBind.illustImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    Glide.with(mContext)
                            .load(currentIllust.getImage_urls().getMedium())
                            .placeholder(R.color.light_bg)
                            .into(bindView.baseBind.illustImage);
                } else {
                    bindView.baseBind.illustImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    // filePath 在不同时期保存的形态不一样：
                    //   - 重构前：本地真实路径 (/storage/.../foo.jpg) — Q+ scoped
                    //     storage 下本 app 已无权直接读，必然失败
                    //   - 重构后：content:// URI (MediaStore 或 SAF document)
                    //   - 文件被删 / SAF 授权被吊销：URI 失效
                    // 统一走 Uri.parse 让 Glide 通过 ContentResolver 取流，
                    // 失败再回退到网络缩略图，避免列表里只剩占位色块。
                    String filePath = allItems.get(position).getFilePath();
                    Object localModel = parseLocalImageModel(filePath);
                    GlideUrl fallbackThumb = GlideUtil.getMediumImg(currentIllust);
                    Glide.with(mContext)
                            .load(localModel != null ? localModel : fallbackThumb)
                            .placeholder(R.color.light_bg)
                            .error(Glide.with(mContext).load(fallbackThumb))
                            .into(bindView.baseBind.illustImage);
                }
            }
            bindView.baseBind.title.setText(allItems.get(position).getFileName());
            bindView.baseBind.author.setText(String.format("by: %s", currentIllust.getUser().getName()));
            bindView.baseBind.time.setText(mTime.format(allItems.get(position).getDownloadTime()));

            if (currentIllust.getPage_count() == 1) {
                bindView.baseBind.pSize.setVisibility(View.GONE);
            } else {
                bindView.baseBind.pSize.setVisibility(View.VISIBLE);
                bindView.baseBind.pSize.setText(String.format(Locale.getDefault(), "%dP", currentIllust.getPage_count()));
            }

            if (mOnItemClickListener != null) {
                bindView.itemView.setOnClickListener(v ->
                        mOnItemClickListener.onItemClick(v, position, 0));
                bindView.baseBind.author.setOnClickListener(v -> {
                    bindView.baseBind.author.setTag(currentIllust.getUser().getId());
                    mOnItemClickListener.onItemClick(bindView.baseBind.author, position, 1);
                });
                bindView.baseBind.deleteItem.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 2));
            }
        }
        //从-400 丝滑滑动到0
        ((DownloadedHolder) bindView).spring.setCurrentValue(-400);
        ((DownloadedHolder) bindView).spring.setEndValue(0);
    }

    @Override
    public ViewHolder<RecyDownloadedBinding> getNormalItem(ViewGroup parent) {
        return new DownloadedHolder(
                DataBindingUtil.inflate(
                        LayoutInflater.from(mContext), mLayoutID, parent, false
                )
        );
    }

    /**
     * 把存进 DB 的 filePath 字符串归一化成 Glide 能处理的 model：
     *   - content://...  → Uri，Glide 走 ContentResolver
     *   - file:///...    → Uri，Glide 走 file scheme
     *   - 裸路径 /storage/... → File（且仅当当前进程能读到时才用，
     *     否则返回 null，让上层走网络回退）
     *   - 其它（空 / 解析失败）→ null
     */
    private static Object parseLocalImageModel(String filePath) {
        if (TextUtils.isEmpty(filePath)) return null;
        if (filePath.startsWith("content://") || filePath.startsWith("file://")) {
            try {
                return Uri.parse(filePath);
            } catch (Exception ignored) {
                return null;
            }
        }
        if (filePath.startsWith("/")) {
            File f = new File(filePath);
            // Q+ scoped storage：旧版本下载留下的真实路径多半已无权读取。
            // 用 canRead 而不是 exists，避免 Glide 反复触发 SecurityException。
            return f.canRead() ? f : null;
        }
        return null;
    }
}
