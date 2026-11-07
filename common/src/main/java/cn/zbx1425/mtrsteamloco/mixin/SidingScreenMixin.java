package cn.zbx1425.mtrsteamloco.mixin;

import cn.zbx1425.mtrsteamloco.Main;
import mtr.screen.SidingScreen;
import mtr.data.RailType;
import mtr.data.Train;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SidingScreen.class)
public abstract class SidingScreenMixin {
    
    @Redirect(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lmtr/data/RailType;ordinal()I",
            remap = false
        ),
        remap = false
    )
    private int modifySliderMaxValue(RailType railType) {
        // The server says whether ANTE's run hooks are on; off, the slider goes as far as MTR's own speeds
        return mtr.client.ClientData.anteRunHooks ? RailType.valueOf("P10000").ordinal() : railType.ordinal();
    }
}