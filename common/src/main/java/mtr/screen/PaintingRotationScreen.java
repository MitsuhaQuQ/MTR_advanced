package mtr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.client.ImportedImages;
import mtr.compat.GuiCompat;
import mtr.compat.ScreenBase;
import mtr.image.PaintingSettings;
import mtr.image.PaintingTransform;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/** Projected XYZ rotation rings about the image centre. */
final class PaintingRotationScreen extends ScreenBase {
	final ImportedPaintingScreen editor;
	private static final int[] COLORS = {0xFFFF6666, 0xFF66DD88, 0xFF6699FF};
	private int axis = -1;
	private double lastAngle, angleValue;
	private PaintingSettings shown;
	PaintingRotationScreen(ImportedPaintingScreen editor) { super(Text.translatable("gui.mtr.image_gizmo")); this.editor = editor; shown = editor.draft(); }
	@Override protected void init() {
		button("gui.done", width / 2 - 104, () -> onClose());
		button("gui.mtr.image_rotation_reset", width / 2 + 4, () -> { for (int i = 0; i < 3; i++) editor.setRotation(i, 0); });
	}
	private void button(String key, int x, Runnable run) {
		Button button = UtilitiesClient.newButton(Text.translatable(key), b -> run.run());
		UtilitiesClient.setWidgetX(button, x); UtilitiesClient.setWidgetY(button, height - 27); button.setWidth(100); addDrawableChild(button);
	}
	private double scale() { return Math.max(24, Math.min(width * 0.32, (height - 100) * 0.43)); }
	private double[] project(double x, double y, double z) {
		return new double[]{width / 2.0 + scale() * (-0.8660254 * x + 0.5 * z), height / 2.0 + scale() * (-0.25 * x - 0.8660254 * y - 0.4330127 * z)};
	}
	private double[] ring(int axis, double angle) {
		double c = Math.cos(angle), s = Math.sin(angle);
		PaintingSettings settings = shown;
		double[] p = axis == 0 ? new double[]{0,c,s} : axis == 1 ? new double[]{s,0,c} : new double[]{c,s,0};
		// Euler X acts inside Y/Z, Y inside Z; display the actual axis of each numeric control.
		p = PaintingTransform.rotate(p[0],p[1],p[2],0,axis==0?settings.rotationY:0,axis<2?settings.rotationZ:0);
		return project(p[0],p[1],p[2]);
	}
	private double angleAt(int axis, double x, double y) {
		double[] u = ring(axis, 0), v = ring(axis, Math.PI / 2);
		double cx = width / 2.0, cy = height / 2.0;
		double ux = u[0]-cx, uy = u[1]-cy, vx = v[0]-cx, vy = v[1]-cy, det = ux*vy-uy*vx;
		if (Math.abs(det) < 1e-6) return Math.atan2(y-cy,x-cx);
		return Math.atan2((ux*(y-cy)-uy*(x-cx))/det, ((x-cx)*vy-(y-cy)*vx)/det);
	}
	@Override public boolean mouseClicked(double x, double y, int button) {
		if (super.mouseClicked(x, y, button)) return true;
		if (button != 0) return false;
		shown = editor.draft();
		double best = 8; int chosen = -1;
		for (int i = 0; i < 3; i++) {
			double distance = Double.POSITIVE_INFINITY;
			for (int sample=0;sample<192;sample++) { double[] p=ring(i,sample*Math.PI/96); distance=Math.min(distance,Math.hypot(x-p[0],y-p[1])); }
			if (distance < best) { best = distance; chosen = i; }
		}
		if (chosen < 0) return false;
		axis = chosen; lastAngle = angleAt(axis, x, y);
		PaintingSettings s = editor.draft(); angleValue = axis == 0 ? s.rotationX : axis == 1 ? s.rotationY : s.rotationZ;
		return true;
	}
	@Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		if (axis < 0 || button != 0) return super.mouseDragged(x,y,button,dx,dy);
		double next = angleAt(axis,x,y);
		angleValue += PaintingTransform.wrap(Math.toDegrees(next-lastAngle)); lastAngle = next;
		editor.setRotation(axis, hasShiftDown() ? Math.round(angleValue / 15) * 15 : angleValue);
		return true;
	}
	@Override public boolean mouseReleased(double x, double y, int button) { axis = -1; return super.mouseReleased(x,y,button); }
	@Override public void onClose() { minecraft.setScreen(editor); }
	@Override public boolean isPauseScreen() { return false; }
	@Override public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		renderBackground(matrices);
		GuiCompat.drawCenteredString(matrices, font, title, width/2, 9, 0xFFFFFF);
		GuiCompat.drawCenteredString(matrices, font, Text.translatable("gui.mtr.image_gizmo_help"), width/2, 24, 0xCCCCCC);
		PaintingSettings s = editor.draft(); shown = s;
		ResourceLocation texture = s.image.isEmpty() ? null : ImportedImages.texture(s.image);
		if (texture != null) {
			MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
			RenderType layer = RenderType.entityTranslucent(texture);
			VertexConsumer buffer = buffers.getBuffer(layer);
			double size = Math.max(s.width,s.height), w = s.width/size * 0.65, h = s.height/size * 0.65;
			double[][] corners = {{w,-h,0},{-w,-h,0},{-w,h,0},{w,h,0}};
			double[] normal = PaintingTransform.rotate(0,0,-1,s.rotationX,s.rotationY,s.rotationZ);
			boolean back = normal[0]*-0.4330127 + normal[1]*0.5 + normal[2]*-0.75 < 0;
			for (int i=0;i<4;i++) {
				double[] r = PaintingTransform.rotate(corners[i][0],corners[i][1],0,s.rotationX,s.rotationY,s.rotationZ), p = project(r[0],r[1],r[2]);
				float u = i==0||i==3?0:1;
				buffer.vertex(matrices.last().pose(),(float)p[0],(float)p[1],0).color(255,255,255,255).uv(back?1-u:u,i<2?1:0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(GuiCompat.FULL_BRIGHT).normal(0,0,1).endVertex();
			}
			buffers.endBatch(layer);
		}
		GuiGraphics graphics = GuiCompat.graphics(matrices);
		for (int a=0;a<3;a++) {
			for(int i=0;i<96;i++) line(graphics,ring(a,i*Math.PI/48),ring(a,(i+1)*Math.PI/48),a==axis?0xFFFFFFFF:COLORS[a]);
			double[] label = ring(a,0.55);
			GuiCompat.draw(matrices,font,("XYZ".substring(a,a+1)),(float)label[0],(float)label[1],COLORS[a]);
		}
		graphics.fill(width/2-2,height/2-2,width/2+3,height/2+3,0xFFFFFFFF);
		graphics.flush();
		GuiCompat.drawCenteredString(matrices,font,String.format(java.util.Locale.ROOT,"X %.1f°   Y %.1f°   Z %.1f°",s.rotationX,s.rotationY,s.rotationZ),width/2,height-43,0xFFFFFF);
		super.render(matrices,mouseX,mouseY,delta);
	}
	private static void line(GuiGraphics graphics,double[] a,double[] b,int color) {
		int count=Math.max(1,(int)Math.ceil(Math.max(Math.abs(b[0]-a[0]),Math.abs(b[1]-a[1]))));
		for(int i=0;i<=count;i++) { int x=(int)Math.round(a[0]+(b[0]-a[0])*i/count), y=(int)Math.round(a[1]+(b[1]-a[1])*i/count); graphics.fill(x,y,x+1,y+1,color); }
	}
}
