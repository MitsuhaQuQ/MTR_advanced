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
		for (int style = 0; style < 3; style++) {
			assertEquals(style == 0 ? 9 / 16D : 1, BlockPlatformEndDoor.outline(style, DoubleBlockHalf.UPPER, Direction.NORTH, false).bounds().maxY);
			for (Direction facing : Direction.Plane.HORIZONTAL) {
				final int height = style == 0 ? 9 : 16;
				assertEquals(IBlock.getVoxelShapeByDirection(14, 0, -14, 16, height, 2, facing).bounds(),
						BlockPlatformEndDoor.outline(style, DoubleBlockHalf.UPPER, facing, true).bounds());
			}
			assertNotEquals(BlockPlatformEndDoor.outline(style, DoubleBlockHalf.LOWER, Direction.NORTH, false).bounds(), BlockPlatformEndDoor.outline(style, DoubleBlockHalf.LOWER, Direction.NORTH, true).bounds());
		}
	}
}
