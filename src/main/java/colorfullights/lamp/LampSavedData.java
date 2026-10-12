package colorfullights.lamp;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** The {@link LampSettings} of every lamp in a level that isn't at its defaults, by block position. */
public final class LampSavedData extends SavedData {
    private static final String NAME = "colorful_lights_lamps";

    private final Long2LongOpenHashMap map = new Long2LongOpenHashMap();

    public static LampSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(LampSavedData::new, LampSavedData::load, null), NAME);
    }

    public long get(long pos) {
        return map.isEmpty() ? 0L : map.get(pos);
    }

    /** @return whether anything changed */
    public boolean set(long pos, long value) {
        long old = map.get(pos);
        if (old == value) return false;
        if (value == 0L) map.remove(pos);
        else map.put(pos, value);
        setDirty();
        return true;
    }

    /** All settings as a payload that replaces what the client knows. */
    public LampSettingsPayload all() {
        long[] keys = new long[map.size()], values = new long[map.size()];
        int i = 0;
        for (Long2LongMap.Entry e : map.long2LongEntrySet()) {
            keys[i] = e.getLongKey();
            values[i++] = e.getLongValue();
        }
        return new LampSettingsPayload(true, keys, values);
    }

    private static LampSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        LampSavedData data = new LampSavedData();
        long[] keys = tag.getLongArray("pos"), values = tag.getLongArray("value");
        for (int i = 0; i < Math.min(keys.length, values.length); i++) data.map.put(keys[i], values[i]);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray("pos", map.keySet().toLongArray());
        long[] values = new long[map.size()];
        int i = 0;
        for (long k : map.keySet()) values[i++] = map.get(k);
        tag.putLongArray("value", values);
        return tag;
    }
}
