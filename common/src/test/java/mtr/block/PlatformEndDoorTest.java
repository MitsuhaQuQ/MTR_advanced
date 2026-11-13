package mtr.block;

import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.CollisionContext;
import static org.junit.jupiter.api.Assertions.*;

class PlatformEndDoorTest {
	@org.junit.jupiter.api.BeforeAll
	static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}
	@Test
	void everyPlatformStyleUsesAManualPanelWithMatchingOpenCollision() {
		assertFalse(BlockPSDAPGDoorBase.class.isAssignableFrom(BlockPlatformEndDoor.class));
		for (int style = 0; style < 4; style++) {
			assertEquals(style == 0 || style == 3 ? 9 / 16D : 1, BlockPlatformEndDoor.outline(style, DoubleBlockHalf.UPPER, Direction.NORTH, false).bounds().maxY);
			for (Direction facing : Direction.Plane.HORIZONTAL) {
				final int height = style == 0 || style == 3 ? 9 : 16;
				assertEquals(IBlock.getVoxelShapeByDirection(14, 0, -12, 16, height, 4, facing).bounds(),
						BlockPlatformEndDoor.outline(style, DoubleBlockHalf.UPPER, facing, true).bounds());
			}
			assertNotEquals(BlockPlatformEndDoor.outline(style, DoubleBlockHalf.LOWER, Direction.NORTH, false).bounds(), BlockPlatformEndDoor.outline(style, DoubleBlockHalf.LOWER, Direction.NORTH, true).bounds());
			// Swinging back into the block, the leaf stands behind the hinge instead of in front of it (0.65.29)
			final net.minecraft.world.phys.AABB in = BlockPlatformEndDoor.outline(style, DoubleBlockHalf.LOWER, Direction.NORTH, true, true).bounds();
			assertEquals(2 / 16D, in.minZ, 1E-9);
			assertEquals(18 / 16D, in.maxZ, 1E-9);
			assertEquals(14 / 16D, in.minX, 1E-9);
		}
	}

	/**
	 * The gate's jambs stand in the same plane as the screen door and gate glass beside it (0.65.29): z 2..4 of a north
	 * facing block, the glass's model x 2..4 turned by its blockstate. Open, the leaf turns about the jamb at (15, 3).
	 */
	@Test
	void theGateLinesUpWithTheGlassBesideIt() throws java.io.IOException {
		for (final String name : new String[]{"platform_edge_door", "platform_edge_door_low", "platform_edge_door_no_handle", "platform_edge_door_low_no_handle"}) {
			for (final String half : new String[]{"lower", "upper"}) {
				final com.google.gson.JsonArray closed = elements(name + "_" + half);
				final com.google.gson.JsonArray open = elements(name + "_" + half + "_open");
				final com.google.gson.JsonArray openIn = elements(name + "_" + half + "_open_in");
				boolean jamb = false;
				for (final com.google.gson.JsonElement element : closed) {
					final com.google.gson.JsonArray from = element.getAsJsonObject().getAsJsonArray("from");
					final com.google.gson.JsonArray to = element.getAsJsonObject().getAsJsonArray("to");
					if (from.get(0).getAsDouble() == 15 && to.get(0).getAsDouble() == 16) {
						assertEquals(2, from.get(2).getAsDouble(), name + " " + half);
						assertEquals(4, to.get(2).getAsDouble(), name + " " + half);
						jamb = true;
					}
				}
				assertTrue(jamb, name + " " + half + " has its hinge jamb");
				// The push bar and its brackets stand on the platform's side of the leaf, the handle on the other
				// (2026-11-14: every model had the door the wrong way round)
				double nearest = 16, furthest = 0;
				for (final com.google.gson.JsonElement element : closed) {
					nearest = Math.min(nearest, element.getAsJsonObject().getAsJsonArray("from").get(2).getAsDouble());
					furthest = Math.max(furthest, element.getAsJsonObject().getAsJsonArray("to").get(2).getAsDouble());
				}
				// The bar is at the top of the lower half, so only that half has anything in front of the glass
				assertTrue(nearest < 0 || !"lower".equals(half), name + " " + half + ": the bar stands in front of the glass, not behind it (" + nearest + ")");
				assertTrue(furthest <= 6, name + " " + half + ": nothing reaches far past the glass (" + furthest + ")");
				// The open model is the closed one moved so the blockstate's turn, (x, z) to (16 - z, x), keeps (15, 3)
				for (int i = 0; i < closed.size(); i++) {
					final com.google.gson.JsonArray closedFrom = closed.get(i).getAsJsonObject().getAsJsonArray("from");
					final com.google.gson.JsonArray openFrom = open.get(i).getAsJsonObject().getAsJsonArray("from");
					assertEquals(closedFrom.get(0).getAsDouble() - 12, openFrom.get(0).getAsDouble(), 1E-6);
					assertEquals(closedFrom.get(2).getAsDouble() - 2, openFrom.get(2).getAsDouble(), 1E-6);
					// Turned the other way, (x, z) to (z, 16 - x), keeping the same hinge
					final com.google.gson.JsonArray inFrom = openIn.get(i).getAsJsonObject().getAsJsonArray("from");
					assertEquals(closedFrom.get(0).getAsDouble() - 2, inFrom.get(0).getAsDouble(), 1E-6);
					assertEquals(closedFrom.get(2).getAsDouble() + 12, inFrom.get(2).getAsDouble(), 1E-6);
				}
			}
		}
	}

	/**
	 * The brush turns a gate left to right (0.77.0): its hinge goes to the other end and it swings to the same side, so
	 * open, every part stands where the part of the gate as drawn stands mirrored across the block's middle.
	 */
	@Test
	void aGateTurnedLeftToRightIsTheMirrorImageOfTheOneAsDrawn() throws java.io.IOException {
		for (int style = 0; style < 4; style++) {
			for (final boolean inward : new boolean[]{false, true}) {
				final net.minecraft.world.phys.AABB drawn = BlockPlatformEndDoor.outline(style, DoubleBlockHalf.LOWER, Direction.NORTH, true, inward, false).bounds();
				final net.minecraft.world.phys.AABB turned = BlockPlatformEndDoor.outline(style, DoubleBlockHalf.LOWER, Direction.NORTH, true, inward, true).bounds();
				assertEquals(1 - drawn.maxX, turned.minX, 1E-9);
				assertEquals(1 - drawn.minX, turned.maxX, 1E-9);
				assertEquals(drawn.minZ, turned.minZ, 1E-9);
				assertEquals(drawn.maxZ, turned.maxZ, 1E-9);
			}
			assertEquals(BlockPlatformEndDoor.outline(style, DoubleBlockHalf.LOWER, Direction.NORTH, false, false, false).bounds(),
					BlockPlatformEndDoor.outline(style, DoubleBlockHalf.LOWER, Direction.NORTH, false, false, true).bounds());
		}
		final com.google.gson.JsonObject variants = json("blockstates/platform_end_psd.json").getAsJsonObject("variants");
		for (final String name : new String[]{"platform_edge_door", "platform_edge_door_low", "platform_edge_door_no_handle", "platform_edge_door_low_no_handle"}) {
			for (final String half : new String[]{"lower", "upper"}) {
				for (final String state : new String[]{"", "_open", "_open_in"}) {
					final String key = "facing=north,half=" + half + ",inward=" + "_open_in".equals(state) + ",mirrored=%s,open=" + !state.isEmpty();
					final int drawnTurn = variants.getAsJsonObject(String.format(key, false)).get("y").getAsInt();
					final int turnedTurn = variants.getAsJsonObject(String.format(key, true)).get("y").getAsInt();
					final java.util.List<String> drawn = new java.util.ArrayList<>();
					for (final com.google.gson.JsonElement element : elements(name + "_" + half + state)) {
						final double[] box = box(element.getAsJsonObject(), drawnTurn);
						drawn.add(describe(16 - box[1], 16 - box[0], box[2], box[3], box[4], box[5]));
					}
					final java.util.List<String> turned = new java.util.ArrayList<>();
					for (final com.google.gson.JsonElement element : elements(name + "_" + half + "_mirrored" + state)) {
						final double[] box = box(element.getAsJsonObject(), turnedTurn);
						turned.add(describe(box[0], box[1], box[2], box[3], box[4], box[5]));
					}
					java.util.Collections.sort(drawn);
					java.util.Collections.sort(turned);
					assertEquals(drawn, turned, name + " " + half + state);
				}
			}
		}
	}

	/** The cube's x and z range after the blockstate's turn about (8, 8), and its y range */
	private static double[] box(com.google.gson.JsonObject element, int turn) {
		final com.google.gson.JsonArray from = element.getAsJsonArray("from");
		final com.google.gson.JsonArray to = element.getAsJsonArray("to");
		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (final double cornerX : new double[]{from.get(0).getAsDouble(), to.get(0).getAsDouble()}) {
			for (final double cornerZ : new double[]{from.get(2).getAsDouble(), to.get(2).getAsDouble()}) {
				double x = cornerX, z = cornerZ;
				for (int i = 0; i < turn / 90; i++) {
					final double turnedX = 16 - z;
					z = x;
					x = turnedX;
				}
				minX = Math.min(minX, x);
				maxX = Math.max(maxX, x);
				minZ = Math.min(minZ, z);
				maxZ = Math.max(maxZ, z);
			}
		}
		return new double[]{minX, maxX, minZ, maxZ, from.get(1).getAsDouble(), to.get(1).getAsDouble()};
	}

	private static String describe(double... values) {
		final StringBuilder builder = new StringBuilder();
		for (final double value : values) builder.append(String.format(java.util.Locale.ROOT, "%.4f ", value + 0.0));
		return builder.toString();
	}

	private static com.google.gson.JsonObject json(String relative) throws java.io.IOException {
		for (final String root : new String[]{"../resources/common/normal", "resources/common/normal"}) {
			final java.nio.file.Path candidate = java.nio.file.Paths.get(root, "assets/mtr", relative);
			if (java.nio.file.Files.exists(candidate)) {
				return com.google.gson.JsonParser.parseString(new String(java.nio.file.Files.readAllBytes(candidate), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
			}
		}
		fail(relative);
		return null;
	}

	private static com.google.gson.JsonArray elements(String model) throws java.io.IOException {
		java.nio.file.Path path = null;
		for (final String root : new String[]{"../resources/common/normal", "resources/common/normal"}) {
			final java.nio.file.Path candidate = java.nio.file.Paths.get(root, "assets/mtr/models/block", model + ".json");
			if (java.nio.file.Files.exists(candidate)) {
				path = candidate;
			}
		}
		assertNotNull(path, model);
		return com.google.gson.JsonParser.parseString(new String(java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("elements");
	}
}
