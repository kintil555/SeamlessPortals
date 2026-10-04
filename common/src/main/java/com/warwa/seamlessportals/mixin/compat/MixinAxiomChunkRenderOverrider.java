package com.warwa.seamlessportals.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.MyGameRenderer;

/**
 * Axiom compat. Axiom's ChunkRenderOverrider is global static state that assumes
 * Minecraft.level never changes mid-frame. Portal passes swap Minecraft.level, so the
 * dirty-section compile consumed dirtyChunks in the portal pass and read the wrong
 * (destination) level -> selection/placed blocks never refreshed.
 * Fix: skip Axiom's upload/render in portal passes and always read the player's real level.
 */
@Pseudo
@Mixin(targets = "com.moulberry.axiom.render.ChunkRenderOverrider", remap = false)
public class MixinAxiomChunkRenderOverrider {
    private static boolean diagLogged;

    private static int diagCount;
    private static void diag(String what) {
        if (diagCount++ < 40) {
            com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info("[AXIOM-DIAG] override.{} destPass={}", what, MyGameRenderer.isInDestPass());
        }
    }
    @Inject(method = "setBlock", at = @At("HEAD"), require = 0)
    private static void seamlessportals$dSet(CallbackInfo ci) { diag("setBlock"); }
    @Inject(method = "revertBlock", at = @At("HEAD"), require = 0)
    private static void seamlessportals$dRevert(CallbackInfo ci) { diag("revertBlock"); }
    @Inject(method = "invalidateChunkSection", at = @At("HEAD"), require = 0)
    private static void seamlessportals$dInval(CallbackInfo ci) { diag("invalidateChunkSection"); }
    @Inject(method = "loadBlocks", at = @At("HEAD"), require = 0)
    private static void seamlessportals$dLoad(CallbackInfo ci) { diag("loadBlocks"); }

    @Inject(method = "uploadDirty", at = @At("HEAD"), cancellable = true, require = 0)
    private static void seamlessportals$skipUploadInPortalPass(CallbackInfo ci) {
        if (!diagLogged) { diagLogged = true; com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info("[AXIOM-DIAG] Axiom compat mixin ACTIVE (uploadDirty hooked)"); }
        if (MyGameRenderer.isInDestPass()) {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private static void seamlessportals$skipRenderInPortalPass(CallbackInfo ci) {
        if (MyGameRenderer.isInDestPass()) {
            ci.cancel();
        }
    }

    @WrapOperation(
        method = {"loadBlocks", "setBlock", "revertBlock"},
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;",
            opcode = 180 // GETFIELD
        ),
        require = 0
    )
    private static ClientLevel seamlessportals$realLevel(Minecraft mc, Operation<ClientLevel> original) {
        // player.level() is never swapped by portal render passes
        if (mc.player != null && mc.player.level() instanceof ClientLevel real) {
            return real;
        }
        return original.call(mc);
    }
}
