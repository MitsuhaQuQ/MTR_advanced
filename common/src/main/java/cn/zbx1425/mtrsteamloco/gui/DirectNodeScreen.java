package cn.zbx1425.mtrsteamloco.gui;

import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import net.minecraft.core.BlockPos;
import mtr.client.IDrawing;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.events.GuiEventListener;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import mtr.data.RailType;
import cn.zbx1425.mtrsteamloco.ClientConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.world.level.block.entity.BlockEntity;
import cn.zbx1425.mtrsteamloco.data.RailExtraSupplier;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import mtr.block.BlockNode;
import net.minecraft.world.level.Level;
import mtr.data.Rail;
import cn.zbx1425.mtrsteamloco.block.BlockDirectNode.BlockEntityDirectNode;
import mtr.screen.WidgetBetterTextField;
import mtr.data.IGui;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
#if MC_VERSION >= "12000"
import net.minecraft.client.gui.GuiGraphics;
#endif
import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.ClientData;
import mtr.data.RailAngle;
import cn.zbx1425.mtrsteamloco.render.rail.BakedRail;

import java.util.ArrayList;
import java.util.function.Supplier;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class DirectNodeScreen extends Screen {

    public static Screen createScreen(Level world, BlockPos pos, Supplier<Screen> parent) {
        if (parent == null) parent = () -> null;
        BlockEntity entity = world.getBlockEntity(pos);
        if (entity == null) return parent.get();
        if (entity instanceof BlockEntityDirectNode e) {
            return new DirectNodeScreen(e, parent);
        } else {
            return parent.get();
        }
    }

    public static Screen createScreen(BlockEntityDirectNode entity, Supplier<Screen> parent) {
        return new DirectNodeScreen(entity, parent);
    }

    private BlockEntityDirectNode entity;
    private Supplier<Screen> parent;
    #if MC_VERSION >= "12000"
    private GuiGraphics matrices;
#else 
    private PoseStack matrices;
#endif
    private int mouseX, mouseY;
    private float partialTick;

    Button btnReturn = UtilitiesClient.newButton(Text.literal("X"), btn -> onClose());
    Button btnCirculateMode = UtilitiesClient.newButton(Text.literal("⇄"), btn -> switchMode(getMode() + 1));
    Button btnAdjust = UtilitiesClient.newButton(Text.translatable("gui.mtrsteamloco.adjust_settings"), btn -> {
        minecraft.setScreen(createAdjustScreen(() -> new DirectNodeScreen(entity, parent)));
    });
    Button btnUnbind = UtilitiesClient.newButton(Text.translatable("gui.mtrsteamloco.direct_node.unbind"), btn -> {
        entity.unbind();
        switchMode(getMode());
    });

    Pattern pattern;

    
    DirectNodeScreen(BlockEntityDirectNode entity, Supplier<Screen> parent) {
        super(Text.literal("Brush Edit Direct Node Screen"));
        this.entity = entity;
        this.parent = parent;
        switchMode(getMode());
    }

    public static int getMode() {
        return ClientConfig.directNodeScreenGroup.modes[0];
    }

    public static ClientConfig.Entry getEntry() {
        return ClientConfig.directNodeScreenGroup;
    }

    public static void setMode(int mode) {
        ClientConfig.directNodeScreenGroup.modes[0] = mode;
        ClientConfig.save();
    }

    private static RailAngle getRailAngle(BlockPos pos, Level world) {
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        BlockEntity entity = world.getBlockEntity(pos);

        if (!(block instanceof BlockNode)) return null;
        if (entity == null) {
            return RailAngle.fromAngle(BlockNode.getAngle(state));
        }
        if (!(entity instanceof BlockEntityDirectNode)) return RailAngle.fromAngle(BlockNode.getAngle(state));
        return ((BlockEntityDirectNode) entity).getRailAngle();
    }

    private void switchMode(int mode) {
        mode %= 2;
        if (mode != getMode()) {
            setMode(mode);
            ClientConfig.save();
            pattern = mode == 0 ? new DegreeSlider() : new DegreeTextField();
        }
        if (pattern == null) {
            pattern = mode == 0 ? new DegreeSlider() : new DegreeTextField();
        }
    }

    @Override
#if MC_VERSION >= "12000"
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
#else
    public void render(PoseStack guiGraphics, int mouseX, int mouseY, float partialTick) {
#endif
        this.matrices = guiGraphics;
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.partialTick = partialTick;
        IDrawing.setPositionAndWidth(btnReturn, 20, 20, 20);
        int w = Math.min(width - 40, 380);
        IDrawing.setPositionAndWidth(btnCirculateMode, width / 2 + w / 2 - 40, height - 80, 40);
        IDrawing.setPositionAndWidth(btnAdjust, width / 2 + w / 2 - 100, height - 80, 50);
        IDrawing.setPositionAndWidth(btnUnbind, width / 2 - w / 2, height - 80, 60);
        btnUnbind.active = entity.isBound() && !entity.isConnected();
        btnUnbind.render(matrices, mouseX, mouseY, partialTick);
        btnReturn.render(matrices, mouseX, mouseY, partialTick);
        btnAdjust.render(matrices, mouseX, mouseY, partialTick);
        btnCirculateMode.render(matrices, mouseX, mouseY, partialTick);
        pattern.render();
    }

    private void bindAngle(double degree) {
        entity.bind(degree);
    }

    public void onClose() {
        minecraft.setScreen(parent.get());
    }

    @Override
    public List<? extends GuiEventListener> children() {
        List<GuiEventListener> children = new ArrayList<>();
        children.addAll(super.children());
        children.add(btnReturn);
        children.add(btnCirculateMode);
        children.add(btnUnbind);
        children.add(btnAdjust);
        children.addAll(pattern.children());
        return children;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public static Screen createAdjustScreen(Supplier<Screen> parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(new FakeScreen(parent))
                .setTitle(Text.translatable("gui.mtrsteamloco.adjust_settings"))
                .setDoesConfirmSave(false)
                .setSavingRunnable(() -> {
                    ClientConfig.save();
                })
                .transparentBackground();
        ConfigEntryBuilder entryBuilder = builder.entryBuilder();
        ConfigCategory common = builder.getOrCreateCategory(
                Text.translatable("gui.mtrsteamloco.config.client.category.common")
        );

        List<AbstractConfigListEntry> entries = new ArrayList<>();
        ClientConfig.directNodeScreenGroup.getListEntries(entries, entryBuilder, () -> createAdjustScreen(parent));

        for (AbstractConfigListEntry entry : entries) {
            common.addEntry(entry);
        }

        return builder.build();
    }

    public interface Pattern {
        public void render();

        public List<? extends GuiEventListener> children();
    }

    public class DegreeSlider implements Pattern {
        WidgetSlider slider;
        float min, max;
        double now;
        int step;

        public DegreeSlider() {
            ClientConfig.Entry entry = getEntry();
            min = entry.min;
            max = entry.max;
            step = entry.step;
            now = entity.getAngleDegrees();
            slider = new WidgetSlider(step, getLevel(now), integer -> {
                double p = getValue(integer);
                if (p != now) {
                    setNow(p);
                }
                return "Degrees: " + String.format("%.2f", p);
            }); 
        }

        public int getLevel(double v) {
            return (int) Math.round((v - min) / ((max - min) / (double) step));
        }

        public void setNow(double now) {
            this.now = now;
            bindAngle(now);
        }

        public double getValue(int level) {
            return min + level * ((max - min) / (float) step);
        }

        @Override
        public void render() {
            int w = Math.min(width - 40, 380);
            IDrawing.setPositionAndWidth(slider, width / 2 - w / 2, DirectNodeScreen.this.height - 50, width / 2 + w / 2);
            slider.render(matrices, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return Collections.singletonList(slider);
        }
    }

    public class DegreeTextField implements Pattern {
        WidgetBetterTextField textField;
        double now = entity.getAngleDegrees();

        public DegreeTextField() {
            textField = new WidgetBetterTextField(String.format("%.1f", now), 10);
            textField.setResponder(s -> {
                double p = getNow();
                try {
                    double f = Double.parseDouble(s);
                    if (f != p) {
                        setNow(f);
                    }
                    textField.setTextColor(0xFFFFFFFF);
                } catch (NumberFormatException e) {
                    textField.setTextColor(0xFFFF0000);
                }
            });
            textField.setValue(String.format("%.1f", now));
            textField.moveCursorToStart();
        }


        @Override
        public void render() {
            int w = Math.min(width - 40, 380 - IGui.TEXT_FIELD_PADDING);
            IDrawing.setPositionAndWidth(textField, width / 2 - w / 2, DirectNodeScreen.this.height - 50, width / 2 + w / 2);
            textField.render(matrices, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return Collections.singletonList(textField);
        }

        public double getNow() {
            return now;
        }

        public void setNow(double now) {
            this.now = now;
            bindAngle(now);
        }
    }
}