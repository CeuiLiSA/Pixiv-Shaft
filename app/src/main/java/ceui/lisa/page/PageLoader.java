package ceui.lisa.page;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;

import java.text.NumberFormat;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.models.NovelDetail;
import ceui.lisa.utils.Common;

public class PageLoader {

    private final PageView mPageView;
    private ReadSettingInfo thisSettingInfo;
    private int mBgColor;
    private final Context mContext;
    private final Paint paint = new Paint();
    private boolean dataInitSuccess;
    private int mDisplayWidth;
    private int mDisplayHeight;
    private boolean pageViewInitSuccess;
    private final NovelDetail mNovelDetail;

    private PageInfoModel mCurPage;

    // 当前章节的页面列表
    private List<PageInfoModel> mCurPageList;
    private int thisPage;
    private TurnPageType lastTurnPageType = TurnPageType.NONE;
    // x轴基点
    private int x;
    // y轴基点
    private int y;
    private Canvas canvas;
    private Thread threadUpdateReadPercentage;
    private String percentage;
    // 绘制进度的画笔
    private Paint mProcessPaint;
    private float processX;
    private float processY;
    private int polarLeft;
    private int polarTop;
    private int polarRight;
    private int polarBottom;
    private Rect polar;

    public PageLoader(PageView pageView, NovelDetail novelBean) {
        mPageView = pageView;
        mNovelDetail = novelBean;
        mContext = mPageView.getContext();
        initPageView();
    }

    public static String getPercent(int diliverNum, int queryMailNum) {
        String result = "";
        try {
            // 创建一个数值格式化对象
            NumberFormat numberFormat = NumberFormat.getInstance();
            // 设置精确到小数点后2位
            numberFormat.setMaximumFractionDigits(1);
            result = numberFormat.format((float) diliverNum / (float) queryMailNum * 100);

            if (TextUtils.equals(result, "0")
                    || TextUtils.equals(result, "0.0"))
                result = "0.1";
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return result;
    }

    public boolean prePage(boolean execute) {
        try {
            // 章内上一页
            if (thisPage > 0) {

                if (execute) {
                    thisPage--;
                    mCurPage = mCurPageList.get(thisPage);
                    lastTurnPageType = TurnPageType.PRE;
                    updateReadPercentage();

                    mPageView.drawNextPage();
                }
                return true;
            }
            return false;
        } catch (Exception ex) {
            ex.printStackTrace();
            return false;
        }
    }

    public boolean nextPage(boolean execute) {
        try {
            // 当前章节正常翻页
            if (thisPage < mCurPageList.size() - 1) {
                if (execute) {
                    thisPage++;
                    mCurPage = mCurPageList.get(thisPage);

                    lastTurnPageType = TurnPageType.NEXT;
                    updateReadPercentage();

                    mPageView.drawNextPage();
                }
                return true;
            }
            return false;
        } catch (Exception ex) {
            ex.printStackTrace();
            return false;
        }
    }

    public void pageCancel() {
        try {
            // 取消上一页
            if (lastTurnPageType == TurnPageType.PRE) {
                thisPage++;
                mCurPage = mCurPageList.get(thisPage);
            }
            // 取消下一页
            else if (lastTurnPageType == TurnPageType.NEXT) {
                thisPage--;
                mCurPage = mCurPageList.get(thisPage);
            }

            // 更新阅读率
            updateReadPercentage();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * 确定翻页
     */
    public void turnPage() {

    }

    /**
     * done
     *
     * @param width
     * @param height
     */
    public void pageViewInitSuccess(int width, int height) {
        mDisplayWidth = width;
        mDisplayHeight = height;

        pageViewInitSuccess = true;
        init();
    }

    public boolean isRequesting() {
        return false;
    }

    public void drawPage(Bitmap nextBitmap, boolean b) {
        try {
            Common.showLog("drawContent drawPage ");
            drawBackground(mPageView.getBgBitmap(), b);

            drawContent(nextBitmap);

            //更新绘制
            mPageView.invalidate();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void drawContent(Bitmap bitmap) {
        try {
            Common.showLog("drawContent 00 ");
            canvas = new Canvas(bitmap);

            if (thisSettingInfo.pageAnimType == PageMode.SCROLL) {
                canvas.drawColor(mBgColor);
            }

            if (mCurPage == null || Common.isEmpty(mCurPage.lisText))
                return;

            for (int i = 0; i < mCurPage.lisText.size(); i++) {

                paint.setTextSize(Utility.dip2px(mCurPage.lisText.get(i).textSize));
                paint.setFakeBoldText(mCurPage.lisText.get(i).fakeBoldText);
                paint.setAntiAlias(true);

                // 第一行：paddingTop + 文字高度
                if (i == 0) {
                    x = mPageView.getPaddingLeft();
                    y = mPageView.getPaddingTop() + mCurPage.lisText.get(i).height;
                }
                // 其他行：文字高度
                else {
                    y += mCurPage.lisText.get(i).height;
                }

                // 绘制
                if (!TextUtils.isEmpty(mCurPage.lisText.get(i).text))
                    canvas.drawText(mCurPage.lisText.get(i).text, x, y, paint);
            }


            drawProcess();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void drawBackground(Bitmap bitmap, boolean isUpdate) {
        try {
            canvas = new Canvas(bitmap);

            // 绘制背景
            if (!isUpdate) {
                canvas.drawColor(mBgColor);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public void init() {
        mPageView.setPageMode(thisSettingInfo.pageAnimType);

        mCurPageList = UtilityMeasure.getPageInfos(mNovelDetail, thisSettingInfo, mPageView);
        // 当前页
        if (!Common.isEmpty(mCurPageList)) {
            mCurPage = mCurPageList.get(0);
            Common.showLog("arrWrapContent 33 " + mCurPageList.size());

            updateReadPercentage();
        }
    }

    private void initPageView() {
        try {
            thisSettingInfo = new ReadSettingInfo();
            thisSettingInfo.lineSpacingExtra = UtilityMeasure.getLineSpacingExtra(thisSettingInfo.frontSize);

            // 背景色
            mBgColor = getBgColor(thisSettingInfo.lightType);
            mPageView.setBgColor(mBgColor);
            // 字体颜色
            paint.setColor(mContext.getResources().getColor(thisSettingInfo.frontColor));
            // 翻页动画
            mPageView.setPageMode(thisSettingInfo.pageAnimType);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * 获取背景颜色
     */
    private int getBgColor(int lightType) {

        int color;

        switch (lightType) {
            case ConstantSetting.LIGHTTYPE_1:
                color = ContextCompat.getColor(mContext, R.color.bg1);
                break;

            case ConstantSetting.LIGHTTYPE_2:
                color = ContextCompat.getColor(mContext, R.color.bg2);
                break;

            case ConstantSetting.LIGHTTYPE_3:
                color = ContextCompat.getColor(mContext, R.color.bg3);
                break;

            case ConstantSetting.LIGHTTYPE_4:
                color = ContextCompat.getColor(mContext, R.color.bg4);
                break;

            case ConstantSetting.LIGHTTYPE_5:
                color = ContextCompat.getColor(mContext, R.color.bg5);
                break;

            default:
                color = ContextCompat.getColor(mContext, R.color.bg1);
                break;
        }

        return color;
    }

    /**
     * 数据加载完成
     */
    public void dataInitSuccess() {
        dataInitSuccess = true;

        init();
    }

    /**
     * 更新阅读率
     */
    private void updateReadPercentage() {
        try {
            threadUpdateReadPercentage = new Thread() {
                @Override
                public void run() {

                    if (mNovelDetail == null ||
                            Common.isEmpty(mCurPageList) || mCurPage == null)
                        return;

                    percentage = getPercent(thisPage + 1, mCurPageList.size()) + "%";
                    Common.showLog("percentage " + percentage);
                }
            };
            threadUpdateReadPercentage.start();
        } catch (Exception ex) {
            ex.printStackTrace();
        }

    }

    /**
     * 绘制进度
     */
    private void drawProcess() {
        if (TextUtils.isEmpty(percentage))
            return;

        try {
            // 当前时间
            if (mProcessPaint == null) {
                mProcessPaint = new Paint();
                mProcessPaint.setTextSize(Utility.dip2px(ConstantPageInfo.processTextSize));
                mProcessPaint.setAntiAlias(true);
                mProcessPaint.setDither(true);

                processX = mDisplayWidth - mPageView.getPaddingRight() - ScreenUtils.dpToPx(15) - ScreenUtils.dpToPx(12);
                processY = mDisplayHeight - ScreenUtils.dpToPx(4);
            }

            canvas.drawText(percentage, processX, processY, mProcessPaint);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
