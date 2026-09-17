package dev.sfmgraph.client;

import ca.teamdman.sfm.client.registry.SFMTextEditors;
import ca.teamdman.sfm.client.screen.ManagerScreen;
import ca.teamdman.sfm.client.screen.SFMScreenChangeHelpers;
import ca.teamdman.sfm.client.text_editor.ISFMTextEditScreenOpenContext;
import ca.teamdman.sfm.client.text_editor.ISFMTextEditorRegistration;
import ca.teamdman.sfm.client.text_editor.SFMTextEditScreenDiskOpenContext;
import ca.teamdman.sfm.common.containermenu.ManagerContainerMenu;
import ca.teamdman.sfm.common.label.LabelPositionHolder;
import ca.teamdman.sfm.common.net.ServerboundManagerProgramPacket;
import ca.teamdman.sfm.common.registry.registration.SFMPackets;
import com.mojang.blaze3d.platform.InputConstants;
import dev.sfmgraph.SFMGraph;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/** Client side wiring: the SFM editor registration and the direct open keybind. */
public final class SFMGraphClient {

    /** The editor id SFM will look up: {@code sfmgraph:graph}. */
    public static final DeferredRegister<ISFMTextEditorRegistration> TEXT_EDITORS =
            DeferredRegister.create(SFMTextEditors.REGISTRY_ID, SFMGraph.MOD_ID);

    /**
     * Opens the graph editor from the manager screen.
     *
     * <p>Ctrl+G rather than a bare key: bare letters are heavily contested in large mod packs (one
     * world this was developed against had seven different actions bound to G), and Ctrl+G mirrors
     * SFM's own Ctrl+E for its text editor.
     */
    public static final KeyMapping OPEN_GRAPH = new KeyMapping(
            "key.sfmgraph.open_graph",
            KeyConflictContext.GUI,
            KeyModifier.CONTROL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "key.categories.sfmgraph"
    );

    /** How many graph editors are currently on the GUI layer stack. */
    private static int openEditors;

    private SFMGraphClient() {
    }

    public static void init(IEventBus modBus) {
        TEXT_EDITORS.register("graph", GraphTextEditorRegistration::new);
        TEXT_EDITORS.register(modBus);
        modBus.addListener(SFMGraphClient::registerKeyMappings);
        NeoForge.EVENT_BUS.addListener(SFMGraphClient::onScreenKeyPressed);
        // One line in the log makes "is it actually loaded?" answerable without guesswork.
        SFMGraph.LOGGER.info(
                "Loaded {} {}: registered SFM text editor '{}:graph', keybind {} (active in the manager screen)",
                SFMGraph.MOD_NAME,
                SFMGraph.version,
                SFMGraph.MOD_ID,
                OPEN_GRAPH.getTranslatedKeyMessage().getString()
        );
    }

    static void editorOpened() {
        openEditors++;
    }

    static void editorClosed() {
        openEditors = Math.max(0, openEditors - 1);
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_GRAPH);
    }

    /**
     * Reads the key press off the screen rather than the usual {@code consumeClick} tick loop.
     *
     * <p>Key bindings are only delivered to the tick loop while no screen is open, and this key only
     * ever matters while the manager screen is open. SFM's own Ctrl+E handles this by checking the
     * key inside its screen; since this addon deliberately does not mix into SFM's screen, it listens
     * for the screen key press event instead, which fires for whichever screen is on top.
     */
    private static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (openEditors > 0) {
            // The graph editor is already on top; it handles its own keys.
            return;
        }
        if (!(event.getScreen() instanceof ManagerScreen)) return;

        KeyMapping mapping = OPEN_GRAPH;
        if (!mapping.matches(event.getKeyCode(), event.getScanCode())) return;
        if (!mapping.getKeyModifier().isActive(KeyConflictContext.GUI)) return;

        if (openEditor()) {
            // Consume it so the manager screen does not react to the same press.
            event.setCanceled(true);
        }
    }

    /**
     * Opens the graph editor on top of the manager screen, reusing exactly the data SFM hands its own
     * editors and saving through SFM's own packet.
     *
     * @return {@code true} when an editor was opened
     */
    private static boolean openEditor() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return false;

        if (!(minecraft.player.containerMenu instanceof ManagerContainerMenu menu)) {
            minecraft.player.displayClientMessage(
                    Component.translatable("sfmgraph.hint.open_manager_first"),
                    true
            );
            return false;
        }
        // The program lives on the disk item, so without one there is nowhere to save it.
        if (menu.getDisk().isEmpty()) {
            minecraft.player.displayClientMessage(
                    Component.translatable("sfmgraph.hint.needs_disk"),
                    true
            );
            return false;
        }

        ISFMTextEditScreenOpenContext context = new SFMTextEditScreenDiskOpenContext(
                menu.program == null ? "" : menu.program,
                LabelPositionHolder.from(menu.getDisk()),
                saveWriter(menu)
        );
        SFMScreenChangeHelpers.setOrPushScreen(new GraphEditorScreen(context));
        return true;
    }

    /**
     * Mirrors {@code ManagerScreen.sendProgram}: the server only accepts a program change while the
     * player still has that manager's container open, which is exactly our situation.
     */
    private static Consumer<String> saveWriter(ManagerContainerMenu menu) {
        return program -> SFMPackets.sendToServer(
                new ServerboundManagerProgramPacket(menu.containerId, menu.MANAGER_POSITION, program)
        );
    }
}
