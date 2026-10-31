package mtr.compat;

import mtr.mappings.ScreenMapper;
import net.minecraft.network.chat.Component;

public abstract class ScreenBase extends ScreenMapper {

	protected ScreenBase(Component title) {
		super(title);
	}
}
