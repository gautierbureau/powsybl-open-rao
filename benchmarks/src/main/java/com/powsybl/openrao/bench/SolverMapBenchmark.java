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
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Micro-benchmark of the OpenRaoMPSolver map change (Tier 4): the solver kept its variables/constraints in a
 * {@code TreeMap} keyed by long concatenated ID strings (O(log n) full-string comparisons on every put/get during
 * model build) versus a {@code HashMap}. This reproduces one LP model build (N puts) followed by the flow-constraint
 * fill phase (each variable/constraint fetched several times by name).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(2)
public class SolverMapBenchmark {

    // N variables/constraints in the LP; a large RAO perimeter builds tens of thousands of them.
    @Param({"20000"})
    int n;

    // each variable is fetched ~this many times during fill (flow constraints reference RA setpoint vars repeatedly)
    static final int LOOKUPS_PER_VAR = 4;

    String[] keys;

    @Setup(Level.Trial)
    public void setup() {
        keys = new String[n];
        for (int i = 0; i < n; i++) {
            // reproduce the long structured variable names OpenRAO builds, e.g. setpoint/flow/binary variables per RA/CNEC/state
            keys[i] = "RangeActionSetpointVariable_RA_pst_someNetworkElement_" + i + "_preventive_optimizationState";
        }
    }

    @Benchmark
    public void treeMapBuildAndFill(Blackhole bh) {
        Map<String, Object> map = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            map.put(keys[i], keys); // value irrelevant
        }
        for (int r = 0; r < LOOKUPS_PER_VAR; r++) {
            for (int i = 0; i < n; i++) {
                bh.consume(map.get(keys[i]));
            }
        }
    }

    @Benchmark
    public void hashMapBuildAndFill(Blackhole bh) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < n; i++) {
            map.put(keys[i], keys);
        }
        for (int r = 0; r < LOOKUPS_PER_VAR; r++) {
            for (int i = 0; i < n; i++) {
                bh.consume(map.get(keys[i]));
            }
        }
    }
}
