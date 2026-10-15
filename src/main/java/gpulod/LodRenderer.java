package gpulod;

import com.mojang.blaze3d.platform.Window;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.ARBClipControl;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Iterator;

/**
 * Renderer LOD berbasis GPU instancing untuk Minecraft 26.x (backend OpenGL).
 *
 * CPU hanya meng-upload 16 sel x 16 byte per chunk (sekali). GPU mengembangkan tiap
 * sel jadi atap + 4 dinding, membuang sel di area vanilla/jarak jauh, lalu shading + fog.
 * Depth memakai konvensi 26.2+: reversed-Z, clip control [0,1], far plane tak hingga.
 * Bila backend bukan OpenGL (mis. Vulkan), mod mematikan diri tanpa crash.
 */
public final class LodRenderer {
    private static final Logger LOG = LoggerFactory.getLogger("gpulod");

    private static final int CELL_BYTES = 16;
    private static final int SLOT_BYTES = ChunkLod.COUNT * CELL_BYTES;
    private static final int VERTS_PER_CELL = 30;
    private static final int UPLOAD_BUDGET = 128;
    private static final float RANGE = 6144f;
    private static final float SKIRT = 12f;
    private static final float NEAR = 0.05f;

    private static final String VS = """
        #version 330 core
        layout(location=0) in ivec4 inst;      // x, y, z, (rgb | valid<<24)
        uniform mat4 uMvp;
        uniform ivec3 uCamI;
        uniform vec3 uCamF;
        uniform vec2 uCenter;
        uniform float uInner;
        uniform float uRange;
        uniform float uSkirt;
        out vec3 vCol;
        out float vDist;

        const vec2 Q[6] = vec2[6](vec2(0,0), vec2(0,1), vec2(1,0), vec2(1,0), vec2(0,1), vec2(1,1));

        void main() {
            vCol = vec3(0.0);
            vDist = 0.0;
            vec2 c = vec2(inst.x, inst.z) + 2.0;
            vec2 d = abs(c - uCenter);
            bool valid = ((inst.w >> 24) & 1) != 0;
            bool insideVanilla = max(d.x, d.y) < uInner;
            vec2 rc = c - (vec2(uCamI.xz) + uCamF.xz);
            if (!valid || insideVanilla || dot(rc, rc) > uRange * uRange) {
                gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                return;
            }

            int id = gl_VertexID;
            vec3 off;
            float shade = 1.0;
            if (id < 6) {
                off = vec3(Q[id].x * 4.0, 0.0, Q[id].y * 4.0);
            } else {
                int s = (id - 6) / 6;
                int k = (id - 6) % 6;
                float u = Q[k].x * 4.0;
                float y = (Q[k].y - 1.0) * uSkirt;
                if (s == 0)      off = vec3(u, y, 0.0);
                else if (s == 1) off = vec3(u, y, 4.0);
                else if (s == 2) off = vec3(0.0, y, u);
                else             off = vec3(4.0, y, u);
                shade = (s < 2) ? 0.78 : 0.68;
            }

            vec3 rel = vec3(ivec3(inst.x, inst.y, inst.z) - uCamI) - uCamF + off;
            gl_Position = uMvp * vec4(rel, 1.0);

            int rgb = inst.w & 0xFFFFFF;
            vCol = vec3(float((rgb >> 16) & 255), float((rgb >> 8) & 255), float(rgb & 255)) / 255.0 * shade;
            vDist = length(rel);
        }
        """;

    private static final String FS = """
        #version 330 core
        in vec3 vCol;
        in float vDist;
        uniform vec3 uFogColor;
        uniform float uRange;
        out vec4 fragColor;
        void main() {
            float f = smoothstep(uRange * 0.55, uRange, vDist);
            fragColor = vec4(mix(vCol, uFogColor, f), 1.0);
        }
        """;

    private final LodStore store;
    private final Long2IntOpenHashMap slotOf = new Long2IntOpenHashMap();
    private boolean ready, disabled, hasGl45;
    private int prog, vao, vbo, capacitySlots, usedSlots;
    private int uMvp, uCamI, uCamF, uCenter, uInner, uRange, uSkirt, uFogColor;

    public LodRenderer(LodStore store) {
        this.store = store;
        slotOf.defaultReturnValue(-1);
    }

    // ---------------------------------------------------------------- init

    private void init() {
        GLCapabilities caps = GL.getCapabilities(); // melempar IllegalStateException bila bukan OpenGL
        if (!caps.OpenGL33) throw new IllegalStateException("butuh OpenGL 3.3+");
        hasGl45 = caps.OpenGL45;
        if (!hasGl45 && !caps.GL_ARB_clip_control)
            throw new IllegalStateException("butuh OpenGL 4.5 / GL_ARB_clip_control");

        prog = GL20.glCreateProgram();
        int vs = compile(GL20.GL_VERTEX_SHADER, VS);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, FS);
        GL20.glAttachShader(prog, vs);
        GL20.glAttachShader(prog, fs);
        GL20.glLinkProgram(prog);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == 0)
            throw new IllegalStateException(GL20.glGetProgramInfoLog(prog));
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);

        uMvp = GL20.glGetUniformLocation(prog, "uMvp");
        uCamI = GL20.glGetUniformLocation(prog, "uCamI");
        uCamF = GL20.glGetUniformLocation(prog, "uCamF");
        uCenter = GL20.glGetUniformLocation(prog, "uCenter");
        uInner = GL20.glGetUniformLocation(prog, "uInner");
        uRange = GL20.glGetUniformLocation(prog, "uRange");
        uSkirt = GL20.glGetUniformLocation(prog, "uSkirt");
        uFogColor = GL20.glGetUniformLocation(prog, "uFogColor");

        vao = GL30.glGenVertexArrays();
        growTo(4096);
        ready = true;
    }

    private static int compile(int type, String src) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0)
            throw new IllegalStateException(GL20.glGetShaderInfoLog(s));
        return s;
    }

    private void growTo(int slots) {
        if (slots <= capacitySlots) return;
        int newCap = Math.max(capacitySlots * 2, slots);
        int nb = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, nb);
        GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, (long) newCap * SLOT_BYTES, GL15.GL_DYNAMIC_DRAW);
        if (vbo != 0) {
            if (usedSlots > 0) {
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, vbo);
                GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER,
                        0L, 0L, (long) usedSlots * SLOT_BYTES);
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
            }
            GL15.glDeleteBuffers(vbo);
        }
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        vbo = nb;
        capacitySlots = newCap;

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL20.glEnableVertexAttribArray(0);
        GL30.glVertexAttribIPointer(0, 4, GL11.GL_INT, CELL_BYTES, 0L);
        GL33.glVertexAttribDivisor(0, 1);
    }

    // -------------------------------------------------------------- upload

    private void upload() {
        if (store.reset) {
            usedSlots = 0;
            slotOf.clear();
            store.reset = false;
        }
        if (store.pending.isEmpty()) return;

        int budget = UPLOAD_BUDGET;
        try (MemoryStack st = MemoryStack.stackPush()) {
            IntBuffer ib = st.mallocInt(ChunkLod.COUNT * 4);
            Iterator<Long2ObjectMap.Entry<ChunkLod>> it = store.pending.long2ObjectEntrySet().fastIterator();
            while (it.hasNext() && budget-- > 0) {
                Long2ObjectMap.Entry<ChunkLod> e = it.next();
                long key = e.getLongKey();
                ChunkLod l = e.getValue();
                it.remove();

                int slot = slotOf.get(key);
                if (slot < 0) {
                    growTo(usedSlots + 1);
                    slot = usedSlots++;
                    slotOf.put(key, slot);
                }

                ib.clear();
                for (int i = 0; i < ChunkLod.COUNT; i++) {
                    boolean valid = l.height[i] != ChunkLod.EMPTY;
                    ib.put(l.x * 16 + (i % ChunkLod.CELLS) * ChunkLod.CELL);
                    ib.put(valid ? l.height[i] : 0);
                    ib.put(l.z * 16 + (i / ChunkLod.CELLS) * ChunkLod.CELL);
                    ib.put((l.rgb[i] & 0xFFFFFF) | (valid ? (1 << 24) : 0));
                }
                ib.flip();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, (long) slot * SLOT_BYTES, ib);
            }
        }
    }

    // -------------------------------------------------------------- render

    public void render(Minecraft mc) {
        if (disabled) return;
        LocalPlayer player = mc.player;
        if (mc.level == null || player == null) return;
        boolean wasReady = ready;
        try {
            draw(mc, player);
            if (!wasReady && ready) say(player, "[GPU LOD] aktif (OpenGL). Jelajahi dulu, lalu menjauh dari area itu.");
        } catch (Throwable t) {
            disabled = true;
            LOG.error("GPU LOD dimatikan: {}", t.toString());
            say(player, "[GPU LOD] NONAKTIF. Mod ini butuh OpenGL, sedangkan Minecraft 26.2+ memakai Vulkan secara default. "
                    + "Buka Options > Video Settings > Graphics API > Prefer OpenGL, lalu restart game. (" + t.getClass().getSimpleName() + ")");
        }
    }

    /** Kirim pesan ke chat. Pakai refleksi karena nama method chat berubah-ubah antar versi 26.x. */
    private static void say(LocalPlayer player, String msg) {
        LOG.info(msg);
        Component c = Component.literal(msg);
        try {
            player.getClass().getMethod("sendSystemMessage", Component.class).invoke(player, c);
            return;
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        try {
            player.getClass().getMethod("displayClientMessage", Component.class, boolean.class).invoke(player, c, false);
            return;
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        try {
            Minecraft mc = Minecraft.getInstance();
            Object gui = Minecraft.class.getField("gui").get(mc);
            Object chat = gui.getClass().getMethod("getChat").invoke(gui);
            chat.getClass().getMethod("addMessage", Component.class).invoke(chat, c);
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }

    private void clipControl(int origin, int depth) {
        if (hasGl45) GL45.glClipControl(origin, depth);
        else ARBClipControl.glClipControl(origin, depth);
    }

    private void draw(Minecraft mc, LocalPlayer player) {
        // simpan state GL milik Minecraft agar bisa dipulihkan persis
        boolean depthOn = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean cullOn = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean blendOn = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean scissorOn = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        int prog0 = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int vao0 = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int arr0 = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int clipOrigin = GL45.GL_LOWER_LEFT, clipDepth = GL45.GL_NEGATIVE_ONE_TO_ONE;
        boolean clipSaved = false;

        try {
            if (!ready) init();
            clipOrigin = GL11.glGetInteger(GL45.GL_CLIP_ORIGIN);
            clipDepth = GL11.glGetInteger(GL45.GL_CLIP_DEPTH_MODE);
            clipSaved = true;

            upload();
            if (usedSlots == 0) return;

            float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            Vec3 eye = player.getEyePosition(partial);
            float pitch = player.getViewXRot(partial);
            float yaw = player.getViewYRot(partial);

            Window w = mc.getWindow();
            float aspect = (float) w.getWidth() / (float) w.getHeight();
            float f = 1f / (float) Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
            // reversed-Z, zero-to-one, far plane tak hingga
            Matrix4f proj = new Matrix4f().zero()
                    .m00(f / aspect).m11(f).m22(0f).m23(-1f).m32(NEAR).m33(0f);
            Matrix4f view = new Matrix4f()
                    .rotateX((float) Math.toRadians(pitch))
                    .rotateY((float) Math.toRadians(yaw + 180f));
            Matrix4f mvp = proj.mul(view, new Matrix4f());

            int cx = Mth.floor(eye.x), cy = Mth.floor(eye.y), cz = Mth.floor(eye.z);
            float centerX = (Mth.floor(player.getX()) >> 4) * 16f + 8f;
            float centerZ = (Mth.floor(player.getZ()) >> 4) * 16f + 8f;
            float inner = mc.options.renderDistance().get() * 16f + 8f;

            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glDepthFunc(GL11.GL_GEQUAL);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            clipControl(GL45.GL_LOWER_LEFT, GL45.GL_ZERO_TO_ONE);

            GL20.glUseProgram(prog);
            try (MemoryStack st = MemoryStack.stackPush()) {
                FloatBuffer fb = st.mallocFloat(16);
                mvp.get(fb);
                GL20.glUniformMatrix4fv(uMvp, false, fb);
            }
            GL20.glUniform3i(uCamI, cx, cy, cz);
            GL20.glUniform3f(uCamF, (float) (eye.x - cx), (float) (eye.y - cy), (float) (eye.z - cz));
            GL20.glUniform2f(uCenter, centerX, centerZ);
            GL20.glUniform1f(uInner, inner);
            GL20.glUniform1f(uRange, RANGE);
            GL20.glUniform1f(uSkirt, SKIRT);
            GL20.glUniform3f(uFogColor, 0.72f, 0.82f, 1.0f);

            GL30.glBindVertexArray(vao);
            GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, VERTS_PER_CELL, usedSlots * ChunkLod.COUNT);
        } finally {
            if (clipSaved) clipControl(clipOrigin, clipDepth);
            GL20.glUseProgram(prog0);
            GL30.glBindVertexArray(vao0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arr0);
            GL11.glDepthFunc(depthFunc);
            GL11.glDepthMask(depthMask);
            setEnabled(GL11.GL_DEPTH_TEST, depthOn);
            setEnabled(GL11.GL_CULL_FACE, cullOn);
            setEnabled(GL11.GL_BLEND, blendOn);
            setEnabled(GL11.GL_SCISSOR_TEST, scissorOn);
        }
    }

    private static void setEnabled(int cap, boolean on) {
        if (on) GL11.glEnable(cap); else GL11.glDisable(cap);
    }
}
