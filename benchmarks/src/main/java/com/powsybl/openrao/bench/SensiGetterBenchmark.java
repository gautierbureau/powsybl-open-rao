/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.powsybl.openrao.bench;

import com.powsybl.iidm.network.TwoSides;
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

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Micro-benchmark of the SystematicSensitivityResult getter change (Tier 3): the getters walked the triple-nested
 * {@code Map<String, Map<String, Map<TwoSides, Double>>>} with a containsKey+get chain (up to 6 hash lookups) versus
 * the new single get + null-check per level (up to 3). Also benchmarks the range-action sensi handler dispatch:
 * a fresh handler object allocated per call (old) versus a memoized handler (new).
 *
 * The nested map is populated at a scale derived from the PEGASE case (20467 branches; here a representative RAO
 * perimeter subset) to exercise realistic cache behaviour. The two variants reproduce the exact code paths that
 * changed in {@code SystematicSensitivityResult}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(2)
public class SensiGetterBenchmark {

    // representative RAO perimeter scale (subset of the 20467 PEGASE branches monitored as CNECs)
    static final int NE_COUNT = 4000;
    static final int VAR_COUNT = 40;

    Map<String, Map<String, Map<TwoSides, Double>>> flowSensitivities;
    String[] neIds;
    String[] varIds;

    // range-action handler variants
    java.util.IdentityHashMap<Object, RangeActionSensiHandler> handlerCache;
    Object[] rangeActions;

    int probe;

    interface RangeActionSensiHandler {
        double sensi(String neId, TwoSides side, Map<String, Map<String, Map<TwoSides, Double>>> sens);
    }

    // stateful handler classes, reproducing new PstRangeActionSensiHandler(...) / new HvdcRangeActionSensiHandler(...):
    // a real heap object (not a scalarizable lambda) allocated by RangeActionSensiHandler.get in the old code path.
    static final class PstHandler implements RangeActionSensiHandler {
        private final String var;

        PstHandler(String var) {
            this.var = var;
        }

        @Override
        public double sensi(String neId, TwoSides side, Map<String, Map<String, Map<TwoSides, Double>>> sens) {
            return newGetSensitivityOnFlow(sens, neId, var, side);
        }
    }

    static final class HvdcHandler implements RangeActionSensiHandler {
        private final String var;

        HvdcHandler(String var) {
            this.var = var;
        }

        @Override
        public double sensi(String neId, TwoSides side, Map<String, Map<String, Map<TwoSides, Double>>> sens) {
            return newGetSensitivityOnFlow(sens, neId, var, side);
        }
    }

    // stand-ins for PstRangeAction / HvdcRangeAction / InjectionRangeAction to reproduce the instanceof dispatch
    static final class PstRa {
        final String var;

        PstRa(String var) {
            this.var = var;
        }
    }

    static final class HvdcRa {
        final String var;

        HvdcRa(String var) {
            this.var = var;
        }
    }

    @Setup(Level.Trial)
    public void setup() {
        neIds = new String[NE_COUNT];
        varIds = new String[VAR_COUNT];
        for (int v = 0; v < VAR_COUNT; v++) {
            varIds[v] = "RA_transformer_variable_" + v + "_someLongIshNetworkElementId";
        }
        flowSensitivities = new HashMap<>(NE_COUNT * 2);
        for (int n = 0; n < NE_COUNT; n++) {
            String neId = "BRANCH-" + n + "-someLongIshNetworkElementIdSuffix";
            neIds[n] = neId;
            Map<String, Map<TwoSides, Double>> perVar = new HashMap<>(VAR_COUNT * 2);
            for (int v = 0; v < VAR_COUNT; v++) {
                Map<TwoSides, Double> perSide = new EnumMap<>(TwoSides.class);
                perSide.put(TwoSides.ONE, 0.001 * (n + v));
                perSide.put(TwoSides.TWO, -0.001 * (n + v));
                perVar.put(varIds[v], perSide);
            }
            flowSensitivities.put(neId, perVar);
        }

        rangeActions = new Object[VAR_COUNT];
        handlerCache = new java.util.IdentityHashMap<>();
        for (int v = 0; v < VAR_COUNT; v++) {
            Object ra = (v % 2 == 0) ? new PstRa(varIds[v]) : new HvdcRa(varIds[v]);
            rangeActions[v] = ra;
            handlerCache.put(ra, makeHandler(ra)); // memoized once (new code path)
        }
    }

    // ---- getter double-lookup vs single-lookup ----

    static double oldGetSensitivityOnFlow(Map<String, Map<String, Map<TwoSides, Double>>> sens, String neId, String varId, TwoSides side) {
        if (!sens.containsKey(neId)
            || !sens.get(neId).containsKey(varId)
            || !sens.get(neId).get(varId).containsKey(side)) {
            return 0.0;
        }
        return sens.get(neId).get(varId).get(side);
    }

    static double newGetSensitivityOnFlow(Map<String, Map<String, Map<TwoSides, Double>>> sens, String neId, String varId, TwoSides side) {
        Map<String, Map<TwoSides, Double>> perVar = sens.get(neId);
        if (perVar == null) {
            return 0.0;
        }
        Map<TwoSides, Double> perSide = perVar.get(varId);
        if (perSide == null) {
            return 0.0;
        }
        Double value = perSide.get(side);
        return value == null ? 0.0 : value;
    }

    @Benchmark
    public void oldGetters(Blackhole bh) {
        int n = (probe = (probe + 7) & (NE_COUNT - 1));
        for (int v = 0; v < VAR_COUNT; v++) {
            bh.consume(oldGetSensitivityOnFlow(flowSensitivities, neIds[n], varIds[v], TwoSides.ONE));
            bh.consume(oldGetSensitivityOnFlow(flowSensitivities, neIds[n], varIds[v], TwoSides.TWO));
        }
    }

    @Benchmark
    public void newGetters(Blackhole bh) {
        int n = (probe = (probe + 7) & (NE_COUNT - 1));
        for (int v = 0; v < VAR_COUNT; v++) {
            bh.consume(newGetSensitivityOnFlow(flowSensitivities, neIds[n], varIds[v], TwoSides.ONE));
            bh.consume(newGetSensitivityOnFlow(flowSensitivities, neIds[n], varIds[v], TwoSides.TWO));
        }
    }

    // ---- handler allocated per call vs memoized ----

    // reproduces RangeActionSensiHandler.get(rangeAction): instanceof dispatch + heap allocation of a stateful handler
    static RangeActionSensiHandler makeHandler(Object ra) {
        if (ra instanceof PstRa pst) {
            return new PstHandler(pst.var);
        } else if (ra instanceof HvdcRa hvdc) {
            return new HvdcHandler(hvdc.var);
        }
        throw new IllegalStateException();
    }

    @Benchmark
    public void oldHandlerPerCall(Blackhole bh) {
        int n = (probe = (probe + 7) & (NE_COUNT - 1));
        for (int v = 0; v < VAR_COUNT; v++) {
            RangeActionSensiHandler handler = makeHandler(rangeActions[v]); // fresh allocation + instanceof each call
            bh.consume(handler); // force escape so allocation is not scalarized away
            bh.consume(handler.sensi(neIds[n], TwoSides.ONE, flowSensitivities));
        }
    }

    @Benchmark
    public void newHandlerMemoized(Blackhole bh) {
        int n = (probe = (probe + 7) & (NE_COUNT - 1));
        for (int v = 0; v < VAR_COUNT; v++) {
            RangeActionSensiHandler handler = handlerCache.get(rangeActions[v]); // identity lookup, no allocation
            bh.consume(handler);
            bh.consume(handler.sensi(neIds[n], TwoSides.ONE, flowSensitivities));
        }
    }
}
