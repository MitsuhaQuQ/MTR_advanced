package mtr.packet;

import io.netty.buffer.Unpooled;
import mtr.Items;
import mtr.Registry;
import mtr.audio.AudioServer;
import mtr.audio.SpeakerSettings;
import mtr.block.BlockAudioSpeaker;
import mtr.data.RailwayData;
import mtr.mappings.Text;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Configuration and targeted playback for audio speakers. */
public final class AudioSpeakerPackets implements IPacket {
	private AudioSpeakerPackets() {
	}

	public static void open(ServerPlayer player, BlockPos pos, SpeakerSettings settings) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBlockPos(pos);
		settings.write(packet);
		Registry.sendToPlayer(player, PACKET_OPEN_AUDIO_SPEAKER, packet);
	}

	public static void edit(MinecraftServer server, ServerPlayer player, FriendlyByteBuf packet) {
		final BlockPos pos = packet.readBlockPos();
		final SpeakerSettings settings = SpeakerSettings.read(packet);
		PacketGuard.execute(server, () -> {
			final ServerLevel level = player.serverLevel();
			if (RailwayData.hasNoPermission(player) || !player.isHolding(Items.BRUSH.get()) || !level.hasChunkAt(pos)
					|| !SpeakerSettings.inRange(pos, 8, player.getX(), player.getY(), player.getZ())) return;
			final BlockEntity blockEntity = level.getBlockEntity(pos);
			if (!(blockEntity instanceof BlockAudioSpeaker.TileEntityAudioSpeaker)) return;
			if ((!settings.sound.isEmpty() && AudioServer.getUploadedHash(settings.sound) == null)
					|| BlockAudioSpeaker.appearance(settings.appearance) == null
					|| (settings.mode == SpeakerSettings.Mode.REGION && !level.isInWorldBounds(settings.triggerCenter))) {
				player.displayClientMessage(Text.translatable("gui.mtr.audio_speaker_invalid"), true);
				return;
			}
			((BlockAudioSpeaker.TileEntityAudioSpeaker) blockEntity).applySettings(settings);
		});
	}

	public static void play(ServerPlayer player, ServerLevel world, BlockPos pos, SpeakerSettings settings, String hash, Action action) {
		final FriendlyByteBuf packet = playbackPacket(world, pos, action);
		packet.writeUtf(settings.sound);
		packet.writeUtf(hash, 64);
		packet.writeVarInt(settings.radius);
		Registry.sendToPlayer(player, PACKET_AUDIO_SPEAKER_PLAYBACK, packet);
	}

	public static void stop(ServerPlayer player, ServerLevel world, BlockPos pos) {
		Registry.sendToPlayer(player, PACKET_AUDIO_SPEAKER_PLAYBACK, playbackPacket(world, pos, Action.STOP));
	}

	private static FriendlyByteBuf playbackPacket(ServerLevel world, BlockPos pos, Action action) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeResourceLocation(world.dimension().location());
		packet.writeBlockPos(pos);
		packet.writeEnum(action);
		return packet;
	}

	public enum Action {
		STOP, ONCE, LOOP
	}
}
