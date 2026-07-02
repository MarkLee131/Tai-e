#!/usr/bin/env python3
"""Regenerate every number in the arm2 (Reflex) paper from the archived raw
JUnit XMLs in ../raw/. Run from this directory:  python3 parse_results.py

Each section prints the table/figure it feeds in PTA-Paper.tex, so the archive
is self-verifying: the paper's numbers must match this script's output.
"""
import re, html, os

RAW = os.path.join(os.path.dirname(__file__), "..", "raw")
def ID_RE(ids):
    return "|".join(re.escape(str(x)) for x in ids)


BENCH11 = ["luindex", "antlr", "bloat", "lusearch", "chart", "hsqldb",
           "fop", "jython", "xalan", "pmd", "eclipse"]
REAL = ["findbugs-3.0", "columba-1.4", "jedit-3.0", "freecol-0.10.3",
        "gruntspud-0.4.6", "briss-0.9", "soot-2.3.0"]


def sysout(name):
    """system-out text of an archived JUnit XML."""
    p = os.path.join(RAW, name)
    if not os.path.exists(p):
        return ""
    t = open(p, encoding="utf-8", errors="replace").read()
    out = ""
    for tag in ("system-out", "system-err"):
        m = re.search(r"<%s>(.*?)</%s>" % (tag, tag), t, re.S)
        if m:
            out += html.unescape(m.group(1))
    return out


def rows(name, ids):
    """{id: (GT, sc_rec, solar_r, solar_p, refl_r, refl_p, extra)} from a run."""
    out = {}
    for l in sysout(name).splitlines():
        m = re.match(r"^(" + ID_RE(ids) + r")\s+\|\s*(\d+)\s*\|"
                     r"\s*([\d.]+)\s*\|\s*r=([\d.]+) p=([\d.]+)\s*\|"
                     r"\s*r=([\d.]+) p=([\d.]+) \+(\d+)", l.strip())
        if m:
            g = m.groups()
            out[g[0]] = (int(g[1]), float(g[2]), float(g[3]), float(g[4]),
                         float(g[5]), float(g[6]), int(g[7]))
    return out


def cg(name, ids):
    """{id: {config: (methods, edges)}} from [cg] lines preceding each row."""
    out, cur = {}, []
    for l in sysout(name).splitlines():
        m = re.search(r"\[cg\].*methods=(\d+) edges=(\d+)\s+\(([\w-]+)\)", l)
        if m:
            cur.append((m.group(3), int(m.group(1)), int(m.group(2))))
        r = re.match(r"^(" + ID_RE(ids) + r")\s+\|", l.strip())
        if r:
            out[r.group(1)] = {c: (me, ed) for c, me, ed in cur}
            cur = []
    return out


def disposer(name, ids):
    """{id: (proposed, phiRej, injected)} from [disposer] lines preceding rows."""
    out, cur = {}, None
    for l in sysout(name).splitlines():
        d = re.search(r"\[disposer\] proposed=(\d+) phiRejected=(\d+) injected=(\d+)", l)
        if d:
            cur = tuple(map(int, d.groups()))
        r = re.match(r"^(" + ID_RE(ids) + r")\s+\|", l.strip())
        if r and cur:
            out[r.group(1)] = cur
            cur = None
    return out


def sec(title):
    print("\n" + "=" * 72 + "\n" + title + "\n" + "=" * 72)


# ---- RQ1: main recall/precision table (tab:rq1) ----
sec("RQ1 (tab:rq1) — recall/precision vs string-constant & SOLAR")
r = {}
for f in ("exp1", "exp3", "exp4"):  # the three live runs covering all 11
    r.update(rows(f + ".xml", BENCH11))
print(f"{'bench':10} {'GT':>6} {'sc':>6} {'SOLAR r/p':>12} {'Reflex r/p':>12} {'+extra':>7}")
for b in BENCH11:
    if b in r:
        gt, sc, sr, sp, rr, rp, ex = r[b]
        print(f"{b:10} {gt:6d} {sc:6.3f} {sr:6.3f}/{sp:.3f} {rr:6.3f}/{rp:.3f} {ex:7d}")

# ---- RQ5: call-graph completeness (tab:cg) ----
sec("RQ5 (tab:cg) — call-graph size string-constant vs Reflex")
c = cg("expCG.xml", BENCH11)
tm = te = 0
print(f"{'bench':10} {'sc m/e':>14} {'Reflex m/e':>16} {'dM':>7} {'dE':>8}")
for b in BENCH11:
    sc, llm = c[b].get("string-constant"), c[b].get("llm")
    if sc and llm:
        dm, de = llm[0] - sc[0], llm[1] - sc[1]
        tm += dm
        te += de
        print(f"{b:10} {sc[0]:6d}/{sc[1]:<7d} {llm[0]:7d}/{llm[1]:<8d} {dm:7d} {de:8d}")
print(f"{'TOTAL Δ':10} {'':>14} {'':>16} {tm:7d} {te:8d}")

# ---- RQ6a: failure mode (disposer) ----
sec("RQ6a — disposer failure mode (proposed / Φ-rejected / injected)")
d = disposer("expCG.xml", BENCH11)
tp = tr = ti = 0
for b in BENCH11:
    if b in d:
        p, rj, i = d[b]
        tp += p
        tr += rj
        ti += i
        print(f"{b:10} proposed={p:3d} phiRej={rj:3d} injected={i:3d}")
print(f"{'TOTAL':10} proposed={tp:3d} phiRej={tr:3d} injected={ti:3d}"
      f"  ({100*tr/tp:.0f}% rejected)" if tp else "")

# ---- RQ6b: cross-model (T3) ----
sec("RQ6b — cross-model recall (T3: recall∝model, precision model-independent)")
sub = ["luindex", "antlr", "fop", "pmd"]
base = rows("exp1.xml", sub)
fl = rows("expModel_flashlite.xml", sub)
f20 = rows("expModel_20flash.xml", sub)
print(f"{'bench':10} {'2.5-flash r/p':>14} {'flash-lite r/p':>15} {'2.0-flash r/p':>14}")
for b in sub:
    def rp(t):
        return f"{t[b][4]:.3f}/{t[b][5]:.3f}" if b in t else "  -/-"
    print(f"{b:10} {rp(base):>14} {rp(fl):>15} {rp(f20):>14}")

# ---- RQ2: context tiers ----
sec("RQ2 — context tiers none/id/full (recall(precision))")
ctx = ["bloat", "chart", "hsqldb", "fop"]
none, idt = rows("expB_none.xml", ctx), rows("expB_id.xml", ctx)
full = {}
for f in ("exp1", "exp4"):
    full.update(rows(f + ".xml", ctx))
print(f"{'bench':10} {'none':>14} {'id':>14} {'full':>14}")
for b in ctx:
    def rp(t):
        return f"{t[b][4]:.3f}({t[b][5]:.3f})" if b in t else "-"
    print(f"{b:10} {rp(none):>14} {rp(idt):>14} {rp(full):>14}")

# ---- RQ3: ablation matrix ----
sec("RQ3 — ablation matrix (luindex/antlr recall, one component off)")
for ab in ["selfInference", "subtypeExpansion", "seeding", "grounding", "serviceLoader"]:
    t = rows(f"expA_{ab}.xml", ["luindex", "antlr"])
    lu = t.get("luindex", (0,)*7)[4]
    an = t.get("antlr", (0,)*7)[4]
    print(f"  -{ab:16} luindex={lu:.3f}  antlr={an:.3f}")

# ---- RQ7: real-world generalization (tab:rw) ----
sec("RQ7 (tab:rw) — real-world applications")
rw = rows("expRealworld.xml", REAL)
rw.update(rows("smoke_jedit.xml", ["jedit-3.0"]))
print(f"{'app':16} {'GT':>6} {'sc':>6} {'Reflex r/p':>12}")
for b in REAL:
    if b in rw:
        gt, sc, sr, sp, rr, rp, ex = rw[b]
        print(f"{b:16} {gt:6d} {sc:6.3f} {rr:6.3f}/{rp:.3f}")

# ---- Log4Shell taint case study ----
sec("Log4Shell taint (Detected N flows per reflection setting)")
td = os.path.join(os.path.dirname(__file__), "..", "taint")
for mode in ["string-constant", "solar", "llm", "llm-composed", "log"]:
    p = os.path.join(td, f"run-{mode}.log")
    if os.path.exists(p):
        t = open(p, errors="replace").read()
        m = re.search(r"Detected (\d+) taint flow", t)
        print(f"  {mode:16} Detected={m.group(1) if m else '?'}")

print("\n[done] all numbers above should match PTA-Paper.tex.")
