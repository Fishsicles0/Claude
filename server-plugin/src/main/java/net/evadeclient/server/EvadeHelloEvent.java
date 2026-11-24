/*
 * Evade Server API - Copyright (c) 2026 Evade Client contributors
 * SPDX-License-Identifier: MIT (see LICENSE)
 */
package net.evadeclient.server;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Fired (on the main thread) when a player's Evade Client says hello, after the configured rules and the welcome
 * notification have been sent. From this point on {@link EvadeApi#isEvade(Player)} is true for the player, so this
 * is the place to send your own waypoints, teams, borders and so on.
 */
public class EvadeHelloEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final EvadeClientInfo info;
    private final boolean first;

    public EvadeHelloEvent(Player player, EvadeClientInfo info, boolean first) {
        super(player);
        this.info = info;
        this.first = first;
    }

    /** What the client reported (version, API level, features). */
    public EvadeClientInfo getInfo() {
        return info;
    }

    /** False when the same player said hello again during one session (e.g. after a proxy server switch). */
    public boolean isFirst() {
        return first;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
