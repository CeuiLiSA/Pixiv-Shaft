package ceui.lisa.fragments

import android.content.Intent
import android.os.Bundle
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.core.Sony
import ceui.lisa.databinding.FragmentABinding
import ceui.lisa.databinding.FragmentAdBinding
import ceui.lisa.databinding.FragmentAdBindingImpl
import ceui.lisa.utils.Params
import com.bumptech.glide.Glide

class FragmentAD : BaseBindFragment<FragmentAdBinding>() {

    lateinit var sony: Sony

    override fun initBundle(bundle: Bundle) {
        sony = bundle.getSerializable(Params.CONTENT) as Sony
    }

    override fun initLayout() {
        mLayoutID = R.layout.fragment_ad
    }

    override fun initData() {
        Glide.with(mContext)
                .load(sony.imageUrl)
                .into(baseBind.image)
        baseBind.image.setOnClickListener {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接")
            intent.putExtra(Params.URL, sony.link)
            intent.putExtra(Params.TITLE, sony.title)
            startActivity(intent)
        }
    }

    companion object{
        fun newInstance(sony: Sony): FragmentAD{
            val args = Bundle()
            args.putSerializable(Params.CONTENT, sony)
            val fragment = FragmentAD()
            fragment.arguments = args
            return fragment
        }
    }
}