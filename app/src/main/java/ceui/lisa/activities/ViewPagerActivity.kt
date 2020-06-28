package ceui.lisa.activities

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager.widget.ViewPager
import ceui.lisa.R
import ceui.lisa.databinding.ActivityViewPagerBinding
import ceui.lisa.utils.DataChannel
import ceui.lisa.utils.PixivOperate.insertIllustViewHistory
import ceui.lisa.viewmodel.Dust
import com.ToxicBakery.viewpager.transforms.DrawerTransformer

class ViewPagerActivity : BaseActivity<ActivityViewPagerBinding>() {

    private lateinit var holder: Dust

    override fun hideStatusBar(): Boolean {
        return true
    }

    override fun initView() {
        holder = ViewModelProvider(this).get(Dust::class.java)
        holder.dust.value = ArrayList()
        holder.dust.value?.addAll(DataChannel.get().illustList)
        holder.index.observe(this, Observer{ index ->
            run {
                baseBind.viewPager.currentItem = index
            }
        })
        baseBind.viewPager.setPageTransformer(true, DrawerTransformer())
        baseBind.viewPager.addOnPageChangeListener(object :ViewPager.OnPageChangeListener{
            override fun onPageScrollStateChanged(state: Int) {
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
            }

            override fun onPageSelected(position: Int) {
                holder.index.value = position
                if (Shaft.sSettings.isSaveViewHistory) {
                    insertIllustViewHistory(holder.dust?.value!![position])
                }
            }
        })

        baseBind.viewPager.adapter = object : FragmentPagerAdapter(supportFragmentManager, 0) {
            override fun getItem(i: Int): Fragment {
//                return FragmentSingleIllust.newInstance(i)
                return Fragment()
            }

            override fun getCount(): Int {
                return holder.dust.value?.size!!
            }
        }
        val p = intent.getIntExtra("position", -1)
        if (p != -1) {
            holder.index.value = p
        }
    }

    override fun initLayout(): Int {
        return R.layout.activity_view_pager
    }

    override fun initData() {
    }
}
