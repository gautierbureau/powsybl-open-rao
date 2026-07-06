/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.powsybl.openrao.bench;

import com.powsybl.matpower.model.MBranch;
import com.powsybl.matpower.model.MBus;
import com.powsybl.matpower.model.MGen;
import com.powsybl.matpower.model.MatpowerFormatVersion;
import com.powsybl.matpower.model.MatpowerModel;
import com.powsybl.matpower.model.MatpowerWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal parser for a MATPOWER ".m" case file (bus/gen/branch matrices) into a powsybl MatpowerModel,
 * then written out as a ".mat" binary that the powsybl MATPOWER importer can read.
 * Column layouts follow the standard MATPOWER CASEFORMAT.
 */
public final class MatpowerMConverter {

    private MatpowerMConverter() {
    }

    static MatpowerModel parse(Path mFile, String caseName) throws IOException {
        String content = Files.readString(mFile);
        MatpowerModel model = new MatpowerModel(caseName);
        model.setVersion(MatpowerFormatVersion.V2);
        model.setBaseMva(parseScalar(content, "mpc.baseMVA"));

        for (double[] row : parseMatrix(content, "mpc.bus")) {
            MBus bus = new MBus();
            bus.setNumber((int) row[0]);
            bus.setName("BUS-" + (int) row[0]);
            bus.setType(MBus.Type.fromInt((int) row[1]));
            bus.setRealPowerDemand(row[2]);
            bus.setReactivePowerDemand(row[3]);
            bus.setShuntConductance(row[4]);
            bus.setShuntSusceptance(row[5]);
            bus.setAreaNumber((int) row[6]);
            bus.setVoltageMagnitude(row[7]);
            bus.setVoltageAngle(row[8]);
            bus.setBaseVoltage(row[9]);
            bus.setLossZone((int) row[10]);
            bus.setMaximumVoltageMagnitude(row[11]);
            bus.setMinimumVoltageMagnitude(row[12]);
            model.addBus(bus);
        }

        for (double[] row : parseMatrix(content, "mpc.gen")) {
            MGen gen = new MGen();
            gen.setNumber((int) row[0]);
            gen.setRealPowerOutput(row[1]);
            gen.setReactivePowerOutput(row[2]);
            gen.setMaximumReactivePowerOutput(row[3]);
            gen.setMinimumReactivePowerOutput(row[4]);
            gen.setVoltageMagnitudeSetpoint(row[5]);
            gen.setTotalMbase(row[6]);
            gen.setStatus((int) row[7]);
            gen.setMaximumRealPowerOutput(row[8]);
            gen.setMinimumRealPowerOutput(row[9]);
            model.addGenerator(gen);
        }

        for (double[] row : parseMatrix(content, "mpc.branch")) {
            MBranch branch = new MBranch();
            branch.setFrom((int) row[0]);
            branch.setTo((int) row[1]);
            branch.setR(row[2]);
            branch.setX(row[3]);
            branch.setB(row[4]);
            branch.setRateA(row[5]);
            branch.setRateB(row[6]);
            branch.setRateC(row[7]);
            branch.setRatio(row[8]);
            branch.setPhaseShiftAngle(row[9]);
            branch.setStatus((int) row[10]);
            branch.setAngMin(row[11]);
            branch.setAngMax(row[12]);
            model.addBranch(branch);
        }
        return model;
    }

    private static double parseScalar(String content, String key) {
        int i = content.indexOf(key);
        if (i < 0) {
            throw new IllegalArgumentException("Missing " + key);
        }
        int eq = content.indexOf('=', i);
        int end = content.indexOf(';', eq);
        return Double.parseDouble(content.substring(eq + 1, end).trim());
    }

    private static List<double[]> parseMatrix(String content, String key) {
        int i = content.indexOf(key);
        if (i < 0) {
            throw new IllegalArgumentException("Missing " + key);
        }
        int open = content.indexOf('[', i);
        int close = content.indexOf(']', open);
        String block = content.substring(open + 1, close);
        List<double[]> rows = new ArrayList<>();
        for (String rawLine : block.split("\n")) {
            String line = rawLine;
            int pct = line.indexOf('%');
            if (pct >= 0) {
                line = line.substring(0, pct);
            }
            line = line.replace(";", " ").trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] tokens = line.split("\\s+");
            double[] row = new double[tokens.length];
            for (int c = 0; c < tokens.length; c++) {
                row[c] = Double.parseDouble(tokens[c]);
            }
            rows.add(row);
        }
        return rows;
    }

    public static void writeMat(Path mFile, Path matFile, String caseName) throws IOException {
        MatpowerModel model = parse(mFile, caseName);
        System.out.printf("Parsed %s: %d buses, %d generators, %d branches (baseMVA=%.1f)%n",
            caseName, model.getBuses().size(), model.getGenerators().size(), model.getBranches().size(), model.getBaseMva());
        MatpowerWriter.write(model, matFile, false);
        System.out.println("Wrote " + matFile);
    }
}
