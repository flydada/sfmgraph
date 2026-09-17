package dev.sfmgraph;

import com.mojang.logging.LogUtils;
import dev.sfmgraph.client.SFMGraphClient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.api.distmarker.Dist;
import org.slf4j.Logger;

/**
 * Adds a visual node-graph editor to Super Factory Manager.
 *
 * <p>Everything this mod does is client side: it registers an extra program editor with SFM, reads
 * the labels off the disk (which SFM already syncs to the client), and saves by writing program text
 * through SFM's own {@code ServerboundManagerProgramPacket} path. SFM stays the only thing that
 * actually moves resources.
 */
@Mod(SFMGraph.MOD_ID)
public class SFMGraph {

    public static final String MOD_ID = "sfmgraph";
    public static final String MOD_NAME = "SFM Graph Editor";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Filled in from the mod container during construction. */
    public static String version = "dev";

    public SFMGraph(IEventBus modBus, ModContainer container) {
        version = String.valueOf(container.getModInfo().getVersion());
        if (FMLEnvironment.dist == Dist.CLIENT) {
            // Touching the client class on a dedicated server would load client-only types.
            SFMGraphClient.init(modBus);
        }
    }
}
