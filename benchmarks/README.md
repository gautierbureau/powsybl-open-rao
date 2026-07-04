# OpenRAO performance benchmarks

A **standalone** harness (deliberately *not* part of the Maven reactor build, so it never runs in
normal CI) for measuring the performance of OpenRAO hot paths. It provides:

- a converter that turns a MATPOWER `.m` case (e.g. the 13 659-bus `case13659pegase`) into an IIDM
  network and caches it as XIIDM;
- JMH micro-benchmarks that isolate specific optimized code paths from load-flow / solver noise.

The network data is **not** committed (see `.gitignore`); fetch it once with the step below.

## Prerequisites

1. Build & install the OpenRAO artifacts this harness depends on (from the repo root):
   ```
   mvn -q -DskipTests install
   ```
   The harness tracks the current project version (`openrao.version` in `pom.xml`).
2. Fetch the PEGASE case (only needed for the `prepare` / end-to-end steps, not for the JMH
   micro-benchmarks):
   ```
   mkdir -p benchmarks/cases
   curl -sSL -o benchmarks/cases/case13659pegase.m \
     https://raw.githubusercontent.com/MATPOWER/matpower/master/data/case13659pegase.m
   ```

## Build

```
cd benchmarks
mvn -q -DskipTests package
```
Produces a self-contained `target/bench.jar`.

## Run

Prepare the network (parse `.m` -> `.mat`, import to IIDM, DC load flow, cache XIIDM):
```
java -jar target/bench.jar prepare
```

Run the JMH micro-benchmarks:
```
java -Xmx4g -jar target/bench.jar jmh -rf text -rff jmh-results.txt ".*Benchmark.*"
```

Run an end-to-end CASTOR RAO on PEGASE (requires the XIIDM cache from `prepare`):
```
java -Xmx8g -jar target/bench.jar castor [nContingencies] [nMonitored] [nRedispatch]
```
The three optional knobs size the synthesized CRAC; the defaults (`40 800 70`) land at ~1 min.

## End-to-end runs (tractable in 1-2 min)

`castor` synthesizes a bounded CRAC on the 13 659-bus PEGASE network and runs one full CASTOR RAO.
Because MATPOWER branches carry no operational limits and its phase-shifters are single-step, the
harness adds synthetic active-power limits (just below the DC flow, so CNECs are genuinely binding)
and uses **redispatching (injection) range actions** on generators rather than PSTs.

Runtime is driven by `#CNECs x #range-actions` and the number of contingency perimeters. Measured:

| Knobs (N contingencies, M monitored, R redispatch) | FlowCNECs | Injection RAs | RAO time |
|---|---:|---:|---:|
| 10, 200, 20 | 4 200 | 20 | ~5 s |
| **40, 800, 70** (default) | **64 800** | **70** | **~62 s** |
| 50, 1000, 100 | 101 000 | 100 | ~197 s |

**Interpreting the end-to-end number.** This run exercises the optimized paths at scale (the
sensitivity getters, the LP variable/constraint maps, and `CracImpl` per-state queries), but a full
RAO's wall-clock is dominated by the OpenLoadFlow sensitivity *solve* — which the Tier 3/4 changes do
not touch. So the end-to-end delta from those changes sits within solver variance; the **JMH
micro-benchmarks above are the precise measurement** of their effect. The end-to-end run is the
right place to measure the **MARMOT parallelization** (Tier 1.1), whose speedup is multiplicative in
timestamps x cores — a `marmot` A/B (parallelism 1 vs N) is the recommended next addition.

## What the micro-benchmarks measure

Each benchmark reproduces the exact changed code path old-vs-new, side by side, at a scale derived
from the PEGASE case, so the delta is isolated from OpenLoadFlow solver variance.

| Class | Optimization |
|---|---|
| `SensiGetterBenchmark` | `SystematicSensitivityResult` getters: `containsKey`+`get` chain vs single `get`+null-check; and per-call sensi-handler allocation vs memoization |
| `SolverMapBenchmark` | `OpenRaoMPSolver` variable/constraint maps: `TreeMap` (string comparisons) vs `HashMap` |
| `CracQueryBenchmark` | `CracImpl.getFlowCnecs(state)`: full-map scan+filter vs `Map<State,Set>` secondary index |

## Latest measured results

Linux x86_64, JDK 21, JMH 1.37, 2 forks, 5×1s warmup + 8×1s measurement. Network foundation:
`case13659pegase` (13 659 buses / 20 467 branches), DC load flow converges in ~1.26 s.

| Benchmark | Old | New | Delta |
|---|---:|---:|---|
| Sensitivity getter (80 lookups) | 1743 ns | 733 ns | **-58 % (2.4x)** |
| Solver var/constraint map (build 20k + fill) | 24 589 µs | 1 831 µs | **-93 % (13.4x)** |
| `getFlowCnecs(state)` (12k CNECs / 200 states) | 339 µs | 2.0 µs | **-99.4 % (167x)** |
| Sensi-handler dispatch (PST/HVDC) | 507 ns | 632 ns | neutral (see note) |

**Note.** Handler memoization is neutral for PST/HVDC range actions — allocating the small stateful
handler is cheap enough that a cache lookup roughly breaks even. Its value is confined to *injection*
range actions, whose handler constructor rebuilds GLSK maps; that case is not exercised here. The
getter / solver-map / state-index changes carry the win.

The solver-map figure covers only the variable/constraint *map management* of a model build, not the
OR-Tools object creation; since the model is rebuilt every sensitivity iteration, it compounds.
