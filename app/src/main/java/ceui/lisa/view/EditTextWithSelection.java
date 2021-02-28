package ceui.lisa.view;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class EditTextWithSelection extends androidx.appcompat.widget.AppCompatEditText {

    private OnSelectionChange mOnSelectionChange;

    public EditTextWithSelection(@NonNull Context context) {
        super(context);
    }

    public EditTextWithSelection(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public EditTextWithSelection(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        if (mOnSelectionChange != null) {
            mOnSelectionChange.onChange(selStart, selEnd);
        }
        super.onSelectionChanged(selStart, selEnd);
    }

    public OnSelectionChange getOnSelectionChange() {
        return mOnSelectionChange;
    }

    public void setOnSelectionChange(OnSelectionChange onSelectionChange) {
        mOnSelectionChange = onSelectionChange;
    }

    public interface OnSelectionChange{
        void onChange(int start, int end);
    }
}
