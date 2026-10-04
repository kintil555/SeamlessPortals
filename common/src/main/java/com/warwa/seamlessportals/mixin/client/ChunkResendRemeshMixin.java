package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Axiom (and similar tools) apply bulk edits server-side and re-send the whole chunk
 * (ClientboundLevelChunkWithLightPacket) for a chunk the client already has.
 * Force a remesh of every section when that happens so the old mesh is not kept.
 */
@Mixin(ClientPacketListener.class)
public abstract class ChunkResendRemeshMixin {

    @Shadow private ClientLevel level;

    @Unique private boolean seamlessportals$hadChunk;

    @Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"))
    private void seamlessportals$markExisting(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        ClientLevel l = this.level;
        this.seamlessportals$hadChunk = l != null && l.getChunkSource().hasChunk(packet.getX(), packet.getZ());
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void seamlessportals$remeshResent(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        ClientLevel l = this.level;
        if (l == null || !this.seamlessportals$hadChunk) return;
        this.seamlessportals$hadChunk = false;
        int x = packet.getX(), z = packet.getZ();
        com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info("[AXIOM-DIAG] chunk resend remesh {} {}", x, z);
        for (int y = l.getMinSectionY(); y <= l.getMaxSectionY(); y++) {
            l.setSectionDirtyWithNeighbors(x, y, z);
        }
    }
}
