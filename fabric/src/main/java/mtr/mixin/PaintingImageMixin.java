package mtr.mixin;

import mtr.image.PaintingImage;
import mtr.image.PaintingSettings;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.*;
import net.minecraft.world.entity.decoration.Painting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Painting.class)
public abstract class PaintingImageMixin implements PaintingImage {
	@Unique private static final EntityDataAccessor<String> MTR_IMAGE = SynchedEntityData.defineId(Painting.class, EntityDataSerializers.STRING);
	@Unique private SynchedEntityData mtr$data() { return ((Painting) (Object) this).getEntityData(); }
	@Inject(method = "defineSynchedData", at = @At("TAIL"))
	private void mtr$define(CallbackInfo ci) { mtr$data().define(MTR_IMAGE, ""); }
	@Override public String mtr$getImageSettings() { return mtr$data().get(MTR_IMAGE); }
	@Override public void mtr$setImageSettings(String settings) { mtr$data().set(MTR_IMAGE, PaintingSettings.parse(settings).json()); }
	@Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
	private void mtr$save(CompoundTag tag, CallbackInfo ci) { if (!mtr$getImageSettings().isEmpty()) tag.putString("mtr_image", mtr$getImageSettings()); }
	@Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
	private void mtr$load(CompoundTag tag, CallbackInfo ci) {
		try { if (tag.contains("mtr_image")) mtr$setImageSettings(tag.getString("mtr_image")); }
		catch (RuntimeException e) { mtr$data().set(MTR_IMAGE, ""); }
	}
}
