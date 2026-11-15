"""Three-way line merge (base, ours, theirs) with difflib. Hunks changed on one side only are taken; overlapping changes are conflicts
and are written with markers, to be resolved by hand.
  python merge3.py <base> <ours> <theirs> <out>    -> prints the number of conflicts"""
import difflib, sys
from pathlib import Path

read = lambda p: Path(p).read_text(encoding='utf-8').replace('\r\n', '\n').split('\n')
base, ours, theirs = read(sys.argv[1]), read(sys.argv[2]), read(sys.argv[3])


def changes(a, b):
    """list of (base_start, base_end, replacement lines) for a -> b"""
    return [(i1, i2, b[j1:j2]) for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes() if tag != 'equal']


ca, cb = changes(base, ours), changes(base, theirs)
out, conflicts, pos, ia, ib = [], 0, 0, 0, 0
while ia < len(ca) or ib < len(cb):
    a = ca[ia] if ia < len(ca) else None
    b = cb[ib] if ib < len(cb) else None
    # next change in base order
    if b is None or (a is not None and a[1] <= b[0] and not (a[0] == a[1] == b[0] == b[1])) and (a[0], a[1]) != (b[0], b[1]):
        if a is not None and (b is None or a[1] <= b[0]) and not (b is not None and a[0] == b[0] and a[0] == a[1]):
            out += base[pos:a[0]] + a[2]; pos = a[1]; ia += 1; continue
    if a is None or (b[1] <= a[0] and not (b[0] == b[1] == a[0])):
        out += base[pos:b[0]] + b[2]; pos = b[1]; ib += 1; continue
    # overlap: grow the region until neither side reaches further
    lo, hi = min(a[0], b[0]), max(a[1], b[1])
    ra, rb = [a], [b]; ia += 1; ib += 1
    grown = True
    while grown:
        grown = False
        if ia < len(ca) and ca[ia][0] < hi:
            ra.append(ca[ia]); hi = max(hi, ca[ia][1]); ia += 1; grown = True
        if ib < len(cb) and cb[ib][0] < hi:
            rb.append(cb[ib]); hi = max(hi, cb[ib][1]); ib += 1; grown = True

    def apply(chs):
        res, p = [], lo
        for s, e, rep in chs:
            res += base[p:s] + rep; p = e
        return res + base[p:hi]
    va, vb = apply(ra), apply(rb)
    out += base[pos:lo]
    if va == vb:
        out += va
    else:
        conflicts += 1
        out += ['<<<<<<< OURS (hardening)'] + va + ['||||||| BASE'] + base[lo:hi] + ['======='] + vb + ['>>>>>>> THEIRS (design)']
    pos = hi
out += base[pos:]
Path(sys.argv[4]).write_text('\n'.join(out), encoding='utf-8', newline='\n')
print('conflicts:', conflicts, ' lines:', len(out), ' changes ours/theirs:', len(ca), len(cb))
