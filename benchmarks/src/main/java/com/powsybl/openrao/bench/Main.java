/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.powsybl.openrao.bench;

import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;

import java.nio.file.Path;
import java.util.Properties;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "prepare";

        if ("jmh".equals(mode)) {
            String[] rest = new String[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            org.openjdk.jmh.Main.main(rest);
            return;
        }

        if ("castor".equals(mode)) {
            EndToEnd.runCastor(args);
            return;
        }

        if ("marmot".equals(mode)) {
            EndToEnd.runMarmot(args);
            return;
        }

        Path dir = Path.of("cases");
        Path mFile = dir.resolve("case13659pegase.m");
        Path matFile = dir.resolve("case13659pegase.mat");
        Path xiidmFile = dir.resolve("case13659pegase.xiidm");

        if ("prepare".equals(mode)) {
            long t0 = System.nanoTime();
            MatpowerMConverter.writeMat(mFile, matFile, "case13659pegase");
            long t1 = System.nanoTime();
            System.out.printf(".m -> .mat parse+write: %.2f s%n", (t1 - t0) / 1e9);

            long t2 = System.nanoTime();
            Network network = Network.read(matFile);
            long t3 = System.nanoTime();
            System.out.printf("MATPOWER import -> IIDM: %.2f s%n", (t3 - t2) / 1e9);
            System.out.printf("Network: %d buses, %d lines, %d 2w-transformers, %d generators, %d loads%n",
                network.getBusBreakerView().getBusStream().count(),
                network.getLineCount(),
                network.getTwoWindingsTransformerCount(),
                network.getGeneratorCount(),
                network.getLoadCount());

            LoadFlowParameters lfParams = new LoadFlowParameters();
            lfParams.setDc(true);
            long t4 = System.nanoTime();
            LoadFlowResult lf = LoadFlow.run(network, lfParams);
            long t5 = System.nanoTime();
            System.out.printf("DC load flow: status=%s, %.2f s%n", lf.isFullyConverged(), (t5 - t4) / 1e9);

            long t6 = System.nanoTime();
            network.write("XIIDM", new Properties(), xiidmFile);
            long t7 = System.nanoTime();
            System.out.printf("Wrote XIIDM cache (%.2f s): %s%n", (t7 - t6) / 1e9, xiidmFile);
        } else {
            System.out.println("Unknown mode: " + mode);
        }
    }
}
