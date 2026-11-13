#!/usr/bin/env python3
"""Compare player drawing order and geometry in two apktool-decoded APKs.

Usage: python3 tests/check_layout_compat.py ORIGINAL_DECODED PATCHED_DECODED
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

if len(sys.argv) != 3:
    sys.exit(__doc__)
old_dir, new_dir = map(Path, sys.argv[1:])
A = "{http://schemas.android.com/apk/res/android}"
Y = "{http://schemas.android.com/apk/res-auto}"


def parse(directory):
    return ET.parse(directory / "res/layout/watch_while_layout.xml").getroot()


def find(root, resource_id):
    return next(e for e in root.iter() if e.get(A + "id") == "@id/" + resource_id)


old, new = parse(old_dir), parse(new_dir)
for attr in ("playerPagePaddingHorizontal", "playerPagePaddingHorizontalMedium", "watchFragmentPaddingTop", "watchFragmentPaddingBottom"):
    assert old.get(Y + attr) == new.get(Y + attr), attr
old_card, new_card = find(old, "watch_fragment_container"), find(new, "watch_fragment_container")
for attr in ("cardElevation", "cardMaxElevation"):
    assert old_card.get(Y + attr) == new_card.get(Y + attr), attr
old_page, new_page = find(old, "player_page"), find(new, "player_page")
child_list = list(new_page)
gradient_tag = "app.morphe.extension.ym.ArtworkGradientView"
gradients = [e for e in child_list if e.tag == gradient_tag]
assert len(gradients) == 1, "exactly one shared player background is required"
gradient = gradients[0]
assert child_list.index(gradient) == child_list.index(find(new, "player_page_background")) + 1, "wrong gradient stacking order"
assert gradient.get(A + "clickable") == "false" and gradient.get(A + "focusable") == "false"
assert gradient.get(A + "importantForAccessibility") == "no"
assert [(e.tag, e.get(A + "id")) for e in old_page] == [(e.tag, e.get(A + "id")) for e in child_list if e is not gradient], "native player child order changed"
assert not any(e.tag == gradient_tag for e in find(new, "player_page_background")), "gradient must not be hidden by native background"
print("OK: native artwork geometry and order preserved; shared non-interactive gradient above native background")
