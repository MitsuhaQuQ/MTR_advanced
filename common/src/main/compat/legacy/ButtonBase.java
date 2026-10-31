package mtr.compat;

import mtr.mappings.ButtonMapper;
import net.minecraft.network.chat.Component;

public abstract class ButtonBase extends ButtonMapper {

	public ButtonBase(int x, int y, int width, int height, Component component, OnPress onPress) {
		super(x, y, width, height, component, onPress);
	}
}
