#!/usr/bin/env python3
"""Per-reflective-site forName-target precision/recall (Elf/SOLAR's granularity).

Parses expG.xml ([refl-target] dumps interleaved with benchmark rows), compares
arm2's per-site targets against each benchmark's TamiFlex log (Class.forName +
Class.newInstance app-level rows), and prints per-benchmark and aggregate P/R.

Pairs are (callerClass.method, targetClass).  Two scopes are reported:
  all       : every log row (incl. library-internal callers arm2 cannot see)
  app-caller: rows whose CALLER is outside java./sun./com.sun. (only-app-visible)
"""
import re, html, collections, os

SC = "/tmp/claude-1003/-home-likaixuan-static-analysis-PTA-enhancement/81a5452e-45fd-403c-8e1e-a4576e779421/scratchpad"
BM = "/home/likaixuan/static_analysis/PTA-enhancement/Tai-e/java-benchmarks/dacapo-2006"
BENCHES = ["luindex","antlr","bloat","lusearch","chart","hsqldb","fop","jython","xalan","pmd","eclipse"]
LIB = re.compile(r'^(java|javax|sun|com\.sun|jdk)\.')

def parse_dump(xml_path):
    t = open(xml_path, encoding="utf-8", errors="replace").read()
    body = ""
    for tag in ("system-out", "system-err"):
        m = re.search(r'<%s>(.*?)</%s>' % (tag, tag), t, re.S)
        if m:
            body += html.unescape(m.group(1))
    # segment: [refl-target] lines belong to the NEXT benchmark row printed
    per_bench, cur = {}, []
    for line in body.splitlines():
        m = re.search(r'\[refl-target\] <([^:]+):\s+\S+\s+([\w$<>]+)\([^#]*#\d+ -> (\S+)', line)
        if m:
            cur.append((f"{m.group(1)}.{m.group(2)}", m.group(3)))
            continue
        r = re.match(r'^(\w[\w.-]*)\s+\|', line.strip())
        if r and r.group(1) in BENCHES:
            per_bench[r.group(1)] = cur
            cur = []
    return per_bench

def log_pairs(bench):
    pairs = set()
    for line in open(os.path.join(BM, f"{bench}-refl.log"), errors="replace"):
        p = line.rstrip("\n").split(";")
        if len(p) >= 3 and p[0] in ("Class.forName", "Class.newInstance") \
                and not LIB.match(p[1]):
            pairs.add((p[2], p[1]))
    return pairs

def main():
    dump = parse_dump(os.path.join(SC, "expG.xml"))
    print(f"{'bench':10} | {'log':>4} {'app':>4} | {'arm2':>4} {'hit':>4} | "
          f"{'P':>5} {'R(all)':>6} {'R(app)':>6}")
    agg = collections.Counter()
    for b in BENCHES:
        arm = set(dump.get(b, []))
        log = log_pairs(b)
        app = {(c, t) for (c, t) in log if not LIB.match(c)}
        hit = arm & log
        P = len(hit) / len(arm) if arm else float("nan")
        Rall = len(hit) / len(log) if log else float("nan")
        Rapp = len(arm & app) / len(app) if app else float("nan")
        agg.update(arm2=len(arm), hit=len(hit), log=len(log),
                   app=len(app), apphit=len(arm & app))
        print(f"{b:10} | {len(log):4d} {len(app):4d} | {len(arm):4d} {len(hit):4d} | "
              f"{P:5.2f} {Rall:6.2f} {Rapp:6.2f}")
    P = agg["hit"] / agg["arm2"] if agg["arm2"] else 0
    print(f"{'TOTAL':10} | {agg['log']:4d} {agg['app']:4d} | {agg['arm2']:4d} "
          f"{agg['hit']:4d} | {P:5.2f} {agg['hit']/agg['log']:6.2f} "
          f"{agg['apphit']/agg['app']:6.2f}")

if __name__ == "__main__":
    main()
