# PowSyBl Open RAO — Project Deep Dive & Performance Analysis

> Analysis of the `powsybl-open-rao` codebase (v7.4.0-SNAPSHOT, ~85k lines of main
> Java source, 49 Maven modules). Covers the project architecture, the performance
> mechanisms already built into the engine, and a ranked list of concrete,
> code-grounded performance-improvement opportunities.

---

## Table of contents

1. [What the project is](#1-what-the-project-is)
2. [The problem domain in plain terms](#2-the-problem-domain-in-plain-terms)
3. [Repository architecture](#3-repository-architecture)
4. [The data layer: CRAC in, RaoResult out](#4-the-data-layer-crac-in-raoresult-out)
5. [The engines: CASTOR, FastRAO, MARMOT](#5-the-engines-castor-fastrao-marmot)
6. [Post-processing and supporting modules](#6-post-processing-and-supporting-modules)
7. [Quality and delivery](#7-quality-and-delivery)
8. [Performance mechanisms already in place](#8-performance-mechanisms-already-in-place)
9. [Performance-improvement opportunities (ranked)](#9-performance-improvement-opportunities-ranked)
10. [Suggested implementation approach](#10-suggested-implementation-approach)
11. [Where to start reading the code](#11-where-to-start-reading-the-code)

---

## 1. What the project is

Open RAO ("Open Remedial Actions Optimizer") is an open-source Java toolbox, part
of Linux Foundation Energy and built on the [PowSyBl](https://www.powsybl.org)
grid-modeling framework. Given a power grid model and a list of things that could
go wrong, it **finds the optimal set of "remedial actions"** — changing a
phase-shifting transformer (PST) tap, opening a switch, adjusting an HVDC
set-point, redispatching a generator — **so the grid stays secure at minimal cost
or maximal margin**, even after any credible outage.

It is developed primarily by RTE (the French TSO) and used in production by
European Regional Coordination Centers: capacity calculation for the CORE, SWE
(France–Spain–Portugal) and Italian (CSE) regions, via RTE's GridCapa
applications. MPL-2.0 licensed, requires JDK 21, solves its optimization problems
through Google OR-Tools.

## 2. The problem domain in plain terms

European TSOs must guarantee the grid survives any single failure (the "N-1"
rule). The project vocabulary comes straight from that world:

- A **contingency** (critical outage) is a credible failure — a line or generator
  tripping.
- A **CNEC** ("Critical Network Element & Contingency") is a monitored element
  *in a specific post-contingency situation*: "line X after the loss of line Y
  must stay under its limit". Most are **FlowCnecs** (power-flow limits); there
  are also **AngleCnecs** and **VoltageCnecs**.
- Time is discretized into **instants**: *preventive* (before any fault),
  *outage* (right after a fault, where short-duration temporary limits — TATLs —
  apply), *auto* (automatic protection devices respond) and one or more
  *curative* instants (operators act, permanent limits — PATLs — apply again).
  An (instant, contingency) pair is a **state**.
- A **remedial action** is a lever, in two families: **network actions**
  (discrete on/off: open a switch, reconnect a line) and **range actions**
  (continuous set-points: PST taps, HVDC set-points, injection/redispatching).
  Each carries **usage rules** saying when it is *available* (the optimizer may
  use it) or *forced* (it fires automatically when a condition is met — that is
  how automatons are modeled).
- All of the above is bundled in one input file called the **CRAC**
  ("Contingencies, Remedial Actions and additional Constraints").

The RAO's task: for each state, choose remedial actions so that every CNEC has a
positive margin — or, in cost mode, achieve that as cheaply as possible.

## 3. Repository architecture

The modules stack up like this (bottom → top):

| Layer | Modules | Role |
|---|---|---|
| Foundations | `commons`, `util` | Units (MW/A/kV/degree/tap), EIC codes, the three logger channels (`BUSINESS_LOGS`, `BUSINESS_WARNS`, `TECHNICAL_LOGS`), and `AbstractNetworkPool` — a ForkJoinPool of cloned network variants that powers all parallelism |
| Data model | `data/crac`, `data/rao-result`, `data/glsk`, `data/refprog`, `data/virtual-hubs`, `data/ics-importer`, `data/time-coupled-constraints` | The CRAC domain model and I/O, the result model, and market data (shift keys, exchange programs, redispatching costs, generator ramping constraints) |
| Computation services | `sensitivity-analysis`, `loopflow-computation` | Wrappers around PowSyBl load flow / sensitivity computation; loop-flow (unscheduled flow) calculation from PTDFs and GLSKs |
| The engine | `ra-optimisation/rao-api`, `ra-optimisation/search-tree-rao` | The `Rao` API and three optimizer implementations: **CASTOR**, **FastRAO**, **MARMOT** |
| Post-processing | `monitoring`, `pst-regulation` | Post-RAO angle/voltage checking; loadflow-based PST current-limiter regulation |
| Delivery & QA | `distribution`, `tests`, `python-util` | Packaging with default config; a 470+-scenario Cucumber functional test suite; parameter-migration tooling |

A consistent design discipline runs through it: every major model is split
**api/impl** (consumers only see interfaces, concrete classes hide behind
factories and `ServiceLoader` SPIs), objects are built through fluent adders
(`crac.newFlowCnec()...add()`), and optional data attaches via PowSyBl-style
extensions.

## 4. The data layer: CRAC in, RaoResult out

The central interface is
`data/crac/crac-api/.../crac/api/Crac.java` — a facade owning the instants,
contingencies, states, CNECs and remedial actions, and how they connect: each
CNEC is monitored in one state; each remedial action declares via usage rules in
which states it may (or must) act.

Because every European process has its own exchange format, CRAC import uses a
"creator" pattern rather than plain deserialization — importers are network-aware
and return a `CracCreationContext` reporting what was imported, altered or
skipped. Supported formats each map to a real process:

| Format | Module | Process |
|---|---|---|
| JSON | `crac-io-json` | Open RAO's native, round-trippable format |
| FlowBasedConstraint | `crac-io-fb-constraint` | CORE region flow-based capacity calculation |
| CIM | `crac-io-cim` | SWE region (source of angle/voltage CNECs) |
| CSE | `crac-io-cse` | Italian-border capacity calculation |
| NC | `crac-io-nc` | ENTSO-E "Network Code" CSA profiles (RDF-based) |
| network | `crac-io-network` | Synthesizes a CRAC directly from a grid file (studies/tests) |

On the output side, `RaoResult` (`data/rao-result/rao-result-api`) answers
everything about the optimized situation, queryable *per instant*:
flows/margins/loop-flows per CNEC, which actions were activated in which state,
optimized PST taps and set-points, functional vs. virtual cost, and an overall
`isSecure()`. Exporters produce the regulatory **CNE** XML documents for CORE and
SWE, plus the intraday F711 document.

## 5. The engines: CASTOR, FastRAO, MARMOT

Three `RaoProvider` implementations are registered via SPI.

### CASTOR — the search-tree RAO (default)

CASTOR ("CAlculation with Scalable and Transparent OptimiseR", entry point
`Castor.java`, orchestrated by `CastorFullOptimization.java`) hybridizes
combinatorial search and linear programming: discrete topological actions are
genuinely non-linear (an LP would misestimate them), while PSTs, HVDCs and
injections are near-linear — so it **searches over the discrete actions and
solves an LP/MIP for the continuous ones at every node**.

The optimization is split into sequential perimeters (`StateTree` decides the
partition):

1. **Preventive perimeter** — optimizes preventive actions against basecase CNECs
   plus post-outage CNECs at their temporary limits. Curative CNECs with no
   curative remedy are pulled into this perimeter at their permanent limit.
2. **Automaton simulation** (per contingency) — automatic actions are *simulated*,
   not optimized: forced network actions fire when triggered, then automatic
   range actions move one at a time (fastest first) using a closed-form
   sensitivity formula, only while overloads persist (`AutomatonSimulator`).
3. **Curative perimeters** (per contingency, per curative instant) — optimizes
   curative actions greedily, instant by instant.
4. **Second preventive RAO** (optional) — re-runs the preventive optimization
   over *all* CNECs with curative decisions held in place, catching curative
   constraints the first sequential pass could not see.

Within each perimeter, the search tree (`SearchTree.java`) works depth by depth:
the current best leaf is "bloomed" (`SearchTreeBloomer`) into candidate
network-action combinations, pruned by filters (max actions per TSO, geographic
proximity to the limiting element, already-tested combinations…), and each
candidate `Leaf` is evaluated in parallel on cloned networks. Evaluating a leaf
means running a sensitivity analysis **and re-optimizing all range actions for
that leaf** — a topology change is always judged together with its best
accompanying set-points.

The linear problem inside each leaf (`IteratingLinearOptimizer`) is a MILP built
by composable `ProblemFiller`s: the core flow equation (flow = reference flow +
sensitivity × set-point change), max-min-margin or relative-margin objectives,
loop-flow caps, MNEC constraints, discrete PST tap modeling, aligned-action
groups, usage-limit constraints. It is solved through Google OR-Tools
(`OpenRaoMPSolver` wrapper; CBC default, SCIP/Xpress optional). Because
sensitivities are only valid near the current operating point, the optimizer
iterates: solve LP → apply set-points → re-run sensitivity → re-linearize →
repeat until the *verified* objective stops improving.

Objective functions: `SECURE_FLOW` (get every margin positive),
`MAX_MIN_MARGIN`, `MAX_MIN_RELATIVE_MARGIN` (margin weighted by zone-to-zone
PTDFs) and `MIN_COST` — the "costly" mode where the objective is actual money
(activation + variation costs) plus penalty terms for residual violations.

### FastRAO — the performance variant

`fastrao/FastRao.java` exploits the fact that most CNECs are never binding. It
starts with an empty set of "critical" CNECs, runs a loadflow to find insecure
ones, runs a full CASTOR on just that subset, applies the result, re-checks *all*
CNECs, and iterates until everything is secure. Several small RAOs beat one
enormous one.

### MARMOT — the time-coupled RAO

`marmot/Marmot.java` handles constraints that couple hourly timestamps — chiefly
generator power gradients (MW/h). It first runs independent per-timestamp RAOs in
parallel to settle topological actions, then runs one **global time-coupled
linear optimization** across all timestamps to smooth range-action set-points so
gradients are respected (`GeneratorConstraintsFiller`, fed by
`data/time-coupled-constraints` and `data/ics-importer`). This is the most active
recent development area.

## 6. Post-processing and supporting modules

- **Monitoring** runs after the RAO: angle and voltage CNECs are too non-linear
  for the optimizer, so this module load-flows the optimized network, checks
  them, applies corrective actions (including GLSK-based redispatching for angle
  violations) and wraps the RaoResult with the outcome.
- **Loop-flow computation** quantifies the part of a line's flow caused by
  third-party commercial exchanges (from PTDFs, GLSKs and the reference exchange
  program); the CORE methodology caps these and `MaxLoopFlowFiller` enforces it
  in the LP.
- **pst-regulation** (newest module): instead of MILP-optimizing PST taps, it
  puts PSTs into PowSyBl's physical current-limiter regulation mode, runs
  OpenLoadFlow and reads back the taps regulation converged to.

## 7. Quality and delivery

The `tests` module is a substantial Cucumber/Gherkin functional suite — 109
`.feature` files organized by capability (import/export, multi-step optimization,
remedial actions, objective functions, time-coupled RAO, loop-flows, CNE export)
— required to pass in CI alongside unit tests, SonarCloud quality gates and
checkstyle. The Sphinx docs (`docs/`, published on ReadTheDocs) mirror the code
structure: `docs/algorithms/castor/` documents each LP filler and RAO step
individually.

---

## 8. Performance mechanisms already in place

Performance is a first-class concern (see
`docs/algorithms/castor/performance.md`):

- **FastRAO** — an entire algorithm dedicated to speed (see §5).
- **Four independent layers of parallelization**, each separately tunable:
  contingency simulations inside each security analysis (delegated to the
  loadflow engine), candidate topological actions in the search tree
  (`leavesInParallel`, evaluated on cloned networks from `AbstractNetworkPool`),
  curative perimeters after the preventive one finishes, and whole timestamps in
  multi-timestamp runs (`MultiTimestampsPool`).
- **Configurable search-effort knobs** trading optimality for time: max
  search-tree depth (preventive and curative separately) and minimum-impact
  thresholds — a candidate action must improve the objective by at least X% or
  Y MW to be kept.
- **MILP tractability tricks**: sensitivities below a configurable threshold are
  zeroed to sparsify the LP; PSTs can be modeled `CONTINUOUS` (fast) vs
  `APPROXIMATED_INTEGERS` (accurate); range-action bounds can shrink between LP
  iterations; the solver backend is swappable (CBC/SCIP/Xpress).
- **MARMOT's memory-conscious design**: inputs are network *file paths* rather
  than loaded `Network` objects, so each timestamp's network loads only when
  needed.

Recent explicitly performance-focused commits: `f2a26d5` "chore(MARMOT):
performance boost thanks to better memory management" and `28634d0` (close
auxiliary networks to free tmp) show MARMOT memory as the active battleground;
`638581d` "Improve crac element and states remove performance" patched
`CracImpl` data-structure costs.

---

## 9. Performance-improvement opportunities (ranked)

Sensitivity analysis is the dominant cost in this engine; the biggest wins are
variations of *"we compute more sensitivities than we need, more often than we
need."* All findings below are grounded in code actually read, with file
references.

### Tier 1 — redundant or oversized sensitivity computations

**1.1 MARMOT's inner loop runs per-timestamp sensitivities sequentially — with an
explicit `TODO: multi-thread`.**
`TimeCoupledIteratingLinearOptimizer.java:150-166`: every MIP iteration loops
over timestamps one by one to re-run sensitivity analyses, even though a
`parallelism` parameter is already threaded into the method and the *initial*
sensitivities are already parallelized via `MarmotUtils.smartMap`
(`Marmot.java:540`). Each timestamp has its own network, so there is no shared
state. Near-linear speedup with timestamp count using existing infrastructure —
the best effort-to-impact ratio in the codebase. *Risk: medium* (confirm
network/`SensitivityComputer` isolation per timestamp; the initial-sensi path
proves it exists).

> **Status: implemented** (in the MARMOT sensitivity parallelization PR) — see commit "Parallelize MARMOT's
> per-timestamp sensitivity analyses in the time-coupled MIP loop". The loop now
> uses the same `MarmotUtils.smartMap(..., parallelism)` pattern as the other
> per-timestamp sensitivity passes; in MARMOT each timestamp always uses a
> `PreventiveOptimizationPerimeter` and reuses only its own `SensitivityComputer`,
> so there is no cross-timestamp sharing. A new test
> (`testWithRedispatchingAndGradientOnImplicatedGeneratorsMultiThreaded`) runs the
> global MIP with 3 threads and asserts results identical to the single-threaded
> run. When `parallelism == 1` the code path is byte-for-byte the sequential one.

**1.2 The automaton simulator computes sensitivities for every curative range
action during set-point shifts.**
`AutomatonSimulator.java:247-255` builds its shift-loop analysis over auto *plus
all curative* range actions, then re-runs it after each shift (up to 10 per
aligned group — `MAX_NUMBER_OF_SENSI_IN_AUTO_SETPOINT_SHIFT` — per group, per
speed batch), but the shift loop only reads the aligned group's own
sensitivities; the curative ones are needed once, at the end
(`runPostRangeAutomatonsSensitivityComputation`). Restricting the shift-loop
analysis to the group being moved cuts automaton-simulation cost roughly in
proportion to the curative-RA count. *Risk: medium.*

**1.3 Full sensitivity analyses where load flows would do.**
Two developer-acknowledged cases:
- `CastorSecondPreventive.java:208` — post-CRA validation, commented *"TODO: this
  is too slow, we can replace it with load-flow computations or security
  analysis since we don't need sensitivity values"*.
- `FastRao.java:316+` — **every** FastRAO iteration runs three full-CRAC
  sensitivity analyses (post-PRA / post-ARA / post-CRA) just to find newly
  insecure CNECs. Intermediate iterations only need flows and margins;
  sensitivities are only needed on the final iteration to build the RaoResult.

*Risk: low-medium* — swap in a security-analysis/loadflow-based evaluation for
the intermediate checks.

### Tier 2 — the LP is torn down and rebuilt from scratch every iteration

`IteratingLinearOptimizer.java:266-272` → `LinearProblem.java:119-123`
(`updateBetweenSensiIteration` → `reset()`) → `OpenRaoMPSolver.resetModel():62-73`:
each accepted sensitivity iteration allocates a **brand-new `MPSolver`** and
re-runs every `ProblemFiller`, recreating all variables and constraints when only
the sensitivity coefficients changed. The code flags it itself (*"TODO: only
reset if failed states have changed?"*), and a lighter path
(`updateBetweenMipIteration`, `LinearProblem.java:125-127`) proves incremental
update is feasible. The cost is paid per iteration × per leaf × per depth — and
in MARMOT it is multiplied by the timestamp count, since the global model spans
all timestamps (`TimeCoupledIteratingLinearOptimizer.java:285-296` does
`reset()` + full refill of all timestamps' fillers every accepted iteration).

*Improvement:* keep the model alive and update only sensitivity-dependent
coefficients/bounds (OR-Tools `setCoefficient`/`setBounds`), or rebuild only
fillers whose inputs changed. *Risk: medium* — fillers must reset all changed
coefficients correctly; the `roundDouble` numerical-determinism machinery must be
preserved.

### Tier 3 — micro-inefficiencies in the hottest call chain

> **Status: implemented** (in the hot-path optimizations PR) — see commit "Optimize hot call chain
> from problem fillers to sensitivity results".

The verified hot chain is `AbstractCoreProblemFiller.buildFlowConstraints`
(`fillers/AbstractCoreProblemFiller.java:166-209`) → `getSensitivityValue` →
`SystematicSensitivityResult`, called on the order of CNECs × sides × range
actions × LP iterations × leaves — easily millions of calls. Along it:

- **`SystematicSensitivityResult.java:278-365`** — every getter does
  `containsKey` + `get` chains through triple-nested
  `Map<String, Map<String, Map<TwoSides, Double>>>`: up to six string-hash
  lookups per value where one `get` + null-check per level would do. The boxed
  `Double` nested-map layout itself is memory-hostile for what is a dense
  matrix. *Risk: very low.*
- **`RangeActionSensiHandler.get()`**
  (`rasensihandler/RangeActionSensiHandler.java:31-41`) — allocates a new
  handler object per query via an `instanceof` chain, in the same hot loop; pure
  garbage, trivially memoizable per range action. *Risk: low.*
- **`InjectionRangeActionSensiHandler.java:39-84`** — rebuilds its GLSK maps
  (`Collectors.toMap`) and re-sums keys with streams on every call even though
  they are constant per action; precompute in the constructor. *Risk: low.*
- **`SystematicSensitivityResult.java:391-405` / `:248-268`** — the
  `Instant`-parameterized getters and `getStatus(State)` re-stream and re-sort
  contingency results per call, while the non-instant overload is properly
  memoized (`memoizedStateResultPerCnec`); mirror the memoization keyed by
  (cnec, instant). *Risk: low.*
- **`FlowResultImpl.java:102-124`** — same `containsKey`+`get` double-lookup
  pattern in `getCommercialFlow`/`getPtdfZonalSum` (margins are already cached —
  good). *Risk: low.*
- **`LoopFlowComputationImpl.java:71-94`** — re-streams the whole GLSK entry set
  and re-filters the main-component predicate for every CNEC × side; hoist the
  filtered (variable set, net position) list out of the loop. *Risk: low.*

Individually small, all low-risk, and they compound because Tier 2 re-triggers
the fills that call them.

### Tier 4 — data-structure choices

> **Status: implemented** (in the hot-path optimizations PR) — see commit "Optimize data structures
> in solver wrapper, search tree and CRAC queries". The `CracImpl` indexes cover
> CNECs-per-state and states-per-instant/contingency; remedial-action-per-state
> queries were deliberately NOT indexed because usage rules are mutable after
> registration (`addUsageRule` is public API), which would make such an index
> unsound.

- **`OpenRaoMPSolver.java:50-51`** — solver variables/constraints are kept in
  `TreeMap`s keyed by long concatenated ID strings: O(log n) full string
  comparisons on every one of the thousands of make/get calls per model build.
  Nothing depends on the ordering (fill order determines solver ordering); a
  `HashMap` is a one-line change. *Risk: low.*
- **`CracImpl.java`** — per-state queries (`getFlowCnecs(State)` :566,
  `getRangeActions(State)` :790, `getStates(Instant)` :381,
  `getNetworkActions(State)` :903…) linearly scan every element per call, and
  `RemedialAction.isAvailableForState` allocates a stream per usage-rule check.
  Call sites cluster in `AutomatonSimulator`, `StateTree`,
  `CastorContingencyScenarios` and every perimeter builder. Secondary indexes
  (`Map<State, Set<FlowCnec>>`, `Map<Instant, Set<State>>`,
  `Map<State, Set<RangeAction<?>>>`) turn O(N) scans into O(1) — the structural
  continuation of commit `638581d`, which patched exactly this class.
  *Risk: medium* (index consistency on remove/mutation paths, e.g.
  `addUsageRule`).
- **`SearchTree.java:313-346`** — the deterministic combination comparator
  recomputes `Hashing.crc32().hashString(getConcatenatedId())` for both operands
  on every comparison and does linear `List.contains` against predefined
  combinations (`SearchTreeBloomer.java:146-148`). Cache the CRC and
  predefined-membership per `NetworkActionCombination`; back the predefined list
  with a `HashSet`. *Risk: low.*
- **`AbstractOptimizationPerimeter.java:128-134`** — `getRangeActions()`
  re-flattens and re-collects the per-state map into a new `HashSet` on every
  call although the union is invariant for the perimeter's lifetime; compute
  once in the constructor. *Risk: low-medium* (verify no caller mutates the
  returned set).

### Honorable mentions

- **`FastRao.java:183`** — the verification network pool is hardcoded to
  3 networks (`AbstractNetworkPool.create(..., 3, true)`) instead of the
  configured thread count; a `TODO` at :86-87 also notes the inner-loop RAO
  implementation should be a parameter.
- **`SearchTree.java:520-528` / `SearchTreeBloomer.java:104-167`** — per-leaf
  recomputation of values constant within a depth (applied-RA snapshot copies
  for global perimeters, per-TSO moved-tap counts); hoist once per depth.
- **`MultipleNetworkPool.java:94`** — network clones are made via
  `NetworkSerDe.copy()` (full XML serialize/deserialize round-trip). Real but
  largely amortized since clones are reused across depths (`initClones` returns
  early when no clones are needed); note each `SearchTree.run()` (one per
  perimeter) creates its own pool, so curative optimization clones at two nested
  levels.
- **MARMOT memory** — the global model keeps all timestamps'
  `PrePerimeterResult`s live (`Marmot.java:124,194`); extend the existing
  `releaseNetwork*` discipline to result objects for many-timestamp runs.

## 10. Suggested implementation approach

1. **Profile first** — JFR on a realistic CRAC from
   `tests/src/test/resources/files/` to confirm the ranking on representative
   inputs.
2. **Quick low-risk PR** — Tier 3 + Tier 4 mechanical cleanups
   (TreeMap→HashMap, sensi-handler memoization, nested-map getter cleanup,
   comparator caching). Cheap to review, regression-guarded by the
   470-scenario Cucumber suite.
3. **Highest-impact single change** — parallelize MARMOT's inner sensitivity
   loop (1.1) using the existing `smartMap` pattern.
4. **Algorithm-level wins** — automaton shift-loop sensitivity restriction (1.2)
   and loadflow-instead-of-sensi for FastRAO/second-preventive intermediate
   checks (1.3).
5. **Deepest change, own design discussion** — incremental LP update (Tier 2);
   it touches every `ProblemFiller`'s contract and the numerical-determinism
   machinery.

## 11. Where to start reading the code

1. `data/crac/crac-api/.../api/Crac.java` — the domain model in one file
2. `ra-optimisation/rao-api/.../raoapi/Rao.java` — the entry point
   (`Rao.find().run(raoInput, parameters)`)
3. `castor/algorithm/CastorFullOptimization.java` — the full orchestration
4. `searchtree/algorithms/SearchTree.java` and `Leaf.java` — the combinatorial
   core
5. `linearoptimisation/algorithms/IteratingLinearOptimizer.java` + `fillers/` —
   the MILP
6. `docs/algorithms/castor/rao-steps.md` — the conceptual map for all of the
   above
