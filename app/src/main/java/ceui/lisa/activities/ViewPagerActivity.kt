package ceui.lisa.activities

import android.graphics.Color
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import ceui.lisa.R
import ceui.lisa.fragments.FragmentSingleIllust
import ceui.lisa.model.IllustsBean
import ceui.lisa.test.BasicActivity
import ceui.lisa.utils.IllustChannel
import com.ToxicBakery.viewpager.transforms.DrawerTransformer
import kotlinx.android.synthetic.main.activity_view_pager.*
import java.util.*

class ViewPagerActivity : BasicActivity() {

    private val dataList = ArrayList<IllustsBean>()

    override fun hideStatusBar(): Boolean {
        return true
    }

    override fun layout(): Int {
        return R.layout.activity_view_pager
    }

    override fun initView() {
        dataList.addAll(IllustChannel.get().illustList)
        viewPager.setPageTransformer(true, DrawerTransformer())
        viewPager.adapter = object : FragmentPagerAdapter(supportFragmentManager) {
            override fun getItem(i: Int): Fragment {
                return FragmentSingleIllust.newInstance(dataList[i])
            }

            override fun getCount(): Int {
                return dataList.size
            }
        }
        viewPager.currentItem = intent.getIntExtra("position", 0)
    }
}
