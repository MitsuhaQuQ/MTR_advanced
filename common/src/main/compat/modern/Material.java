package mtr.compat;

import net.minecraft.world.level.block.state.BlockBehaviour;

public final class Material {

	public static final Material METAL = new Material(MaterialColor.METAL);
	public static final Material STONE = new Material(MaterialColor.STONE);

	private final MaterialColor defaultColor;

	private Material(MaterialColor defaultColor) {
		this.defaultColor = defaultColor;
	}

	public static BlockBehaviour.Properties properties(Material material) {
		return properties(material, material.defaultColor);
	}

	public static BlockBehaviour.Properties properties(Material material, MaterialColor color) {
		return BlockBehaviour.Properties.of().mapColor(color.data);
	}
}
