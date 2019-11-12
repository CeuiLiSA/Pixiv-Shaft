package ceui.lisa.fragments;

import ceui.lisa.R;
import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.databinding.FragmentAboutUserBinding;
import ceui.lisa.interfaces.Display;
import ceui.lisa.model.UserDetailResponse;
import ceui.lisa.utils.Common;

public class FragmentAboutUser extends BaseBindFragment<FragmentAboutUserBinding> implements
        Display<UserDetailResponse> {

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_about_user;
    }

    @Override
    public void show(UserDetailResponse response) {
        baseBind.mainPage.setHtml(Common.checkEmpty(response.getProfile().getWebpage()));
        baseBind.twitter.setHtml(Common.checkEmpty(response.getProfile().getTwitter_url()));
        baseBind.description.setHtml(Common.checkEmpty(response.getUser().getComment()));
        baseBind.pawoo.setHtml(Common.checkEmpty(response.getProfile().getPawoo_url()));
        baseBind.computer.setText(Common.checkEmpty(response.getWorkspace().getPc()));
        baseBind.monitor.setText(Common.checkEmpty(response.getWorkspace().getMonitor()));
        baseBind.app.setText(Common.checkEmpty(response.getWorkspace().getTool()));
        baseBind.scanner.setText(Common.checkEmpty(response.getWorkspace().getScanner()));
        baseBind.drawBoard.setText(Common.checkEmpty(response.getWorkspace().getTablet()));
        baseBind.mouse.setText(Common.checkEmpty(response.getWorkspace().getMouse()));
        baseBind.printer.setText(Common.checkEmpty(response.getWorkspace().getPrinter()));
        baseBind.tableObjects.setText(Common.checkEmpty(response.getWorkspace().getDesktop()));
        baseBind.likeMusic.setText(Common.checkEmpty(response.getWorkspace().getMusic()));
        baseBind.table.setText(Common.checkEmpty(response.getWorkspace().getDesk()));
        baseBind.chair.setText(Common.checkEmpty(response.getWorkspace().getChair()));
    }

    @Override
    void initData() {
        UserDetailResponse user = ((UserDetailResponse) mActivity.getIntent().getSerializableExtra(
                TemplateFragmentActivity.EXTRA_OBJECT));
        show(user);
    }
}
