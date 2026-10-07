package colorfullights.light;

import colorfullights.Config;
import colorfullights.compat.CreateCompat;
import colorfullights.debug.Timings;
import colorfullights.compat.SableCompat;
import colorfullights.render.DynamicLightShader;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import colorfullights.ColorfulLights;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Colored light emitted by moving things: items held by any living entity, dropped items, items in frames,
 * burning entities, blazes and fireballs, and light-emitting blocks on Create contraptions and Sable sub-levels
 * (Create Aeronautics ships).
 * <p>
 * Light falls off with straight-line distance (a sphere, not vanilla's diamond) and is blocked by walls using a
 * per-source {@link LightVolume}. A source may also be a spot light that only shines into a cone (a flashlight:
 * light items tagged {@link #FLASHLIGHTS} held by an entity shine where it looks). With Sodium the terrain is lit per pixel in the chunk shader every frame
 * ({@link DynamicLightShader}), so lights move smoothly without re-meshing. Without Sodium, dynamic light is
 * baked into chunk meshes, which are rebuilt when a light moves to another block.
 * <p>
 * Sources live on the client thread. Other threads (chunk meshing, Flywheel) read the immutable {@link Snapshot}.
 */
public final class DynamicLights {
    /** Only entities this close to the camera emit dynamic light. */
    private static final double RANGE = 96;
    /** Visibility volumes recomputed per tick at most; the nearest lights go first. */
    private static final int VOLUME_BUDGET = 6;

    /** Held light items in this tag shine as a cone in the holder's view direction instead of all around. */
    public static final TagKey<Item> FLASHLIGHTS = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(ColorfulLights.MODID, "flashlights"));
    /** Half angles (degrees) of a held flashlight's cone: full light inside the inner one, none outside the outer. */
    private static final float FLASHLIGHT_INNER = 12f, FLASHLIGHT_OUTER = 28f;
    /** Color of flashlight items that aren't light blocks. */
    private static final int FLASHLIGHT_COLOR = 0xFFF2DC;
    /** A spot light that turned further than this (cosine, ~8 degrees) is published again (baked: re-meshed). */
    private static final float SPOT_TURN = 0.99f;
    /** Cone cosines of a light that shines all around (smoothstep(-2, -1, c) = 1 for any c). */
    private static final float NO_CONE_INNER = -1f, NO_CONE_OUTER = -2f;

    /** Where a light source is (or, for a spot light's direction, where it points) at a given partial tick. */
    @FunctionalInterface
    public interface Positioner {
        Vec3 at(float partialTick);
    }

    /** A light source, persistent across ticks. Client thread only. */
    public static final class Source {
        public final long key;
        Positioner positioner;
        /** Unit direction of a spot light, or null for a light shining all around. */
        Positioner direction;
        /** Cosines of the cone's half angles: full light inside cosInner, none outside cosOuter. */
        public float cosInner = NO_CONE_INNER, cosOuter = NO_CONE_OUTER;
        public int level;
        /** Saturated light color (brightest channel = 255), or -1 if unknown. */
        public int color;
        boolean seen;
        /** Level or color changed since the last tick. */
        boolean lightChanged;

        /** Position at the last tick. */
        double x, y, z;
        /** Spot direction at the last tick, and the one last published. */
        float dx, dy, dz;
        float pdx, pdy, pdz;

        /** Visibility volume, its origin block, and the block it was computed for. */
        public byte[] vis;
        public int ox, oy, oz;
        int vx = Integer.MIN_VALUE, vy, vz;
        /** Block the light was in at the last tick. */
        int bx = Integer.MIN_VALUE, by, bz;
        boolean volumeDirty = true;

        Source(long key, Positioner positioner) {
            this.key = key;
            this.positioner = positioner;
        }

        /** Interpolated position for the current frame. */
        public Vec3 framePosition(float partialTick) {
            return positioner.at(partialTick);
        }

        Vec3 tickPosition() {
            return framePosition(1f);
        }

        public boolean isSpot() {
            return direction != null;
        }

        /** Interpolated unit direction of a spot light for the current frame. */
        public Vec3 frameDirection(float partialTick) {
            return direction.at(partialTick);
        }
    }

    private static final Long2ObjectOpenHashMap<Source> SOURCES = new Long2ObjectOpenHashMap<>();

    // --- extension point for other mods ---

    /**
     * Adds light for an entity near the camera, next to the built-in sources. Use entity light indexes of 2 and up
     * (0 and 1 are the held item and fire / glowing mobs).
     */
    @FunctionalInterface
    public interface EntityProvider {
        void collect(Entity entity, Collector collector);
    }

    /**
     * Adds light that isn't tied to an entity, once per tick after the entity pass. Keys must have the sign bit set
     * (see {@link Collector#add(long, java.util.function.Supplier, int, int)}).
     */
    @FunctionalInterface
    public interface LevelProvider {
        void collect(ClientLevel level, Vec3 camera, double range, Collector collector);
    }

    private static final List<EntityProvider> ENTITY_PROVIDERS = new CopyOnWriteArrayList<>();
    private static final List<LevelProvider> LEVEL_PROVIDERS = new CopyOnWriteArrayList<>();

    /** Registers extra per-entity light sources. Client side, e.g. from FMLClientSetupEvent. */
    public static void addEntityProvider(EntityProvider provider) {
        ENTITY_PROVIDERS.add(provider);
    }

    /** Registers extra light sources that aren't entities. Client side, e.g. from FMLClientSetupEvent. */
    public static void addLevelProvider(LevelProvider provider) {
        LEVEL_PROVIDERS.add(provider);
    }

    /** Immutable view of the current sources for other threads. */
    public static final class Snapshot {
        final int count;
        final float[] pos;    // x, y, z per source (tick position)
        final int[] level;
        final int[] color;
        final byte[][] vis;
        final int[] origin;   // ox, oy, oz per source
        final float[] dir;    // spot direction x, y, z per source (0 if none)
        final float[] cone;   // cosInner, cosOuter per source

        Snapshot(List<Source> sources) {
            count = sources.size();
            dir = new float[count * 3];
            cone = new float[count * 2];
            pos = new float[count * 3];
            level = new int[count];
            color = new int[count];
            vis = new byte[count][];
            origin = new int[count * 3];
            for (int i = 0; i < count; i++) {
                Source s = sources.get(i);
                pos[i * 3] = (float) s.x;
                pos[i * 3 + 1] = (float) s.y;
                pos[i * 3 + 2] = (float) s.z;
                level[i] = s.level;
                color[i] = s.color;
                vis[i] = s.vis;
                origin[i * 3] = s.ox;
                origin[i * 3 + 1] = s.oy;
                origin[i * 3 + 2] = s.oz;
                dir[i * 3] = s.dx;
                dir[i * 3 + 1] = s.dy;
                dir[i * 3 + 2] = s.dz;
                cone[i * 2] = s.cosInner;
                cone[i * 2 + 1] = s.cosOuter;
            }
        }

        public boolean isEmpty() {
            return count == 0;
        }
    }

    private static final Snapshot EMPTY = new Snapshot(List.of());
    private static volatile Snapshot current = EMPTY;
    /** Bumped whenever {@link #current} changes, so caches of colors that include dynamic light can be dropped. */
    private static final AtomicInteger VERSION = new AtomicInteger();

    private DynamicLights() {
    }

    public static Snapshot current() {
        return current;
    }

    public static int version() {
        return VERSION.get();
    }

    /** All live sources that have a visibility volume. Client thread only. */
    public static Collection<Source> sources() {
        return SOURCES.values();
    }

    /** Whether dynamic light is drawn by the Sodium chunk shader instead of being baked into meshes. */
    public static boolean inShader() {
        return DynamicLightShader.isActive();
    }

    // --- collection ---

    /** Receives light sources while gathering. Client thread only. */
    public static final class Collector {
        private Collector() {
        }

        /** A light carried by an entity (index 0: held/dropped item, 1: fire or glowing mob), at its center. */
        public void add(Entity entity, int index, BlockState colorSource, int lightLevel) {
            add(entity, index, LightColors.colorOf(Block.getId(colorSource)), lightLevel);
        }

        public void add(Entity entity, int index, int rgb, int lightLevel) {
            add((long) entity.getId() << 8 | index, () -> pt -> {
                Vec3 p = entity.getPosition(pt);
                return new Vec3(p.x, p.y + entity.getBbHeight() * 0.5, p.z);
            }, rgb, lightLevel);
        }

        /**
         * A light identified by a stable key; the positioner is only created for new sources.
         * Keys with the sign bit set are reserved for sources that aren't entities (e.g. Sable sub-levels).
         */
        public void add(long key, java.util.function.Supplier<Positioner> positioner, BlockState colorSource, int lightLevel) {
            add(key, positioner, LightColors.colorOf(Block.getId(colorSource)), lightLevel);
        }

        public void add(long key, java.util.function.Supplier<Positioner> positioner, int rgb, int lightLevel) {
            Source s = put(key, positioner, rgb, lightLevel);
            if (s != null && s.direction != null) {
                // was a spot light under the same key (e.g. the held item changed)
                s.positioner = positioner.get();
                s.direction = null;
                s.cosInner = NO_CONE_INNER;
                s.cosOuter = NO_CONE_OUTER;
                s.dx = s.dy = s.dz = 0f;
                s.lightChanged = true;
            }
        }

        /** A spot light carried by an entity: from its eyes, in its view direction. */
        public void addSpot(Entity entity, int index, BlockState colorSource, int lightLevel, float innerDegrees, float outerDegrees) {
            addSpot(entity, index, LightColors.colorOf(Block.getId(colorSource)), lightLevel, innerDegrees, outerDegrees);
        }

        public void addSpot(Entity entity, int index, int rgb, int lightLevel, float innerDegrees, float outerDegrees) {
            addSpot((long) entity.getId() << 8 | index, () -> entity::getEyePosition, () -> entity::getViewVector,
                    rgb, lightLevel, innerDegrees, outerDegrees);
        }

        /**
         * A spot light: full light within {@code innerDegrees} of its direction, fading out towards
         * {@code outerDegrees} (half angles). The direction positioner must return unit vectors; like the position
         * positioner it is only created for new sources (or when a plain light under the same key becomes a spot).
         */
        public void addSpot(long key, java.util.function.Supplier<Positioner> positioner, java.util.function.Supplier<Positioner> direction,
                            int rgb, int lightLevel, float innerDegrees, float outerDegrees) {
            boolean existed = SOURCES.containsKey(key);
            Source s = put(key, positioner, rgb, lightLevel);
            if (s == null) return;
            if (s.direction == null) {
                if (existed) s.positioner = positioner.get(); // was a plain light, positioned elsewhere
                s.direction = direction.get();
                s.lightChanged = true;
            }
            float outer = Math.max(innerDegrees + 0.5f, outerDegrees);
            float cosInner = (float) Math.cos(Math.toRadians(Math.max(0f, innerDegrees)));
            float cosOuter = (float) Math.cos(Math.toRadians(Math.min(180f, outer)));
            if (cosInner != s.cosInner || cosOuter != s.cosOuter) s.lightChanged = true;
            s.cosInner = cosInner;
            s.cosOuter = cosOuter;
        }

        private Source put(long key, java.util.function.Supplier<Positioner> positioner, int rgb, int lightLevel) {
            if (lightLevel <= 0) return null;
            Source s = SOURCES.get(key);
            if (s == null) SOURCES.put(key, s = new Source(key, positioner.get()));
            int level = Math.min(15, lightLevel);
            int color = rgb < 0 ? -1 : ColorSampler.saturate(rgb);
            if (level != s.level || color != s.color) s.lightChanged = true;
            if (level > s.level) s.volumeDirty = true; // the visibility volume only reaches as far as the light did
            s.level = level;
            s.color = color;
            s.seen = true;
            return s;
        }
    }

    private static final Collector COLLECTOR = new Collector();

    // --- per-tick update (client thread) ---

    public static void tick(Minecraft mc) {
        long start = Timings.start();
        try {
            tickImpl(mc);
        } finally {
            Timings.end(Timings.Part.DYNAMIC_TICK, start);
        }
    }

    private static void tickImpl(Minecraft mc) {
        LongSet dirtySections = new LongOpenHashSet();
        boolean baked = !inShader();

        if (!Config.enabled || !Config.dynamicLights || mc.level == null || !LightColors.isReady()) {
            if (!SOURCES.isEmpty()) {
                if (baked) SOURCES.values().forEach(s -> addReach(s, dirtySections, s.bx, s.by, s.bz));
                SOURCES.clear();
                publish(mc, dirtySections);
            }
            return;
        }

        SOURCES.values().forEach(s -> s.seen = false);
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.distanceToSqr(cam) > RANGE * RANGE) continue;
            collect(entity, COLLECTOR);
            if (CreateCompat.LOADED) CreateCompat.collect(entity, COLLECTOR);
            for (EntityProvider provider : ENTITY_PROVIDERS) provider.collect(entity, COLLECTOR);
        }
        if (SableCompat.LOADED) SableCompat.collect(mc.level, cam, RANGE, COLLECTOR);
        for (LevelProvider provider : LEVEL_PROVIDERS) provider.collect(mc.level, cam, RANGE, COLLECTOR);

        boolean changed = SOURCES.values().removeIf(s -> {
            if (s.seen) return false;
            if (baked) addReach(s, dirtySections, s.bx, s.by, s.bz);
            return true;
        });

        List<Source> pending = new ArrayList<>();
        for (Source s : SOURCES.values()) {
            Vec3 p = s.tickPosition();
            int bx = (int) Math.floor(p.x), by = (int) Math.floor(p.y), bz = (int) Math.floor(p.z);
            if (bx != s.bx || by != s.by || bz != s.bz) {
                // moved to another block: publish the new position; baked meshes around both positions rebuild
                if (baked) {
                    addReach(s, dirtySections, s.bx, s.by, s.bz);
                    addReach(s, dirtySections, bx, by, bz);
                }
                s.bx = bx;
                s.by = by;
                s.bz = bz;
                changed = true;
            }
            s.x = p.x;
            s.y = p.y;
            s.z = p.z;
            if (s.direction != null) {
                Vec3 d = s.direction.at(1f);
                s.dx = (float) d.x;
                s.dy = (float) d.y;
                s.dz = (float) d.z;
                if (s.dx * s.pdx + s.dy * s.pdy + s.dz * s.pdz < SPOT_TURN) {
                    // turned: entity lighting (and baked meshes) follow the cone in steps
                    s.pdx = s.dx;
                    s.pdy = s.dy;
                    s.pdz = s.dz;
                    changed = true;
                    if (baked) addReach(s, dirtySections, s.bx, s.by, s.bz);
                }
            }
            // Which cells a light sees barely changes over one block, so the (costly) visibility volume is only
            // recomputed once the light is 2 blocks from where it was computed.
            if (Math.max(Math.abs(bx - s.vx), Math.max(Math.abs(by - s.vy), Math.abs(bz - s.vz))) >= 2) s.volumeDirty = true;
            if (s.volumeDirty) pending.add(s);
            if (s.lightChanged) {
                s.lightChanged = false;
                changed = true;
                if (baked) addReach(s, dirtySections, s.bx, s.by, s.bz);
            }
        }

        pending.sort(Comparator.comparingDouble(s -> cam.distanceToSqr(s.x, s.y, s.z)));
        for (int i = 0; i < pending.size() && i < VOLUME_BUDGET; i++) {
            Source s = pending.get(i);
            int bx = (int) Math.floor(s.x), by = (int) Math.floor(s.y), bz = (int) Math.floor(s.z);
            long volumeStart = Timings.start();
            s.vis = LightVolume.compute(mc.level, bx, by, bz, s.level);
            Timings.end(Timings.Part.LIGHT_VOLUME, volumeStart);
            s.ox = bx - LightVolume.CENTER;
            s.oy = by - LightVolume.CENTER;
            s.oz = bz - LightVolume.CENTER;
            s.vx = bx;
            s.vy = by;
            s.vz = bz;
            s.volumeDirty = false;
            if (baked) addReach(s, dirtySections, s.bx, s.by, s.bz);
            changed = true;
        }

        if (changed || !dirtySections.isEmpty()) publish(mc, dirtySections);
    }

    /** Called from the client thread when a block changes: lights that can see it recompute their volume. */
    public static void onBlockChanged(BlockPos pos) {
        for (Source s : SOURCES.values()) {
            if (s.vis == null) continue;
            int x = pos.getX() - s.ox, y = pos.getY() - s.oy, z = pos.getZ() - s.oz;
            if (x >= 0 && y >= 0 && z >= 0 && x < LightVolume.SIZE && y < LightVolume.SIZE && z < LightVolume.SIZE) {
                s.volumeDirty = true;
            }
        }
    }

    /**
     * Dynamic light switched between being drawn by the shader and being baked into meshes: rebuilds the chunk
     * sections the current lights reach, so baked light is added or removed there.
     */
    public static void onRenderModeChanged(Minecraft mc) {
        LongSet sections = new LongOpenHashSet();
        for (Source s : SOURCES.values()) addReach(s, sections, s.bx, s.by, s.bz);
        publish(mc, sections);
    }

    public static void reset() {
        SOURCES.clear();
        current = EMPTY;
        VERSION.incrementAndGet();
    }

    private static void publish(Minecraft mc, LongSet dirtySections) {
        List<Source> ready = new ArrayList<>();
        for (Source s : SOURCES.values()) {
            if (s.vis != null) ready.add(s);
        }
        current = ready.isEmpty() ? EMPTY : new Snapshot(ready);
        VERSION.incrementAndGet();
        if (mc.level != null) {
            for (long sec : dirtySections) {
                mc.levelRenderer.setSectionDirty(SectionPos.x(sec), SectionPos.y(sec), SectionPos.z(sec));
            }
        }
    }

    /** Adds the chunk sections a (baked) light reaches from block (x, y, z). */
    private static void addReach(Source s, LongSet sections, int x, int y, int z) {
        if (s.vis == null || x == Integer.MIN_VALUE) return;
        int r = s.level;
        for (int sx = (x - r) >> 4; sx <= (x + r) >> 4; sx++)
            for (int sy = (y - r) >> 4; sy <= (y + r) >> 4; sy++)
                for (int sz = (z - r) >> 4; sz <= (z + r) >> 4; sz++)
                    sections.add(SectionPos.asLong(sx, sy, sz));
    }

    // --- sources ---

    private static void collect(Entity entity, Collector c) {
        if (entity instanceof LivingEntity living) {
            ItemStack mainStack = living.getMainHandItem(), offStack = living.getOffhandItem();
            ItemStack flashlight = mainStack.is(FLASHLIGHTS) ? mainStack : offStack.is(FLASHLIGHTS) ? offStack : null;
            if (flashlight != null) {
                BlockState s = heldLight(flashlight);
                if (s != null) c.addSpot(entity, 0, s, s.getLightEmission(), FLASHLIGHT_INNER, FLASHLIGHT_OUTER);
                else c.addSpot(entity, 0, FLASHLIGHT_COLOR, 15, FLASHLIGHT_INNER, FLASHLIGHT_OUTER);
            } else {
                BlockState main = heldLight(mainStack);
                BlockState off = heldLight(offStack);
                BlockState held = main == null ? off : off == null ? main
                        : main.getLightEmission() >= off.getLightEmission() ? main : off;
                if (held != null) c.add(entity, 0, held, held.getLightEmission());
            }
        } else if (entity instanceof ItemEntity item) {
            BlockState s = heldLight(item.getItem());
            if (s != null) c.add(entity, 0, s, s.getLightEmission());
        } else if (entity instanceof ItemFrame frame) {
            BlockState s = heldLight(frame.getItem());
            if (s != null) c.add(entity, 0, s, s.getLightEmission());
        }

        EntityType<?> type = entity.getType();
        if (type == EntityType.BLAZE) {
            c.add(entity, 1, Blocks.FIRE.defaultBlockState(), 12);
        } else if (type == EntityType.MAGMA_CUBE) {
            c.add(entity, 1, Blocks.MAGMA_BLOCK.defaultBlockState(), 8);
        } else if (type == EntityType.GLOW_SQUID) {
            c.add(entity, 1, 0x30FFD8, 8);
        } else if (type == EntityType.SPECTRAL_ARROW) {
            c.add(entity, 1, 0xFFE860, 8);
        } else if (entity instanceof AbstractHurtingProjectile && type != EntityType.WIND_CHARGE && type != EntityType.BREEZE_WIND_CHARGE) {
            c.add(entity, 1, Blocks.FIRE.defaultBlockState(), 12);
        } else if (entity.isOnFire() && !entity.isSpectator()) {
            c.add(entity, 1, Blocks.FIRE.defaultBlockState(), 15);
        }
    }

    /** Light state per item (NO_LIGHT if none); items never change, so this is computed once per item. */
    private static final Map<Item, BlockState> ITEM_LIGHTS = new IdentityHashMap<>();
    private static final BlockState NO_LIGHT = Blocks.AIR.defaultBlockState();

    /** The light-emitting block state an item stands for, or null. */
    private static BlockState heldLight(ItemStack stack) {
        if (stack.isEmpty()) return null;
        BlockState state = ITEM_LIGHTS.computeIfAbsent(stack.getItem(), DynamicLights::lightOf);
        return state == NO_LIGHT ? null : state;
    }

    private static BlockState lightOf(Item item) {
        if (item == Items.LAVA_BUCKET) return Blocks.LAVA.defaultBlockState();
        if (!(item instanceof BlockItem blockItem)) return NO_LIGHT;
        BlockState state = blockItem.getBlock().defaultBlockState();
        if (state.getLightEmission() > 0) return state;
        // some light blocks only glow in a non-default state (e.g. lit candles); use the brightest one
        BlockState best = NO_LIGHT;
        for (BlockState s : blockItem.getBlock().getStateDefinition().getPossibleStates()) {
            if (s.getLightEmission() > best.getLightEmission()) best = s;
        }
        return best;
    }

    // --- queries (any thread) ---

    /**
     * Dynamic light level (0..15, fractional) at a world position. With {@code corner}, the position is a mesh
     * vertex on a block corner and the brightest of the 8 surrounding cells counts (the lit side of a wall);
     * otherwise the cell containing the position is used.
     */
    public static float levelAt(Snapshot s, float x, float y, float z, boolean corner) {
        float best = 0f;
        for (int i = 0; i < s.count; i++) {
            float d2 = distanceSq(s, i, x, y, z);
            if (d2 >= s.level[i] * s.level[i]) continue;
            float lv = (s.level[i] - (float) Math.sqrt(d2)) * cone(s, i, x, y, z, d2);
            if (lv <= best) continue;
            lv *= visibility(s, i, x, y, z, corner);
            if (lv > best) best = lv;
        }
        return best;
    }

    /** Integer dynamic light level at a block (for entity / block entity / particle lighting). */
    public static int levelAt(BlockPos pos) {
        Snapshot s = current;
        if (s.count == 0) return 0;
        return Math.round(levelAt(s, pos.getX() + 0.5f, pos.getY() + 0.5f, pos.getZ() + 0.5f, false));
    }

    /** Adds dynamic sources' colors to a weighted color sum {r, g, b, w} at a position. */
    static void accumulate(Snapshot s, float x, float y, float z, boolean corner, float[] sum) {
        for (int i = 0; i < s.count; i++) {
            int rgb = s.color[i];
            if (rgb < 0) continue;
            float reach = s.level[i] + 0.5f;
            float d2 = distanceSq(s, i, x, y, z);
            if (d2 >= reach * reach) continue;
            float v = (reach - (float) Math.sqrt(d2)) * cone(s, i, x, y, z, d2);
            if (v <= 0f) continue;
            v *= visibility(s, i, x, y, z, corner);
            if (v <= 0f) continue;
            float w = v * v;
            sum[0] += ((rgb >> 16) & 0xFF) * w;
            sum[1] += ((rgb >> 8) & 0xFF) * w;
            sum[2] += (rgb & 0xFF) * w;
            sum[3] += w;
        }
    }

    private static float distanceSq(Snapshot s, int i, float x, float y, float z) {
        float dx = x - s.pos[i * 3], dy = y - s.pos[i * 3 + 1], dz = z - s.pos[i * 3 + 2];
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * How much of a spot light reaches a position by its angle off the light's direction (1 for plain lights).
     * Same as in the Sodium shader: smoothstep(cosOuter, cosInner, cos angle).
     */
    private static float cone(Snapshot s, int i, float x, float y, float z, float d2) {
        float cosOuter = s.cone[i * 2 + 1];
        if (cosOuter < -1f) return 1f;
        float cosInner = s.cone[i * 2];
        float c = 1f;
        if (d2 > 1e-8f) {
            float dx = x - s.pos[i * 3], dy = y - s.pos[i * 3 + 1], dz = z - s.pos[i * 3 + 2];
            c = (dx * s.dir[i * 3] + dy * s.dir[i * 3 + 1] + dz * s.dir[i * 3 + 2]) / (float) Math.sqrt(d2);
        }
        float t = Math.max(0f, Math.min(1f, (c - cosOuter) / (cosInner - cosOuter)));
        return t * t * (3f - 2f * t);
    }

    private static float visibility(Snapshot s, int i, float x, float y, float z, boolean corner) {
        byte[] vis = s.vis[i];
        int ox = s.origin[i * 3], oy = s.origin[i * 3 + 1], oz = s.origin[i * 3 + 2];
        if (!corner) {
            return LightVolume.sample(vis, ox, oy, oz, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        }
        int bx = Math.round(x), by = Math.round(y), bz = Math.round(z);
        float best = 0f;
        for (int dx = -1; dx <= 0; dx++)
            for (int dy = -1; dy <= 0; dy++)
                for (int dz = -1; dz <= 0; dz++)
                    best = Math.max(best, LightVolume.sample(vis, ox, oy, oz, bx + dx, by + dy, bz + dz));
        return best;
    }
}
