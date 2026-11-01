package cn.zbx1425.mtrsteamloco.mixin;

#if MC_VERSION >= "12000"
import mtr.compat.MaterialColor;
#else
import net.minecraft.world.level.material.MaterialColor;
#endif
import mtr.data.RailType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RailType.class)
public abstract class RailTypeMixin {
    @Shadow(remap = false) @Final @Mutable
    private static RailType[] $VALUES;
    private static final Map<String, RailType> MAP = new HashMap<>();

    @Invoker(value = "<init>")
    private static RailType create(String name, int ordinal, int speedLimit, MaterialColor materialColor, boolean hasSavedRail, boolean canAccelerate, boolean hasSignal, RailType.RailSlopeStyle railSlopeStyle) {
        throw new IllegalStateException();
    }

    static {
        List<RailType> railTypes = new ArrayList<>();
        railTypes.addAll(Arrays.asList($VALUES));
        for (int i = -1; i <= 600; i++) railTypes.add(create("P" + i, railTypes.size(), i, MaterialColor.EMERALD, false, true, true, RailType.RailSlopeStyle.CURVE));

        for (int i = 1000; i <= 10000; i+= 500) railTypes.add(create("P" + i, railTypes.size(), i, MaterialColor.EMERALD, false, true, true, RailType.RailSlopeStyle.CURVE));

        RailType[] values = railTypes.toArray(new RailType[0]);
        $VALUES = values;

        for (RailType railType : $VALUES) {
            MAP.put(railType.name(), railType);
        }
    }

    @Mutable
    @Inject(method = "valueOf", at = @At("HEAD"), cancellable = true, remap = false)
    private static void valueOf(String name, CallbackInfoReturnable<RailType> cir) {
        cir.setReturnValue(MAP.get(name));
        cir.cancel();
        return;
    }
}