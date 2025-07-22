package ceui.pixiv.ui.opengl

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentOpenglBinding
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.viewBinding

class GLFragment : PixivFragment(R.layout.fragment_opengl) {

    private val binding by viewBinding(FragmentOpenglBinding::bind)

    private lateinit var glSurfaceView: MyGLSurfaceView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireContext()
        // 创建 GLSurfaceView
        glSurfaceView = MyGLSurfaceView(context)

        binding.rootLayout.addView(glSurfaceView)
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
    }
}