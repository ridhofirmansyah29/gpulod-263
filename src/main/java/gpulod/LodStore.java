package gpulod;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Data LOD per dunia/dimensi, disimpan di .minecraft/gpulod/<dunia>/<dimensi>/r.X.Z.lod
 * (1 file = 32x32 chunk, gzip). Dimuat di thread latar saat join, lalu di-upload ke GPU.
 */
public final class LodStore {
    private static final Logger LOG = LoggerFactory.getLogger("gpulod");
    private static final int MAGIC = 0x4C4F4402;
    private static final int AUTOSAVE_TICKS = 1200;

    public final Long2ObjectLinkedOpenHashMap<ChunkLod> pending = new Long2ObjectLinkedOpenHashMap<>();
    public boolean reset;

    private record Loaded(int gen, ChunkLod lod) {}

    private final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<ChunkLod>> regions = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet dirty = new LongOpenHashSet();
    private final ConcurrentLinkedQueue<Loaded> loaded = new ConcurrentLinkedQueue<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gpulod-io");
        t.setDaemon(true);
        return t;
    });
    private volatile int generation;
    private String currentKey;
    private Path dir;
    private int saveTimer;

    /** Kunci chunk/region: (x di 32 bit atas, z di 32 bit bawah). */
    public static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int keyX(long k) { return (int) (k >> 32); }
    private static int keyZ(long k) { return (int) k; }

    // ------------------------------------------------------------ dunia aktif

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String keyFor(Minecraft mc, ClientLevel level) {
        if (level == null) return null;
        String base;
        if (mc.getSingleplayerServer() != null) {
            base = "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName();
        } else {
            ServerData sd = mc.getCurrentServer();
            base = "mp_" + (sd != null ? sd.ip : "unknown");
        }
        return sanitize(base) + "/" + sanitize(level.dimension().toString());
    }

    private void sync(Minecraft mc, ClientLevel level) {
        String key = keyFor(mc, level);
        if (Objects.equals(key, currentKey)) return;

        flush(true);
        regions.clear();
        pending.clear();
        dirty.clear();
        loaded.clear();
        reset = true;
        generation++;
        currentKey = key;
        saveTimer = 0;

        if (key == null) { dir = null; return; }
        dir = FabricLoader.getInstance().getGameDir().resolve("gpulod").resolve(key);
        final Path d = dir;
        final int gen = generation;
        io.execute(() -> loadAll(d, gen));
    }

    // ------------------------------------------------------------ tangkap chunk

    public void capture(ClientLevel level, LevelChunk chunk) {
        sync(Minecraft.getInstance(), level);
        if (dir == null) return;

        ChunkPos p = chunk.getPos();
        int bx = p.getMinBlockX(), bz = p.getMinBlockZ();
        ChunkLod lod = new ChunkLod(bx >> 4, bz >> 4);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int minY = level.getMinY();

        for (int i = 0; i < ChunkLod.COUNT; i++) {
            int lx = (i % ChunkLod.CELLS) * ChunkLod.CELL + ChunkLod.CELL / 2;
            int lz = (i / ChunkLod.CELLS) * ChunkLod.CELL + ChunkLod.CELL / 2;
            int h = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
            lod.height[i] = ChunkLod.EMPTY;
            // cari blok permukaan; toleran terhadap selisih 1 blok pada definisi heightmap
            for (int y = h; y >= Math.max(h - 3, minY); y--) {
                m.set(bx + lx, y, bz + lz);
                MapColor mc = chunk.getBlockState(m).getMapColor(level, m);
                if (mc != null && mc.col != 0) {
                    lod.height[i] = (short) (y + 1);
                    lod.rgb[i] = mc.col;
                    break;
                }
            }
        }
        put(lod, true);
    }

    private void put(ChunkLod lod, boolean markDirty) {
        long rk = key(lod.x >> 5, lod.z >> 5);
        long ck = key(lod.x, lod.z);
        Long2ObjectOpenHashMap<ChunkLod> region = regions.get(rk);
        if (region == null) {
            region = new Long2ObjectOpenHashMap<>();
            regions.put(rk, region);
        }
        ChunkLod old = region.get(ck);
        if (old != null && Arrays.equals(old.height, lod.height) && Arrays.equals(old.rgb, lod.rgb)) return;
        region.put(ck, lod);
        pending.put(ck, lod);
        if (markDirty) dirty.add(rk);
    }

    // ------------------------------------------------------------ tick

    public void tick(Minecraft mc) {
        sync(mc, mc.level);

        int budget = 4000;
        Loaded l;
        while (budget-- > 0 && (l = loaded.poll()) != null) {
            if (l.gen() != generation) continue;
            ChunkLod lod = l.lod();
            Long2ObjectOpenHashMap<ChunkLod> region = regions.get(key(lod.x >> 5, lod.z >> 5));
            if (region != null && region.containsKey(key(lod.x, lod.z))) continue;
            put(lod, false);
        }

        if (++saveTimer >= AUTOSAVE_TICKS) {
            saveTimer = 0;
            flush(true);
        }
    }

    // ------------------------------------------------------------ simpan

    public void flush(boolean async) {
        if (dir == null || dirty.isEmpty()) return;
        final Path d = dir;
        for (long rk : dirty) {
            Long2ObjectOpenHashMap<ChunkLod> region = regions.get(rk);
            if (region == null || region.isEmpty()) continue;
            final byte[] bytes = serialize(region);
            final Path file = d.resolve("r." + keyX(rk) + "." + keyZ(rk) + ".lod");
            if (async) io.execute(() -> write(file, bytes));
            else write(file, bytes);
        }
        dirty.clear();
    }

    private static byte[] serialize(Long2ObjectOpenHashMap<ChunkLod> region) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(region.size() * 100 + 16);
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bos))) {
                out.writeInt(MAGIC);
                out.writeInt(region.size());
                for (ChunkLod l : region.values()) {
                    out.writeInt(l.x);
                    out.writeInt(l.z);
                    for (int i = 0; i < ChunkLod.COUNT; i++) out.writeShort(l.height[i]);
                    for (int i = 0; i < ChunkLod.COUNT; i++) out.writeInt(l.rgb[i]);
                }
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warn("Gagal menyimpan {}", file, e);
        }
    }

    // ------------------------------------------------------------ muat

    private void loadAll(Path d, int gen) {
        if (!Files.isDirectory(d)) return;
        try (Stream<Path> files = Files.list(d)) {
            for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".lod"))::iterator) {
                if (generation != gen) return;
                readRegion(f, gen);
            }
        } catch (IOException e) {
            LOG.warn("Gagal membaca {}", d, e);
        }
    }

    private void readRegion(Path f, int gen) {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(f)))))) {
            if (in.readInt() != MAGIC) return;
            int n = in.readInt();
            for (int c = 0; c < n; c++) {
                ChunkLod l = new ChunkLod(in.readInt(), in.readInt());
                for (int i = 0; i < ChunkLod.COUNT; i++) l.height[i] = in.readShort();
                for (int i = 0; i < ChunkLod.COUNT; i++) l.rgb[i] = in.readInt();
                loaded.add(new Loaded(gen, l));
            }
        } catch (IOException e) {
            LOG.warn("File LOD rusak, dilewati: {}", f, e);
        }
    }
}
