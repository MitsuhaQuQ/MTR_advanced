package mtr.block;

import mtr.BlockEntityTypes;
import mtr.audio.AudioServer;
import mtr.audio.SpeakerSettings;
import mtr.data.RailwayData;
import mtr.mappings.BlockEntityClientSerializableMapper;
import mtr.mappings.BlockEntityMapper;
import mtr.mappings.BlockMapper;
import mtr.mappings.EntityBlockMapper;
import mtr.packet.AudioSpeakerPackets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class BlockAudioSpeaker extends BlockMapper implements EntityBlockMapper {

	public BlockAudioSpeaker() {
		super(BlockBehaviour.Properties.copy(Blocks.SMOOTH_STONE).noOcclusion());
	}

	@Override
	public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
		return new TileEntityAudioSpeaker(pos, state);
	}

	@Override
	public BlockEntityType<? extends BlockEntityMapper> getType() {
		return BlockEntityTypes.AUDIO_SPEAKER_TILE_ENTITY.get();
	}

	@Override
	public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		return IBlock.checkHoldingBrush(world, player, () -> {
			final BlockEntity entity = world.getBlockEntity(pos);
			if (player instanceof ServerPlayer && !RailwayData.hasNoPermission((ServerPlayer) player) && entity instanceof TileEntityAudioSpeaker) {
				AudioSpeakerPackets.open((ServerPlayer) player, pos, ((TileEntityAudioSpeaker) entity).settings);
			}
		});
	}

	@Override
	public <T extends BlockEntityMapper> void tick(Level world, BlockPos pos, T blockEntity) {
		if (world instanceof ServerLevel && blockEntity instanceof TileEntityAudioSpeaker) {
			((TileEntityAudioSpeaker) blockEntity).tickSpeaker((ServerLevel) world);
		}
	}

	@Override
	public void neighborChanged(BlockState state, Level world, BlockPos pos, Block block, BlockPos fromPos, boolean moving) {
		super.neighborChanged(state, world, pos, block, fromPos, moving);
		if (world instanceof ServerLevel) {
			final BlockEntity entity = world.getBlockEntity(pos);
			if (entity instanceof TileEntityAudioSpeaker) ((TileEntityAudioSpeaker) entity).samplePower((ServerLevel) world);
		}
	}

	@Override
	public RenderShape getRenderShape(BlockState state) {
		return RenderShape.INVISIBLE;
	}

	public static BlockState appearance(ResourceLocation id) {
		final Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
		if (block == null) return null;
		final BlockState state = block.defaultBlockState();
		return state.getRenderShape() == RenderShape.MODEL ? state : null;
	}

	public static final class TileEntityAudioSpeaker extends BlockEntityClientSerializableMapper {
		private final SpeakerSettings.Trigger trigger = new SpeakerSettings.Trigger();
		private final Set<UUID> listeners = new HashSet<>();
		private SpeakerSettings settings;
		private int loopTicks;

		public TileEntityAudioSpeaker(BlockPos pos, BlockState state) {
			super(BlockEntityTypes.AUDIO_SPEAKER_TILE_ENTITY.get(), pos, state);
			settings = SpeakerSettings.defaults(pos == null ? BlockPos.ZERO : pos);
		}

		@Override
		public void readCompoundTag(CompoundTag tag) {
			stopLoop();
			settings = SpeakerSettings.read(tag, worldPosition);
			trigger.reset();
			loopTicks = 0;
		}

		@Override
		public void writeCompoundTag(CompoundTag tag) {
			settings.write(tag);
		}

		public void applySettings(SpeakerSettings settings) {
			stopLoop();
			this.settings = settings;
			trigger.reset();
			loopTicks = 0;
			if (level instanceof ServerLevel) {
				final ServerLevel world = (ServerLevel) level;
				if (settings.mode == SpeakerSettings.Mode.REDSTONE) trigger.sample(world.hasNeighborSignal(worldPosition));
				if (settings.mode == SpeakerSettings.Mode.REGION) trigger.sample(regionOccupied(world));
			}
			setChanged();
			syncData();
		}

		public BlockState appearance() {
			final BlockState state = BlockAudioSpeaker.appearance(settings.appearance);
			return state == null ? Blocks.JUKEBOX.defaultBlockState() : state;
		}

		private void tickSpeaker(ServerLevel world) {
			if (settings.sound.isEmpty()) return;
			switch (settings.mode) {
				case LOOP:
					final String hash = AudioServer.getUploadedHash(settings.sound);
					if (world.hasNeighborSignal(worldPosition) || hash == null) {
						stopLoop();
						loopTicks = 0;
					} else if (loopTicks++ % 5 == 0) {
						updateLoop(world, hash, loopTicks % 20 == 1);
					}
					break;
				case REDSTONE:
					samplePower(world);
					break;
				case REGION:
					if (trigger.sample(regionOccupied(world))) playOnce(world);
					break;
			}
		}

		private void samplePower(ServerLevel world) {
			if (settings.sound.isEmpty()) return;
			final boolean powered = world.hasNeighborSignal(worldPosition);
			if (settings.mode == SpeakerSettings.Mode.LOOP && powered) stopLoop();
			if (settings.mode == SpeakerSettings.Mode.REDSTONE && trigger.sample(powered)) playOnce(world);
		}

		private boolean regionOccupied(ServerLevel world) {
			return world.players().stream().anyMatch(player -> !player.isSpectator() && inside(player, settings.triggerCenter, settings.triggerRadius));
		}

		private void playOnce(ServerLevel world) {
			final String hash = AudioServer.getUploadedHash(settings.sound);
			if (hash == null) return;
			for (final ServerPlayer player : world.players()) {
				if (inside(player, worldPosition, settings.radius)) {
					AudioSpeakerPackets.play(player, world, worldPosition, settings, hash, AudioSpeakerPackets.Action.ONCE);
				}
			}
		}

		private void updateLoop(ServerLevel world, String hash, boolean refresh) {
			listeners.removeIf(id -> {
				final ServerPlayer player = world.getServer().getPlayerList().getPlayer(id);
				if (player != null && player.serverLevel() == world) {
					if (inside(player, worldPosition, settings.radius)) return false;
					AudioSpeakerPackets.stop(player, world, worldPosition);
				}
				return true;
			});
			for (final ServerPlayer player : world.players()) {
				if (inside(player, worldPosition, settings.radius) && (listeners.add(player.getUUID()) || refresh)) {
					AudioSpeakerPackets.play(player, world, worldPosition, settings, hash, AudioSpeakerPackets.Action.LOOP);
				}
			}
		}

		private void stopLoop() {
			if (level instanceof ServerLevel) {
				final ServerLevel world = (ServerLevel) level;
				for (final UUID id : listeners) {
					final ServerPlayer player = world.getServer().getPlayerList().getPlayer(id);
					if (player != null && player.serverLevel() == world) AudioSpeakerPackets.stop(player, world, worldPosition);
				}
			}
			listeners.clear();
		}

		@Override
		public void setRemoved() {
			stopLoop();
			super.setRemoved();
		}

		private static boolean inside(ServerPlayer player, BlockPos center, int radius) {
			return SpeakerSettings.inRange(center, radius, player.getX(), player.getY(), player.getZ());
		}
	}
}
