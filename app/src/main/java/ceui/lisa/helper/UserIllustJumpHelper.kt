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

    /** callback: (offset, targetDate ISO string 或 null) */
    fun interface OnJumpPicked {
        fun onPicked(offset: Int, targetDate: String?)
    }

    @JvmStatic
    fun showJumpDialog(
        activity: Activity,
        userID: Int,
        kind: Kind,
        onJump: OnJumpPicked
    ) {
        if (userID <= 0) return
        val loading = QMUITipDialog.Builder(activity)
            .setIconType(QMUITipDialog.Builder.ICON_TYPE_LOADING)
            .setTipWord(activity.getString(R.string.user_jump_loading))
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
                        Common.showToast(activity.getString(R.string.user_jump_no_works))
                        return
                    }
                    showChoiceDialog(activity, userID, kind, total, onJump)
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
        onJump: OnJumpPicked
    ) {
        if (!activity.isAlive()) return
        val totalPages = (total + PAGE_SIZE - 1) / PAGE_SIZE
        val earliest = activity.getString(R.string.user_jump_to_earliest)
        val byDate = activity.getString(R.string.user_jump_by_date)
        val byPage = activity.getString(R.string.user_jump_by_page)
        val choices = if (kind == Kind.NOVEL) {
            arrayOf(earliest, byPage)
        } else {
            arrayOf(earliest, byDate, byPage)
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.user_jump_dialog_title, total, totalPages))
            .setItems(choices) { _, which ->
                when (choices[which]) {
                    earliest -> {
                        val offset = ((total - 1) / PAGE_SIZE) * PAGE_SIZE
                        onJump.onPicked(offset, null)
                    }
                    byDate -> pickDate(activity, userID, kind, total, onJump)
                    byPage -> pickPage(activity, totalPages, onJump)
                }
            }
            .setNegativeButton(R.string.string_142, null)
            .show()
    }

    private fun pickPage(activity: Activity, totalPages: Int, onJump: OnJumpPicked) {
        val edit = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            hint = activity.getString(R.string.user_jump_page_hint, totalPages)
        }
        val container = LinearLayout(activity).apply {
            setPadding(64, 32, 64, 0)
            addView(edit, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.user_jump_page_dialog_title)
            .setMessage(activity.getString(R.string.user_jump_page_dialog_message, totalPages, PAGE_SIZE))
            .setView(container)
            .setPositiveButton(R.string.sure) { _: DialogInterface, _ ->
                val page = edit.text.toString().toIntOrNull()
                if (page == null || page < 1 || page > totalPages) {
                    Common.showToast(activity.getString(R.string.user_jump_page_range_error, totalPages))
                    return@setPositiveButton
                }
                onJump.onPicked((page - 1) * PAGE_SIZE, null)
            }
            .setNegativeButton(R.string.string_142, null)
            .show()
    }

    private fun pickDate(
        activity: Activity,
        userID: Int,
        kind: Kind,
        total: Int,
        onJump: OnJumpPicked
    ) {
        val now = Calendar.getInstance()
        val listener = DatePickerDialog.OnDateSetListener { _, year, month0, day ->
            val target = LocalDate.of(year, month0 + 1, day)
            locateByDate(activity, userID, kind, total, target, onJump)
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
        onJump: OnJumpPicked
    ) {
        val totalPages = (total + PAGE_SIZE - 1) / PAGE_SIZE
        val targetIso = target.toString()
        if (totalPages <= 1) {
            onJump.onPicked(0, targetIso)
            return
        }
        val tip = QMUITipDialog.Builder(activity)
            .setIconType(QMUITipDialog.Builder.ICON_TYPE_LOADING)
            .setTipWord(activity.getString(R.string.user_jump_locating, target.toString()))
            .create()
        tip.show()

        binarySearch(userID, kind, totalPages, target,
            onDone = { pageIndex ->
                if (tip.isShowing && activity.isAlive()) tip.dismiss()
                if (!activity.isAlive()) return@binarySearch
                // 目标日期落在 page (pageIndex-1) 内（因为该页的 first_date > target 而 page pageIndex 的 first_date ≤ target）
                // 落到这一页后，Fragment 侧用 targetIso 继续 scrollToPosition 到具体 item
                val landing = ((pageIndex - 1).coerceAtLeast(0)) * PAGE_SIZE
                onJump.onPicked(landing, targetIso)
            },
            onFail = { err ->
                if (tip.isShowing && activity.isAlive()) tip.dismiss()
                if (activity.isAlive()) Common.showToast(
                    activity.getString(R.string.user_jump_locate_failed, err.message ?: "unknown")
                )
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
