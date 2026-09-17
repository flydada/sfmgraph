package dev.sfmgraph.client.ui;

import java.util.List;

/** A blocking message box drawn over the editor, with a row of buttons. */
public record Modal(String title, List<String> lines, List<MenuBar.Item> buttons) {
}
