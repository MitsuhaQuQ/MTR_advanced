package cn.zbx1425.mtrsteamloco.gui;

import cn.zbx1425.mtrsteamloco.Main;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.gui.Gui;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.Util;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.screens.Screen;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import net.minecraft.client.gui.components.Button;
import com.mojang.datafixers.util.Pair;
import me.shedaniel.clothconfig2.api.ScissorsHandler;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import mtr.screen.WidgetBetterTextField;
import net.minecraft.network.chat.FormattedText;
import cn.zbx1425.sowcer.math.PoseStackUtil;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.core.NonNullList;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.Tesselator;
import mtr.mappings.Text;
import net.minecraft.world.level.block.state.BlockState;
import cn.zbx1425.mtrsteamloco.network.PacketUpdateHoldingItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.client.gui.Font;
import me.shedaniel.math.Rectangle;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.util.Mth;
import cn.zbx1425.mtrsteamloco.data.*;
import mtr.client.IDrawing;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.StateDefinition;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import net.minecraft.network.chat.Component;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.components.EditBox;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.core.Direction;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import mtr.data.*;
import cn.zbx1425.mtrsteamloco.util.PinyinUtils;
import cn.zbx1425.mtrsteamloco.gui.entries.*;
import static cn.zbx1425.mtrsteamloco.item.CompoundCreator.*;

#if MC_VERSION >= "12000"
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.CreativeModeTabs;
#else
import net.minecraft.client.gui.GuiComponent;
#endif

#if MC_VERSION >= "11903"
import net.minecraft.core.registries.BuiltInRegistries;
#else
import net.minecraft.core.Registry;
#endif

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Comparator;
import java.util.Arrays;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.Iterator;
import java.util.Collection;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;

public class CompoundCreatorScreen extends Screen {
    public static Screen createScreen(Screen parent) {
        CompoundCreatorScreen screen = new CompoundCreatorScreen(parent);
        if (screen.load()) return screen;
        return parent;
    }

    private static final String TAG_TASKS = "tasks";
    public static final ResourceLocation BACKGROUND_LOCATION = new ResourceLocation("textures/gui/options_background.png");
    private static final ResourceLocation WHITE = new ResourceLocation("minecraft", "textures/block/white_concrete_powder.png");
    private Screen parent;
    private List<Entry> entries = new ArrayList<>();
    private Entry selectedEntry = null;
    private int scroll = 0; 
    private Rectangle scissor = null;
    private boolean draggingSlider = false;

    private Button btnAdd = UtilitiesClient.newButton(20, Text.literal("+"), button -> minecraft.setScreen(new TaskSelectScreen()));
    private Button btnRemove = UtilitiesClient.newButton(20, Text.literal("-"), button -> removeEntry());
    private Button btnCopy = UtilitiesClient.newButton(20, Text.translatable("gui.mtrsteamloco.compound_creator.copy"), button -> copyEntry());
    private Button btnClear = UtilitiesClient.newButton(20, Text.translatable("gui.mtrsteamloco.compound_creator.clear"), button -> clearEntries());
    private Button btnClose = UtilitiesClient.newButton(20, Text.literal("X"), button -> onClose());

    CompoundCreatorScreen(Screen parent) {
        super(Text.translatable(""));
        this.parent = parent;
    }

    public boolean load() {
        List<Task> tasks = new ArrayList<>();
        if (getTag() == null) return false;
        CompoundTag tag = getTag();
        if (!tag.contains(TAG_TASKS)) return true;
        CompoundTag tasksTag = tag.getCompound(TAG_TASKS).copy();
        for (String key : tasksTag.getAllKeys()) {
            CompoundTag task = tasksTag.getCompound(key);
            String type = task.getString(Task.TAG_TYPE);
            if (type.equals(SliceTask.TYPE)) {
                tasks.add(new SliceTask(task));
            } else if (type.equals(RailModifierTask.TYPE)) {
                tasks.add(new RailModifierTask(task));
            } else {
                Main.LOGGER.error("Unknown task type: " + type);
            }
        }
        tasks.sort(Comparator.comparingInt(task -> task.order));
        List<Entry> entries = new ArrayList<>();
        for (Task task : tasks) {
            entries.add(new Entry(task));
        }
        this.entries = entries;
        selectedEntry = entries.isEmpty()? null : entries.get(0);
        return true;
    }

    public static CompoundTag getTag() {
        if (Minecraft.getInstance().player == null) return null;
        ItemStack item = Minecraft.getInstance().player.getMainHandItem();
        if (!item.is(Main.COMPOUND_CREATOR.get())) return null;
        CompoundTag tag = item.getOrCreateTag();
        return tag;
    }

    public static void updateTag(Consumer<CompoundTag> modifier) {
        if (Minecraft.getInstance().player == null) return;
        ItemStack item = Minecraft.getInstance().player.getMainHandItem();
        if (!item.is(Main.COMPOUND_CREATOR.get())) return;
        CompoundTag tag = item.getOrCreateTag();
        modifier.accept(tag);
        PacketUpdateHoldingItem.sendUpdateC2S();
    }

    @Override
    protected void init() {
    }

    private void update() {
        updateTag(tag -> {
            List<Entry> copy = new ArrayList<>(entries);
            CompoundTag tasksTag = new CompoundTag();
            for (int i = 0; i < copy.size(); i++) {
                copy.get(i).task.order = i;
                tasksTag.put(i + "", copy.get(i).task.toCompoundTag());
            }
            tag.put(TAG_TASKS, tasksTag);
        });
    }


    private void clearEntries() {
        entries.clear();
        selectedEntry = null;
        update();
    }

    private void addEntry(Task task) {
        Entry entry = new Entry(task);
        entries.add(entry);
        selectedEntry = entry;
        update();
    }

    private void removeEntry() {
        if (selectedEntry == null) return;
        entries.remove(selectedEntry);
        if (entries.isEmpty()) selectedEntry = null;
        else selectedEntry = entries.get(0);
        update();
    }

    private void copyEntry() {
        if (selectedEntry == null) return;
        Entry entry = null;
        if (selectedEntry.task instanceof SliceTask) {
            entry = new Entry(new SliceTask((SliceTask) selectedEntry.task));
        } else if (selectedEntry.task instanceof RailModifierTask) {
            entry = new Entry(new RailModifierTask((RailModifierTask) selectedEntry.task));
        } else {
            Main.LOGGER.error("Unknown task type: " + selectedEntry.task.getClass());
            return;
        }
        entries.add(entry);
        selectedEntry = entry;
        update();
    }

    @Override
#if MC_VERSION >= "12000"
    public void render(GuiGraphics matrices, int mouseX, int mouseY, float partialTick) {
#else
    public void render(PoseStack matrices, int mouseX, int mouseY, float partialTick) {
#endif
        renderDirtBackground(this, matrices);
        super.render(matrices, mouseX, mouseY, partialTick);
        fill(matrices, 0, 38, width, height - 38, 0x90000000);
        
        drawCenteredString(matrices, minecraft.font, Text.translatable("gui.mtrsteamloco.compound_creator.title").getString() , width / 2, 18, 0xFFFFFFFF);
        IDrawing.setPositionAndWidth(btnAdd, width - 50, 60, 40);
        IDrawing.setPositionAndWidth(btnRemove, width - 50, 90, 40);
        IDrawing.setPositionAndWidth(btnCopy, width - 50, 120, 40);
        IDrawing.setPositionAndWidth(btnClear, width - 50, 150, 40);
        IDrawing.setPositionAndWidth(btnClose, 10, 10, 20);
        btnAdd.render(matrices, mouseX, mouseY, partialTick);
        btnRemove.render(matrices, mouseX, mouseY, partialTick);
        btnCopy.render(matrices, mouseX, mouseY, partialTick);
        btnClear.render(matrices, mouseX, mouseY, partialTick);
        btnClose.render(matrices, mouseX, mouseY, partialTick);
        scissor = new Rectangle(0, 40, width, height - 80);
        ScissorsHandler.INSTANCE.scissor(scissor);
        checkAndScroll(scroll);
        int y = 40 + scroll;
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).render(matrices, mouseX, mouseY, i, y, partialTick);
            y += entries.get(i).height();
        }
        ScissorsHandler.INSTANCE.clearScissors();
        if (canScoll()) {
            int[] pas = getSliderPositionAndSize();
            blit(matrices, WHITE, pas[0], pas[1], pas[2], pas[3]);
        }
    }

    private void setScroll(int mouseY) {
        int[] pas = getSliderPositionAndSize();
        int sh = pas[3];
        int maxd = scissor.height - sh;
        int dy = mouseY - sh / 2 - scissor.y;
        int maxScroll = -entries.size() * Entry.height() + scissor.height;
        checkAndScroll((int) (dy / ((float) maxd) * maxScroll));
    }

    private void checkAndScroll(int temp) {
        if (!canScoll()) {
            scroll = 0;
            return;
        }
        if (temp > 0) temp = 0;
        int min = -entries.size() * Entry.height() + scissor.height;
        if (temp < min) temp = min;
        scroll = temp;
    }


    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        if (!canScoll()) return super.mouseScrolled(x, y, amount);
        if (scissor.x <= x && x <= scissor.x + scissor.width && scissor.y <= y && y <= scissor.y + scissor.height) {
            checkAndScroll(scroll + 10 * (int) amount);
            return true;
        }
        return super.mouseScrolled(x, y, amount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int i) {
        if (!canScoll()) return super.mouseClicked(mouseX, mouseY, i);
        int[] spas = getSliderPositionAndSize();
        if (isMouseOverSlider(mouseX, mouseY)) {
            setScroll((int) mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, i);
    }

    @Override
    public boolean mouseDragged(double sx, double sy, int i, double tx, double ty) {
        if (isMouseOverSlider(sx, sy) || draggingSlider) {
            setScroll((int) (sy + ty));
            draggingSlider = true;
            return true;
        }
        return super.mouseDragged(sx, sy, i, tx, ty);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int i) {
        if (draggingSlider) {
            draggingSlider = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, i);
    }

    private boolean isMouseOverSlider(double mouseX, double mouseY) {
        if (!canScoll()) return false;
        int[] pas = getSliderPositionAndSize();
        return pas[0] <= mouseX && mouseX <= pas[0] + pas[2] && pas[1] <= mouseY && mouseY <= pas[1] + pas[3];
    }

    private int[] getSliderPositionAndSize() {
        float ah = (float) entries.size() * Entry.height();
        float th = (float) scissor.height;
        int h = (int) (th / ah * th);
        int py = scissor.y + (int) (-1F * scroll / ah * th);
        return new int[]{Entry.x() + Entry.width(width), py, (int) (width * 0.01F), h};
    }

    private boolean canScoll() {
        return entries.size() * Entry.height() > scissor.height;
    }

    @Override
    public List<? extends GuiEventListener> children() {
        List<GuiEventListener> result = new ArrayList<>();
        result.addAll(super.children());
        for (Entry entry : entries) {
            result.addAll(entry.children());
        }
        // result.add(title);
        result.add(btnAdd);
        result.add(btnRemove);
        result.add(btnCopy);
        result.add(btnClear);
        result.add(btnClose);
        return result;
    }

#if MC_VERSION >= "12000"
    private static void drawText(GuiGraphics guiGraphics, Font font, FormattedCharSequence text, int x, int y, int color) {
        guiGraphics.drawString(font, text, x, y, color);
    }

    private static void blit(GuiGraphics guiGraphics, ResourceLocation texture, int x, int y, int width, int height) {
        guiGraphics.blit(texture, x, y, width, height, 0, 0, 1, 1, 1, 1);
    }

    private static void fill(GuiGraphics guiGraphics, int x, int y, int width, int height, int color) {
        guiGraphics.fill(x, y, width, height, color);
    }

    private static void drawCenteredString(GuiGraphics guiGraphics, Font font, String text, int x, int y, int color) {
        guiGraphics.drawCenteredString(font, text, x, y, color);
    }

    public static void renderDirtBackground(Screen screen, GuiGraphics guiGraphics) {
        screen.renderDirtBackground(guiGraphics);
    }
#else
    private static void drawText(PoseStack matrices, Font font, FormattedCharSequence text, int x, int y, int color) {
        font.drawShadow(matrices, text, x, y, color);
    }

    private static void blit(PoseStack matrices, ResourceLocation texture, int x, int y, int width, int height) {
        RenderSystem.setShaderTexture(0, texture);
        GuiComponent.blit(matrices, x, y, width, height, 0, 0, 1, 1, 1, 1);
    }

#if MC_VERSION >= "11904"
    public static void renderDirtBackground(Screen screen, PoseStack matrices) {
        screen.renderDirtBackground(matrices);
    }
#else 
    public static void renderDirtBackground(Screen screen, PoseStack matrices) {
        screen.renderDirtBackground(0);
    }
#endif
#endif

    @Override
    public void onClose() {
        update();
        minecraft.setScreen(parent);
    }

    public class Entry implements GuiEventListener{
        public Task task;
        private int y;
        private static int x = 20;
        private static int height = 20;
        private WidgetBetterTextField nameField = new WidgetBetterTextField("", 256);
        private Button enter = UtilitiesClient.newButton(Text.literal("Enter"), btn -> enter());
        private Button up = UtilitiesClient.newButton(Text.literal("▲"), btn -> moveUp());
        private Button down = UtilitiesClient.newButton(Text.literal("▼"), btn -> moveDown());
        private List<GuiEventListener> children = Arrays.asList(nameField, enter, up, down, this);

        public Entry(Task task) {
            this.task = task;
            nameField.setResponder(this::updateName);
            nameField.setValue(task.name);
            nameField.moveCursorToStart();
        }

        public List<? extends GuiEventListener> children() {
            return children;
        }

        public void enter() {
            if (task instanceof SliceTask) {
                minecraft.setScreen(new SliceTaskScreen((SliceTask) task));    
            } else if (task instanceof RailModifierTask) {
                setRailModifierScreen((RailModifierTask) task);
            } else {
                Main.LOGGER.error("Unknown task type: " + task.getClass().getName());
            }
        }

        public void updateName(String name) {
            task.name = name;
            update();
            selectedEntry = this;
        }

        public void moveUp() {
            int index = entries.indexOf(this);
            if (index > 0) {
                entries.set(index, entries.get(index - 1));
                entries.set(index - 1, this);
                update();
            }
            selectedEntry = this;
        }

        private boolean isFocused = false;
        
        public boolean isFocused() {
            return isFocused;
        }

        public void setFocused(boolean focused) {
            isFocused = focused;
        }

        public void moveDown() {
            int index = entries.indexOf(this);
            if (index < entries.size() - 1) {
                entries.set(index, entries.get(index + 1));
                entries.set(index + 1, this);
                update();
            }
            selectedEntry = this;
        }

    #if MC_VERSION >= "12000"
        public void render(GuiGraphics matrices, int mouseX, int mouseY, int num, int y, float partialTick) {
    #else
        public void render(PoseStack matrices, int mouseX, int mouseY, int num, int y, float partialTick) {
    #endif
            this.y = y;
            if (isSelected()) {
                fill(matrices, 0, y(), width - 55, y() + height(), 0xa0eeeeee);
            } else if (isMouseOver(mouseX, mouseY)) {
                fill(matrices, 0, y(), width - 55, y() + height(), 0x40eeeeee);
            }
            y += 2;
            int count = width() / 20;
            int x = x();
            IDrawing.setPositionAndWidth(nameField, x, y, count * 7);
            x += count * 8;
            IDrawing.setPositionAndWidth(enter, x, y, count * 6);
            x += count * 7;
            IDrawing.setPositionAndWidth(up, x, y, count * 2);
            x += count * 3;
            IDrawing.setPositionAndWidth(down, x, y, count * 2);
            nameField.render(matrices, mouseX, mouseY, partialTick);
            enter.render(matrices, mouseX, mouseY, partialTick);
            up.render(matrices, mouseX, mouseY, partialTick);
            down.render(matrices, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            if (x() <= mouseX && mouseX <= x() + width() && y() <= mouseY && mouseY <= y() + height()) {
                return true;
            }
            return false;
        }

        public static int x() {
            return 20;
        }

        public int y() {
            return y;
        }

        public int width() {
            return width - 80;
        }

        public static int width(int width) {
            return width - 80;
        }

        public static int height() {
            return 24;
        }

        public boolean isSelected() {
            return selectedEntry == this;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int i) {
            if (isMouseOver(mouseX, mouseY)) {
                selectedEntry = this;
                return true;
            } else {
                return false;
            }
        }
    }

    public class TaskSelectScreen extends Screen {
        private Button btnReturn = UtilitiesClient.newButton(Text.literal("X"), btn -> onClose());
        private Button btnNewRailModifier = UtilitiesClient.newButton(Text.translatable("gui.mtrsteamloco.compound_creator.rail_modifier"), btn -> {
            RailModifierTask task = new RailModifierTask();
            addEntry(task);
            minecraft.setScreen(CompoundCreatorScreen.this);
        });
        private Button btnNewSliceTask = UtilitiesClient.newButton(Text.translatable("gui.mtrsteamloco.compound_creator.slice_task"), btn -> {
            SliceTask task = new SliceTask();
            addEntry(task);
            minecraft.setScreen(CompoundCreatorScreen.this);
        });

        public TaskSelectScreen() {
            super(Text.literal("Select Task"));
        }

        public void init() {
            IDrawing.setPositionAndWidth(btnReturn, 10, 10, 20);
            IDrawing.setPositionAndWidth(btnNewRailModifier, width / 2 - 150, 50, 300);
            IDrawing.setPositionAndWidth(btnNewSliceTask, width / 2 - 150, 80, 300);
            addRenderableWidget(btnReturn);
            addRenderableWidget(btnNewRailModifier);
            addRenderableWidget(btnNewSliceTask);
        }

    #if MC_VERSION >= "12000"
        public void render(GuiGraphics matrices, int mouseX, int mouseY, float partialTick) {
    #else 
        public void render(PoseStack matrices, int mouseX, int mouseY, float partialTick) {
    #endif
            IDrawing.setPositionAndWidth(btnNewRailModifier, width / 2 - 150, 50, 300);
            IDrawing.setPositionAndWidth(btnNewSliceTask, width / 2 - 150, 80, 300);
            CompoundCreatorScreen.renderDirtBackground(this, matrices);
            super.render(matrices, mouseX, mouseY, partialTick);
        }

        public void onClose() {
            minecraft.setScreen(CompoundCreatorScreen.this);
        }
    }

    public Screen newRailModifierScreen(RailModifierTask task0) {
        RailModifierTask task = new RailModifierTask(task0);
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(this)
                .setTitle(Text.translatable("轨道修改器"))
                .setDoesConfirmSave(false)
                .transparentBackground()
                .setSavingRunnable(() -> {
                    task0.copyFrom(task);
                    this.update();
                });
        ConfigEntryBuilder entryBuilder = builder.entryBuilder();
        ConfigCategory common = builder.getOrCreateCategory(
                Text.translatable("gui.mtrsteamloco.config.client.category.common")
        );

        Rail rail = task.rail;
        RailExtraSupplier extra = (RailExtraSupplier) rail;

        String modelKey = extra.getModelKey();
        RailModelProperties properties = RailModelRegistry.ELEMENTS.get(modelKey);
        Button btnEnterSelect = UtilitiesClient.newButton(Text.translatable("gui.mtrsteamloco.brush_edit_rail.present", (properties != null ? (properties.name.getString()) : (modelKey + " (???)"))), btn -> {
            Minecraft.getInstance().setScreen(new SelectScreen(task, () -> {
                task0.copyFrom(task);
                setRailModifierScreen(task0);
            }));
        });
        btnEnterSelect.setWidth(300);
        common.addEntry(new ButtonListEntry(
            Text.literal(""),
            btnEnterSelect,
            (e, b, a1, a2, a3, a4, a5, a6, a7, a8, a9) -> {
                Window window = Minecraft.getInstance().getWindow();
                UtilitiesClient.setWidgetX(b, window.getGuiScaledWidth() / 2 - 150);
            }
        ));

        common.addEntry(
            entryBuilder.startTextField(
                Text.translatable("gui.mtrsteamloco.brush_edit_rail.rail_type"),
                rail.railType.name()
            ).setErrorSupplier(str -> {
                try {
                    RailType type = RailType.valueOf(str);
                    if (type != null && type != RailType.NONE) return Optional.empty();
                } catch (Exception e) {}
                return Optional.of(Text.translatable("gui.mtrsteamloco.brush_edit_rail.rail_type_error"));
            }
            ).setSaveConsumer(str -> {
                extra.setRailType(RailType.valueOf(str));
            }).build()
        );

        common.addEntry(
            entryBuilder.startBooleanToggle(
                Text.translatable("gui.mtrsteamloco.compound_creator.isOneWay"),
                task.isOneWay
            ).setSaveConsumer(
                isOneWay -> {
                    task.isOneWay = isOneWay;
                }
            ).setDefaultValue(false).build()
        );

        common.addEntry(
            entryBuilder.startBooleanToggle(
                Text.translatable("gui.mtrsteamloco.compound_creator.isReversed"),
                task.isReversed
            ).setSaveConsumer(
                isReversed -> {
                    task.isReversed = isReversed;
                }
            ).setDefaultValue(false).build()
        );

        common.addEntry(new RollAnglesListEntry(rail, false, res -> this.update(), f -> update(), () -> newRailModifierScreen(task)));

        common.addEntry(
            entryBuilder.startIntSlider(
                Text.translatable("开门方向"),
                extra.getOpeningDirection(),
                0, 3
            )
            .setTooltipSupplier(v -> Optional.of(new Component[]{Text.translatable("gui.mtrsteamloco.brush_edit_rail.opening_direction_tooltip")}))
            .setDefaultValue(0)
            .setSaveConsumer(value -> {
                extra.setOpeningDirection(value);
            }).build()
        );

        Map<String, String> customConfigs = extra.getCustomConfigs();
        Map<String, ConfigResponder> responders = extra.getCustomResponders();
        List<AbstractConfigListEntry> entries = ConfigResponder.getEntrysFromMaps(customConfigs, responders, entryBuilder, () -> newRailModifierScreen(task));
        for (AbstractConfigListEntry entry : entries) {
            common.addEntry(entry);
        }

        Screen screen = builder.build();
        return screen;
    }

    public void setRailModifierScreen(RailModifierTask task) {
        Minecraft.getInstance().setScreen(newRailModifierScreen(task));
    }

    private class SelectScreen extends SelectListScreen {

        private static final String INSTRUCTION_LINK = "https://aphrodite281.github.io/mtr-ante/#/railmodel";
        private final WidgetLabel lblInstruction = new WidgetLabel(0, 0, 0, Text.translatable("gui.mtrsteamloco.eye_candy.tip_resource_pack"), () -> {
            this.minecraft.setScreen(new ConfirmLinkScreen(bl -> {
                if (bl) {
                    Util.getPlatform().openUri(INSTRUCTION_LINK);
                }
                this.minecraft.setScreen(this);
            }, INSTRUCTION_LINK, true));
        });
        private Rail rail;
        private RailModifierTask task;
        private RailExtraSupplier extra;
        private Runnable returnParent;

        public SelectScreen(RailModifierTask task, Runnable returnParent) {
            super(Text.literal("Select rail arguments"));
            this.task = task;
            this.rail = task.rail;
            this.extra = (RailExtraSupplier) rail;
            this.returnParent = returnParent;
        }

        @Override
        protected void init() {
            super.init();

            loadPage();
        }

        @Override
        protected void loadPage() {
            clearWidgets();

            
            String modelKey = extra.getModelKey();
            scrollList.visible = true;
            loadSelectPage(key -> !key.equals(modelKey));
            lblInstruction.alignR = true;
            IDrawing.setPositionAndWidth(lblInstruction, width / 2 + SQUARE_SIZE, height - SQUARE_SIZE - TEXT_HEIGHT, 0);
            lblInstruction.setWidth(width / 2 - SQUARE_SIZE * 2);
            addRenderableWidget(lblInstruction);
        }

        @Override
        protected void onBtnClick(String btnKey) {
            extra.setModelKey(btnKey);
            task.tryCallRailScript();
        }

        @Override
        protected List<Pair<String, String>> getRegistryEntries() {
            return new HashSet<>(RailModelRegistry.ELEMENTS.entrySet()).stream()
                    .filter(e -> !e.getValue().name.getString().isEmpty())
                    .map(e -> new Pair<>(e.getKey(), e.getValue().name.getString()))
                    .toList();
        }

        @Override
    #if MC_VERSION >= "12000"
        public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
    #else
        public void render(PoseStack guiGraphics, int mouseX, int mouseY, float partialTick) {
    #endif
            this.renderBackground(guiGraphics);
            super.render(guiGraphics, mouseX, mouseY, partialTick);

            renderSelectPage(guiGraphics);
        }

        @Override
        public void onClose() {
            returnParent.run();
        }

        @Override
        public boolean isPauseScreen() {
            return true;
        }
    }

    public class SliceTaskScreen extends Screen {
        public SliceTask task;
        protected int tx = 0;
        protected int ty = 0;
        private Button btnReturn = UtilitiesClient.newButton(Text.literal("X"), btn -> onClose());
        private Rectangle scissor = new Rectangle(0, 0, 0, 0);
        private List<Square> canvas = new ArrayList<>();
        private Inventory inventory = new Inventory();

        private WidgetBetterTextField nameField = new WidgetBetterTextField("", 256);
        private Button btnAddWidth = UtilitiesClient.newButton(Text.literal("+"), btn -> setWidthAndHeight(task.width + 2, task.height));
        private Button btnSubWidth = UtilitiesClient.newButton(Text.literal("-"), btn -> setWidthAndHeight(task.width - 2, task.height));
        private Button btnAddHeight = UtilitiesClient.newButton(Text.literal("+"), btn -> setWidthAndHeight(task.width, task.height + 2));
        private Button btnSubHeight = UtilitiesClient.newButton(Text.literal("-"), btn -> setWidthAndHeight(task.width, task.height - 2));
        private Button btnSubTX = UtilitiesClient.newButton(Text.literal("◁"), btn -> setTX(tx + 4 * Square.length));
        private Button btnAddTX = UtilitiesClient.newButton(Text.literal("▷"), btn -> setTX(tx - 4 * Square.length));
        private Button btnSubTY = UtilitiesClient.newButton(Text.literal("▲"), btn -> setTY(ty + 4 * Square.length));
        private Button btnAddTY = UtilitiesClient.newButton(Text.literal("▼"), btn -> setTY(ty - 4 * Square.length));
        private Button btnCenter = UtilitiesClient.newButton(Text.literal("▣"), btn -> {tx = 0; ty = 0;});

        private Button btnEnterConfig = UtilitiesClient.newButton(Text.literal("配置"), btn -> setConfigScreen());

        private Square mouseOver = null;

        private Square now = new Square(0, 0, null, square -> square.state = null, square -> true, square -> true, false, true);

        public SliceTaskScreen(SliceTask task) {
            super(Text.translatable(""));
            this.task = task;
            reload();
        }

        private void setWidthAndHeight(int width, int height) {
            if (!task.setWidthAndHeight(width, height)) return;
            update();
            reload();
        }

        private void updateTask() {
            for (int i = 0; i < canvas.size(); i++) {
                Lump lump = task.lumps.get(i);
                Square sq = canvas.get(i);
                lump.blockState = sq.state;
                lump.replacement = sq.replacement;
            }
            update();
        }

        private void reload() {
            List<Square> canvas = new ArrayList<>();
            Consumer<Square> consumer = square -> {
                square.state = now.state;
                square.replacement = now.replacement;
                updateTask();
            };
            Function<Square, Boolean> visible = sq -> {
                int l = Square.length;
                Rectangle s = scissor;
                return sq.x + l >= s.x && sq.x <= s.x + s.width && sq.y + l >= s.y && sq.y <= s.y + s.height;
            };
            for (Lump lump : task.lumps) {
                canvas.add(new Square(0, 0, lump.blockState, consumer, square -> true, visible, false, lump.replacement));
            }
            this.canvas = canvas;
        }

        @Override
        protected void init() {
            nameField.setValue(task.name);
            nameField.moveCursorToStart();

            nameField.setResponder(str -> {
                task.name = str;
                updateTask();   
            });
            updateWidgetPosition();
            addRenderableWidget(btnReturn);
            addRenderableWidget(nameField);
            addRenderableWidget(btnAddWidth);
            addRenderableWidget(btnSubWidth);
            addRenderableWidget(btnAddHeight);
            addRenderableWidget(btnSubHeight);
            addRenderableWidget(btnSubTX);
            addRenderableWidget(btnAddTX);
            addRenderableWidget(btnSubTY);
            addRenderableWidget(btnAddTY);
            addRenderableWidget(btnCenter);
            addRenderableWidget(btnEnterConfig);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            List<GuiEventListener> result = new ArrayList<>();
            result.addAll(super.children());
            result.addAll(canvas);
            result.addAll(inventory.children());
            result.add(now);
            return result;
        }

        @Override
    #if MC_VERSION >= "12000"
        public void render(GuiGraphics matrices, int mouseX, int mouseY, float partialTick) {
    #else
        public void render(PoseStack matrices, int mouseX, int mouseY, float partialTick) {
    #endif
            CompoundCreatorScreen.renderDirtBackground(this, matrices);
            super.render(matrices, mouseX, mouseY, partialTick);
            setTY(ty);
            setTX(tx);
            mouseOver = null;
            updateWidgetPosition();
            Rectangle full = new Rectangle(40, 40, width - 40 - 10 - Inventory.width, height - 40 - 40);
            scissor = new Rectangle(60 + 2, 50 + 2, width - 60 - 10 - Inventory.width - 2 - 2, height - 50 - 40 - 2 - 2);

            float midX = this.tx + scissor.x + scissor.width / 2.0F;
            float midY = this.ty + scissor.y + scissor.height / 2.0F;
            int x = (int) (midX - task.width / 2.0F * Square.length);
            int y = (int) (midY - task.height / 2.0F * Square.length);

            fill(matrices, full.x - 1, full.y - 1, full.x + full.width + 1, full.y + full.height + 1, full.contains(mouseX, mouseY) ? 0xfff0eacc : 0xff0c0d0b);
            fill(matrices, full.x, full.y, full.x + full.width, full.y + full.height, 0xff424242);

            fill(matrices, scissor.x - 1, scissor.y - 1, scissor.x + scissor.width + 1, scissor.y + scissor.height + 1, scissor.contains(mouseX, mouseY) ? 0xffd1b2b2 : 0xff403636);
            fill(matrices, scissor.x, scissor.y, scissor.x + scissor.width, scissor.y + scissor.height, 0xff8f5d5d);
            
            ScissorsHandler.INSTANCE.scissor(new Rectangle(scissor.x, full.y, scissor.width, 12));
            int a = task.width / 2;
            int in = 3;
            int ay = 42;
            drawCenteredString(matrices, minecraft.font, "0", (int) midX, ay, 0xFFFFFFFF);
            if (a < 1) ;
            else if (a < in) {
                drawCenteredString(matrices, minecraft.font, "+" + a, (int) midX + a * Square.length, ay, 0xFFFFFFFF);
                drawCenteredString(matrices, minecraft.font, "-" + a, (int) midX - a * Square.length, ay, 0xFFFFFFFF);
            } else {
                for (int i = in; i <= a; i += in) {
                    drawCenteredString(matrices, minecraft.font, "+" + i, (int) midX + i * Square.length, ay, 0xFFFFFFFF);
                    drawCenteredString(matrices, minecraft.font, "-" + i, (int) midX - i * Square.length, ay, 0xFFFFFFFF);
                }
            }
            ScissorsHandler.INSTANCE.clearScissors();

            ScissorsHandler.INSTANCE.scissor(new Rectangle(full.x, scissor.y, 22, scissor.height));
            int b = task.height / 2;
            int ax = 50;
            drawCenteredString(matrices, minecraft.font, "0", ax, (int) midY - 5, 0xFFFFFFFF);
            if (b < 1) ;
            else if (b < in) {
                drawCenteredString(matrices, minecraft.font, "+" + b, ax, (int) midY - 5 + b * Square.length, 0xFFFFFFFF);
                drawCenteredString(matrices, minecraft.font, "-" + b, ax, (int) midY - 5 - b * Square.length, 0xFFFFFFFF);
            } else {
                for (int i= in; i <= b; i += in) {
                    drawCenteredString(matrices, minecraft.font, "-" + i, ax, (int) midY - 5 + i * Square.length, 0xFFFFFFFF);
                    drawCenteredString(matrices, minecraft.font, "+" + i, ax, (int) midY - 5 - i * Square.length, 0xFFFFFFFF);
                }
            }
            ScissorsHandler.INSTANCE.clearScissors();

            ScissorsHandler.INSTANCE.scissor(scissor);
            for (int i = 0; i < canvas.size(); i++) {
                canvas.get(i).render(matrices, mouseX, mouseY, x + i % task.width * Square.length, y + i / task.width * Square.length, partialTick);
            }
            int py = (int) midY - 18 / 2 - 1;
            fill(matrices, x , py , x + task.width * Square.length, py + 2, 0x7FFF0000);
            py += 18;
            fill(matrices, x , py , x + task.width * Square.length, py + 2, 0x7FFF0000);
            int px = (int) midX - 18 / 2 - 1;
            fill(matrices, px, y , px + 2, y + task.height * Square.length, 0x7F00FF00);
            px += 18;
            fill(matrices, px, y , px + 2, y + task.height * Square.length, 0x7F00FF00);
            ScissorsHandler.INSTANCE.clearScissors();

            now.render(matrices, mouseX, mouseY, 191, 11, partialTick);

            inventory.render(matrices, mouseX, mouseY, partialTick);
            
            if (mouseOver != null) {
                mouseOver.renderTooltip(matrices, mouseX, mouseY, partialTick);
            }
            // drawCenteredString(matrices, minecraft.font, task.width + "x" + task.height, (200 + width - Inventory.width) / 2, 11, 0xFFFFFFFF);
        }

        private void setConfigScreen() {
            ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(this)
                .setTitle(Text.translatable("gui.mtrsteamloco.compound_creator.task.config.title"))
                .setDoesConfirmSave(false)
                .transparentBackground();
            ConfigEntryBuilder entryBuilder = builder.entryBuilder();
            ConfigCategory common = builder.getOrCreateCategory(
                    Text.translatable("gui.mtrsteamloco.config.client.category.common")
            );

            Function<Double, Optional<Component>> positive = d -> {
                if (d <= 0) {
                    return Optional.of(Text.translatable("gui.mtrsteamloco.error.invalid_value"));
                }
                return Optional.empty();
            };

            common.addEntry(entryBuilder
                .startDoubleField(
                    Text.translatable("gui.mtrsteamloco.compound_creator.task.config.start_pos"),
                    task.start
                ).setDefaultValue(0)
                .setSaveConsumer(d -> {
                    task.start = d;
                })
                .setMin(0)
                .build()
            );

            common.addEntry(entryBuilder
                .startDoubleField(
                    Text.translatable("gui.mtrsteamloco.compound_creator.task.config.length"),
                    task.length == null ? -1 : task.length
                ).setDefaultValue(-1)
                .setSaveConsumer(d -> {
                    if (d <= 0) {
                        task.length = null;
                    } else {
                        task.length = d;
                    }
                })
                .build()
            );

            common.addEntry(entryBuilder
                .startDoubleField(
                    Text.translatable("gui.mtrsteamloco.compound_creator.task.config.interval"),
                    task.interval == null ? -1 : task.interval
                ).setDefaultValue(-1)
                .setSaveConsumer(d -> {
                    if (d <= 0) {
                        task.interval = null;
                    } else {
                        task.interval = d;
                    }
                }).build()
            );

            common.addEntry(entryBuilder
                .startDoubleField(
                    Text.translatable("gui.mtrsteamloco.compound_creator.task.config.increment"),
                    task.increment
                ).setDefaultValue(0.1)
                .setSaveConsumer(d -> {
                    task.increment = d;
                })
                .setErrorSupplier(positive)
                .build()
            );

            common.addEntry(entryBuilder
                .startBooleanToggle(
                    Text.translatable("gui.mtrsteamloco.compound_creator.task.config.use_yaw"),
                    task.useYaw
                ).setDefaultValue(true)
                .setSaveConsumer(b -> {
                    task.useYaw = b;
                })
                .build()
            );

            common.addEntry(entryBuilder
                .startBooleanToggle(
                    Text.translatable("gui.mtrsteamloco.compound_creator.task.config.use_pitch"),
                    task.usePitch
                ).setDefaultValue(true)
                .setSaveConsumer(b -> {
                    task.usePitch = b;
                })
                .build()
            );

            common.addEntry(entryBuilder
                .startBooleanToggle(
                    Text.translatable("gui.mtrsteamloco.compound_creator.task.config.use_roll"),
                    task.useRoll
                ).setDefaultValue(false)
                .setSaveConsumer(b -> {
                    task.useRoll = b;
                })
                .build()
            );

            builder.setSavingRunnable(() -> {
                updateTask();
            });

            minecraft.setScreen(builder.build());
        }

        private void updateWidgetPosition() {
            IDrawing.setPositionAndWidth(btnReturn, 10, 10, 20);
            IDrawing.setPositionAndWidth(nameField, 40, 10, 90);
            IDrawing.setPositionAndWidth(btnEnterConfig, 140, 10, 40);

            IDrawing.setPositionAndWidth(btnAddWidth, 40, height - 30, 20);
            IDrawing.setPositionAndWidth(btnSubWidth, 70, height - 30, 20);
            IDrawing.setPositionAndWidth(btnSubTX, 100, height - 30, 20);
            IDrawing.setPositionAndWidth(btnAddTX, 130, height - 30, 20);

            IDrawing.setPositionAndWidth(btnAddHeight, 10, 40, 20);
            IDrawing.setPositionAndWidth(btnSubHeight, 10, 70, 20);
            IDrawing.setPositionAndWidth(btnSubTY, 10, 100, 20);
            IDrawing.setPositionAndWidth(btnAddTY, 10, 130, 20);

            IDrawing.setPositionAndWidth(btnCenter, 10, height - 30, 20);
        }

        private void setTX(int tx) {
            int w = task.width * Square.length;
            boolean canMove = w > scissor.width;
            if (!canMove) {
                this.tx = 0;
                return;
            }
            int full = w - scissor.width;
            int min = -full / 2;
            int max = full / 2;
            if (tx < min) tx = min;
            else if (tx > max) tx = max;
            this.tx = tx;
        }

        private void setTY(int ty) {
            int h = task.height * Square.length;
            boolean canMove = h > scissor.height;
            if (!canMove) {
                this.ty = 0;
                return;
            }
            int full = h - scissor.height;
            int min = -full / 2;
            int max = full / 2;
            if (ty < min) ty = min;
            else if (ty > max) ty = max;
            this.ty = ty;
        }

        @Override
        public void onClose() {
            minecraft.setScreen(CompoundCreatorScreen.this);
        }

        private void setNowBlock(BlockState state) {
            now.state = state;
        }

    #if MC_VERSION >= "12000"
        private void renderBlockState(GuiGraphics matrices, int x, int y, float partialTick, BlockState state) {
            renderBlockState(matrices.pose(), x, y, partialTick, state);
        }
    #endif
        private void renderBlockState(PoseStack poseStack, int x, int y, float partialTick, BlockState state) {
            BlockRenderDispatcher blockRenderer = minecraft.getBlockRenderer();
            
            poseStack.pushPose();
            poseStack.translate(x, y + 16 , 0);
            poseStack.scale(15.5F, -15.5F, 15.5F);
            float v = (float) (3 * Math.PI / 180F);
            PoseStackUtil.rotX(poseStack, v);

            MultiBufferSource.BufferSource buffer = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
            RenderSystem.enableDepthTest();
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);
            
            blockRenderer.renderSingleBlock(
                state,
                poseStack,
                buffer,
                LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY
            );

            
            buffer.endBatch();
            poseStack.popPose();
        }

        public class Square implements GuiEventListener {
            public static int length = 18;
            public int x;
            public int y;
            public BlockState state;
            public Consumer<Square> consumer;
            public Function<Square, Boolean> highlight = square -> false;
            public Function<Square, Boolean> visible = square -> true;
            public boolean fixed = false;
            public boolean replacement = false;

            public static ResourceLocation PURPLE_CIRCLE = new ResourceLocation("mtrsteamloco:textures/gui/compound_creator/purple_circle.png");
            public static ResourceLocation BLUE_CIRCLE = new ResourceLocation("mtrsteamloco:textures/gui/compound_creator/blue_circle.png");
            public static ResourceLocation MID_CIRCLE = new ResourceLocation("mtrsteamloco:textures/gui/compound_creator/mid_circle.png");

            public Square(Square other) {
                this.x = other.x;
                this.y = other.y;
                this.state = other.state;
                this.consumer = other.consumer;
                this.highlight = other.highlight;
                this.visible = other.visible;
                this.fixed = other.fixed;
                this.replacement = other.replacement;
            }

            public Square(int x, int y, BlockState state, Consumer<Square> consumer, Function<Square, Boolean> highlight, Function<Square, Boolean> visible, boolean fixed, boolean replacement) {
                this.x = x;
                this.y = y;
                this.state = state;
                this.consumer = consumer;
                if (highlight != null) this.highlight = highlight;
                if (visible != null) this.visible = visible;
                this.fixed = fixed;
                this.replacement = replacement;
            }

        #if MC_VERSION >= "12000"
            public void render(GuiGraphics matrices, int mouseX, int mouseY, int tx, int ty, float partialTick) {
        #else
            public void render(PoseStack matrices, int mouseX, int mouseY, int tx, int ty, float partialTick) {
        #endif
                x = tx;
                y = ty;
                if (!isVisible()) return;
                fill(matrices, x, y, x + length, y + length, isMouseOver(mouseX, mouseY) ? 0xfffafff2 : 0xff9b9e96);
                fill(matrices, x + 1, y + 1, x + length - 1, y + length - 1, 0xff919191);
                if (state != null) {
                    renderBlockState(matrices, x + 1, y + 1, partialTick, state);
                    fill(matrices, x + 1, y + 1, x + length - 1, y + length - 1, highlight.apply(this) ? 0x2ff5f5f5 : 0x1fdda9df);
                    CompoundCreatorScreen.this.blit(matrices, replacement ? PURPLE_CIRCLE : BLUE_CIRCLE, x + 1, y + 1, 16, 16);
                    if (fixed) CompoundCreatorScreen.this.blit(matrices, MID_CIRCLE, x + 1, y + 1, 16, 16);
                }
                if (isMouseOver(mouseX, mouseY)) {
                    mouseOver = this;
                }
            }

        #if MC_VERSION >= "12000"
            public void renderTooltip(GuiGraphics matrices, int mouseX, int mouseY, float partialTick) {
        #else
            public void renderTooltip(PoseStack matrices, int mouseX, int mouseY, float partialTick) {
        #endif
                String str = "";
                if (state != null) {
                    Block block = state.getBlock();
                    StateDefinition<Block, BlockState> statedefinition = block.getStateDefinition();
                    Collection<Property<?>> collection = statedefinition.getProperties();
                    StringBuilder sb = new StringBuilder();
                    sb.append("/");
                    for (Property<?> property : collection) {
                        sb.append(property.getName());
                        sb.append(": ");
                        sb.append(state.getValue(property).toString());
                        sb.append("/");
                    }
                    str = "Replacement:" + replacement + '\n' + block.getName().getString() + '\n' + block.getDescriptionId() + '\n' + sb.toString();
                } else {
                    str = "Empty";
                }
                FormattedText formattedText = FormattedText.of(str);
                List<FormattedCharSequence> lines = minecraft.font.split(formattedText, width - Inventory.width - 10);
                int h = (int) (minecraft.font.lineHeight * 1.1F);
                int y = height - lines.size() * h;
                for (FormattedCharSequence line : lines) {
                    drawText(matrices, minecraft.font, line, 0, y, 0xFFFFFFFF);
                    y += h;
                }
            }

            public boolean isMouseOver(double mouseX, double mouseY) {
                if (!isVisible()) return false;
                if (x <= mouseX && mouseX <= x + length && y <= mouseY && mouseY <= y + length) {
                    return true;
                }
                return false;
            }

            private boolean isVisible() {
                return x >= -length && x <= SliceTaskScreen.this.width && y >= -length && y <= SliceTaskScreen.this.height && visible.apply(this);
            }

            public boolean mouseClicked(double mouseX, double mouseY, int i) {
                if (!isMouseOver(mouseX, mouseY)) return false;
                if (i == 0) {
                    consumer.accept(this);
                } else if (i == 1) {
                    if (state != null) {
                        minecraft.setScreen(newPropertyScreen(this, fixed));
                    }
                } else if (i == 2) {
                    now.state = this.state;
                    now.replacement = this.replacement;
                }
                return true;
            }

            private boolean isFocused = false;

            public boolean isFocused() {
                return isFocused;
            }

            public void setFocused(boolean focused) {
                isFocused = focused;
            }
        }


    public Screen newPropertyScreen(Square square, boolean fixed) {
            Square present = fixed ? new Square(square) : square;
            Block block = present.state.getBlock();
            StateDefinition<Block, BlockState> statedefinition = block.getStateDefinition();
            Collection<Property<?>> collection = statedefinition.getProperties();

            ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(SliceTaskScreen.this)
                .setTitle(block.getName())
                .setDoesConfirmSave(false)
                .setTransparentBackground(false)
                .setSavingRunnable(() -> {
                    now.state = present.state;
                    now.replacement = present.replacement;
                });
            ConfigEntryBuilder entryBuilder = builder.entryBuilder();
            ConfigCategory common = builder.getOrCreateCategory(
                    Text.translatable("gui.mtrsteamloco.config.client.category.common")
            );

            common.addEntry(
                entryBuilder.startBooleanToggle(
                    Text.translatable("gui.mtrsteamloco.compound_creator.replacement"),
                    present.replacement
                ).setDefaultValue(true)
                .setSaveConsumer(b -> {
                    present.replacement = b;
                }).build()
            );

            for (Property<?> property : collection) {
                common.addEntry(newButtonCycleListEntry(present, property, entryBuilder));
            }

            return builder.build();
        }

        private <T extends Comparable<T>> ButtonCycleListEntry newButtonCycleListEntry(Square square, Property<T> property, ConfigEntryBuilder entryBuilder) {
            BlockState state = square.state;
            BlockState def = state.getBlock().defaultBlockState();
            List<String> values = new ArrayList<>();
            for (T value : property.getPossibleValues()) {
                values.add(property.getName(value));
            }
            String current = property.getName(property.value(state).value());
            int index = values.indexOf(current);
            String defaultName = property.getName(property.value(def).value());
            int defIndex = values.indexOf(defaultName);
            return new ButtonCycleListEntry(Text.literal(property.getName()), index, values, entryBuilder.getResetButtonKey(), () -> defIndex, ind -> {
                square.state = square.state.setValue(property, property.getValue(values.get(ind)).get());
            }, null, false);
        }

        public class Inventory implements GuiEventListener {
            public static final int width = 100;
            public static final int col = 5;
            private int scroll = 0;
            private List<Square> blocksList = new ArrayList<>();
            private List<Square> searchedList = new ArrayList<>();
            private boolean draggingSlider = false;
            private EditBox searchField = new EditBox(Minecraft.getInstance().font, 11, 45, width, 15, Text.literal(""));
            private Rectangle scissor = new Rectangle(19, 19, 8, 10);

            public Inventory() {
                NonNullList<ItemStack> items = NonNullList.create();
        #if MC_VERSION >= "11903"
                for (Block block : BuiltInRegistries.BLOCK) {
        #else
                for (Block block : Registry.BLOCK) {
        #endif
                    markSquare(new Square(0, 0, block.defaultBlockState(), square -> {
                        now.state = square.state;
                    }, square -> square.state == now.state, square -> true, true, true));
                }
                searchedList = new ArrayList<>(blocksList);

                searchField.moveCursorToStart();
                searchField.setResponder(this::search);
            }

            private void markSquare(Square square) {
                blocksList.add(square);
            }

            public void search(String str) {
                if (str.isEmpty()) {
                    searchedList = new ArrayList<>(blocksList);
                    return;
                }
                str = str.toLowerCase();
                List<Square> list = new ArrayList<>();
                for (Square square : blocksList) {
                    String blockName = square.state.getBlock().getName().getString().toLowerCase();
                    String di = square.state.getBlock().getDescriptionId().toLowerCase();
                    if (blockName.contains(str) || di.contains(str)) {
                        list.add(square);
                    } else {
                        String pinyin = PinyinUtils.getPinyin(blockName);
                        String initials = PinyinUtils.getPinyinInitials(blockName);
                        if (pinyin.contains(str) || initials.contains(str)) {
                            list.add(square);
                        }
                    }
                }
                searchedList = list;
            }

        #if MC_VERSION >= "12000"
            public void render(GuiGraphics matrices, int mouseX, int mouseY, float partialTick) {
        #else
            public void render(PoseStack matrices, int mouseX, int mouseY, float partialTick) {
        #endif
                fill(matrices, scissor.x, 0, scissor.x + scissor.width, height, 0xff212121);
                fill(matrices, scissor.x, 0, scissor.x + 1, height, mouseX >= scissor.x ? 0xfff2f7eb : 0xffafb3aa);
                IDrawing.setPositionAndWidth(searchField, SliceTaskScreen.this.width - width + 3, 1, width - 3);
                searchField.render(matrices, mouseX, mouseY, partialTick);
                scissor = new Rectangle(SliceTaskScreen.this.width - width, 18, width, SliceTaskScreen.this.height - 18);
                checkAndScroll(scroll);
                int x = scissor.x + 3;
                ScissorsHandler.INSTANCE.scissor(scissor);
                for (int i = 0; i < searchedList.size(); i++) {
                    searchedList.get(i).render(matrices, mouseX, mouseY, x + i % col * Square.length, scissor.y + scroll + i / col * Square.length, partialTick);
                }
                ScissorsHandler.INSTANCE.clearScissors();
                if (canScoll()) {
                    int[] pas = getSliderPositionAndSize();
                    fill(matrices, pas[0], pas[1], pas[0] + pas[2], pas[1] + pas[3], 0xffb0b0b0);
                }
            }

            public List<? extends GuiEventListener> children() {
                List<GuiEventListener> result = new ArrayList<>();
                result.add(this);
                result.add(searchField);
                result.addAll(searchedList);
                return result;
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int i) {
                if (!canScoll()) return false;
                int[] spas = getSliderPositionAndSize();
                if (isMouseOverSlider(mouseX, mouseY)) {
                    setScroll((int) mouseY);
                    return true;
                }
                return false;
            }

            private boolean isFocused = false;

            public boolean isFocused() {
                return isFocused;
            }

            public void setFocused(boolean focused) {
                isFocused = focused;
            }

            @Override
            public boolean mouseDragged(double sx, double sy, int i, double tx, double ty) {
                if (isMouseOverSlider(sx, sy) || draggingSlider) {
                    setScroll((int) (sy + ty));
                    draggingSlider = true;
                    return true;
                }
                return false;
            }

            @Override
            public boolean mouseScrolled(double x, double y, double amount) {
                if (!canScoll()) return false;
                if (isMouseOver(x, y)) {
                    checkAndScroll(scroll + 20 * (int) amount);
                    return true;
                }
                return false;
            }

            public boolean isMouseOver(double mouseX, double mouseY) {
                return scissor.x <= mouseX && mouseX <= scissor.x + scissor.width && scissor.y <= mouseY && mouseY <= scissor.y + scissor.height;
            }

            @Override
            public boolean mouseReleased(double mouseX, double mouseY, int i) {
                if (draggingSlider) {
                    draggingSlider = false;
                    return true;
                }
                return false;
            }

            private boolean isMouseOverSlider(double mouseX, double mouseY) {
                if (!canScoll()) return false;
                int[] pas = getSliderPositionAndSize();
                return pas[0] <= mouseX && mouseX <= pas[0] + pas[2] && pas[1] <= mouseY && mouseY <= pas[1] + pas[3];
            }

            private int[] getSliderPositionAndSize() {
                float ah = (float) ah();
                float th = (float) scissor.height;
                int h = (int) (th / ah * th);
                int py = scissor.y + (int) (-1F * scroll / ah * th);
                if (h > 5) {
                    return new int[]{SliceTaskScreen.this.width - 5, py, 5, h};
                } else {
                    return new int[]{SliceTaskScreen.this.width - 5, py, 5, 5};
                }
            }

            private boolean canScoll() {
                return ah() > scissor.height;
            }

            private int ah() {
                return (searchedList.size() / col) * Square.length;
            }

            private void setScroll(int mouseY) {
                int[] pas = getSliderPositionAndSize();
                int sh = pas[3];
                int maxd = scissor.height - sh;
                int dy = mouseY - sh / 2 - scissor.y;
                int maxScroll = -ah() + scissor.height;
                checkAndScroll((int) (dy / ((float) maxd) * maxScroll));
            }

            private void checkAndScroll(int temp) {
                if (!canScoll()) {
                    scroll = 0;
                    return;
                }
                if (temp > 0) temp = 0;
                int min = -ah() + scissor.height;
                if (temp < min) temp = min;
                scroll = temp;
            }
        }
    }
}