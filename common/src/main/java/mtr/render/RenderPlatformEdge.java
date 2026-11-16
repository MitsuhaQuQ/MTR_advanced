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
 * and no recess, and from nearer, as there may be many of them along a tunnel.
 */
public class RenderPlatformEdge<T extends BlockPlatformEdge.TileEntityPlatformEdge> extends BlockEntityRendererMapper<T> {

	/**
	 * The textures of each block's model, by the model, so a reload of the resources makes them again
	 */
	private static final Map<BakedModel, Map<BlockState, Textures>> TEXTURES = new WeakHashMap<>();
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
		final BlockState source = entity.getSource() == null ? Blocks.PLATFORM.get().defaultBlockState() : entity.getSource();
		final Textures textures = texturesOf(source);
		// Below a recess (MTR's indented platforms) the platform's part starts further from the track (0.89.4)
		final int[] recess = gapFiller ? new int[]{0, 0} : BlockPlatformEdge.recessOf(entity.getSource());
		final float recessHeight = recess[0] == 0 ? 0 : (float) recess[1] / PlatformEdges.CELLS;
		final double[] lowerOutline = recess[0] == 0 ? outline : PlatformEdges.outline(angle, BlockPlatformEdge.recessedCut(cut, recess));
		final int topLight = LevelRenderer.getLightColor(world, pos.above());
		final int sideLight = LevelRenderer.getLightColor(world, pos);
		final Matrix4f pose = matrices.last().pose();
		final Matrix3f normal = matrices.last().normal();
		final VertexConsumer consumer = vertexConsumers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));

		// The top and the bottom, as fans of triangles drawn as quads with the last corner twice, the texture as on a whole block
		if (textures.top != null) {
			fan(consumer, pose, normal, outline, 1, textures.top, topLight, 1, null);
		}
		if (textures.side != null) {
			if (lowerOutline.length >= 6) {
				fan(consumer, pose, normal, lowerOutline, 0, textures.side, sideLight, -1, null);
			}
			if (recessHeight > 0) {
				// The underside of the top over the recess
				fan(consumer, pose, normal, outline, recessHeight, textures.side, sideLight, -1, null);
			}
		}
		// The top's further layers along the cut: across the cut from 1 at the edge to 0 a block in, along it centred
		final double towardsTrackX = Math.cos(angle * 2 * Math.PI / PlatformEdges.ANGLES);
		final double towardsTrackZ = Math.sin(angle * 2 * Math.PI / PlatformEdges.ANGLES);
		final double d = (double) (cut - PlatformEdges.CUT_CENTRE) / PlatformEdges.CELLS;
		final double[] marked = clip(outline, (x, z) -> d - ((x - 0.5) * towardsTrackX + (z - 0.5) * towardsTrackZ) - PlatformEdges.MARKING_REACH);
		if (marked.length >= 6 && !gapFiller) {
			// The side the source block faces is its side towards the track (0.89.4): that side of its marking is laid at the
			// cut, wherever it is on the texture; a block with no facing has the texture's bottom (v = 1) there
			final Direction facing = source.hasProperty(HorizontalDirectionalBlock.FACING) ? source.getValue(HorizontalDirectionalBlock.FACING) : null;
			for (final Layer layer : textures.layers) {
				fan(consumer, pose, normal, marked, 1 + MARKING_LIFT, layer.sprite, topLight, 1, (x, z) -> {
					final double inwards = (d - ((x - 0.5) * towardsTrackX + (z - 0.5) * towardsTrackZ)) / PlatformEdges.MARKING_REACH;
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
		// Each side of the outline, the cut among them, from the bottom to the top; under a recess the lower outline's
		if (textures.side != null) {
			sides(consumer, pose, normal, outline, recessHeight, 1, textures.side, sideLight);
			if (recessHeight > 0) {
				sides(consumer, pose, normal, lowerOutline, 0, recessHeight, textures.side, sideLight);
			}
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
