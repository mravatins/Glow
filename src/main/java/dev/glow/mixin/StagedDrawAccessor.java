package dev.glow.mixin;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.List;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StagedVertexBuffer.Draw.class)
public interface StagedDrawAccessor {
	@Accessor("format")
	VertexFormat glow$format();

	@Accessor("vertexBufferSlices")
	List<ByteBufferBuilder.Result> glow$slices();
}
