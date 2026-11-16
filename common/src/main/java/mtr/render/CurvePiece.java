package mtr.render;

import mtr.block.CurvedPSD;
import mtr.client.ClientData;
import mtr.data.Rail;
import net.minecraft.core.BlockPos;
import transitsim.geometry.PlatformEdges;

import java.util.Map;

/**
 * A curved screen door block's piece of the platform's edge curve itself (0.92.0), drawn along the curve so the pieces of
 * a run join into one smooth curve: a place along the piece is a distance along the platform's rail between the piece's
 * start and end, and depth goes back from the edge square to the rail there.
 */
public final class CurvePiece implements PlatformEdges.Piece {

	private final Rail rail;
	private final CurvedPSD.Curve curve;
	private final BlockPos pos;

	private CurvePiece(Rail rail, CurvedPSD.Curve curve, BlockPos pos) {
		this.rail = rail;
		this.curve = curve;
		this.pos = pos;
	}

	/**
	 * @return the block's piece of the curve; null when it has none or the client doesn't know the rail
	 */
	public static CurvePiece of(CurvedPSD.Curve curve, BlockPos pos) {
		if (curve == null) {
			return null;
		}
		final Map<BlockPos, Rail> from = ClientData.RAILS.get(curve.railFrom);
		final Rail rail = from == null ? null : from.get(curve.railTo);
		return rail == null ? null : new CurvePiece(rail, curve, pos);
	}

	@Override
	public double[] at(double along, double back) {
		final double distance = Math.max(0, Math.min(rail.getLength(), curve.start + (curve.end - curve.start) * along));
		final double[] point = CurvedPSD.Curve.edgePoint(rail, distance, curve.side, curve.offset + back);
		return new double[]{point[0] - pos.getX(), point[1] - pos.getZ()};
	}

	@Override
	public double[] turn(double alongPart, double towardsTrackPart) {
		final double[] start = at(0.45, 0);
		final double[] end = at(0.55, 0);
		final double[] back = at(0.5, 1);
		final double[] line = at(0.5, 0);
		final double alongLength = Math.hypot(end[0] - start[0], end[1] - start[1]);
		final double alongX = alongLength == 0 ? 0 : (end[0] - start[0]) / alongLength;
		final double alongZ = alongLength == 0 ? 0 : (end[1] - start[1]) / alongLength;
		// Towards the track is against the way depth goes
		final double towardsX = line[0] - back[0];
		final double towardsZ = line[1] - back[1];
		return new double[]{alongX * alongPart + towardsX * towardsTrackPart, alongZ * alongPart + towardsZ * towardsTrackPart};
	}
}
