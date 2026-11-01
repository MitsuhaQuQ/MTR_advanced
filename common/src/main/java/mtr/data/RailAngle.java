package mtr.data;

public final class RailAngle {

	public final float angleDegrees;
	public final double angleRadians;
	public final double sin;
	public final double cos;
	public final double tan;
	public final double halfTan;
	private final int units;

	private static final int DEGREES_IN_CIRCLE = 360;
	private static final int UNITS_PER_DEGREE = 10;
	private static final int UNITS_IN_CIRCLE = DEGREES_IN_CIRCLE * UNITS_PER_DEGREE;
	private static final int QUADRANT_UNITS = UNITS_IN_CIRCLE / 16;
	private static final RailAngle[] ANGLES = new RailAngle[UNITS_IN_CIRCLE];

	static {
		for (int i = 0; i < UNITS_IN_CIRCLE; i++) {
			ANGLES[i] = new RailAngle(i);
		}
	}

	public static final int QUADRANTS = UNITS_IN_CIRCLE / QUADRANT_UNITS;
	private static final float ANGLE_INCREMENT = (float) DEGREES_IN_CIRCLE / QUADRANTS;

	public static final RailAngle E = fromQuadrant(0);
	public static final RailAngle SEE = fromQuadrant(1);
	public static final RailAngle SE = fromQuadrant(2);
	public static final RailAngle SSE = fromQuadrant(3);
	public static final RailAngle S = fromQuadrant(4);
	public static final RailAngle SSW = fromQuadrant(5);
	public static final RailAngle SW = fromQuadrant(6);
	public static final RailAngle SWW = fromQuadrant(7);
	public static final RailAngle W = fromQuadrant(8);
	public static final RailAngle NWW = fromQuadrant(9);
	public static final RailAngle NW = fromQuadrant(10);
	public static final RailAngle NNW = fromQuadrant(11);
	public static final RailAngle N = fromQuadrant(12);
	public static final RailAngle NNE = fromQuadrant(13);
	public static final RailAngle NE = fromQuadrant(14);
	public static final RailAngle NEE = fromQuadrant(15);

	// Instances are cached so that angles can always be compared with ==
	private RailAngle(int units) {
		this.units = units;
		angleDegrees = normalizeAngle(units / (float) UNITS_PER_DEGREE);
		angleRadians = Math.toRadians(normalizeAngle(units / (double) UNITS_PER_DEGREE));
		sin = Math.sin(angleRadians);
		cos = Math.cos(angleRadians);
		tan = Math.tan(angleRadians);
		halfTan = Math.tan(angleRadians / 2);
	}

	public RailAngle getOpposite() {
		return fromUnits(units + UNITS_IN_CIRCLE / 2);
	}

	public RailAngle add(RailAngle railAngle) {
		return fromUnits(units + railAngle.units);
	}

	public RailAngle sub(RailAngle railAngle) {
		return fromUnits(units - railAngle.units);
	}

	/**
	 * Gets the facing of a rail after its node turns to this angle. Rails are compared with a rail of the same node
	 * and not with the new angle, because at a quarter turn both sides are equally close to the new angle.
	 */
	public RailAngle getRotatedFacing(RailAngle referenceFacing, RailAngle railFacing) {
		final RailAngle referenceNewFacing = cos * referenceFacing.cos + sin * referenceFacing.sin >= 0 ? this : getOpposite();
		final boolean sameSide = railFacing.cos * referenceFacing.cos + railFacing.sin * referenceFacing.sin >= 0;
		return sameSide ? referenceNewFacing : referenceNewFacing.getOpposite();
	}

	public boolean isParallel(RailAngle railAngle) {
		return this == railAngle || this == railAngle.getOpposite();
	}

	public boolean similarFacing(float newAngleDegrees) {
		return similarFacing(angleDegrees, newAngleDegrees);
	}

	@Override
	public String toString() {
		return String.format("%s", angleDegrees);
	}

	public static boolean similarFacing(float angleDegrees1, float angleDegrees2) {
		return Math.abs(normalizeAngle(angleDegrees1 - angleDegrees2)) < DEGREES_IN_CIRCLE / 4F;
	}

	public static int getQuadrant(float angleDegrees, boolean include225) {
		final int factor = include225 ? 1 : 2;
		return (Math.round((normalizeAngle(angleDegrees) + DEGREES_IN_CIRCLE) / ANGLE_INCREMENT / factor) % (QUADRANTS / factor));
	}

	public static RailAngle fromAngle(double angleDegrees) {
		return fromUnits(Math.round(angleDegrees * UNITS_PER_DEGREE));
	}

	public static RailAngle closestQuadrant(float angleDegrees) {
		return fromQuadrant(getQuadrant(angleDegrees, true));
	}

	private static RailAngle fromQuadrant(int quadrant) {
		return ANGLES[quadrant * QUADRANT_UNITS];
	}

	private static RailAngle fromUnits(long units) {
		return ANGLES[(int) Math.floorMod(units, (long) UNITS_IN_CIRCLE)];
	}

	private static float normalizeAngle(float angleDegrees) {
		return (float) normalizeAngle((double) angleDegrees);
	}

	private static double normalizeAngle(double angleDegrees) {
		int additional = 0;
		while (angleDegrees + additional < -DEGREES_IN_CIRCLE / 2F) {
			additional += DEGREES_IN_CIRCLE;
		}
		while (angleDegrees + additional >= DEGREES_IN_CIRCLE / 2F) {
			additional -= DEGREES_IN_CIRCLE;
		}
		return angleDegrees + additional;
	}
}
