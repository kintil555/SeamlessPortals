package com.warwa.seamlessportals.mixin;

import java.util.List;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;

/**
 * ImmPtl cancels vanilla chunk tracking (applyChunkTrackingView), so vanilla
 * ChunkMap.getPlayers(pos, boundaryOnly) returns an empty list. Mods that call it
 * directly (Axiom's chunk re-send after bulk edits) then never reach the client.
 * Answer from ImmPtl's tracking instead, same as MixinChunkHolder does for block updates.
 */
@Mixin(value = ChunkMap.class, priority = 1100)
public abstract class ChunkMapGetPlayersMixin {

    @Shadow @Final private ServerLevel level;

    @Inject(method = "getPlayers", at = @At("HEAD"), cancellable = true)
    private void seamlessportals$useImmPtlTracking(ChunkPos pos, boolean boundaryOnly,
                                                   CallbackInfoReturnable<List<ServerPlayer>> cir) {
        cir.setReturnValue(ImmPtlChunkTracking.getPlayersViewingChunk(
            this.level.dimension(), pos.x(), pos.z(), boundaryOnly));
    }
}
