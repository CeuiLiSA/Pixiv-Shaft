package ceui.pixiv.muzei

import androidx.fragment.app.Fragment
import ceui.lisa.activities.TemplateActivity

/**
 * Muzei「来源设置」入口(manifest 里 provider 的 settingsActivity 指过来,必须 exported)。
 * 复用 [TemplateActivity] 的容器,但固定装 [MuzeiSettingsFragment],不读 EXTRA_FRAGMENT ——
 * Muzei 拉起时不会带任何 extra。Shaft 自己的设置页也直接 start 这个 Activity,不做
 * start+finish 蹦床(平板双栏下 finish 会连坐)。
 */
class MuzeiSettingsActivity : TemplateActivity() {

    override fun createNewFragment(): Fragment = MuzeiSettingsFragment()
}
