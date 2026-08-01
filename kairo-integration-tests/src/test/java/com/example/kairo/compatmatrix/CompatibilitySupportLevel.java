package com.example.kairo.compatmatrix;

/**
 * Declared support level for one V1.7 compatibility-matrix scenario (&sect;10.1 /
 * &sect;10.2). This is the M3-A real-process matrix contract: it is deliberately
 * separate from the V1.5 in-process {@code SupportLevel} in {@code kairo-api},
 * which this package never reads or modifies.
 *
 * <p>{@link #FORMAL} rows (C01&ndash;C08, C10) carry a candidate support promise:
 * the M3 completion gate (&sect;10.5) requires each to be {@code PASSED}. Any
 * formal row that is {@code FAILED}/{@code SKIPPED}/{@code NOT_RUN} fails the
 * aggregate fail-closed. {@link #EXPERIMENTAL} (C09) does not block the matrix:
 * without a real macOS runner it is recorded as {@code EXPERIMENTAL} or
 * {@code NOT_RUN} (&sect;10.4.2), and macOS never enters formal support
 * (&sect;10.1).
 */
public enum CompatibilitySupportLevel {

    /** Committed candidate row; must reach PASSED for the M3 gate. C01&ndash;C08, C10. */
    FORMAL,
    /** Not part of the formal promise; non-blocking. C09 unless a real macOS CI exists. */
    EXPERIMENTAL;

    /** Whether this level is a blocking commitment for the aggregate. */
    public boolean isFormal() {
        return this == FORMAL;
    }
}
