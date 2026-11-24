/*
 * Evade Server API - Copyright (c) 2026 Evade Client contributors
 * SPDX-License-Identifier: MIT (see LICENSE)
 */
package net.evadeclient.server;

import java.util.Set;

/**
 * What an Evade Client told the server in its {@code evade:hello} message.
 *
 * @param version  the client's version, e.g. {@code "2.0.1"}
 * @param api      the Evade Server API level the client speaks (1 for now)
 * @param features the message types / features the client supports, e.g. {@code "waypoint"}, {@code "cooldown"}
 * @param since    when the hello arrived ({@link System#currentTimeMillis()})
 */
public record EvadeClientInfo(String version, int api, Set<String> features, long since) {
    public EvadeClientInfo {
        features = Set.copyOf(features);
    }

    /** Whether the client listed {@code feature} in its hello (case-insensitive). */
    public boolean hasFeature(String feature) {
        if (feature == null) return false;
        for (String f : features) if (f.equalsIgnoreCase(feature)) return true;
        return false;
    }
}
