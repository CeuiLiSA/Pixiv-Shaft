package ceui.loxia

import androidx.fragment.app.Fragment
import ceui.lisa.R

open class NavFragment(layoutId: Int) : Fragment(layoutId)

abstract class SlinkyListFragment(layoutId: Int = R.layout.fragment_slinky_list) : NavFragment(layoutId) {

    open fun isDefaultLayoutManager(): Boolean {
        return true
    }
}