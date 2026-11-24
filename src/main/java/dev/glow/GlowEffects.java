package dev.glow;

import static org.lwjgl.system.MemoryUtil.memAddress0;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.nio.ByteBuffer;
import java.util.List;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Per-frame quads of particles or weather, in the vertex layout rays read. Vanilla's own builders write them through VertexConsumer. */
final class GlowEffects implements VertexConsumer {
	static final int MAX_QUADS = 8192; // Particles past this still draw, but rays don't see them.
	/** Above the texture index in the vertex's material bits: the shaders read these as bits 25 and 26 of the packed word. */
	private static final int GLOWS_FLAG = 1 << 9, SOFT_FLAG = 1 << 10;
	private static final int FULL_BLOCK_LIGHT = 240; // Particles at this level are flames and sparks, which light themselves.
	private static final float RAIN_MAX_ALPHA = 1.0F, SNOW_MAX_ALPHA = 0.8F, WEATHER_FAR_ALPHA = 0.5F;
	private static final float WEATHER_V_PER_BLOCK = 0.25F;

	private final int stride;
	private ByteBuffer data;
	private int vertices;
	private int material;
	private long at;
	private boolean full;

	GlowEffects(int stride) {
		this.stride = stride;
	}

	void clear() {
		vertices = 0;
		full = false;
	}

	int quads() {
		return vertices / 4;
	}

	ByteBuffer data() {
		return data;
	}

	/** Quads added from here on use this texture slot. */
	void useTexture(int texture) {
		material = texture | SOFT_FLAG;
	}

	void addRain(List<WeatherEffectRenderer.ColumnInstance> columns, Vec3 camera, int radius, float intensity) {
		addColumns(columns, camera, RAIN_MAX_ALPHA, radius, intensity);
	}

	void addSnow(List<WeatherEffectRenderer.ColumnInstance> columns, Vec3 camera, int radius, float intensity) {
		addColumns(columns, camera, SNOW_MAX_ALPHA, radius, intensity);
	}

	/** The same quads as WeatherEffectRenderer.prepareInstances. */
	private void addColumns(List<WeatherEffectRenderer.ColumnInstance> columns, Vec3 camera, float maxAlpha, int radius, float intensity) {
		float radiusSquared = radius * radius;
		for (WeatherEffectRenderer.ColumnInstance column : columns) {
			float x = (float) (column.x() + 0.5 - camera.x), z = (float) (column.z() + 0.5 - camera.z);
			float squared = Mth.lengthSquared(x, z);
			int color = ARGB.white(Mth.lerp(Math.min(squared / radiusSquared, 1.0F), maxAlpha, WEATHER_FAR_ALPHA) * intensity);
			float distance = Math.max(Mth.sqrt(squared), 1.0E-3F);
			float halfX = -z / distance / 2.0F, halfZ = x / distance / 2.0F;
			float top = (float) (column.topY() - camera.y), bottom = (float) (column.bottomY() - camera.y);
			float u0 = column.uOffset(), u1 = u0 + 1.0F;
			float v0 = column.bottomY() * WEATHER_V_PER_BLOCK + column.vOffset(), v1 = column.topY() * WEATHER_V_PER_BLOCK + column.vOffset();
			addVertex(x - halfX, top, z - halfZ).setUv(u0, v0).setColor(color).setLight(column.lightCoords());
			addVertex(x + halfX, top, z + halfZ).setUv(u1, v0).setColor(color).setLight(column.lightCoords());
			addVertex(x + halfX, bottom, z + halfZ).setUv(u1, v1).setColor(color).setLight(column.lightCoords());
			addVertex(x - halfX, bottom, z - halfZ).setUv(u0, v1).setColor(color).setLight(column.lightCoords());
		}
	}

	@Override
	public VertexConsumer addVertex(float x, float y, float z) {
		if (data == null) data = memAlloc(MAX_QUADS * 4 * stride);
		full = vertices == MAX_QUADS * 4;
		if (full) return this;
		at = memAddress0(data) + vertices++ * (long) stride;
		memPutFloat(at, x);
		memPutFloat(at + 4, y);
		memPutFloat(at + 8, z);
		return this;
	}

	@Override
	public VertexConsumer setUv(float u, float v) {
		if (full) return this;
		memPutFloat(at + 12, u);
		memPutFloat(at + 16, v);
		return this;
	}

	@Override
	public VertexConsumer setUv2(int block, int sky) {
		if (full) return this;
		int flags = block >= FULL_BLOCK_LIGHT ? GLOWS_FLAG : 0;
		memPutInt(at + 20, Math.min(block, 255) | Math.min(sky, 255) << 8 | (material | flags) << 16);
		return this;
	}

	@Override
	public VertexConsumer setColor(int color) {
		if (full) return this;
		// The vertex holds RGBA bytes in memory, which is ABGR as an int.
		memPutInt(at + 24, ARGB.toABGR(color));
		return this;
	}

	@Override
	public VertexConsumer setColor(int r, int g, int b, int a) {
		return setColor(ARGB.color(a, r, g, b));
	}

	@Override
	public VertexConsumer setUv1(int u, int v) {
		return this;
	}

	@Override
	public VertexConsumer setUv3(float u, float v) {
		return this;
	}

	@Override
	public VertexConsumer setNormal(float x, float y, float z) {
		return this;
	}

	@Override
	public VertexConsumer setLineWidth(float width) {
		return this;
	}
}
