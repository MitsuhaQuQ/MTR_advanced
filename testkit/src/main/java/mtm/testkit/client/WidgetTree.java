package mtm.testkit.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mtr.data.NameColorDataBase;
import mtr.screen.DashboardList;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The widgets of the open screen as a tree a script can read, like a page's accessibility tree. A widget's id is the field
 * that holds it, "ScreenClass.field" (or "ScreenClass.list.field" inside a {@link DashboardList}), found by reflection
 * so MTR's code needs no change; a widget no field holds is "Type#index".
 */
public final class WidgetTree {

	private static final int MAX_DEPTH = 3;

	private WidgetTree() {
	}

	public static final class Found {
		public final String id;
		public final AbstractWidget widget;

		private Found(String id, AbstractWidget widget) {
			this.id = id;
			this.widget = widget;
		}
	}

	/**
	 * @return the screen's widgets in the order the screen lists them, each with its id
	 */
	public static List<Found> widgets(Screen screen) {
		final Map<Object, String> ids = new IdentityHashMap<>();
		walk(screen, screen.getClass().getSimpleName(), screen.getClass(), ids, 0);
		final List<Found> found = new ArrayList<>();
		int index = 0;
		for (final GuiEventListener child : screen.children()) {
			if (child instanceof AbstractWidget) {
				final String id = ids.get(child);
				found.add(new Found(id == null ? typeName(child) + "#" + index : id, (AbstractWidget) child));
			}
			index++;
		}
		return found;
	}

	public static JsonObject describe(Found found) {
		final AbstractWidget widget = found.widget;
		final JsonObject entry = new JsonObject();
		entry.addProperty("id", found.id);
		entry.addProperty("type", typeName(widget));
		entry.addProperty("text", widget.getMessage().getString());
		entry.addProperty("x", widget.getX());
		entry.addProperty("y", widget.getY());
		entry.addProperty("width", widget.getWidth());
		entry.addProperty("height", widget.getHeight());
		entry.addProperty("visible", widget.visible);
		entry.addProperty("active", widget.active);
		entry.addProperty("focused", widget.isFocused());
		if (widget instanceof EditBox) {
			entry.addProperty("value", ((EditBox) widget).getValue());
		} else if (widget instanceof Checkbox) {
			entry.addProperty("value", ((Checkbox) widget).selected());
		} else if (widget instanceof AbstractSliderButton) {
			entry.addProperty("value", ((AbstractSliderButton) widget).value);
		}
		return entry;
	}

	/**
	 * The {@link DashboardList}s held by the screen, with the rows they show now and where each row is drawn.
	 */
	public static JsonArray lists(Screen screen) {
		final Map<Object, String> ids = new IdentityHashMap<>();
		walk(screen, screen.getClass().getSimpleName(), screen.getClass(), ids, 0);
		final JsonArray lists = new JsonArray();
		ids.forEach((object, id) -> {
			if (object instanceof DashboardList) {
				lists.add(describeList(id, (DashboardList) object));
			}
		});
		return lists;
	}

	@SuppressWarnings("unchecked")
	private static JsonObject describeList(String id, DashboardList list) {
		final JsonObject entry = new JsonObject();
		entry.addProperty("id", id);
		entry.addProperty("x", list.x);
		entry.addProperty("y", list.y);
		entry.addProperty("width", list.width);
		entry.addProperty("height", list.height);
		try {
			final List<NameColorDataBase> dataSorted = (List<NameColorDataBase>) field(DashboardList.class, "dataSorted").get(list);
			final List<Integer> dataFiltered = (List<Integer>) field(DashboardList.class, "dataFiltered").get(list);
			final int scroll = field(DashboardList.class, "scroll").getInt(list);
			final Method itemsToShowMethod = DashboardList.class.getDeclaredMethod("itemsToShow");
			itemsToShowMethod.setAccessible(true);
			final int itemsToShow = (int) itemsToShowMethod.invoke(list);
			final int topOffset = field(DashboardList.class, "TOP_OFFSET").getInt(null);
			final int rowHeight = mtr.data.IGui.SQUARE_SIZE;
			final Function<NameColorDataBase, String> rowLabel = (Function<NameColorDataBase, String>) field(DashboardList.class, "rowLabel").get(list);
			final Predicate<NameColorDataBase> isHeaderRow = (Predicate<NameColorDataBase>) field(DashboardList.class, "isHeaderRow").get(list);
			entry.addProperty("scroll", scroll);
			entry.addProperty("total", dataFiltered.size());
			entry.addProperty("rowsShown", itemsToShow);
			final JsonArray rows = new JsonArray();
			for (int i = 0; i < itemsToShow && i + scroll < dataFiltered.size(); i++) {
				final NameColorDataBase data = dataSorted.get(dataFiltered.get(i + scroll));
				final JsonObject row = new JsonObject();
				row.addProperty("index", i + scroll);
				row.addProperty("dataId", data.id);
				row.addProperty("name", data.name);
				row.addProperty("label", rowLabel.apply(data));
				row.addProperty("header", isHeaderRow.test(data));
				row.addProperty("type", data.getClass().getSimpleName());
				row.addProperty("x", list.x);
				row.addProperty("y", list.y + topOffset + rowHeight * i);
				row.addProperty("height", rowHeight);
				rows.add(row);
			}
			entry.add("rows", rows);
		} catch (ReflectiveOperationException | RuntimeException e) {
			entry.addProperty("error", e.toString());
		}
		return entry;
	}

	private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
		final Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	private static void walk(Object object, String prefix, Class<?> fromClass, Map<Object, String> ids, int depth) {
		// Fields held directly name their widgets first, so a widget is named by its owner rather than by a helper
		// that also holds it
		final List<Object[]> nested = new ArrayList<>();
		for (Class<?> c = fromClass; c != null && c != Screen.class && c != Object.class; c = c.getSuperclass()) {
			for (final Field field : c.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive() || field.isSynthetic()) {
					continue;
				}
				final Object value;
				try {
					field.setAccessible(true);
					value = field.get(object);
				} catch (ReflectiveOperationException | RuntimeException e) {
					continue;
				}
				if (value == null) {
					continue;
				}
				final String id = prefix + "." + field.getName();
				if (value instanceof AbstractWidget || value instanceof DashboardList) {
					ids.putIfAbsent(value, id);
					if (value instanceof DashboardList) {
						nested.add(new Object[]{value, id});
					}
				} else if (value instanceof List) {
					int i = 0;
					for (final Object item : new ArrayList<>((List<?>) value)) {
						if (item instanceof AbstractWidget) {
							ids.putIfAbsent(item, id + "[" + i + "]");
						}
						i++;
					}
				} else if (value.getClass().isArray() && !value.getClass().getComponentType().isPrimitive()) {
					for (int i = 0; i < Array.getLength(value); i++) {
						final Object item = Array.get(value, i);
						if (item instanceof AbstractWidget) {
							ids.putIfAbsent(item, id + "[" + i + "]");
						}
					}
				} else if (isScreenHelper(value)) {
					nested.add(new Object[]{value, id});
				}
			}
		}
		if (depth < MAX_DEPTH) {
			for (final Object[] entry : nested) {
				walk(entry[0], (String) entry[1], entry[0].getClass(), ids, depth + 1);
			}
		}
	}

	/**
	 * MTR's own screen parts (lists, maps, rows), not lambdas or screens
	 */
	private static boolean isScreenHelper(Object value) {
		final Class<?> c = value.getClass();
		return c.getName().startsWith("mtr.screen.") && !c.isSynthetic() && !c.getName().contains("$$Lambda") && !(value instanceof Screen);
	}

	/**
	 * MTR's own classes keep their names in the released jar, Minecraft's do not, so Minecraft's widgets are named by the
	 * nearest well-known type.
	 */
	public static String typeName(Object widget) {
		final Class<?> c = widget.getClass();
		if (!c.getName().startsWith("net.minecraft.")) {
			return c.getSimpleName();
		}
		if (widget instanceof ImageButton) {
			return "ImageButton";
		} else if (widget instanceof EditBox) {
			return "EditBox";
		} else if (widget instanceof Checkbox) {
			return "Checkbox";
		} else if (widget instanceof AbstractSliderButton) {
			return "Slider";
		} else if (widget instanceof AbstractButton) {
			return "Button";
		} else if (widget instanceof AbstractWidget) {
			return "Widget";
		} else {
			return "Listener";
		}
	}
}
