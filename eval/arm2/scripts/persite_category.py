#!/usr/bin/env python3
"""Per-reflective-category site-TARGET recall (Yue-Li/SOLAR granularity), current staged
pipeline. Class-name (forName/newInstance) target recall vs TamiFlex, app-visible callers.
Run from the Tai-e root: python3 eval/arm2/scripts/persite_category.py"""
import re, html, collections, os
ROOT = "/home/likaixuan/static_analysis/PTA-enhancement/Tai-e"
XML = ROOT + "/eval/arm2/raw/staged-g2/expG_current.xml"
BM = ROOT + "/java-benchmarks/dacapo-2006"
BENCHES = ["luindex","antlr","bloat","lusearch","chart","hsqldb","fop","jython","xalan","pmd","eclipse"]
LIB = re.compile(r'^(java|javax|sun|com\.sun|jdk)\.')
t = html.unescape(open(XML, errors="replace").read())
resolved = collections.defaultdict(set); curr = None
for line in t.splitlines():
    mb = re.search(r'findClass\(\)>@3 . \[dacapo\.(\w+)\.', line)
    if mb and mb.group(1) in BENCHES: curr = mb.group(1)
    mt = re.search(r'\[refl-target\] <[^>]+>#\d+ -> (\S+)', line)
    if mt and curr: resolved[curr].add(mt.group(1))
def gt(b, apis):
    s = set(); p = os.path.join(BM, b + "-refl.log")
    if not os.path.exists(p): return s
    for line in open(p, errors="replace"):
        f = line.strip().split(";")
        if len(f) < 3: continue
        api, tgt, caller = f[0], f[1], f[2]
        if LIB.match(caller): continue
        if any(api.startswith(a) for a in apis): s.add(tgt)
    return s
print("bench,GT_classname,resolved_hit,recall")
tg = th = 0
for b in BENCHES:
    g = gt(b, ("Class.forName","Class.newInstance","Constructor.newInstance"))
    if not g: continue
    h = len(g & resolved[b]); tg += len(g); th += h
    print("%s,%d,%d,%.3f" % (b, len(g), h, h/len(g)))
print("AGGREGATE,%d,%d,%.3f" % (tg, th, th/tg))
