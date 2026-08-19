package mods.hexagon.sdf3d.block;

import mods.hexagon.sdf3d.Sdf3d;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Marker block entity used to place (and track) the SDF instance for {@link SdfBlock}. */
public class SdfBlockEntity extends BlockEntity {
    public SdfBlockEntity(BlockPos pos, BlockState state) {
        super(Sdf3d.SDF_BLOCK_ENTITY.get(), pos, state);
    }
}
