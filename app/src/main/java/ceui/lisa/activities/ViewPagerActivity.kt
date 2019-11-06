package ceui.lisa.activities

import androidx.databinding.ViewDataBinding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import ceui.lisa.R
import ceui.lisa.databinding.ActivityViewPagerBinding
import ceui.lisa.fragments.FragmentSingleIllust
import ceui.lisa.model.IllustsBean
import ceui.lisa.utils.IllustChannel
import com.ToxicBakery.viewpager.transforms.DrawerTransformer
import java.util.*

class ViewPagerActivity : BaseActivity<ActivityViewPagerBinding>() {

    private val dataList = ArrayList<IllustsBean>()

    override fun hideStatusBar(): Boolean {
        return true
    }

    override fun initView() {
        dataList.addAll(IllustChannel.get().illustList)
        baseBind.viewPager.setPageTransformer(true, DrawerTransformer())
        baseBind.viewPager.adapter = object : FragmentPagerAdapter(supportFragmentManager) {
            override fun getItem(i: Int): Fragment {
                return FragmentSingleIllust.newInstance(dataList[i])
            }

            override fun getCount(): Int {
                return dataList.size
            }
        }
        baseBind.viewPager.currentItem = intent.getIntExtra("position", 0)
    }

    override fun initLayout(): Int {
        return R.layout.activity_view_pager
    }

    override fun initData() {
    }
}
