# GPU LOD v0.4.2 (Fabric, Minecraft 26.3)

Build: lewat GitHub Actions (.github/workflows/build.yml) -> artifact "gpulod-jar".
Lokal: JDK 25 + Gradle 9.5.1:  gradle build

Pasang: Fabric Loader 0.19.5+, Fabric API 0.161.0+26.3, lalu gpulod-0.4.2.jar di folder mods.

PENTING (RTX/GPU modern juga kena): Minecraft 26.2+ default-nya Vulkan.
Set Options > Video Settings > Graphics API > Prefer OpenGL, lalu restart.
Mod ini memakai OpenGL mentah. Minecraft 26.2+ bisa memakai backend Vulkan;
di Vulkan mod mematikan diri (cek latest.log). Pilih OpenGL di pengaturan video bila perlu.

Data tersimpan: .minecraft/gpulod/<dunia>/<dimensi>/r.X.Z.lod

Belum ada: octree/LOD bertingkat, distant generation (sisi server), fog yang
disamakan dengan vanilla (fog LOD memakai warna tetap), kamera orang-ketiga.
