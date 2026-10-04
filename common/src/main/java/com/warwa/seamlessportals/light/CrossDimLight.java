package com.warwa.seamlessportals.light;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.Portal;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.warwa.seamlessportals.portal.PortalInfo;
import com.warwa.seamlessportals.portal.PortalLink;
import com.warwa.seamlessportals.portal.PortalManager;
import com.warwa.seamlessportals.portal.PortalTransform;

/**
 * Block light crossing between dimensions through portals.
 *
 * Every {@link #INTERVAL} ticks, for each linked portal, the cells adjacent to the source
 * plane (one layer each side) get an "emission" equal to the block light of the cell just
 * across the plane in the destination dimension, minus 1. The light engine then sees those
 * (air) cells as light sources through {@code LightEngine.getState} (see
 * LightEngineCrossDimMixin), so light spreads naturally from one dimension into the other
 * and the shading at the portal border is continuous.
 *
 * Each crossing costs 1 level per side, so a round trip strictly decreases: no light loops.
 */
public final class CrossDimLight {
    public static final int INTERVAL = 10;
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Incremented by the mixin each time an override is actually served (diagnostics). */
    public static final AtomicInteger OVERRIDE_HITS = new AtomicInteger();
    public static final AtomicInteger SERVER_HITS = new AtomicInteger();
    private static int diagCounter;
    private static int lastServerTick = -1;

    /** dimension -> (packed pos -> emission level 1..14), read from the light thread. */
    private static final Map<ResourceKey<Level>, Map<Long, Byte>> CACHE = new ConcurrentHashMap<>();
    private static final BlockState[] LIGHT_STATES = new BlockState[16];
    private static int tickCounter;

    static {
        for (int i = 0; i < 16; i++) {
            LIGHT_STATES[i] = Blocks.LIGHT.defaultBlockState().setValue(BlockStateProperties.LEVEL, i);
        }
    }

    private CrossDimLight() {}

    /** Called from the light engine thread; cheap lookup only. Returns null if no override. */
    public static BlockState overrideFor(Level level, BlockPos pos) {
        if (CACHE.isEmpty()) return null;
        Map<Long, Byte> map = CACHE.get(level.dimension());
        if (map == null) return null;
        Byte v = map.get(pos.asLong());
        return v == null ? null : LIGHT_STATES[v];
    }

    public static void tick(MinecraftServer server) {
        if (server == null) return;
        if (server.getTickCount() == lastServerTick) return; // may be called from 2 hooks
        lastServerTick = server.getTickCount();
        if (++tickCounter % INTERVAL != 0) return;
        Map<ResourceKey<Level>, Map<Long, Byte>> next = new HashMap<>();
        int linkCount = 0, maxRemote = 0;

        for (PortalLink link : PortalManager.getServerInstance().getAllLinks()) {
            PortalInfo src = link.getSource();
            PortalInfo dst = link.getDestination();
            ServerLevel srcLevel = server.getLevel(src.getDimension());
            ServerLevel dstLevel = server.getLevel(dst.getDimension());
            if (srcLevel == null || dstLevel == null) continue;
            linkCount++;

            Vec3 normal = src.getNormal();
            Vec3 center = src.getCenter();
            AABB box = src.getBoundingBox().inflate(1.5);
            BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();

            for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX); x++) {
                for (int y = (int) Math.floor(box.minY); y <= (int) Math.floor(box.maxY); y++) {
                    for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ); z++) {
                        Vec3 c = new Vec3(x + 0.5, y + 0.5, z + 0.5);
                        double d = c.subtract(center).dot(normal);
                        if (Math.abs(Math.abs(d) - 1.0) > 0.3) continue; // adjacent layers only
                        // in-plane: stay within the portal opening (+1 margin)
                        Vec3 rel = c.subtract(center).subtract(normal.scale(d));
                        if (!src.getBoundingBox().inflate(1.0).contains(center.add(rel))) continue;

                        cell.set(x, y, z);
                        if (!srcLevel.hasChunkAt(cell) || !srcLevel.getBlockState(cell).isAir()) continue;

                        // transformTeleportPoint negates depth, so the cell at d=+1 maps to the destination's
                        // d'=-1 cell: exactly what the viewer sees just beyond the window.
                        Vec3 across = c;
                        Vec3 dp = PortalTransform.transformTeleportPoint(src, dst, src.getType(), across);
                        BlockPos dpos = BlockPos.containing(dp);
                        if (!dstLevel.hasChunkAt(dpos)) continue;

                        int remote = dstLevel.getBrightness(LightLayer.BLOCK, dpos);
                        if (remote > maxRemote) maxRemote = remote;
                        int emission = remote - 1;
                        if (emission < 1) continue;
                        next.computeIfAbsent(src.getDimension(), k -> new HashMap<>())
                            .merge(cell.asLong(), (byte) emission, (a, b) -> (byte) Math.max(a, b));
                    }
                }
            }
        }


        // ---- Entity portals (the flag-ON engine: qouteall Portal entities). ----
        // IP-style portals show the destination 1:1 through the plane (transformPoint, no depth
        // negation), so a front-side cell at +d sees the destination at the mirrored point -d.
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player.level() instanceof ServerLevel sl)) continue;
            for (Portal portal : McHelper.getEntitiesNearby(sl, player.position(), Portal.class, 48.0)) {
                if (!portal.isAlive()) continue;
                ServerLevel dstLevel = server.getLevel(portal.getDestDim());
                if (dstLevel == null) continue;
                linkCount++;
                Vec3 origin = portal.getOriginPos();
                Vec3 normal = portal.getNormal();
                Vec3 axisW = portal.getAxisW();
                Vec3 axisH = portal.getAxisH();
                double halfW = portal.getWidth() / 2.0 + 1.0;
                double halfH = portal.getHeight() / 2.0 + 1.0;
                int r = (int) Math.min(12, Math.ceil(Math.max(halfW, halfH)) + 2);
                int ox = (int) Math.floor(origin.x), oy = (int) Math.floor(origin.y), oz = (int) Math.floor(origin.z);
                BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
                for (int x = ox - r; x <= ox + r; x++) {
                    for (int y = oy - r; y <= oy + r; y++) {
                        for (int z = oz - r; z <= oz + r; z++) {
                            Vec3 c = new Vec3(x + 0.5, y + 0.5, z + 0.5);
                            Vec3 rel = c.subtract(origin);
                            double d = rel.dot(normal);
                            if (Math.abs(d) < 0.5 || Math.abs(d) > 1.5) continue; // adjacent layer only
                            if (Math.abs(rel.dot(axisW)) > halfW || Math.abs(rel.dot(axisH)) > halfH) continue;
                            cell.set(x, y, z);
                            if (!sl.hasChunkAt(cell) || !sl.getBlockState(cell).isAir()) continue;
                            Vec3 across = c.subtract(normal.scale(2.0 * d));
                            BlockPos dpos = BlockPos.containing(portal.transformPoint(across));
                            if (!dstLevel.hasChunkAt(dpos)) continue;
                            int remote = dstLevel.getBrightness(LightLayer.BLOCK, dpos);
                            if (remote > maxRemote) maxRemote = remote;
                            int emission = remote - 1;
                            if (emission < 1) continue;
                            next.computeIfAbsent(sl.dimension(), k -> new HashMap<>())
                                .merge(cell.asLong(), (byte) emission, (a, b) -> (byte) Math.max(a, b));
                        }
                    }
                }
            }
        }

        if (++diagCounter % 20 == 0) { // every ~10s
            int seeded = 0;
            for (Map<Long, Byte> m : next.values()) seeded += m.size();
            LOGGER.info("[SEAMLESS LIGHT] links={} seededCells={} maxRemote={} clientHits={} serverHits={}",
                linkCount, seeded, maxRemote, OVERRIDE_HITS.get(), SERVER_HITS.get());
            // Does the SERVER engine actually store the seeded level? (stored < emission => engine ignores us)
            int shown = 0;
            for (Map.Entry<ResourceKey<Level>, Map<Long, Byte>> de : next.entrySet()) {
                ServerLevel dl = server.getLevel(de.getKey());
                if (dl == null) continue;
                for (Map.Entry<Long, Byte> ce : de.getValue().entrySet()) {
                    if (shown++ >= 4) break;
                    BlockPos bp = BlockPos.of(ce.getKey());
                    LOGGER.info("[SEAMLESS LIGHT]   cell {} emission={} serverBlockLight={}",
                        bp, ce.getValue(), dl.getBrightness(LightLayer.BLOCK, bp));
                }
            }
        }

        // Diff against the live cache and re-check changed cells so the engine relights them.
        List<ResourceKey<Level>> dims = new ArrayList<>(CACHE.keySet());
        for (ResourceKey<Level> k : next.keySet()) if (!dims.contains(k)) dims.add(k);
        for (ResourceKey<Level> dim : dims) {
            ServerLevel level = server.getLevel(dim);
            Map<Long, Byte> fresh = next.getOrDefault(dim, Map.of());
            Map<Long, Byte> live = CACHE.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
            List<Long> changed = new ArrayList<>();
            for (Map.Entry<Long, Byte> e : fresh.entrySet()) {
                Byte old = live.put(e.getKey(), e.getValue());
                if (old == null || !old.equals(e.getValue())) changed.add(e.getKey());
            }
            for (Long key : new ArrayList<>(live.keySet())) {
                if (!fresh.containsKey(key)) {
                    live.remove(key);
                    changed.add(key);
                }
            }
            if (level != null) {
                for (long packed : changed) {
                    level.getLightEngine().checkBlock(BlockPos.of(packed));
                }
            }
            if (live.isEmpty()) CACHE.remove(dim);
        }
    }
}
