package transitsim.geometry;

/**
 * The height of a rail along its length when its two ends have grades (design 9.2): a vertical curve from the start's
 * grade to a constant grade, that grade, and a vertical curve to the end's grade. Vertical curves are parabolas, so the
 * grade changes evenly along them, by one over the radius each block. When the rail is too short for the radii asked,
 * both are made smaller by the same factor until it fits, and {@link #fitsRadius()} is false.
 * <p>
 * Distances are along the rail as its horizontal length counts them, and grades are rise over that distance (0.02 is
 * 20 per thousand), so the length of a rail and everything worked out from it stay the same whatever its profile.
 */
public final class VerticalProfile {

	/**
	 * The radius of a vertical curve when nothing else is set, in blocks.
	 */
	public static final double DEFAULT_RADIUS = 500;
	public static final double MIN_RADIUS = 10;
	public static final double MAX_RADIUS = 20000;
	/**
	 * Steeper ends than one block up for one along are taken as typing mistakes.
	 */
	public static final double MAX_GRADE = 1;

	private static final int ITERATIONS = 100;
	private static final double EPSILON = 1E-9;

	private final double length;
	private final double yStart;
	private final double yEnd;
	private final double gradeStart;
	private final double gradeEnd;
	private final double radiusStart;
	private final double radiusEnd;
	private final double grade;
	private final double curveStart;
	private final double curveEnd;
	private final double radiusScale;

	private VerticalProfile(double length, double yStart, double yEnd, double gradeStart, double gradeEnd, double radiusStart, double radiusEnd, double grade, double radiusScale) {
		this.length = length;
		this.yStart = yStart;
		this.yEnd = yEnd;
		this.gradeStart = gradeStart;
		this.gradeEnd = gradeEnd;
		this.radiusStart = radiusStart;
		this.radiusEnd = radiusEnd;
		this.grade = grade;
		this.radiusScale = radiusScale;
		curveStart = radiusStart * Math.abs(grade - gradeStart);
		curveEnd = radiusEnd * Math.abs(grade - gradeEnd);
	}

	/**
	 * @param length       the horizontal length of the rail
	 * @param gradeStart   the grade at the start, going along the rail
	 * @param gradeEnd     the grade at the end, going along the rail
	 * @param radiusStart  the radius of the vertical curve at the start
	 * @param radiusEnd    the radius of the vertical curve at the end
	 */
	public static VerticalProfile of(double length, double yStart, double yEnd, double gradeStart, double gradeEnd, double radiusStart, double radiusEnd) {
		final double radius1 = clampRadius(radiusStart);
		final double radius2 = clampRadius(radiusEnd);
		final double grade1 = clampGrade(gradeStart);
		final double grade2 = clampGrade(gradeEnd);
		if (!(length > EPSILON)) {
			return new VerticalProfile(Math.max(0, length), yStart, yEnd, grade1, grade2, 0, 0, 0, 0);
		}
		final double rise = yEnd - yStart;
		final double grade = solve(length, rise, grade1, grade2, radius1, radius2);
		if (!Double.isNaN(grade)) {
			return new VerticalProfile(length, yStart, yEnd, grade1, grade2, radius1, radius2, grade, 1);
		}
		// Smaller radii by the same factor until the curves fit; with no curves at all a straight grade always does
		double fitting = 0;
		double fittingGrade = rise / length;
		double tooLarge = 1;
		for (int i = 0; i < ITERATIONS / 2; i++) {
			final double scale = (fitting + tooLarge) / 2;
			final double scaledGrade = solve(length, rise, grade1, grade2, radius1 * scale, radius2 * scale);
			if (Double.isNaN(scaledGrade)) {
				tooLarge = scale;
			} else {
				fitting = scale;
				fittingGrade = scaledGrade;
			}
		}
		return new VerticalProfile(length, yStart, yEnd, grade1, grade2, radius1 * fitting, radius2 * fitting, fittingGrade, fitting);
	}

	public double getY(double distance) {
		if (length <= EPSILON) {
			return distance < length / 2 ? yStart : yEnd;
		}
		final double s = Math.max(0, Math.min(length, distance));
		if (s < curveStart) {
			return yStart + gradeStart * s + Math.signum(grade - gradeStart) * s * s / (2 * radiusStart);
		} else if (s > length - curveEnd) {
			final double u = length - s;
			return yEnd - gradeEnd * u - Math.signum(grade - gradeEnd) * u * u / (2 * radiusEnd);
		} else {
			return yStart + curveStart * (gradeStart + grade) / 2 + grade * (s - curveStart);
		}
	}

	public double getGrade(double distance) {
		final double s = Math.max(0, Math.min(length, distance));
		if (s < curveStart) {
			return gradeStart + Math.signum(grade - gradeStart) * s / radiusStart;
		} else if (s > length - curveEnd) {
			return gradeEnd + Math.signum(grade - gradeEnd) * (length - s) / radiusEnd;
		} else {
			return grade;
		}
	}

	/**
	 * @return the constant grade between the two vertical curves
	 */
	public double getMiddleGrade() {
		return grade;
	}

	/**
	 * @return the length of the vertical curve at the start
	 */
	public double getCurveStart() {
		return curveStart;
	}

	public double getCurveEnd() {
		return curveEnd;
	}

	/**
	 * @return the radii used over the radii asked, 1 when they fit
	 */
	public double getRadiusScale() {
		return radiusScale;
	}

	public boolean fitsRadius() {
		return radiusScale >= 1 - EPSILON;
	}

	public static double clampRadius(double radius) {
		return Double.isFinite(radius) ? Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius)) : DEFAULT_RADIUS;
	}

	public static double clampGrade(double grade) {
		return Double.isFinite(grade) ? Math.max(-MAX_GRADE, Math.min(MAX_GRADE, grade)) : 0;
	}

	/**
	 * The grade of a node when it is worked out from its rails rather than set: the mean of the mean grades of the rails
	 * on each side of it, or of the one side that has rails. Rails of the old shape are level at their ends, so a node
	 * with any of those is level too.
	 *
	 * @param gradesAway   the mean grade of each rail, rise over length going away from the node
	 * @param forwardSide  whether each rail leaves the node the way the node faces
	 * @param anyLevelEnd  whether a rail of the node is level at its end whatever the node's grade
	 * @return the grade going the way the node faces
	 */
	public static double autoNodeGrade(double[] gradesAway, boolean[] forwardSide, boolean anyLevelEnd) {
		if (anyLevelEnd) {
			return 0;
		}
		double forwardSum = 0;
		double backwardSum = 0;
		int forwardCount = 0;
		int backwardCount = 0;
		for (int i = 0; i < gradesAway.length; i++) {
			if (forwardSide[i]) {
				forwardSum += gradesAway[i];
				forwardCount++;
			} else {
				// Going away on the back side is going against the node's facing
				backwardSum -= gradesAway[i];
				backwardCount++;
			}
		}
		final double grade;
		if (forwardCount > 0 && backwardCount > 0) {
			grade = (forwardSum / forwardCount + backwardSum / backwardCount) / 2;
		} else if (forwardCount > 0) {
			grade = forwardSum / forwardCount;
		} else if (backwardCount > 0) {
			grade = backwardSum / backwardCount;
		} else {
			grade = 0;
		}
		return clampGrade(grade);
	}

	/**
	 * The constant grade that makes the rail rise by {@code rise} over {@code length}, or NaN if the two vertical curves
	 * can't fit. The height reached is {@code G L - (R1 s(G - g1) + R2 s(G - g2)) / 2} with {@code s(x) = x |x|}, which
	 * only grows with G where the curves fit, so halving the range finds it.
	 */
	private static double solve(double length, double rise, double grade1, double grade2, double radius1, double radius2) {
		if (radius1 + radius2 <= EPSILON) {
			return rise / length;
		}
		// The curves are shortest at one of the two end grades, and longer the further the middle grade is from it
		final double middle = curvesLength(grade1, grade1, grade2, radius1, radius2) <= curvesLength(grade2, grade1, grade2, radius1, radius2) ? grade1 : grade2;
		if (curvesLength(middle, grade1, grade2, radius1, radius2) > length + EPSILON) {
			return Double.NaN;
		}
		final double low = furthestFitting(middle, -1, length, grade1, grade2, radius1, radius2);
		final double high = furthestFitting(middle, 1, length, grade1, grade2, radius1, radius2);
		if (rise < height(low, length, grade1, grade2, radius1, radius2) - EPSILON || rise > height(high, length, grade1, grade2, radius1, radius2) + EPSILON) {
			return Double.NaN;
		}
		double min = low;
		double max = high;
		for (int i = 0; i < ITERATIONS; i++) {
			final double grade = (min + max) / 2;
			if (height(grade, length, grade1, grade2, radius1, radius2) < rise) {
				min = grade;
			} else {
				max = grade;
			}
		}
		return (min + max) / 2;
	}

	private static double furthestFitting(double from, int direction, double length, double grade1, double grade2, double radius1, double radius2) {
		double near = from;
		double step = 1;
		double far = from + direction * step;
		while (curvesLength(far, grade1, grade2, radius1, radius2) <= length && step < 1E6) {
			near = far;
			step *= 2;
			far = from + direction * step;
		}
		for (int i = 0; i < ITERATIONS; i++) {
			final double grade = (near + far) / 2;
			if (curvesLength(grade, grade1, grade2, radius1, radius2) <= length) {
				near = grade;
			} else {
				far = grade;
			}
		}
		return near;
	}

	private static double curvesLength(double grade, double grade1, double grade2, double radius1, double radius2) {
		return radius1 * Math.abs(grade - grade1) + radius2 * Math.abs(grade - grade2);
	}

	private static double height(double grade, double length, double grade1, double grade2, double radius1, double radius2) {
		final double difference1 = grade - grade1;
		final double difference2 = grade - grade2;
		return grade * length - (radius1 * difference1 * Math.abs(difference1) + radius2 * difference2 * Math.abs(difference2)) / 2;
	}
}
