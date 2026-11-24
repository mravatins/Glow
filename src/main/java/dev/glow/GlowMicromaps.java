package dev.glow;

import com.mojang.blaze3d.platform.NativeImage;
import dev.glow.mixin.SpriteContentsAccessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

/**
 * Builds opacity micromaps for cutout geometry from the block atlas's alpha, so the hardware can skip the alpha test for parts of a triangle
 * that are fully opaque or fully see-through. Mixed parts stay unknown and still run the test in the shader.
 */
final class GlowMicromaps {
	static final int FORMAT_4_STATE = 2;
	static final int MAX_LEVEL = 5;
	static final int FULLY_TRANSPARENT = -1;
	static final int FULLY_OPAQUE = -2;
	static final int FULLY_UNKNOWN_OPAQUE = -4;
	private static final int TRANSPARENT = 0, OPAQUE = 1, UNKNOWN = 2;
	private static final int STATE_UNKNOWN_OPAQUE = 3;
	private static final float EDGE = 1.0E-3F;

	// One byte per atlas texel: TRANSPARENT, OPAQUE or UNKNOWN (animated sprites and gaps between sprites).
	private static volatile byte[] mask;
	private static int width, height;
	private static Object maskKey;
	// Encoded micromap triangles by their atlas UVs, shared by every mesh until the atlas changes. Data is the level followed by the packed states.
	private static final Map<UvKey, byte[]> CACHE = new java.util.concurrent.ConcurrentHashMap<>();
	// A triangle whose UVs need no micromap stores its special index here instead, as a one-byte array: {index}.

	private record UvKey(int a, int b, int c, int d, int e, int f) {}

	/** The data one geometry needs: packed state bytes, one 8-byte VkMicromapTriangleEXT per entry, one uint32 index per triangle, and usage counts. */
	record Encoded(ByteBuffer data, ByteBuffer triangles, ByteBuffer indices, int[] entriesByLevel, int[] referencesByLevel, int entries) {}

	private GlowMicromaps() {}

	static boolean hasMask() {
		return mask != null;
	}

	/** Rebuilds the alpha mask when the atlas's sprites change. */
	static void update(Map<Identifier, TextureAtlasSprite> sprites, int atlasWidth, int atlasHeight, Predicate<String> foliage) {
		if (sprites == maskKey) return;
		maskKey = sprites;
		width = atlasWidth;
		height = atlasHeight;
		mask = new byte[width * height];
		Arrays.fill(mask, (byte) UNKNOWN);
		CACHE.clear();
		for (Map.Entry<Identifier, TextureAtlasSprite> entry : sprites.entrySet()) {
			TextureAtlasSprite sprite = entry.getValue();
			// Shadow rays tint at each leaf they pass, so opaque leaf texels must still reach the shader. Rays that do not tint force these to opaque.
			byte solid = foliage.test(entry.getKey().getPath()) ? (byte) UNKNOWN : (byte) OPAQUE;
			SpriteContents contents = sprite.contents();
			int x0 = Math.round(sprite.getU0() * width), y0 = Math.round(sprite.getV0() * height);
			int w = contents.width(), h = contents.height();
			if (x0 < 0 || y0 < 0 || x0 + w > width || y0 + h > height) continue;
			if (contents.isAnimated()) continue;
			NativeImage image = ((SpriteContentsAccessor) contents).glow$originalImage();
			if (image == null || image.getWidth() < w || image.getHeight() < h) continue;
			for (int y = 0; y < h; y++) {
				int row = (y0 + y) * width + x0;
				for (int x = 0; x < w; x++) mask[row + x] = (byte) ((image.getPixel(x, y) >>> 24) >= 128 ? solid : TRANSPARENT);
			}
		}
	}

	/** Encodes one geometry of quads, whose triangles are (v0, v1, v2) and (v2, v3, v0). Vertex uv floats sit at byte 12 and 16, the texture index in bits 16..23 of word 5. */
	static Encoded encode(ByteBuffer vertices, int stride, int quads) {
		int triangles = quads * 2;
		ByteBuffer indices = ByteBuffer.allocateDirect(triangles * 4).order(ByteOrder.nativeOrder());
		Map<UvKey, Integer> entries = new HashMap<>();
		java.util.List<byte[]> order = new java.util.ArrayList<>();
		int[] referencesByLevel = new int[MAX_LEVEL + 1];
		int base = vertices.position();
		int[] corner = new int[3];
		for (int t = 0; t < triangles; t++) {
			int q = t >> 1;
			if ((t & 1) == 0) { corner[0] = q * 4; corner[1] = q * 4 + 1; corner[2] = q * 4 + 2; }
			else { corner[0] = q * 4 + 2; corner[1] = q * 4 + 3; corner[2] = q * 4; }
			float[] uv = new float[6];
			boolean atlas = true;
			for (int k = 0; k < 3; k++) {
				int at = base + corner[k] * stride;
				uv[k * 2] = vertices.getFloat(at + 12);
				uv[k * 2 + 1] = vertices.getFloat(at + 16);
				if ((vertices.getInt(at + 20) >>> 16 & 0xFF) != 0) atlas = false;
			}
			if (!atlas) {
				indices.putInt(t * 4, FULLY_UNKNOWN_OPAQUE);
				continue;
			}
			UvKey key = new UvKey(Float.floatToRawIntBits(uv[0]), Float.floatToRawIntBits(uv[1]), Float.floatToRawIntBits(uv[2]),
				Float.floatToRawIntBits(uv[3]), Float.floatToRawIntBits(uv[4]), Float.floatToRawIntBits(uv[5]));
			byte[] encoded = CACHE.computeIfAbsent(key, k -> classify(uv));
			if (encoded.length == 1) {
				indices.putInt(t * 4, encoded[0]);
				continue;
			}
			Integer entry = entries.get(key);
			if (entry == null) {
				entry = order.size();
				entries.put(key, entry);
				order.add(encoded);
			}
			indices.putInt(t * 4, entry);
			referencesByLevel[encoded[0]]++;
		}
		if (order.isEmpty()) return null;

		int dataSize = 0;
		for (byte[] encoded : order) dataSize += encoded.length - 1;
		ByteBuffer data = ByteBuffer.allocateDirect(dataSize).order(ByteOrder.nativeOrder());
		ByteBuffer triangleArray = ByteBuffer.allocateDirect(order.size() * 8).order(ByteOrder.nativeOrder());
		int[] entriesByLevel = new int[MAX_LEVEL + 1];
		int offset = 0;
		for (int i = 0; i < order.size(); i++) {
			byte[] encoded = order.get(i);
			triangleArray.putInt(i * 8, offset);
			triangleArray.putShort(i * 8 + 4, encoded[0]);
			triangleArray.putShort(i * 8 + 6, (short) FORMAT_4_STATE);
			data.put(offset, encoded, 1, encoded.length - 1);
			offset += encoded.length - 1;
			entriesByLevel[encoded[0]]++;
		}
		return new Encoded(data, triangleArray, indices, entriesByLevel, referencesByLevel, order.size());
	}

	/** Returns {special index} when the whole triangle is one state, otherwise {level, packed 2-bit states...} in bird-curve order. */
	private static byte[] classify(float[] uv) {
		float ax = (uv[2] - uv[0]) * width, ay = (uv[3] - uv[1]) * height;
		float bx = (uv[4] - uv[0]) * width, by = (uv[5] - uv[1]) * height;
		float area = Math.abs(ax * by - ay * bx) * 0.5F;
		int level = Math.clamp(Math.round((float) (Math.log(Math.max(area, 1.0F)) / Math.log(4.0))), 1, MAX_LEVEL);
		int count = 1 << 2 * level;
		int n = 1 << level;
		byte[] out = new byte[1 + Math.max(1, count / 4)];
		out[0] = (byte) level;
		boolean allOpaque = true, allTransparent = true, allUnknown = true;
		int[] corners = new int[6];
		for (int i = 0; i < count; i++) {
			microTriangle(i, level, corners);
			float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE, minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
			for (int k = 0; k < 3; k++) {
				float bu = corners[k * 2] / (float) n, bv = corners[k * 2 + 1] / (float) n;
				float u = uv[0] * (1.0F - bu - bv) + uv[2] * bu + uv[4] * bv;
				float v = uv[1] * (1.0F - bu - bv) + uv[3] * bu + uv[5] * bv;
				minU = Math.min(minU, u); maxU = Math.max(maxU, u);
				minV = Math.min(minV, v); maxV = Math.max(maxV, v);
			}
			int state = texels(minU, maxU, minV, maxV);
			int bits = state == OPAQUE ? OPAQUE : state == TRANSPARENT ? TRANSPARENT : STATE_UNKNOWN_OPAQUE;
			if (bits != OPAQUE) allOpaque = false;
			if (bits != TRANSPARENT) allTransparent = false;
			if (bits != STATE_UNKNOWN_OPAQUE) allUnknown = false;
			out[1 + (i >> 2)] |= (byte) (bits << ((i & 3) * 2));
		}
		if (allOpaque) return new byte[] {FULLY_OPAQUE};
		if (allTransparent) return new byte[] {FULLY_TRANSPARENT};
		if (allUnknown) return new byte[] {FULLY_UNKNOWN_OPAQUE};
		return out;
	}

	/** The combined state of the atlas texels a UV box touches. */
	private static int texels(float minU, float maxU, float minV, float maxV) {
		int x0 = (int) Math.floor(minU * width + EDGE), x1 = (int) Math.ceil(maxU * width - EDGE) - 1;
		int y0 = (int) Math.floor(minV * height + EDGE), y1 = (int) Math.ceil(maxV * height - EDGE) - 1;
		x1 = Math.max(x1, x0);
		y1 = Math.max(y1, y0);
		if (x0 < 0 || y0 < 0 || x1 >= width || y1 >= height) return UNKNOWN;
		int first = mask[y0 * width + x0];
		if (first == UNKNOWN) return UNKNOWN;
		for (int y = y0; y <= y1; y++) {
			for (int x = x0; x <= x1; x++) {
				if (mask[y * width + x] != first) return UNKNOWN;
			}
		}
		return first;
	}

	/** The barycentric corners, in units of 1/2^level, of micro-triangle index in the spec's bird-curve order: u along v0 to v1, v along v0 to v2. */
	static void microTriangle(int index, int level, int[] out) {
		int b0 = extractEvenBits(index), b1 = extractEvenBits(index >>> 1);
		int fx = prefixEor(b0), fy = prefixEor(b0 & ~b1);
		int t = fy ^ b1;
		int mask = (1 << level) - 1;
		int u = ((fx & ~t) | (b0 & ~t) | (~b0 & ~fx & t)) & mask;
		int v = (fy ^ b0) & mask;
		int w = ((~fx & ~t) | (b0 & ~t) | (~b0 & fx & t)) & mask;
		boolean upright = ((u ^ v ^ w) & 1) != 0;
		if (upright) {
			out[0] = u; out[1] = v; out[2] = u + 1; out[3] = v; out[4] = u; out[5] = v + 1;
		} else {
			u++;
			v++;
			out[0] = u; out[1] = v; out[2] = u - 1; out[3] = v; out[4] = u; out[5] = v - 1;
		}
	}

	private static int extractEvenBits(int x) {
		x &= 0x55555555;
		x = (x | (x >>> 1)) & 0x33333333;
		x = (x | (x >>> 2)) & 0x0f0f0f0f;
		x = (x | (x >>> 4)) & 0x00ff00ff;
		x = (x | (x >>> 8)) & 0x0000ffff;
		return x;
	}

	private static int prefixEor(int x) {
		x ^= (x >>> 1) & 0x7fff7fff;
		x ^= (x >>> 2) & 0x3fff3fff;
		x ^= (x >>> 4) & 0x0fff0fff;
		x ^= (x >>> 8) & 0x00ff00ff;
		return x;
	}
}
