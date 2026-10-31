package mtr.compat;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

public abstract class SliderBase extends AbstractSliderButton {

	public SliderBase(int x, int y, int width, int height, Component message, double value) {
		super(x, y, width, height, message, value);
	}
}
