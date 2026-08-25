package com.mapsyncer.mixin;

import com.mapsyncer.client.XaeroBridge;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "xaero.map.MapProcessor", remap = false)
public abstract class MapProcessorMixin {

    @Inject(
            method = "updateWorldSynced",
            at =
                    @At(
                            value = "FIELD",
                            target = "Lxaero/map/MapProcessor;currentMWId:Ljava/lang/String;",
                            opcode = Opcodes.PUTFIELD,
                            ordinal = 0,
                            shift = At.Shift.AFTER),
            require = 1)
    private void mapsyncer$onMapContextAssigned(CallbackInfo ci) {
        XaeroBridge.onMWIdChanged();
    }
}
