package gpulod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;

public class GpuLodClient implements ClientModInitializer {
    public static final LodStore STORE = new LodStore();
    private static final LodRenderer RENDERER = new LodRenderer(STORE);

    @Override
    public void onInitializeClient() {
        ClientChunkEvents.CHUNK_LOAD.register(STORE::capture);
        ClientTickEvents.END_CLIENT_TICK.register(STORE::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> STORE.flush(false));
        LevelRenderEvents.END_MAIN.register(ctx -> RENDERER.render(Minecraft.getInstance()));
    }
}
