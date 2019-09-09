package ceui.lisa.interfaces;

import android.view.LayoutInflater;
import android.view.ViewGroup;


public interface Binding<T> {

    T getBind(LayoutInflater inflater, ViewGroup container);
}
