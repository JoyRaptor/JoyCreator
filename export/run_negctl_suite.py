"""Runs every audio spec probe's --selftest: good input must PASS, deliberately
broken input must FAIL. A probe whose negative control does not fail has no
teeth and cannot certify anything (four false greens in tooling already).

Usage:
    python export/run_negctl_suite.py

Exit 0 iff every probe demonstrated teeth in this run.
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PROBES = [
    ('B3 solo', 'probe_solo_b3.py'),
    ('B4 meters', 'probe_meters_b4.py'),
    ('C6 gain-reduction', 'probe_gr_c6.py'),
    ('C7 bypass', 'probe_bypass_c7.py'),
    ('C5.E ducking', 'probe_duck_c5e.py'),
    ('C9 voice-chain opt-in', 'probe_voicefx_c9.py'),
]


def main():
    ok = True
    for name, script in PROBES:
        print(f'\n{"=" * 70}\n{name} — {script} --selftest\n{"=" * 70}')
        r = subprocess.run([sys.executable, os.path.join(HERE, '..', 'tasks', script),
                            '--selftest'])
        if r.returncode != 0:
            print(f'>>> {name}: NEGATIVE CONTROL DID NOT DEMONSTRATE TEETH')
            ok = False
    print(f'\n{"SUITE PASS - every probe fails on broken input" if ok else "SUITE FAIL"}')
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
