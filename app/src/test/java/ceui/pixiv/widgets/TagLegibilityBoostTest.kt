package ceui.pixiv.widgets

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import ceui.pixiv.ui.settings.ThemeColorCatalog
import ceui.pixiv.witstudio.theme.ApcaContrast
import ceui.pixiv.witstudio.theme.V3Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * 「提升标签文字辨识度」的行为约束。
 *
 * 只断言性质、不断言色值 —— 色值依赖 ColorUtils 的 HSL 往返，逐位硬编码会在换 JDK 或换
 * Android 版本时假报警。真正要守住的是五条：默认参数等于显式 0、0 点不回归、满值到达 APCA
 * 正文线、滑条只增不减、以及**每一档主题色都必须跟着滑条换色**。
 *
 * 参考底统一取 α20 合成 cardFill：它既是选中态的实底，也是深浅两模式下**最差**的那个底
 * （见 V3Palette.textTag 的 KDoc），按它算就够，不必对每个宿主底各算一遍。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class TagLegibilityBoostTest {

    private val presets = ThemeColorCatalog.entries.map { Color.parseColor(it.hex) }
    private val modes = listOf(false, true)

    private fun chipBackground(palette: V3Palette): Int =
        ColorUtils.compositeColors(palette.alpha20, palette.cardFill)

    private fun lc(palette: V3Palette): Double =
        abs(ApcaContrast.lc(palette.textTag, chipBackground(palette)))

    private fun name(primary: Int): String = String.format("#%06X", primary and 0xFFFFFF)

    @Test
    fun `默认参数与显式零值一致`() {
        for (dark in modes) {
            for (primary in presets) {
                assertEquals(
                    V3Palette(primary, dark).textTag,
                    V3Palette(primary, dark, 0f).textTag,
                )
            }
        }
    }

    @Test
    fun `零值不回归 —— 仍守住既有的 4_5 保底`() {
        val failures = mutableListOf<String>()
        for (dark in modes) {
            for (primary in presets) {
                val palette = V3Palette(primary, dark, 0f)
                val contrast = ColorUtils.calculateContrast(palette.textTag, chipBackground(palette))
                if (contrast < 4.5) failures += "${name(primary)}, dark=$dark: $contrast"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `满值到达 APCA 正文线`() {
        val failures = mutableListOf<String>()
        for (dark in modes) {
            for (primary in presets) {
                val value = lc(V3Palette(primary, dark, 1f))
                if (value < ApcaContrast.BODY_TEXT_MIN) {
                    failures += "${name(primary)}, dark=$dark: $value"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `滑条只增不减`() {
        for (dark in modes) {
            for (primary in presets) {
                val background = chipBackground(V3Palette(primary, dark))
                var previous = 0.0
                for (step in 0..10) {
                    val value = abs(ApcaContrast.lc(
                        V3Palette(primary, dark, step / 10f).textTag, background))
                    assertTrue(
                        "${name(primary)}, dark=$dark, step=$step: $value 低于 $previous",
                        value >= previous - 0.01,
                    )
                    previous = value
                }
            }
        }
    }

    @Test
    fun `增强只改原文色 —— 其余传入色号逐位相同`() {
        // 这是对"胶囊是否被整体提亮"的代码层结论：把增强前后所有会传给 View 的色号逐个比对。
        var textChanged = 0
        for (dark in modes) {
            for (primary in presets) {
                val off = V3Palette(primary, dark, 0f)
                val on = V3Palette(primary, dark, 1f)
                val who = "${name(primary)} dark=$dark"

                // ① 填充与描边 —— 必须完全相同
                assertEquals(who, off.alpha08, on.alpha08)
                assertEquals(who, off.alpha10, on.alpha10)
                assertEquals(who, off.alpha15, on.alpha15)
                assertEquals(who, off.alpha20, on.alpha20)
                assertEquals(who, off.alpha30, on.alpha30)
                assertEquals(who, off.cardFill, on.cardFill)
                assertEquals(who, off.tagLockedBg().color?.defaultColor, on.tagLockedBg().color?.defaultColor)
                assertEquals(who, off.pillSecondary().color?.defaultColor, on.pillSecondary().color?.defaultColor)

                // ② 非原文角色 —— 必须完全相同
                assertEquals(who, off.textTagAux, on.textTagAux)
                assertEquals(who, off.textAccent, on.textAccent)
                assertEquals(who, off.textSecondary, on.textSecondary)
                assertEquals(who, off.onPrimary, on.onPrimary)

                // ③ 原文 —— 唯一允许变化的
                if (off.textTag != on.textTag) textChanged++
                println("$who  fill=${Integer.toHexString(off.tagLockedBg().color!!.defaultColor)}" +
                        "  aux=${Integer.toHexString(off.textTagAux)}" +
                        "  pin=${Integer.toHexString(off.textAccent)}" +
                        "  text: ${Integer.toHexString(off.textTag)} -> ${Integer.toHexString(on.textTag)}")
            }
        }
        // 至少要有主色真的变了，否则"没变化"是因为功能没生效而不是因为设计如此
        assertTrue("原文色一档都没变，增强没生效", textChanged > 0)
        println(">>> 原文色发生变化的主色档数：$textChanged / ${presets.size * modes.size}")
    }

    @Test
    fun `每一档主题色都跟着滑条换色`() {
        // 这是本次的核心约束：滑条拉到满值，十档主题色**每一档都必须换色**。
        // 目标取 `现状 + boost × |75 − 现状|`；早先用 `max(0, …)` 钳位时，距离为负的那一侧
        // 目标正好等于现状，被原样返回 —— 滑条从 0 拉到 100% 也看不到任何变化。
        val frozen = mutableListOf<String>()
        for (dark in modes) {
            for (primary in presets) {
                if (V3Palette(primary, dark, 0f).textTag == V3Palette(primary, dark, 1f).textTag) {
                    frozen += "${name(primary)}, dark=$dark"
                }
            }
        }
        assertTrue("这些档拉满滑条也没换色：${frozen.joinToString()}", frozen.isEmpty())
    }
}