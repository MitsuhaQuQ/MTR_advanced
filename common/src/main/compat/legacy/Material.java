package mtr.compat;

import net.minecraft.world.level.block.state.BlockBehaviour;

public final class Material {

	public static final Material METAL = new Material(net.minecraft.world.level.material.Material.METAL);
	public static final Material STONE = new Material(net.minecraft.world.level.material.Material.STONE);

	private final net.minecraft.world.level.material.Material data;

	private Material(net.minecraft.world.level.material.Material data) {
		this.data = data;
	}

	public static BlockBehaviour.Properties properties(Material material) {
		return BlockBehaviour.Properties.of(material.data);
	}

	public static BlockBehaviour.Properties properties(Material material, MaterialColor color) {
		return BlockBehaviour.Properties.of(material.data, color.data);
	}
}
