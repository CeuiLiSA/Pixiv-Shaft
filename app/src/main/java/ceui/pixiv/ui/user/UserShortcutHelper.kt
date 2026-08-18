package ceui.pixiv.ui.user

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.MainActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.models.UserBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 把作者主页固定为桌面快捷方式（issue #1027）。
 *
 * 桌面图标用作者头像（圆形裁剪），点击直达该作者主页。
 */
object UserShortcutHelper {

    /**
     * 桌面是否支持固定快捷方式。部分第三方 launcher 和 API 26 以下的老桌面不支持，
     * 不支持时菜单项直接不展示，别让用户点了才发现没反应。
     */
    fun isSupported(context: Context): Boolean {
        return try {
            ShortcutManagerCompat.isRequestPinShortcutSupported(context)
        } catch (t: Throwable) {
            // 国产改版 ROM 的 ShortcutManager 实现可能直接抛（同 #477）
            Common.showLog("UserShortcutHelper isSupported failed: $t")
            false
        }
    }

    fun pin(activity: FragmentActivity, user: UserBean) {
        val appContext = activity.applicationContext
        activity.lifecycleScope.launch {
            val icon = withContext(Dispatchers.IO) { loadAvatarIcon(appContext, user) }
            if (!activity.isFinishing && !activity.isDestroyed) {
                request(activity, user, icon)
            }
        }
    }

    private fun request(context: Context, user: UserBean, icon: IconCompat) {
        val label = user.name?.takeIf { it.isNotBlank() } ?: user.id.toString()
        try {
            val shortcut = ShortcutInfoCompat.Builder(context, "user_${user.id}")
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(icon)
                .setIntents(buildIntents(context, user.id))
                .build()
            if (!ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)) {
                Common.showToast(context.getString(R.string.add_to_home_screen_failed))
            }
        } catch (t: Throwable) {
            Common.showLog("UserShortcutHelper pin failed: $t")
            Common.showToast(context.getString(R.string.add_to_home_screen_failed))
        }
    }

    /**
     * 快捷方式带一整条返回栈：MainActivity 打底、作者页在上。
     *
     * 只丢一个作者页进去的话，从桌面进来按返回会直接退出 App；铺上 MainActivity 后
     * 返回就落到首页，且首页停在哪个 tab 由「设置 · 界面 · 启动页」决定
     * （MainActivity 自己读 navigationInitPosition，这里不用管）。
     *
     * 指向 UActivity 而不是 UserActivityV3：作者页 V2/V3 的分发口在 UActivity 里，
     * 按点击时的设置走。快捷方式是长期留在桌面上的，不能把创建那一刻的 V2/V3 选择焊死。
     */
    private fun buildIntents(context: Context, userId: Int): Array<Intent> {
        val home = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val userPage = Intent(context, UActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(Params.USER_ID, userId)
        }
        return arrayOf(home, userPage)
    }

    private fun loadAvatarIcon(context: Context, user: UserBean): IconCompat {
        val size = launcherIconSize(context)
        val bitmap = runCatching {
            Glide.with(context)
                .asBitmap()
                .load(GlideUtil.getHead(user))
                .submit(size, size)
                .get()
        }.getOrNull()
        val circular = bitmap?.let { runCatching { toCircle(it, size) }.getOrNull() }
        return if (circular != null) {
            IconCompat.createWithBitmap(circular)
        } else {
            // 头像拉不下来（无网络/头像 404）也得让快捷方式建得出来，退回 App 图标
            IconCompat.createWithResource(context, R.mipmap.ic_launcher_round)
        }
    }

    private fun launcherIconSize(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val size = am?.launcherLargeIconSize ?: 0
        return if (size > 0) size else 192
    }

    /** 头像按短边居中裁成正方形再抠成圆，避免非 1:1 头像被拉变形。 */
    private fun toCircle(source: Bitmap, size: Int): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val side = minOf(source.width, source.height)
        val src = Rect(
            (source.width - side) / 2,
            (source.height - side) / 2,
            (source.width + side) / 2,
            (source.height + side) / 2,
        )
        val dst = RectF(0f, 0f, size.toFloat(), size.toFloat())

        canvas.drawOval(dst, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, src, dst, paint)
        return output
    }
}
