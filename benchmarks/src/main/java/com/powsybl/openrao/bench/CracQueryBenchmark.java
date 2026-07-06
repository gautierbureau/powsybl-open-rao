/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.powsybl.openrao.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Micro-benchmark of the CracImpl per-state query change (Tier 4): getFlowCnecs(state) linearly scanned the full
 * flowCnecs map filtering by state (old) versus a maintained {@code Map<State, Set<FlowCnec>>} secondary index (new).
 * A RAO calls these per-state queries repeatedly (automaton simulator, StateTree, perimeter builders). Scale is
 * derived from PEGASE: many monitored branches over a set of contingency states.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(2)
public class CracQueryBenchmark {

    // representative: monitored branches spread over contingency+instant states
    static final int CNEC_COUNT = 12000;
    static final int STATE_COUNT = 200;

    record St(String id) {
    }

    record Cnec(String id, St state) {
    }

    Map<String, Cnec> flowCnecs;               // primary store (as in CracImpl)
    Map<St, Set<Cnec>> flowCnecsPerState;       // secondary index (new)
    St[] states;

    int probe;

    @Setup(Level.Trial)
    public void setup() {
        states = new St[STATE_COUNT];
        for (int s = 0; s < STATE_COUNT; s++) {
            states[s] = new St("contingency_" + s + "_curative");
        }
        flowCnecs = new HashMap<>(CNEC_COUNT * 2);
        flowCnecsPerState = new HashMap<>();
        for (int c = 0; c < CNEC_COUNT; c++) {
            St st = states[c % STATE_COUNT];
            Cnec cnec = new Cnec("BRANCH-" + c + " - " + st.id(), st);
            flowCnecs.put(cnec.id(), cnec);
            flowCnecsPerState.computeIfAbsent(st, k -> new HashSet<>()).add(cnec);
        }
    }

    @Benchmark
    public void oldScanFilter(Blackhole bh) {
        St state = states[(probe = (probe + 1) % STATE_COUNT)];
        Set<Cnec> result = new HashSet<>();
        for (Cnec cnec : flowCnecs.values()) {
            if (cnec.state().equals(state)) {
                result.add(cnec);
            }
        }
        bh.consume(result);
    }

    @Benchmark
    public void newIndexLookup(Blackhole bh) {
        St state = states[(probe = (probe + 1) % STATE_COUNT)];
        Set<Cnec> stateCnecs = flowCnecsPerState.get(state);
        Set<Cnec> result = stateCnecs == null ? new HashSet<>() : new HashSet<>(stateCnecs);
        bh.consume(result);
    }
}
