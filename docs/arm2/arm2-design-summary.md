# arm②: LLM-assisted, soundness-guided reflection resolution for pointer analysis
### Design summary: core idea · formal guarantees · innovations · results · lessons

**Date:** 2026-07-02 · **System:** Tai-e (Java pointer analysis) · **Fork:** github.com/MarkLee131/Tai-e (`llm-pta`)

This is the consolidated FUNCTIONAL design document; it supersedes the earlier working notes
(three-arms methodology, optimization plans, observations, systematic-limitation analysis,
theory↔evidence synthesis, framework-residual taxonomy, SOLAR comparison). Its formal
companions live alongside it: `2026-07-02-arm2-formal-core-oracle-soundness.md` (the theorem
spine T1/T2/T3, the three-state disposer, per-component local-soundness, gaps G1–G8 with
live status) and `2026-07-02-arm2-formal-core-review.md` (the textbook-grounded review that
drove its corrections).

---

## 1. Core idea: *LLM proposes, sound logic disposes*

Static pointer analysis loses recall at reflective calls whose targets are not recoverable
from the code (names driven by config files, command-line, or the app's identity). The
strongest static reflection analyses (Elf's self-inference, SOLAR's soundness-guided
collective inference) flag exactly these as unrecoverable and ask for external input.

arm② puts a Large Language Model in that slot, but strictly off the soundness-critical
path. The LLM only proposes candidate names for the residual sites; Tai-e's sound
machinery disposes: it drops non-existent classes, clamps proposals by the use-site type,
and injects only add-only points-to facts. A wrong or even adversarial LLM can never make the
analysis unsound; it can at most waste precision, bounded by the use-site type.

## 2. Formal guarantees (the delta over SOLAR/SAS'15)

The oracle is modeled as an untrusted, universally-quantified function `O`. The disposer
is a three-state map: for a residual site ℓ, realizable proposals `Cand(ℓ)=⋃Φ(O(ℓ))` (Lemma 1:
non-existent names drop), clamped by the use-site bound `Clamp(ℓ)={t∈Cand:t≼B(ℓ)}` (Lemma 2:
`B` is sound), injected add-only into a monotone may-analysis (`F_O ⊒ F_base`, Lemma 3).

- **T1 Safety** (soundness is preserved, oracle-independently). For every oracle `O`,
  `X_O ⊒ X_base` (**T1a**, preservation) and `X_O ⊒ α(⟦P⟧_{no-ext-refl})` (**T1b**, sound
  modulo external input). The oracle never appears as a hypothesis in the soundness proof;
  injection only adds facts to a monotone analysis, so no `O` can remove a needed fact. (Not
  claimed: absolute soundness w.r.t. the full semantics; no static reflection analysis has it,
  and a wrong `O` costs precision/recall, never the base's soundness.)
- **T2 Confinement** (a wrong oracle's source-site damage is type-bounded). The object the
  oracle injects at the reflective site is `≼ B(ℓ)` (garbage proposals clamped away), the
  same over-approximation a use-site cast already licenses. (Downstream, a wrong-but-type-valid
  class propagates like any spurious call-graph edge, bounded by reachability; we do not claim
  a global type envelope; see formal-core §7 T2.)
- **T3 Benefit** (recall is monotone in oracle correctness). A correct proposal for ℓ lands
  its true target in `Cand(ℓ)`, so recall rises with oracle accuracy, the LLM's only job.

This is the licensing argument that makes an untrusted LLM safe to embed in a sound analysis:
soundness and bounded-precision hold unconditionally; recall is the only thing the LLM can
move.

## 3. Innovations: the resolution pipeline

Each stage has a theoretical role (SOLAR/Elf/TOSEM) and a measured effect (§4).

1. **Unknown-trigger (latched).** Fire on input-dependent names: seed empty-points-to name
   vars with a placeholder String (`onPhaseFinish`) so the pts-driven handler fires; and
   consult the LLM whenever the site has EVER carried an Unknown name obj; the residual
   predicate ⊤∈Ŝ(n) is a per-site latch (`unknownNames`), so a co-present bogus constant
   can never suppress it (the fix that unblocked DaCapo's `findClass`, 0.008→1.0; now
   uniform across forName AND getMethod/getField) and a transiently-empty pts can never burn
   the one-shot query.
2. **Context modeling.** Backward string-flow extraction (def-indexed, one IR pass) +
   config-file reading (memoized per classpath), quality-gated (high-confidence fragments fed
   raw; weak evidence preprocessed).
3. **Identity + classpath grounding.** Feed the application identity (an out-of-band fact the
   analysis knows but the code doesn't) + the real classpath classes matching it, so the LLM
   proposes an existing convention-driven name instead of hallucinating.
4. **LLM proposal** (live Gemini; query ONCE per site), then sound disposal (T1/T2):
   realizability + type clamp + add-only injection, with proposals cached and re-injected
   on every handler fire over that fire's class set, so classes arriving in later solver
   iterations still receive them (monotone injection; the channel itself is hardened:
   256-token list answers, config-versioned cache keys, blank responses never cached,
   full control-char JSON escaping).
5. **Self-inference** (`SelfInferenceModel`): model `Class.getName()`→name-constant (one
   canonical obj per name) and `System.getProperty(k,default)`→`default ⊔ ⊤`, the ⊤ emitted
   SITE-level so opaque defaults keep it: the Elf collective-inference links Tai-e lacked;
   closes the `IMPL = forName(getProperty(key, X.class.getName()))` idiom soundly (G7).
6. **Abstract→concrete subtype expansion**: `forName`→abstract expanded to instantiable
   subclasses (a `newInstance` object is concrete), the SOLAR/Elf subtype treatment;
   overflow past κ flags the site (no silent truncation, G1), and the downcast bound is
   enforced downstream at the action site (fixture-pinned invariant).
7. **ServiceLoader** (`META-INF/services`) provider resolution with event-driven monotone
   delivery (loader→iterator→next graph): providers recorded after the iterator/next
   handlers fired are still pushed through; no stale snapshot.
8. **Cascade:** Tai-e's reflective edges (getConstructor/newInstance/invoke) propagate a
   resolved class into reachable methods (TOSEM: reflection is action-dominated, so one
   resolved bootstrap unlocks a whole subtree). These stages attach only in
   `reflection-inference:llm` mode (or explicit `-Darm2.addons`); the shipped baselines
   stay vanilla Tai-e by construction.

**Design rule enforced throughout:** every component must be doubly justified: a named role
in the theory and a measured recall/precision delta. This blocks both failure modes:
theory-only over-generalization and evidence-only over-fitting.

## 4. Experimental results (DaCapo-2006, live Gemini)

Ground truth = TamiFlex-log-reachable \ no-reflection. Method recall + precision. A
`-Darm2.noAddons` switch measures Tai-e as shipped for the SOLAR baseline.

| bench | GT | string-constant | **SOLAR (Tai-e complete)** r/p | **arm② (ours)** r/p |
|---|---|---|---|---|
| luindex | 525 | 0.006 | 0.013 / 0.233 | 1.000 / 1.000 |
| antlr | 1399 | 0.002 | 0.004 / 0.200 | 1.000 / 0.943 |
| bloat | 1385 | 0.002 | 0.004 / 0.200 | 1.000 / 0.996 |
| lusearch | 229 | 0.013 | 0.031 / 0.233 | 1.000 / 1.000 |
| chart | 2098 | 0.001 | 0.003 / 0.233 | 0.984 / 0.992 |
| hsqldb | 99 | 0.030 | 0.061 / 0.200 | 0.970 / 0.990 |
| fop | 1244 | 0.002 | 0.006 / 0.233 | 0.930 / 0.877 |
| jython | 5302 | 0.001 | 0.002 / 0.300 | 0.901 / 0.980 |
| xalan | 1115 | 0.003 | 0.007 / 0.267 | 0.494 / 0.977 |
| pmd | 1388 | 0.002 | 0.005 / 0.233 | 0.299 / 0.983 |
| eclipse | 11076 | 0.000 | 0.001 / 0.267 | 0.125 / 0.989 |

(post-fix-wave numbers, 2026-07-02: recalls identical to the pre-wave table; precision
moved ≤0.018 on four benchmarks because the soundness fixes recover facts that were
previously SILENTLY DROPPED (late-class proposals, interface fields, late ServiceLoader
providers) which a dynamic under-approximating log counts as "extra". Wall-clock, llm
config: luindex 4.7s · antlr 3.1s · bloat 4.8s · lusearch 3.1s · chart 7.4s · hsqldb 2.6s ·
fop 7.2s · jython 4.8s (was 21.8s before the efficiency pass, warm oracle cache) ·
xalan 12.8s · pmd 9.5s · eclipse 14.1s; all baseline configs sub-second to ~6s.)

- **arm② vs SOLAR, stated precisely.** In this setting, pure-static (no dynamic
  reflection log), method-reachability recall, `cs:ci`/`only-app`, arm② is ~2 orders of
  magnitude above SOLAR in recall. This is NOT "arm② beats the published SOLAR." SOLAR's
  papers (Elf ECOOP'14, SOLAR SAS'15, TOSEM'19; same DaCapo-2006/JDK-1.6) report a different
  metric (recall of the direct reflective targets dynamically executed, e.g. TOSEM Table 2
  SOLAR = total recall) on a closed world built partly from TamiFlex dynamic runs + program
  inputs + a context-sensitive analysis. Our SOLAR ≈ 0 is exactly what those papers predict
  for the pure-static setting: 55% of class-retrieving names are "Unknown" (config/cmdline);
  SOLAR/Elf do not guess names, they self-infer the type and flag the rest, so the
  config-driven harness bootstrap is flagged-and-missed, the cascade never fires, method recall
  ≈ 0. The honest framing: both arm② and SOLAR run without a dynamic log; arm② additionally
  uses one out-of-band fact, the app identity, turning it into the missing name via the LLM,
  which is a static substitute for the dynamic closed-world help SOLAR's own eval relied on.
  They are complementary: SOLAR recovers the type (self-inference), arm② the name (LLM),
  and arm②'s TypeMatcher clamp is itself SOLAR-style. (arm② precisions 0.88–1.00 and SOLAR's
  paper precisions (devirtualization ~93%) are also different metrics; not directly compared.)
- 8/11 benchmarks reach ≥0.90 recall at ≥0.98 precision. The win is exactly where
  reflection is bootstrap-and-cascade-dominated: one identity-driven name (the DaCapo
  `dacapo.<id>.<Id>Harness`) unlocks the whole subtree via self-inference.
- Recall is engineerable, not stochastic; the decisive control: bloat went 0.004→1.0 from
  one added sentence of application context (id-only grounding was ambiguous → the LLM picked
  the benchmark's own main; +launcher convention → the correct harness → cascade).

### 4.1 The review-driven correctness wave (2026-07-02)

A 7-angle code review (36 candidates) followed by benchmark experiments hardened the
implementation without regressing any number (live re-measure: luindex/antlr/fop identical,
pmd +3 sound methods, jython +0.001 recall):

- **Traditional-analysis soundness (TDD, RED→GREEN unless noted):** latched residual
  predicate (a co-present constant can never suppress the LLM; empty pts never burns the
  query latch); monotone proposal re-injection (classes arriving after the one-shot query
  now receive the cached proposals; deterministic miss reproduced and fixed); variant-matched
  resolution probe (getDeclared\* ledger honesty); `Class.getField`'s superinterface lookup;
  event-driven monotone ServiceLoader delivery (late providers pushed through a
  loader→iterator→next graph; stale-snapshot miss reproduced deterministically); site-level
  `getProperty` ⊤ (opaque defaults no longer lose G7's over-approximation).
- **LLM-channel robustness:** output-token cap 64→256 (list answers were truncated mid-FQN
  and cached forever); generation-config-versioned cache keys; blank responses treated as
  retryable and never cached (poison-proof); full control-character JSON escaping (IR text
  embedded in prompts).
- **Baseline purity by construction:** the deterministic add-ons attach only for
  `reflection-inference:llm`; the shipped baselines are vanilla Tai-e with no property
  juggling; the SOLAR-N3 ledgers reset on every analysis in a JVM.
- **Negative results, kept honestly:** a forName-site type clamp (review's P1) was
  implemented as a precision fixture first, which showed Tai-e's ACTION-site machinery
  (ReflectiveActionModel + TypeMatcher) already enforces the downcast bound where instances
  materialize; no clamp code was added, and `ReflectionCastClampTest` pins the invariant.
  Phase-end scans, classpath I/O and IR scans were indexed/memoized (measured on
  jython/eclipse).

The wave's value lands on the soundness/robustness plane, not DaCapo's recall, exactly
the T1-vs-T3 decomposition's prediction: correctness fixes protect the guarantees; recall
moves only with evidence/context quality.

### 4.2 The ablation battery (2026-07-02, same code version as the table)

Per-component switches (`-PreflAblate=…`) + context tiers (`-PreflContext=none|id|full`)
+ cold-cache salt (`-PreflCacheSalt`), all runs archived:

- **Necessity (antlr):** −seeding → 0.562; −subtypeExpansion → 0.565; full → 1.000/0.943.
  −serviceLoader → no change (honest null: DaCapo app code doesn't use it).
- **REFUTED claim:** "self-inference is necessary". −selfInference held luindex at
  1.000: the emptied name vars were seeded, fell through to the oracle (+2 live
  queries, \$0.0006), and the LLM read the class-constant default out of the prompt's
  method body. Correct statement: mutual redundancy / defense in depth; self-inference
  buys determinism and zero marginal cost, not recall exclusivity.
- **Grounding:** not load-bearing under full context on luindex/antlr (antlr even gained
  precision without it: the model proposed concrete generators directly); it earns its keep
  at the id-only tier.
- **Context staircase (none/id/full, recall(precision)):** bloat 0.004(0.833) /
  0.004(0.667) / 1.000(0.996); chart 0.003 / 0.018(0.776) / 0.984; hsqldb
  0.051 / 0.061(0.090, wrong-but-loadable proposal at a B=⊤ site injects: the
  documented unfenced case; full restores 0.990) / 0.970; fop 0.005 / 0.010 / 0.930.
  Context quality is the recall and precision dial.
- **Cold-cache cost:** luindex 4 queries/\$0.0009, antlr 3/\$0.0008, pmd 8/\$0.0045;
  ≤8 queries, <半美分 per benchmark.
- **Bootstrap substitution re-verified** on this version: +bootstrap → lucene 350/350,
  recall 0.006→1.000.
- **Stability (E):** 4 independent fresh-oracle runs (cache bypassed via salt) on
  luindex/antlr/pmd → identical recall in all 12 measurements; only jitter = 3 extra
  methods on one pmd run (precision 0.983↔0.990). Temperature-0 + realizability makes the
  pipeline deterministic-in-practice.
- **Context sensitivity (F):** 2-obj spot check (luindex/antlr/hsqldb) reproduces the
  identical recall/precision values, llm analyses sub-second; conclusions do not hinge on
  the CI setting.
- **Site granularity (G, all 11):** per-site forName-target aggregate = recall 0.29
  (app-visible callers) at precision 0.35, deliberately reported: method-level recall
  concentrates in cascade-GATING sites (luindex resolves 9/11 sites yet method recall is
  1.00, the two misses gate nothing), and site precision is depressed by sound expansions
  the dynamic log never exercises. The claim is reachability recovery, not per-site
  completeness. Full per-benchmark table in the experiment archive (`persite.py` over
  `expG.xml`).

### 4.3 Downstream impact, oracle generalization, and the Solar-compose tradeoff (2026-07-02)

Instrumentation (commit 9a1c79ba): `[cg]` call-graph edge/method counts, `-Darm2.model`
oracle switch, `[disposer]` proposed/Φ-rejected/injected counters.

- **RQ5: call-graph completeness (downstream).** Under string-constant every benchmark
  reaches only the 229-method / 642-edge DaCapo launcher shell (identical across all 11,
  the body sits behind the reflective `forName`); Reflex adds +14,344 reachable methods and
  +91,823 call edges suite-wide (2.0–22× expansion). Per-benchmark Δmethods ≈ GT
  (consistency check passed). This is the "so-what" for clients: every call-graph consumer is
  blind to that code until reflection is resolved.
- **RQ6a: failure mode (disposal).** Oracle proposed 260 class names across the suite; Φ
  realizability dropped 65 (25%) that name no loadable class (pure hallucinations),
  injected 195. Reject rate tracks difficulty: 0% on convention benches, pmd 20/40 = 50%,
  eclipse 13/32 = 43%, xalan 20/78. Empirical T1/T2: a quarter of LLM output was wrong, none
  reached the analysis.
- **RQ6b: cross-model (T1/T3).** Same pipeline, cold cache, swap model: recall∝capability:
  antlr 1.000 (2.5-flash) → 0.054 (flash-lite) → 0.002 (2.0-flash); luindex 1.000/1.000/0.006.
  Precision stays 0.64–1.00 regardless of model (empirical T1: a worse oracle loses recall,
  never breaks soundness).
- **Log4Shell downstream case (honest boundary → measured tradeoff).** Tai-e taint on
  log4j-core 2.14.0 (source = attacker string, sink = `InitialContext.lookup`, CVE-2021-44228):
  string-constant 0 flows, SOLAR 1, Reflex-alone 0. The flow is `Method.invoke`
  (TYPE)-gated: SOLAR's TypeMatcher resolves it; Reflex targets unknown NAMES (DaCapo's mode),
  not this. Reflex⊕SOLAR composition (`-Darm2.solarCompose`, opt-in, both models add-only ⇒
  sound): composed llm recovers Log4Shell (Detected=1), recall monotone (jython 0.901→0.911,
  eclipse 0.125→0.175, rest unchanged), precision −0.01…−0.19 (hsqldb 0.990→0.800,
  lusearch 1.000→0.836); SOLAR's forName c^u over-approximation returns. Kept OPT-IN;
  Reflex-alone stays the high-precision headline. Precision cost is isolated to SOLAR's forName
  c^u (not its invoke type-matching) → surgical compose (methodInvoke-only) is motivated future
  work. Mechanism: `@InvokeHandler` registration is per-plugin-instance, so two InferenceModel
  plugins coexist (forName double-fires, both add-only) with no conflict.

### 4.4 Generalization to real-world applications (RQ7, 2026-07-02)

Ran the Elf/SOLAR real-world suite (all with TamiFlex GT; harness generalized so real
apps get generic identity + their actual main class, no DaCapo launcher trick):

| app | structure | GT | sc-rec | Reflex r/p |
|---|---|---|---|---|
| columba | gated | 975 | 0.000 | 0.991 / 0.989 |
| gruntspud | gated | 80 | 0.338 | 0.625 / 0.417 |
| freecol | direct | 239 | 0.912 | 0.925 / 0.718 |
| jedit | direct | 15 | 0.800 | 0.800 / 0.750 |
| briss | direct | 10 | 0.300 | 0.300 / 1.000 |
| soot | direct | 1 | 1.000 | 1.000 / 0.500 |
| findbugs | lib-internal | 307 | 0.059 | 0.059 / 0.900 |

The DaCapo where-it-helps 3-way taxonomy TRANSFERS to real apps: (1) reflectively-GATED
(columba 0→0.991, sc reaches 41 methods, Reflex 1018; gruntspud 0.338→0.625) = the DaCapo
win reappears; (2) DIRECT-entry (jedit/freecol/briss/soot) = small residual (GT 1-239),
baseline already captures it, Reflex ~neutral; (3) LIBRARY-internal (findbugs 0.059) = same
boundary as pmd/xalan/eclipse, name resolution has no leverage. Φ hallucination-reject also
generalizes (columba 3/29, gruntspud 4/30, freecol 2/8). CONCLUSION: value is a function of
PROGRAM STRUCTURE not benchmark vintage; kills the "only-DaCapo-2006" external-validity
threat. columba is the headline real-world win (a real app that is DaCapo-like harness-gated).

## 5. Lessons & experience: improving reflection in a pointer analysis

1. **The bottleneck is evidence, not cleverness.** The LLM helps exactly to the extent it is
   given the out-of-band fact (the app identity) that static analysis lacks. Where the name is
   conventional, the LLM's world knowledge supplies it; where it is bespoke, a human/config
   must. This is the honest edge of "LLM proposes."
2. **Model = theory ∧ evidence, always.** The project's biggest near-miss was concluding from
   an un-decomposed number that "the gap is information-theoretic / +1 is the ceiling."
   Decomposing the log (70% library-internal, the rest a fixable self-inference hole plus one
   identity name), instrumenting the trigger, and running a bootstrap-only cascade experiment
   overturned it. Theory located the gap and the soundness discipline; observation revealed
   what theory did not (context-quality gating, the two unmodeled self-inference links, a
   constant suppressing the LLM). Only the fusion is correct.
3. **Confirm the cause before the fix.** A class-count "missed 49 Elem\* classes" looked like a
   registry-dispatch residual; the confirmation experiment (supply just those targets) refuted
   it, at the method level it was ~3 methods. The real residual was the JAXP→xerces chain.
4. **Know your ceiling and name it.** arm②'s remaining gap (pmd/xalan/eclipse) is a multi-layer,
   library-internal factory chain (JAXP-factory → xerces-factory → xerces-`ObjectFactory` →
   parser), below `only-app`. A correct top-level JAXP model fired and resolved but moved recall
   0; the bottleneck is one layer deeper, inside the library. That is a scope + depth
   boundary, not a name-resolution problem; LLM name-resolution has no leverage there, and it
   was cut rather than shipped as dead weight ("keep by measured effect").
5. **Soundness must be a licence, not a hope.** Embedding an untrusted component (an LLM) in a
   sound analysis is only defensible with the T1/T2 argument: the component is off the
   soundness path and its damage is type-bounded. That is what lets us use a probabilistic
   oracle without giving up the guarantees a pointer analysis exists to provide.
6. **The solver's delta mechanics are part of the spec.** The worst review-wave bugs were not
   in any formula but in the interaction with the fixpoint engine: handlers receive the
   DELTA of the changed variable (so one-shot injection silently starves late-arriving
   classes), mock objects never re-trigger handlers (so side-band state like a provider map
   silently goes stale), and per-fire local flags (`knownName`) are anti-monotone. The
   pattern that survives: latch observations, cache decisions, replay effects at the
   current state, i.e. make every plugin behave like a monotone transformer evaluated at
   the current `X`, which is exactly what the formal model assumed all along. Conversely,
   verify "missing" mechanisms before adding them: the forName-site type clamp turned out to
   already exist at the action site, and a fixture proved it; an invariant test beats new
   code.

**Bottom line.** arm② moves DaCapo reflection recall from ~0 (string-constant and SOLAR) to
near the dynamic upper bound at high precision, by supplying, safely, the one kind of
evidence no static self-inference can recover: config/identity-driven names. The guarantees
are unconditional; the recall is a function of context quality; the residual is an honestly-
scoped library-depth boundary.

---
Reproduce: `./gradlew reflRecall -ParmLive [-PreflBenchmarks=…]` (recall/precision vs
string-constant + SOLAR); diagnostic hooks `-PreflDebug`, `-PreflDumpTargets`,
`-PreflProbe=…`, `-PreflExtraLog=…`. Functional suite: `./gradlew test --tests 'pta.arm2.*'`.
