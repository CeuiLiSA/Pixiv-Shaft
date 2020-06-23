package ceui.lisa.fragments;

import android.view.View;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.effective.android.panel.PanelSwitchHelper;

import ceui.lisa.R;
import ceui.lisa.adapters.EmojiAdapter;
import ceui.lisa.databinding.FragmentComingBinding;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.EmojiItem;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Emoji;
import ceui.lisa.view.EditTextWithSelection;

public class FragmentComing extends BaseFragment<FragmentComingBinding> {

    private PanelSwitchHelper mHelper;

    public static FragmentComing newInstance() {
        return new FragmentComing();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mHelper == null) {
            mHelper = new PanelSwitchHelper.Builder(this)
                    .contentCanScrollOutside(true)    //可选模式，默认true，当面板实现时内容区域是否往上滑动
                    .logTrack(true)                   //可选，默认false，是否开启log信息输出
                    .build(false);              //可选，默认false，是否默认打开输入法
        }
    }

    @Override
    public void initView(View view) {
        super.initView(view);
        RecyclerView recyclerView = view.findViewById(R.id.recy_list);
        GridLayoutManager layoutManager = new GridLayoutManager(getContext(), 6);
        recyclerView.setLayoutManager(layoutManager);
        EmojiAdapter adapter = new EmojiAdapter(Emoji.getEmojis(), getContext());
        adapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                final EmojiItem item = adapter.getItemAt(position);

                String name = item.getName();
                String show = baseBind.inputBox.getText().toString();
                if (selection < show.length()) {
                    String left = show.substring(0, selection);
                    String right = show.substring(selection);
                    commitString = left + name + right;



                    baseBind.inputBox.setText(left + name + right);
                    baseBind.inputBox.setSelection(selection + name.length());
                } else {
                    String result = show + name;
                    commitString = result;


                    baseBind.inputBox.setText(result);
                    baseBind.inputBox.setSelection(result.length());
                }
                Common.showLog(className + selection);

            }
        });
        recyclerView.setAdapter(adapter);
        baseBind.inputBox.setOnSelectionChange(new EditTextWithSelection.OnSelectionChange() {
            @Override
            public void onChange(int start, int end) {
                if (start != 0) {
                    selection = start;
                }
            }
        });
    }

    private int selection = 0;

    private String commitString = "";

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_coming;
    }
}
