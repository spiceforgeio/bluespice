package dev.bluespice.core.circuit;

import java.util.Objects;

/**
 * Immutable circuit node identity.
 */
public final class Node {
    private final String label;
    private final long internalId;
    private final boolean ground;

    Node(String label, long internalId, boolean ground) {
        this.label = Objects.requireNonNull(label, "label");
        this.internalId = internalId;
        this.ground = ground;
    }

    /**
     * User-visible node label.
     */
    public String label() {
        return label;
    }

    /**
     * Stable per-circuit identity used for equality.
     */
    public long internalId() {
        return internalId;
    }

    /**
     * Returns whether this node is circuit ground.
     */
    public boolean isGround() {
        return ground;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Node node && internalId == node.internalId;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(internalId);
    }

    @Override
    public String toString() {
        return ground ? "Node[0:gnd]" : "Node[" + internalId + ":" + label + "]";
    }
}
