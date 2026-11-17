package transitsim.geometry;

/**
 * Superelevation (cant) of the outer rail on curves (0.98.0): how far the outer rail stands above the inner one along a
 * rail, from the curvature of its line and the settings of the nodes at its two ends, and the roll it gives the track
 * and the trains on it.
 * <p>
 * A node gives either a cant in millimetres, which the sharpest part of the rail gets and the rest in proportion to its
 * curvature (so it rises along a transition curve and is nothing on the straight), or a design speed, from which the
 * cant is worked out wherever the rail curves by the usual rule for standard gauge, {@code h = 11.8 v² / R} (h in mm, v in
 * km/h, R in metres, one block a metre). Either way it is held to {@link #MAX_CANT}. When the two ends differ the cant
 * goes over from the one to the other along the rail; an end with no setting takes the other end's.
 */
public final class Superelevation {

	/**
	 * The most cant, in millimetres
	 */
	public static final double MAX_CANT = 180;
	/**
	 * The fastest design speed taken, in km/h
	 */
	public static final double MAX_SPEED = 400;
	/**
	 * The distance between the middles of the two rail heads of standard gauge, in millimetres, over which the cant turns
	 * the track
	 */
	public static final double RAIL_HEADS = 1500;
	/**
	 * {@code h = COEFFICIENT · v² / R}: millimetres from km/h and metres, for standard gauge
	 */
	public static final double COEFFICIENT = 11.8;
	/**
	 * Less curvature than this, per block, counts as straight (a radius of ten thousand blocks)
	 */
	private static final double STRAIGHT = 1E-4;

	private Superelevation() {
	}

	/**
	 * @return the cant a node's setting gives a place with this curvature, in millimetres: from the design speed when it
	 * has one, else its cant in proportion to how sharp the place is against the sharpest of the rail
	 */
	public static double cantOf(double cant, double speed, double curvature, double sharpest) {
		final double sharpness = Math.abs(curvature);
		if (sharpness < STRAIGHT) {
			return 0;
		}
		if (speed > 0) {
			final double v = Math.min(speed, MAX_SPEED);
			return Math.min(MAX_CANT, COEFFICIENT * v * v * sharpness);
		}
		if (cant > 0 && sharpest >= STRAIGHT) {
			return Math.min(MAX_CANT, cant) * Math.min(1, sharpness / sharpest);
		}
		return 0;
	}

	/**
	 * @return the cant in millimetres that rolls the track by this many degrees, held to {@link #MAX_CANT}: a cant may be
	 * typed either way (0.98.0)
	 */
	public static double cantOfDegrees(double degrees) {
		if (!(degrees > 0)) {
			return 0;
		}
		return Math.min(MAX_CANT, RAIL_HEADS * Math.sin(Math.toRadians(Math.min(degrees, 90))));
	}

	/**
	 * @return the roll of the track for a cant in millimetres, in degrees, unsigned
	 */
	public static double degreesOf(double cant) {
		return Math.toDegrees(rollOf(cant));
	}

	/**
	 * @return the roll of the track for a cant in millimetres, in radians, unsigned
	 */
	public static double rollOf(double cant) {
		return Math.asin(Math.max(0, Math.min(MAX_CANT, cant)) / RAIL_HEADS);
	}

	/**
	 * The roll along a rail at points the same distance apart along it, signed as its curvature: positive where the rail
	 * turns from +x towards +z (its heading on the horizontal plane growing), the way the track leans into the curve.
	 *
	 * @param xs          the points' x, in order along the rail
	 * @param zs          the points' z
	 * @param step        the distance between neighbouring points along the rail
	 * @param cantStart   the start node's cant in mm, 0 for none
	 * @param speedStart  the start node's design speed in km/h, 0 for none
	 * @param cantEnd     the end node's cant
	 * @param speedEnd    the end node's design speed
	 * @return the roll at each point, in radians; all 0 when neither end has a setting
	 */
	public static float[] rolls(double[] xs, double[] zs, double step, double cantStart, double speedStart, double cantEnd, double speedEnd) {
		final int count = xs.length;
		final float[] rolls = new float[count];
		if (count < 3 || step <= 0) {
			return rolls;
		}
		final boolean hasStart = cantStart > 0 || speedStart > 0;
		final boolean hasEnd = cantEnd > 0 || speedEnd > 0;
		if (!hasStart && !hasEnd) {
			return rolls;
		}
		final double startCant = hasStart ? cantStart : cantEnd;
		final double startSpeed = hasStart ? speedStart : speedEnd;
		final double endCant = hasEnd ? cantEnd : cantStart;
		final double endSpeed = hasEnd ? speedEnd : speedStart;

		// The curvature at each point from the headings of the pieces either side of it
		final double[] curvatures = new double[count];
		for (int i = 1; i < count - 1; i++) {
			final double before = Math.atan2(zs[i] - zs[i - 1], xs[i] - xs[i - 1]);
			final double after = Math.atan2(zs[i + 1] - zs[i], xs[i + 1] - xs[i]);
			curvatures[i] = wrap(after - before) / step;
		}
		curvatures[0] = curvatures[1];
		curvatures[count - 1] = curvatures[count - 2];
		double sharpest = 0;
		for (final double curvature : curvatures) {
			sharpest = Math.max(sharpest, Math.abs(curvature));
		}

		for (int i = 0; i < count; i++) {
			final double along = (double) i / (count - 1);
			final double cant = cantOf(startCant, startSpeed, curvatures[i], sharpest) * (1 - along) + cantOf(endCant, endSpeed, curvatures[i], sharpest) * along;
			rolls[i] = (float) (Math.signum(curvatures[i]) * rollOf(cant));
		}
		return rolls;
	}

	private static double wrap(double angle) {
		double wrapped = angle;
		while (wrapped > Math.PI) {
			wrapped -= 2 * Math.PI;
		}
		while (wrapped < -Math.PI) {
			wrapped += 2 * Math.PI;
		}
		return wrapped;
	}
}
