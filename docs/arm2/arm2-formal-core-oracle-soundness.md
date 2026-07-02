# Formal core skeleton — safe integration of an untrusted oracle into sound reflection resolution

**Date:** 2026-07-02
**Status:** SKELETON (definitions + theorem statements + proof sketches + assumptions +
open gaps). This is the PLDI-shaped spine for arm②. It is deliberately *not* a finished
proof — every `⟨proof sketch⟩` marks where a full argument goes, and §10 lists the
implementation changes required for the theorems to actually hold.

**Thesis, formalized.** *LLM proposes, sound logic disposes* is three theorems:
- **T1 Safety** — soundness is preserved for **every** oracle, correct or adversarial
  (the oracle never appears as a hypothesis in the soundness proof).
- **T2 Confinement** — a wrong oracle's precision damage is bounded by a statically
  provable **use-site type envelope**; it cannot pollute beyond it.
- **T3 Benefit** — recall is **monotone in oracle correctness**: a better oracle never
  lowers recall, and a correct proposal is provably admitted (never clamped away).

The contribution to PL is not any single reflection model (those re-derive Elf/SOLAR); it
is a **general framework for admitting an untrusted, statistical component into a sound
monotone analysis with a proved safety/confinement/benefit decomposition**. Reflection is
instantiation #1 (§9).

---

## 0. Why this is the PLDI delta over SOLAR (SAS'15)

SOLAR is sound *modulo external input* and hands the residual to **human annotation** —
annotations are finite and **trusted-correct**. Our oracle is **automatic, statistical, and
may be arbitrarily wrong**. The new theorem SOLAR does not state:

> soundness is **preserved** (T1a) and holds **modulo external input** (T1b), and precision
> loss is confined at the source site (T2), under an **untrusted** oracle — quantified over
> *all* oracle outputs, not just correct ones.

SOLAR assumes the annotation is right; we assume it can be wrong and prove the analysis is
still safe — never made unsound, only possibly less precise. That quantifier flip (∀ correct →
∀ arbitrary) is the whole paper. *(We do not claim the untrusted oracle makes the analysis
absolutely sound; no static reflection analysis is. We claim it cannot degrade the soundness the
base already has, and improves recall — see §7 T1a/T1b.)*

---

## 1. Standing assumptions (inherited from SOLAR/Elf; stated, not hidden)

Mirror the reflection literature so the result is comparable and its edges are explicit.

- **A1 Closed world.** All classes that may be reflectively targeted are on the analysis
  classpath (`Loadable(P)` below is complete w.r.t. the deployment).
- **A2 Well-behaved class loaders.** `Class.forName(s)` loads the class named `s` under the
  standard loader; no custom loader renames/synthesizes classes.
- **A3 Correct casts.** A downcast `(C) e` succeeds at runtime ⇒ the runtime type of `e`
  is `≼ C`. (Used to prove the use-site bound `B` is *sound* — Lemma 2 — hence the clamp
  never drops a *true* target.)
- **A4 Object reachability.** Reflectively created/accessed objects flow only through
  statically tracked variables/fields (no out-of-band `sun.misc.Unsafe` smuggling).

T1 (safety) needs only **A1–A2** and monotonicity (§3). T2/T3 additionally need **A3**.
When A3 fails, the clamp may drop a true target: the disposer must then fall back to the
`OverApprox`/`Flagged` states (§6) rather than silently resolve — soundness is preserved
by *widening*, recall degrades gracefully, and we say so.

---

## 2. Abstract domains and notation

Standard Andersen-style whole-program may-analysis, formulated as a **monotone constraint
system whose least solution is the result** (Møller–Schwartzbach — hereafter *MS* — §5, §11.2),
over an abstract heap.

**Finiteness (so the least fixpoint is usable).** `Var, Field, Method, Type` and the abstract
objects `Obj` (allocation-site abstraction, MS §11.2 p.148) are **finite**. Hence every carrier
below is a **complete lattice of finite height**, so a monotone `F` has `lfp(F)=⊔ᵢ F^i(⊥)`
(MS §4.4 p.47; height p.41). We keep **Tarski** (an lfp exists for any monotone map on a
complete lattice) and **Kleene** (the *iterative* `⊔ᵢ F^i(⊥)` additionally needs finite height)
distinct.

| symbol | meaning |
|---|---|
| `Var, Field, Method, Type` | program variables, fields, methods, classes/types — all **finite** |
| `Obj` | abstract objects (allocation sites), incl. reflective metaobjects — **finite** |
| `t ≼ t'` | subtype (reflexive-transitive class hierarchy); `Inst ⊆ Type` = instantiable (concrete, non-abstract, non-interface) |
| `type : Obj → Type` | the (abstract) type of an object |
| `Loadable(P) ⊆ Type` | classes actually present on the classpath (A1); `nameOf : Type → Σ*` the fully-qualified-name map, made **functional** by A2 |
| `Str` | **finite-height** string lattice `℘_{≤k}(Σ*) ∪ {⊤}`: constant sets up to size `k`, with join `= ∪` when the result is `≤k` else `⊤`; `⊤` = *Unknown* (`= Σ*`). Strings live on **objects**: `S : Obj → Str`; a var's abstract string is `Ŝ(x) = ⊔_{o∈pt(x)} S(o)`. *(the `k`-bound gives finite height; equivalently declare a widening `∇`, MS §6.2 p.82.)* |
| `pt, CG, Reach` | `pt:(Var ∪ Obj×Field)→℘(Obj)` points-to; `CG⊆Invoke×Method` call graph; `Reach⊆Method` reachable methods |
| `X ∈ D` | the **abstract** analysis state `(pt, CG, Reach, S, …)`, a complete lattice of finite height, ordered pointwise by `⊑` |

**Metaobjects.** `ClassObj⟦t⟧`, `MethodObj⟦m⟧`, `FieldObj⟦f⟧ ∈ Obj` model the results of
`forName/getMethod/getField`. `type(ClassObj⟦t⟧)=Class`; its *referent* is `t`.

**Concrete vs. abstract — two distinct lattices.** The collecting semantics lives in a
**separate concrete** lattice, *not* in `D` (MS §12.1 p.164): `C = ℘(RState)`, sets of reachable
concrete states of the RefJava operational semantics (Appendix — `forName` resolves to the class
the runtime string names, `newInstance` allocates it, `invoke/get/set` dispatch on it), ordered
by `⊆`. `⟦P⟧ ∈ C` is the collecting semantics — the reachable-state set over all executions.
Abstraction/concretization form a **Galois connection** `⟨C, α, γ, D⟩` (MS §12.2 p.171–172:
`α,γ` monotone, `γ∘α` extensive, `α∘γ` reductive, `α(c)⊑d ⟺ c⊑γ(d)`) — the canonical
allocation-site abstraction. **Soundness of a state `X` ≝ `α(⟦P⟧) ⊑ X`** (equivalently
`⟦P⟧ ⊑ γ(X)`), the α-form MS use (p.171). *(So `α` is rooted in `C`; writing `α(⟦P⟧)` with
`⟦P⟧∈D` — as an earlier draft did — was ill-typed.)*

---

## 3. Base analysis as a monotone sound constraint system

The base analysis (Tai-e's PTA + its *static* reflection handling, **including our
always-on static models** — `SelfInferenceModel` for `Class.getName()`→name and
`System.getProperty(k,d)`→d, `ServiceLoaderModel`, string-constant `forName`) is a monotone
transfer function

> `F_base : D → D`, monotone, with a least fixpoint `X_base = lfp(F_base)`.

- **P1 (Monotonicity).** `X ⊑ Y ⇒ F_base(X) ⊑ F_base(Y)`. *(Holds because the analysis is a
  union-only may-analysis with no negation; standard for Andersen/Tai-e.)*
- **P2 (Base soundness).** `X_base ⊒ α(⟦P⟧_{no-external-refl})` — sound for everything
  except the *external-input residual* it provably cannot name (defined next). *(Given;
  it is SOLAR/Elf's own soundness modulo inputs, re-established for Tai-e's models.)*

Note the clean separation the formalism buys us: **the static self-inference gains live in
`F_base`, the oracle gains live only in `F_O` (§6).** This is exactly the ablation boundary
reviewers demand — the LLM's marginal contribution is `lfp(F_O) ⊖ lfp(F_base)`, by
construction disjoint from the static-modeling contribution.

---

## 4. Reflective sites, the residual, the use-site bound, realizability

- **Reflective sites** `ℓ ∈ Site`: invocations of the RefJava API
  (`forName/loadClass`, `getMethod*`, `getField*`, `getConstructor*`, `newInstance`,
  `Constructor.newInstance`, `Method.invoke`, `Field.get/set`). Each name-bearing site has a
  name operand `n(ℓ) ∈ Var`.

- **Residual predicate (SOLAR N3, decidable from `X`).** The name operand carries an
  **Unknown** (input-dependent) string component:
  > `Residual_X(ℓ) ≝ Reach_X ∋ ℓ  ∧  ⊤ ∈ Ŝ_X(n(ℓ))`

  i.e. a *reachable* reflective site whose name abstraction contains `⊤` — the slot static
  analysis provably cannot name. *(Implementation, post fix-wave 2026-07-02: the predicate is
  realized as a per-site **latch** `unknownNames` — once ANY fire observes a non-constant name
  obj, the site is residual forever. This is exactly the ever-held `⊤∈Ŝ` predicate, monotone
  by construction.* We **do not** use a `Targets=∅` disjunct: in a may-analysis `Targets_X(ℓ)`
  only *grows*, so `Targets=∅` is **anti-monotone** and would make the consult-set — and hence
  `F_O` — non-monotone. The empty-points-to case is instead handled by *seeding* (§8.4b), which
  raises an empty-pts name var to `Ŝ=⊤`, so it too satisfies the monotone `⊤∈Ŝ` predicate.)*
  Per-API note: `forName` and `getMethod/getField/getDeclared*` now ALL trigger on the same
  latched `⊤∈Ŝ(n)` — the earlier `!knownName` member-site trigger (anti-monotone per fire, and
  suppressible by a co-present constant in the same delta batch) is gone; a constant can never
  mask the residual, and an empty/transient pts can never burn the one-shot query.
  The oracle is consulted **only** on latched-residual sites.

- **Use-site type bound `B(ℓ) ∈ Type`.** A *sound upper bound* on the runtime type produced
  or acted upon at ℓ, read from the code — its meaning is **per-API** (see Lemma 2):
  - `forName(...).newInstance()` post-dominated by a downcast `(C)` ⇒ `B(ℓ)=C` (bounds the
    *instance* type);
  - `m.invoke(recv,…)` / `f.get(recv)` / `f.set(recv,…)` ⇒ `B(ℓ)=` the abstract type set of the
    *receiver* `recv` (bounds the *holder* of the admitted member: a member is admissible only
    if some `o∈pt(recv)` has `type(o) ≼ holder`);
  - absent any bound ⇒ `B(ℓ)=⊤_Type` (top of the hierarchy).

- **Realizability filter.** `Φ(name) ≝ { t ∈ Loadable(P) : nameOf(t)=name }` — the classes a
  name can *actually* denote in this program (A1/A2). Hallucinated names ⇒ `Φ=∅`.

**Lemma 1 (Realizability drops non-existent names).** For any `name`, `Φ(name) ⊆ Loadable(P)`
and `Φ(name)=∅` when no class is named `name`. *(Immediate from the definition; this is the
"a wrong guess is a non-existent class the gate drops" property, made formal.)*

**Lemma 2 (Bound soundness, needs A3) — per API.** For every residual site ℓ and every concrete
run, the true reflective target is bounded by `B(ℓ)`:
- **`forName/newInstance` (instance clamp).** The true runtime object `o★` has
  `type(o★) ≼ B(ℓ)`. ⟨A3 on the post-dominating downcast `(C)`: a successful cast to `C` forces
  `type(o★) ≼ C = B(ℓ)`.⟩
- **`invoke/get/set` (holder clamp).** The true target member `μ★` is declared in a holder
  `H(μ★)` with `∃ o∈pt(recv): type(o) ≼ H(μ★)`. ⟨the member is invoked/accessed on the receiver,
  whose runtime type `type(o★_recv)` is over-approximated by `pt(recv)`; virtual/reflective
  dispatch requires `type(o★_recv) ≼ H(μ★)`, so the holder is a supertype of a receiver type.⟩
- `B=⊤_Type` trivially bounds.

*This lemma is why clamping to `B` cannot remove a true target — the hinge of both T2 and T3.
It rests on **A3 (correct casts)** for `newInstance` and on the receiver-type soundness of the
base PTA for `invoke/get/set`; where A3 fails (cast in `try`/absent), the disposer falls back to
`OverApprox` (§6) rather than a precise clamp (see G4, §10).*

---

## 5. The oracle — untrusted, universally quantified

> `O : Site → ℘(Σ*)`, an **arbitrary** total function (no correctness assumption).

`O(ℓ)` is a set of proposed names. We prove properties **∀O**. Define oracle *correctness at
ℓ*: `Correct_O(ℓ) ≝ nameOf(t★(ℓ)) ∈ O(ℓ)` (the true runtime name is among the proposals);
no global correctness is ever assumed. The LLM is one realization of `O`; so is an adversary.

---

## 6. The three-state disposer, injection, and `F_O`

The disposer converts a proposal into admitted facts through Φ and the bound `B`, and — this
is the point — is **total**: it never silently drops a reachable residual site.

> `Dispose(ℓ, O) : { Resolved(T), OverApprox(B(ℓ)), Flagged(ℓ) }`

```
  Cand(ℓ)   = ⋃_{name ∈ O(ℓ)} Φ(name)                      -- realizable proposals (Lemma 1)
  Clamp(ℓ)  = { t ∈ Cand(ℓ) : admissible(t, B(ℓ)) }        -- per-API clamp (Lemma 2): t≼B for newInstance; ∃o∈pt(recv).type(o)≼holder(t) for invoke/get/set
  Expand(ℓ) = { t' ∈ Inst : ∃ t ∈ Clamp(ℓ). t' ≼ t }       -- subtype expansion (newInstance needs concrete)

  Dispose(ℓ,O) =
    | Resolved(Expand(ℓ))      if Cand(ℓ) ≠ ∅ ∧ Clamp(ℓ) ≠ ∅ ∧ |Expand(ℓ)| ≤ κ
    | OverApprox(B(ℓ))         if  B(ℓ) ≠ ⊤_Type ∧ ( Clamp(ℓ)=∅ ∨ |Expand(ℓ)| > κ )   -- widen to the whole bound
    | Flagged(ℓ)               otherwise ( B(ℓ)=⊤_Type and nothing realizable )        -- SOLAR N3, add nothing, record
```

- `Resolved(T)`: inject `ClassObj⟦t⟧` for each `t∈T` and let Tai-e's `ReflectiveActionModel`
  build the `newInstance/invoke/get/set` edges with argument flow.
- `OverApprox(B)`: inject **all** instantiable subtypes of `B` (a sound, less precise
  widening); `κ` bounds *precision effort*, not soundness, because exceeding it widens, never
  truncates. *(Code status 2026-07-02: `injectClassAndSubtypes` realizes the `|Expand|>κ` case
  as `Flagged`, not this `OverApprox(B)` branch — a conservative, bounded choice that restores
  no-silent-drop (G1) but does not yet recover `≼B(ℓ)` or preserve the T3b boundary case; see
  §10 G1.)*
- `Flagged(ℓ)`: add **nothing**, append ℓ to the residual gap report. Never "silently empty."

**The `queried` latch, as a monotone state component.** The implementation consults the oracle
**at most once per site** (`queried.add(invoke)`) and injects through the solver, so facts
**persist**. *(Post fix-wave: the code also caches the proposals per site and **re-runs the
injection on every subsequent fire** over that fire's class objects — i.e. it re-evaluates the
monotone `Dispose(ℓ,O)` at the CURRENT `X`, exactly as `F_O(X)` below requires. The earlier
one-shot injection evaluated `Dispose` only at first-query-time `X`, a real model↔code
mismatch: classes reaching the site later never received the proposals. Query-once, inject-
per-X.)* We model this faithfully with a monotone auxiliary component `Fired ⊆ Site` that
only grows:

> `Fired` accumulates every site that has *ever* been residual: `ℓ ∈ Fired_X` once
> `Residual_{X'}(ℓ)` held at some `X' ⊑ X`. `F_O` injects over `Fired`, **not** over the
> instantaneous residual set.

This is what makes `F_O` monotone even though a per-API trigger (`getMethod`'s `!knownName`) is
anti-monotone on its own: the latch guarantees *fire-once-and-stay*, exactly as `queried` does.

**Injection is add-only (extensive).** `Inject : PowerFacts → (D→D)`, `Inject(Δ)(X) = X ⊔
lift(Δ)`, satisfies `Inject(Δ)(X) ⊒ X`. *("Add-only" = extensive; we reserve the term for
`Inject`. `F_base` is **monotone** with a least solution (MS §5), not necessarily extensive.)*
Define the oracle-augmented analysis

> `F_O(X) = F_base(X)  ⊔  ⊔_{ℓ ∈ Fired_X} Inject( 𝓘(Dispose(ℓ,O)) )`,   `X_O = lfp(F_O)`

where `𝓘(Resolved(T))` / `𝓘(OverApprox(B))` are the injected metaobjects and `𝓘(Flagged)=∅`.

- **P3 (Add-only over the base).** `∀X. F_O(X) ⊒ F_base(X)` ⟨`F_O = F_base ⊔ (extensive Inject
  terms)`⟩.
- **P4 (Monotonicity of `F_O`).** `F_O` is monotone. ⟨Let `X ⊑ Y`. (i) `F_base(X) ⊑ F_base(Y)`
  (P1). (ii) `Fired_X ⊆ Fired_Y`: `Fired` is the accumulation of an *ever-held* predicate, a
  latched (monotone) set — growing `X` can only add sites, never remove. (iii) each
  `Dispose(ℓ,O)` is monotone in `X`: larger `X` ⇒ larger `pt`/`Ŝ`/receiver-types ⇒ larger
  `Cand`/`Clamp`/`Expand`, and the `Resolved→OverApprox` branch only *widens*
  (`Resolved(Expand) ⊑ OverApprox(B)`, since `Expand ⊆ {t∈Inst:t≼B}`), never shrinks. (iv)
  `Inject` is `⊔`-monotone. Composing gives `F_O(X) ⊑ F_O(Y)`. ∎⟩ *(An earlier draft triggered
  on a `Targets=∅` disjunct — anti-monotone (§4) — which made this false; removing it and adding
  the `Fired` latch restores monotonicity, so `lfp(F_O)` is well-defined, MS §4.4 p.47.)*

**Corollary 0 (Totality / no silent drop).** For every reachable residual site ℓ,
`Dispose(ℓ,O) ∈ {Resolved, OverApprox, Flagged}` and each accounts for ℓ (facts injected or
gap recorded). No reachable reflective site is dropped without a sound over-approximation or
an explicit flag. ⟨by case exhaustion on the definition⟩.

---

## 7. The theorems

### Lemma 3 (Fixpoint order). `X_base = lfp(F_base) ⊑ lfp(F_O) = X_O`.
⟨proof: P3 gives `F_O ⊒ F_base` pointwise; both monotone (P1, P4); by **monotonicity of `lfp`**
(MS §4.4) the lfp of the pointwise-larger monotone map is larger.⟩ *The oracle only ever adds
facts.*

### Theorem 1 (Safety). Under A1–A2 and P1–P4, for **every** oracle `O`:
> **(T1a — preservation / relative soundness)** `X_O ⊒ X_base`, hence `γ(X_O) ⊇ γ(X_base)`:
> `X_O` is sound for *everything `X_base` was sound for*. An untrusted `O` **cannot introduce
> unsoundness** — it never removes coverage.
> **(T1b — soundness modulo external input)** `X_O ⊒ α(⟦P⟧_{no-ext-refl})`: the analysis is
> sound exactly to the degree any static reflection analysis is — modulo the reflective inputs
> it cannot read (SOLAR's own limit, P2).

⟨proof: **T1a** is Lemma 3 plus `γ` monotone (MS §12.2). **T1b:** `X_O ⊒ X_base ⊒
α(⟦P⟧_{no-ext-refl})` (T1a then P2). In both, `O` enters *only* through the extensive `Inject`,
never in a fact-removing step, so neither bound mentions `O`. ∎⟩

**Not claimed:** `X_O ⊒ α(⟦P⟧)` for the **full** semantics *including* external-input
reflection. Under a wrong or silent `O`, a residual site is `Resolved` to a wrong class or
`Flagged` (injects nothing) — neither over-approximates the true external-input target, so full
soundness fails, exactly as it does for the base and for *every* static reflection analysis.
The external-input fragment is fully covered only under `Correct_O` (that is **T3a**) or by
forcing every residual site to `OverApprox(B)` — which the design deliberately does **not** do,
trading that for recall via `Resolved`. **This is the honest core:** the LLM buys *recall*, not
soundness; soundness is *preserved* (T1a) and *modulo-input* (T1b), never manufactured. *(A
wrong oracle can only make the analysis less **precise** and no more **complete** than the true
target requires — see T2/T3.)*

**Honest note.** T1a/T1b are structurally simple — "an extensive `Inject` into a monotone
analysis cannot lose coverage." Their *value* is licensing: they certify an **untrusted
statistical oracle is safe to embed at all**. The non-trivial results are T2 and T3.

### Theorem 2 (Confinement — the *direct* injection at ℓ is type-bounded). Under A1–A3, at each site ℓ the objects `X_O` injects **at ℓ's result variable `v`** are use-site-bounded:
> `pt_{X_O}(v) \ pt_{X_base}(v) ⊆ { o : type(o) ≼ B(ℓ) }` and `⊆ Loadable(P)`.

⟨proof: additions at `v` come only from `Resolved(Expand)` or `OverApprox(B)`; `Expand ⊆
{t∈Inst:t≼B}` and `OverApprox` injects exactly `{t∈Inst:t≼B}`; and `Cand ⊆ Loadable` (Lemma 1).
For `invoke/get/set` read `B` as the holder clamp (Lemma 2): the admitted member's holder is a
supertype of a receiver type, so the receiving var stays within the receiver's own type set. ∎⟩

**Scope — and the honest limit.** T2 bounds the object set **at the receiving variable**, not
the whole analysis. An injected (even type-valid) class makes new methods reachable via the
cascade (§8.3d); those methods allocate their own objects and add points-to facts **not** `≼
B(ℓ)`. Secondary pollution propagates like *any* spurious call-graph edge — bounded only by
reachability, not by `B(ℓ)`. So the correct, non-overclaimed statement is: *a wrong oracle
cannot place an off-envelope object **at the reflective site** (it cannot fabricate an
arbitrary-typed receiver there); its downstream effects are exactly those of a spurious but
type-valid edge — no worse than a mis-resolved virtual call in the base analysis.* This
source-site confinement is still a guarantee SOLAR's trusted-annotation model never stated — but
we do **not** claim a global type envelope.

### Theorem 3 (Benefit — recall is monotone in oracle correctness). Under A1–A4:
> (a) *Admission.* If `Correct_O(ℓ)` and Lemma 2 holds at ℓ, the true target is admitted: for
> `newInstance` the true `t★` (or a concrete subtype witness `t‡≼t★`) is in `Expand(ℓ)`; for
> `invoke/get/set` the true member `μ★` passes the holder clamp. A correct proposal is **never
> clamped away**.
> (b) *Monotonicity.* `O ⊑ O'` pointwise ⇒ `X_O ⊑ X_{O'}`; hence `Recall(X_{O'}) ≥ Recall(X_O)`.

⟨(a): `Correct_O(ℓ)` ⇒ `nameOf(t★)∈O(ℓ)` ⇒ `t★∈Cand` (Lemma 1); Lemma 2 ⇒ `t★` admissible ⇒
`t★∈Clamp`; if abstract, witness `t‡∈Expand`. (b): `Fired` depends on `Ŝ`, **not** on `O`, so
`O⊑O'` leaves `Fired` unchanged; larger `O` ⇒ larger `Cand/Clamp/Expand` (and any
`Resolved→OverApprox` flip only *widens*), so `F_O ⊑ F_{O'}` pointwise ⇒ `X_O ⊑ X_{O'}`; recall
is monotone in `Reach`. ∎⟩ *(b relies on the `OverApprox` widening. **Code status 2026-07-02:**
G1's silent truncation is fixed, but the code realizes `|Expand|>κ` as `Flagged`(∅), not
`OverApprox(B)` — and `Flagged` does **not** widen, so T3b(b) has a boundary exception: a better
`O'` that pushes `|Expand|` past `κ` flips `Resolved(Expand_O)`→`Flagged(∅)` and loses those
targets. So T3b holds as stated only for sites with `|Expand|≤κ` (or once the code adopts
`OverApprox`); a *single* fire-once run is still monotone as a fixpoint (`O`,`Expand`
fixed after the query). See §10 G1 — closing it (option a/b there) restores T3b in full.)*

**Reading.** Recall is the *only* thing the oracle controls, and it moves in the safe direction:
correctness ⇒ admission (T3a), more/better proposals ⇒ ≥ recall (T3b). This is the formal
content of the empirical "recall ∝ context quality" curve (bloat 0.004→1.0 from one sentence):
context makes `O` more correct, T3 turns that monotonically into recall, while T1a/T1b preserve
soundness and T2 confines the source-site damage regardless.

### The decomposition, in one line.
> **Safety is preserved unconditionally in `O` (T1a) and holds modulo external input (T1b); the
> source-site damage of a wrong `O` is type-confined (T2); benefit is monotone in `O`'s
> correctness (T3).** That is "LLM proposes, sound logic disposes."

---

## 8. Formal correctness of the current design — component decomposition by soundness plane

§7 proves the theorems over an *abstract* disposer. The **implemented** pipeline
(design-summary §3) is eight concrete components. This section discharges the theorems for
the *real* design in two moves: (i) partition the components into three **planes** by their
relation to the soundness-critical path, and (ii) give each on-path component its
**abstract-interpretation local-soundness obligation**. The payoff is a *safety reduction*:
soundness rests only on a small, auditable set of Plane-C obligations; every heuristic /
LLM-facing component is safe **by construction** (confinement to the oracle input).

### 8.1 The proof tool — local soundness of an abstract transformer

We discharge each on-path component with the standard Cousot–Cousot criterion. For a Galois
connection `℘(Concrete) —⟨α,γ⟩→ Abstract`, a concrete transformer `f` and its abstract model
`f#`, `f#` is **locally sound** iff

> `α ∘ f  ⊑  f# ∘ α`   (equivalently `f ∘ γ ⊑ γ ∘ f#`).

An analysis assembled from monotone, locally-sound transformers has
`lfp(F#) ⊒ α(lfp(F_concrete))` — **global soundness**. `add-only` (`f#(X) ⊒ X`) is the
sufficient monotone-accumulation condition we use for P3–P5. Every Plane-C component below is
discharged by exhibiting its concrete JDK/JVM semantics `f`, its model `f#`, and checking
`α∘f ⊑ f#∘α`.

### 8.2 The three planes

| plane | components (design-summary §3) | relation to soundness path | obligation | theorem served |
|---|---|---|---|---|
| **A — oracle input** | context modeling (backward string-flow slice, config read *for the prompt*), identity + classpath grounding, prompt builder | **off it entirely** — computes only the argument to `O` | **none for soundness** | recall only (T3) |
| **B — residual detection** | string abstraction `S`, empty-pts placeholder seeding, Unknown-not-suppressed trigger, `Residual_X` | gates *whether* `O` is consulted | `Residual_X` a **sound over-approx of "unresolved"** (completeness of flagging) | recall only (a miss ⇒ lost recall, never unsafety) |
| **C — sound disposal + static models** | `Φ`, type clamp, subtype `Expand`, add-only `Inject` (§6) **and** the always-on static models in `F_base`: `SelfInferenceModel`, `ServiceLoaderModel`, `ReflectiveActionModel` cascade | **fully on it** | each a **locally-sound, add-only abstract transformer** | safety + confinement (T1, T2) |

Formally, Plane A is a pure function `ctx : Site → PromptData` with `O = LLM ∘ ctx`. Since §5–§7
quantify over **all** `O`, *any* `ctx` yields a legal oracle; hence Plane A carries zero
soundness burden and may be arbitrarily heuristic. (Grounding pre-applies `Φ` to restrict
proposals to `Loadable(P)`; this is `Φ` moved earlier — a recall/precision aid that cannot
admit anything disposal-time `Φ` would later reject, so it changes neither T1 nor T2.)

**Proposition 8.1 (Plane separation / safety reduction).** Under §8.2, the soundness of `X_O`
(T1) and its type-confinement (T2) depend **only** on the Plane-C obligations; Plane A is
unconstrained; a Plane-B defect can reduce only recall.
⟨proof: T1/T2 quantify over all `O` and never mention `ctx` ⇒ Plane A is free. `Residual_X`
only decides whether `O` is consulted at ℓ; *not* consulting `O` leaves `X_base`'s facts at ℓ,
sound by P2 ⇒ a Plane-B miss subtracts at most from `Cand`, i.e. recall. Every fact *addition*
flows through Plane-C disposal/models, whose local soundness + add-only give T1/T2 exactly as
in §7. ∎⟩

> This is the design's central structural virtue: it **quarantines all statistical/heuristic
> machinery (the LLM, string slicing, prompt config) into Plane A**, where the ∀-`O` theorems
> render it provably harmless to safety — leaving a soundness surface of just five abstract
> transformers plus one disposer.

### 8.3 Plane-C — static models as locally-sound abstract transformers

Each is part of `F_base` (not the oracle). We give `f` (concrete), `f#` (model), the
local-soundness check, and add-only.

**(a) `SelfInferenceModel` — `Class.getName()`.**
`f`: for a `Class` object denoting `t`, `getName()` returns the constant `nameOf(t)`.
`f#`: `getName#(x) = { StrConst(nameOf(t)) : ClassObj⟦t⟧ ∈ pt(x) }` ⊔-ed into `S(result)`.
Local soundness: `α(getName(c)) = {nameOf(type(c))}` and `ClassObj⟦type(c)⟧∈pt#(x)` ⇒
`⊑ f#∘α`. Add-only. ✔ *(closes an Elf self-inference link Tai-e lacked, soundly.)*

**(b) `SelfInferenceModel` — `System.getProperty(k, d)` — the subtle one.**
`f`: returns the environment's value for `k` **if set**, else the default `d`; the env value
is an arbitrary string **not in the program**.
A model returning only `{d}` is **UNSOUND** — it under-approximates, missing the env-set
target. The **sound** transformer joins the default with Unknown:

> `getProperty#(k,d) = S(d) ⊔ ⊤_Str`.

The `⊤_Str` component is load-bearing: it makes any downstream `forName(getProperty(k,d))` a
**residual** site (`S=⊤` ⇒ `Residual_X` fires), routing the env-/config-set case to the oracle
— while `S(d)` still flows the concrete default (this is what makes the luindex
`IMPL = forName(getProperty(key, X.class.getName()))` idiom bootstrap). Local soundness:
`α(getProperty(k,d)) = {envValue}?∪{d} ⊑ γ(S(d) ⊔ ⊤_Str)`. ✔  *(DISCHARGED, 2026-07-02: the
code emits `default ⊔ ⊤`, with the crucial realization detail that `⊤` is emitted
**site-level** — once per reachable two-arg `getProperty` site from `onPhaseFinish` — NOT from
the `argIndexes={1}` handler, which fires only when the default's pts is non-empty and hence
would lose the `⊤` exactly for opaque defaults (`StringBuilder` results). The arg-gated
handler flows only `S(d)`. Fixture: an opaque default plus a co-present constant that defeats
both the seeder and the Unknown trigger — only the site-level `⊤` recovers the env case.
Precision refinement stands: drop `⊤` only if the configuration is provably closed for `k`.)*

**(c) `ServiceLoaderModel` — `META-INF/services/<I>`.**
`f`: `ServiceLoader.load(I)` iterates the providers listed in the closed-world resource file,
instantiating and dispatching each.
`f#`: read `Prov(I)` from the classpath resource (complete under **A1**); inject `Alloc(p)`
per `p∈Prov(I)`; wire `load→iterator→next→p`.
Local soundness: under A1 the provider set **equals** the file contents (closed world), so
`f#` is sound *and* exact w.r.t. the analyzed configuration; add-only. *(MONOTONICITY fixed
2026-07-02: the original wiring copied a one-shot provider SNAPSHOT at `iterator()`/`next()`
fire time — providers recorded later (the interface `Class` objs arrive across solver
iterations, while the mock loader/iterator objects never change, so those handlers never
re-fire) were silently dropped: an under-approximation of this very `f#`, reproduced
deterministically. The code now keeps the delivery graph loader→iterators→next-result-vars
and pushes late providers through it eagerly — `f#` is evaluated at the current `X`,
add-only and monotone.)* ✔ *(Scope caveat:
providers whose service file lives in library code excluded by `only-app` are out of scope —
sound **relative to the analysis scope**, an honest scope boundary, not unsoundness.)*

**(d) `ReflectiveActionModel` cascade — metaobject → action.**
`f`: from a resolved `Class` for `t`: `newInstance()`→ fresh `t`; `getMethod(s)`→ `Method`
meta `m`; `m.invoke(recv,args)`→ dispatch with arg/return flow; `getField/get/set` likewise.
`f#`: fixed rules that, on `ClassObj⟦t⟧∈pt(x)`, add `Alloc_t` at `x.newInstance()`,
`MethodObj/FieldObj` at `getX`, and call/param/return edges (`ReflectiveCallEdge`) at
`invoke/get/set` — all `⊔`.
Local soundness: each rule mirrors the JLS reflective semantics as a join; monotone; add-only
⇒ P4/P5. This is the transformer that turns **one** resolved bootstrap name into the whole
reachable subtree (TOSEM action-dominance); its soundness is Tai-e's existing reflective-action
model, reused unchanged. ✔ *(Obligation G5, §10: audit no rule removes a fact.)*

**(e) Subtype `Expand` — closed-world completeness.**
`f`: `forName(name).newInstance()` with `name` denoting abstract/interface `A` allocates *some*
concrete `t ≼ A`.
`f#`: `Expand(A) = { t ∈ Inst : t ≼ A }`.
Local soundness needs **A1** (all subtypes known): then the true concrete `t ∈ Expand(A)`.
The cap `κ` (`MAX_SUBTYPES`, overridable via `-Darm2.maxSubtypes`) bounds precision effort;
on `|Expand|>κ` the code now **flags the site** (`Flagged`, gap report) instead of silently
truncating — no-silent-drop restored, with the `OverApprox(B)` widening (which would also
restore T3b's boundary case) still open as G1's full closure. Two further notes discharged
empirically: the downcast bound is enforced at the ACTION site (ReflectiveActionModel +
TypeMatcher — a bound-violating `t∈Expand` never becomes an instance; fixture-pinned by
`ReflectionCastClampTest`), and `Expand` re-evaluates per fire (monotone). ✔ **modulo G1 (§10).**

### 8.4 Plane-B — residual detection as sound flagging (completeness obligations)

A defect here costs recall, not safety (Prop. 8.1); we still make each obligation explicit.

**(a) String abstraction `S` (the G2 Galois connection, concretely).**
`℘(Σ*) —⟨α_str,γ_str⟩→ Str = ℘_fin(Σ*) ∪ {⊤}`, with `α_str(L) = L` if `L` is finite-and-small,
else `⊤`. **Obligation:** every string operation has a locally-sound `f#` that **widens to `⊤`**
when it cannot enumerate its result: `new String(c)`, `concat/+`, `StringBuilder.toString`,
`char[]`/byte decode, config/resource read. Under-approximating any of these (returning a
constant where the runtime value is broader) makes `Residual` **miss** ⇒ recall loss. The
per-op models + their `⊤`-widening points are the content of G2 (§10).

**(b) Empty-pts placeholder seeding — observational inertness.**
Problem: `StringBuilder`/config-read name vars have *empty* `pt` ⇒ the points-to-driven
`@InvokeHandler` never fires ⇒ the site is never even evaluated for residual. Fix: at
`onPhaseFinish`, seed a placeholder `String` object `o_⊤` with `S(o_⊤)=⊤` into `pt(n)`.
**Soundness obligation (why seeding cannot corrupt the analysis):**
1. `o_⊤` carries `name=⊤`, `type=String`; injecting it only *raises* `S(n)` toward `⊤`, a
   **sound widening** (`α_str(any runtime string) ⊑ ⊤`);
2. `o_⊤` is a `String`, **not** a `Class`, so it introduces **no** class target by itself — it
   only makes the site residual, after which `Φ`/clamp gate any oracle proposal (§6).
Hence seeding is a sound over-approximation of `S` and injects no unsound class fact; its only
cost is precision / extra oracle calls. ✔

**(c) Unknown-not-suppressed trigger (the latched `unknownNames`).**
Requirement: `Residual_X(ℓ)` must fire whenever `⊤ ∈ S(n(ℓ))` **even if `pt(n(ℓ))` also holds
constants**. The bug it fixes: a co-present constant set `knownName=true` and *suppressed* the
oracle, so a site with `pt = {const…, ⊤}` was mis-classified as resolved and its `⊤`-target
silently dropped — a **completeness violation** of `Residual`. Sound predicate: the per-site
**latch** `ℓ ∈ unknownNames` once any fire observes a non-constant name obj — the ever-held
`⊤ ∈ S(n)`, monotone by construction (no `Targets=∅` disjunct: that is anti-monotone, §4).
✔ *(This fix took DaCapo `findClass` 0.008→1.0 at forName; the 2026-07-02 wave extended the
same latched predicate to `getMethod/getField` — which still had the per-fire `!knownName`
trigger, suppressible by a constant in the same delta batch and latch-burning on transiently
empty pts — and made injection replay cached proposals per fire (§6), so late-arriving
classes receive them.)*

### 8.5 What safety actually rests on

T1 (safety) and T2 (confinement) **reduce** to: (1) the five Plane-C transformers §8.3(a–e)
being locally-sound and add-only, and (2) the disposer's `Φ`/clamp/add-only (§6, proved in §7).
Plane A (everything the LLM touches) is unconstrained; Plane B trades only recall. The
**auditable soundness surface of the entire design** is therefore small and explicit — *five
abstract transformers + one disposer*, each with a one-line local-soundness obligation — of
which three carry concrete implementation gaps tracked in §10: **G1** (`Expand` cap → widen),
**G2** (string-op `⊤`-widening), and **G7** (`getProperty` must emit `default ⊔ ⊤`).

---

## 9. Generalization — the oracle-assisted residual-closure framework (reflection = instance #1)

Abstract away "reflection." The framework is any tuple
`⟨F_base, R, B, Φ, Inject⟩` where:
- `F_base` is a monotone sound may-analysis (P1–P2);
- `R` (residual) is a decidable predicate marking facts `F_base` provably cannot resolve;
- `B` is a **sound use-site upper bound** on the residual quantity (Lemma 2 analogue);
- `Φ` is a **realizability filter** admitting only facts that exist in `P` (Lemma 1 analogue);
- `Inject` is **add-only** into the may-lattice.

**Meta-theorem.** For any such tuple and any untrusted oracle `O` proposing residual fills,
T1 (safety), T2 (confinement to `B`), T3 (benefit monotone in correctness) hold verbatim.

Instantiations (reflection is the flagship; the rest are the "why PLDI not ISSTA" generality
claim, each a future case study):
| instance | residual `R` | bound `B` | realizability `Φ` |
|---|---|---|---|
| **reflection (this paper)** | Unknown/empty reflective name | downcast / receiver type | class exists on classpath |
| indirect/virtual dispatch | unresolved fn-pointer / `invokedynamic` | declared functional type | signature-compatible method exists |
| native methods | unmodeled JNI return | declared return type | type in program |
| config-driven DI/factory | bean name from XML/props | injection-point type | bean class on classpath |

The claim to PLDI: *a principled, proved-safe protocol for letting a statistical component
close the residuals a sound analysis flags* — with reflection as the fully-built,
fully-measured instance.

---

## 10. Gaps that must be closed for the proofs to actually hold (honest TODO)

These are places where the **current implementation** contradicts the **formal model**; the
theorems are about the model, so either the code changes or a theorem weakens. Non-negotiable
for a PLDI submission.

> **Status (2026-07-02):** **G7 CLOSED** (code now matches the model). **G1 PARTIALLY CLOSED** —
> the silent truncation (a T1 no-silent-drop violation) is fixed via `Flagged`, but the code
> does not yet do the model's `OverApprox(B)`, so a T3b boundary case remains open (a
> deliberate practicality tradeoff; see G1 for the two closing options). G2–G6, G8 unchanged.
>
> **Status (2026-07-02, review-driven fix wave):** a 7-angle code review + benchmark
> re-measurement produced further alignment:
> - **Residual predicate & latch now match §4/§6 exactly:** Unknown observation is LATCHED
>   per site (`unknownNames` = the monotone ⊤∈Ŝ(n)); a co-present constant can never
>   suppress the residual; proposals are cached and RE-INJECTED per fire, making injection
>   monotone over late-arriving classes (the `Fired`-latch model's intent, now true of
>   injection too, not just querying). The SOLAR-N3 ledger is cumulative (un-flag on later
>   resolution) with a separate OVERFLOW ledger so G1's flag survives.
> - **§6 Clamp is discharged at the ACTION site:** empirical fixture evidence
>   (`ReflectionCastClampTest`) shows Tai-e's `ReflectiveActionModel` + `TypeMatcher`
>   already enforce the downcast bound where instances materialize — a bound-violating
>   subtype from the expansion never becomes an instance (no reachability). The model's
>   `Clamp(ℓ)` obligation is therefore realized by an existing Plane-C component; the
>   unclamped forName-site metaobject plane is a pts-precision (not reachability) matter.
> - **G5 progress:** the ServiceLoader cascade is now event-driven monotone (late providers
>   delivered through a loader→iterator→next delivery graph; deterministic stale-snapshot
>   repro fixed). `getFields` implements Class.getField's interface lookup (a Plane-C
>   local-soundness fix). `getProperty#` ⊤ is site-level (S5), completing G7 for opaque
>   defaults. Baselines are pure by construction (add-ons attach iff `llm` mode).
> - **Re-measured (live, 5 benchmarks):** no regressions; pmd/jython tiny gains. The
>   correctness wave moves the soundness/robustness plane, not DaCapo's recall — exactly
>   the T1-vs-T3 decomposition's prediction.

- **G1 — PARTIALLY FIXED 2026-07-02 (commit 0d9ffb86); one honest tradeoff remains.**
  `injectClassAndSubtypes` (`LlmInferenceModel.java`) no longer *silently* truncates at the
  `MAX_SUBTYPES` cap `κ`: on `|Expand| > κ` it now **flags the site** (`Flagged`, appends ℓ to
  the gap report + logs) and the cap is overridable via `-Darm2.maxSubtypes`. This **restores
  T1's no-silent-drop** (Corollary 0): the residual is explicit, never silently empty.
  **BUT it does not match §6's `OverApprox(B(ℓ))` branch, and that gap is real, not cosmetic:**
  - `Flagged` injects ∅, whereas `OverApprox(B(ℓ))` injects `{t∈Inst:t≼B(ℓ)}`. So the code is
    *sound-modulo-flag* (it does not recover the `≼B(ℓ)` targets that `OverApprox` would).
  - **It breaks T3b (monotone-in-oracle) at the κ boundary.** `OverApprox` preserves T3b
    because `Resolved(Expand) ⊑ OverApprox(B)` (widening). With `Flagged`=∅, a *better* oracle
    `O'⊒O` whose extra proposals push `|Expand|` from `≤κ` to `>κ` flips `Resolved(Expand_O)`
    → `Flagged(∅)`, i.e. **more/better proposals ⇒ less recall** — exactly the non-monotonicity
    G1 was load-bearing against. (Within a *single* fire-once run this cannot manifest —
    `O` is fixed and `Expand` is hierarchy-deterministic once the proposal lands, so the code
    is monotone as a fixpoint; the break is in the *cross-oracle* T3b theorem, not a single run.)
  - **Why not the model's `OverApprox(B(ℓ))`:** two options both preserve T3b — (a) inject *all
    subtypes of the proposal `c`* (`⊆ OverApprox(B)` since `c≼B`), simple and T3b-safe but risks
    blow-up if the LLM ever proposes a broad type; (b) inject `{t≼B(ℓ)}` properly, bounded by the
    cast but requiring `B(ℓ)`, which is a `newInstance`-site quantity awkward to obtain at the
    `forName`-site expansion. Per an explicit practicality steer, the conservative `Flagged`
    was chosen (bounded, no blow-up) at the cost of the T3b-boundary case above. **To fully close
    G1 (match §6, recover T3b): implement (a) with a high safety cap, or (b) with `B(ℓ)` threaded
    from the `newInstance` handler.** `Flagged` is the honest floor until then.
- **G2 — String-abstraction soundness.** `S : Var → Str` and the `Unknown` join must be a
  *sound* abstraction of runtime strings (`α`-`γ` on `℘(Σ*)`), else `Residual` can miss a
  site. State and discharge the Galois connection; verify `StringBuilder`/`concat`/config
  reads all widen to `⊤` soundly.
- **G3 — `B` soundness across all APIs.** Lemma 2 must be proved for *each* API form
  (`newInstance` post-dominating downcast, `invoke` receiver, `get/set` receiver,
  no-cast ⇒ `⊤`). The post-dominance/downcast extraction must be shown to actually bound the
  runtime type (needs A3 + the SSA/def-use facts Tai-e provides).
- **G4 — A3 (correct casts) is load-bearing and sometimes false.** Where a program casts
  incorrectly / catches `ClassCastException`, the clamp can drop a real target (T3a fails).
  Handle by: detect cast-in-try / no-cast ⇒ fall to `OverApprox`. Report how often this
  fires empirically.
- **G5 — Metaobject / cascade soundness — SUBSTANTIAL PROGRESS 2026-07-02.** The
  `ReflectiveActionModel` edge building and argument flow (`ReflectiveCallEdge`) must be
  add-only & monotone for P4; audit that no reflective-edge construction removes facts.
  *Discharged so far:* (i) the ServiceLoader cascade's stale-snapshot under-approximation
  (the one monotonicity violation actually found — by two independent review angles, with a
  deterministic repro) is fixed via the event-driven delivery graph (§8.3c); (ii) fixture
  evidence (`ReflectionCastClampTest`) confirms the action-site machinery enforces the
  downcast bound and never *removes* facts on the tested paths; (iii) member-site injection
  is now re-evaluated per fire (monotone, §6). *Open:* the full audit of every
  `ReflectiveActionModel` rule remains a paper-proof item, not a code change.
- **G6 — Reproducibility for the artifact.** T1–T3 are ∀O, but the *reported numbers* use a
  specific stochastic `O` (Gemini). Freeze the oracle transcript (prompt→response cache) and
  ship it in the artifact so the empirical run is deterministic; report recall as a
  distribution over N reseeded runs to show T3's "∝ correctness" is stable. *(Note
  2026-07-02: cache keys are now versioned by model + generation config — a frozen transcript
  must pin that config; changing it deliberately invalidates the transcript. Blank responses
  are never cached, so a frozen cache cannot contain poisoned empty entries.)*
- **G7 — FIXED, in two stages (both 2026-07-02).** `getProperty#(k,d) = S(d) ⊔ ⊤_Str` is the
  code. *Stage 1 (commit bb934631):* the `⊤` was emitted from the `argIndexes={1}` handler —
  which the review then showed is INCOMPLETE: that handler fires only when the default's pts
  is non-empty, i.e. never for opaque defaults (`StringBuilder` results, unmodeled reads) —
  the `⊤` was lost exactly in the most input-dependent cases. *Stage 2 (commit e736fc10, S5):*
  `⊤` is now emitted **site-level**, once per reachable two-arg `getProperty` site from
  `onPhaseFinish`, independent of the default's pts; the arg-gated handler flows only the
  default. Tests: `ReflectionEnvPropertyTest` (constant default) and `ReflectionEnvOpaqueTest`
  (opaque default + a co-present constant defeating both the seeder and the Unknown trigger —
  only the site-level `⊤` recovers the env case). **Code now matches §8.3(b) in full.**
  (Dropping `⊤` where the config is provably closed for `k` remains an available precision
  refinement.)
- **G8 — Keep the model's `Fired` latch in sync with the code's `queried` set — CLOSED
  2026-07-02, and it WAS a code bug after all.** The model's `F_O(X)` injects
  `𝓘(Dispose_X(ℓ,O))` over the latched `Fired` at every `X`; the code used to evaluate
  `Dispose` only ONCE (at first-query-time `X`), so classes reaching a member site later
  never received the proposals — a genuine under-approximation the review reproduced
  deterministically (`ArmReflectionLateClass`). The code now matches the model exactly:
  the ORACLE CALL is once per site (`queried`), the residual predicate is the latched
  `⊤∈Ŝ(n)` (`unknownNames` — also at member sites, closing the co-present-constant
  suppression and the transient-empty latch burn), and the INJECTION replays the cached
  proposals on every fire over the current class set (monotone `Dispose` at current `X`).
  (T3b's κ-boundary case still needs G1's `OverApprox` widening.)

---

## 11. What to mechanize vs. prove on paper

- **On paper:** RefJava operational semantics + `α`/`γ` + Lemmas 1–3 + T1–T3.
- **Mechanize (Coq/Lean) the high-value core:** the finite-height lattice, the `Fired` latch,
  `F_O = F_base ⊔ ⊔_{Fired} Inject`, P3/P4 (**monotonicity — mechanize this, it is where the
  `Targets=∅` bug lived**), Lemma 3, and **T1a/T1b** (the safety certificate — the one reviewers
  will most want machine-checked, since it licenses the untrusted oracle). T2/T3 depend on A3
  and are cleaner left on paper with the assumption explicit.
- **Empirical (the existing DaCapo harness):** instantiate `O`=Gemini, report the
  `X_O ⊖ X_base` marginal (isolating the LLM from the static `F_base` gains), the T3
  monotonicity curve (context-quality sweep), and the T2 confinement check (measure that no
  added object exceeds `B(ℓ)` — an *executable* corollary).

---

## 12. One-paragraph abstract seed (for the paper)

> Sound static reflection analyses (SOLAR, Elf) resolve reflective targets *modulo* the
> names they can read from code, and flag the rest — config-, command-line-, and
> identity-driven names — for **manual annotation**. We replace that trusted manual step with
> an **untrusted** language-model oracle and prove the exchange is safe: we give a
> reflection-resolution calculus in which (T1) soundness is **preserved** for *every* oracle
> output, correct or adversarial — the analysis is never made unsound, and stays sound modulo
> the external inputs no static analysis can read; (T2) a wrong proposal's precision damage is
> confined **at the reflective site** to a statically provable use-site type bound; and (T3)
> recall is monotone in oracle
> correctness, with correct proposals provably admitted. The oracle sits, by construction,
> off the soundness-critical path. We frame this as a general protocol for admitting a
> statistical component into a sound monotone analysis, instantiate it for Java reflection in
> Tai-e, and show on DaCapo that it recovers [X]% of reflection-reachable methods missed by a
> strong static baseline at [Y] precision — the recall SOLAR leaves to humans, now automatic
> and provably safe.
