package cn.zbx1425.mtrsteamloco.gui;

import mtr.client.IDrawing;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.Util;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import cn.zbx1425.mtrsteamloco.data.Tree;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
#if MC_VERSION >= "12000"
import net.minecraft.client.gui.GuiGraphics;
#endif

import cn.zbx1425.mtrsteamloco.gui.CompoundCreatorScreen.SliceTaskScreen.Square;
import cn.zbx1425.mtrsteamloco.util.PinyinUtils;
import me.shedaniel.cloth.clothconfig.shadowed.org.yaml.snakeyaml.events.Event.ID;

public class SelectScreen extends Screen {
    public static final Map<String, Integer> SCROLLING_MAP = new HashMap<>();

    protected Supplier<Screen> parent = null;

    protected int scrolling = 0;
    protected int maxScroll;
    protected int realityMaxScroll = 0;
    protected TreeMap<String, Button> buttons = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    protected TreeMap<String, Button> filteredButtons = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    protected Supplier<String> getSelected;
    protected MutableComponent screenKey;
    protected Button btnReturn = UtilitiesClient.newButton(20, Text.literal("X"), btn -> onClose());
    protected Runnable onMerge = null;
    protected Button btnMerge = UtilitiesClient.newButton(20, Text.translatable("gui.mtrsteamloco.select_screen.merge"), btn -> merge());
    protected EditBox searchField = new EditBox(Minecraft.getInstance().font, 0, 0, width / 3, 20, Text.literal(""));
    protected WidgetLabel lblInstruction;
    protected float alpha = 0.6F;

    public SelectScreen(Supplier<Screen> parent, MutableComponent screenKey, Map<String, String> map, Supplier<String> getSelected, SelectedCallback onSelected, String instructionLink) {
        super(Text.literal("SelectScreen"));
        this.parent = parent;
        this.screenKey = screenKey;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            final String key = entry.getKey();
            final String value = entry.getValue();
            Button button = UtilitiesClient.newButton(20, Text.translatable(value), btn -> onSelected.accept(minecraft, this, key));
            buttons.put(key, button);
        }
        scrolling = SCROLLING_MAP.getOrDefault(screenKey.getString(), 0);
        searchField.setResponder(this::filter);
        searchField.moveCursorToStart();

        lblInstruction = new WidgetLabel(0, 0, 0, screenKey.copy().append(Text.literal("\n").append(Text.translatable("tooltip.mtrsteamloco.select_screen.info"))), () -> {
            this.minecraft.setScreen(new ConfirmLinkScreen(bl -> {
                if (bl) {
                    Util.getPlatform().openUri(instructionLink);
                }
                this.minecraft.setScreen(this);
            }, instructionLink, true));
        });
        btnMerge.active = false;
        this.getSelected = getSelected;
    }

    public SelectScreen(Supplier<Screen> parent, MutableComponent screenKey, List<String> list, Supplier<String> getSelected, SelectedCallback onSelected, String instructionLink) {
        super(Text.literal("SelectScreen"));
        this.parent = parent;
        this.screenKey = screenKey;
        for (String value : list) {
            Button button = UtilitiesClient.newButton(20, Text.translatable(value), btn -> onSelected.accept(minecraft, this, value));
            buttons.put(value, button);
        }
        scrolling = SCROLLING_MAP.getOrDefault(screenKey.getString(), 0);
        searchField.setResponder(this::filter);
        searchField.moveCursorToStart();
        lblInstruction = new WidgetLabel(0, 0, 0, screenKey.copy().append(Text.literal("\n").append(Text.translatable("tooltip.mtrsteamloco.select_screen.info"))), () -> {
            this.minecraft.setScreen(new ConfirmLinkScreen(bl -> {
                if (bl) {
                    Util.getPlatform().openUri(instructionLink);
                }
                this.minecraft.setScreen(this);
            }, instructionLink, true));
        });
        btnMerge.active = false;
        this.getSelected = getSelected;
    }

    public <T> SelectScreen(Supplier<Screen> parent, Tree.Branch<T> tree, Supplier<String> getSelected, SelectedCallback onSelected, String instructionLink) {
        super(Text.literal("SelectScreen"));
        this.parent = parent;
        this.screenKey = Text.literal(tree.getPathKey());
        for (Tree.Node<T> node : tree.getNodes().values()) {
            Button button = null;
            if (node instanceof Tree.Data data) {
                button = UtilitiesClient.newButton(20, data.name, btn -> onSelected.accept(minecraft, this, data.key));
            } else if (node instanceof Tree.Branch branch) {
                button = UtilitiesClient.newButton(20, Text.literal("§e❇ ").append(branch.name), btn -> minecraft.setScreen(new SelectScreen(
                    () -> this, branch, getSelected, onSelected, instructionLink
                )));
            }
            if (button == null) continue;
            buttons.put(node.key, button);
        }
        scrolling = SCROLLING_MAP.getOrDefault(screenKey.getString(), 0);
        searchField.setResponder(this::filter);
        searchField.moveCursorToStart();
        lblInstruction = new WidgetLabel(0, 0, 0, tree.getPathName().append(Text.literal("\n").append(Text.translatable("tooltip.mtrsteamloco.select_screen.info"))), () -> {
            this.minecraft.setScreen(new ConfirmLinkScreen(bl -> {
                if (bl) {
                    Util.getPlatform().openUri(instructionLink);
                }
                this.minecraft.setScreen(this);
            }, instructionLink, true));
        });
        if (tree.hasSubBranches()) {
            onMerge = () -> {
                Map<String, Tree.Data<T>> leaves = tree.mergeLevel();
                Map<String, String> map = new HashMap<>();
                for (Map.Entry<String, Tree.Data<T>> entry : leaves.entrySet()) {
                    map.put(entry.getKey(), entry.getValue().name.getString());
                }
                minecraft.setScreen(new SelectScreen(() -> this, Text.translatable("gui.mtrsteamloco.select_screen.merged").append(tree.getPathName()), map, getSelected, onSelected, instructionLink));
            };
            btnMerge.active = true;
        } else {
            btnMerge.active = false;
        }
        this.getSelected = getSelected;
    }

    protected void merge() {
        if (onMerge != null) onMerge.run();
    }

    protected void filter(String filter) {
        if (filter.isEmpty()) {
            filteredButtons = new TreeMap<>(buttons);
            genScrollingLimits();
            return;
        }
        filter = filter.toLowerCase();
        filteredButtons.clear();
        for (Map.Entry<String, Button> entry : buttons.entrySet()) {
            final String str = entry.getValue().getMessage().getString().toLowerCase();
            final Button button = entry.getValue();
            final String key = entry.getKey();
            if (str.contains(filter)) {
                filteredButtons.put(key, button);
                continue;
            }
            String pinyin = PinyinUtils.getPinyin(str).toLowerCase();
            String initials = PinyinUtils.getPinyinInitials(str).toLowerCase();
            if (pinyin.contains(filter) || initials.contains(filter)) {
                filteredButtons.put(key, button);
            }
        }
        genScrollingLimits();
    }

    protected void genScrollingLimits() {
        maxScroll = filteredButtons.size() * 20;
        realityMaxScroll = Math.max(maxScroll - height + 20, 0);
        scrolling = Math.min(realityMaxScroll, Math.max(scrolling, 0));
    }

    @Override
    protected void init() {
        addWidget(searchField);
        addWidget(btnMerge);
        for (Button button : buttons.values()) {
            addWidget(button);
        }
        addWidget(btnReturn);
        IDrawing.setPositionAndWidth(btnReturn, width - 15 - 20, 15, 20);
        int w = Math.min(width / 3, 320);
        int w1 = (int) Math.round(w * 0.6F);
        IDrawing.setPositionAndWidth(searchField, 0, 0, w1);
        IDrawing.setPositionAndWidth(btnMerge, w1, 0, w - w1);
        genScrollingLimits();

        int w2 = Math.min(width / 2, 320);
        IDrawing.setPositionAndWidth(lblInstruction, w + (width - w - w2) / 2, height - 40, w2);
        addWidget(lblInstruction);
    }

    @Override
#if MC_VERSION >= "12000"
    public void render(GuiGraphics in, int mouseX, int mouseY, float partialTick) {
#else
    public void render(PoseStack in, int mouseX, int mouseY, float partialTick) {
#endif
        String selected = "";
        if (getSelected != null) selected = getSelected.get();
        if (selected == null) selected = "";
        for (Map.Entry<String, Button> entry : buttons.entrySet()) {
            final String key = entry.getKey();
            final Button button = entry.getValue();
            if (key.equals(selected)) {
                button.active = false;
            } else {
                button.active = true;
            }
            if (filteredButtons.containsKey(key)) {
                button.visible = true;
            } else {
                button.visible = false;
            }
        }

        int w = Math.min(width / 3, 320);
        vcEnableScissor(0, 20, width, height);
        int y = 20 -scrolling;
        for (Button button : filteredButtons.values()) {
            IDrawing.setPositionAndWidth(button, 0, y, w);
            button.render(in, mouseX, mouseY, partialTick);
            button.setAlpha(alpha);
            y += 20;
        }
        RenderSystem.disableScissor();

        btnReturn.setAlpha(alpha);
        searchField.setAlpha(alpha);
        btnMerge.setAlpha(alpha);
        lblInstruction.setAlpha(alpha);
        btnReturn.render(in, mouseX, mouseY, partialTick);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        searchField.render(in, mouseX, mouseY, partialTick);
        btnMerge.render(in, mouseX, mouseY, partialTick);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        lblInstruction.render(in, mouseX, mouseY, partialTick);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    public static void vcEnableScissor(int x1, int y1, int x2, int y2) {
        Window window = Minecraft.getInstance().getWindow();
        int wndHeight = window.getHeight();
        double guiScale = window.getGuiScale();
        double scaledX1 = (double)x1 * guiScale;
        double scaledY1 = (double)wndHeight - (double)y2 * guiScale;
        double scaledWidth = (double)(x2 - x1) * guiScale;
        double scaledHeight = (double)(y2 - y1) * guiScale;
        RenderSystem.enableScissor((int)scaledX1, (int)scaledY1, Math.max(0, (int)scaledWidth), Math.max(0, (int)scaledHeight));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        if (x <= width / 3) {
            scrolling = Math.min(Math.max(scrolling - (int) (amount * realityMaxScroll / 5), 0), realityMaxScroll);
            return true;
        }
        return super.mouseScrolled(x, y, amount);
    }

    @Override
    public void onClose() {
        SCROLLING_MAP.put(screenKey.getString(), scrolling);
        minecraft.setScreen(parent.get());
    }

    @Override
    public void tick() {
        searchField.tick();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public static interface SelectedCallback {
        void accept(Minecraft mc, SelectScreen screen, String selected);
    }
}
