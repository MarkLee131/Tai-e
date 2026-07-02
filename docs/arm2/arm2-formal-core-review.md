# Review of the formal core (`2026-07-02-arm2-formal-core-oracle-soundness.md`)

**Date:** 2026-07-02
**Basis:** Møller & Schwartzbach, *Static Program Analysis* (the `static program analysis.pdf`
in this folder). Page citations below are that book's printed pages.
**Verdict:** the three-theorem architecture (Safety / Confinement / Benefit) is the right
spine, but several load-bearing statements are, as written, **incorrect or inconsistent** —
most seriously the soundness statement (T1), the monotonicity of `F_O`, and the
concrete/abstract domain setup. They are fixable; this is the fix list, prioritized.

Legend: **[C] correction** (a claim is false/inconsistent as written) · **[R] rigor**
(true but under-justified / imprecise) · **[m] minor/presentation**.

---

## A. Corrections — things that are actually wrong

### [C1] The concrete semantics is put in the *abstract* lattice, then abstracted again (§2)
> §2: "`⟦P⟧ ∈ D` is the collecting (soundy) semantics … **Soundness** ≝ `X ⊒ α(⟦P⟧)`."

`⟦P⟧` is declared to live in `D` (the abstract analysis lattice), and then `α` is applied to
it. That is a category error: in the book the collecting semantics lives in the **concrete**
lattice `C = (℘(ConcreteState))ⁿ` ordered by `⊆` (p.164, §12.1), and `α : C → D` maps it into
the abstract lattice (p.171–172, §12.2). Applying `α` to something already in `D` is
ill-typed, and `X ⊒ α(⟦P⟧)` cannot be stated until `α`'s domain is `C`, not `D`.

**Fix.** Introduce two lattices explicitly:
- concrete `C = ℘(Runtime facts)` (per-program-point sets of concrete states / a trace
  collecting semantics, p.164);
- abstract `D` (the points-to/call-graph state, §2);
- a Galois connection `⟨C, α, γ, D⟩` (p.171: `α`,`γ` monotone, `γ∘α` extensive, `α∘γ`
  reductive; characterization `α(x)⊑y ⟺ x⊑γ(y)`).
Then `⟦P⟧ ∈ C` and soundness `X ⊒ α(⟦P⟧)` (α-form, matching the book p.171) type-checks.
The book uses the **α-form**, so the doc's α-form is the right choice — it just needs `α`
rooted in `C`.

### [C2] T1 overclaims *absolute* soundness and contradicts P2 (§7, §3)
> P2 (§3): `X_base ⊒ α(⟦P⟧_{no-external-refl})` — base is sound **only** for the fragment
> without external-input reflection.
> T1 (§7): "for **every** oracle `O`, `X_O ⊒ α(⟦P⟧)`" — full `⟦P⟧`, external reflection
> included.

These are inconsistent. Under a **wrong or silent** oracle (`O(ℓ)` empty or naming the wrong
class), a residual site is `Resolved` to the wrong class (or `Flagged`, which injects
nothing). Neither over-approximates the *true* external-input target, so `X_O` does **not**
cover that part of `⟦P⟧` — exactly the fragment P2 already excludes for the base. So
`X_O ⊒ α(⟦P⟧)` is **false for arbitrary `O`**. (A wrong oracle *cannot* manufacture soundness
that no static analysis has — this is precisely SOLAR's "sound modulo input" limitation.)

**Fix — split T1 into the two things that are actually true ∀O:**
- **T1a (preservation / relative soundness):** `∀O. X_O ⊒ X_base`. Immediate from Lemma 3
  (add-only ⇒ `F_O ⊒ F_base` ⇒ `lfp` order). Hence `γ(X_O) ⊇ γ(X_base)`: the oracle **never
  removes coverage** — it cannot introduce unsoundness. *This is the real "LLM is safe"
  theorem, and it is the honest one.*
- **T1b (soundness modulo external input):** `∀O. X_O ⊒ α(⟦P⟧_{no-external-refl})` (inherits
  P2; add-only preserves it). The analysis is sound exactly to the degree any static
  reflection analysis is — modulo the inputs it cannot read.
- **Drop** the unconditional `X_O ⊒ α(⟦P⟧)`. Full external-refl soundness holds only under
  `Correct_O` (that is T3a) or if every residual site falls back to `OverApprox(B)` (global
  over-approximation, which the design deliberately does *not* do, because it uses `Resolved`
  for recall). State this trade explicitly.

This is the single most important correction — a reflection-analysis reviewer (e.g. the SOLAR
authors) will catch the overclaim immediately, and the *relative* framing is both defensible
and still exactly the selling point ("the LLM cannot make a sound analysis unsound").

### [C3] `F_O` is **not monotone** as defined — the residual trigger is anti-monotone (§6, P4)
> P4 caveat (§6): "verify `Residual` is monotone — it is, because … a **shrinking target set**
> only enables the residual, add-only."

The justification is backwards. In a may-analysis the target set **grows** during fixpoint
iteration, it never shrinks. `Residual_X(ℓ)` contains the disjunct `Targets_X(ℓ)=∅`; as `X`
grows, `Targets_X(ℓ)` grows, so `Targets=∅` flips **true→false** — the disjunct is
**anti-monotone**. A site flagged residual only because its targets were empty becomes
**un-flagged** once they fill in; then `F_O` stops injecting that site's oracle facts, so
`F_O(X) ⋢ F_O(X')` for `X ⊑ X'`. `F_O` is therefore **non-monotone**, and `lfp(F_O)` is not
guaranteed to exist / Kleene iteration need not converge (Kleene requires a *monotone* `f` on
a finite-height complete lattice, p.47).

**Fix — latch the trigger so consultation is monotone.** Add an auxiliary monotone component
`Fired ⊆ Site` to the state that only grows: `ℓ ∈ Fired` once `Residual`(ℓ) has *ever* held,
and inject over `Fired`, not over the instantaneous `Residual_X`. Injected facts then persist
(they are add-only and remain sound by T2 even after the site is otherwise resolved).
Equivalently, trigger only on the **monotone** predicate `⊤ ∈ S(n(ℓ))` (Unknown-in-string,
which is stable once reached — and the empty-pts *seeding* of §8.4b is exactly what raises an
empty-pts name var to `S=⊤`, so the `Targets=∅` disjunct is redundant once seeding is in
place). Either way, make `Residual`/trigger monotone and say so; the current caveat is not
just under-argued, it asserts the false direction.

### [C4] The string domain is not a finite-height lattice and `α_str` is not a Galois α (§2, §8.4a)
> §2: `Str = ℘(Σ*) ∪ {⊤}`.  §8.4a: `α_str(L) = L if L is finite-and-small, else ⊤`.

Two problems. (i) `℘(Σ*)` already has top `Σ*`; the extra `∪{⊤}` is redundant — `⊤` *is* `Σ*`
("any string"). (ii) `℘(Σ*)` has **infinite height** (ascending chains `{a}⊑{a,b}⊑…`), so it
is not a monotone-framework lattice (p.41 height; p.79: finite height is required, else use
widening). "finite-and-small" is not a lattice condition; the `≤k`-then-`⊤` rule is really a
**widening**, not a clean abstraction.

**Fix.** Either (a) make the domain **finite-height** by bounding the constant-set size:
`Str = ℘_{≤k}(Σ*) ∪ {⊤}` with join defined as `∪` when `≤k` else `⊤` — then `α_str` is
monotone and the height is finite (p.41); or (b) declare an explicit **widening** `∇` on the
string domain satisfying the two widening properties (p.82: `x,y ⊑ x∇y` and it stabilizes
every ascending chain), and drop the claim that `α_str` is a Galois abstraction. Do not
present it as a Galois `α` without the connection; the book is careful to separate
finite-height-lattice analyses (Ch 5) from widening-based ones (Ch 6).

---

## B. Rigor — true but under-justified or imprecise

### [R1] Missing finite-height / finiteness hypotheses; "Tarski/Kleene" conflated (§2, §7 Lemma 3)
`lfp(F) = ⊔ᵢ F^i(⊥)` (the form the doc uses via "Kleene") holds for a monotone `f` on a
complete lattice **of finite height** (p.47). The doc says "by Tarski/Kleene" but never states
(a) `D` is a **complete lattice** (only "complete lattice ordered pointwise" is asserted
without the finiteness that makes it usable), nor (b) that the abstract carriers `Obj`, `Type`
are **finite** (allocation-site abstraction, p.148) so `D` has finite height. Tarski gives an
lfp for any monotone map on a complete lattice; the *iterative* `⊔F^i(⊥)` needs finite height
(or continuity). **Fix:** state `Obj`, `Type`, `Field`, `Method` finite ⇒ `D` finite height ⇒
Kleene form valid; cite p.47/p.41. Keep Tarski and Kleene distinct.

### [R2] T2 confinement is only about the *direct* injection; transitive pollution is unbounded (§7)
> T2 "Reading": "the oracle's blast radius is **bounded by the code's own casts**."

The theorem statement is scoped to "the variable `v` receiving ℓ's result", and *there* it is
correct: `pt_{X_O}(v)\pt_{X_base}(v) ⊆ {o:type(o)≼B(ℓ)}`. But the *Reading* generalizes to the
whole analysis, which is false. An injected (even type-correct) class makes **new methods
reachable** via the cascade (§8.3d); those methods allocate their own objects and add points-to
facts **not** bounded by `B(ℓ)`. Secondary pollution propagates like any spurious call-graph
edge — bounded only by reachability, not by `B(ℓ)`. **Fix:** keep the precise per-var
statement; replace the "blast radius bounded by casts" sentence with the honest version: *the
direct contribution at ℓ is type-confined; downstream effects of a wrong-but-type-valid class
propagate like any spurious edge and are bounded only by reachability.* (This weakens the
confinement pitch but is the truth; T2 is still meaningful — the LLM cannot inject an
off-envelope object *at the site*, so it cannot, e.g., fabricate an arbitrary-typed receiver.)

### [R3] `B(ℓ)` and Lemma 2 are stated uniformly but differ per API (§4, §6)
For `newInstance`, the disposed object is an **instance** of a class and `t≼B` (the downcast)
is the right clamp. For `invoke`/`get`/`set`, the disposed metaobject is a **Method/Field**,
and the relevant constraint is that the *holder* declaring the member is compatible with the
receiver's type — not a plain `t≼B` on a class. The uniform `Clamp={t∈Cand:t≼B}` conflates
"class instance" with "member holder". **Fix:** give Lemma 2 and `Clamp` **per-API** forms:
class-subtype clamp for `forName/newInstance`; holder-of-member-vs-receiver clamp for
`invoke/get/set` (this matches the `TypeMatcher` split the implementation already makes).

### [R4] Frame the base as a monotone constraint system with a least solution, not "add-only" (§3)
The book's vocabulary (and the right one) is: Andersen-style analysis = a **monotone
constraint system** over a finite-height lattice whose **least solution** is the result
(p.51, p.54, p.148). `F_base` is **monotone** (P1) — it need not be *extensive*
(`F_base(X)⊒X`); "add-only" is the doc's informal gloss. **Fix:** reserve "add-only"
(extensive) for the **`Inject`** component (which genuinely satisfies `Inject(Δ)(X)⊒X`), and
describe `F_base` as monotone with a least solution. This also sharpens P3 (`F_O⊒F_base`
because `F_O = F_base ⊔ extensive`, so the extra term only adds).

### [R5] Abstract strings live on objects, not variables (§2, §8.4)
`S : Var → Str` skips the heap indirection: in a pointer analysis a string constant is an
**object** (Tai-e's `StringLiteral` allocation), and a var's abstract string value is the join
of `S(o)` over `o∈pt(var)`. The whole point of the empty-pts seeding (§8.4b) is that
`pt(nameVar)` can be **empty**, which only makes sense if strings are carried by objects.
**Fix:** `S : Obj → Str`, and `Ŝ(x) = ⊔_{o∈pt(x)} S(o)`; state the residual/trigger over
`Ŝ(n(ℓ))`. This also makes the seeding argument ("inject a ⊤-string *object*") type-check.

### [R6] State the Galois connection you rely on (§8.1 uses local soundness, but §2 never sets it up)
§8.1 correctly invokes local soundness `α∘f ⊑ f#∘α` (p.171). But it is used without ever
declaring the `⟨C,α,γ,D⟩` connection (the [C1] fix). Once [C1] adds it, §8.1 is well-founded;
until then every `α∘f⊑f#∘α` obligation in §8.3 is formally dangling. **Fix:** add the
connection in §2 and reference it from §8.1.

---

## C. Minor / presentation

- **[m1] Corollary 0 wording (§6).** "No reachable reflective site is dropped" — but `Flagged`
  injects nothing, i.e. the site is left **unmodeled**: that is acknowledged *incompleteness*
  (a recorded unsoundness-at-site), not soundness. Reword to "every residual site is either
  soundly over-approximated, resolved, or **explicitly recorded as an unresolved gap**" — and
  tie it to the T1b modulo-input framing, not to soundness.
- **[m2] RefJava concrete semantics is referenced ("Appendix") but not given.** Lemma 2, T1,
  and the `α` of [C1] all depend on it. For a submission it must exist; for the skeleton, mark
  it as a required artifact, not a citation.
- **[m3] `nameOf : Type ⇌ Σ*` (§2)** is many-to-one across class loaders; A2 collapses this.
  Say `nameOf` is keyed by fully-qualified name and A2 makes it functional. One line.
- **[m4] `OverApprox(B)` with a high `B`** (e.g. `B=Object` when the only bound is a
  no-op cast) injects *all* instantiable classes — sound but a precision cliff. Worth one
  honest sentence (it is the price of the [C2] fallback and motivates good `B` extraction, G3).
- **[m5] κ in `Dispose` (§6)** — good that exceeding κ *widens* to `OverApprox` rather than
  truncates (the G1 fix). Make explicit that this is what keeps `Dispose` monotone in `O`
  (needed by T3b): `Resolved(Expand) ⊑ OverApprox(B)` since `Expand ⊆ {t∈Inst:t≼B}`.

---

## D. What is right and should stay

- The **three-plane partition** (§8.2) and **Proposition 8.1** (safety reduces to Plane-C) are
  sound and genuinely the strongest structural idea — they survive all the above (they are
  about *where* `O` enters, which the corrections don't touch).
- The **α-form** soundness choice matches the book (p.171). Good.
- **Lemma 1** (realizability), **T3a** (correct proposals admitted under A3), and the
  **local-soundness discharge of the static models** (§8.3) are correct in shape; §8.3(b)
  `getProperty = default ⊔ ⊤` is a real find and correctly stated.
- The honest **G1–G7** gap list is the right instinct; [C3] (latching) should be **added as
  G8**, and [C1]/[C2]/[C4] promoted from "gaps" to "core corrections" since they change
  theorem *statements*, not just the implementation.

---

## E. Priority order for the rewrite

1. **[C2]** restate T1 as T1a (preservation) + T1b (modulo-input); drop absolute soundness.
2. **[C3]** latch the trigger → make `F_O` monotone (add G8); fixes the ill-defined `lfp`.
3. **[C1]** split concrete `C` / abstract `D`, add the Galois connection; re-root `α`.
4. **[C4]** finite-height string domain **or** an explicit widening.
5. **[R2]** de-overclaim T2 (direct vs transitive); **[R3]** per-API `B`/clamp.
6. **[R1]/[R4]/[R5]/[R6]** finiteness+height hypotheses, monotone-constraint framing, `S` on
   objects, declare the connection.
7. Minor **[m1–m5]**.

Items 1–4 change what the theorems *say*; do them before expanding any proof (§11 mechanization
would otherwise mechanize a false T1 and a non-monotone `F_O`).
