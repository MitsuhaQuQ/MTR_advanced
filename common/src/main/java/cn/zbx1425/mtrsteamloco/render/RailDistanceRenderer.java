package cn.zbx1425.mtrsteamloco.render;

import cn.zbx1425.mtrsteamloco.mixin.RailAccessor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import mtr.Blocks;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.data.Rail;
import mtr.mappings.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.apache.commons.lang3.StringUtils;
import net.minecraft.world.phys.Vec3;
import cn.zbx1425.sowcer.math.PoseStackUtil;
import cn.zbx1425.mtrsteamloco.data.RailExtraSupplier;
import cn.zbx1425.mtrsteamloco.ClientConfig;

import java.util.Map;

public class RailDistanceRenderer {
    private static Vec3 cameraPos = null;

    public static void render(PoseStack matrices, MultiBufferSource vertexConsumers) {
        cameraPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        synchronized (ClientData.RAILS) {
            for (Map<BlockPos, Rail> railMap : ClientData.RAILS.values()) {
                for (Rail rail : railMap.values()) {
                    render(rail, matrices, vertexConsumers);
                }
            }
        }
    }

    private static void render(Rail rail, PoseStack matrices, MultiBufferSource vertexConsumers) {
        double length = rail.getLength();
        int interval = ClientConfig.railDistanceRendererInterval;
        for (int i = 0; i < length - interval; i += interval) {
            render(rail, i, new String[] { Integer.toString(i) , ""}, matrices, vertexConsumers);
        }
        render(rail, length - 1E-4, new String[] { "", String.format("%.1f", length - 1E-4)}, matrices, vertexConsumers);
    }

    public static int adjustColor(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        
        double luminance = 0.299 * r + 0.587 * g + 0.114 * b;
        
        if (luminance < 128.0) {
            int newR = 255 - r;
            int newG = 255 - g;
            int newB = 255 - b;
            return (newR << 16) | (newG << 8) | newB;
        }
        
        return color;
    }

    private static void render(Rail rail, double i, String[] contents, PoseStack matrices, MultiBufferSource vertexConsumers) {
        Vec3 last = rail.getPosition(i);
        if (last.distanceToSqr(cameraPos) > ClientConfig.railDistanceRendererMaxDistanceSqr) return;
        Vec3 next = rail.getPosition(i + 1E-4);
        final float yaw = (float) Mth.atan2(next.x - last.x, next.z - last.z);
        final float pitch = (float) Mth.atan2(next.y - last.y, (float) Math.sqrt((next.x - last.x) * (next.x - last.x) + (next.z - last.z) *(next.z - last.z)));
        final float roll = RailExtraSupplier.getRollAngle(rail, i);

        matrices.pushPose();
        matrices.translate((float) last.x, (float) last.y, (float) last.z);
        PoseStackUtil.rotY(matrices, yaw);
        PoseStackUtil.rotX(matrices, -pitch);
        PoseStackUtil.rotZ(matrices, roll);// ((RailExtraSupplier) rail).getRenderReversed() ? -roll : 
        matrices.translate(0, 1.5F, 0);

        float scale = 0.05F;
        matrices.scale(-scale, -scale, scale);

        drawInBatch(contents, 0xFF << 24 | rail.hashCode() & 0xFFFFFF, matrices, vertexConsumers);

        matrices.popPose();
    }

    private static void drawInBatch(String[] contents, int color, PoseStack matrices, MultiBufferSource vertexConsumers) {
        color = adjustColor(color);
        float opacity = Minecraft.getInstance().options.getBackgroundOpacity(0.2F);
        int bgColor = (int)(opacity * 255.0F) << 24;
        Font font = Minecraft.getInstance().font;
        float yOffset = -contents.length / 2F * font.lineHeight;
        for (var text : contents) {
            if (text != null && !StringUtils.isEmpty(text)) {
                float xOffset = (float) (-font.width(text) / 2);
#if MC_VERSION >= "11904"
                font.drawInBatch(text, xOffset, yOffset, color, false, matrices.last().pose(), vertexConsumers, Font.DisplayMode.SEE_THROUGH, bgColor, LightTexture.FULL_BRIGHT, false);
#else
                font.drawInBatch(text, xOffset, yOffset, color, false, matrices.last().pose(), vertexConsumers, false, bgColor, LightTexture.FULL_BRIGHT);
#endif
            }
            yOffset += font.lineHeight + 2;
        }
    }
}