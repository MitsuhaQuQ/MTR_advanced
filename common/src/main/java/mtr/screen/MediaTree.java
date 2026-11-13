package mtr.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Uploaded files as a tree (0.74.0): who uploaded them, then their folders inside folders ({@link mtr.media.MediaFolders}),
 * then the files. The image picker of paintings shows it a level at a time, as {@link SoundTree} does for sounds.
 *
 * @param <T> a file
 */
public final class MediaTree<T> {

	public final Node<T> root = new Node<>("", null);

	/**
	 * Adds a file under its uploader and folder.
	 */
	public void add(String uploaderName, String folder, T item) {
		node(uploaderName, folder).items.add(item);
	}

	/**
	 * Adds a folder, so it is there when empty too.
	 */
	public void addFolder(String uploaderName, String folder) {
		node(uploaderName, folder);
	}

	/**
	 * Counts what each folder holds, all levels down; call once everything is added.
	 */
	public void count() {
		root.count();
	}

	private Node<T> node(String uploaderName, String folder) {
		Node<T> node = root.folder(uploaderName == null || uploaderName.isEmpty() ? "?" : uploaderName);
		if (folder != null) {
			for (final String name : folder.split("/")) {
				if (!name.isEmpty()) {
					node = node.folder(name);
				}
			}
		}
		return node;
	}

	public static final class Node<T> {

		public final String name;
		public final Node<T> parent;
		public final Map<String, Node<T>> folders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		public final List<T> items = new ArrayList<>();
		/** Files here and in every folder below */
		public int total;

		private Node(String name, Node<T> parent) {
			this.name = name;
			this.parent = parent;
		}

		public Node<T> folder(String name) {
			return folders.computeIfAbsent(name, key -> new Node<>(key, this));
		}

		/**
		 * Every file here and below, folders first.
		 */
		public void forEachItem(Consumer<T> consumer) {
			folders.values().forEach(folder -> folder.forEachItem(consumer));
			items.forEach(consumer);
		}

		/**
		 * The folder names from the top, such as "Steve / 1号线 / 上行", empty at the top.
		 */
		public String path() {
			final List<String> names = new ArrayList<>();
			for (Node<T> node = this; node != null && node.parent != null; node = node.parent) {
				names.add(0, node.name);
			}
			return String.join(" / ", names);
		}

		/**
		 * The folder holding a file, or null if it isn't below here.
		 */
		public Node<T> find(T item) {
			if (items.contains(item)) {
				return this;
			}
			for (final Node<T> folder : folders.values()) {
				final Node<T> found = folder.find(item);
				if (found != null) {
					return found;
				}
			}
			return null;
		}

		private int count() {
			total = items.size();
			for (final Node<T> folder : folders.values()) {
				total += folder.count();
			}
			return total;
		}
	}
}
