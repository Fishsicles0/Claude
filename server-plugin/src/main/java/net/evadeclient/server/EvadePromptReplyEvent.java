/*
 * Evade Server API - Copyright (c) 2026 Evade Client contributors
 * SPDX-License-Identifier: MIT (see LICENSE)
 */
package net.evadeclient.server;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Fired (on the main thread) when a player answers a prompt sent with {@link EvadeApi#promptInput} or
 * {@link EvadeApi#promptTransfer}: what they typed, that they cancelled, or whether they accepted the transfer.
 * The callback given to those methods runs first. Treat {@link #getValue()} like chat input: the player typed it.
 */
public class EvadePromptReplyEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String type, id, value;
    private final boolean accepted;

    public EvadePromptReplyEvent(Player player, String type, String id, String value, boolean accepted) {
        super(player);
        this.type = type;
        this.id = id;
        this.value = value;
        this.accepted = accepted;
    }

    /** {@code "prompt.input"} or {@code "prompt.transfer"}. */
    public String getType() { return type; }

    /** The id you gave the prompt. */
    public String getId() { return id; }

    /** For an input prompt: what the player typed, or null when they cancelled. */
    public String getValue() { return value; }

    /** True when the player sent an answer (input) or clicked Join (transfer). */
    public boolean isAccepted() { return accepted; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
