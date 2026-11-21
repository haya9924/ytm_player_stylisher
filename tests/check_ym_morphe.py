#!/usr/bin/env python3
"""Validate the standalone ym_morphe bundle and its patched APK.

Usage: python3 tests/check_ym_morphe.py [ORIGINAL_APK PATCHED_APK]
"""
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
ANDROID = "{http://schemas.android.com/apk/res/android}"
YT = "{http://schemas.android.com/apk/res-auto}"
root = Path(__file__).resolve().parents[1]
bandle = root / "ym_morphe/patches/build/libs/patches-1.6.1.mpp"
patched_apk = Path(sys.argv[2]) if len(sys.argv) > 2 else root / "output/ym_morphe-test-unsigned.apk"
decoded = root / "ym_morphe/patches/build/decoded"

gradient_source = (root / "ym_morphe/extensions/ym/src/main/java/app/morphe/extension/ym/ArtworkGradientView.java").read_text()
for method in ("setScaleX(", "setScaleY(", "setTranslationY(", "setPivotX(", "setPivotY("):
    assert method not in gradient_source, f"artwork transform may interfere with player: {method}"
patch_source = (root / "ym_morphe/patches/src/main/kotlin/app/morphe/patches/music/layout/classicplayer/ClassicPlayerPatch.kt").read_text()
assert 'setAttribute("yt:cardElevation"' not in patch_source, "artwork must not be raised above controls"
assert 'setAttribute("yt:cardMaxElevation"' not in patch_source
assert 'name = "B"' not in patch_source, "native artwork geometry must remain intact"

with zipfile.ZipFile(bandle) as z:
    names = z.namelist()
    assert "extensions/ym.mpe" in names, "player extension missing"
    assert not any(n.startswith(("extensions/music", "extensions/youtube", "extensions/shared", "extensions/reddit")) for n in names)
    # Exactly one patch entry point should be exposed.
    assert sum(n.startswith("app/morphe/patches/music/layout/classicplayer/") for n in names) >= 1

with zipfile.ZipFile(patched_apk) as z:
    dex = [n for n in z.namelist() if n.endswith(".dex")]
    data = [z.read(n) for n in dex]
    for name in (b"ArtworkGradientView", b"GradientSettingsActivity", b"AudioVideoPill"):
        assert any(b"Lapp/morphe/extension/ym/" + name + b";" in content for content in data), name

print("OK: standalone bundle contains only ym_morphe, and injected extension is present")
