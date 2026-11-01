package cn.zbx1425.mtrsteamloco.gui;

import mtr.mappings.Text;
import net.minecraft.client.gui.screens.Screen;

import java.util.function.Supplier;

public class FakeScreen extends Screen {
    public Supplier<Screen> actual;

    public FakeScreen(Supplier<Screen> actual) {
        super(Text.literal(""));
        this.actual = actual;
    }

    @Override
    protected void init() {
        minecraft.setScreen(actual.get());
    }
}