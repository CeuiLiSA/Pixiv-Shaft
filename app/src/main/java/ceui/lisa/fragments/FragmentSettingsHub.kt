package ceui.lisa.fragments

import android.Manifest
import android.content.pm.PackageManager
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import ceui.lisa.R
import ceui.lisa.databinding.FragmentSettingsHubBinding
import androidx.core.content.ContextCompat
import ceui.lisa.utils.Common
import ceui.pixiv.witstudio.theme.WitRowStyle

/**
 * 设置主页（两级设置的第一级）：MD3-E 分类列表 + 全量设置项搜索。
 * 具体设置项都在 SettingsCatalog 定义的分类页里。
 */
class FragmentSettingsHub : BaseFragment<FragmentSettingsHubBinding>() {

    override fun initLayout() {
        mLayoutID = R.layout.fragment_settings_hub
    }

    override fun initData() {
        baseBind.toolbar.setNavigationOnClickListener { mActivity.finish() }

        // 搜索胶囊底色跟随主题色（同分类行的隐约 tint）
        val palette = ceui.pixiv.witstudio.theme.V3Palette.from(mContext)
        (baseBind.searchBar.background.mutate() as? android.graphics.drawable.GradientDrawable)
            ?.setColor(palette.cardFill)

        buildCategoryList()

        baseBind.searchClear.setOnClickListener { baseBind.searchInput.setText("") }
        baseBind.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onQueryChanged(s?.toString().orEmpty())
            }
        })

        // Android 10 以下：设置页涉及备份/恢复等文件读写，进入时先要存储权限（沿用旧设置页行为）。
        if (!Common.isAndroidQ() && ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_CODE_STORAGE_PERMISSION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_STORAGE_PERMISSION) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Common.showToast(getString(R.string.access_denied))
                finish()
            }
        }
    }

    private fun buildCategoryList() {
        val container = baseBind.categoryContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(mContext)
        val categories = SettingsCatalog.categories
        // icon 圆底也联动主题色：在行底 cardFill 上再混入一截 primary，比行底稍显色
        val palette = ceui.pixiv.witstudio.theme.V3Palette.from(mContext)
        val iconCircle = androidx.core.graphics.ColorUtils.blendARGB(
            palette.cardFill, palette.primary, if (palette.isDark) 0.16f else 0.14f)
        categories.forEachIndexed { index, category ->
            val row = inflater.inflate(R.layout.item_settings_category, container, false)
            row.setBackgroundResource(backgroundFor(index, categories.size))
            (row.findViewById<View>(R.id.category_icon_wrap).background.mutate()
                    as? android.graphics.drawable.GradientDrawable)?.setColor(iconCircle)
            row.findViewById<ImageView>(R.id.category_icon).setImageResource(category.iconRes)
            row.findViewById<TextView>(R.id.category_title).text = getString(category.titleRes)
            row.findViewById<TextView>(R.id.category_subtitle).text = subtitleFor(category)
            row.setOnClickListener {
                SettingsCatalog.open(mContext, category)
            }
            SettingsCatalog.applyThemedRowBg(row)
            container.addView(row)
        }
    }

    /** 分类行的子项预览：把该分类下前几个设置项标题串起来，同时兼当搜索提示。 */
    private fun subtitleFor(category: SettingsCatalog.Category): String {
        val lang = resources.configuration.locales[0].language
        val separator = if (lang == "zh" || lang == "ja") "、" else ", "
        return SettingsCatalog.entriesOf(category)
            .take(4)
            .joinToString(separator) { getString(it.titleRes) }
    }

    private fun onQueryChanged(query: String) {
        val q = query.trim()
        baseBind.searchClear.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
        if (q.isEmpty()) {
            baseBind.categoryContainer.visibility = View.VISIBLE
            baseBind.searchResultsContainer.visibility = View.GONE
            baseBind.searchEmpty.visibility = View.GONE
            return
        }
        val results = SettingsCatalog.search(mContext, q).take(MAX_RESULTS)
        baseBind.categoryContainer.visibility = View.GONE
        val container = baseBind.searchResultsContainer
        container.removeAllViews()
        if (results.isEmpty()) {
            container.visibility = View.GONE
            baseBind.searchEmpty.visibility = View.VISIBLE
            return
        }
        baseBind.searchEmpty.visibility = View.GONE
        container.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(mContext)
        results.forEachIndexed { index, entry ->
            val row = inflater.inflate(R.layout.item_settings_search_result, container, false)
            row.setBackgroundResource(backgroundFor(index, results.size))
            row.findViewById<TextView>(R.id.result_title).text = getString(entry.titleRes)
            val path = StringBuilder(getString(entry.category.titleRes))
            if (entry.descRes != 0) {
                path.append(" · ").append(getString(entry.descRes))
            }
            row.findViewById<TextView>(R.id.result_path).text = path
            row.setOnClickListener {
                SettingsCatalog.open(mContext, entry.category, entry.idName)
            }
            SettingsCatalog.applyThemedRowBg(row)
            container.addView(row)
        }
    }

    private fun backgroundFor(index: Int, total: Int): Int =
        WitRowStyle.rowBackground(index, total)

    companion object {
        private const val MAX_RESULTS = 30
        private const val REQUEST_CODE_STORAGE_PERMISSION = 1001
    }
}
