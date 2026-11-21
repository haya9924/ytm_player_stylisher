#!/usr/bin/env python3
"""Check every native pill lookup and its injected call in the supported 9.30.53 APK.

Usage: python3 tests/check_audio_video_pill.py ORIGINAL_APK PATCHED_APK
"""
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

sdk = Path(__file__).resolve().parents[1] / 'tools/android-sdk/build-tools/36.0.0'
hook = 'Lapp/morphe/extension/ym/AudioVideoPill;.apply:(Landroid/view/View;)V'


def counts(apk):
    table = subprocess.check_output([sdk / 'aapt2', 'dump', 'resources', apk], text=True)
    resource_id = re.search(r'resource 0x([0-9a-f]+) id/audio_video_switch_pill_container\b', table)[1]
    constant = re.compile(r'\bconst (v\d+), .*// #' + resource_id + r'\b')
    lookup = re.compile(r'\binvoke-virtual \{v\d+, (v\d+)\}, .*\.findViewById:\(I\)Landroid/view/View;')
    result = re.compile(r'\bmove-result-object (v\d+)\b')
    found = hooked = calls = 0
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as tmp:
        for member in archive.namelist():
            if not re.fullmatch(r'classes\d*\.dex', member):
                continue
            dex = Path(archive.extract(member, tmp))
            # DEX string constants can contain Modified UTF-8; instructions we match are ASCII.
            with subprocess.Popen([sdk / 'dexdump', '-d', dex], stdout=subprocess.PIPE,
                                  encoding='utf-8', errors='replace') as dump:
                previous = ['', '', '']
                for line in dump.stdout:
                    if hook in line:
                        calls += 1
                    if match := constant.search(previous[0]):
                        call = lookup.search(previous[1])
                        register = result.search(previous[2])
                        if call and register and call[1] == match[1]:
                            found += 1
                            if hook in line:
                                assert f'invoke-static/range {{{register[1]}}}' in line, line
                                hooked += 1
                    previous = [previous[1], previous[2], line]
                assert dump.wait() == 0
            dex.unlink()
    return found, hooked, calls


original, patched = sys.argv[1:]
assert counts(original) == (3, 0, 0)
assert counts(patched) == (3, 3, 3)
print('OK: all three native pill lookups are hooked with the correct result register')
