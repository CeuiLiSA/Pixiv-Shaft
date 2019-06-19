package ceui.lisa.fragments;

import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import org.sufficientlysecure.htmltextview.HtmlTextView;

import ceui.lisa.R;
import ceui.lisa.response.UserDetailResponse;
import ceui.lisa.utils.Common;

public class FragmentAboutUser extends BaseFragment {

    private HtmlTextView desc, mainPage, twitter, pawoo;
    private TextView computer, monitor, app, scanner, drawBoard, mouse, printer, desktopObjects, music, desk, chair;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_about_user;
    }

    @Override
    View initView(View v) {
        mainPage = v.findViewById(R.id.main_page);
        twitter = v.findViewById(R.id.twitter);
        desc = v.findViewById(R.id.description);
        pawoo = v.findViewById(R.id.pawoo);
        computer = v.findViewById(R.id.computer);
        monitor = v.findViewById(R.id.monitor);
        app = v.findViewById(R.id.app);
        scanner = v.findViewById(R.id.scanner);
        drawBoard = v.findViewById(R.id.draw_board);
        mouse = v.findViewById(R.id.mouse);
        printer = v.findViewById(R.id.printer);
        desktopObjects = v.findViewById(R.id.table_objects);
        music = v.findViewById(R.id.like_music);
        desk = v.findViewById(R.id.table);
        chair = v.findViewById(R.id.chair);
        Common.showLog(className + "initView 结束");
        return v;
    }

    @Override
    void initData() {
    }

    public void setData(UserDetailResponse response) {
        try {
            mainPage.setHtml(TextUtils.isEmpty(response.getProfile().getWebpage()) ?
                    "" : response.getProfile().getWebpage());
            twitter.setHtml(TextUtils.isEmpty(response.getProfile().getTwitter_url()) ?
                    "" : response.getProfile().getTwitter_url());
            desc.setHtml(TextUtils.isEmpty(response.getUser().getComment()) ?
                    "" : response.getUser().getComment());
            pawoo.setHtml(TextUtils.isEmpty(response.getProfile().getPawoo_url()) ?
                    "" : response.getProfile().getPawoo_url());
            computer.setText(TextUtils.isEmpty(response.getWorkspace().getPc()) ?
                    "" : response.getWorkspace().getPc());
            monitor.setText(TextUtils.isEmpty(response.getWorkspace().getMonitor()) ?
                    "" : response.getWorkspace().getMonitor());
            app.setText(TextUtils.isEmpty(response.getWorkspace().getTool()) ?
                    "" : response.getWorkspace().getTool());
            scanner.setText(TextUtils.isEmpty(response.getWorkspace().getScanner()) ?
                    "" : response.getWorkspace().getScanner());
            drawBoard.setText(TextUtils.isEmpty(response.getWorkspace().getTablet()) ?
                    "" : response.getWorkspace().getTablet());
            mouse.setText(TextUtils.isEmpty(response.getWorkspace().getMouse()) ?
                    "" : response.getWorkspace().getMouse());
            printer.setText(TextUtils.isEmpty(response.getWorkspace().getPrinter()) ?
                    "" : response.getWorkspace().getPrinter());
            desktopObjects.setText(TextUtils.isEmpty(response.getWorkspace().getDesktop()) ?
                    "" : response.getWorkspace().getDesktop());
            music.setText(TextUtils.isEmpty(response.getWorkspace().getMusic()) ?
                    "" : response.getWorkspace().getMusic());
            desk.setText(TextUtils.isEmpty(response.getWorkspace().getDesk()) ?
                    "" : response.getWorkspace().getDesk());
            chair.setText(TextUtils.isEmpty(response.getWorkspace().getChair()) ?
                    "" : response.getWorkspace().getChair());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
