package dev.bluespice.core.sim;

/**
 * Immutable rectangular complex value.
 *
 * @param real real component
 * @param imaginary imaginary component
 */
public record Complex(double real, double imaginary) {
    public Complex {
        if (!Double.isFinite(real)) {
            throw new IllegalArgumentException("real must be finite");
        }
        if (!Double.isFinite(imaginary)) {
            throw new IllegalArgumentException("imaginary must be finite");
        }
    }

    /**
     * Returns the complex magnitude.
     */
    public double magnitude() {
        return Math.hypot(real, imaginary);
    }

    /**
     * Returns the phase angle in radians.
     */
    public double phaseRadians() {
        return Math.atan2(imaginary, real);
    }

    /**
     * Returns the phase angle in degrees.
     */
    public double phaseDegrees() {
        return Math.toDegrees(phaseRadians());
    }

    /**
     * Returns this value plus {@code other}.
     */
    public Complex plus(Complex other) {
        return new Complex(real + other.real, imaginary + other.imaginary);
    }

    /**
     * Returns this value minus {@code other}.
     */
    public Complex minus(Complex other) {
        return new Complex(real - other.real, imaginary - other.imaginary);
    }

    /**
     * Returns this value divided by a real scalar.
     */
    public Complex dividedBy(double scalar) {
        if (!Double.isFinite(scalar) || scalar == 0.0) {
            throw new IllegalArgumentException("scalar must be finite and non-zero");
        }
        return new Complex(real / scalar, imaginary / scalar);
    }
}
