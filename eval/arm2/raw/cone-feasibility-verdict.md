# ζ0 verdict: cone-closure feasibility (M1 admission-ladder gate)

**Task:** plan `2026-07-10-m1-full-admission-ladder.md` §Task ζ0. Gates β1's O-disp
investment. Probe: `src/test/java/pta/arm2/ConeClosureFeasibilityProbe.java`
(classifier unit-tested in `ConeClassifierTest`, 6/6 green). Rerun: `./gradlew zeta0`.
Data: `eval/arm2/raw/cone-feasibility.csv` (2026-07-10, branch `llm-pta-zeta0`).

## Data

| slice | config | roots | closure | open calls | carved | cone med/p90/max | resolved med/p90/max | refusal% (native / mh-vh / indy / no-body) |
|---|---|---|---|---|---|---|---|---|
| xalan | without-carveout | 12 | 32,744 | 76,173 | – | 1 / 23 / 4,917 | 1 / 5 / 507 | 1.7% (542 / 10 / 0 / 0) |
| xalan | WITH carve-out | 12 | 26,000 | 52,017 | 5,295 | 1 / 18 / 4,917 | 1 / 4 / 316 | 2.0% (509 / 10 / 0 / 0) |
| xalan | open-as-action (extra) | 12 | **103** | 142 | – | 1 / 10 / 4,917 | 1 / 2 / 507 | 3.9% (4 / 0 / 0 / 0) |
| pmd | without-carveout | 22 | 37,402 | 91,205 | – | 1 / 24 / 5,617 | 1 / 5 / 630 | 1.5% (543 / 12 / 0 / 0) |
| pmd | WITH carve-out | 22 | 33,871 | 72,685 | 8,177 | 1 / 18 / 5,617 | 1 / 4 / 338 | 1.5% (511 / 12 / 0 / 0) |
| pmd | open-as-action (extra) | 22 | **225** | 415 | – | 1 / 42 / 5,617 | 1 / 9 / 630 | 2.7% (6 / 0 / 0 / 0) |

World sizes: xalan 6,117 classes / 59,357 methods; pmd 6,968 / 65,024 (Tai-e closed
world = reference closure of app classes over JRE 1.6 + deps).

## Findings (stated plainly)

1. **Cone-closure as specified is intractable.** From 12 root methods, xalan's
   closure is 32,744 methods — 55% of every method in the world; pmd: 37,402 (58%).
   89% (xalan) / 79% (pmd) of the closure is JDK code. A summary family for a
   ~dozen-method JAXP slice would have to carry summaries for half the JDK.

2. **The egalitarian carve-out does not rescue it.** Closure shrinks only 21%
   (xalan: 26,000) and 9% (pmd: 33,871). Two reasons visible in the data:
   (i) the explosion is transitive, not per-call — median cone is 1 and p90 ≤ 24;
   one uncarved hub call (e.g. `Hashtable.get` → `hashCode` on 309–341 classes)
   pulls in classes whose OTHER methods then continue through ordinary
   static/special/tracked edges inside the JDK; (ii) Object-protocol members
   outside the carve-out list (`clone`, `getClass`, `wait`) surface with max-size
   cones (4,917 / 5,617), dominated by array receivers (`Object[].clone` resolves
   to 149–153 targets). Top offenders (WITH carve-out): `Class[].Object.clone`
   in `Constructor/Method.getExceptionTypes`, `Object[].getClass` in
   `Arrays.copyOf` / `ArrayList.toArray`, `Object[].clone` in `Arrays.sort`.

3. **refusal% is small but that does not save route (a).** 1.5–2.0% everywhere —
   but 2% of 26,000 is ~509 native methods inside the mandated scope (plus 10–12
   MethodHandle/defineClass users). Any cone-closed family on these slices
   contains unsummarizable methods, so Check refuses it; and tractability is
   already dead at 26k methods regardless. exotic-indy = 0 and no-body = 0
   everywhere (Java 6 bytecode, complete JRE on classpath).

4. **Route (b) is tractable, measured directly (extra config).** Treating every
   open dispatch as an unmodeled action (only tracked + static/special edges
   expand) collapses the closure ~300×: xalan 103 methods, pmd 225. The refusal
   set inside that scope is small and NAMED: `System.arraycopy`,
   `Class.forName0`, `sun.reflect.Reflection.getCallerClass`,
   `AccessController.doPrivileged` (×2 overloads, pmd), `FileInputStream.open` —
   all standard native-model/axiom candidates. 103–225 methods is a checkable
   summary-family size.

## Verdict: route (b) — open-call-as-unmodeled-action is needed

- (a) **Egalitarian-model inventory alone: REJECTED by the data.** Even with the
  carve-out, closure = 26,000–33,871 methods (44–52% of the world) with ~510
  natives inside. No inventory of `java.lang`/`java.util` protocol models fixes
  a transitive avalanche whose drivers include non-protocol calls and the
  static/special JDK web.
- (b) **Open-call-as-unmodeled-action: ADOPT.** Closure 103/225 methods,
  refusals reduced to 4–6 named JDK natives that the trust base can carry as
  native models/axioms (Tai-e already models several). β1's O-disp discharge
  should be formulated so that an open dispatch whose cone exits scope becomes
  an unmodeled action (havoc at the boundary projection), not a family refusal.
  The egalitarian carve-out remains useful as a PRECISION add-on on top of (b)
  (5,295–8,177 calls per slice would get real models instead of havoc), not as
  the tractability mechanism.
- (c) Re-scope instance #3: not required; (b) suffices on both slices.

## Probe approximations (all conservative toward OPEN, per plan's "syntactic
origin approximation acceptable")

- "Unique reaching def" implemented as unique whole-body def; Copy/Cast chains
  followed; params/this/loads/call-results/phi ⇒ OPEN.
- Escape-havoc: chain var passed as an argument (not as base) of any call at a
  smaller stmt index than the dispatch ⇒ OPEN (loop order ignored).
- CHA universe = Tai-e closed world (reference closure), so cone sizes are a
  floor; the intractability conclusion holds a fortiori at the floor.
- Only `org.apache.xerces.parsers.ObjectFactory` of the six ObjectFactory copies
  on xalan's classpath is referenced and hence in the hierarchy; roots follow
  the protocol's "grep the hierarchy". The other five are near-identical copies
  whose closures land in the same JDK avalanche.
- Carve-out arms: receiver static type `java.lang.Object` ∪ Object-protocol
  subsignatures (`equals(Object)`/`hashCode()`/`toString()`, any receiver) ∪
  egalitarian names declared on `java.lang`/`java.util`(/`.function`)
  interfaces. The subsignature arm slightly WIDENS the plan's letter — i.e. the
  carve-out was given its best shot, and route (a) still fails.
- invokedynamic edges are not expanded (indy is refusal-classified; none occur
  in Java 6 bytecode); native-model synthetic IR is not scanned.

## Gate consequence

β1's O-disp discharge starts from the open-call-as-unmodeled-action semantics.
To record (per plan): this verdict in the ledger and in α1 §4.2/§7, α2 §4.
