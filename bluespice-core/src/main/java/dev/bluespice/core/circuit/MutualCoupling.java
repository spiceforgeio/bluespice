package dev.bluespice.core.circuit;

import static dev.bluespice.core.circuit.CircuitValidation.requireFinite;
import static dev.bluespice.core.circuit.CircuitValidation.requireText;

/**
 * Immutable magnetic coupling relationship between two existing inductor components.
 *
 * <p>The relationship has no conductive terminals. Winding polarity is defined by the referenced
 * inductors' terminal order.
 */
public record MutualCoupling(
        String id,
        String firstInductorId,
        String secondInductorId,
        double couplingCoefficient) {
    public MutualCoupling {
        id = requireText(id, "id");
        firstInductorId = requireText(firstInductorId, "firstInductorId");
        secondInductorId = requireText(secondInductorId, "secondInductorId");
        if (firstInductorId.equals(secondInductorId)) {
            throw new IllegalArgumentException("mutual coupling requires two distinct inductors");
        }
        requireFinite(couplingCoefficient, "couplingCoefficient");
        if (couplingCoefficient <= 0.0 || couplingCoefficient > 1.0) {
            throw new IllegalArgumentException("couplingCoefficient must be > 0.0 and <= 1.0");
        }
    }

    /**
     * Returns whether this relationship references the given component id.
     */
    public boolean references(String componentId) {
        String normalizedId = requireText(componentId, "componentId");
        return firstInductorId.equals(normalizedId) || secondInductorId.equals(normalizedId);
    }

    boolean hasUnorderedPair(String first, String second) {
        return (firstInductorId.equals(first) && secondInductorId.equals(second))
                || (firstInductorId.equals(second) && secondInductorId.equals(first));
    }
}
