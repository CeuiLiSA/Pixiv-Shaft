package ceui.lisa.helper

import android.app.Activity
import android.content.DialogInterface
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import ceui.lisa.R
import ceui.lisa.http.NullCtrl
import ceui.lisa.http.Retro
import ceui.lisa.repo.buildOffsetUrl
import ceui.lisa.utils.Common
import com.qmuiteam.qmui.widget.dialog.QMUITipDialog
import com.wdullaer.materialdatetimepicker.date.DatePickerDialog
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Calendar

/**
 * 支持用户跳转到指定位置（按时间 / 按页码 / 最早作品）
 * 依赖: Pixiv App API 的 /v1/user/illusts 支持任意 offset（已实测 user 440400 验证）
 */
object UserIllustJumpHelper {

    const val PAGE_SIZE = 30

    enum class Kind { ILLUST, MANGA, NOVEL }

    @JvmStatic
    fun showJumpDialog(
        activity: Activity,
        userID: Int,
        kind: Kind,
        onOffsetPicked: (Int) -> Unit
    ) {
        if (userID <= 0) return
        val loading = QMUITipDialog.Builder(activity)
            .setIconType(QMUITipDialog.Builder.ICON_TYPE_LOADING)
            .setTipWord("加载中...")
            .create()
        loading.show()

        Retro.getAppApi().getUserDetail(userID)
            .subscribeOn(Schedulers.newThread())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : NullCtrl<ceui.lisa.models.UserDetailResponse>() {
                override fun success(resp: ceui.lisa.models.UserDetailResponse) {
                    if (activity.isAlive()) loading.dismiss()
                    if (!activity.isAlive()) return
                    val profile = resp.profile ?: return
                    val total = when (kind) {
                        Kind.ILLUST -> profile.total_illusts
                        Kind.MANGA -> profile.total_manga
                        Kind.NOVEL -> profile.total_novels
                    }
                    if (total <= 0) {
                        Common.showToast("ta 没有这个类型的作品")
                        return
                    }
                    showChoiceDialog(activity, userID, kind, total, onOffsetPicked)
                }

                override fun must(isSuccess: Boolean) {
                    if (!isSuccess && loading.isShowing && activity.isAlive()) loading.dismiss()
                }
            })
    }

    private fun Activity.isAlive(): Boolean = !isFinishing && !isDestroyed

    private fun showChoiceDialog(
        activity: Activity,
        userID: Int,
        kind: Kind,
        total: Int,
        onOffsetPicked: (Int) -> Unit
    ) {
        if (!activity.isAlive()) return
        val totalPages = (total + PAGE_SIZE - 1) / PAGE_SIZE
        val message = "共 $total 件作品，$totalPages 页"
        val choices = if (kind == Kind.NOVEL) {
            // Novel 没测过任意 offset 是否可用，但按页码/跳到最早都只靠单次请求，风险可控；时间跳转依赖 create_date 排序未知，暂不提供
            arrayOf("跳到最早作品", "按页码跳转")
        } else {
            arrayOf("跳到最早作品", "按时间跳转", "按页码跳转")
        }
        AlertDialog.Builder(activity)
            .setTitle("跳转到…")
            .setMessage(message)
            .setItems(choices) { _, which ->
                when (choices[which]) {
                    "跳到最早作品" -> {
                        val offset = ((total - 1) / PAGE_SIZE) * PAGE_SIZE
                        onOffsetPicked(offset)
                    }
                    "按时间跳转" -> pickDate(activity, userID, kind, total, onOffsetPicked)
                    "按页码跳转" -> pickPage(activity, totalPages, onOffsetPicked)
                }
            }
            .setNegativeButton(R.string.string_142, null)
            .show()
    }

    private fun pickPage(activity: Activity, totalPages: Int, onOffsetPicked: (Int) -> Unit) {
        val edit = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            hint = "输入页码 (1-$totalPages)"
        }
        val container = LinearLayout(activity).apply {
            setPadding(64, 32, 64, 0)
            addView(edit, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(activity)
            .setTitle("跳到第几页")
            .setMessage("共 $totalPages 页，每页 $PAGE_SIZE 件")
            .setView(container)
            .setPositiveButton(R.string.sure) { _: DialogInterface, _ ->
                val page = edit.text.toString().toIntOrNull()
                if (page == null || page < 1 || page > totalPages) {
                    Common.showToast("页码需要在 1 到 $totalPages 之间")
                    return@setPositiveButton
                }
                onOffsetPicked((page - 1) * PAGE_SIZE)
            }
            .setNegativeButton(R.string.string_142, null)
            .show()
    }

    private fun pickDate(
        activity: Activity,
        userID: Int,
        kind: Kind,
        total: Int,
        onOffsetPicked: (Int) -> Unit
    ) {
        val now = Calendar.getInstance()
        val listener = DatePickerDialog.OnDateSetListener { _, year, month0, day ->
            val target = LocalDate.of(year, month0 + 1, day)
            locateByDate(activity, userID, kind, total, target, onOffsetPicked)
        }
        val dpd = DatePickerDialog.newInstance(
            listener,
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH)
        )
        val start = Calendar.getInstance().apply { set(2007, 0, 1) } // Pixiv 创立于 2007
        dpd.setMinDate(start)
        dpd.setMaxDate(now)
        dpd.setAccentColor(Common.resolveThemeAttribute(activity, androidx.appcompat.R.attr.colorPrimary))
        dpd.setThemeDark(activity.resources.getBoolean(R.bool.is_night_mode))
        if (activity is FragmentActivity) {
            dpd.show(activity.supportFragmentManager, "UserIllustJumpDatePicker")
        }
    }

    /**
     * 二分查找：作品按 create_date 倒序排列，找到目标日期覆盖的页面。
     * 策略：取最新 first_date <= target 的最小页索引 p，用 (p-1)*PAGE_SIZE 作为起点，
     * 让列表呈现目标日期前后的作品。
     */
    private fun locateByDate(
        activity: Activity,
        userID: Int,
        kind: Kind,
        total: Int,
        target: LocalDate,
        onOffsetPicked: (Int) -> Unit
    ) {
        val totalPages = (total + PAGE_SIZE - 1) / PAGE_SIZE
        if (totalPages <= 1) {
            onOffsetPicked(0)
            return
        }
        val tip = QMUITipDialog.Builder(activity)
            .setIconType(QMUITipDialog.Builder.ICON_TYPE_LOADING)
            .setTipWord("正在定位 $target …")
            .create()
        tip.show()

        binarySearch(userID, kind, totalPages, target,
            onDone = { pageIndex ->
                if (tip.isShowing && activity.isAlive()) tip.dismiss()
                if (!activity.isAlive()) return@binarySearch
                val landing = ((pageIndex - 1).coerceAtLeast(0)) * PAGE_SIZE
                onOffsetPicked(landing)
            },
            onFail = { err ->
                if (tip.isShowing && activity.isAlive()) tip.dismiss()
                if (activity.isAlive()) Common.showToast("定位失败：${err.message ?: "unknown"}")
            }
        )
    }

    private fun binarySearch(
        userID: Int,
        kind: Kind,
        totalPages: Int,
        target: LocalDate,
        onDone: (Int) -> Unit,
        onFail: (Throwable) -> Unit,
        left: Int = 0,
        right: Int = totalPages - 1,
    ) {
        if (left >= right) {
            onDone(left)
            return
        }
        val mid = (left + right) / 2
        val offset = mid * PAGE_SIZE
        fetchFirstDate(userID, kind, offset,
            onSuccess = { firstDate ->
                if (firstDate == null) {
                    // 空页，视为越界，收缩右边界
                    binarySearch(userID, kind, totalPages, target, onDone, onFail, left, (mid - 1).coerceAtLeast(left))
                    return@fetchFirstDate
                }
                // first_date 是该页最新作品；数据按时间倒序。
                // first_date <= target 说明目标日期在更新的页里（更小的 p）
                if (!firstDate.isAfter(target)) {
                    binarySearch(userID, kind, totalPages, target, onDone, onFail, left, mid)
                } else {
                    binarySearch(userID, kind, totalPages, target, onDone, onFail, mid + 1, right)
                }
            },
            onFailure = onFail
        )
    }

    private fun fetchFirstDate(
        userID: Int,
        kind: Kind,
        offset: Int,
        onSuccess: (LocalDate?) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val api = Retro.getAppApi()
        when (kind) {
            Kind.NOVEL -> {
                val url = "https://app-api.pixiv.net/v1/user/novels?user_id=$userID&offset=$offset"
                api.getNextNovel(url)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(object : NullCtrl<ceui.lisa.model.ListNovel>() {
                        override fun success(r: ceui.lisa.model.ListNovel) {
                            val d = r.list?.firstOrNull()?.create_date
                            onSuccess(parseDate(d))
                        }
                        override fun nullSuccess() { onSuccess(null) }
                        override fun error(e: Throwable) { onFailure(e) }
                    })
            }
            else -> {
                val type = if (kind == Kind.MANGA) "manga" else "illust"
                api.getNextIllust(buildOffsetUrl(userID, type, offset))
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(object : NullCtrl<ceui.lisa.model.ListIllust>() {
                        override fun success(r: ceui.lisa.model.ListIllust) {
                            val d = r.list?.firstOrNull()?.create_date
                            onSuccess(parseDate(d))
                        }
                        override fun nullSuccess() { onSuccess(null) }
                        override fun error(e: Throwable) { onFailure(e) }
                    })
            }
        }
    }

    private fun parseDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(raw).toLocalDate()
        } catch (_: Exception) {
            null
        }
    }
}
