package mtr.screen;

import mtr.compat.GuiCompat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.data.IGui;
import mtr.data.NameColorDataBase;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

public class DashboardList implements IGui {

	public int x;
	public int y;
	public int width;
	public int height;

	private final WidgetBetterTextField textFieldSearch;

	private final ImageButton buttonPrevPage;
	private final ImageButton buttonNextPage;

	private final ImageButton buttonFind;
	private final ImageButton buttonDrawArea;
	private final ImageButton buttonEdit;
	private final ImageButton buttonUp;
	private final ImageButton buttonDown;
	private final ImageButton buttonAdd;
	private final ImageButton buttonDelete;

	private final Supplier<String> getSearch;
	private final Consumer<String> setSearch;

	private List<NameColorDataBase> dataSorted = new ArrayList<>();
	private final List<Integer> dataFiltered = new ArrayList<>();
	private String lastSearch = "";
	private int hoverIndex, scroll;
	private boolean isDraggingScrollbar;
	private long selectedItemId = NO_SELECTION;
	private Function<NameColorDataBase, String> rowLabel = data -> IGui.formatStationName(data.name);
	private Predicate<NameColorDataBase> isHeaderRow = data -> false;
	private ToIntFunction<NameColorDataBase> rowColor = data -> data.color;
	private Consumer<NameColorDataBase> onClickHeaderRow = data -> {
	};

	private boolean hasFind;
	private boolean hasDrawArea;
	private boolean hasEdit;
	private boolean hasSort;
	private boolean hasAdd;
	private boolean hasDelete;

	private static final int TOP_OFFSET = SQUARE_SIZE + TEXT_FIELD_PADDING;
	private static final int SCROLLBAR_WIDTH = 4;
	private static final int MIN_SCROLLBAR_HEIGHT = 16;
	private static final int ROWS_PER_WHEEL_STEP = 3;
	private static final int ARGB_SCROLLBAR_TRACK = 0x40FFFFFF;
	private static final int ARGB_SCROLLBAR_HANDLE = 0xC0FFFFFF;
	private static final int ARGB_SELECTED_ITEM = 0x50FFFFFF;
	private static final long NO_SELECTION = -1;

	public <T> DashboardList(BiConsumer<NameColorDataBase, Integer> onFind, BiConsumer<NameColorDataBase, Integer> onDrawArea, BiConsumer<NameColorDataBase, Integer> onEdit, Runnable onSort, BiConsumer<NameColorDataBase, Integer> onAdd, BiConsumer<NameColorDataBase, Integer> onDelete, Supplier<List<T>> getList, Supplier<String> getSearch, Consumer<String> setSearch) {
		this(onFind, onDrawArea, onEdit, onSort, onAdd, onDelete, getList, getSearch, setSearch, true);
	}

	public <T> DashboardList(BiConsumer<NameColorDataBase, Integer> onFind, BiConsumer<NameColorDataBase, Integer> onDrawArea, BiConsumer<NameColorDataBase, Integer> onEdit, Runnable onSort, BiConsumer<NameColorDataBase, Integer> onAdd, BiConsumer<NameColorDataBase, Integer> onDelete, Supplier<List<T>> getList, Supplier<String> getSearch, Consumer<String> setSearch, boolean playSound) {
		this.getSearch = getSearch;
		this.setSearch = setSearch;
		textFieldSearch = new WidgetBetterTextField(Text.translatable("gui.mtr.search").getString());
		buttonPrevPage = new ImageButton(0, 0, 0, SQUARE_SIZE, 0, 0, 20, new ResourceLocation("mtr:textures/gui/icon_left.png"), 20, 40, button -> setScroll(scroll - itemsToShow()));
		buttonNextPage = new ImageButton(0, 0, 0, SQUARE_SIZE, 0, 0, 20, new ResourceLocation("mtr:textures/gui/icon_right.png"), 20, 40, button -> setScroll(scroll + itemsToShow()));
		buttonFind = new WidgetSilentImageButton(0, 0, 0, SQUARE_SIZE, 0, 0, 20, new ResourceLocation("mtr:textures/gui/icon_find.png"), 20, 40, button -> onClick(onFind), playSound);
		buttonDrawArea = new ImageButton(0, 0, 0, SQUARE_SIZE, 0, 0, 20, new ResourceLocation("mtr:textures/gui/icon_draw_area.png"), 20, 40, button -> onClick(onDrawArea));
		buttonEdit = new ImageButton(0, 0, 0, SQUARE_SIZE, 0, 0, 20, new ResourceLocation("mtr:textures/gui/icon_edit.png"), 20, 40, button -> onClick(onEdit));
		buttonUp = new ImageButton(0, 0, 0, SQUARE_SIZE, 0, 0, 20, new ResourceLocation("mtr:textures/gui/icon_up.png"), 20, 40, button -> {
			onUp(getList);
			onSort.run();
		});
		buttonDown = new ImageButton(0, 0, 0, SQUARE_SIZE, 0, 0, 20, new ResourceLocation("mtr:textures/gui/icon_down.png"), 20, 40, button -> {
			onDown(getList);
			onSort.run();
		});
		buttonAdd = new ImageButton(0, 0, 0, SQUARE_SIZE, 0, 0, 20, new ResourceLocation("mtr:textures/gui/icon_add.png"), 20, 40, button -> onClick(onAdd));
		buttonDelete = new ImageButton(0, 0, 0, SQUARE_SIZE, 0, 0, 20, new ResourceLocation("mtr:textures/gui/icon_delete.png"), 20, 40, button -> onClick(onDelete));
	}

	public void init(Consumer<AbstractWidget> addDrawableChild) {
		IDrawing.setPositionAndWidth(buttonPrevPage, x, y + TEXT_FIELD_PADDING / 2, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonNextPage, x + SQUARE_SIZE * 3, y + TEXT_FIELD_PADDING / 2, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(textFieldSearch, x + SQUARE_SIZE * 4 + TEXT_FIELD_PADDING / 2, y + TEXT_FIELD_PADDING / 2, width - SQUARE_SIZE * 4 - TEXT_FIELD_PADDING);

		textFieldSearch.setResponder(setSearch);
		textFieldSearch.setValue(getSearch.get());

		buttonFind.visible = false;
		buttonDrawArea.visible = false;
		buttonEdit.visible = false;
		buttonUp.visible = false;
		buttonDown.visible = false;
		buttonAdd.visible = false;
		buttonDelete.visible = false;

		addDrawableChild.accept(buttonPrevPage);
		addDrawableChild.accept(buttonNextPage);

		addDrawableChild.accept(buttonFind);
		addDrawableChild.accept(buttonDrawArea);
		addDrawableChild.accept(buttonEdit);
		addDrawableChild.accept(buttonUp);
		addDrawableChild.accept(buttonDown);
		addDrawableChild.accept(buttonAdd);
		addDrawableChild.accept(buttonDelete);

		addDrawableChild.accept(textFieldSearch);
	}

	public void tick() {
		textFieldSearch.tick();
		UtilitiesClient.setWidgetX(buttonPrevPage, x);
		UtilitiesClient.setWidgetX(buttonNextPage, x + SQUARE_SIZE * 3);
		UtilitiesClient.setWidgetX(textFieldSearch, x + SQUARE_SIZE * 4 + TEXT_FIELD_PADDING / 2);

		final String text = textFieldSearch.getValue();
		final String[] keywords = text.toLowerCase(Locale.ENGLISH).trim().split("\\s+");
		dataFiltered.clear();
		for (int i = 0; i < dataSorted.size(); i++) {
			if (matchesKeywords(dataSorted.get(i).name, keywords)) {
				dataFiltered.add(i);
			}
		}

		if (!text.equals(lastSearch)) {
			lastSearch = text;
			scroll = 0;
		}
		setScroll(scroll);
	}

	/**
	 * @param rowLabel         the text of each row
	 * @param isHeaderRow      true for rows that stand for a group, which have no buttons and are clicked instead
	 * @param rowColor         the colour of each row
	 * @param onClickHeaderRow what clicking a header row does
	 */
	public void setRowStyle(Function<NameColorDataBase, String> rowLabel, Predicate<NameColorDataBase> isHeaderRow, ToIntFunction<NameColorDataBase> rowColor, Consumer<NameColorDataBase> onClickHeaderRow) {
		this.rowLabel = rowLabel;
		this.isHeaderRow = isHeaderRow;
		this.rowColor = rowColor;
		this.onClickHeaderRow = onClickHeaderRow;
	}

	public void setData(Set<? extends NameColorDataBase> dataSet, boolean hasFind, boolean hasDrawArea, boolean hasEdit, boolean hasSort, boolean hasAdd, boolean hasDelete) {
		List<? extends NameColorDataBase> dataList = new ArrayList<>(dataSet);
		Collections.sort(dataList);
		setData(dataList, hasFind, hasDrawArea, hasEdit, hasSort, hasAdd, hasDelete);
	}

	public void setData(List<? extends NameColorDataBase> dataList, boolean hasFind, boolean hasDrawArea, boolean hasEdit, boolean hasSort, boolean hasAdd, boolean hasDelete) {
		dataSorted = new ArrayList<>(dataList);
		this.hasFind = hasFind;
		final boolean hasPermission = ClientData.hasPermission();
		this.hasDrawArea = hasPermission && hasDrawArea;
		this.hasEdit = hasPermission && hasEdit;
		this.hasSort = hasPermission && hasSort;
		this.hasAdd = hasPermission && hasAdd;
		this.hasDelete = hasPermission && hasDelete;
	}

	public void render(PoseStack matrices, Font textRenderer) {
		final int itemsToShow = itemsToShow();
		final String positionText = String.format("%s/%s", Math.min(scroll + itemsToShow, dataFiltered.size()), dataFiltered.size());
		final int positionWidth = textRenderer.width(positionText);
		matrices.pushPose();
		matrices.translate(x + SQUARE_SIZE * 2, y + TEXT_PADDING + TEXT_FIELD_PADDING / 2F, 0);
		if (positionWidth > SQUARE_SIZE * 2) {
			matrices.scale((float) SQUARE_SIZE * 2 / positionWidth, 1, 1);
		}
		GuiCompat.drawCenteredString(matrices, textRenderer, positionText, 0, 0, ARGB_WHITE);
		matrices.popPose();

		final int rowWidth = getRowWidth();
		if (hasScrollbar()) {
			final int trackHeight = itemsToShow * SQUARE_SIZE;
			final int handleHeight = getScrollbarHandleHeight();
			final int handleY = y + TOP_OFFSET + (trackHeight - handleHeight) * scroll / getMaxScroll();
			final int barX = x + width - SCROLLBAR_WIDTH;
			final Tesselator tesselator = Tesselator.getInstance();
			final BufferBuilder buffer = tesselator.getBuilder();
			RenderSystem.enableBlend();
			UtilitiesClient.beginDrawingRectangle(buffer);
			IDrawing.drawRectangle(buffer, barX, y + TOP_OFFSET, x + width, y + TOP_OFFSET + trackHeight, ARGB_SCROLLBAR_TRACK);
			IDrawing.drawRectangle(buffer, barX, handleY, x + width, handleY + handleHeight, ARGB_SCROLLBAR_HANDLE);
			tesselator.end();
			RenderSystem.disableBlend();
			UtilitiesClient.finishDrawingRectangle();
		}

		for (int i = 0; i < itemsToShow; i++) {
			if (i + scroll < dataFiltered.size()) {
				final int drawY = SQUARE_SIZE * i + TEXT_PADDING + TOP_OFFSET;
				final NameColorDataBase data = dataSorted.get(dataFiltered.get(i + scroll));

				Tesselator tesselator = Tesselator.getInstance();
				BufferBuilder buffer = tesselator.getBuilder();
				RenderSystem.enableBlend();
				UtilitiesClient.beginDrawingRectangle(buffer);
				if (data.id == selectedItemId) {
					IDrawing.drawRectangle(buffer, x, y + drawY - TEXT_PADDING, x + rowWidth, y + drawY - TEXT_PADDING + SQUARE_SIZE, ARGB_SELECTED_ITEM);
				}
				IDrawing.drawRectangle(buffer, x + TEXT_PADDING, y + drawY, x + TEXT_PADDING + TEXT_HEIGHT, y + drawY + TEXT_HEIGHT, ARGB_BLACK | rowColor.applyAsInt(data));
				tesselator.end();
				RenderSystem.disableBlend();
				UtilitiesClient.finishDrawingRectangle();

				final String drawString = rowLabel.apply(data);
				final int textStart = TEXT_PADDING * 2 + TEXT_HEIGHT;
				final int textWidth = textRenderer.width(drawString);
				final int availableSpace = rowWidth - textStart;
				matrices.pushPose();
				matrices.translate(x + textStart, 0, 0);
				if (textWidth > availableSpace) {
					matrices.scale((float) availableSpace / textWidth, 1, 1);
				}
				GuiCompat.draw(matrices, textRenderer, drawString, 0, y + drawY, ARGB_WHITE);
				matrices.popPose();
			}
		}
	}

	public void mouseMoved(double mouseX, double mouseY) {
		buttonFind.visible = false;
		buttonDrawArea.visible = false;
		buttonEdit.visible = false;
		buttonUp.visible = false;
		buttonDown.visible = false;
		buttonAdd.visible = false;
		buttonDelete.visible = false;

		final int rowWidth = getRowWidth();
		final int itemsToShow = itemsToShow();
		final boolean isOverScrollbar = hasScrollbar() && mouseX >= x + rowWidth && mouseX < x + width && mouseY >= y + TOP_OFFSET && mouseY < y + TOP_OFFSET + SQUARE_SIZE * itemsToShow;
		final boolean isLeftPressed = Minecraft.getInstance().mouseHandler.isLeftPressed();
		isDraggingScrollbar = isLeftPressed && (isDraggingScrollbar || isOverScrollbar);
		if (isDraggingScrollbar) {
			final int handleHeight = getScrollbarHandleHeight();
			final double handleTop = mouseY - y - TOP_OFFSET - handleHeight / 2D;
			setScroll((int) Math.round(handleTop * getMaxScroll() / Math.max(1, itemsToShow * SQUARE_SIZE - handleHeight)));
			return;
		}

		if (mouseX >= x && mouseX < x + rowWidth && mouseY >= y + TOP_OFFSET && mouseY < y + TOP_OFFSET + SQUARE_SIZE * itemsToShow) {
			hoverIndex = ((int) mouseY - y - TOP_OFFSET) / SQUARE_SIZE;
			final int dataSize = dataFiltered.size();
			final boolean hasSortFiltered = hasSort && dataSize == dataSorted.size();
			if (hoverIndex >= 0 && hoverIndex + scroll < dataSize) {
				final boolean isHeader = isHeaderRow.test(dataSorted.get(dataFiltered.get(hoverIndex + scroll)));
				buttonFind.visible = hasFind && !isHeader;
				buttonDrawArea.visible = hasDrawArea && !isHeader;
				buttonEdit.visible = hasEdit && !isHeader;
				buttonUp.visible = hasSortFiltered && !isHeader;
				buttonDown.visible = hasSortFiltered && !isHeader;
				buttonAdd.visible = hasAdd && !isHeader;
				buttonDelete.visible = hasDelete && !isHeader;

				buttonUp.active = hoverIndex + scroll > 0;
				buttonDown.active = hoverIndex + scroll < dataSize - 1;

				final int renderOffset = y + hoverIndex * SQUARE_SIZE + TOP_OFFSET;
				IDrawing.setPositionAndWidth(buttonFind, x + rowWidth - SQUARE_SIZE * (1 + (hasDelete ? 1 : 0) + (hasAdd ? 1 : 0) + (hasSortFiltered ? 2 : 0) + (hasEdit ? 1 : 0) + (hasDrawArea ? 1 : 0)), renderOffset, SQUARE_SIZE);
				IDrawing.setPositionAndWidth(buttonDrawArea, x + rowWidth - SQUARE_SIZE * (1 + (hasDelete ? 1 : 0) + (hasAdd ? 1 : 0) + (hasSortFiltered ? 2 : 0) + (hasEdit ? 1 : 0)), renderOffset, SQUARE_SIZE);
				IDrawing.setPositionAndWidth(buttonEdit, x + rowWidth - SQUARE_SIZE * (1 + (hasDelete ? 1 : 0) + (hasAdd ? 1 : 0) + (hasSortFiltered ? 2 : 0)), renderOffset, SQUARE_SIZE);
				IDrawing.setPositionAndWidth(buttonUp, x + rowWidth - SQUARE_SIZE * (2 + (hasDelete ? 1 : 0) + (hasAdd ? 1 : 0)), renderOffset, SQUARE_SIZE);
				IDrawing.setPositionAndWidth(buttonDown, x + rowWidth - SQUARE_SIZE * (1 + (hasDelete ? 1 : 0) + (hasAdd ? 1 : 0)), renderOffset, SQUARE_SIZE);
				IDrawing.setPositionAndWidth(buttonAdd, x + rowWidth - SQUARE_SIZE * (1 + (hasDelete ? 1 : 0)), renderOffset, SQUARE_SIZE);
				IDrawing.setPositionAndWidth(buttonDelete, x + rowWidth - SQUARE_SIZE, renderOffset, SQUARE_SIZE);
			}
		}
	}

	/**
	 * @return true if this click landed on a header row
	 */
	public boolean mouseClicked(double mouseX, double mouseY) {
		if (mouseX >= x && mouseX < x + getRowWidth() && mouseY >= y + TOP_OFFSET && mouseY < y + TOP_OFFSET + SQUARE_SIZE * itemsToShow()) {
			final int index = ((int) mouseY - y - TOP_OFFSET) / SQUARE_SIZE + scroll;
			if (index >= 0 && index < dataFiltered.size()) {
				final NameColorDataBase data = dataSorted.get(dataFiltered.get(index));
				if (isHeaderRow.test(data)) {
					onClickHeaderRow.accept(data);
					return true;
				}
			}
		}
		return false;
	}

	public void mouseScrolled(double mouseX, double mouseY, double amount) {
		if (mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height) {
			setScroll(scroll + (int) Math.signum(-amount) * (Screen.hasShiftDown() ? itemsToShow() : ROWS_PER_WHEEL_STEP));
		}
	}

	public void clearSearch() {
		textFieldSearch.setValue("");
	}

	/**
	 * Highlights an item and scrolls it to the middle of the list, leaving any search so it can't be filtered out.
	 */
	public void selectItem(long id) {
		selectedItemId = id;
		clearSearch();
		lastSearch = "";
		dataFiltered.clear();
		for (int i = 0; i < dataSorted.size(); i++) {
			dataFiltered.add(i);
			if (dataSorted.get(i).id == id) {
				scroll = i - itemsToShow() / 2;
			}
		}
		setScroll(scroll);
	}

	public void clearSelectedItem() {
		selectedItemId = NO_SELECTION;
	}

	public int getHoverItemIndex() {
		final int sortedIndex = hoverIndex + scroll;
		if (sortedIndex >= 0 && sortedIndex < dataFiltered.size()) {
			return dataFiltered.get(sortedIndex);
		} else {
			return -1;
		}
	}

	private void setScroll(int newScroll) {
		final int maxScroll = getMaxScroll();
		scroll = Mth.clamp(newScroll, 0, maxScroll);
		buttonPrevPage.visible = scroll > 0;
		buttonNextPage.visible = scroll < maxScroll;
	}

	private int getMaxScroll() {
		return Math.max(0, dataFiltered.size() - itemsToShow());
	}

	private boolean hasScrollbar() {
		return getMaxScroll() > 0;
	}

	private int getRowWidth() {
		return hasScrollbar() ? width - SCROLLBAR_WIDTH : width;
	}

	private int getScrollbarHandleHeight() {
		final int trackHeight = itemsToShow() * SQUARE_SIZE;
		return Math.min(trackHeight, Math.max(MIN_SCROLLBAR_HEIGHT, trackHeight * itemsToShow() / Math.max(1, dataFiltered.size())));
	}

	/**
	 * Every word typed has to appear somewhere in the name, in any order.
	 */
	private static boolean matchesKeywords(String name, String[] keywords) {
		final String lowerCaseName = name.toLowerCase(Locale.ENGLISH);
		for (final String keyword : keywords) {
			if (!lowerCaseName.contains(keyword)) {
				return false;
			}
		}
		return true;
	}

	private void onClick(BiConsumer<NameColorDataBase, Integer> onClick) {
		final int index = getHoverItemIndex();
		if (index >= 0 && index < dataSorted.size()) {
			onClick.accept(dataSorted.get(index), index);
		}
	}

	private <T> void onUp(Supplier<List<T>> getList) {
		if (textFieldSearch.getValue().isEmpty()) {
			final int index = hoverIndex + scroll;
			final List<T> list = getList.get();
			if (Screen.hasShiftDown()) {
				list.add(0, list.remove(index));
			} else {
				final T aboveItem = list.get(index - 1);
				final T thisItem = list.get(index);
				list.set(index - 1, thisItem);
				list.set(index, aboveItem);
			}
		}
	}

	private <T> void onDown(Supplier<List<T>> getList) {
		if (textFieldSearch.getValue().isEmpty()) {
			final int index = hoverIndex + scroll;
			final List<T> list = getList.get();
			if (Screen.hasShiftDown()) {
				list.add(list.remove(index));
			} else {
				final T thisItem = list.get(index);
				final T belowItem = list.get(index + 1);
				list.set(index, belowItem);
				list.set(index + 1, thisItem);
			}
		}
	}

	private int itemsToShow() {
		return (height - TOP_OFFSET) / SQUARE_SIZE;
	}
}
