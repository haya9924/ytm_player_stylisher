#!/usr/bin/env python3
"""Check hooks in an apktool smali decode: ORIGINAL_DECODE PATCHED_DECODE."""
import re
import sys
from pathlib import Path

original, patched = map(Path, sys.argv[1:])
policy = 'Lapp/morphe/extension/ym/PlaybackNetworkPolicy;'


def source(root, name):
    matches = list(root.glob(f'smali*/{name}.smali'))
    assert len(matches) == 1, (name, matches)
    return matches[0].read_text()


def method(text, signature):
    return next(m for m in re.findall(r'^\.method .*?^\.end method', text, re.M | re.S)
                if m.splitlines()[0].endswith(' ' + signature))


def instructions(body):
    return [s for line in body.splitlines() if (s := line.strip())
            and not s.startswith(('.', ':', '#'))]


for signature, hook in {
    'd()V': 'onUrgent', 'p()V': 'onUrgent', 'l()V': 'onUrgent',
    't(JLcbxe;)V': 'onUrgent', 'm(JLcbxe;)V': 'onUrgent',
    'o()V': 'onPlaying', 'k()V': 'onTerminal', 'f()V': 'onTerminal',
    'w()V': 'onTerminal', 'g(Lawbo;)V': 'onTerminal',
}.items():
    before = instructions(method(source(original, 'avdj'), signature))
    after = instructions(method(source(patched, 'avdj'), signature))
    assert after[0] == f'invoke-static/range {{p0 .. p0}}, {policy}->{hook}(Ljava/lang/Object;)V'
    assert after[1:] == before, signature

startup = instructions(method(source(patched, 'jbg'), 'onCreate()V'))
init = f'invoke-static/range {{p0 .. p0}}, {policy}->initialize(Landroid/content/Context;)V'
for i, instruction in enumerate(startup):
    if instruction == 'return-void':
        assert startup[i - 1] == init
# Official GmsCore support also inserts its own initialization in combined APKs.
assert [i for i in startup if i != init and 'Lapp/morphe/extension/' not in i] == instructions(method(source(original, 'jbg'), 'onCreate()V'))

gate = method(source(patched, 'bbir'), 'a(Lbbiq;Lbbil;Lbbio;Z)Lj$/util/Optional;')
for expected in ('iget-object v0, p1, Lbbiq;->a:Lbbil;', 'iget v0, v0, Lbbil;->b:I',
                 'iget v1, p2, Lbbil;->b:I', f'{policy}->shouldSuppress()Z',
                 'Lj$/util/Optional;->empty()Lj$/util/Optional;'):
    assert expected in gate, expected
assert re.search(r'if-eq v0, v1, (:cond_\w+)', gate)
assert 'Lbbil;->c:' not in gate, 'nested index must not be gated'
# Native cancellation/queue logic is not modified at all.
for name in ('bbhw', 'bbmh', 'bbiv'):
    assert instructions(source(original, name)) == instructions(source(patched, name)), name
print('OK: observation-only events, startup hooks, outer-index-only prefetch guard; native controllers unchanged')
