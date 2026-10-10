package ceui.pixiv.ui.novel.reader.settings

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import ceui.lisa.R
import java.io.File

/**
 * 小说阅读器可下载的中日文字体（#1060）。全部来自 Google Fonts，SIL OFL 1.1 授权，可免费再分发。
 *
 * 文件取 google/fonts 仓库钉死在 [GOOGLE_FONTS_COMMIT] 的那一份：地址不会漂，字节数与 SHA-256
 * 写死在这里逐个校验，所以中间经过哪个 GitHub 加速站都不影响正确性。列表里的预览图
 * （[previewRes]）由 `scripts/gen_reader_font_previews.py` 从同一份文件生成，换 commit 时一起重跑。
 *
 * 可变字体（[variable]）的默认实例是 Thin / ExtraLight，必须用 [Typeface.Builder] 指定 wght，
 * 那是 API 26 才有的接口 —— 所以 API 24/25 上不列出它们（[isSupported]）。
 */
enum class ReaderWebFont(
    val id: String,
    @StringRes val nameRes: Int,
    val group: Group,
    /** `ofl/` 下的路径，已做 URL 编码。 */
    private val repoPath: String,
    val byteSize: Long,
    private val sha256: String,
    private val variable: Boolean,
    private val minWeight: Int,
    @DrawableRes val previewRes: Int,
) {
    NOTO_SANS_SC(
        "web_noto_sans_sc", R.string.reader_font_noto_sans_sc, Group.CHINESE,
        "notosanssc/NotoSansSC%5Bwght%5D.ttf", 17_772_300L,
        "a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da",
        variable = true, minWeight = 100, R.drawable.reader_font_preview_noto_sans_sc,
    ),
    NOTO_SERIF_SC(
        "web_noto_serif_sc", R.string.reader_font_noto_serif_sc, Group.CHINESE,
        "notoserifsc/NotoSerifSC%5Bwght%5D.ttf", 25_125_512L,
        "050080d9255a86808f2945bffac582b31ef32bc36411ce29563b4961670c66f9",
        variable = true, minWeight = 200, R.drawable.reader_font_preview_noto_serif_sc,
    ),
    LXGW_WENKAI_TC(
        "web_lxgw_wenkai_tc", R.string.reader_font_lxgw_wenkai, Group.CHINESE,
        "lxgwwenkaitc/LXGWWenKaiTC-Regular.ttf", 13_110_528L,
        "4fcc5aec11cbbf737b0cfab7b63796f7f280087a7a656b2f13342cd5e5318d95",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_lxgw_wenkai_tc,
    ),
    NOTO_SANS_JP(
        "web_noto_sans_jp", R.string.reader_font_noto_sans_jp, Group.JAPANESE,
        "notosansjp/NotoSansJP%5Bwght%5D.ttf", 9_589_900L,
        "c2f3b4d463500a2ddcd3849cded1fceeb9fd6d1c32e6cbecd568453ba50fc68f",
        variable = true, minWeight = 100, R.drawable.reader_font_preview_noto_sans_jp,
    ),
    NOTO_SERIF_JP(
        "web_noto_serif_jp", R.string.reader_font_noto_serif_jp, Group.JAPANESE,
        "notoserifjp/NotoSerifJP%5Bwght%5D.ttf", 13_574_352L,
        "2fd527ba12b6a44ec30d796d633360da0aeba6c5d4af1304ce12bb4dc15a7dfc",
        variable = true, minWeight = 200, R.drawable.reader_font_preview_noto_serif_jp,
    ),
    KLEE_ONE(
        "web_klee_one", R.string.reader_font_klee_one, Group.JAPANESE,
        "kleeone/KleeOne-Regular.ttf", 8_724_204L,
        "bf4063f030cc2ae6adf0a11424a1888e5c0eb4438f1f6d02f52294af868e9b3a",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_klee_one,
    ),
    ZEN_MARU_GOTHIC(
        "web_zen_maru_gothic", R.string.reader_font_zen_maru_gothic, Group.JAPANESE,
        "zenmarugothic/ZenMaruGothic-Regular.ttf", 3_832_756L,
        "a0c0b53543e0993ae2225e629c833f3d51495ad31720694ff112ce4ce11111ef",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_zen_maru_gothic,
    );

    enum class Group(@StringRes val titleRes: Int) {
        CHINESE(R.string.reader_font_group_chinese),
        JAPANESE(R.string.reader_font_group_japanese),
    }

    val isSupported: Boolean get() = !variable || Build.VERSION.SDK_INT >= 26

    val downloadUrl: String
        get() = "https://github.com/google/fonts/raw/$GOOGLE_FONTS_COMMIT/ofl/$repoPath"

    fun file(context: Context): File = File(dir(context), "$id.ttf")

    fun matches(bytes: Long, sha256Hex: String): Boolean = bytes == byteSize && sha256Hex == sha256

    /** 已下载且本机支持时返回字体，否则 null（由调用方回退到系统字体）。 */
    fun loadTypeface(context: Context, weight: Int): Typeface? {
        if (!isSupported) return null
        val file = file(context)
        if (!file.exists()) return null
        return runCatching {
            if (variable && Build.VERSION.SDK_INT >= 26) {
                Typeface.Builder(file)
                    .setFontVariationSettings("'wght' ${weight.coerceIn(minWeight, 900)}")
                    .build()
            } else {
                Typeface.createFromFile(file)
            }
        }.getOrNull()
    }

    companion object {
        const val GOOGLE_FONTS_COMMIT = "bd8f81ddb5c74d5c8897b36ad88b440266245103"

        /** 不进云备份：一款十几 MB，随时可以重新下载。 */
        fun dir(context: Context): File = File(context.noBackupFilesDir, "reader_fonts")

        fun byId(id: String): ReaderWebFont? = entries.firstOrNull { it.id == id }
    }
}
