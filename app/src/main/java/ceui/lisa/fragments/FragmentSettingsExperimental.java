package ceui.lisa.fragments;

import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;

import com.google.firebase.analytics.FirebaseAnalytics;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.FragmentSettingsExperimentalBinding;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;

/** 设置 · 试验性 */
public class FragmentSettingsExperimental extends SettingsPageFragment<FragmentSettingsExperimentalBinding> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_settings_experimental;
    }

    @Override
    protected void initData() {
        // google(Play)渠道:聊天室 / 广场是站外 UGC 入口,合规起见整组不出现。认 IS_LITE
        // 而不是 debug 口径 —— lite 的 debug 包同样没有,与 SettingsCatalog 索引一致。
        if (ceui.lisa.BuildConfig.IS_LITE) {
            baseBind.showChatRoomEntryRela.setVisibility(View.GONE);
            baseBind.showChatRoomPushBannerRela.setVisibility(View.GONE);
            baseBind.showChatRoomPushBannerDivider.setVisibility(View.GONE);
            baseBind.showPlazaEntryRela.setVisibility(View.GONE);
            // 上面整组消失后 Firebase 成了页内唯一一行,它原本用来跟前一组拉开的
            // 上外边距就成了页首一块空白,去掉。
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) baseBind.isFirebaseEnableRela.getLayoutParams();
            lp.topMargin = 0;
            bindFirebaseRow();
            return;
        }

        boolean chatRoomOn = Shaft.sSettings.isShowChatRoomEntry();
        baseBind.showChatRoomEntry.setChecked(chatRoomOn);
        // push banner 行只在「聊天室入口」开启时展示
        int bannerRowVisibility = chatRoomOn ? View.VISIBLE : View.GONE;
        baseBind.showChatRoomPushBannerRela.setVisibility(bannerRowVisibility);
        baseBind.showChatRoomPushBannerDivider.setVisibility(bannerRowVisibility);
        baseBind.showChatRoomPushBanner.setChecked(Shaft.sSettings.isShowChatRoomPushBanner());
        baseBind.showPlazaEntry.setChecked(Shaft.sSettings.isShowPlazaEntry());

        baseBind.showChatRoomEntry.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setShowChatRoomEntry(isChecked);
                // push banner 行随「聊天室入口」联动显隐;banner 是否真正弹出由 ChatBannerBridge
                // 同时校验 showChatRoomEntry && showChatRoomPushBanner 决定,所以这里只切显隐、
                // 保留子开关自身的值(关掉再打开聊天室不会丢失用户的 push 偏好)。
                int visibility = isChecked ? View.VISIBLE : View.GONE;
                baseBind.showChatRoomPushBannerRela.setVisibility(visibility);
                baseBind.showChatRoomPushBannerDivider.setVisibility(visibility);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.showChatRoomEntryRela.setOnClickListener(v ->
                baseBind.showChatRoomEntry.performClick());

        baseBind.showChatRoomPushBanner.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setShowChatRoomPushBanner(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.showChatRoomPushBannerRela.setOnClickListener(v ->
                baseBind.showChatRoomPushBanner.performClick());

        baseBind.showPlazaEntry.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setShowPlazaEntry(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.showPlazaEntryRela.setOnClickListener(v ->
                baseBind.showPlazaEntry.performClick());

        bindFirebaseRow();
    }

    private void bindFirebaseRow() {
        baseBind.isFirebaseEnable.setChecked(Shaft.sSettings.isFirebaseEnable());
        baseBind.isFirebaseEnable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setFirebaseEnable(isChecked);
                Local.setSettings(Shaft.sSettings);
                Common.showToast(getString(R.string.string_428), 2);
                FirebaseAnalytics.getInstance(mContext).setAnalyticsCollectionEnabled(isChecked);
            }
        });
        baseBind.isFirebaseEnableRela.setOnClickListener(v ->
                baseBind.isFirebaseEnable.performClick());
    }
}
