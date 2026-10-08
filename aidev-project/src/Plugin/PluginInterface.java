package Plugin;

import javax.swing.JPanel;

/**
 * Local compile-time mirror of TBtools' {@code Plugin.PluginInterface}.
 *
 * <p>TBtools defines this interface inside {@code TBtools_JRE1.6.jar} and wires
 * plugins through {@code Plugin.DynamicClassLoader}. That JAR is not part of a
 * normal build, so this stub lets the source tree compile anywhere — including
 * on a machine with no TBtools install — while keeping the entry point's
 * {@code implements} clause honest.
 *
 * <p>When {@code build.ps1} finds a TBtools JAR it puts the real one on the
 * classpath and this stub is shadowed, so the packaged plugin is always compiled
 * against the genuine interface. The file is not shipped in the output JAR.
 */
public interface PluginInterface {

    /** Returns the panel TBtools embeds in its main window. */
    JPanel generatePanel();

    /** Display name shown in the TBtools menu. */
    String getPluginName();
}
