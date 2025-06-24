package ceui.pixiv.ui.rank

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.lisa.databinding.FragmentRankViewpagerBinding
import ceui.loxia.ObjectType
import ceui.loxia.threadSafeArgs
import ceui.pixiv.ui.circles.PagedFragmentItem
import ceui.pixiv.ui.circles.SmartFragmentPagerAdapter
import ceui.pixiv.ui.common.TitledViewPagerFragment
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.setUpWith
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class RankFragment : TitledViewPagerFragment(R.layout.fragment_rank_viewpager) {

    private val binding by viewBinding(FragmentRankViewpagerBinding::bind)
    private val safeArgs by threadSafeArgs<RankFragmentArgs>()
    private val rankDayViewModal by viewModels<RankDayViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.optionLayout.updatePadding(0, insets.top, 0, 0)
            binding.toolbar.updateLayoutParams {
                height = insets.top - 10.ppppx
            }
            windowInsets
        }

        rankDayViewModal.rankDay.observe(viewLifecycleOwner) { day ->
            if (day?.isNotEmpty() == true) {
                binding.selectDate.text = day
                binding.clearDate.isVisible = true
            } else {
                binding.selectDate.text = getString(R.string.rank_select_date)
                binding.clearDate.isVisible = false
            }
        }

        binding.clearDate.setOnClick {
            rankDayViewModal.applyRankDay(null)
        }

        binding.selectDate.setOnClick {
            showDatePicker()
        }

        binding.appBar.addOnOffsetChangedListener { appBarLayout, verticalOffset ->
            val totalScrollRange = appBarLayout.totalScrollRange
            if (totalScrollRange == 0) {
                return@addOnOffsetChangedListener
            }

            val percentage = (Math.abs(verticalOffset) / totalScrollRange.toFloat())
            binding.optionLayout.alpha = 1F - percentage
        }


        val seeds = if (safeArgs.objectType == ObjectType.ILLUST) {
            listOf(
                "day",
                "week",
                "month",
                "day_ai",
                "day_male",
                "day_female",
                "week_original",
                "week_rookie"
            )
        } else {
            listOf(
                "day_manga",
                "week_manga",
                "month_manga",
                "week_rookie_manga"
            )
        }
        val adapter = SmartFragmentPagerAdapter(
            seeds.map { str ->
                PagedFragmentItem(
                    builder = {
                        RankingIllustsFragment().apply {
                            arguments = RankingIllustsFragmentArgs(str).toBundle()
                        }
                    }, initialTitle = str
                )
            }, this
        )
        binding.rankViewpager.adapter = adapter
        binding.tabLayoutList.setUpWith(
            binding.rankViewpager, binding.slidingCursor, viewLifecycleOwner, {})
    }


    private fun showDatePicker() {
        // 设置最大日期为今天的前一天
        val maxDate = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, -1)
        }

        // 设置最小日期为 2008-08-01
        val minDate = Calendar.getInstance().apply {
            set(2008, Calendar.AUGUST, 1)  // 注意：月份是从 0 开始的
        }

        // 获取默认选中的日期
        val defaultCalendar = Calendar.getInstance()

        val lastSelectedDateStr = rankDayViewModal.rankDay.value  // 假设你在 ViewModel 里有这个字段
        if (!lastSelectedDateStr.isNullOrEmpty()) {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val date = sdf.parse(lastSelectedDateStr)
                if (date != null) {
                    defaultCalendar.time = date
                }
            } catch (ex: Exception) {
                // fallback 到 maxDate
                Timber.e(ex)
                defaultCalendar.time = maxDate.time
            }
        } else {
            defaultCalendar.time = maxDate.time
        }

        val datePickerDialog = DatePickerDialog(
            requireActivity(),
            { _, year, month, dayOfMonth ->
                val selectedCalendar = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth)
                }

                val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val formattedDate = formatter.format(selectedCalendar.time)

                rankDayViewModal.applyRankDay(formattedDate)
            },
            defaultCalendar.get(Calendar.YEAR),
            defaultCalendar.get(Calendar.MONTH),
            defaultCalendar.get(Calendar.DAY_OF_MONTH)
        )

        datePickerDialog.datePicker.minDate = minDate.timeInMillis
        datePickerDialog.datePicker.maxDate = maxDate.timeInMillis

        datePickerDialog.show()
    }
}