package bioenv.ui;

import java.io.File;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import bioenv.i18n.I18n;
import bioenv.i18n.Strings;

/**
 * File chooser helper that bridges the Windows JFileChooser and the WSL
 * filesystem.
 *
 * <p>TBtools runs on Windows, so the chooser browses Windows paths. Because the
 * files are consumed by Linux tools through {@code /mnt/<drive>}, the
 * translation happens later in {@code WslBridge.toWslPath}; here the concern is
 * only letting the user pick a real, reachable file.
 */
final class FileDialogs {

    private FileDialogs() {
    }

    /**
     * Shows a chooser.
     *
     * @param current     current text in the field, used as the starting point
     * @param directory   true to select a directory rather than a file
     * @param save        true for a save dialog, false to open
     * @param parent      component to parent the dialog to
     * @return the chosen file, or null when cancelled
     */
    static File choose(String current, boolean directory, boolean save, java.awt.Component parent) {
        return choose(current == null ? "" : current, directory, save, parent, startFrom(current));
    }

    /**
     * Shows a directory chooser starting from a given directory.
     *
     * @param start  directory to open at, may be null
     * @param parent component to parent the dialog to
     * @return the chosen directory, or null when cancelled
     */
    static File chooseDirectory(File start, java.awt.Component parent) {
        return choose("", true, false, parent, start);
    }

    /**
     * Shows a chooser that accepts several files at once.
     *
     * <p>For a parameter the tool wants as a list. The files come back in the order
     * the chooser lists them, which is the order the user sees while selecting.
     *
     * @param current the text already in the field, used to start somewhere sensible
     * @param parent  component to parent the dialog to
     * @return the chosen files, or an empty list when cancelled
     */
    static java.util.List<File> chooseMany(String current, java.awt.Component parent) {
        java.util.List<File> out = new java.util.ArrayList<File>();
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        String first = firstPath(current);
        File startAt = startFrom(first);
        if (startAt != null && startAt.exists()) {
            File dir = startAt.isDirectory() ? startAt
                    : startAt.getAbsoluteFile().getParentFile();
            if (dir != null && dir.exists()) {
                chooser.setCurrentDirectory(dir);
            }
        }
        int r = chooser.showOpenDialog(parent);
        if (r != JFileChooser.APPROVE_OPTION) {
            return out;
        }
        File[] chosen = chooser.getSelectedFiles();
        if (chosen != null) {
            for (File f : chosen) {
                if (f != null) {
                    out.add(f);
                }
            }
        }
        if (out.isEmpty() && chooser.getSelectedFile() != null) {
            out.add(chooser.getSelectedFile());
        }
        return out;
    }

    /** The first path in a comma-separated list, for choosing where to open at. */
    private static String firstPath(String list) {
        if (list == null) {
            return "";
        }
        int comma = list.indexOf(',');
        String first = comma < 0 ? list : list.substring(0, comma);
        return first.trim();
    }

    private static File startFrom(String current) {
        return current == null || current.trim().isEmpty()
                ? null : new File(current.trim());
    }

    private static File choose(String current, boolean directory, boolean save,
                               java.awt.Component parent, File startAt) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(directory
                ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
        if (startAt != null && startAt.exists()) {
            File dir = startAt.isDirectory() ? startAt
                    : startAt.getAbsoluteFile().getParentFile();
            if (dir != null && dir.exists()) {
                chooser.setCurrentDirectory(dir);
            }
        }
        if (current != null && !current.trim().isEmpty()) {
            File f = new File(current.trim());
            if (!directory && f.isFile()) {
                chooser.setSelectedFile(f);
            }
        }
        int r = save ? chooser.showSaveDialog(parent) : chooser.showOpenDialog(parent);
        if (r != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File sel = chooser.getSelectedFile();
        if (save && sel.exists()) {
            // The file path is an argument rather than baked into the translated
            // sentence: it must stay verbatim whichever language is showing.
            int confirm = JOptionPane.showConfirmDialog(parent,
                    I18n.t(Strings.FILE_OVERWRITE_QUESTION, sel.getAbsolutePath()),
                    I18n.t(Strings.FILE_OVERWRITE_TITLE), JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) {
                return null;
            }
        }
        return sel;
    }

    /** Thread-safe helper for updating a component from a worker thread. */
    static void onEdt(final Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            guard(r);
        } else {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    guard(r);
                }
            });
        }
    }

    /**
     * 执行界面更新，并挡住「这个实例的类已经不存在了」这一类失败。
     *
     * <h2>为什么会发生</h2>
     *
     * <p>TBtools 重装插件时，先把新 jar 覆盖到
     * {@code .TBtools\.Plugin\<名字>\<名字>.jar}，但<b>不会先丢弃上一个版本的实例</b>。
     * 于是旧版 {@code MainPanel} 可能还活在同一台 JVM 里：它的 SwingWorker 回来
     * 投递结果时，要用的某个匿名类只能从<b>那份刚被覆盖掉的 jar</b> 里取，
     * 自然取不到，于是抛 {@code NoClassDefFoundError}。
     *
     * <p>这类失败没有任何补救余地——那个实例已经没用了，而新装的插件是好的。
     * 让它冒到 TBtools 的未捕获异常对话框，结果就是用户每次装插件都看到一大段栈，
     * 以为装坏了。
     *
     * <h2>为什么只吞这一种</h2>
     *
     * <p>判据是「加载不到的类属于本插件自己的包」——那种情况必定意味着这个实例
     * 所属的 jar 已经不在了。别的类加载失败（第三方依赖缺失、打包真的漏了类）
     * 照旧抛出去：那是真坏了，必须让人看见。
     *
     * <p>不弹窗，但往 stderr 写一行；TBtools 会把它记进日志，排查时还找得到。
     */
    private static void guard(Runnable r) {
        try {
            r.run();
        } catch (NoClassDefFoundError e) {
            if (!isOwnClassUnloadable(e)) {
                throw e;
            }
            System.err.println("[BioEnv Caller] 插件已被重新安装，旧实例的界面更新被忽略："
                    + e.getMessage());
        }
    }

    /** 加载不到的类是不是本插件自己的。 */
    private static boolean isOwnClassUnloadable(NoClassDefFoundError e) {
        String m = e.getMessage();
        if (m == null) {
            return false;
        }
        String n = m.trim().replace('/', '.');
        return n.startsWith("bioenv.") || n.startsWith("vmadapt.");
    }
}
