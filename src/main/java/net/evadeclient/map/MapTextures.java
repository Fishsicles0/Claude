package net.evadeclient.map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * GPU textures of explored regions for the World Map: one 512 x 512 texture per region on screen, built
 * progressively (a couple of whole regions per frame) and then kept up to date chunk by chunk (only chunks whose
 * revision changed are re-uploaded, as 16 x 16 sub-images). Unexplored columns are transparent. All textures are
 * freed when the map closes or the world changes.
 */
public final class MapTextures implements AutoCloseable {
    private static final int SIZE = 512;
    private static final int FULL_BUILDS_PER_FRAME = 2, CHUNK_UPLOADS_PER_FRAME = 96;

    private static final class Tex {
        final NativeImageBackedTexture tex;
        final int[] revs = new int[1024];
        boolean built;
        long lastFrame;
        Tex(NativeImageBackedTexture t) { tex = t; }
    }

    private final Long2ObjectOpenHashMap<Tex> textures = new Long2ObjectOpenHashMap<>();
    private int generation = Integer.MIN_VALUE, styleKey = Integer.MIN_VALUE;
    private int fullBudget, chunkBudget;
    private long frame;
    private final int[] buf = new int[256];

    /** Starts a frame with the view's shading; returns false if nothing can be shown (no mapped world). */
    public boolean begin(int shadeMode, boolean waterDepth) {
        frame++;
        int key = shadeMode * 2 + (waterDepth ? 1 : 0);
        if (MapStore.generation() != generation || key != styleKey) {
            close();
            generation = MapStore.generation();
            styleKey = key;
        }
        fullBudget = FULL_BUILDS_PER_FRAME;
        chunkBudget = CHUNK_UPLOADS_PER_FRAME;
        return MapStore.mapping();
    }

    /** The texture for region (rx, rz), brought up to date within this frame's budget, or null if there is none yet. */
    public GpuTextureView view(int rx, int rz, int shadeMode, boolean waterDepth) {
        long key = ChunkPos.toLong(rx, rz);
        Tex t = textures.get(key);
        if (t == null && !MapStore.hasRegion(rx, rz)) return null;
        MapRegion reg = MapStore.region(rx, rz);
        if (reg == null) return t == null ? null : t.tex.getGlTextureView();
        if (t == null) {
            if (fullBudget <= 0) return null;
            fullBudget--;
            t = new Tex(new NativeImageBackedTexture(() -> "evade_world_map", SIZE, SIZE, false));
            textures.put(key, t);
        }
        t.lastFrame = frame;
        NativeImage img = t.tex.getImage();
        if (img == null) return null;
        if (!t.built) {
            for (int c = 0; c < 1024; c++) {
                paint(img, reg, c, shadeMode, waterDepth);
                t.revs[c] = reg.rev[c];
            }
            t.tex.upload();
            t.built = true;
        } else {
            for (int c = 0; c < 1024 && chunkBudget > 0; c++) {
                if (t.revs[c] == reg.rev[c]) continue;
                paint(img, reg, c, shadeMode, waterDepth);
                t.revs[c] = reg.rev[c];
                chunkBudget--;
                int bx = (c & 31) * 16, bz = (c >> 5) * 16;
                if (t.tex.getGlTexture() != null)
                    RenderSystem.getDevice().createCommandEncoder().writeToTexture(t.tex.getGlTexture(), img, 0, 0, bx, bz, 16, 16, bx, bz);
            }
        }
        return t.tex.getGlTextureView();
    }

    private void paint(NativeImage img, MapRegion reg, int c, int mode, boolean wd) {
        int bx = (c & 31) * 16, bz = (c >> 5) * 16;
        if (reg.rev[c] == 0) {
            img.fillRect(bx, bz, 16, 16, 0);
            return;
        }
        System.arraycopy(reg.px, c * 256, buf, 0, 256);
        for (int i = 0; i < 256; i++) img.setColorArgb(bx + (i & 15), bz + (i >> 4), MapShading.argb(buf[i], mode, wd, 0));
    }

    /** Frees textures not drawn for a while once there are more than {@code keep}. */
    public void trim(int keep) {
        if (textures.size() <= keep) return;
        List<Long> drop = new ArrayList<>();
        for (var e : textures.long2ObjectEntrySet()) if (e.getValue().lastFrame < frame - 2) drop.add(e.getLongKey());
        for (long k : drop) {
            if (textures.size() <= keep) break;
            textures.remove(k).tex.close();
        }
    }

    public int count() { return textures.size(); }

    @Override
    public void close() {
        for (Tex t : textures.values()) t.tex.close();
        textures.clear();
    }
}
