#!/usr/bin/env python3
"""
生成小说阅读器「更多字体」里每款可下载字体的预览图（VectorDrawable）。

预览是字体自己的字形轮廓，不用先下载字体就能看出效果。字体文件必须和
ReaderWebFont 里钉死的 google/fonts commit 是同一份：

    python3 -m pip install fonttools
    python3 scripts/gen_reader_font_previews.py <放着全部 ttf 的目录>

输出到 app/src/main/res/drawable/reader_font_preview_*.xml。
"""
import os
import sys

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ZH = "春眠不觉晓，处处闻啼鸟"
TC = "春眠不覺曉，處處聞啼鳥"
JA = "ゆく河の流れは絶えずして"

# (drawable 名, 文件名, 示例文字)；示例跟着 ReaderWebFont.group 走
FONTS = [
    ("noto_sans_sc", "NotoSansSC[wght].ttf", ZH),
    ("noto_serif_sc", "NotoSerifSC[wght].ttf", ZH),
    ("lxgw_wenkai_tc", "LXGWWenKaiTC-Regular.ttf", ZH),
    ("zcool_xiaowei", "ZCOOLXiaoWei-Regular.ttf", ZH),
    ("lxgw_marker_gothic", "LXGWMarkerGothic-Regular.ttf", ZH),
    ("ma_shan_zheng", "MaShanZheng-Regular.ttf", ZH),
    ("zcool_kuaile", "ZCOOLKuaiLe-Regular.ttf", ZH),
    ("long_cang", "LongCang-Regular.ttf", ZH),
    ("noto_sans_tc", "NotoSansTC[wght].ttf", TC),
    ("noto_serif_tc", "NotoSerifTC[wght].ttf", TC),
    ("cactus_classical_serif", "CactusClassicalSerif-Regular.ttf", TC),
    ("iansui", "Iansui-Regular.ttf", TC),
    ("huninn", "Huninn-Regular.ttf", TC),
    ("noto_sans_jp", "NotoSansJP[wght].ttf", JA),
    ("noto_serif_jp", "NotoSerifJP[wght].ttf", JA),
    ("biz_udp_gothic", "BIZUDPGothic-Regular.ttf", JA),
    ("biz_udp_mincho", "BIZUDPMincho-Regular.ttf", JA),
    ("zen_kaku_gothic_new", "ZenKakuGothicNew-Regular.ttf", JA),
    ("shippori_mincho", "ShipporiMincho-Regular.ttf", JA),
    ("zen_old_mincho", "ZenOldMincho-Regular.ttf", JA),
    ("ibm_plex_sans_jp", "IBMPlexSansJP-Regular.ttf", JA),
    ("kaisei_opti", "KaiseiOpti-Regular.ttf", JA),
    ("zen_maru_gothic", "ZenMaruGothic-Regular.ttf", JA),
    ("m_plus_rounded_1c", "MPLUSRounded1c-Regular.ttf", JA),
    ("kiwi_maru", "KiwiMaru-Regular.ttf", JA),
    ("klee_one", "KleeOne-Regular.ttf", JA),
    ("hina_mincho", "HinaMincho-Regular.ttf", JA),
    ("new_tegomin", "NewTegomin-Regular.ttf", JA),
    ("zen_antique", "ZenAntique-Regular.ttf", JA),
    ("yomogi", "Yomogi-Regular.ttf", JA),
    ("zen_kurenaido", "ZenKurenaido-Regular.ttf", JA),
    ("yusei_magic", "YuseiMagic-Regular.ttf", JA),
]

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "drawable")


def render(font_path: str, text: str) -> tuple[str, int, int]:
    font = TTFont(font_path)
    if "fvar" in font:
        # 可变字体的默认实例是 Thin / ExtraLight，预览按阅读器默认的 400 出
        font = instancer.instantiateVariableFont(font, {"wght": 400})
    upm = font["head"].unitsPerEm
    cmap = font.getBestCmap()
    glyphs = font.getGlyphSet()
    hmtx = font["hmtx"]
    # 统一用表意字框：基线以上 0.88em、以下 0.12em，各字体的预览上下对齐
    ascent = round(upm * 0.88)
    pen = SVGPathPen(glyphs, ntos=lambda v: str(round(v)))
    x = 0
    for ch in text:
        name = cmap[ord(ch)]
        glyphs[name].draw(TransformPen(pen, (1, 0, 0, -1, x, ascent)))
        x += hmtx[name][0]
    return pen.getCommands(), x, upm


def main() -> None:
    src = sys.argv[1]
    for key, file, text in FONTS:
        path, width, height = render(os.path.join(src, file), text)
        dp_height = 20
        dp_width = round(width * dp_height / height)
        xml = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            f"<!-- 由 scripts/gen_reader_font_previews.py 生成，勿手改。字形来自 Google Fonts（SIL OFL 1.1）。 -->\n"
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:tools="http://schemas.android.com/tools"\n'
            f'    android:width="{dp_width}dp"\n'
            f'    android:height="{dp_height}dp"\n'
            f'    android:viewportWidth="{width}"\n'
            f'    android:viewportHeight="{height}"\n'
            '    tools:ignore="VectorPath,VectorRaster">\n'
            f'    <path android:fillColor="#FF000000" android:pathData="{path}" />\n'
            "</vector>\n"
        )
        with open(os.path.join(OUT, f"reader_font_preview_{key}.xml"), "w", encoding="utf-8") as f:
            f.write(xml)
        print(f"{key}: {len(path)} chars, {dp_width}x{dp_height}dp")


if __name__ == "__main__":
    main()
