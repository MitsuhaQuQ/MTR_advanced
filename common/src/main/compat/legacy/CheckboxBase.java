package mtr.compat;

import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.network.chat.Component;

public abstract class CheckboxBase extends Checkbox {

	public CheckboxBase(int x, int y, int width, int height, Component text, boolean checked, boolean showMessage) {
		super(x, y, width, height, text, checked, showMessage);
	}
}
