package ceui.lisa.fragments;

import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.effective.android.panel.PanelSwitchHelper;
import com.effective.android.panel.interfaces.listener.OnKeyboardStateListener;
import com.effective.android.panel.interfaces.listener.OnPanelChangeListener;
import com.effective.android.panel.view.panel.IPanelView;
import com.effective.android.panel.view.panel.PanelView;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.EmojiAdapter;
import ceui.lisa.core.ImgGetter;
import ceui.lisa.databinding.FragmentComingBinding;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.Content;
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
                    .build(false);	          //可选，默认false，是否默认打开输入法
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
                if (selection < current.size()) {
                    current.add(selection, item);


                    Content temp = new Content();
                    temp.setEmoji(true);
                    content.add(selection, temp);
                } else {
                    current.add(item);


                    Content temp = new Content();
                    temp.setEmoji(true);
                    content.add(temp);
                }


                SpannableString spannableString = new SpannableString(Html.fromHtml(Emoji.transform(emojiToString()),
                        new ImgGetter(baseBind.inputBox), null));
                baseBind.inputBox.setText(spannableString);


                if (selection < current.size()) {
                    baseBind.inputBox.setSelection(selection + 1);
                } else {
                    current.add(adapter.getItemAt(position));
                    baseBind.inputBox.setSelection(baseBind.inputBox.getText().toString().length());
                    selection = baseBind.inputBox.getText().toString().length();
                }



            }
        });
        recyclerView.setAdapter(adapter);
        baseBind.inputBox.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if(keyCode == KeyEvent.KEYCODE_DEL && event.getAction() != KeyEvent.ACTION_UP) {
                    if (selection > 0) {
                        final int index = selection - 1;
                        if (index < current.size()) {
                            current.remove(index);
                            selection = selection - 1;
                        }
                    }
                }
                return false;
            }
        });
        baseBind.inputBox.setOnSelectionChange(new EditTextWithSelection.OnSelectionChange() {
            @Override
            public void onChange(int start, int end) {
                if (start != 0) {
                    selection = start;
                }
            }
        });
        baseBind.inputBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                Common.showLog(className + "afterTextChanged " + s + " " + "￼".equals(s.toString()));

            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private int selection = 0;

    private List<EmojiItem> current = new ArrayList<>();
    private List<Content> content = new ArrayList<>();

    private String emojiToString() {
        String result = "";
        for (EmojiItem emojiItem : current) {
            result = result + emojiItem.getName();
        }
        return result;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_coming;
    }
}
