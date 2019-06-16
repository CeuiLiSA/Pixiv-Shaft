package ceui.lisa.fragments;

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
            mainPage.setHtml(response.getProfile().getWebpage());
            twitter.setHtml(response.getProfile().getTwitter_account() + " / " + response.getProfile().getTwitter_url());
            desc.setHtml(response.getUser().getComment());
            pawoo.setHtml(response.getProfile().getPawoo_url());
            computer.setText(response.getWorkspace().getPc());
            monitor.setText(response.getWorkspace().getMonitor());
            app.setText(response.getWorkspace().getTool());
            scanner.setText(response.getWorkspace().getScanner());
            drawBoard.setText(response.getWorkspace().getTablet());
            mouse.setText(response.getWorkspace().getMouse());
            printer.setText(response.getWorkspace().getPrinter());
            desktopObjects.setText(response.getWorkspace().getDesktop());
            music.setText(response.getWorkspace().getMusic());
            desk.setText(response.getWorkspace().getDesk());
            chair.setText(response.getWorkspace().getChair());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
