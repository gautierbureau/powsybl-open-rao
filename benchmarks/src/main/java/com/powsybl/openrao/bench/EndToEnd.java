/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.powsybl.openrao.bench;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Branch;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.IdentifiableType;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openrao.data.crac.api.Crac;
import com.powsybl.openrao.data.crac.api.parameters.CracCreationParameters;
import com.powsybl.openrao.data.crac.io.network.NetworkCracCreationContext;
import com.powsybl.openrao.data.crac.io.network.NetworkCracCreator;
import com.powsybl.openrao.data.crac.io.network.parameters.CriticalElements;
import com.powsybl.openrao.data.crac.io.network.parameters.MinAndMax;
import com.powsybl.openrao.data.crac.io.network.parameters.NetworkCracCreationParameters;
import com.powsybl.openrao.data.raoresult.api.RaoResult;
import com.powsybl.openrao.raoapi.Rao;
import com.powsybl.openrao.raoapi.RaoInput;
import com.powsybl.openrao.raoapi.json.JsonRaoParameters;
import com.powsybl.openrao.raoapi.parameters.RaoParameters;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * End-to-end RAO runs sized to complete in ~1-2 minutes on the 13659-bus PEGASE case.
 *
 * <p>Runtime is dominated by sensitivity cost (~ #CNECs x #range-actions, plus the ~1s base solve on 13k buses) and
 * the number of contingency perimeters, so tractability is achieved by a deliberately bounded synthesized CRAC:
 * N contingencies, M monitored branches (CNECs) and R redispatching (injection) range actions, with search-tree
 * depth 1. These three knobs are the calibration levers (CLI args). Measured on this environment:
 * <pre>
 *   knobs (N, M, R)   CNECs    RAs   RAO time
 *   10, 200, 20        4 200    20     ~5 s
 *   40, 800, 70       64 800    70    ~62 s   (default; lands in the 1-2 min target)
 *   50, 1000, 100    101 000   100   ~197 s
 * </pre>
 */
public final class EndToEnd {

    private EndToEnd() {
    }

    /** Calibration knobs (overridable via CLI args: {@code castor [N] [M] [R]}); defaults land at ~1 min. */
    static int nContingencies = 40;
    static int nMonitored = 800;
    static int nRedispatch = 70;
    static final double REDISPATCH_RANGE_MW = 400.0;

    static RaoParameters loadDcParameters() {
        try (InputStream is = EndToEnd.class.getResourceAsStream("/RaoParameters_DC_shallow.json")) {
            return JsonRaoParameters.read(is, ReportNode.NO_OP);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** deterministic small subsets of branches/generators the CRAC is built on. */
    record Subsets(Set<String> contingencyBranches, Set<String> monitoredBranches, Set<String> generatorIds) {
    }

    static Subsets pickSubsets(Network network) {
        List<String> branchIds = network.getBranchStream().map(Identifiable::getId).sorted().toList();
        Set<String> contingencyBranches = new HashSet<>(branchIds.subList(0, nContingencies));
        Set<String> monitoredBranches = new HashSet<>(branchIds.subList(nContingencies, nContingencies + nMonitored));
        // redispatching range actions on generators with a real power range
        Set<String> generatorIds = new HashSet<>(network.getGeneratorStream()
            .filter(g -> g.getMaxP() - g.getMinP() > 50.0)
            .map(Identifiable::getId).sorted().limit(nRedispatch).toList());
        return new Subsets(contingencyBranches, monitoredBranches, generatorIds);
    }

    /**
     * MATPOWER-imported branches carry no operational limits, so the CRAC creator would build no CNEC thresholds.
     * Run a DC load flow and give each monitored branch an active-power limit just below its actual flow, so the
     * CNECs are genuinely binding and the RAO does real optimization work.
     */
    static void addSyntheticLimits(Network network, Set<String> monitoredBranches) {
        LoadFlowParameters lf = new LoadFlowParameters();
        lf.setDc(true);
        LoadFlow.run(network, lf);
        int added = 0;
        for (String id : monitoredBranches) {
            Branch<?> branch = network.getBranch(id);
            if (branch == null) {
                continue;
            }
            double p1 = branch.getTerminal1().getP();
            double limit = Double.isNaN(p1) ? 500.0 : Math.max(50.0, 0.85 * Math.abs(p1));
            branch.newActivePowerLimits1().setPermanentLimit(limit).add();
            added++;
        }
        System.out.printf("Added synthetic active-power limits to %d monitored branches%n", added);
    }

    static Crac synthesizeCrac(Network network, Subsets subsets) {
        CracCreationParameters ccp = new CracCreationParameters();
        // monitor a single side => fewer thresholds / faster
        ccp.setDefaultMonitoredLineSide(CracCreationParameters.MonitoredLineSide.MONITOR_LINES_ON_SIDE_ONE);
        NetworkCracCreationParameters p = new NetworkCracCreationParameters(null, List.of("curative"));
        ccp.addExtension(NetworkCracCreationParameters.class, p);

        // thresholds as a multiplier of the branch's permanent operational limit (added synthetically above)
        p.getCriticalElements().setThresholdDefinition(CriticalElements.ThresholdDefinition.PERM_LIMIT_MULTIPLIER);
        p.getCriticalElements().setLimitMultiplierPerInstant(Map.of("preventive", 1.0, "outage", 1.3, "curative", 1.1));

        p.getContingencies().setBranchFilter(b -> subsets.contingencyBranches().contains(b.getId()));
        p.getCriticalElements().setOptimizedMonitoredProvider(
            (b, c, cc) -> new CriticalElements.OptimizedMonitored(subsets.monitoredBranches().contains(b.getId()), false));

        // disable PST range actions: PEGASE's MATPOWER phase-shifters are single-step (no usable tap range)
        p.getPstRangeActions().setPstRaPredicate((twt, state, cc) -> false);

        // redispatching (injection) range actions on the selected generators, in preventive
        Set<String> genSet = subsets.generatorIds();
        p.getRedispatchingRangeActions().setRdRaPredicate(
            (injection, instant, cc) -> injection.getType() == IdentifiableType.GENERATOR
                && instant.isPreventive() && genSet.contains(injection.getId()));
        p.getRedispatchingRangeActions().setRaRangeProvider(
            (injection, instant) -> new MinAndMax<>(-REDISPATCH_RANGE_MW, REDISPATCH_RANGE_MW));

        NetworkCracCreationContext ctx = NetworkCracCreator.createCrac(network, ccp);
        Crac crac = ctx.getCrac();
        System.out.printf("Synthesized CRAC: %d contingencies, %d FlowCNECs, %d injection RAs (%d generators selected)%n",
            crac.getContingencies().size(), crac.getFlowCnecs().size(), crac.getInjectionRangeActions().size(),
            subsets.generatorIds().size());
        return crac;
    }

    static void applyKnobs(String[] args, int startIdx) {
        if (args.length > startIdx) {
            nContingencies = Integer.parseInt(args[startIdx]);
        }
        if (args.length > startIdx + 1) {
            nMonitored = Integer.parseInt(args[startIdx + 1]);
        }
        if (args.length > startIdx + 2) {
            nRedispatch = Integer.parseInt(args[startIdx + 2]);
        }
        System.out.printf("Knobs: nContingencies=%d, nMonitored=%d, nRedispatch=%d%n", nContingencies, nMonitored, nRedispatch);
    }

    static void runCastor(String[] args) {
        applyKnobs(args, 1);
        Path xiidm = Path.of("cases", "case13659pegase.xiidm");
        long t0 = System.nanoTime();
        Network network = Network.read(xiidm);
        System.out.printf("Loaded XIIDM (%.2f s)%n", (System.nanoTime() - t0) / 1e9);

        Subsets subsets = pickSubsets(network);
        addSyntheticLimits(network, subsets.monitoredBranches());
        Crac crac = synthesizeCrac(network, subsets);
        RaoParameters params = loadDcParameters();
        RaoInput raoInput = RaoInput.build(network, crac).build();

        long t1 = System.nanoTime();
        RaoResult result = Rao.find("SearchTreeRao").run(raoInput, params, ReportNode.NO_OP);
        double sec = (System.nanoTime() - t1) / 1e9;
        System.out.printf("CASTOR RAO: status=%s, %.2f s%n", result.getComputationStatus(), sec);
    }
}
