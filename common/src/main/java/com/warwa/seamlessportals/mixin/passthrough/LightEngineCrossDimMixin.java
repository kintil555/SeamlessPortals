package com.warwa.seamlessportals.mixin.passthrough;

import com.warwa.seamlessportals.light.CrossDimLight;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Feeds cross-dimension light (see {@link CrossDimLight}) into the server light engine. */
@Mixin(LightEngine.class)
public abstract class LightEngineCrossDimMixin {

    @Shadow
    @Final
    protected LightChunkGetter chunkSource;

    @Inject(method = "getState", at = @At("RETURN"), cancellable = true, require = 0)
    private void seamlessportals$crossDimLight(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        BlockState state = cir.getReturnValue();
        if (state == null || !state.isAir()) return;
        // Server AND client engine: IP only ships server light for boundary chunks, the client
        // computes the rest itself from block data, so it needs the same override. The client
        // re-checks changed cells via CrossDimLight.CLIENT_RECHECK (no stale light on removal).
        if (!(chunkSource.getLevel() instanceof net.minecraft.world.level.Level level)) return;
        BlockState override = CrossDimLight.overrideFor(level, pos);
        if (override != null) {
            (level instanceof net.minecraft.server.level.ServerLevel ? CrossDimLight.SERVER_HITS : CrossDimLight.OVERRIDE_HITS).incrementAndGet();
            cir.setReturnValue(override);
        }
    }
}
