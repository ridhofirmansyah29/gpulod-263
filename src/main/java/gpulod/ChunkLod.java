package gpulod;

/** Ringkasan satu chunk: 4x4 sel (tiap sel 4x4 blok) berisi tinggi + warna permukaan. */
public final class ChunkLod {
    public static final int CELL = 4;
    public static final int CELLS = 16 / CELL;
    public static final int COUNT = CELLS * CELLS; // 16 sel per chunk
    public static final short EMPTY = Short.MIN_VALUE;

    public final int x, z;
    public final short[] height = new short[COUNT];
    public final int[] rgb = new int[COUNT];

    public ChunkLod(int x, int z) { this.x = x; this.z = z; }
}
