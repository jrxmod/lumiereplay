package com.jrxmod.lumiereplay;

/**
 * Controls who can interact with a projector block.
 * ALL   — any player (default, backward compatible with pre-v0.3.0 blocks)
 * OWNER — only the player who placed the block
 * OPS   — owner or any operator (permission level >= 2)
 */
public enum AccessMode {
    ALL("all"),
    OWNER("owner"),
    OPS("ops");

    public final String id;

    AccessMode(String id) { this.id = id; }

    public static AccessMode fromId(String id) {
        for (AccessMode m : values()) if (m.id.equals(id)) return m;
        return ALL;
    }
}
