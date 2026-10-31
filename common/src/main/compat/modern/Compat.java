package mtr.compat;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;

public interface Compat {

	static Level getLevel(Entity entity) {
		return entity.level();
	}

	static boolean isIce(BlockState state) {
		return state.is(BlockTags.ICE);
	}

	static boolean isSolid(BlockState state) {
		return state.isSolid();
	}

	static boolean isReplaceable(BlockState state) {
		return state.canBeReplaced();
	}

	static int getColor(DyeColor dyeColor) {
		return dyeColor.getMapColor().col;
	}

	static int getColor(Block block) {
		return block.defaultMapColor().col;
	}

	// Blocks already override getPistonPushReaction on older versions, but from 1.20 it is a block property
	static BlockBehaviour.Properties noPistonPush(BlockBehaviour.Properties properties) {
		return properties.pushReaction(PushReaction.BLOCK);
	}
}
