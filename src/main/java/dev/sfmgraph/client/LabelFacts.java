package dev.sfmgraph.client;

import ca.teamdman.sfm.common.block_network.ICableBlock;
import ca.teamdman.sfm.common.label.LabelPositionHolder;
import ca.teamdman.sfm.common.util.BlockPosIterator;
import ca.teamdman.sfm.common.util.BlockPosSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the client can tell about each SFM label: which blocks carry it, what they are, and whether
 * they look reachable by the manager's cables.
 *
 * <p>The label map itself comes from the disk item, which SFM already syncs to the client, so no
 * extra packets are needed. Positions in unloaded chunks simply show up without a block name.
 */
public final class LabelFacts {

    /**
     * @param positions    every block carrying the label, in the manager's level
     * @param icon         item form of the first block, for drawing in the node header
     * @param blockName    translated block name, or {@code null} when the chunk is not loaded
     * @param hasFacing    whether the block has a facing property, i.e. whether FRONT/LEFT/... work
     * @param cableReach   how many of the positions look adjacent to a cable
     */
    public record Fact(
            String label,
            List<BlockPos> positions,
            ItemStack icon,
            @Nullable String blockName,
            boolean hasFacing,
            int cableReach
    ) {

        public int count() {
            return positions.size();
        }

        public @Nullable BlockPos first() {
            return positions.isEmpty() ? null : positions.get(0);
        }

        /** SFM only works with labelled blocks that touch a cable of the manager's network. */
        public boolean anyNextToCable() {
            return cableReach > 0;
        }

        public int notNextToCable() {
            return Math.max(0, count() - cableReach);
        }

        public String coords() {
            BlockPos pos = first();
            if (pos == null) return "-";
            String text = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
            return count() > 1 ? text + " (+" + (count() - 1) + ")" : text;
        }
    }

    public static final Fact MISSING = new Fact("", List.of(), ItemStack.EMPTY, null, false, 0);

    private LabelFacts() {
    }

    public static Map<String, Fact> collect(LabelPositionHolder holder) {
        Map<String, Fact> facts = new LinkedHashMap<>();
        if (holder == null) return facts;
        Level level = Minecraft.getInstance().level;
        for (Map.Entry<String, BlockPosSet> entry : holder.labels().entrySet()) {
            String label = entry.getKey();
            if (label == null) continue;
            List<BlockPos> positions = new ArrayList<>();
            BlockPosIterator iterator = entry.getValue().blockPosIterator();
            while (iterator.hasNext()) {
                // The iterator reuses one mutable pos, so copy before keeping it.
                positions.add(iterator.next().immutable());
            }
            facts.put(label, describe(label, positions, level));
        }
        return facts;
    }

    private static Fact describe(String label, List<BlockPos> positions, @Nullable Level level) {
        if (positions.isEmpty() || level == null) {
            return new Fact(label, positions, ItemStack.EMPTY, null, false, 0);
        }
        BlockState state = level.getBlockState(positions.get(0));
        ItemStack icon = state.getBlock().asItem().getDefaultInstance();
        String blockName = state.isAir() ? null : state.getBlock().getName().getString();
        boolean hasFacing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                            || state.hasProperty(BlockStateProperties.FACING);
        // Counted per block, not "any block": SFM skips each unconnected block on its own, so a label
        // where only one of four machines touches a cable works for exactly one machine.
        int cableReach = 0;
        for (BlockPos pos : positions) {
            if (touchesCable(level, pos)) cableReach++;
        }
        return new Fact(label, positions, icon, blockName, hasFacing, cableReach);
    }

    /** Mirrors SFM's rule that a labelled block only works when it touches the cable network. */
    private static boolean touchesCable(Level level, BlockPos pos) {
        if (level.getBlockState(pos).getBlock() instanceof ICableBlock) return true;
        for (Direction direction : Direction.values()) {
            if (level.getBlockState(pos.relative(direction)).getBlock() instanceof ICableBlock) return true;
        }
        return false;
    }
}
