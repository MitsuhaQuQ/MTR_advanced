package mtr.screen;

import mtr.client.AudioCache;
import mtr.data.DataConverter;
import mtr.data.NameColorDataBase;
import mtr.mappings.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Sounds to choose from as a tree (0.73.1, user 2026-11-13): at the top the game's sounds by namespace ({@code minecraft},
 * {@code mtr}, then any other mod's) and the uploaded files; a namespace's sounds are split into folders by the dots of
 * their names, the uploaded files by who uploaded them. One level is shown at a time in a {@link DashboardList}: clicking
 * a folder opens it, the first row goes back up. Typing a search lists every sound under the open folder that matches.
 * <p>
 * With a preview, each sound has a play button and an add button that takes it (the announcer); without, clicking a sound
 * takes it (the audio speaker, which plays uploaded files only, so it gets no game sounds and opens on the uploaded files).
 */
public final class SoundTree {

	public final DashboardList list;
	private final Consumer<String> preview;
	private final Consumer<String> choose;
	private final Node root = new Node("", null, true);
	private Node current;
	private String search = "";
	private boolean searching;
	/** What each row shown stands for: a {@link Node} to open, {@link #UP} or a sound to take */
	private final Map<NameColorDataBase, Object> targets = new IdentityHashMap<>();

	private static final Object UP = new Object();
	private static final int COLOR_FOLDER = 0xFFFFAA;
	private static final int COLOR_SOUND = 0xEEEEEE;

	/**
	 * @param withGameSounds whether the game's sounds are listed besides the uploaded files
	 * @param preview        plays a sound, or null if clicking a sound takes it
	 * @param choose         takes a sound: an uploaded file's reference or a game sound's ID
	 */
	public SoundTree(boolean withGameSounds, Consumer<String> preview, Consumer<String> choose) {
		this.preview = preview;
		this.choose = choose;
		list = new DashboardList(preview == null ? null : (data, index) -> {
			final Object target = targets.get(data);
			if (target instanceof Sound) preview.accept(((Sound) target).value);
		}, null, null, null, preview == null ? null : (data, index) -> {
			final Object target = targets.get(data);
			if (target instanceof Sound) choose.accept(((Sound) target).value);
		}, null, null, () -> search, this::setSearch, false);
		list.setRowStyle(row -> row.name, row -> {
			final Object target = targets.get(row);
			return target != null && (!(target instanceof Sound) || preview == null);
		}, row -> row.color, this::click);

		if (withGameSounds) {
			// minecraft and mtr first, then other mods' namespaces by name
			root.folder("minecraft");
			root.folder("mtr");
		}
		final Node uploaded = root.folder(Text.translatable("gui.mtr.sound_tree_uploaded").getString());
		// The speaker plays a file by the sound it was given, so files without one are left out there
		AudioCache.ENTRIES.stream().filter(entry -> withGameSounds || entry.getSoundLocation() != null).sorted(Comparator.comparing(AudioCache.Entry::getReference, String.CASE_INSENSITIVE_ORDER)).forEach(entry -> {
			final ResourceLocation location = entry.getSoundLocation();
			final String uploader = entry.uploaderName == null || entry.uploaderName.isEmpty() ? "?" : entry.uploaderName;
			folderOf(uploaded.folder(uploader), entry.folder).sounds.add(new Sound(entry.getReference(), entry.getReference() + (location == null ? "" : "  " + location)));
		});
		// The kept folders, so empty ones show too (0.74.0)
		AudioCache.FOLDERS.forEach(folder -> folderOf(uploaded.folder(folder.ownerName == null || folder.ownerName.isEmpty() ? "?" : folder.ownerName), folder.path));
		if (withGameSounds) {
			final List<ResourceLocation> ids = new ArrayList<>(Minecraft.getInstance().getSoundManager().getAvailableSounds());
			ids.sort(Comparator.comparing(ResourceLocation::toString));
			final List<String> otherNamespaces = new ArrayList<>();
			ids.forEach(id -> {
				if (!root.folders.containsKey(id.getNamespace()) && !otherNamespaces.contains(id.getNamespace())) otherNamespaces.add(id.getNamespace());
			});
			otherNamespaces.sort(String.CASE_INSENSITIVE_ORDER);
			otherNamespaces.forEach(root::folder);
			for (final ResourceLocation id : ids) {
				Node node = root.folder(id.getNamespace());
				final String[] parts = id.getPath().split("\\.");
				for (int i = 0; i < parts.length - 1; i++) node = node.folder(parts[i]);
				node.sounds.add(new Sound(id.toString(), id.toString()));
			}
		}
		root.count();
		current = withGameSounds ? root : uploaded;
		refresh();
	}

	/**
	 * The node of a folder path ({@link mtr.media.MediaFolders}) under an uploader's node.
	 */
	private static Node folderOf(Node uploaderNode, String path) {
		Node node = uploaderNode;
		if (path != null) {
			for (final String name : path.split("/")) {
				if (!name.isEmpty()) {
					node = node.folder(name);
				}
			}
		}
		return node;
	}

	public void tick() {
		list.tick();
	}

	/** The open folder's path, such as "minecraft / block / anvil", empty at the top */
	public String path() {
		final List<String> names = new ArrayList<>();
		for (Node node = current; node != null && node.parent != null; node = node.parent) names.add(0, node.name);
		return String.join(" / ", names);
	}

	private void setSearch(String text) {
		search = text;
		if (text.trim().isEmpty() == searching) refresh();
	}

	private void click(NameColorDataBase row) {
		final Object target = targets.get(row);
		if (target == UP) {
			if (current.parent != null) current = current.parent;
			refresh();
		} else if (target instanceof Node) {
			current = (Node) target;
			refresh();
		} else if (target instanceof Sound) {
			choose.accept(((Sound) target).value);
		}
	}

	private void refresh() {
		searching = !search.trim().isEmpty();
		targets.clear();
		final List<DataConverter> rows = new ArrayList<>();
		if (searching) {
			// Every sound below, for the list's own filter to match against
			current.forEachSound(sound -> add(rows, sound.label, COLOR_SOUND, sound));
		} else {
			if (current.parent != null) {
				add(rows, "< " + Text.translatable("gui.mtr.sound_tree_up").getString() + "  (" + path() + ")", COLOR_FOLDER, UP);
			}
			current.folders.values().forEach(folder -> add(rows, "> " + folder.name + "  (" + folder.total + ")", COLOR_FOLDER, folder));
			current.sounds.forEach(sound -> add(rows, sound.label, COLOR_SOUND, sound));
		}
		list.setData(rows, preview != null, false, false, false, preview != null, false);
	}

	private void add(List<DataConverter> rows, String label, int color, Object target) {
		final DataConverter row = new DataConverter(label, color);
		rows.add(row);
		targets.put(row, target);
	}

	private static final class Sound {
		private final String value, label;

		private Sound(String value, String label) {
			this.value = value;
			this.label = label;
		}
	}

	private static final class Node {
		private final String name;
		private final Node parent;
		private final Map<String, Node> folders;
		private final List<Sound> sounds = new ArrayList<>();
		private int total;

		private Node(String name, Node parent, boolean keepOrder) {
			this.name = name;
			this.parent = parent;
			folders = keepOrder ? new LinkedHashMap<>() : new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		}

		private Node folder(String name) {
			return folders.computeIfAbsent(name, key -> new Node(key, this, false));
		}

		private int count() {
			total = sounds.size();
			for (final Node folder : folders.values()) total += folder.count();
			return total;
		}

		private void forEachSound(Consumer<Sound> consumer) {
			folders.values().forEach(folder -> folder.forEachSound(consumer));
			sounds.forEach(consumer);
		}
	}
}
