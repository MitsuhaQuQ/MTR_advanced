package mtr.compat;

public final class MaterialColor {

	public static final MaterialColor COLOR_GRAY = new MaterialColor(net.minecraft.world.level.material.MapColor.COLOR_GRAY);
	public static final MaterialColor COLOR_BLACK = new MaterialColor(net.minecraft.world.level.material.MapColor.COLOR_BLACK);
	public static final MaterialColor COLOR_YELLOW = new MaterialColor(net.minecraft.world.level.material.MapColor.COLOR_YELLOW);
	public static final MaterialColor QUARTZ = new MaterialColor(net.minecraft.world.level.material.MapColor.QUARTZ);
	public static final MaterialColor TERRACOTTA_YELLOW = new MaterialColor(net.minecraft.world.level.material.MapColor.TERRACOTTA_YELLOW);
	public static final MaterialColor TERRACOTTA_LIGHT_BLUE = new MaterialColor(net.minecraft.world.level.material.MapColor.TERRACOTTA_LIGHT_BLUE);
	public static final MaterialColor STONE = new MaterialColor(net.minecraft.world.level.material.MapColor.STONE);
	public static final MaterialColor SNOW = new MaterialColor(net.minecraft.world.level.material.MapColor.SNOW);
	public static final MaterialColor WOOD = new MaterialColor(net.minecraft.world.level.material.MapColor.WOOD);
	public static final MaterialColor METAL = new MaterialColor(net.minecraft.world.level.material.MapColor.METAL);
	public static final MaterialColor ICE = new MaterialColor(net.minecraft.world.level.material.MapColor.ICE);
	public static final MaterialColor EMERALD = new MaterialColor(net.minecraft.world.level.material.MapColor.EMERALD);
	public static final MaterialColor DIAMOND = new MaterialColor(net.minecraft.world.level.material.MapColor.DIAMOND);
	public static final MaterialColor COLOR_RED = new MaterialColor(net.minecraft.world.level.material.MapColor.COLOR_RED);
	public static final MaterialColor COLOR_PURPLE = new MaterialColor(net.minecraft.world.level.material.MapColor.COLOR_PURPLE);
	public static final MaterialColor COLOR_ORANGE = new MaterialColor(net.minecraft.world.level.material.MapColor.COLOR_ORANGE);
	public static final MaterialColor COLOR_LIGHT_GRAY = new MaterialColor(net.minecraft.world.level.material.MapColor.COLOR_LIGHT_GRAY);
	public static final MaterialColor COLOR_BROWN = new MaterialColor(net.minecraft.world.level.material.MapColor.COLOR_BROWN);
	public static final MaterialColor COLOR_BLUE = new MaterialColor(net.minecraft.world.level.material.MapColor.COLOR_BLUE);

	public final net.minecraft.world.level.material.MapColor data;
	public final int col;

	private MaterialColor(net.minecraft.world.level.material.MapColor data) {
		this.data = data;
		col = data.col;
	}
}
