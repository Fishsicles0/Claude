package net.evadeclient.clips.ui;

import javax.swing.JPanel;
import java.awt.CardLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The four Clips pages (Library, Record, Editor, Settings) in one card panel, without any window around them. The
 * standalone {@link MainWindow} puts its sidebar next to it; the Evade Launcher shows it as its "Clips" section.
 */
public final class ClipsPages extends JPanel {
    public static final List<String> IDS = List.of("library", "record", "editor", "settings");

    private final CardLayout cards = new CardLayout();
    public final LibraryPanel library;
    public final RecordPanel record;
    public final EditorPanel editor;
    public final SettingsPanel settings;
    private String page = "library";
    private final List<Consumer<String>> listeners = new ArrayList<>();

    public ClipsPages(App app) {
        setLayout(cards);
        setBackground(Theme.PANEL);
        library = new LibraryPanel(app);
        record = new RecordPanel(app);
        editor = new EditorPanel(app);
        settings = new SettingsPanel(app);
        add(library, "library");
        add(record, "record");
        add(editor, "editor");
        add(settings, "settings");
        // Focus moves here when a page opens, so it never lands on a random control (or one hidden with the old page).
        setFocusable(true);
    }

    /** Opens a page: "library", "record", "editor" (only with a clip open) or "settings". */
    public void show(String name) {
        if (!IDS.contains(name)) return;
        if (name.equals("editor") && editor.clip() == null) name = "library";
        if (page.equals("editor") && !name.equals("editor")) editor.close();
        page = name;
        cards.show(this, name);
        switch (name) {
            case "library" -> library.focusGrid();
            case "editor" -> {} // the editor puts focus on its video itself
            default -> requestFocusInWindow();
        }
        for (Consumer<String> l : listeners) l.accept(name);
    }

    public String page() { return page; }

    /** Called (on the UI thread) whenever another page opens. */
    public void onPageChanged(Consumer<String> l) { listeners.add(l); }

    /** Frees what's only needed while the pages are on screen (the open clip, thumbnails). */
    public void hidden() {
        editor.close();
        library.clearCaches();
        if (page.equals("editor")) { page = "library"; cards.show(this, "library"); }
    }

    public void focusLibrary() { library.focusGrid(); }
}
