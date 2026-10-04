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
        // SERVER engine only: the client engine must not invent light from this server-side cache
        // (it is never re-checked when seeds drop, which left stale light until a reload).
        if (!(chunkSource.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
        BlockState override = CrossDimLight.overrideFor(level, pos);
        if (override != null) {
            CrossDimLight.SERVER_HITS.incrementAndGet();
            cir.setReturnValue(override);
        }
    }
}
