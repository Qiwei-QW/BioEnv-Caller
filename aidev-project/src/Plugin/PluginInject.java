package Plugin;

import bioenv.i18n.I18n;
import bioenv.ui.MainPanel;
import javax.swing.JPanel;

/**
 * TBtools plugin entry point.
 *
 * <p>TBtools locates this class from {@code project.json}
 * ({@code panelPackage} + {@code panelClassSimpleName}) and calls
 * {@link #generatePanel()} to obtain the panel it embeds, and
 * {@link #getPluginName()} for the display name.
 *
 * <p>TBtools ships a {@code Plugin.PluginInterface} declaring exactly these two
 * methods, and {@code Plugin.DynamicClassLoader} loads the entry point through
 * it. The reference {@code FASTA Merge} plugin declares no {@code implements}
 * clause and works because TBtools resolves the methods reflectively, but
 * implementing the interface is strictly safer: it turns a signature mistake
 * into a compile error instead of a plugin that silently fails to load.
 *
 * <p>The interface lives in TBtools' own JAR, so it is compiled against only
 * when that JAR is on the build classpath (see build.ps1). The plugin still runs
 * correctly without it because the methods match by name and signature.
 */
public class PluginInject implements PluginInterface {

    /**
     * True once the saved UI language has been read.
     *
     * <p>Guarded so the file is read once per JVM rather than once per panel:
     * TBtools may build the panel more than once (and the AI Dev panel rebuilds
     * it), and re-reading the file each time would also overwrite a language the
     * user changed during the session.
     */
    private static boolean languageLoaded;

    /** Returns the panel TBtools embeds in its main window. */
    @Override
    public JPanel generatePanel() {
        // Restore the saved language before the panel builds its labels, so the
        // first paint is already in the right language rather than flashing the
        // default and switching a moment later.
        if (!languageLoaded) {
            languageLoaded = true;
            I18n.loadOnce();
        }
        return new MainPanel();
    }

    /** Display name shown in the TBtools menu. */
    @Override
    public String getPluginName() {
        return "BioEnv Caller";
    }
}
