package cn.zbx1425.mtrsteamloco.gui;

#if MC_VERSION >= "12000"
import net.minecraft.client.gui.GuiGraphics;
#endif

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;
import mtr.client.IDrawing;
import mtr.data.IGui;
import net.minecraft.client.gui.components.Button;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.gui.screens.Screen;

import java.util.function.Supplier;

import com.mojang.blaze3d.vertex.PoseStack;

import cn.zbx1425.mtrsteamloco.data.RailExtraSupplier;
import cn.zbx1425.mtrsteamloco.network.PacketUpdateRail;
import mtr.data.Rail;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;

public class RailPathEditorScreen extends Screen implements IGraphics{

    public static Screen createScreen(BlockPos posStart, BlockPos posEnd, Rail rail, Supplier<Screen> parent) {
        switch(((RailExtraSupplier) (Object) rail).getPathMode()) {
            case 0:
                return new Original(posStart, posEnd, rail, parent);
            case 1:
                return new Bezier(posStart, posEnd, rail, parent);
            default:
                return parent.get();
        }
    }

    protected final BlockPos posStart;
    protected final BlockPos posEnd;
    protected final Rail rail;
    protected final RailExtraSupplier extra;
    protected Supplier<Screen> parent;
    protected final Button btnChangePathMode;


    protected RailPathEditorScreen(BlockPos posStart, BlockPos posEnd, Rail rail, Supplier<Screen> parent) {
        super(Text.literal("Rail Path Editor"));
        this.posStart = posStart;
        this.posEnd = posEnd;
        this.rail = rail;
        this.extra = (RailExtraSupplier) (Object) rail;
        this.parent = parent == null ? () -> null : parent;
        btnChangePathMode = UtilitiesClient.newButton(getModeTextComponent(), btn -> changeMode());
        btnChangePathMode.active = extra.couldSwitchModeTo(extra.getPathMode());
    }

    @Override
    protected void init() {
        int w = (int) Math.round(Math.max(320, width * 0.8f));
        IDrawing.setPositionAndWidth(btnChangePathMode, (width - w) / 2, 10, w);
        addWidget(btnChangePathMode);
    }

@Override
#if MC_VERSION >= "12000"
    public void render(GuiGraphics in, int mouseX, int mouseY, float partialTick) {
#else
    public void render(PoseStack in, int mouseX, int mouseY, float partialTick) {
#endif
        btnChangePathMode.render(in, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent.get());
    }

    protected int getMode() {
        return ((RailExtraSupplier) (Object) rail).getPathMode();
    }

    protected String getModeText() {
        switch (getMode()) {
            case 0:
                return "Original";
            case 1:
                return "Bezier";
            default:
                return "Unknown";
        }
    }

    protected void changeMode() {
        ((RailExtraSupplier) (Object) rail).changePathMode(1 - ((RailExtraSupplier) (Object) rail).getPathMode());
        updateRail();
        minecraft.setScreen(createScreen(posStart, posEnd, rail, parent));
    }

    protected void updateRail() {
        PacketUpdateRail.sendUpdateC2S(rail, posStart, posEnd);
    }

    protected MutableComponent getModeTextComponent() {
        switch (getMode()) {
            case 0: return Text.translatable("tooltip.mtrsteamloco.rail.path_mode.original");
            case 1: return Text.translatable("tooltip.mtrsteamloco.rail.path_mode.bezier");
            default: return Text.translatable("tooltip.mtrsteamloco.rail.path_mode.unknown");
        }
    }
    
    public static class Original extends RailPathEditorScreen {

        public Original(BlockPos posStart, BlockPos posEnd, Rail rail, Supplier<Screen> parent) {
            super(posStart, posEnd, rail, parent);
        }
    }

    public static class Bezier extends RailPathEditorScreen {
        public Bezier(BlockPos posStart, BlockPos posEnd, Rail rail, Supplier<Screen> parent) {
            super(posStart, posEnd, rail, parent);
        }

        @Override
    #if MC_VERSION >= "12000"
        public void render(GuiGraphics in, int mouseX, int mouseY, float partialTick) {
    #else
        public void render(PoseStack in, int mouseX, int mouseY, float partialTick) {
    #endif
            super.render(in, mouseX, mouseY, partialTick);
            final MultiBufferSource.BufferSource immediate = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());

            

            immediate.endBatch();
        }
    }
}