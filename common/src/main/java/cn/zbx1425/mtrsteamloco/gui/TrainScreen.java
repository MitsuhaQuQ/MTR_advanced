package cn.zbx1425.mtrsteamloco.gui;

import cn.zbx1425.mtrsteamloco.data.TrainExtraSupplier;
import mtr.data.TrainClient;
import cn.zbx1425.mtrsteamloco.data.ConfigResponder;
import mtr.mappings.Text;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
import cn.zbx1425.mtrsteamloco.network.PacketUpdateTrainCustomConfigs;
import cn.zbx1425.mtrsteamloco.KeyMappings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import mtr.client.ClientData;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;

import java.util.List;
import java.util.Map;

public class TrainScreen {

    public static void tick() {
		if (KeyMappings.TRAIN_SCREEN.isDown()) {
            final Minecraft client = Minecraft.getInstance();
            final LocalPlayer player = client.player;
            if (player == null) return;
			for (TrainClient train : ClientData.TRAINS) {
                if (train.isPlayerRiding(player)) {
                    Minecraft.getInstance().setScreen(createScreen(train, null));
                    break;
                }
			}
        }
    }

    public static Screen createScreen(TrainClient train, Screen parent) {
        TrainExtraSupplier supplier = (TrainExtraSupplier) train;
        Map<String, String> customConfigs = supplier.getCustomConfigs();
        Map<String, ConfigResponder> customResponders = supplier.getConfigResponders();

        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Text.translatable("gui.mtrsteamloco.train_screen.title"))
            .setDoesConfirmSave(false)
            .transparentBackground();
        ConfigEntryBuilder entryBuilder = builder.entryBuilder();
        ConfigCategory common = builder.getOrCreateCategory(
                Text.translatable("gui.mtrsteamloco.config.client.category.common")
        );

        List<AbstractConfigListEntry> entries = ConfigResponder.getEntrysFromMaps(customConfigs, customResponders, entryBuilder, () -> TrainScreen.createScreen(train, parent));

        if (entries.isEmpty()) {
            common.addEntry(entryBuilder.startTextDescription(
                Text.translatable("gui.mtrsteamloco.custom_config.null")
                ).build()
            );
        } else {
            for (AbstractConfigListEntry entry : entries) {
                common.addEntry(entry);
            }
        }
        builder.setSavingRunnable(() -> {
            supplier.setCustomConfigs(customConfigs);
            PacketUpdateTrainCustomConfigs.sendUpdateC2S(train);
        });
        return builder.build();
    }
}