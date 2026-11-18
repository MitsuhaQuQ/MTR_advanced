package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.Blocks;
import mtr.block.BlockGapFiller;
import mtr.block.BlockPlatformEdge;
import mtr.block.IBlock;
import mtr.mappings.BlockEntityRendererMapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import transitsim.geometry.PlatformEdges;
import transitsim.geometry.SlopedTops;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws a platform edge block (0.88.0) as the platform block it was put against (0.89.0): the platform's part of the block
 * as a prism with that block's top and side textures, and the top's further layers (the edge marking of MTR's platforms)
 * laid along the cut, so the marking runs along the track whatever its direction, up to a block from the edge. Also draws
 * a gap filler (0.93.0, {@link mtr.block.BlockGapFiller}) as the block it was cut from: the same prism, with no marking
 * and no recess, and from nearer, as there may be many of them along a tunnel; its top sloped when it has one (0.94.0).
 */
public class RenderPlatformEdge<T extends BlockPlatformEdge.TileEntityPlatformEdge> extends BlockEntityRendererMapper<T> {

	/**
	 * The textures of each block's model, by the model, so a reload of the resources makes them again
	 */
	private static final Map<BakedModel, Map<BlockState, Textures>> TEXTURES = new WeakHashMap<>();
	/**
	 * Each block's faces as last made (0.101.1), by the block entity
	 */
	private static final Map<BlockPlatformEdge.TileEntityPlatformEdge, Mesh> MESHES = new WeakHashMap<>();
	private static final float MARKING_LIFT = 0.003F;
	/**
	 * How far gap fillers are drawn from, in blocks
	 */
	private static final int GAP_FILLER_VIEW_DISTANCE = 96;

	public RenderPlatformEdge(BlockEntityRenderDispatcher dispatcher) {
		super(dispatcher);
	}

	@Override
	public void render(T entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay) {
		// An error drawing one block (bad data read from a save, say) skips it, never crashing the game (0.100.0)
		RenderGuard.draw("Platform edge", entity.getBlockPos(), matrices, guarded -> renderBlock(entity, tickDelta, guarded, vertexConsumers, light, overlay));
	}

	private void renderBlock(T entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay) {
		final Level world = entity.getLevel();
		if (world == null) {
			return;
		}
		final BlockPos pos = entity.getBlockPos();
		final BlockState state = world.getBlockState(pos);
		final boolean gapFiller = state.getBlock() instanceof BlockGapFiller;
		if (!(state.getBlock() instanceof BlockPlatformEdge) && !gapFiller) {
			return;
		}
		final int angle = IBlock.getStatePropertySafe(state, BlockPlatformEdge.ANGLE);
		final int cut = IBlock.getStatePropertySafe(state, BlockPlatformEdge.CUT);
		final double[] outline = PlatformEdges.outline(angle, cut);
		final int corners = outline.length / 2;
		if (corners < 3) {
			return;
		}
		// As a platform block under screen doors or platform gates looks (0.97.0): their top, and the boarding arrows by a door
		final BlockState plain = entity.getSource() == null ? Blocks.PLATFORM.get().defaultBlockState() : entity.getSource();
		final BlockState source = gapFiller ? plain : mtr.block.BlockPlatform.withDoorsAbove(world, pos, plain);
		final Textures textures = texturesOf(source);
		// Under screen doors or gates (0.100.0, user 2026-11-17: 地面贴图还是方块单位): MTR's platform under doors is a
		// lighter block, a row a block deep along a straight edge. Along a curved or slanting edge the block's own top keeps
		// the platform's look and the lighter one is laid as a band a block deep along the edge itself ({@link #doorBand})
		final boolean underDoors = !gapFiller && mtr.block.BlockPlatform.hasDoorsAbove(source);
		final Textures topTextures = underDoors ? texturesOf(plain) : textures;
		// Below a recess (MTR's indented platforms) the platform's part starts further from the track (0.89.4)
		final int[] recess = gapFiller ? new int[]{0, 0} : BlockPlatformEdge.recessOf(entity.getSource());
		final float recessHeight = recess[0] == 0 ? 0 : (float) recess[1] / PlatformEdges.CELLS;
		// A gap filler's sloped top (0.94.0): the top and the sides' tops follow it
		final int slopedTop = entity instanceof BlockGapFiller.TileEntityGapFiller ? ((BlockGapFiller.TileEntityGapFiller) entity).getTop() : SlopedTops.FLAT;
		// Along the platform's edge curve itself where it is known (0.95.0)
		final PlatformEdgeCurves.Shape curved = gapFiller || slopedTop != SlopedTops.FLAT ? null : PlatformEdgeCurves.shapeOf(entity, pos, (double) recess[0] / PlatformEdges.CELLS);

		// The block's shape is made once and drawn again each frame with the light of the moment (0.101.1): it is made
		// afresh only when something it is made from is another (the block, the block it stands for, the doors above, the
		// textures after a reload, the edge curve)
		final Object[] key = {state, plain, source, slopedTop, textures, topTextures, curved};
		Mesh mesh = MESHES.get(entity);
		if (mesh == null || !java.util.Arrays.equals(mesh.key, key)) {
			mesh = new Mesh(key);
			build(mesh, new Matrix4f(), new Matrix3f(), Mesh.TOP, Mesh.SIDE, angle, cut, outline, gapFiller, source, textures, topTextures, underDoors, recessHeight, recess, slopedTop, curved);
			MESHES.put(entity, mesh);
		}
		mesh.draw(vertexConsumers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS)), matrices.last().pose(), matrices.last().normal(), LevelRenderer.getLightColor(world, pos.above()), LevelRenderer.getLightColor(world, pos));
	}

	/**
	 * Lays the block's faces, in the block's own coordinates
	 */
	private static void build(VertexConsumer consumer, Matrix4f pose, Matrix3f normal, int topLight, int sideLight, int angle, int cut, double[] outline, boolean gapFiller, BlockState source, Textures textures, Textures topTextures, boolean underDoors, float recessHeight, int[] recess, int slopedTop, PlatformEdgeCurves.Shape curved) {
		final double[] lowerOutline = recess[0] == 0 ? outline : PlatformEdges.outline(angle, BlockPlatformEdge.recessedCut(cut, recess));
		if (gapFiller && slopedTop != SlopedTops.FLAT) {
			final Value topAt = (x, z) -> SlopedTops.heightAt(slopedTop, x, z);
			if (textures.top != null) {
				slopedFan(consumer, pose, normal, outline, slopedTop, textures.top, sideLight);
			}
			if (textures.side != null) {
				fan(consumer, pose, normal, outline, 0, textures.side, sideLight, -1, null);
				slopedSides(consumer, pose, normal, outline, topAt, textures.side, sideLight);
			}
			return;
		}

		// Along the edge curve, neighbouring blocks join smoothly; the polygons are then fanned from their middle, as a piece
		// of a curve isn't always convex
		final double[] upper = curved == null ? outline : centred(curved.outline);
		final double[] lower = curved == null ? lowerOutline : centred(curved.lowerOutline);

		// The top and the bottom, as fans of triangles drawn as quads with the last corner twice, the texture as on a whole block
		if (topTextures.top != null) {
			fan(consumer, pose, normal, upper, 1, topTextures.top, topLight, 1, null);
		}
		if (textures.side != null) {
			if (lower.length >= 6) {
				fan(consumer, pose, normal, lower, 0, textures.side, sideLight, -1, null);
			}
			if (recessHeight > 0) {
				// The underside of the top over the recess
				fan(consumer, pose, normal, upper, recessHeight, textures.side, sideLight, -1, null);
			}
		}
		// The top's further layers along the cut: across the cut from 1 at the edge to 0 a block in, along it centred
		final double towardsTrackX = curved == null ? Math.cos(angle * 2 * Math.PI / PlatformEdges.ANGLES) : curved.towardsTrackX;
		final double towardsTrackZ = curved == null ? Math.sin(angle * 2 * Math.PI / PlatformEdges.ANGLES) : curved.towardsTrackZ;
		final double d = (double) (cut - PlatformEdges.CUT_CENTRE) / PlatformEdges.CELLS;
		// How far into the platform from the edge: from the straight cut, or from the curve itself
		final Value depthOf = curved == null ? (x, z) -> d - ((x - 0.5) * towardsTrackX + (z - 0.5) * towardsTrackZ) : curved::depth;
		// Along a curve, only alongside it: at the platform's end the marking stops square to the edge (0.100.0) rather than
		// fanning round the curve's last point
		final double[] marked = curved == null ? clip(outline, (x, z) -> depthOf.at(x, z) - PlatformEdges.MARKING_REACH) : centred(clip(clip(subdivide(curved.outline), (x, z) -> depthOf.at(x, z) - PlatformEdges.MARKING_REACH), curved::pastEnds));
		if (marked.length >= 6 && !gapFiller) {
			// The side the source block faces is its side towards the track (0.89.4): that side of its marking is laid at the
			// cut, wherever it is on the texture; a block with no facing has the texture's bottom (v = 1) there
			final Direction facing = source.hasProperty(HorizontalDirectionalBlock.FACING) ? source.getValue(HorizontalDirectionalBlock.FACING) : null;
			for (final Layer layer : textures.layers) {
				fan(consumer, pose, normal, marked, 1 + MARKING_LIFT, layer.sprite, topLight, 1, (x, z) -> {
					final double inwards = depthOf.at(x, z) / PlatformEdges.MARKING_REACH;
					final double along = ((x - 0.5) * -towardsTrackZ + (z - 0.5) * towardsTrackX) / Math.sqrt(2);
					if (facing == null || layer.uvOf == null) {
						return new float[]{(float) (0.5 + along), (float) (1 - inwards)};
					}
					// The point of the source block as far from its facing side and as far along it
					final double towardsFacing = 0.5 - inwards;
					return layer.uvOf(0.5 + towardsFacing * facing.getStepX() - along * facing.getStepZ(), 0.5 + towardsFacing * facing.getStepZ() + along * facing.getStepX());
				});
			}
		}
		if (underDoors && textures.top != null) {
			final double[] edge = curved == null ? clipToBlock(straightEdge(depthOf)) : curved.edgeInBlock();
			doorBand(consumer, pose, normal, edge, depthOf, textures.top, topLight);
		}
		// Each side of the outline, the cut among them, from the bottom to the top; under a recess the lower outline's
		if (textures.side != null) {
			sides(consumer, pose, normal, curved == null ? outline : curved.outline, recessHeight, 1, textures.side, sideLight);
			if (recessHeight > 0) {
				sides(consumer, pose, normal, curved == null ? lowerOutline : curved.lowerOutline, 0, recessHeight, textures.side, sideLight);
			}
		}
	}

	/**
	 * How deep the lighter band under screen doors is, from the edge into the platform: as MTR's row of platform blocks
	 * under straight doors
	 */
	private static final double DOOR_BAND = 1;
	private static final float DOOR_BAND_LIFT = 0.0045F;

	/**
	 * The lighter band under screen doors (0.100.0): from the block's piece of the edge a block deep into the platform,
	 * square to the edge, so the pieces of neighbouring blocks meet and the band follows the edge, over the platform's
	 * blocks further in too.
	 *
	 * @param edge the block's piece of the edge line, x, z pairs in order
	 */
	private static void doorBand(VertexConsumer consumer, Matrix4f pose, Matrix3f normal, double[] edge, Value depthOf, TextureAtlasSprite sprite, int light) {
		final int points = edge.length / 2;
		if (points < 2) {
			return;
		}
		// Into the platform, square to the edge at each point
		final double[] inX = new double[points], inZ = new double[points];
		double total = 0;
		final double[] along = new double[points];
		for (int i = 0; i < points; i++) {
			final int a = Math.max(0, i - 1), b = Math.min(points - 1, i + 1);
			final double dx = edge[b * 2] - edge[a * 2], dz = edge[b * 2 + 1] - edge[a * 2 + 1];
			final double length = Math.hypot(dx, dz);
			if (length < 1E-9) {
				return;
			}
			double nx = dz / length, nz = -dx / length;
			if (depthOf.at(edge[i * 2] + nx * 0.05, edge[i * 2 + 1] + nz * 0.05) < depthOf.at(edge[i * 2] - nx * 0.05, edge[i * 2 + 1] - nz * 0.05)) {
				nx = -nx;
				nz = -nz;
			}
			inX[i] = nx;
			inZ[i] = nz;
			if (i > 0) {
				total += Math.hypot(edge[i * 2] - edge[i * 2 - 2], edge[i * 2 + 1] - edge[i * 2 - 1]);
			}
			along[i] = total;
		}
		// Over the platform blocks' own top layers (MTR's marking at 16.05 / 16) and the edge marking, under the boarding
		// arrows (16.1 / 16): the band is the whole row under the doors, as MTR's block there has no marking
		final float y = 1 + DOOR_BAND_LIFT;
		for (int i = 0; i + 1 < points; i++) {
			final float x1 = (float) edge[i * 2], z1 = (float) edge[i * 2 + 1];
			final float x2 = (float) edge[i * 2 + 2], z2 = (float) edge[i * 2 + 3];
			final float u1 = (float) along[i], u2 = (float) along[i + 1];
			vertex(consumer, pose, normal, x1, y, z1, sprite, u1, 0, 0, 1, 0, light);
			vertex(consumer, pose, normal, x2, y, z2, sprite, u2, 0, 0, 1, 0, light);
			vertex(consumer, pose, normal, (float) (x2 + inX[i + 1] * DOOR_BAND), y, (float) (z2 + inZ[i + 1] * DOOR_BAND), sprite, u2, 1, 0, 1, 0, light);
			vertex(consumer, pose, normal, (float) (x1 + inX[i] * DOOR_BAND), y, (float) (z1 + inZ[i] * DOOR_BAND), sprite, u1, 1, 0, 1, 0, light);
		}
	}

	/**
	 * @return a straight edge (where the depth is 0) as its two ends, on the block's square or beyond it
	 */
	private static double[] straightEdge(Value depthOf) {
		// The depth goes linearly over the block: its gradient from three points
		final double d00 = depthOf.at(0, 0), d10 = depthOf.at(1, 0), d01 = depthOf.at(0, 1);
		final double gx = d10 - d00, gz = d01 - d00;
		final double length = Math.hypot(gx, gz);
		if (length < 1E-9) {
			return new double[0];
		}
		// The point of the line nearest the block's middle, and the line's way
		final double middle = depthOf.at(0.5, 0.5);
		final double cx = 0.5 - middle * gx / (length * length), cz = 0.5 - middle * gz / (length * length);
		final double wx = -gz / length, wz = gx / length;
		return new double[]{cx - wx * 2, cz - wz * 2, cx + wx * 2, cz + wz * 2};
	}

	/**
	 * @return the part of a line of points inside the block's square (x and z from 0 to 1), the longest run of it, with
	 * the points where it crosses the square's sides
	 */
	static double[] clipToBlock(double[] line) {
		if (line == null || line.length < 4) {
			return new double[0];
		}
		List<Double> best = new ArrayList<>();
		List<Double> run = new ArrayList<>();
		for (int i = 0; i + 3 < line.length; i += 2) {
			final double[] clipped = clipSegment(line[i], line[i + 1], line[i + 2], line[i + 3]);
			if (clipped == null) {
				if (run.size() > best.size()) {
					best = run;
				}
				run = new ArrayList<>();
				continue;
			}
			if (run.isEmpty() || Math.abs(run.get(run.size() - 2) - clipped[0]) > 1E-9 || Math.abs(run.get(run.size() - 1) - clipped[1]) > 1E-9) {
				if (run.size() > best.size()) {
					best = run;
				}
				run = new ArrayList<>();
				run.add(clipped[0]);
				run.add(clipped[1]);
			}
			run.add(clipped[2]);
			run.add(clipped[3]);
		}
		if (run.size() > best.size()) {
			best = run;
		}
		final double[] result = new double[best.size()];
		for (int i = 0; i < result.length; i++) {
			result[i] = best.get(i);
		}
		return result;
	}

	/** Liang-Barsky: the part of a segment inside the unit square, or null */
	private static double[] clipSegment(double x1, double z1, double x2, double z2) {
		double t0 = 0, t1 = 1;
		final double dx = x2 - x1, dz = z2 - z1;
		final double[] p = {-dx, dx, -dz, dz};
		final double[] q = {x1, 1 - x1, z1, 1 - z1};
		for (int i = 0; i < 4; i++) {
			if (Math.abs(p[i]) < 1E-12) {
				if (q[i] < 0) {
					return null;
				}
			} else {
				final double t = q[i] / p[i];
				if (p[i] < 0) {
					t0 = Math.max(t0, t);
				} else {
					t1 = Math.min(t1, t);
				}
			}
		}
		if (t0 > t1 || t1 - t0 < 1E-9) {
			return null;
		}
		return new double[]{x1 + dx * t0, z1 + dz * t0, x1 + dx * t1, z1 + dz * t1};
	}

	/**
	 * @return the polygon with its middle first and its first corner again at the end, so a fan from the first point covers
	 * it when it isn't convex but every corner can be seen from its middle (0.95.0)
	 */
	private static double[] centred(double[] polygon) {
		final int corners = polygon.length / 2;
		if (corners < 3) {
			return polygon;
		}
		double x = 0;
		double z = 0;
		for (int i = 0; i < corners; i++) {
			x += polygon[i * 2];
			z += polygon[i * 2 + 1];
		}
		final double[] result = new double[polygon.length + 4];
		result[0] = x / corners;
		result[1] = z / corners;
		System.arraycopy(polygon, 0, result, 2, polygon.length);
		result[result.length - 2] = polygon[0];
		result[result.length - 1] = polygon[1];
		return result;
	}

	/**
	 * @return the polygon with no side longer than a quarter block, so clipping it by a curved distance stays near the curve
	 */
	private static double[] subdivide(double[] polygon) {
		final int corners = polygon.length / 2;
		final List<Double> result = new ArrayList<>();
		for (int i = 0; i < corners; i++) {
			final double x1 = polygon[i * 2], z1 = polygon[i * 2 + 1];
			final double x2 = polygon[(i + 1) % corners * 2], z2 = polygon[(i + 1) % corners * 2 + 1];
			final int pieces = Math.max(1, (int) Math.ceil(Math.hypot(x2 - x1, z2 - z1) / 0.25));
			for (int j = 0; j < pieces; j++) {
				result.add(x1 + (x2 - x1) * j / pieces);
				result.add(z1 + (z2 - z1) * j / pieces);
			}
		}
		final double[] array = new double[result.size()];
		for (int i = 0; i < array.length; i++) {
			array[i] = result.get(i);
		}
		return array;
	}

	/**
	 * A sloped top (0.94.0): the outline's fan with each corner at the top's height there, its normal square to the
	 * top's average slope
	 */
	private static void slopedFan(VertexConsumer consumer, Matrix4f pose, Matrix3f normal, double[] polygon, int top, TextureAtlasSprite sprite, int light) {
		final double h00 = SlopedTops.corner(top, 0), h10 = SlopedTops.corner(top, 1), h11 = SlopedTops.corner(top, 2), h01 = SlopedTops.corner(top, 3);
		final double riseX = (h10 - h00 + h11 - h01) / 2 / SlopedTops.CELLS;
		final double riseZ = (h01 - h00 + h11 - h10) / 2 / SlopedTops.CELLS;
		final double normalLength = Math.sqrt(riseX * riseX + 1 + riseZ * riseZ);
		final float normalX = (float) (-riseX / normalLength);
		final float normalY = (float) (1 / normalLength);
		final float normalZ = (float) (-riseZ / normalLength);
		final int corners = polygon.length / 2;
		for (int i = 1; i + 1 < corners; i++) {
			for (final int corner : new int[]{0, i, i + 1, i + 1}) {
				final double x = polygon[corner * 2];
				final double z = polygon[corner * 2 + 1];
				vertex(consumer, pose, normal, (float) x, (float) SlopedTops.heightAt(top, x, z), (float) z, sprite, (float) x, (float) z, normalX, normalY, normalZ, light);
			}
		}
	}

	/**
	 * Each side of the outline from the bottom up to a sloped top (0.94.0), the texture cut off where the top is
	 */
	private static void slopedSides(VertexConsumer consumer, Matrix4f pose, Matrix3f normal, double[] outline, Value topAt, TextureAtlasSprite sprite, int light) {
		final int corners = outline.length / 2;
		for (int i = 0; i < corners; i++) {
			final float x1 = (float) outline[i * 2], z1 = (float) outline[i * 2 + 1];
			final float x2 = (float) outline[(i + 1) % corners * 2], z2 = (float) outline[(i + 1) % corners * 2 + 1];
			final float length = (float) Math.hypot(x2 - x1, z2 - z1);
			final float top1 = (float) topAt.at(x1, z1);
			final float top2 = (float) topAt.at(x2, z2);
			if (length < 1E-4 || top1 < 1E-4 && top2 < 1E-4) {
				continue;
			}
			final float normalX = (z2 - z1) / length;
			final float normalZ = -(x2 - x1) / length;
			final float u1 = x1 * Math.abs(normalZ) + z1 * Math.abs(normalX);
			final float u2 = x2 * Math.abs(normalZ) + z2 * Math.abs(normalX);
			vertex(consumer, pose, normal, x1, 0, z1, sprite, u1, 1, normalX, 0, normalZ, light);
			vertex(consumer, pose, normal, x2, 0, z2, sprite, u2, 1, normalX, 0, normalZ, light);
			vertex(consumer, pose, normal, x2, top2, z2, sprite, u2, 1 - top2, normalX, 0, normalZ, light);
			vertex(consumer, pose, normal, x1, top1, z1, sprite, u1, 1 - top1, normalX, 0, normalZ, light);
		}
	}

	private static void sides(VertexConsumer consumer, Matrix4f pose, Matrix3f normal, double[] outline, float bottom, float top, TextureAtlasSprite sprite, int light) {
		final int corners = outline.length / 2;
		if (corners < 3) {
			return;
		}
		for (int i = 0; i < corners; i++) {
			final float x1 = (float) outline[i * 2], z1 = (float) outline[i * 2 + 1];
			final float x2 = (float) outline[(i + 1) % corners * 2], z2 = (float) outline[(i + 1) % corners * 2 + 1];
			final float length = (float) Math.hypot(x2 - x1, z2 - z1);
			if (length < 1E-4) {
				continue;
			}
			// Outwards: the outline goes round anticlockwise seen from above when x is east and z south
			final float normalX = (z2 - z1) / length;
			final float normalZ = -(x2 - x1) / length;
			final float u1 = x1 * Math.abs(normalZ) + z1 * Math.abs(normalX);
			final float u2 = x2 * Math.abs(normalZ) + z2 * Math.abs(normalX);
			vertex(consumer, pose, normal, x1, bottom, z1, sprite, u1, 1 - bottom, normalX, 0, normalZ, light);
			vertex(consumer, pose, normal, x2, bottom, z2, sprite, u2, 1 - bottom, normalX, 0, normalZ, light);
			vertex(consumer, pose, normal, x2, top, z2, sprite, u2, 1 - top, normalX, 0, normalZ, light);
			vertex(consumer, pose, normal, x1, top, z1, sprite, u1, 1 - top, normalX, 0, normalZ, light);
		}
	}

	@Override
	public int getViewDistance() {
		return this instanceof GapFiller ? GAP_FILLER_VIEW_DISTANCE : 256;
	}

	private static void fan(VertexConsumer consumer, Matrix4f pose, Matrix3f normal, double[] polygon, float y, TextureAtlasSprite sprite, int light, int up, Mapping mapping) {
		final int corners = polygon.length / 2;
		for (int i = 1; i + 1 < corners; i++) {
			final int[] order = up > 0 ? new int[]{0, i, i + 1, i + 1} : new int[]{0, i + 1, i, i};
			for (final int corner : order) {
				final double x = polygon[corner * 2];
				final double z = polygon[corner * 2 + 1];
				final float[] uv = mapping == null ? new float[]{(float) x, (float) z} : mapping.uv(x, z);
				vertex(consumer, pose, normal, (float) x, y, (float) z, sprite, uv[0], uv[1], 0, up, 0, light);
			}
		}
	}

	/**
	 * @return the polygon's part where the value is not above 0, as {x, z} pairs
	 */
	private static double[] clip(double[] polygon, Value value) {
		final int corners = polygon.length / 2;
		final List<Double> result = new ArrayList<>();
		for (int i = 0; i < corners; i++) {
			final double x1 = polygon[i * 2], z1 = polygon[i * 2 + 1];
			final double x2 = polygon[(i + 1) % corners * 2], z2 = polygon[(i + 1) % corners * 2 + 1];
			final double value1 = value.at(x1, z1);
			final double value2 = value.at(x2, z2);
			if (value1 <= 0) {
				result.add(x1);
				result.add(z1);
			}
			if (value1 <= 0 != value2 <= 0) {
				final double t = value1 / (value1 - value2);
				result.add(x1 + (x2 - x1) * t);
				result.add(z1 + (z2 - z1) * t);
			}
		}
		final double[] array = new double[result.size()];
		for (int i = 0; i < array.length; i++) {
			array[i] = result.get(i);
		}
		return array;
	}

	private static void vertex(VertexConsumer consumer, Matrix4f pose, Matrix3f normal, float x, float y, float z, TextureAtlasSprite sprite, float u, float v, float normalX, float normalY, float normalZ, int light) {
		final float clampedU = Math.max(0, Math.min(1, u));
		final float clampedV = Math.max(0, Math.min(1, v));
		consumer.vertex(pose, x, y, z).color(255, 255, 255, 255).uv(sprite.getU(clampedU * 16), sprite.getV(clampedV * 16)).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(normal, normalX, normalY, normalZ).endVertex();
	}

	/**
	 * @return the block's top texture (its lowest top face), its further top layers (MTR's platform marking), and its side
	 * texture, from its model
	 */
	private static Textures texturesOf(BlockState source) {
		final BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(source);
		// By the model and then the state, as one model (a multipart one) can lay its faces turned for each facing
		return TEXTURES.computeIfAbsent(model, key -> new HashMap<>()).computeIfAbsent(source, key -> {
			final RandomSource random = RandomSource.create(42);
			final List<BakedQuad> tops = new ArrayList<>(model.getQuads(source, Direction.UP, random));
			if (tops.isEmpty()) {
				model.getQuads(source, null, random).stream().filter(quad -> quad.getDirection() == Direction.UP).forEach(tops::add);
			}
			final List<BakedQuad> sides = new ArrayList<>(model.getQuads(source, Direction.NORTH, random));
			if (sides.isEmpty()) {
				model.getQuads(source, null, random).stream().filter(quad -> quad.getDirection().getAxis().isHorizontal()).forEach(sides::add);
			}
			final List<Layer> layers = new ArrayList<>();
			for (int i = 1; i < tops.size(); i++) {
				layers.add(new Layer(tops.get(i)));
			}
			final TextureAtlasSprite side = sides.isEmpty() ? model.getParticleIcon() : sides.get(0).getSprite();
			return new Textures(tops.isEmpty() ? side : tops.get(0).getSprite(), layers, side);
		});
	}

	private static final class Textures {

		private final TextureAtlasSprite top;
		private final List<Layer> layers;
		private final TextureAtlasSprite side;

		private Textures(TextureAtlasSprite top, List<Layer> layers, TextureAtlasSprite side) {
			this.top = top;
			this.layers = layers;
			this.side = side;
		}
	}

	/**
	 * A further layer of a block's top, with where on its texture each point of the top is as the block's model lays it
	 * (turned with the block's facing), so the marking keeps the side the block has towards the track (0.89.4)
	 */
	private static final class Layer {

		private final TextureAtlasSprite sprite;
		/**
		 * u = a x + b z + c, v = d x + e z + f, in block coordinates and the texture from 0 to 1; null when the quad does not
		 * give it
		 */
		private final double[] uvOf;

		private Layer(BakedQuad quad) {
			sprite = quad.getSprite();
			uvOf = mapping(quad);
		}

		private float[] uvOf(double x, double z) {
			return new float[]{(float) (uvOf[0] * x + uvOf[1] * z + uvOf[2]), (float) (uvOf[3] * x + uvOf[4] * z + uvOf[5])};
		}

		private static double[] mapping(BakedQuad quad) {
			final int[] vertices = quad.getVertices();
			final int stride = vertices.length / 4;
			if (stride < 6) {
				return null;
			}
			final TextureAtlasSprite sprite = quad.getSprite();
			final double uSpan = sprite.getU1() - sprite.getU0();
			final double vSpan = sprite.getV1() - sprite.getV0();
			if (uSpan == 0 || vSpan == 0) {
				return null;
			}
			final double[] x = new double[3], z = new double[3], u = new double[3], v = new double[3];
			for (int i = 0; i < 3; i++) {
				x[i] = Float.intBitsToFloat(vertices[i * stride]);
				z[i] = Float.intBitsToFloat(vertices[i * stride + 2]);
				u[i] = (Float.intBitsToFloat(vertices[i * stride + 4]) - sprite.getU0()) / uSpan;
				v[i] = (Float.intBitsToFloat(vertices[i * stride + 5]) - sprite.getV0()) / vSpan;
			}
			final double determinant = (x[1] - x[0]) * (z[2] - z[0]) - (x[2] - x[0]) * (z[1] - z[0]);
			if (Math.abs(determinant) < 1E-6) {
				return null;
			}
			final double[] result = new double[6];
			for (int row = 0; row < 2; row++) {
				final double[] w = row == 0 ? u : v;
				final double a = ((w[1] - w[0]) * (z[2] - z[0]) - (w[2] - w[0]) * (z[1] - z[0])) / determinant;
				final double b = ((x[1] - x[0]) * (w[2] - w[0]) - (x[2] - x[0]) * (w[1] - w[0])) / determinant;
				result[row * 3] = a;
				result[row * 3 + 1] = b;
				result[row * 3 + 2] = w[0] - a * x[0] - b * z[0];
			}
			return result;
		}
	}

	/**
	 * A block's faces made once (0.101.1): each vertex in the block's coordinates, its place on the block atlas, its normal
	 * and which light it takes, the top's (the block above's) or the sides' (the block's own)
	 */
	static final class Mesh implements VertexConsumer {

		static final int TOP = 0;
		static final int SIDE = 1;
		private static final int STRIDE = 9;

		private final Object[] key;
		private float[] data = new float[STRIDE * 64];
		private int size;
		private float x, y, z, u, v, normalX, normalY, normalZ;
		private int light;

		Mesh(Object[] key) {
			this.key = key;
		}

		void draw(VertexConsumer consumer, Matrix4f pose, Matrix3f normal, int topLight, int sideLight) {
			for (int i = 0; i < size; i += STRIDE) {
				consumer.vertex(pose, data[i], data[i + 1], data[i + 2]).color(255, 255, 255, 255).uv(data[i + 3], data[i + 4]).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(data[i + 8] == TOP ? topLight : sideLight).normal(normal, data[i + 5], data[i + 6], data[i + 7]).endVertex();
			}
		}

		int vertices() {
			return size / STRIDE;
		}

		@Override
		public VertexConsumer vertex(double x, double y, double z) {
			this.x = (float) x;
			this.y = (float) y;
			this.z = (float) z;
			return this;
		}

		@Override
		public VertexConsumer color(int red, int green, int blue, int alpha) {
			return this;
		}

		@Override
		public VertexConsumer uv(float u, float v) {
			this.u = u;
			this.v = v;
			return this;
		}

		@Override
		public VertexConsumer overlayCoords(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer uv2(int u, int v) {
			light = u;
			return this;
		}

		@Override
		public VertexConsumer normal(float x, float y, float z) {
			normalX = x;
			normalY = y;
			normalZ = z;
			return this;
		}

		@Override
		public void endVertex() {
			if (size + STRIDE > data.length) {
				data = java.util.Arrays.copyOf(data, data.length * 2);
			}
			data[size] = x;
			data[size + 1] = y;
			data[size + 2] = z;
			data[size + 3] = u;
			data[size + 4] = v;
			data[size + 5] = normalX;
			data[size + 6] = normalY;
			data[size + 7] = normalZ;
			data[size + 8] = light;
			size += STRIDE;
		}

		@Override
		public void defaultColor(int red, int green, int blue, int alpha) {
		}

		@Override
		public void unsetDefaultColor() {
		}
	}

	@FunctionalInterface
	private interface Value {
		double at(double x, double z);
	}

	@FunctionalInterface
	private interface Mapping {
		float[] uv(double x, double z);
	}

	/**
	 * Gap fillers' drawing (0.93.0): the same, from nearer
	 */
	public static class GapFiller extends RenderPlatformEdge<BlockGapFiller.TileEntityGapFiller> {

		public GapFiller(BlockEntityRenderDispatcher dispatcher) {
			super(dispatcher);
		}
	}
}
