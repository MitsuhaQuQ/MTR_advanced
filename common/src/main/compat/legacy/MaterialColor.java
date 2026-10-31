package mtr.compat;

public final class MaterialColor {

	public static final MaterialColor COLOR_GRAY = new MaterialColor(net.minecraft.world.level.material.MaterialColor.COLOR_GRAY);
	public static final MaterialColor COLOR_BLACK = new MaterialColor(net.minecraft.world.level.material.MaterialColor.COLOR_BLACK);
	public static final MaterialColor COLOR_YELLOW = new MaterialColor(net.minecraft.world.level.material.MaterialColor.COLOR_YELLOW);
	public static final MaterialColor QUARTZ = new MaterialColor(net.minecraft.world.level.material.MaterialColor.QUARTZ);
	public static final MaterialColor TERRACOTTA_YELLOW = new MaterialColor(net.minecraft.world.level.material.MaterialColor.TERRACOTTA_YELLOW);
	public static final MaterialColor TERRACOTTA_LIGHT_BLUE = new MaterialColor(net.minecraft.world.level.material.MaterialColor.TERRACOTTA_LIGHT_BLUE);
	public static final MaterialColor STONE = new MaterialColor(net.minecraft.world.level.material.MaterialColor.STONE);
	public static final MaterialColor SNOW = new MaterialColor(net.minecraft.world.level.material.MaterialColor.SNOW);
	public static final MaterialColor WOOD = new MaterialColor(net.minecraft.world.level.material.MaterialColor.WOOD);
	public static final MaterialColor METAL = new MaterialColor(net.minecraft.world.level.material.MaterialColor.METAL);
	public static final MaterialColor ICE = new MaterialColor(net.minecraft.world.level.material.MaterialColor.ICE);
	public static final MaterialColor EMERALD = new MaterialColor(net.minecraft.world.level.material.MaterialColor.EMERALD);
	public static final MaterialColor DIAMOND = new MaterialColor(net.minecraft.world.level.material.MaterialColor.DIAMOND);
	public static final MaterialColor COLOR_RED = new MaterialColor(net.minecraft.world.level.material.MaterialColor.COLOR_RED);
	public static final MaterialColor COLOR_PURPLE = new MaterialColor(net.minecraft.world.level.material.MaterialColor.COLOR_PURPLE);
	public static final MaterialColor COLOR_ORANGE = new MaterialColor(net.minecraft.world.level.material.MaterialColor.COLOR_ORANGE);
	public static final MaterialColor COLOR_LIGHT_GRAY = new MaterialColor(net.minecraft.world.level.material.MaterialColor.COLOR_LIGHT_GRAY);
	public static final MaterialColor COLOR_BROWN = new MaterialColor(net.minecraft.world.level.material.MaterialColor.COLOR_BROWN);
	public static final MaterialColor COLOR_BLUE = new MaterialColor(net.minecraft.world.level.material.MaterialColor.COLOR_BLUE);

	public final net.minecraft.world.level.material.MaterialColor data;
	public final int col;

	private MaterialColor(net.minecraft.world.level.material.MaterialColor data) {
		this.data = data;
		col = data.col;
	}
}
