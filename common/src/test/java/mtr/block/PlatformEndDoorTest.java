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
