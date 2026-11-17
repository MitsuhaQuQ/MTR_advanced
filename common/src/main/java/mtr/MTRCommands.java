package mtr;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import mtr.compat.Compat;
import mtr.data.RailwayData;
import mtr.data.Siding;
import mtr.data.TrainServer;
import mtr.image.ImageServer;
import mtr.item.ItemPlatformEdge;
import mtr.mappings.Text;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The {@code /mtr} commands other than {@code deleteaudio} ({@link mtr.audio.AudioServer#registerCommand}), which brigadier
 * puts under the same {@code mtr} (0.95.0):
 * <ul>
 * <li>{@code /mtr boarding}: puts the player in the middle of the nearest car of the nearest train running in their
 * world and takes them on board, as stepping in through an open door would, whether the train's doors are open or not.
 * Trains standing in their sidings don't count. Who may use it is {@link SecurityConfig#boardingPermissionLevel},
 * operators by default, as it takes a player anywhere.</li>
 * <li>{@code /mtr deletepic <namespace>:<file name>}: deletes an imported image (the editor's images page), with the same
 * rules: anyone their own, managers anyone's; the console and command blocks by {@code imageManagePermissionLevel}.</li>
 * <li>{@code /mtr undoedge} (0.96.0): takes back the player's last platform edge strip laid by sneaking
 * ({@link ItemPlatformEdge#undoLastLay}); the message after laying has a button running it.</li>
 * </ul>
 */
public final class MTRCommands {

	private MTRCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("mtr").then(Commands.literal("boarding")
				.requires(source -> source.hasPermission(SecurityConfig.boardingPermissionLevel))
				.executes(context -> board(context.getSource()))));
		dispatcher.register(Commands.literal("mtr").then(Commands.literal("deletepic").then(Commands.argument("name", StringArgumentType.greedyString())
				.suggests((context, builder) -> {
					final CommandSourceStack source = context.getSource();
					final ServerPlayer player = source.getEntity() instanceof ServerPlayer ? (ServerPlayer) source.getEntity() : null;
					return SharedSuggestionProvider.suggest(ImageServer.deletableNames(player, source.hasPermission(SecurityConfig.imageManagePermissionLevel)), builder);
				})
				.executes(context -> deletePicture(context.getSource(), StringArgumentType.getString(context, "name").trim())))));
		dispatcher.register(Commands.literal("mtr").then(Commands.literal("undoedge")
				.requires(source -> source.getEntity() instanceof ServerPlayer)
				.executes(context -> undoEdge(context.getSource()))));
	}

	private static int undoEdge(CommandSourceStack source) {
		final int removed = ItemPlatformEdge.undoLastLay((ServerPlayer) source.getEntity());
		if (removed < 0) {
			source.sendFailure(Text.translatable("gui.mtr.platform_edge_nothing_to_undo"));
			return 0;
		}
		source.sendSuccess(() -> Text.translatable("gui.mtr.platform_edge_undone", removed), false);
		return 1;
	}

	private static int deletePicture(CommandSourceStack source, String reference) {
		final ServerPlayer player = source.getEntity() instanceof ServerPlayer ? (ServerPlayer) source.getEntity() : null;
		final String error = ImageServer.deleteByCommand(source.getServer(), player, source.hasPermission(SecurityConfig.imageManagePermissionLevel), reference);
		if (error != null) {
			source.sendFailure(Text.translatable(error, reference));
			return 0;
		}
		source.sendSuccess(() -> Text.translatable("gui.mtr.image_deleted_named", reference), true);
		return 1;
	}

	private static int board(CommandSourceStack source) {
		if (!(source.getEntity() instanceof ServerPlayer)) {
			source.sendFailure(Text.translatable("gui.mtr.boarding_players_only"));
			return 0;
		}
		final ServerPlayer player = (ServerPlayer) source.getEntity();
		final Level world = Compat.getLevel(player);
		final RailwayData railwayData = RailwayData.getInstance(world);
		if (railwayData == null || !(world instanceof ServerLevel)) {
			source.sendFailure(Text.translatable("gui.mtr.boarding_no_train"));
			return 0;
		}

		// The nearest car of any train out on its route
		final Vec3 position = player.position();
		final TrainServer[] bestTrain = {null};
		final int[] bestCar = {0};
		final Vec3[] bestCentre = {null};
		final double[] bestDistance = {Double.MAX_VALUE};
		for (final Siding siding : railwayData.sidings) {
			siding.forEachTrain(train -> {
				if (!train.isOnRoute()) {
					return;
				}
				final Vec3[] centres = train.getCarCentres();
				if (centres == null) {
					return;
				}
				for (int car = 0; car < centres.length; car++) {
					final double distance = centres[car].distanceTo(position);
					if (distance < bestDistance[0]) {
						bestDistance[0] = distance;
						bestTrain[0] = train;
						bestCar[0] = car;
						bestCentre[0] = centres[car];
					}
				}
			});
		}
		if (bestTrain[0] == null) {
			source.sendFailure(Text.translatable("gui.mtr.boarding_no_train"));
			return 0;
		}

		final Vec3 centre = bestCentre[0];
		player.teleportTo((ServerLevel) world, centre.x, centre.y, centre.z, player.getYRot(), player.getXRot());
		bestTrain[0].board(world, player, bestCar[0]);
		source.sendSuccess(() -> Text.translatable("gui.mtr.boarding_done", bestCar[0] + 1, Math.round(bestDistance[0])), false);
		return 1;
	}
}
