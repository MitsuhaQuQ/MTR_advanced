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

import java.util.List;

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
			if (settings.clips.stream().anyMatch(clip -> AudioServer.getUploadedHash(clip.sound) == null)
					|| BlockAudioSpeaker.appearance(settings.appearance) == null
					|| (settings.mode == SpeakerSettings.Mode.REGION && !level.isInWorldBounds(settings.triggerCenter))) {
				player.displayClientMessage(Text.translatable("gui.mtr.audio_speaker_invalid"), true);
				return;
			}
			((BlockAudioSpeaker.TileEntityAudioSpeaker) blockEntity).applySettings(settings);
		});
	}

	/**
	 * Tells a player to play a speaker's clips (0.70.0): each clip's sound, its file's hash and the gap after it, then the
	 * radius, the directions heard from, whether a play finishes before another, and the interval in tenths of a second.
	 */
	public static void play(ServerPlayer player, ServerLevel world, BlockPos pos, SpeakerSettings settings, List<String> hashes, Action action) {
		final FriendlyByteBuf packet = playbackPacket(world, pos, action);
		packet.writeVarInt(settings.clips.size());
		for (int i = 0; i < settings.clips.size(); i++) {
			packet.writeUtf(settings.clips.get(i).sound);
			packet.writeUtf(hashes.get(i), 64);
			packet.writeVarInt(settings.clips.get(i).gapTenths);
		}
		packet.writeVarInt(settings.radius);
		packet.writeByte(settings.audienceMask);
		packet.writeBoolean(settings.finishBeforeRepeat);
		packet.writeVarInt(settings.intervalTenths);
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
