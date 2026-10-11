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
 *
 * [group] 按字符覆盖划分，不按字体名里的 SC / TC：简体组完整覆盖 GB 2312，繁体组完整覆盖 Big5
 * 常用字，日文组完整覆盖 JIS 第一水准。覆盖外的字由系统字体补，混排会跳字形，所以不达标的不收。
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
        "web_noto_sans_sc", R.string.reader_font_noto_sans_sc, Group.SIMPLIFIED_CHINESE,
        "notosanssc/NotoSansSC%5Bwght%5D.ttf", 17_772_300L,
        "a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da",
        variable = true, minWeight = 100, R.drawable.reader_font_preview_noto_sans_sc,
    ),
    NOTO_SERIF_SC(
        "web_noto_serif_sc", R.string.reader_font_noto_serif_sc, Group.SIMPLIFIED_CHINESE,
        "notoserifsc/NotoSerifSC%5Bwght%5D.ttf", 25_125_512L,
        "050080d9255a86808f2945bffac582b31ef32bc36411ce29563b4961670c66f9",
        variable = true, minWeight = 200, R.drawable.reader_font_preview_noto_serif_sc,
    ),
    LXGW_WENKAI_TC(
        "web_lxgw_wenkai_tc", R.string.reader_font_lxgw_wenkai, Group.SIMPLIFIED_CHINESE,
        "lxgwwenkaitc/LXGWWenKaiTC-Regular.ttf", 13_110_528L,
        "4fcc5aec11cbbf737b0cfab7b63796f7f280087a7a656b2f13342cd5e5318d95",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_lxgw_wenkai_tc,
    ),
    ZCOOL_XIAOWEI(
        "web_zcool_xiaowei", R.string.reader_font_zcool_xiaowei, Group.SIMPLIFIED_CHINESE,
        "zcoolxiaowei/ZCOOLXiaoWei-Regular.ttf", 6_313_808L,
        "a42b620140f493db42f741351dfbf343c0936d58588ee8004b8b2a218d997ff1",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_zcool_xiaowei,
    ),
    LXGW_MARKER_GOTHIC(
        "web_lxgw_marker_gothic", R.string.reader_font_lxgw_marker_gothic, Group.SIMPLIFIED_CHINESE,
        "lxgwmarkergothic/LXGWMarkerGothic-Regular.ttf", 3_187_360L,
        "e6a55cfd5f18dc393f92670a164397a69242cb4eabe1d3feb5dc22fc4947a8ba",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_lxgw_marker_gothic,
    ),
    MA_SHAN_ZHENG(
        "web_ma_shan_zheng", R.string.reader_font_ma_shan_zheng, Group.SIMPLIFIED_CHINESE,
        "mashanzheng/MaShanZheng-Regular.ttf", 5_857_936L,
        "6d2546bb189c732a8ca29af9e22457b152387d158aa459e4ac2ce1e51788b7fb",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_ma_shan_zheng,
    ),
    ZCOOL_KUAILE(
        "web_zcool_kuaile", R.string.reader_font_zcool_kuaile, Group.SIMPLIFIED_CHINESE,
        "zcoolkuaile/ZCOOLKuaiLe-Regular.ttf", 1_514_968L,
        "812a6fc1fe54b6d73a419245c32dfeba8aa33104d5be90d1cf6af082007cb71d",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_zcool_kuaile,
    ),
    LONG_CANG(
        "web_long_cang", R.string.reader_font_long_cang, Group.SIMPLIFIED_CHINESE,
        "longcang/LongCang-Regular.ttf", 5_162_508L,
        "e5bf2c3f24ef2327c6f136d8f73e2f9dfdf44896fdbeb35a9515f44777bb91bc",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_long_cang,
    ),
    NOTO_SANS_TC(
        "web_noto_sans_tc", R.string.reader_font_noto_sans_tc, Group.TRADITIONAL_CHINESE,
        "notosanstc/NotoSansTC%5Bwght%5D.ttf", 11_941_968L,
        "864727d210d54f2537bbe23b3a839436c3992af72de9322af5270897246bd44f",
        variable = true, minWeight = 100, R.drawable.reader_font_preview_noto_sans_tc,
    ),
    NOTO_SERIF_TC(
        "web_noto_serif_tc", R.string.reader_font_noto_serif_tc, Group.TRADITIONAL_CHINESE,
        "notoseriftc/NotoSerifTC%5Bwght%5D.ttf", 16_851_596L,
        "0077e18f57c6908f4a000969880940bdb0dad057c0e8d98b49dc364c3d1b09c6",
        variable = true, minWeight = 200, R.drawable.reader_font_preview_noto_serif_tc,
    ),
    CACTUS_CLASSICAL_SERIF(
        "web_cactus_classical_serif", R.string.reader_font_cactus_classical_serif, Group.TRADITIONAL_CHINESE,
        "cactusclassicalserif/CactusClassicalSerif-Regular.ttf", 28_502_932L,
        "ecbd58961db922392d7fecfafa3681fefaeec0c399b860ab6940ba700f7da055",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_cactus_classical_serif,
    ),
    IANSUI(
        "web_iansui", R.string.reader_font_iansui, Group.TRADITIONAL_CHINESE,
        "iansui/Iansui-Regular.ttf", 9_420_560L,
        "6e6340d80d618a42b48ade9370c34fa37a8210750c6fbc8efe65f23716538a2b",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_iansui,
    ),
    HUNINN(
        "web_huninn", R.string.reader_font_huninn, Group.TRADITIONAL_CHINESE,
        "huninn/Huninn-Regular.ttf", 4_683_972L,
        "1bd770a5ffc0c06723b567686f8b5db5abf9ab54227f3bbc4e6fd648f4698805",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_huninn,
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
    BIZ_UDP_GOTHIC(
        "web_biz_udp_gothic", R.string.reader_font_biz_udp_gothic, Group.JAPANESE,
        "bizudpgothic/BIZUDPGothic-Regular.ttf", 4_669_688L,
        "258d7156c165f2ff774b6efee637c22c3b950de0d8a10e501137061bc8085d01",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_biz_udp_gothic,
    ),
    BIZ_UDP_MINCHO(
        "web_biz_udp_mincho", R.string.reader_font_biz_udp_mincho, Group.JAPANESE,
        "bizudpmincho/BIZUDPMincho-Regular.ttf", 6_156_444L,
        "dbcea04578ac1e9d3484525e870ce491bd04361768f4d2ba4b827d96e20f891d",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_biz_udp_mincho,
    ),
    ZEN_KAKU_GOTHIC_NEW(
        "web_zen_kaku_gothic_new", R.string.reader_font_zen_kaku_gothic_new, Group.JAPANESE,
        "zenkakugothicnew/ZenKakuGothicNew-Regular.ttf", 2_360_248L,
        "b840cd07a67d89cacca44249ae49aa99ee7640eb5ce623be8d8983d6aabac801",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_zen_kaku_gothic_new,
    ),
    SHIPPORI_MINCHO(
        "web_shippori_mincho", R.string.reader_font_shippori_mincho, Group.JAPANESE,
        "shipporimincho/ShipporiMincho-Regular.ttf", 8_677_284L,
        "769b5269f0f9bc6534b352c0e6bd856a566e03ff788f107191c2d835863570b2",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_shippori_mincho,
    ),
    ZEN_OLD_MINCHO(
        "web_zen_old_mincho", R.string.reader_font_zen_old_mincho, Group.JAPANESE,
        "zenoldmincho/ZenOldMincho-Regular.ttf", 5_442_512L,
        "4c051a78a21c4e8e9dccf1c754776d33f356b8cc6ef95d9b64761b9bae814b84",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_zen_old_mincho,
    ),
    IBM_PLEX_SANS_JP(
        "web_ibm_plex_sans_jp", R.string.reader_font_ibm_plex_sans_jp, Group.JAPANESE,
        "ibmplexsansjp/IBMPlexSansJP-Regular.ttf", 2_396_484L,
        "372f8bba95f386856ae435dc0e69f08db1cac29bed513d99171a0ae2d307ba2a",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_ibm_plex_sans_jp,
    ),
    KAISEI_OPTI(
        "web_kaisei_opti", R.string.reader_font_kaisei_opti, Group.JAPANESE,
        "kaiseiopti/KaiseiOpti-Regular.ttf", 4_503_024L,
        "990cfb1fb00f311c8975c2c5f4778b2fe5462fd5eabf0884828a323dad3f18c0",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_kaisei_opti,
    ),
    ZEN_MARU_GOTHIC(
        "web_zen_maru_gothic", R.string.reader_font_zen_maru_gothic, Group.JAPANESE,
        "zenmarugothic/ZenMaruGothic-Regular.ttf", 3_832_756L,
        "a0c0b53543e0993ae2225e629c833f3d51495ad31720694ff112ce4ce11111ef",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_zen_maru_gothic,
    ),
    M_PLUS_ROUNDED_1C(
        "web_m_plus_rounded_1c", R.string.reader_font_m_plus_rounded_1c, Group.JAPANESE,
        "mplusrounded1c/MPLUSRounded1c-Regular.ttf", 3_389_792L,
        "b75708b53e45b06d17d470aeeca5b766e3d1b3999f03f13ec4eb863ca846c14c",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_m_plus_rounded_1c,
    ),
    KIWI_MARU(
        "web_kiwi_maru", R.string.reader_font_kiwi_maru, Group.JAPANESE,
        "kiwimaru/KiwiMaru-Regular.ttf", 5_065_572L,
        "b0c3103b2639f690c1fcb44e060058383174bfd2eb72e6635bc9869b374dee87",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_kiwi_maru,
    ),
    KLEE_ONE(
        "web_klee_one", R.string.reader_font_klee_one, Group.JAPANESE,
        "kleeone/KleeOne-Regular.ttf", 8_724_204L,
        "bf4063f030cc2ae6adf0a11424a1888e5c0eb4438f1f6d02f52294af868e9b3a",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_klee_one,
    ),
    HINA_MINCHO(
        "web_hina_mincho", R.string.reader_font_hina_mincho, Group.JAPANESE,
        "hinamincho/HinaMincho-Regular.ttf", 6_451_924L,
        "8395fafa0c2721b4b5c274031e1336fea0f703d908b175ee21f8ba2f1ad566a0",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_hina_mincho,
    ),
    NEW_TEGOMIN(
        "web_new_tegomin", R.string.reader_font_new_tegomin, Group.JAPANESE,
        "newtegomin/NewTegomin-Regular.ttf", 7_450_688L,
        "bcae8775f0f9b88e12e40434918c56817e96e3a291e2a6595a34fb38fe3e58fb",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_new_tegomin,
    ),
    ZEN_ANTIQUE(
        "web_zen_antique", R.string.reader_font_zen_antique, Group.JAPANESE,
        "zenantique/ZenAntique-Regular.ttf", 5_507_920L,
        "8c5cf7a136837ee705d06bbc133ea18ac06b7dd284f38aace91f5de36725c315",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_zen_antique,
    ),
    YOMOGI(
        "web_yomogi", R.string.reader_font_yomogi, Group.JAPANESE,
        "yomogi/Yomogi-Regular.ttf", 4_045_904L,
        "ec280f473a03187292905618f24fdb08a6dcbf8620b52637e3b589fe745259b9",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_yomogi,
    ),
    ZEN_KURENAIDO(
        "web_zen_kurenaido", R.string.reader_font_zen_kurenaido, Group.JAPANESE,
        "zenkurenaido/ZenKurenaido-Regular.ttf", 4_303_112L,
        "58b8d930d9fc10c8a5810c085bae378dacb98d0779073ee6d53d919f19ee6a4f",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_zen_kurenaido,
    ),
    YUSEI_MAGIC(
        "web_yusei_magic", R.string.reader_font_yusei_magic, Group.JAPANESE,
        "yuseimagic/YuseiMagic-Regular.ttf", 3_134_968L,
        "82098615f39ed9da6a8ccc674b9006e49c70dd5b775a7a1697f6bedd22ce25a2",
        variable = false, minWeight = 400, R.drawable.reader_font_preview_yusei_magic,
    );

    enum class Group(@StringRes val titleRes: Int) {
        SIMPLIFIED_CHINESE(R.string.reader_font_group_chinese),
        TRADITIONAL_CHINESE(R.string.reader_font_group_traditional_chinese),
        JAPANESE(R.string.reader_font_group_japanese),
    }

    val isSupported: Boolean get() = !variable || Build.VERSION.SDK_INT >= 26

    val downloadUrl: String
        get() = "https://github.com/google/fonts/raw/$GOOGLE_FONTS_COMMIT/ofl/$repoPath"

    fun file(context: Context): File = File(dir(context), "$id.ttf")

    /** 只在 rename 校验过的文件时才存在，所以文件在 = 已装好。 */
    fun isInstalled(context: Context): Boolean = isSupported && file(context).exists()

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
