package transitsim.geometry;

import java.util.ArrayList;
import java.util.List;

/**
 * A rail's shape seen from above as pieces one after another (design 9.3, 0.95.0): lines, arcs and transition curves
 * (clothoids), whose curvature changes evenly along them, so that a train goes from a straight into a curve without the
 * curvature jumping.
 * <p>
 * Headings are angles in the x-z plane ({@code atan2(dz, dx)}); curvature is the change of heading per block along the
 * rail, positive turning from x towards z. A clothoid has no formula in elementary functions: its points are worked out
 * by Gauss-Legendre quadrature from points kept every {@link #ANCHOR_STEP} blocks.
 * <p>
 * {@link Builder#turn} replaces one turn of a rail (the part between a straight line through its start and one through
 * its end) by a line, a transition, an arc, a transition and a line, keeping both ends and their headings exactly.
 */
public final class TransitionPath {

	/**
	 * The longest transition a node may ask for, in blocks
	 */
	public static final double MAX_LENGTH = 200;
	/**
	 * The smallest radius of a rail, as in the game
	 */
	public static final double MIN_RADIUS = 2;
	/**
	 * Turns sharper than this keep their arc: the straight lines through their ends meet too far away, or behind them
	 */
	public static final double MAX_TURN = Math.toRadians(175);
	/**
	 * Transitions made shorter than this to fit are left out
	 */
	public static final double MIN_TRANSITION = 0.5;

	private static final double ANCHOR_STEP = 2;
	private static final double MIN_TURN = 1E-6;
	private static final double EPSILON = 1E-9;
	private static final double END_TOLERANCE = 1E-4;
	private static final int ITERATIONS = 80;
	private static final double[] GAUSS_X = {-0.9061798459386640, -0.5384693101056831, 0, 0.5384693101056831, 0.9061798459386640};
	private static final double[] GAUSS_W = {0.2369268850561891, 0.4786286704993665, 0.5688888888888889, 0.4786286704993665, 0.2369268850561891};

	private final Piece[] pieces;
	private final double length;

	private TransitionPath(List<Piece> pieces) {
		this.pieces = pieces.toArray(new Piece[0]);
		double total = 0;
		for (final Piece piece : this.pieces) {
			piece.offset = total;
			total += piece.length;
		}
		length = total;
	}

	public double getLength() {
		return length;
	}

	public int getPieceCount() {
		return pieces.length;
	}

	/**
	 * @return {x, z, heading} at a distance along the path, clamped to it
	 */
	public double[] getPoint(double distance) {
		final Piece piece = pieceAt(distance);
		return piece.point(clamp(distance - piece.offset, 0, piece.length));
	}

	/**
	 * @return the curvature at a distance along the path, positive turning from x towards z
	 */
	public double getCurvature(double distance) {
		final Piece piece = pieceAt(distance);
		return piece.curvature(clamp(distance - piece.offset, 0, piece.length));
	}

	/**
	 * @return the kind of each piece, for tests and the debug view: 'L' line, 'A' arc, 'T' transition
	 */
	public String describe() {
		final StringBuilder stringBuilder = new StringBuilder();
		for (final Piece piece : pieces) {
			stringBuilder.append(piece.curvatureStart == 0 && piece.curvatureEnd == 0 ? 'L' : piece.curvatureStart == piece.curvatureEnd ? 'A' : 'T');
		}
		return stringBuilder.toString();
	}

	private Piece pieceAt(double distance) {
		int low = 0;
		int high = pieces.length - 1;
		while (low < high) {
			final int middle = (low + high + 1) / 2;
			if (pieces[middle].offset <= distance) {
				low = middle;
			} else {
				high = middle - 1;
			}
		}
		return pieces[low];
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private static double normalizeAngle(double angle) {
		double result = angle % (2 * Math.PI);
		if (result > Math.PI) {
			result -= 2 * Math.PI;
		} else if (result <= -Math.PI) {
			result += 2 * Math.PI;
		}
		return result;
	}

	/**
	 * Puts a path together piece by piece from a start point and heading.
	 */
	public static final class Builder {

		private final List<Piece> pieces = new ArrayList<>();
		private double x;
		private double z;
		private double heading;

		public Builder(double x, double z, double heading) {
			this.x = x;
			this.z = z;
			this.heading = heading;
		}

		public double getX() {
			return x;
		}

		public double getZ() {
			return z;
		}

		public double getHeading() {
			return heading;
		}

		public Builder line(double length) {
			return add(0, 0, length);
		}

		public Builder arc(double curvature, double length) {
			return add(curvature, curvature, length);
		}

		public Builder transition(double curvatureStart, double curvatureEnd, double length) {
			return add(curvatureStart, curvatureEnd, length);
		}

		/**
		 * Goes from where the builder is to an end point and heading in one turn: a line, a transition of
		 * {@code lengthIn}, an arc, a transition of {@code lengthOut} and a line, the arc as large as fits. Transitions
		 * that don't fit are made shorter by the same factor; if they would be shorter than {@link #MIN_TRANSITION} or the
		 * ends aren't one turn apart, nothing is added.
		 *
		 * @return whether the turn was added
		 */
		public boolean turn(double endX, double endZ, double endHeading, double lengthIn, double lengthOut) {
			final List<Piece> turn = solveTurn(x, z, heading, endX, endZ, endHeading, clamp(lengthIn, 0, MAX_LENGTH), clamp(lengthOut, 0, MAX_LENGTH));
			if (turn == null) {
				return false;
			}
			for (final Piece piece : turn) {
				add(piece.curvatureStart, piece.curvatureEnd, piece.length);
			}
			// Land exactly on the end asked for; the pieces get there within a ten thousandth of a block
			x = endX;
			z = endZ;
			heading = endHeading;
			return true;
		}

		public TransitionPath build() {
			return new TransitionPath(pieces);
		}

		private Builder add(double curvatureStart, double curvatureEnd, double length) {
			if (length > EPSILON) {
				final Piece piece = new Piece(x, z, heading, curvatureStart, curvatureEnd, length);
				pieces.add(piece);
				final double[] end = piece.point(length);
				x = end[0];
				z = end[1];
				heading = end[2];
			}
			return this;
		}
	}

	/**
	 * @return the pieces of one turn, or null if the ends aren't one turn apart or no transition fits
	 */
	private static List<Piece> solveTurn(double startX, double startZ, double startHeading, double endX, double endZ, double endHeading, double lengthIn, double lengthOut) {
		final double turn = normalizeAngle(endHeading - startHeading);
		final double absTurn = Math.abs(turn);
		if (absTurn < MIN_TURN || absTurn > MAX_TURN || lengthIn + lengthOut < MIN_TRANSITION) {
			return null;
		}

		// Where the straight lines through the two ends meet, and how far each end is from there
		final double directionStartX = Math.cos(startHeading);
		final double directionStartZ = Math.sin(startHeading);
		final double directionEndX = Math.cos(endHeading);
		final double directionEndZ = Math.sin(endHeading);
		final double differenceX = endX - startX;
		final double differenceZ = endZ - startZ;
		final double determinant = directionStartX * directionEndZ - directionStartZ * directionEndX;
		if (Math.abs(determinant) < EPSILON) {
			return null;
		}
		final double rawTangentStart = (differenceX * directionEndZ - differenceZ * directionEndX) / determinant;
		final double rawTangentEnd = (directionStartX * differenceZ - directionStartZ * differenceX) / determinant;
		if (rawTangentStart < -END_TOLERANCE || rawTangentEnd < -END_TOLERANCE) {
			return null;
		}
		final double tangentStart = Math.max(0, rawTangentStart);
		final double tangentEnd = Math.max(0, rawTangentEnd);

		// The largest share of the transitions asked for that fits with the smallest radius allowed
		final double scale;
		if (fits(absTurn, lengthIn, lengthOut, tangentStart, tangentEnd)) {
			scale = 1;
		} else {
			double low = 0;
			double high = 1;
			for (int i = 0; i < ITERATIONS; i++) {
				final double middle = (low + high) / 2;
				if (fits(absTurn, lengthIn * middle, lengthOut * middle, tangentStart, tangentEnd)) {
					low = middle;
				} else {
					high = middle;
				}
			}
			scale = low;
		}
		final double transitionIn = lengthIn * scale;
		final double transitionOut = lengthOut * scale;
		if (transitionIn + transitionOut < MIN_TRANSITION) {
			return null;
		}

		// The largest radius with neither straight line negative
		final double minRadius = minRadius(absTurn, transitionIn, transitionOut);
		final double radius = Math.min(solveRadius(absTurn, transitionIn, transitionOut, minRadius, tangentStart, true), solveRadius(absTurn, transitionIn, transitionOut, minRadius, tangentEnd, false));
		final double[] tangents = tangents(absTurn, radius, transitionIn, transitionOut);
		final double sign = Math.signum(turn);
		final double curvature = sign / radius;
		final double arcLength = radius * absTurn - (transitionIn + transitionOut) / 2;

		final List<Piece> turnPieces = new ArrayList<>();
		final Builder builder = new Builder(startX, startZ, startHeading);
		builder.line(Math.max(0, tangentStart - tangents[0]));
		builder.transition(0, curvature, transitionIn);
		builder.arc(curvature, Math.max(0, arcLength));
		builder.transition(curvature, 0, transitionOut);
		builder.line(Math.max(0, tangentEnd - tangents[1]));
		if (Math.hypot(builder.x - endX, builder.z - endZ) > END_TOLERANCE || Math.abs(normalizeAngle(builder.heading - endHeading)) > END_TOLERANCE) {
			return null;
		}
		turnPieces.addAll(builder.pieces);
		return turnPieces;
	}

	private static double minRadius(double turn, double transitionIn, double transitionOut) {
		// The transitions turn by half their length over the radius each; the arc can't be shorter than nothing
		return Math.max(MIN_RADIUS, (transitionIn + transitionOut) / (2 * turn));
	}

	private static boolean fits(double turn, double transitionIn, double transitionOut, double tangentStart, double tangentEnd) {
		final double[] tangents = tangents(turn, minRadius(turn, transitionIn, transitionOut), transitionIn, transitionOut);
		return tangents[0] <= tangentStart && tangents[1] <= tangentEnd;
	}

	/**
	 * Tangent lengths grow with the radius, so the radius giving one of them is found by halving.
	 */
	private static double solveRadius(double turn, double transitionIn, double transitionOut, double minRadius, double tangent, boolean start) {
		double low = minRadius;
		double high = Math.max(minRadius * 2, 1);
		while (tangents(turn, high, transitionIn, transitionOut)[start ? 0 : 1] < tangent && high < 1E7) {
			high *= 2;
		}
		for (int i = 0; i < ITERATIONS; i++) {
			final double middle = (low + high) / 2;
			if (tangents(turn, middle, transitionIn, transitionOut)[start ? 0 : 1] < tangent) {
				low = middle;
			} else {
				high = middle;
			}
		}
		return low;
	}

	/**
	 * A left turn from the origin heading along x: transition, arc, transition.
	 *
	 * @return how far before and after the meeting point of the two straight lines the curve starts and ends
	 */
	private static double[] tangents(double turn, double radius, double transitionIn, double transitionOut) {
		final Builder builder = new Builder(0, 0, 0);
		builder.transition(0, 1 / radius, transitionIn);
		builder.arc(1 / radius, Math.max(0, radius * turn - (transitionIn + transitionOut) / 2));
		builder.transition(1 / radius, 0, transitionOut);
		final double along = -builder.z / Math.sin(turn);
		return new double[]{builder.x + along * Math.cos(turn), -along};
	}

	private static final class Piece {

		private final double startX;
		private final double startZ;
		private final double startHeading;
		private final double curvatureStart;
		private final double curvatureEnd;
		private final double length;
		private final double[] anchorX;
		private final double[] anchorZ;
		private double offset;

		private Piece(double startX, double startZ, double startHeading, double curvatureStart, double curvatureEnd, double length) {
			this.startX = startX;
			this.startZ = startZ;
			this.startHeading = startHeading;
			this.curvatureStart = curvatureStart;
			this.curvatureEnd = curvatureEnd;
			this.length = length;
			if (curvatureStart == curvatureEnd) {
				anchorX = anchorZ = null;
			} else {
				final int count = (int) Math.ceil(length / ANCHOR_STEP) + 1;
				anchorX = new double[count];
				anchorZ = new double[count];
				anchorX[0] = startX;
				anchorZ[0] = startZ;
				for (int i = 1; i < count; i++) {
					final double from = (i - 1) * ANCHOR_STEP;
					final double to = Math.min(i * ANCHOR_STEP, length);
					anchorX[i] = anchorX[i - 1] + integrate(from, to, true);
					anchorZ[i] = anchorZ[i - 1] + integrate(from, to, false);
				}
			}
		}

		private double heading(double distance) {
			return startHeading + curvatureStart * distance + (curvatureEnd - curvatureStart) * distance * distance / (2 * length);
		}

		private double curvature(double distance) {
			return curvatureStart + (curvatureEnd - curvatureStart) * distance / length;
		}

		private double[] point(double distance) {
			final double heading = heading(distance);
			if (curvatureStart == curvatureEnd) {
				if (curvatureStart == 0) {
					return new double[]{startX + distance * Math.cos(startHeading), startZ + distance * Math.sin(startHeading), heading};
				} else {
					return new double[]{startX + (Math.sin(heading) - Math.sin(startHeading)) / curvatureStart, startZ - (Math.cos(heading) - Math.cos(startHeading)) / curvatureStart, heading};
				}
			}
			final int anchor = Math.min((int) (distance / ANCHOR_STEP), anchorX.length - 1);
			final double from = anchor * ANCHOR_STEP;
			return new double[]{anchorX[anchor] + integrate(from, distance, true), anchorZ[anchor] + integrate(from, distance, false), heading};
		}

		private double integrate(double from, double to, boolean x) {
			final double half = (to - from) / 2;
			final double middle = (to + from) / 2;
			double sum = 0;
			for (int i = 0; i < GAUSS_X.length; i++) {
				final double heading = heading(middle + half * GAUSS_X[i]);
				sum += GAUSS_W[i] * (x ? Math.cos(heading) : Math.sin(heading));
			}
			return sum * half;
		}
	}
}
