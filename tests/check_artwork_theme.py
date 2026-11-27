#!/usr/bin/env python3
"""Check the native artwork-theme wiring in an actual patched YouTube Music APK.

Usage: python3 tests/check_artwork_theme.py AAPT2 PATCHED_APK SMALI_DIRECTORY
SMALI_DIRECTORY: apktool d -r output of the patched APK (including all smali* folders).
"""
import re
import subprocess
import sys
from pathlib import Path


aapt, apk, smali = sys.argv[1:]
root = Path(smali)


def dump(*args):
    return subprocess.check_output([aapt, "dump", *args, apk], text=True)


resources = dump("resources")


def entry(kind, name):
    section = re.search(rf"^    resource (0x[\da-f]+) {kind}/{re.escape(name)}\n"
                        r"((?:^      .*\n)*)", resources, re.M)
    assert section, (kind, name)
    resource_id, value = section.groups()
    path = re.search(r"\(file\) (\S+)", value)
    return resource_id, path[1] if path else None


expected = {
    "main_activity": ("main",),
    "pivot_bar": ("pivot",),
    "browse_fragment": ("canvas", "chrome_empty"),
    "browse_unlimited_fragment": ("canvas", "chrome_empty"),
    "explore_browse_fragment": ("canvas",),
    "library_browse_fragment": ("library", "chrome_empty"),
    "search_input_fragment": ("canvas", "chrome_black"),
    "search_result_fragment": ("canvas", "chrome_black"),
    "detail_page_header": ("chrome_black", "chrome_header"),
    "detail_page_tabbed_fragment": ("canvas", "chrome_empty"),
    "preference_activity": ("preferences", "chrome_preferences"),
    "two_pane_preference_activity": ("preferences", "chrome_preferences"),
    "bottom_sheet_modal_container_round_corners": ("sheet_rounded",),
    "bottom_sheet_modal_container_square_corners": ("sheet_square",),
}

for layout, names in expected.items():
    _, path = entry("layout", layout)
    tree = dump("xmltree", "--file", path)
    for name in names:
        drawable, drawable_path = entry("drawable", "ym_artwork_" + name)
        assert re.search(rf":background\(0x010100d4\)=@{drawable}\b", tree), (layout, name)
        xml = dump("xmltree", "--file", drawable_path)
        assert "E: app.morphe.extension.ym.ArtworkSurfaceDrawable" in xml, name
        assert ":drawable(0x01010199)=" in xml and ":level(" in xml, name
        if name in {"main", "sheet_square"}:
            assert ":drawable(0x01010199)=?" in xml and ":color(0x010101a5)=@" in xml, name

_, watch = entry("layout", "watch_while_layout")
watch_tree = dump("xmltree", "--file", watch)
background, _ = entry("id", "player_page_background")
assert watch_tree.index(f"=@{background}") < watch_tree.index("E: app.morphe.extension.ym.ArtworkGradientView")
assert watch_tree.count("E: app.morphe.extension.ym.ArtworkGradientView") == 1


def dex_file(path):
    candidates = list(root.glob(f"smali*/{path}.smali"))
    assert len(candidates) == 1, (path, candidates)
    return candidates[0].read_text()


def method(path, signature):
    text = dex_file(path)
    match = re.search(rf"^\.method[^\n]* {re.escape(signature)}\n(.*?)^\.end method", text, re.M | re.S)
    assert match, (path, signature)
    return match[1]


owner = "Lapp/morphe/extension/ym/ArtworkTheme;->"
source = method("rtw", "a(Lqhq;)V")
assert source.count(owner + "onNativePalette(II)V") == 1
assert source.count(owner + "onNativePaletteUnavailable()V") == 1
assert all(f"Lqhq;->{marker}:Lqhq;" in source for marker in "cdef")
assert re.search(r"Lqhq;->c:Lqhq;\s+invoke-virtual \{v0, p1\}, Ljava/lang/Object;->equals", source)
assert source.index("Lckkd;->a:I") < source.index(owner + "onNativePalette(II)V")
assert source.index("Lckkd;->d:I") < source.index(owner + "onNativePalette(II)V")
assert source.index(owner + "onNativePaletteUnavailable()V") < source.index("YTM_PLAYER_COLOR_SAMPLE_PALETTE_ENTITY_KEY")
assert "Lrtw;->a(Lqhq;)V" in method("rtu", "a(Ljava/lang/Object;)V")

player = method("rxx", "B(IIJ)V")
assert player.count(owner + "protectPlayerStops([I)V") == 3
assert player.count(owner + "readablePlayerBackground(I)I") == 2
assert "iput p1, p0, Lrxx;->aY:I" in player and "iput p2, p0, Lrxx;->aZ:I" in player
assert owner + "readablePlayerBackground(I)I" in method("rxu", "b(I)V")
assert owner + "updateLithoPaint(" in method("xjy", "draw(Landroid/graphics/Canvas;)V")
assert owner + "initialize(Landroid/content/Context;)V" in method("jbg", "onCreate()V")
for extension in ("ArtworkTheme", "ArtworkSurfaceDrawable", "ArtworkPalette"):
    dex_file("app/morphe/extension/ym/" + extension)

print("OK: live artwork surfaces, native publisher/reset, Litho and contrast sinks in patched APK")
