package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.block.BlockAudioSpeaker;
import mtr.mappings.BlockEntityRendererMapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;

public final class RenderAudioSpeaker extends BlockEntityRendererMapper<BlockAudioSpeaker.TileEntityAudioSpeaker> {
	public RenderAudioSpeaker(BlockEntityRenderDispatcher dispatcher) {
		super(dispatcher);
	}

	@Override
	public void render(BlockAudioSpeaker.TileEntityAudioSpeaker speaker, float delta, PoseStack matrices, MultiBufferSource buffers, int light, int overlay) {
		Minecraft.getInstance().getBlockRenderer().renderSingleBlock(speaker.appearance(), matrices, buffers, light, overlay);
	}
}
