package ceui.lisa.core

import android.os.Bundle
import androidx.fragment.app.Fragment

class ItemFragment : Fragment() {

    // TODO: Customize parameters
    private var title = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let {
            title = it.getString(ARG_TITLE).toString()
        }
    }

    companion object {

        const val ARG_TITLE = "fragment_title"

        @JvmStatic
        fun newInstance(title: String) =
            ItemFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                }
            }
    }
}
