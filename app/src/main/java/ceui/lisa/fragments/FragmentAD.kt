package ceui.lisa.fragments

import android.content.Intent
import android.os.Bundle
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.model.AdItem
import ceui.lisa.databinding.FragmentAdBinding
import ceui.lisa.utils.Params
import com.bumptech.glide.Glide

class FragmentAD : BaseBindFragment<FragmentAdBinding>() {

    lateinit var mAdItem: AdItem

    override fun initBundle(bundle: Bundle) {
        mAdItem = bundle.getSerializable(Params.CONTENT) as AdItem
    }

    override fun initLayout() {
        mLayoutID = R.layout.fragment_ad
    }

    override fun initData() {
        Glide.with(mContext)
                .load(mAdItem.imageUrl)
                .into(baseBind.image)
        baseBind.image.setOnClickListener {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接")
            intent.putExtra(Params.URL, mAdItem.link)
            intent.putExtra(Params.TITLE, mAdItem.title)
            startActivity(intent)
        }
    }

    companion object{
        fun newInstance(adItem: AdItem): FragmentAD{
            val args = Bundle()
            args.putSerializable(Params.CONTENT, adItem)
            val fragment = FragmentAD()
            fragment.arguments = args
            return fragment
        }
    }
}