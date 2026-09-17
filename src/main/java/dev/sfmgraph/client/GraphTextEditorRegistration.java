package dev.sfmgraph.client;

import ca.teamdman.sfm.client.screen.text_editor.ISFMTextEditScreen;
import ca.teamdman.sfm.client.text_editor.ISFMTextEditScreenOpenContext;
import ca.teamdman.sfm.client.text_editor.ISFMTextEditorRegistration;

/**
 * Hooks the graph editor into SFM's text editor registry, which is the extension point SFM itself
 * uses for its three built-in editors.
 *
 * <p>Once registered the editor id {@code sfmgraph:graph} can be chosen in SFM's client config
 * ({@code config/sfm-client-program-editor.toml}), after which the manager's own edit button opens
 * the graph editor.
 */
public class GraphTextEditorRegistration implements ISFMTextEditorRegistration {

    @Override
    public ISFMTextEditScreen createScreen(ISFMTextEditScreenOpenContext context) {
        return new GraphEditorScreen(context);
    }
}
