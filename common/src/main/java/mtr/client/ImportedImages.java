package mtr.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.netty.buffer.Unpooled;
import mtr.RegistryClient;
import mtr.audio.AudioFiles;
import mtr.image.*;
import mtr.mappings.Text;
import mtr.packet.IPacket;
import mtr.packet.PacketGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import java.io.ByteArrayInputStream;
import java.util.*;

/** Per-server catalogue plus a content-addressed disk cache; textures have a small LRU bound. */
public final class ImportedImages implements IPacket {
	public static final List<ImageDatabase.Entry> ENTRIES = new ArrayList<>();
	public static final Set<String> DELETABLE = new HashSet<>();
	private static final Map<String, byte[]> RECEIVING = new HashMap<>();
	private static final Map<String, Integer> OFFSETS = new HashMap<>();
	private static final Map<String, Long> WANTED = new HashMap<>();
	private static final LinkedHashMap<String, PictureTexture> TEXTURES = new LinkedHashMap<>(16, 0.75F, true);
	private static Object connection;
	private static String session = "";
	public static boolean canUpload;
	public static long maxTotalBytes = 256L * 1024 * 1024;
	public static String message = "";
	public static boolean ok;
	private static long uploading;
	private static ImageDatabase cache;
	private ImportedImages() { }
	public static void ensureConnection() {
		final Object next = Minecraft.getInstance().getConnection();
		if (connection != next) { clear(); connection = next; }
	}
	private static void clear() {
		TEXTURES.values().forEach(PictureTexture::release); TEXTURES.clear(); ENTRIES.clear(); DELETABLE.clear(); RECEIVING.clear(); OFFSETS.clear(); WANTED.clear(); canUpload = false; uploading = 0; message = "";
	}
	private static ImageDatabase cache() throws Exception {
		if (cache == null) cache = new ImageDatabase(Minecraft.getInstance().gameDirectory.toPath().resolve("mtr_image_cache/pictures.db"));
		return cache;
	}
	public static void receive(FriendlyByteBuf packet) {
		final int action = packet.readUnsignedByte();
		if (action == 0) {
			final String nextSession = packet.readUtf(64); final boolean permission = packet.readBoolean(); final int count = PacketGuard.readCount(packet, 20, 256);
			final List<ImageDatabase.Entry> entries = new ArrayList<>();
			final Set<String> deletable = new HashSet<>();
			for (int i = 0; i < count; i++) {
				final String hash = packet.readUtf(64), name = packet.readUtf(129); final int width = packet.readInt(), height = packet.readInt(), size = packet.readInt();
				if (packet.readBoolean()) deletable.add(name);
				final String uploader = packet.readUtf(64), uploaderName = packet.readUtf(64); final long uploadedAt = packet.readLong();
				if (ImageFiles.isHash(hash) && ImageFiles.isReference(name) && width > 0 && height > 0 && width <= ImageFiles.MAX_DIMENSION && height <= ImageFiles.MAX_DIMENSION && size > 0 && size <= ImageFiles.MAX_BYTES) { final int colon = name.indexOf(':'); entries.add(new ImageDatabase.Entry(name.substring(0, colon), name.substring(colon + 1), hash, width, height, size, uploader, uploaderName, uploadedAt)); }
			}
			final long totalLimit = packet.readableBytes() >= 8 ? packet.readLong() : 256L * 1024 * 1024;
			Minecraft.getInstance().execute(() -> { ensureConnection(); if (!session.equals(nextSession)) clear(); session = nextSession; canUpload = permission; maxTotalBytes = Math.max(0, Math.min(4096L * 1024 * 1024, totalLimit)); ENTRIES.clear(); ENTRIES.addAll(entries); DELETABLE.clear(); DELETABLE.addAll(deletable); });
		} else if (action == 1) {
			final String hash = packet.readUtf(64); final int size = packet.readInt(), offset = packet.readInt(); final byte[] bytes = packet.readByteArray(ImageFiles.CHUNK);
			Minecraft.getInstance().execute(() -> data(hash, size, offset, bytes));
		} else if (action == 2) {
			final boolean success = packet.readBoolean(), transfer = packet.readBoolean(); final String key = packet.readUtf(128);
			Minecraft.getInstance().execute(() -> { if (transfer) uploading = 0; ok = success; message = key; if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.displayClientMessage(Text.translatable(key), false); });
		}
	}
	private static void data(String hash, int size, int offset, byte[] bytes) {
		ensureConnection();
		if (!WANTED.containsKey(hash) || size < 1 || size > ImageFiles.MAX_BYTES || ENTRIES.stream().noneMatch(entry -> entry.hash.equals(hash) && entry.size == size)) return;
		if (offset == 0) { RECEIVING.put(hash, new byte[size]); OFFSETS.put(hash, 0); }
		final byte[] target = RECEIVING.get(hash);
		if (target == null || OFFSETS.getOrDefault(hash, -1) != offset || (long) offset + bytes.length > size) { RECEIVING.remove(hash); OFFSETS.remove(hash); WANTED.remove(hash); return; }
		System.arraycopy(bytes, 0, target, offset, bytes.length); OFFSETS.put(hash, offset + bytes.length);
		if (offset + bytes.length != size) return;
		try {
			if (!ImageFiles.hash(target).equals(hash)) throw new IllegalArgumentException("Image hash mismatch");
			ImageFiles.check(target); cache().writeData(hash, target);
		} catch (Exception e) { e.printStackTrace(); }
		finally { RECEIVING.remove(hash); OFFSETS.remove(hash); WANTED.remove(hash); }
	}
	public static boolean busy() {
		ensureConnection();
		if (uploading != 0 && System.currentTimeMillis() - uploading > 60000) { uploading = 0; ok = false; message = "gui.mtr.image_timeout"; }
		return uploading != 0;
	}
	public static void upload(String name, byte[] bytes) throws Exception {
		ensureConnection(); if (!canUpload) throw new IllegalArgumentException("gui.mtr.image_no_permission"); if (busy()) throw new IllegalArgumentException("gui.mtr.image_busy"); ImageFiles.check(bytes);
		uploading = System.currentTimeMillis(); message = "gui.mtr.image_sending";
		final String clean = AudioFiles.cleanName(name);
		for (int offset = 0; offset < bytes.length; offset += ImageFiles.CHUNK) {
			final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer()); packet.writeByte(0); packet.writeUtf(clean); packet.writeInt(bytes.length); packet.writeInt(offset); packet.writeByteArray(Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + ImageFiles.CHUNK))); RegistryClient.sendToServer(PACKET_IMAGE_ACTION, packet);
		}
	}
	public static byte[] read(String hash) {
		ensureConnection();
		if (!ImageFiles.isHash(hash) || ENTRIES.stream().noneMatch(entry -> entry.hash.equals(hash))) return null;
		try {
			final byte[] bytes = cache().read(hash);
			if (bytes != null && bytes.length <= ImageFiles.MAX_BYTES && ImageFiles.hash(bytes).equals(hash)) return bytes;
			if (bytes != null) cache().deleteData(hash);
		} catch (Exception e) { e.printStackTrace(); }
		final long now = System.currentTimeMillis();
		WANTED.entrySet().removeIf(entry -> { if (now - entry.getValue() <= 30000) return false; RECEIVING.remove(entry.getKey()); OFFSETS.remove(entry.getKey()); return true; });
		if (WANTED.size() < 8 && !WANTED.containsKey(hash)) { WANTED.put(hash, now); final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer()); packet.writeByte(1); packet.writeUtf(hash); RegistryClient.sendToServer(PACKET_IMAGE_ACTION, packet); }
		return null;
	}
	public static ResourceLocation texture(String reference) {
		ensureConnection();
		final String hash = ENTRIES.stream().filter(entry -> entry.name.equalsIgnoreCase(reference)).map(entry -> entry.hash).findFirst().orElse(reference);
		final PictureTexture cached = TEXTURES.get(hash);
		if (cached != null) return cached.update();
		final byte[] bytes = read(hash);
		if (bytes == null) return null;
		try {
			final AnimatedGif gif = ImageFiles.isGif(bytes) ? AnimatedGif.decode(bytes) : null;
			final NativeImage pixels;
			if (ImageFiles.isJpeg(bytes)) {
				// Java's decoder, pixel by pixel (NativeImage takes ABGR)
				final java.awt.image.BufferedImage image = ImageFiles.decodeJpeg(bytes);
				pixels = new NativeImage(image.getWidth(), image.getHeight(), false);
				for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
					final int rgb = image.getRGB(x, y);
					pixels.setPixelRGBA(x, y, 0xFF000000 | (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 0xFF));
				}
				image.flush();
			} else if (gif == null) {
				ImageFiles.check(bytes);
				pixels = NativeImage.read(new ByteArrayInputStream(bytes));
			} else {
				pixels = new NativeImage(gif.width, gif.height, false);
				PictureTexture.writeFrame(pixels, gif, 0);
			}
			final PictureTexture picture = new PictureTexture(new DynamicTexture(pixels), gif);
			TEXTURES.put(hash, picture);
			while (TEXTURES.size() > 16 || TEXTURES.values().stream().mapToLong(PictureTexture::animatedPixels).sum() > 2L * AnimatedGif.MAX_PIXELS) {
				TEXTURES.remove(TEXTURES.keySet().iterator().next()).release();
			}
			return picture.id;
		} catch (Exception e) { e.printStackTrace(); return null; }
	}

	private static final class PictureTexture {
		private final DynamicTexture texture;
		private final AnimatedGif gif;
		private final ResourceLocation id;
		private final long started = System.nanoTime();
		private int frame;

		private PictureTexture(DynamicTexture texture, AnimatedGif gif) {
			this.texture = texture;
			this.gif = gif;
			id = Minecraft.getInstance().getTextureManager().register("mtr_image", texture);
		}

		private ResourceLocation update() {
			if (gif != null) {
				final int next = gif.frameAt((System.nanoTime() - started) / 1_000_000);
				if (next != frame) {
					writeFrame(texture.getPixels(), gif, next);
					texture.upload();
					frame = next;
				}
			}
			return id;
		}

		private int animatedPixels() {
			return gif == null ? 0 : gif.pixelCount();
		}

		private void release() {
			Minecraft.getInstance().getTextureManager().release(id);
		}

		private static void writeFrame(NativeImage image, AnimatedGif gif, int frame) {
			final int[] pixels = gif.pixels(frame);
			for (int y = 0; y < gif.height; y++) {
				for (int x = 0; x < gif.width; x++) {
					final int argb = pixels[y * gif.width + x];
					final int abgr = (argb & 0xFF00FF00) | ((argb & 0xFF) << 16) | ((argb >>> 16) & 0xFF);
					image.setPixelRGBA(x, y, abgr);
				}
			}
		}
	}
	public static void apply(int entityId, PaintingSettings settings) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer()); packet.writeByte(2); packet.writeInt(entityId); packet.writeUtf(settings.json()); RegistryClient.sendToServer(PACKET_IMAGE_ACTION, packet);
	}
	public static void delete(String reference) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer()); packet.writeByte(3); packet.writeUtf(reference); RegistryClient.sendToServer(PACKET_IMAGE_ACTION, packet);
	}
}
