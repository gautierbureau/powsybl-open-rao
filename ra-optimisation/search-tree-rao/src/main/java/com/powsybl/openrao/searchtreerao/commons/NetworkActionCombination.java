/*
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.powsybl.openrao.searchtreerao.commons;

import com.google.common.hash.Hashing;
import com.powsybl.openrao.data.crac.api.Identifiable;
import com.powsybl.openrao.data.crac.api.networkaction.NetworkAction;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Baptiste Seguinot {@literal <baptiste.seguinot at rte-france.com>}
 */
public class NetworkActionCombination {

    private final Set<NetworkAction> networkActionSet;
    private final boolean detectedDuringRao;
    // used repeatedly when sorting combinations: computed lazily then cached (benign race: recomputing yields the same value)
    private String concatenatedId;
    private Integer concatenatedIdCrc32;

    public NetworkActionCombination(Set<NetworkAction> networkActionSet, boolean detectedDuringRao) {
        this.networkActionSet = networkActionSet;
        this.detectedDuringRao = detectedDuringRao;
    }

    public NetworkActionCombination(Set<NetworkAction> networkActionSet) {
        this(networkActionSet, false);
    }

    public NetworkActionCombination(NetworkAction networkAction) {
        this(Collections.singleton(networkAction), false);
    }

    public Set<NetworkAction> getNetworkActionSet() {
        return networkActionSet;
    }

    public Set<String> getOperators() {
        return networkActionSet.stream()
            .map(NetworkAction::getOperator)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    public String getConcatenatedId() {
        String id = concatenatedId;
        if (id == null) {
            id = networkActionSet.stream()
                .map(Identifiable::getId)
                .collect(Collectors.joining(" + "));
            concatenatedId = id;
        }
        return id;
    }

    public int getConcatenatedIdCrc32() {
        Integer crc32 = concatenatedIdCrc32;
        if (crc32 == null) {
            crc32 = Hashing.crc32().hashString(getConcatenatedId(), StandardCharsets.UTF_8).asInt();
            concatenatedIdCrc32 = crc32;
        }
        return crc32;
    }

    public boolean isDetectedDuringRao() {
        return detectedDuringRao;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        NetworkActionCombination oNetworkActionCombination = (NetworkActionCombination) o;
        return this.detectedDuringRao == oNetworkActionCombination.isDetectedDuringRao()
                && this.networkActionSet.equals(oNetworkActionCombination.networkActionSet);
    }

    @Override
    public int hashCode() {
        return Objects.hash(networkActionSet) + 37 * Objects.hash(detectedDuringRao);
    }
}
