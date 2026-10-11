package bioenv.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.core.TypeInference;
import bioenv.core.WslBridge;
import bioenv.i18n.DescZh;
import bioenv.i18n.I18n;
import bioenv.i18n.Strings;

/**
 * Renders a parameter form for any tool, driven entirely by its discovered
 * {@link Schema}.
 *
 * <p>No tool is special-cased: every control comes from a {@link Schema.Option}
 * (DESIGN.md P1, P3). The form is rebuilt whenever the selected tool or
 * subcommand changes.
 *
 * <p>Control kinds are inferred from help text and are therefore only a guess.
 * Each row therefore offers a small kind selector so the user can correct it
 * (DESIGN.md P7); the override is kept per option flag and survives until the
 * tool changes.
 */
public final class SchemaFormPanel extends JPanel {

    /** The command whose schema is currently displayed. */
    private Tool tool;
    /** Active schema, i.e. the tool's or a selected subcommand's. */
    private Schema schema;
    /** Subcommand path, empty for the top level. */
    private String subPath = "";

    private final Map<String, JComponent> editors = new LinkedHashMap<String, JComponent>();
    private final Map<String, JLabel> labels = new LinkedHashMap<String, JLabel>();
    /**
     * The control kind the user chose for an option, keyed by its flag.
     *
     * <p>Every option starts as a plain text field. Nothing is inferred, so there
     * is no wrong answer to correct — but the user may know that a value is a file
     * or a number and want the matching control. The choice is theirs to make and
     * is remembered, including a choice to go back to text.
     */
    private final Map<String, Schema.Option.Kind> chosenKind =
            new HashMap<String, Schema.Option.Kind>();

    /**
     * Text typed before the row's control was changed, so switching kind does not
     * discard what the user entered.
     */
    private final Map<String, String> carryText = new HashMap<String, String>();

    /**
     * The map key for an option's settings, scoped to the current subcommand.
     *
     * <p>Two subcommands of one tool commonly share an option name — {@code bio
     * fasta} and {@code bio gff} both take {@code -s} — so a key of the flag alone
     * would carry a choice made in one into the other.
     */
    private String scopedKey(Schema.Option o) {
        return subPath + "\u0000" + key(o);
    }

    /**
     * The name this panel's current options are stored under.
     *
     * <p>The tool's own name for its top-level options, and the tool plus the
     * subcommand for a subcommand's — so the two never share an entry.
     */
    private String scopeName() {
        if (tool == null) {
            return "";
        }
        return subPath.isEmpty() ? tool.command : tool.command + " " + subPath;
    }

    /**
     * The components making up one option's row.
     *
     * <p>Kept together so a search can hide a whole row. Hiding the label alone
     * would leave an orphaned value field, and hiding the field alone would leave a
     * label pointing at nothing.
     */
    private static final class FormRow {
        final Schema.Option option;
        final Component[] parts;
        /** The group heading that introduced this row, shown or hidden with it. */
        final JLabel heading;

        FormRow(Schema.Option option, Component label, Component editor,
                Component kind, JLabel heading) {
            this.option = option;
            this.parts = new Component[]{label, editor, kind};
            this.heading = heading;
        }

        /**
         * True when this row matches a search term.
         *
         * <p>The description is matched against <b>both</b> the tool's original
         * English and its Chinese gloss. The original must keep working on its own:
         * the search box is filled from what the user reads, but users also paste
         * flag names and English keywords straight out of a tool's manual, and a
         * Chinese-mode UI that could no longer find {@code "input file"} would have
         * silently lost a working feature. Matching the gloss as well simply means
         * the term the user sees on screen also finds the row.
         *
         * <p>{@code DescZh.translate} is the identity in English mode, so the second
         * comparison is the same string there and nothing changes for English users.
         */
        boolean matches(String needle) {
            if (needle == null || needle.isEmpty()) {
                return true;
            }
            String n = needle.toLowerCase();
            String flag = option.flag == null ? "" : option.flag.toLowerCase();
            String desc = option.description == null ? "" : option.description.toLowerCase();
            String alias = option.aliases.toString().toLowerCase();
            if (flag.contains(n) || desc.contains(n) || alias.contains(n)) {
                return true;
            }
            String gloss = DescZh.translate(
                    option.description == null ? "" : option.description).toLowerCase();
            return gloss.contains(n);
        }
    }

    /** Every option's row, in display order, for the search to show or hide. */
    private final java.util.List<FormRow> formRows =
            new java.util.ArrayList<FormRow>();

    /**
     * Shows only the rows matching the search term.
     *
     * <p>A tool with sixty options is hard to use by scrolling alone: the user knows
     * what they are looking for and should not have to read every row to find it.
     * Matching covers the name, its aliases and the description, because the
     * description is often what the user remembers — they may not know the flag is
     * called {@code -o} but they know it is about the output.
     *
     * @param needle the text to match, empty to show everything
     * @return how many rows are visible
     */
    public int filterOptions(String needle) {
        int shown = 0;
        for (FormRow row : formRows) {
            boolean visible = row.matches(needle);
            for (Component part : row.parts) {
                if (part != null) {
                    part.setVisible(visible);
                }
            }
            // A heading is shown only when something under it is.
            if (row.heading != null) {
                row.heading.setVisible(visible);
            }
            if (visible) {
                shown++;
            }
        }
        // Lay the form out again so the hidden rows give up their space; without
        // this the gaps stay where the rows were.
        if (optionForm != null) {
            optionForm.revalidate();
            optionForm.repaint();
        }
        return shown;
    }

    /**
     * The row above the option list: a search field and a button that opens the
     * options in a window of their own.
     *
     * <p>The search exists because a tool with sixty options is not usable by
     * scrolling alone — the user knows what they are looking for. The button exists
     * because the form shares the panel with the tree, the preview and the output,
     * and a user filling in a long tool may want all of the screen for the task.
     */
    private JComponent buildOptionToolbar() {
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        optionSearchField = new JTextField();
        optionSearchField.setToolTipText(I18n.t(Strings.FORM_SEARCH_TIP));
        optionSearchField.getDocument().addDocumentListener(
                new javax.swing.event.DocumentListener() {
                    @Override
                    public void insertUpdate(javax.swing.event.DocumentEvent e) {
                        applyOptionFilter();
                    }

                    @Override
                    public void removeUpdate(javax.swing.event.DocumentEvent e) {
                        applyOptionFilter();
                    }

                    @Override
                    public void changedUpdate(javax.swing.event.DocumentEvent e) {
                        applyOptionFilter();
                    }
                });

        searchCaption = new JLabel(I18n.t(Strings.FORM_FIND));

        // Kept as a field so it can be hidden while the options are already in that
        // window: the toolbar travels with the option list, and offering to open a
        // window from inside that window would do nothing.
        openWindowButton = new JButton(I18n.t(Strings.FORM_OPEN_WINDOW));
        openWindowButton.setMargin(new Insets(1, 8, 1, 8));
        openWindowButton.setToolTipText(I18n.t(Strings.FORM_OPEN_WINDOW_TIP));
        openWindowButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openOptionsWindow();
            }
        });

        // The way to add a parameter lives in the parameter editor, not here. The
        // toolbar had grown a search field, a count, an add button and a window
        // button, which is a lot of furniture above a list; adding a parameter is a
        // correction to the tool, and belongs with the other corrections.
        optionCountLabel = new JLabel();
        optionCountLabel.setForeground(new Color(0x66, 0x66, 0x66));

        // 搜索整组挪到这一行的右端：左边空出来，右边是「找到哪个」和「在窗口里打开」。
        // 框宽写死，免得它把整行吃掉。
        optionSearchField.setPreferredSize(new Dimension(260,
                optionSearchField.getPreferredSize().height));
        // 居中排列。
        //
        // 这一组（查找框 + 参数计数 + 在新窗口中打开）走过三个位置：原来靠右、
        // 后来靠左、现在居中 —— 居中时它落在选项区的视觉重心上，两边留白对称。
        // BorderLayout.CENTER 让这一格占满整行宽度，FlowLayout.CENTER 再把这一组
        // 摆在中间；CENTER 格本身有 weight，窗口拉宽也会自动跟着居中。
        JPanel row = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.CENTER, 6, 0));
        row.add(searchCaption);
        row.add(optionSearchField);
        row.add(optionCountLabel);
        row.add(openWindowButton);

        bar.add(row, BorderLayout.CENTER);
        return bar;
    }

    /** Note that a parameter was removed, without claiming a run was affected. */
    private void setStatusRemoved(String flag) {
        if (statusSink != null) {
            statusSink.accept(I18n.t(Strings.FORM_REMOVED_FLAG, flag));
        }
    }

    /** Receives a one-line status message, so this panel need not own the bar. */
    public interface StatusSink {
        void accept(String message);
    }

    public void setStatusSink(StatusSink sink) {
        this.statusSink = sink;
    }

    /** The store of parameters added by hand; null when none is available. */
    public void setCustomOptions(bioenv.core.CustomOptionStore store) {
        this.customOptions = store;
    }

    private bioenv.core.CustomOptionStore customOptions;
    private StatusSink statusSink;


    /**
     * Hides the "open in a window" button while the options are in that window.
     *
     * <p>The toolbar is part of the option list and moves with it. Pressing the
     * button there would find the window already open and merely raise it, which
     * looks like nothing happened.
     */
    private void setOpenWindowButtonVisible(boolean visible) {
        if (openWindowButton != null) {
            openWindowButton.setVisible(visible);
        }
    }

    /** Applies the current search text and reports how many rows remain. */
    private void applyOptionFilter() {
        String needle = optionSearchField == null
                ? "" : optionSearchField.getText().trim();
        int shown = filterOptions(needle);
        if (optionCountLabel != null) {
            if (needle.isEmpty()) {
                // One template with a count, not "N option" + a plural "s": Chinese
                // has no plural suffix, so assembling it from pieces cannot work.
                optionCountLabel.setText(I18n.t(Strings.FORM_OPTION_COUNT,
                        Integer.valueOf(formRows.size())));
            } else {
                optionCountLabel.setText(I18n.t(Strings.FORM_OPTION_SHOWN_OF,
                        Integer.valueOf(shown), Integer.valueOf(formRows.size())));
            }
        }
        // Everything hidden is a state the user did not intend to reach and cannot
        // easily see; saying so is clearer than an empty list.
        if (!needle.isEmpty() && shown == 0 && optionCountLabel != null) {
            optionCountLabel.setText(I18n.t(Strings.FORM_NO_MATCH));
            optionCountLabel.setForeground(new Color(0xB0, 0x50, 0x00));
        } else if (optionCountLabel != null) {
            optionCountLabel.setForeground(new Color(0x66, 0x66, 0x66));
        }
    }

    /**
     * The option list's titled border, kept in a field so a language switch can
     * retitle it. A {@link javax.swing.border.TitledBorder} is not a component, so
     * nothing walking the container tree would find it again.
     */
    private javax.swing.border.TitledBorder optionsBorder;

    /** Builds (or rebuilds) the option list's border in the current language. */
    private javax.swing.border.TitledBorder optionsBorder() {
        // 新风格：不加框线，标题按次级灰的小字画；经典：原样。
        optionsBorder = themedTitledBorder(I18n.t(Strings.FORM_OPTIONS_TITLE));
        return optionsBorder;
    }

    /** Opens the options in their own window. */
    public void openOptionsInWindow() {
        openOptionsWindow();
    }

    /** Closes the options window, putting the list back in the panel. */
    public void closeOptionsWindowForTest() {
        closeOptionsWindow();
    }

    /**
     * Opens the options in a window of their own, with the command beneath them.
     *
     * <p>A convenience for a tool with many parameters: the panel shares its width
     * with the tree and the output, and filling in a long command benefits from
     * having the screen. The window holds the same form objects, not copies, so
     * anything typed here appears there and the command preview stays in step.
     */
    private void openOptionsWindow() {
        if (optionsWindow != null && optionsWindow.isDisplayable()) {
            optionsWindow.toFront();
            return;
        }
        JPanel wide = new JPanel(new BorderLayout(4, 4));
        wide.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        // The live option area, moved out of the panel for as long as the window is
        // open and put back when it closes. Sharing the object is what keeps the
        // two views consistent; duplicating the form would let them disagree.
        if (optionScroll != null) {
            optionHost.remove(optionScroll);
            optionScroll.setBorder(optionsBorder());
            wide.add(optionScroll, BorderLayout.CENTER);
        }
        // The search field stays — it is as useful in a large window as in a small
        // one — while the button that opens this window is hidden, since it is
        // already here.
        setOpenWindowButtonVisible(false);
        wide.add(buildWindowPreview(), BorderLayout.SOUTH);

        // A frame rather than a dialog, so the window carries the system's maximise
        // button and can be double-clicked to fill the screen. A dialog has only a
        // close button, which made "full screen" impossible however the window was
        // configured. The trade is that this window is no longer always above
        // TBtools, which is the right way round for a window the user may want to
        // work in at full size.
        optionsWindow = new javax.swing.JFrame(
                I18n.t(Strings.FORM_OPTIONS_WINDOW, describeCommand()));
        optionsWindow.setDefaultCloseOperation(
                javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
        optionsWindow.setContentPane(wide);
        // 这是独立窗口，不在主面板的组件树里 —— 主面板那几遍主题刷新走不到它，
        // 不单独刷一次的话里面一直是原生外观（原生下拉框、原生按钮）。
        UiTheme.applyTo(wide);
        // A generous starting size, then left to the user: the point of the window is
        // that it can be made as large as the screen allows.
        optionsWindow.setSize(1100, 800);
        optionsWindow.setMinimumSize(new Dimension(480, 320));
        optionsWindow.setLocationRelativeTo(this);
        optionsWindow.setResizable(true);
        optionsWindow.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                closeOptionsWindow();
            }

            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                closeOptionsWindow();
            }
        });
        optionsWindow.setVisible(true);
        if (optionSearchField != null) {
            optionSearchField.requestFocusInWindow();
        }
    }

    /** Expands or restores the options window. */
    private void toggleWindowMaximised() {
        if (optionsWindow == null) {
            return;
        }
        int state = optionsWindow.getExtendedState();
        if ((state & java.awt.Frame.MAXIMIZED_BOTH) != 0) {
            optionsWindow.setExtendedState(java.awt.Frame.NORMAL);
        } else {
            optionsWindow.setExtendedState(java.awt.Frame.MAXIMIZED_BOTH);
        }
    }

    /**
     * Expands the options window to fill the screen.
     *
     * <p>Offered for the same reason the window exists: a tool with many parameters
     * is easier to fill in with the screen to itself, and the button saves the user
     * hunting for the window frame's maximise control.
     */
    public void maximiseOptionsWindow() {
        toggleWindowMaximised();
    }

    /** Puts the option area back where it belongs and disposes of the window. */
    private void closeOptionsWindow() {
        if (optionsWindow == null) {
            return;
        }
        java.awt.Window w = optionsWindow;
        optionsWindow = null;
        if (optionScroll != null) {
            w.remove(optionScroll);
            optionHost.add(optionScroll, BorderLayout.CENTER);
        }
        setOpenWindowButtonVisible(true);
        // A fresh window starts following the form again.
        windowPreviewEdited = false;
        windowPreviewArea = null;
        w.dispose();
        rebuild();
    }

    /**
     * The command line shown under the window's option list, with a run button.
     *
     * <p>The text is editable here for the same reason it is editable in the panel:
     * someone who knows the tool should be able to type the command rather than work
     * around the form. Editing stops the automatic refresh, exactly as it does in the
     * panel, so a command someone wrote is never overwritten.
     *
     * <p>Running from here closes the window first, so the log and the progress are
     * where the user expects to watch them.
     */
    private JComponent buildWindowPreview() {
        JPanel p = new JPanel(new BorderLayout(4, 2));
        // 这里用不带框线的标题边框：这个面板在选项窗口里是贴底的一条，
        // 带框线会在它上面横一道白线，很突兀。
        p.setBorder(plainTitledBorder(I18n.t(Strings.FORM_COMMAND_LABEL)));

        JTextArea area = new JTextArea(3, 60);
        area.setEditable(true);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setBackground(Color.WHITE);
        area.setName(WINDOW_PREVIEW_NAME);
        windowPreviewArea = area;
        // Same rule as the panel's preview: the plugin's own writes do not count as
        // an edit, the user's do.
        area.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                noteWindowPreviewEdited();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                noteWindowPreviewEdited();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                noteWindowPreviewEdited();
            }
        });

        runFromWindowButton = new JButton(I18n.t(Strings.FORM_RUN_THIS));
        runFromWindowButton.setMargin(new Insets(2, 12, 2, 12));
        runFromWindowButton.setToolTipText(I18n.t(Strings.FORM_RUN_THIS_TIP));
        runFromWindowButton.setName(WINDOW_RUN_NAME);
        runFromWindowButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                runFromWindow();
            }
        });

        JPanel south = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.RIGHT, 6, 2));
        south.add(runFromWindowButton);

        p.add(new JScrollPane(area), BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        updateWindowPreview();
        return p;
    }

    /** Records that the user has taken the window's command over. */
    private void noteWindowPreviewEdited() {
        if (windowPreviewWriting || windowPreviewEdited) {
            return;
        }
        windowPreviewEdited = true;
        if (windowPreviewArea != null) {
            windowPreviewArea.setBackground(new Color(0xFF, 0xFD, 0xE8));
        }
    }

    /**
     * Runs what the window shows, after putting the panel back.
     *
     * <p>The window is closed first so the run is watched in the panel: the log, the
     * progress and the stop button all live there.
     */
    private void runFromWindow() {
        if (optionsWindow == null) {
            return;
        }
        String typed = windowPreviewArea == null ? "" : windowPreviewArea.getText();
        boolean handEdited = windowPreviewEdited;
        // Close before running, so the panel is visible when the output arrives.
        closeOptionsWindow();
        if (runner == null) {
            return;
        }
        if (handEdited) {
            // The window's text is what the user asked for, so it is what the panel
            // should show and run.
            setCommandTextFromWindow(typed);
        }
        runner.run();
    }

    /** Puts the window's edited text into the panel's preview and marks it edited. */
    private void setCommandTextFromWindow(String text) {
        if (previewSink != null) {
            previewSink.setText(text);
        }
    }

    /**
     * Receives the panel's preview text, so the window's edits can be handed over
     * without this class knowing where the preview lives.
     */
    public interface PreviewSink {
        void setText(String text);
    }

    public void setPreviewSink(PreviewSink sink) {
        this.previewSink = sink;
    }

    /** Starts a run; supplied by the panel that owns the run button. */
    public interface Runner {
        void run();
    }

    public void setRunner(Runner r) {
        this.runner = r;
    }

    /** The name of the window's run button, for tests. */
    public static final String WINDOW_RUN_NAME = "bioenv.windowRun";

    private PreviewSink previewSink;
    private Runner runner;
    private JButton runFromWindowButton;
    private boolean windowPreviewWriting;
    private boolean windowPreviewEdited;


    /** Refreshes the command shown in the options window, if it is open. */
    public void refreshWindowPreview() {
        updateWindowPreview();
    }

    /** Refreshes the window's command line from the current form contents. */
    private void updateWindowPreview() {
        if (windowPreviewArea == null || commandPreviewer == null) {
            return;
        }
        // Once the user has written the command themselves, it is theirs; a form
        // change no longer replaces it. Same rule as the panel's preview.
        if (windowPreviewEdited) {
            return;
        }
        windowPreviewWriting = true;
        try {
            windowPreviewArea.setText(commandPreviewer.get());
        } catch (Exception e) {
            windowPreviewArea.setText(I18n.t(Strings.LOG_CANNOT_BUILD, e.getMessage()));
        } finally {
            windowPreviewWriting = false;
        }
    }

    /**
     * Supplies the command line for the window, so this panel does not need to know
     * how a command is assembled.
     */
    public interface CommandPreviewer {
        String get();
    }

    public void setCommandPreviewer(CommandPreviewer p) {
        this.commandPreviewer = p;
    }

    /** The name of the window's preview area, for tests and for accessibility. */
    public static final String WINDOW_PREVIEW_NAME = "bioenv.windowPreview";

    private javax.swing.JFrame optionsWindow;
    private JTextArea windowPreviewArea;
    private CommandPreviewer commandPreviewer;

    /** The form's container, so filtering can trigger a fresh layout. */
    private JPanel optionForm;

    /**
     * Holds the option area while it is in the panel.
     *
     * <p>It exists so the window can move the area out and put it back into a known
     * slot. Handing the split pane a null component would leave it with nothing to
     * restore.
     */
    private JPanel optionHost;


    /**
     * The command preview section, when this panel is shown with one.
     *
     * <p>Read by callers that want to fold it, and by tests.
     */
    public CollapsiblePanel previewSection() {
        return previewSection;
    }

    private CollapsiblePanel previewSection;

    /** Wraps a component in the foldable preview section, for the host panel. */
    public CollapsiblePanel makePreviewSection(JComponent commandPanel) {
        previewSection = new CollapsiblePanel(I18n.t(Strings.FORM_COMMAND_PREVIEW), commandPanel, false);
        return previewSection;
    }

    /** How many option rows the current form holds. */
    public int optionCount() {
        return formRows.size();
    }

    /** The search field, so it can be focused when the panel appears. */
    public JTextField optionSearch() {
        return optionSearchField;
    }

    private JTextField optionSearchField;
    private JLabel optionCountLabel;

    /** The "Find" caption; a field so a language switch can relabel it. */
    private JLabel searchCaption;

    /** The button that moves the options into a window; hidden once they are there. */
    private JButton openWindowButton;

    /**
     * Components whose text is translated and must be re-labelled on a language
     * switch. Kept as fields because most are created inside builder methods and
     * would otherwise be unreachable from the refresh callback.
     */
    private JLabel workdirLabel;
    private JButton makeWorkButton;
    private JLabel extraArgsLabel;
    private javax.swing.border.TitledBorder commandBorder;


    /**
     * Records what every current control holds, so a rebuild can restore it.
     *
     * <p>Kept apart from {@code carryText}'s other use — a value being moved from one
     * kind of control to another — only by intent; both end up as the text a new
     * control starts from. A tick box records {@code "true"} when ticked and nothing
     * when not, which is what a rebuilt tick box reads back.
     */
    private void captureCurrentValues() {
        if (schema == null || editors.isEmpty()) {
            return;
        }
        for (Schema.Option o : schema.options) {
            JComponent editor = editors.get(key(o));
            if (editor == null) {
                continue;
            }
            carryText.put(scopedKey(o), currentText(o));
        }
    }

    /** The kind to show for an option: the user's choice, or text by default. */
    private Schema.Option.Kind kindOf(Schema.Option o) {
        // 三级优先：用户指定 > 帮助里推断出来的 > 文本。
        //
        // 这里以前只认前两级里的"是不是开关"（isSwitch），压根没用 o.kind ——
        // 等于把解析阶段推断出的类型整个丢掉了，结果凡是没被用户改过、又刚好被
        // 判成开关的参数都成了勾选框（-v、-h 只是凑巧在其中，并不是写死的）。
        //
        // 现在的规则就是原先定的那条：推断出来就按推断出的来，推断不出来按文本。
        Schema.Option.Kind k = chosenKind.get(scopedKey(o));
        if (k != null) {
            return k;
        }
        if (o.kind != null) {
            return o.kind;
        }
        return Schema.Option.Kind.TEXT;
    }

    /**
     * Loads the kinds the user chose for this tool and subcommand in an earlier
     * session.
     *
     * <p>Called whenever the panel is shown or the subcommand changes, so a choice
     * comes back both for the tool's own options and for each of its subcommands
     * separately.
     */
    private void recallChosenKinds() {
        if (kindMemory == null || tool == null || schema == null) {
            return;
        }
        for (Schema.Option o : schema.options) {
            Schema.Option.Kind k = kindMemory.recall(schema.scope(tool.command), key(o));
            if (k != null) {
                chosenKind.put(scopedKey(o), k);
            }
        }
    }

    /** The text currently in an option's editor, ready to carry to a new control. */
    private String currentText(Schema.Option o) {
        String carried = carryText.get(scopedKey(o));
        if (carried != null) {
            return carried;
        }
        JComponent editor = editors.get(key(o));
        if (editor instanceof JCheckBox) {
            return ((JCheckBox) editor).isSelected() ? "true" : "";
        }
        if (editor instanceof JComboBox) {
            Object sel = ((JComboBox<?>) editor).getSelectedItem();
            return sel == null ? "" : sel.toString();
        }
        if (editor instanceof JSpinner) {
            Object v = ((JSpinner) editor).getValue();
            // 整数值不留小数点尾巴：4.0 → "4"，1.2 保持 "1.2"。
            //
            // Double 的 toString 一律带小数位，于是 -d 4 会写成 -d 4.0 —— 多数工具
            // 认，但看着不干净，而且和帮助里的默认值写法不一致。
            // stripTrailingZeros 正好做这件事：4.0 → 4，1.2 → 1.2。
            if (v instanceof Number) {
                try {
                    return new java.math.BigDecimal(v.toString())
                            .stripTrailingZeros().toPlainString();
                } catch (NumberFormatException e) {
                    return String.valueOf(v);
                }
            }
            return String.valueOf(v);
        }
        JTextField f = findField(editor);
        return f == null ? "" : HintText.realText(f);
    }

    private final JLabel headerLabel = new JLabel();
    private final JTextArea descArea = new JTextArea(2, 30);
    private final JTextField workdirField = new JTextField(26);
    private final JTextArea rawArgsArea = new JTextArea(3, 30);
    /** Guards against attaching the document listener more than once. */
    private boolean rawArgsListenerAttached;
    private final JPanel formHolder = new JPanel(new BorderLayout());
    /**
     * Scroll pane holding the option form.
     *
     * <p>Kept as a field rather than rebuilt each time, because the scroll position
     * lives on it. Changing one option's control rebuilds the form inside, and
     * discarding the pane would throw away where the user had scrolled to — they
     * would state a kind for a parameter and be returned to the top of a long list.
     */
    private JScrollPane optionScroll;

    /** Set when the next rebuild should start at the top, as for a new tool. */
    private boolean resetScroll;

    /** Divides the option list from the extra-arguments box; resizable. */
    private JSplitPane formSplit;

    /**
     * Height of one parameter row, used as the wheel's unit increment.
     *
     * <p>Measured from the laid-out form: a control plus the vertical padding the
     * row insets add comes to about 33 pixels. The point is that one wheel notch
     * moves one row, which is what makes a dense form scroll predictably instead
     * of appearing to ignore the wheel.
     */
    private static final int ROW_HEIGHT = 33;
    private final JComboBox<String> subcommandBox = new JComboBox<String>();
    private final JPanel subcommandRow = new JPanel(new BorderLayout(6, 0));
    private final JLabel schemaStatus = new JLabel();

    /** Notified when the user changes an option's value. */
    private Runnable changeListener = new Runnable() {
        @Override
        public void run() {
        }
    };

    /**
     * Receives type corrections so they can be persisted
     * (DESIGN.md P10, layer 5).
     */
    public interface CorrectionSink {
        void correct(String command, String option, Schema.Option.Kind kind);
    }

    /**
     * Supplies the kind a user previously chose for an option, so the choice
     * survives a restart.
     *
     * <p>Without this the chooser would be a per-session convenience: the user
     * states the kind, closes the plugin, and has to state it again. Stating it
     * once and having it kept is the point.
     */
    public interface KindMemory {
        /** @return the remembered kind, or null when none was ever chosen */
        Schema.Option.Kind recall(String command, String option);
    }

    /**
     * Asks the owner to learn a subcommand's options when the user selects it.
     *
     * <p>Subcommands are learned on demand rather than all at once: a tool may
     * expose dozens, and probing every one of them would make opening the tool
     * take minutes for options the user is not looking at (DESIGN.md P21).
     */
    public interface SubcommandLoader {
        Schema load(Schema.Subcommand sub);
    }

    private CorrectionSink correctionSink;
    private SubcommandLoader subcommandLoader;
    private KindMemory kindMemory;

    /** Sets the sink that persists user type corrections. */
    public void setCorrectionSink(CorrectionSink sink) {
        this.correctionSink = sink;
    }

    /** Sets the source that recalls kinds the user chose earlier. */
    public void setKindMemory(KindMemory memory) {
        this.kindMemory = memory;
    }

    /** Sets the loader that learns a subcommand's options on demand. */
    public void setSubcommandLoader(SubcommandLoader loader) {
        this.subcommandLoader = loader;
    }

    /**
     * 工具名右侧的动作按钮。位置由本面板决定，按钮本身由宿主（MainPanel）提供。
     *
     * <p>做成一个固定的容器而不是每次 rebuild 现造：宿主在构造期就把按钮塞进来，
     * 这里只负责把它摆在标题行右边。容器本身没有内容，所以也没必要重造。
     */
    private final JPanel headerActions = new JPanel(
            new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));

    /** 把一个动作按钮放到工具名右侧（宿主调用）。 */
    public void addHeaderAction(javax.swing.JComponent c) {
        headerActions.add(c);
        headerActions.revalidate();
        headerActions.repaint();
    }

    /**
     * 面板顶部：工具名 + 右上角动作，下面依次是状态行、说明段落、工作目录。
     *
     * <p>构造和每次 rebuild 都走同一条路径，这样两条分支不会长歪。
     */
    private JPanel buildHead() {
        JPanel titleRow = new JPanel(new BorderLayout(8, 0));
        titleRow.add(headerLabel, BorderLayout.CENTER);
        titleRow.add(headerActions, BorderLayout.EAST);

        // 状态行紧跟工具名：它是这一屏最该先看到的一行。
        JPanel mid = new JPanel(new BorderLayout(4, 2));
        mid.add(schemaStatus, BorderLayout.NORTH);
        mid.add(descArea, BorderLayout.CENTER);

        JPanel head = new JPanel(new BorderLayout(4, 2));
        head.add(titleRow, BorderLayout.NORTH);
        head.add(mid, BorderLayout.CENTER);
        // 工作目录：标签一行，输入框一行（见 buildWorkdirRow）。
        head.add(buildWorkdirRow(), BorderLayout.SOUTH);
        return head;
    }

    /** 换风格时把这一层重刷一遍；由构造器注册到 UiTheme。 */
    private final Runnable themeRefresh = new Runnable() {
        @Override
        public void run() {
            applyTheme();
            revalidate();
            repaint();
        }
    };

    /**
     * 按当前风格刷新颜色、字体与边框。
     *
     * <p>和 MainPanel 一样：只改"怎么画"，控件本身一个都不重建 —— 用户已经填进去的
     * 值、以及参数的顺序都不能因为换个颜色就没了。
     */
    /** 分区标题：只留一行小字、不加框线（给贴边的面板用）。 */
    private javax.swing.border.TitledBorder plainTitledBorder(String title) {
        if (UiTheme.flat()) {
            return BorderFactory.createTitledBorder(BorderFactory.createEmptyBorder(),
                    title, javax.swing.border.TitledBorder.LEADING,
                    javax.swing.border.TitledBorder.TOP,
                    UiTheme.ui(12, true), UiTheme.text3());
        }
        return BorderFactory.createTitledBorder(title);
    }

    /** 分区标题：新风格只留一行小字、不加框线；经典原样。 */
    private javax.swing.border.TitledBorder themedTitledBorder(String title) {
        if (UiTheme.flat()) {
            // 圆角细线做卡片边界：三列同为白底时，靠这条线把区域分开。
            return BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(UiTheme.line(), 1, true),
                    title, javax.swing.border.TitledBorder.LEADING,
                    javax.swing.border.TitledBorder.TOP,
                    UiTheme.ui(12, true), UiTheme.text3());
        }
        return BorderFactory.createTitledBorder(title);
    }

    /** 换风格后待重设的参数区分隔位置，见 {@link #doLayout()}。 */
    private boolean formSplitPending;

    @Override
    public void doLayout() {
        super.doLayout();
        if (formSplitPending && formSplit != null && formSplit.getHeight() > 0) {
            // 硬钉 0.62：这样无论"直接开这套风格"还是"从别的风格切过来"，
            // 走的都是同一条路径，位置自然一致。
            formSplit.setDividerLocation(0.62);
        // 上面那次调用多半是空操作：分栏这时候还没有尺寸。挂个监听，等它自己的尺寸
        // 定下来再摆。
        //
        // 关键是"不能摆一次就收手"：布局是分几步走的，分栏会先从 0 长到 509、
        // 再长到 535。按 509 摆出来的位置是 311，按 535 摆才是 327 —— 早摆一次
        // 就停在中间态上了。所以每次高度变化都重摆，直到高度不再变化为止。
        final JSplitPane pend = formSplit;
        formSplit.addComponentListener(new java.awt.event.ComponentAdapter() {
            private int lastH = -1;

            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (!formSplitPending || formSplit != pend || pend.getHeight() <= 0) {
                    return;
                }
                if (pend.getHeight() == lastH) {
                    formSplitPending = false;    // 高度稳住了，收手
                    return;
                }
                lastH = pend.getHeight();
                pend.setDividerLocation(0.62);
            }
        });
            formSplitPending = false;
        }
    }

    private void applyTheme() {
        // 这一层在新风格里是一张白卡片。给了 surface 角色之后，decorate() 会把这张
        // 卡片色一路传到最里面 —— 这正是第一次失败的地方。
        UiTheme.role(this, "surface");
        if (headerLabel != null) {
            UiTheme.role(headerLabel, "text");
            UiTheme.font(headerLabel, UiTheme.ui(20, true));
        }
        if (descArea != null) {
            UiTheme.role(descArea, "plain");
        }
        if (schemaStatus != null) {
            UiTheme.role(schemaStatus, "muted");
        }
        if (workdirLabel != null) {
            UiTheme.role(workdirLabel, "muted");
        }
        if (workdirField != null) {
            UiTheme.role(workdirField, "field");
        }
        if (optionSearchField != null) {
            UiTheme.role(optionSearchField, "field");
        }
        if (rawArgsArea != null) {
            UiTheme.role(rawArgsArea, "field");
        }
        UiTheme.applyRoles(this);
        UiTheme.buttons(this);
        UiTheme.decorate(this);
        // 参数区内部那条上下分隔只在 rebuild() 里设过一次 0.62；换风格会重建分隔条，
        // 位置被重算，不重设的话切回来时下面的内容会整体挪几个像素。
        // setDividerLocation(double) 在分栏还没有尺寸时会被丢掉，而换风格发生在布局
        // 之前。invokeLater 也不够：那一轮 EDT 事件里尺寸可能仍然没定下来。挂个标志，
        // 等 doLayout 真的带着尺寸跑过一次再设 —— 和"直接开经典"走的是同一条路。
        formSplitPending = true;
        if (optionScroll != null) {
            optionScroll.setBorder(optionsBorder());
        }
    }

    public SchemaFormPanel() {        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD,
                headerLabel.getFont().getSize() + 2f));
        descArea.setEditable(false);
        descArea.setLineWrap(true);
        descArea.setWrapStyleWord(true);
        descArea.setOpaque(false);
        descArea.setBorder(null);

        JPanel head = buildHead();
        add(head, BorderLayout.NORTH);

        add(formHolder, BorderLayout.CENTER);

        subcommandBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Ignore the changes that refilling the list makes on its own; only
                // a genuine choice should switch subcommands.
                if (subcommandListenMuted) {
                    return;
                }
                if (subcommandBox.getSelectedItem() == null) {
                    return;
                }
                String sel = subcommandBox.getSelectedItem().toString();
                // The top-level entry means no subcommand at all, which the rest of
                // the panel represents as an empty path.
                selectSubcommand(topLevelEntry().equals(sel) ? "" : sel);
            }
        });
        showTool(null);
        // Registered last, so the callback only ever runs against a fully built
        // panel. It rebuilds the form from the catalogue rather than patching
        // individual labels: almost every string here lives in a row that is
        // regenerated anyway, so re-running the same code path that built them is
        // both shorter and impossible to leave half-updated.
        I18n.addListener(languageRefresh);
        UiTheme.addListener(themeRefresh);
        applyTheme();
    }

    /**
     * Rebuilds the form's text when the language changes.
     *
     * <p>Runs on the EDT (I18n dispatches there), so touching Swing components is
     * safe.
     *
     * <p>Calls {@link #rebuild()} and <b>not</b> {@code showTool(tool)}: the latter
     * resets per-tool state, so using it here would silently discard what the user
     * had typed — the working directory, the raw-arguments box and every carried
     * field value — as a side effect of switching language. {@code rebuild()} only
     * redraws, and it re-reads each string from the catalogue on the way.
     */
    private final Runnable languageRefresh = new Runnable() {
        @Override
        public void run() {
            if (searchCaption != null) {
                searchCaption.setText(I18n.t(Strings.FORM_FIND));
            }
            if (optionSearchField != null) {
                optionSearchField.setToolTipText(I18n.t(Strings.FORM_SEARCH_TIP));
            }
            if (openWindowButton != null) {
                openWindowButton.setText(I18n.t(Strings.FORM_OPEN_WINDOW));
                openWindowButton.setToolTipText(I18n.t(Strings.FORM_OPEN_WINDOW_TIP));
            }
            if (runFromWindowButton != null) {
                runFromWindowButton.setText(I18n.t(Strings.FORM_RUN_THIS));
                runFromWindowButton.setToolTipText(I18n.t(Strings.FORM_RUN_THIS_TIP));
            }
            if (workdirLabel != null) {
                workdirLabel.setText(I18n.t(Strings.FORM_WORKDIR));
                workdirLabel.setToolTipText(I18n.t(Strings.FORM_WORKDIR_TIP));
            }
            if (makeWorkButton != null) {
                // 只换提示，不设文字：这个按钮是图标按钮（企鹅），
                // 一旦给它 setText，图标旁边就会多出一截标签，失去图标按钮的意义。
                makeWorkButton.setToolTipText(I18n.t(Strings.FORM_IN_WSL_TIP));
                // 无障碍名仍要跟着语言走，所以文字设成空串、把名字放在 accessible name 上。
                makeWorkButton.getAccessibleContext().setAccessibleName(
                        I18n.t(Strings.FORM_IN_WSL));
            }
            if (extraArgsLabel != null) {
                extraArgsLabel.setText(I18n.t(Strings.FORM_EXTRA_ARGS));
                extraArgsLabel.setToolTipText(I18n.t(Strings.FORM_EXTRA_ARGS_TIP));
            }
            if (optionsBorder != null) {
                optionsBorder.setTitle(I18n.t(Strings.FORM_OPTIONS_TITLE));
            }
            // Redraws the form rows, whose labels and tooltips come from the
            // catalogue. The current tool, subcommand and typed values are untouched.
            rebuild();
        }
    };

    /**
     * Unregisters the language listener.
     *
     * <p>Call when the panel is discarded. The listener list is static, so without
     * this every panel ever created stays registered and a language switch would
     * rebuild all of them.
     */
    public void dispose() {
        I18n.removeListener(languageRefresh);
    }

    public void setChangeListener(Runnable r) {
        if (r != null) {
            this.changeListener = r;
        }
    }

    /** Displays a tool, clearing per-tool state. */
    public void showTool(Tool t) {
        this.tool = t;
        this.schema = t == null ? null : t.schema;
        this.subPath = "";
        editors.clear();
        labels.clear();
        // The chosen kinds belong to the previous tool's options and mean nothing
        // for the new one. Restore any the user chose for this tool before.
        chosenKind.clear();
        carryText.clear();
        recallChosenKinds();
        rawArgsArea.setText("");
        workdirField.setText("");
        // A different tool is a different list, so the previous scroll position
        // would be meaningless. Within one tool it is kept.
        resetScroll = true;
        rebuild();
    }

    /** Switches to a subcommand (or the top level when {@code sub} is empty). */
    private void selectSubcommand(String sub) {
        this.subPath = sub == null ? "" : sub;
        editors.clear();
        labels.clear();
        // Nothing is carried between subcommands: the previous subcommand's typed
        // text belongs to its own options. Settings already chosen for the new one
        // are restored below.
        carryText.clear();
        if (tool == null) {
            return;
        }
        if (sub.isEmpty()) {
            this.schema = tool.schema;
            if (this.schema != null) {
                this.schema.path = "";
            }
            recallChosenKinds();
            rebuild();
            return;
        }
        Schema.Subcommand sc = tool.schema == null ? null : tool.schema.sub(sub);
        if (sc == null) {
            this.schema = null;
            rebuild();
            return;
        }
        // Learn this subcommand's options the first time it is opened. The
        // parent's help already told us the subcommand exists; only its own
        // options need probing (DESIGN.md P21).
        if (sc.schema == null && subcommandLoader != null) {
            loadingSubcommand = true;
            this.schema = null;
            rebuild();
            new javax.swing.SwingWorker<Schema, Void>() {
                @Override
                protected Schema doInBackground() {
                    return subcommandLoader.load(sc);
                }

                @Override
                protected void done() {
                    loadingSubcommand = false;
                    try {
                        schema = get();
                    } catch (Exception ignored) {
                        schema = null;
                    }
                    if (schema != null) {
                        schema.path = subPath;
                    }
                    // Restore this subcommand's own settings, which are kept apart
                    // from its siblings'.
                    recallChosenKinds();
                    rebuild();
                    fireChange();
                }
            }.execute();
            return;
        }
        this.schema = sc.schema;
        if (this.schema != null) {
            this.schema.path = subPath;
        }
        recallChosenKinds();
        rebuild();
    }

    /** Rebuilds the form; exposed so a test can drive the same path. */
    public void rebuildForTest() {
        rebuild();
    }

    /** True while a subcommand's help is being read. */
    private boolean loadingSubcommand;

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void rebuild() {
        // Everything the user has entered is read back first, so the new controls
        // start from it. A rebuild recreates every control, and one that is not given
        // its old value comes back at its default — which lost a ticked switch or a
        // typed value whenever the panel was rebuilt for an unrelated reason, such as
        // closing the options window before a run.
        captureCurrentValues();
        removeAll();

        JPanel head = buildHead();

        // The subcommand row sits between the command's own settings and the option
        // list, because that is what it selects: which set of options is shown below.
        // It is added here, once, so every branch has it — it used to be added only
        // on the path that found options, which meant it vanished while a subcommand
        // was loading or when one had nothing to show, exactly when the user wants
        // the way back.
        JPanel subRow = buildSubcommandRow();
        if (subRow.getComponentCount() > 0) {
            JPanel headPlus = new JPanel(new BorderLayout(4, 4));
            headPlus.add(head, BorderLayout.NORTH);
            headPlus.add(subRow, BorderLayout.SOUTH);
            add(headPlus, BorderLayout.NORTH);
        } else {
            add(head, BorderLayout.NORTH);
        }

        if (tool == null) {
            headerLabel.setText(I18n.t(Strings.FORM_NO_TOOL));
            descArea.setText(I18n.t(Strings.FORM_PICK_TOOL));
            schemaStatus.setText("");
            add(new JLabel(I18n.t(Strings.FORM_SELECT_TOOL)), BorderLayout.CENTER);
            applyTheme();
            revalidate();
            repaint();
            return;
        }

        headerLabel.setText(tool.command + (subPath.isEmpty() ? "" : "  " + subPath));

        if (loadingSubcommand) {
            descArea.setText(I18n.t(Strings.FORM_READING_SUB, subPath));
            // The trailing marker is part of the status line's own syntax, so it is
            // translated as a fragment alongside the counts below rather than glued
            // here; the command and environment names are data and stay verbatim.
            schemaStatus.setText(tool.command + " " + subPath
                    + "  @  " + tool.envName + "   \u2014   "
                    + I18n.t(Strings.FORM_STATUS_LOADING));
            add(new JLabel(I18n.t(Strings.FORM_LOADING)), BorderLayout.CENTER);
            // 提前返回也要刷主题。
            //
            // 这里的头块和占位标签是刚建出来的，还带着外观默认的浅色底；方法末尾那句
            // applyTheme() 走不到，于是"读取帮助"期间就出现半深半浅的样子，读完了才好。
            applyTheme();
            revalidate();
            repaint();
            return;
        }

        if (schema == null || (!schema.hasOptions() && schema.subcommands.isEmpty())) {
            // Nothing could be learned from the help. Entry falls back to raw
            // arguments rather than refusing to run the tool (DESIGN.md P6).
            //
            // The explanation distinguishes the two causes, because they are not
            // the same thing and the user can act on only one of them:
            //
            //   * the document has no usable structure — the help really does not
            //     describe its parameters, and there is nothing to fix;
            //   * the document looks structured but yielded no options — a
            //     limitation of the reading, worth reporting as such.
            boolean unstructured = schema == null || schema.format == null
                    || schema.format.isUnusable();
            if (unstructured) {
                descArea.setText(I18n.t(Strings.FORM_UNSTRUCTURED));
                schemaStatus.setText(tool.command + I18n.t(Strings.FORM_STATUS_AT)
                        + tool.envName + I18n.t(Strings.FORM_STATUS_SEP)
                        + I18n.t(Strings.FORM_STATUS_NO_OPTIONS));
            } else {
                descArea.setText(I18n.t(Strings.FORM_STRUCTURED_UNREADABLE,
                        schema.format == null
                                ? I18n.t(Strings.FORM_UNKNOWN)
                                : schema.format.describe()));
                schemaStatus.setText(tool.command + I18n.t(Strings.FORM_STATUS_AT)
                        + tool.envName + I18n.t(Strings.FORM_STATUS_SEP)
                        + I18n.t(Strings.FORM_STATUS_UNREADABLE));
            }
            add(buildFallbackPanel(), BorderLayout.CENTER);
            revalidate();
            repaint();
            fireChange();
            // 提前返回也要刷主题：这里新建的面板还带着外观默认的浅色底。
            applyTheme();
            return;
        }

        descArea.setText(describe(tool, schema));
        schemaStatus.setText(tool.command + "  @  " + tool.envName
                + I18n.t(Strings.FORM_STATUS_SEP)
                + I18n.t(Strings.FORM_STATUS_OPTIONS,
                        Integer.valueOf(schema.options.size()))
                + (subPath.isEmpty() ? ""
                        : I18n.t(Strings.FORM_STATUS_SUBCOMMAND, subPath)));

        // The option list and the extra-arguments box are stacked in a split
        // pane, so the user can decide how much height each deserves. Previously
        // they were BorderLayout CENTER and SOUTH, which gave the option list all
        // the spare room and left the extras at their content height, with no
        // boundary between them to drag (DESIGN.md P13).
        //
        // The scroll pane is reused across rebuilds and its position restored, so
        // stating a kind for a parameter does not send the user back to the top of
        // a long list. The scroll position belongs to the pane, so replacing the
        // pane would lose it; replacing only the view inside keeps it.
        final int scrollAt = (optionScroll == null || resetScroll)
                ? 0 : optionScroll.getVerticalScrollBar().getValue();
        resetScroll = false;
        if (optionScroll == null) {
            optionScroll = new JScrollPane();
            // The search field and the expand button sit in the scroll pane's column
            // header, so they stay put while the options scroll beneath them and no
            // vertical room is taken from the list.
            optionScroll.setColumnHeaderView(buildOptionToolbar());
            optionScroll.setBorder(optionsBorder());
            // The form is a plain JPanel, which does not implement Scrollable, so
            // a wheel notch moved it by the scroll pane's fallback increment of a
            // single pixel — effectively not at all for a dense form. Setting an
            // explicit unit increment makes one notch move by one row, which is
            // what a list-like area should feel like.
            optionScroll.getVerticalScrollBar().setUnitIncrement(ROW_HEIGHT);
            optionScroll.getVerticalScrollBar().setBlockIncrement(ROW_HEIGHT * 5);
            // Scrolling stays: with several dozen options and a short pane, the
            // ones below the fold would otherwise be unreachable.
            optionScroll.setMinimumSize(new Dimension(200, 60));
        } else {
            // Detach so the new form can be installed without the old one lingering.
            optionScroll.setViewportView(null);
        }
        optionScroll.setViewportView(buildForm());
        // A fresh form starts unfiltered, so the count reflects what is listed.
        if (optionSearchField != null) {
            applyOptionFilter();
        }
        // Restore the position after the layout pass. The scroll bar's maximum is
        // derived from the view's size, which is not known until the form has been
        // laid out; setting a value before that clamps it to zero and the position
        // is silently lost. Validating first, then setting, keeps it.
        if (scrollAt > 0) {
            optionScroll.validate();
            optionScroll.getVerticalScrollBar().setValue(scrollAt);
            javax.swing.SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    if (optionScroll != null) {
                        optionScroll.getVerticalScrollBar().setValue(scrollAt);
                    }
                }
            });
        }

        JComponent extras = buildRawArgsRow();
        extras.setMinimumSize(new Dimension(200, 60));

        // The option area lives inside a host of its own so the window can borrow it
        // and hand it back. Taking the scroll pane out of the split pane directly
        // would leave the split pane with a null component and no way to restore it
        // to the right slot.
        if (optionHost == null) {
            optionHost = new JPanel(new BorderLayout());
        }
        if (optionScroll.getParent() != optionHost) {
            optionHost.add(optionScroll, BorderLayout.CENTER);
        }

        // A split pane, so the user can decide how the height is shared. Neither part
        // folds any more: the extra-arguments box is where a parameter the help does
        // not describe is written, and folding it hid the one place to put such a
        // parameter.
        if (formSplit == null) {
            formSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, optionHost, extras);
            formSplit.setResizeWeight(0.62);
            formSplit.setDividerSize(6);
            formSplit.setBorder(null);
            // 分隔位置要等分栏自己有尺寸之后再摆，而且布局分几步走（先 0、再 509、最后
            // 535），早摆一次就会停在中间态上。所以每次高度变化都重摆，直到高度稳住。
            //
            // 注意：这个监听器只能挂一次。之前它被脚本插进了另一个监听器内部，结果每
            // resize 一次就再挂一个 —— 结构差异和那个几像素的位移都是这么来的。
            final JSplitPane pend = formSplit;
            formSplit.addComponentListener(new java.awt.event.ComponentAdapter() {
                private int lastH = -1;

                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    if (!formSplitPending || formSplit != pend || pend.getHeight() <= 0) {
            // 提前返回也要刷主题：这里新建的面板还带着外观默认的浅色底。
            applyTheme();
                        return;
                    }
                    if (pend.getHeight() == lastH) {
                        formSplitPending = false;      // 高度稳住了，收手
            // 提前返回也要刷主题：这里新建的面板还带着外观默认的浅色底。
            applyTheme();
                        return;
                    }
                    lastH = pend.getHeight();
                    pend.setDividerLocation(0.62);
                }
            });
        } else {
            // 这里必须把<b>上下两半都换掉</b>。
            //
            // 重建会造出全新的 optionHost 和 extras，原来只换了下半：上半还挂着上一轮
            // 的旧面板，于是"重建出来的结构"和"首次构建的结构"不是同一个 —— 子件顺序、
            // 分隔位置都会差一点，也就是切语言/切主题后那几像素位移的来源。
            formSplit.setTopComponent(optionHost);
            formSplit.setBottomComponent(extras);
        }

        JPanel content = new JPanel(new BorderLayout(4, 4));
        content.add(formSplit, BorderLayout.CENTER);

        add(content, BorderLayout.CENTER);
        // 走完整套刷新，不能只走 decorate()。
        //
        // 切中英文会整个重建表单（见 languageRefresh）。重建出来的控件是"生"的：
        // 只调 decorate() 的话，角色（参数名标签、说明文字、卡片底色）和按钮的扁平
        // 外观都不会被重新装上 —— 表现就是切一次语言，按钮退回原生、底色退回默认。
        applyTheme();
        // 每次重建都会换一条新的 formSplit。标志是给"这一条"挂的，重建后必须重挂，
        // 否则换风格时挂的那个标志早就被旧对象消耗掉了，新分隔条永远等不到摆位。
        formSplitPending = true;
        revalidate();
        repaint();
        fireChange();
    }



    private String describe(Tool tool, Schema s) {
        if (s.subcommands.size() >= 2 && s.subcommands.size() >= s.options.size()) {
            return I18n.t(Strings.FORM_DESC_SUBCOMMANDS);
        }
        return I18n.t(Strings.FORM_DESC_OPTIONS);
    }

    /**
     * The subcommand selector's entry for "no subcommand".
     *
     * <p>Written as words rather than a marker, because it is an entry in a list
     * the user reads: an earlier version put the internal token {@code @top} in the
     * dropdown, which told the user nothing and looked like a subcommand they did
     * not have.
     *
     * <p>The parentheses keep it from colliding with a real subcommand's name,
     * should one ever be called "top level".
     */
    /**
     * The subcommand box's entry meaning "no subcommand".
     *
     * <p>A method rather than a constant because the label is display text and must
     * follow the language. It stays safe to compare against because the comparison is
     * always made against this same method ({@code topLevelEntry().equals(sel)}), so
     * the sentinel and the label can never drift apart — which a translated literal
     * compared against a fixed constant would do silently.
     */
    private static String topLevelEntry() {
        return I18n.t(Strings.FORM_TOP_LEVEL);
    }

    /**
     * True while the subcommand list is being refilled.
     *
     * <p>Refilling changes the selection as a side effect, and the change listener
     * would otherwise read that as the user choosing something — clearing the active
     * subcommand in the middle of restoring it.
     */
    private boolean subcommandListenMuted;

    /** The command's own name, for messages that refer to it. */
    private String describeCommand() {
        return tool == null || tool.command == null || tool.command.isEmpty()
                ? I18n.t(Strings.FORM_THIS_COMMAND) : tool.command;
    }

    /** Subcommand selector, shown only for multi-level commands. */
    private JPanel buildSubcommandRow() {
        subcommandRow.removeAll();
        // The list of subcommands comes from the command's own help, which is known
        // before any subcommand is opened. Reading it from the *current* schema would
        // hide the whole row while a subcommand's options are being fetched, taking
        // with it the way back to the top level — exactly when the user has just
        // gone somewhere they may want to leave.
        Schema parent = tool == null ? null : tool.schema;
        if (parent == null || parent.subcommands.size() < 2) {
            return new JPanel();
        }
        // Named so it can be told apart from the per-option kind selectors, which
        // are also combo boxes.
        subcommandBox.setName("bioenv.subcommand");

        // Refilling the list changes the selection as a side effect: removing the
        // items clears it, and adding the first item selects that one. Both fire the
        // action listener, which would run selectSubcommand("") and wipe the very
        // subcommand this method is about to restore — so the panel bounced back to
        // the top level every time it was rebuilt. The listener is muted while the
        // list is rebuilt, and the selection is restored before it is unmuted.
        boolean wasMuted = subcommandListenMuted;
        subcommandListenMuted = true;
        try {
            subcommandBox.removeAllItems();
            subcommandBox.addItem(topLevelEntry());
            for (Schema.Subcommand sc : parent.subcommands) {
                subcommandBox.addItem(sc.name);
            }
            // Reflect the active subcommand without firing a redundant reload.
            if (!subPath.isEmpty()) {
                subcommandBox.setSelectedItem(subPath);
            } else {
                subcommandBox.setSelectedIndex(0);
            }
        } finally {
            subcommandListenMuted = wasMuted;
        }
        subcommandBox.setToolTipText(I18n.t(Strings.FORM_SUBCOMMAND_TIP, topLevelEntry())
                );
        subcommandRow.add(new JLabel(I18n.t(Strings.FORM_SUBCOMMAND)), BorderLayout.WEST);
        subcommandRow.add(subcommandBox, BorderLayout.CENTER);
        subcommandRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));

        JPanel wrap = new JPanel();
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
        wrap.add(subcommandRow);

        // The description of the chosen subcommand, so the user can see what it does
        // without opening a tooltip.
        if (!subPath.isEmpty()) {
            Schema.Subcommand sc = tool.schema.sub(subPath);
            if (sc != null && !sc.description.isEmpty()) {
                // Third-party help text: glossed by DescZh, not looked up in I18n.
                JLabel d = new JLabel(DescZh.translate(sc.description));
                d.setForeground(new Color(0x44, 0x44, 0x44));
                d.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
                wrap.add(d);
            }
        }

        // The way back sits beside the dropdown, on the same line. It was given a row
        // of its own for a while, which cost a row of height on every tool with
        // subcommands; sharing the line with the selector costs the selector some
        // width instead, and the selector's entries are short names.
        final JButton up = new JButton(I18n.t(Strings.FORM_BACK_TO_TOP));
        up.setMargin(new Insets(1, 8, 1, 8));
        up.setToolTipText(I18n.t(Strings.FORM_BACK_TO_TOP_TIP, describeCommand())
                );
        up.setEnabled(!subPath.isEmpty());
        up.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                selectSubcommand("");
            }
        });
        subcommandRow.add(up, BorderLayout.EAST);

        return wrap;
    }

    /** Fallback UI when no schema could be parsed. */
    private JPanel buildFallbackPanel() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.add(new JLabel(I18n.t(Strings.FORM_RAW_ARGS)), BorderLayout.NORTH);
        configureRawArgs();
        p.add(new JScrollPane(rawArgsArea), BorderLayout.CENTER);
        p.add(buildWorkdirRow(), BorderLayout.SOUTH);
        return p;
    }

    /** The generated option form. */
    private JPanel buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        optionForm = form;
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(2, 4, 2, 4);
        g.anchor = GridBagConstraints.WEST;
        int row = 0;
        formRows.clear();

        if (schema == null || schema.options.isEmpty()) {
            form.add(new JLabel(I18n.t(Strings.FORM_SUB_NO_OPTIONS)));
            return form;
        }

        String currentGroup = null;
        JLabel groupHeading = null;
        for (Schema.Option o : schema.options) {
            // Group headings, when the help used sections.
            String grp = o.group == null ? "" : o.group;
            if (!grp.equals(currentGroup == null ? "" : currentGroup)) {
                currentGroup = grp;
                if (!grp.isEmpty()) {
                    JLabel h = new JLabel(grp);
                    h.setFont(h.getFont().deriveFont(Font.BOLD));
                    h.setForeground(new Color(0x1A, 0x5F, 0xB4));
            UiTheme.role(h, "accent");
                    g.gridx = 0; g.gridy = row; g.gridwidth = 3;
                    g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL;
                    g.insets = new Insets(8, 4, 2, 4);
                    form.add(h, g);
                    g.gridwidth = 1;
                    g.insets = new Insets(2, 4, 2, 4);
                    row++;
                    groupHeading = h;
                }
            }

            // No marker for a mandatory option: which options are mandatory is the
        // program's determination, not the plugin's, so the plugin does not
        // display one (see Schema.Option).
        JLabel lab = new JLabel(o.label());
            UiTheme.role(lab, "chip");
            lab.setToolTipText(buildTooltip(o));
            labels.put(key(o), lab);

            g.gridx = 0; g.gridy = row; g.weightx = 0;
            g.fill = GridBagConstraints.NONE;
            form.add(lab, g);

            g.gridx = 1; g.weightx = 1;
        // 控件在这里建出来。这两行是被我误删的（改正则时把捕获组吃掉了）。
        JComponent editor = createEditor(o);
        editors.put(key(o), editor);
        // 勾选框不横向填满。
        //
        // 这一列是给输入框用的（要占满宽度），但勾选框跟着一起被拉伸，就成了
        // 一条长长的空框 —— 功能没问题，只是被布局拉变形了。
        if (editor instanceof JCheckBox) {
            g.fill = GridBagConstraints.NONE;
            g.anchor = GridBagConstraints.WEST;
        } else {
            g.fill = GridBagConstraints.HORIZONTAL;
        }
        form.add(editor, g);

            // The third column lets the user say what kind of value this is, and
            // the row then changes to suit. The plugin does not guess a kind, so
            // there is nothing to correct — only something to state, which is the
            // user's call to make once and keep.
            //
            // Once stated, the dropdown is replaced by a small button showing what
            // was chosen. The setting is settled at that point, and leaving a live
            // dropdown there would invite an accidental change; the button is how
            // the user revisits it, and removing the entry under "Parameter types"
            // brings the dropdown back.
            g.gridx = 2; g.weightx = 0; g.fill = GridBagConstraints.NONE;
            // The type chooser is not shown here. Every parameter's kind is stated
            // in the parameter editor, which is where the list as a whole is
            // corrected, and a column of dropdowns beside every row made the option
            // list read as a form to fill in rather than a list to scan. The kind is
            // still applied; only the control for changing it moved.
            form.add(new JPanel(), g);

            // The three components of one row, recorded together so a search can
            // show or hide the whole row rather than a stray cell of it.
            formRows.add(new FormRow(o, lab, editor, null, groupHeading));
            // The label carries its parameter's name, so the order the rows are in
            // can be read back — by a test, and by anything that needs to know what
            // the form is showing without re-deriving it from the schema.
            lab.putClientProperty(FLAG_PROPERTY, key(o));
            groupHeading = null;
            row++;
        }

        g.gridx = 0; g.gridy = row; g.gridwidth = 3;
        g.weighty = 1; g.fill = GridBagConstraints.BOTH;
        form.add(new JPanel(), g);
        return form;
    }

    /**
     * True when the user has stated a kind for this option.
     *
     * <p>A remembered choice counts, so a tool reopened later shows the settled
     * state rather than asking again.
     */
    private boolean isKindChosen(Schema.Option o) {
        return chosenKind.containsKey(scopedKey(o));
    }

    /**
     * The settled state of a row whose kind has been chosen: a small button naming
     * the kind, which reopens the choice when pressed.
     *
     * <p>Shown instead of the dropdown so the row reads as decided rather than as
     * still asking. Pressing it restores the dropdown, which is the same thing
     * that removing the entry under "Parameter types" does.
     */
    private JComponent buildKindBadge(final Schema.Option o) {
        final Schema.Option.Kind k = kindOf(o);
        JButton badge = new JButton(label(k));
        badge.setName(key(o));
        badge.setMargin(new Insets(0, 6, 0, 6));
        badge.setToolTipText(I18n.t(Strings.FORM_KIND_BADGE_TIP, label(k)));
        badge.setPreferredSize(new Dimension(118, badge.getPreferredSize().height));
        badge.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Drop the setting, which brings the dropdown back on the next
                // layout pass. Nothing else about the row changes.
                chosenKind.remove(scopedKey(o));
                if (correctionSink != null && tool != null) {
                    correctionSink.correct(scopeName(), key(o), null);
                }
                carryText.put(scopedKey(o), currentText(o));
                rebuild();
            }
        });
        return badge;
    }

    /**
     * Builds the control-kind chooser for one option.
     *
     * <p>A dropdown rather than a row of buttons: the kinds are mutually
     * exclusive, a dropdown reads as a single setting, and it keeps the column
     * narrow so the value field stays wide.
     */
    private JComponent buildKindChooser(final Schema.Option o) {
        final JComboBox<String> box = new JComboBox<String>();
        for (Schema.Option.Kind k : Schema.Option.Kind.values()) {
            box.addItem(label(k));
        }
        box.setName(key(o));
        box.setSelectedItem(label(kindOf(o)));
        box.setToolTipText(I18n.t(Strings.FORM_KIND_TIP));
        box.setPreferredSize(new Dimension(118, box.getPreferredSize().height));
        box.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Object sel = box.getSelectedItem();
                if (sel == null) {
                    return;
                }
                Schema.Option.Kind k = kindFor(String.valueOf(sel));
                if (k == null || k == kindOf(o)) {
                    return;
                }
                // Carry over whatever the user has already typed, so changing the
                // control never discards their input.
                String typed = currentText(o);
                chosenKind.put(scopedKey(o), k);
                if (correctionSink != null && tool != null) {
                    // Recorded against the tool *and* the subcommand, so the same
                    // option name in a sibling subcommand is unaffected.
                    correctionSink.correct(scopeName(), key(o), k);
                }
                carryText.put(scopedKey(o), typed);
                rebuild();
            }
        });
        return box;
    }

    /** What the chooser button offers, said in the button's tooltip. */
    private static String chooserHint(boolean directory, boolean multi, boolean save) {
        if (directory) {
            return I18n.t(Strings.FORM_CHOOSE_FOLDER);
        }
        if (multi) {
            return I18n.t(Strings.FORM_CHOOSE_FILES);
        }
        return save ? I18n.t(Strings.FORM_CHOOSE_SAVE)
                : I18n.t(Strings.FORM_CHOOSE_FILE);
    }

    /**
     * Joins chosen paths into the one argument the tool takes.
     *
     * <p>A comma and no space: the list is a single argument, and a space would split
     * it into two on the command line.
     */
    private static String joinPaths(java.util.List<java.io.File> files) {
        StringBuilder sb = new StringBuilder();
        for (java.io.File f : files) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(f.getAbsolutePath());
        }
        return sb.toString();
    }

    /**
     * A short label for a kind, shown in the chooser.
     *
     * <p><b>These strings are deliberately NOT translated.</b> They are a protocol
     * value, not prose, for three independent reasons — any one of which would be
     * enough:
     *
     * <ol>
     *   <li><b>They are a round-trip key.</b> {@link #kindFor(String)} recovers the
     *       kind with {@code label(k).equals(label)}. Translating this method means
     *       the two must be translated in lockstep forever; the day they diverge,
     *       the chooser silently stops recognising its own items and every
     *       correction falls back to "unknown kind".</li>
     *   <li><b>They are TBtools' own control names</b> (see {@code Schema.Kind}'s
     *       javadoc). A type chosen here is meant to read the same as the same
     *       control in TBtools; renaming it in one language would break that
     *       correspondence rather than translate it.</li>
     *   <li><b>The regression suite asserts the exact strings.</b>
     *       {@code KindChooserTest} and {@code KindCatalogueTest} compare against
     *       "CheckBox", "InFilePath", "OutFilePath", "number" and every other label.
     *       Translating them fails two shipped test files.</li>
     * </ol>
     *
     * <p>Persistence is unaffected either way: the cache and the correction store
     * write {@code Kind.name()}/{@code Kind.valueOf(...)}, not these labels.
     */
    public static String label(Schema.Option.Kind k) {
        // The TBtools names, so a choice made here reads the same as one made there.
        // The two the plugin adds are named plainly, since there is nothing to match.
        switch (k) {
            case NONE:
                return "None";
            case INPUT_PATH:
                return "InFilePath";
            case OUTPUT_PATH:
                return "OutFilePath";
            case PATH_LIST:
                return "FilePathList";
            case DIRECTORY_PATH:
                return "DirectoryPath";
            case TEXT:
                return "Text";
            case SWITCH:
                return "CheckBox";
            case CHOICE:
                return "Choice";
            case NUMBER:
                return "number";
            default:
                return "Text";
        }
    }

    /**
     * The kind a chooser label denotes, or null when it is not recognised.
     */
    public static Schema.Option.Kind kindFor(String label) {
        for (Schema.Option.Kind k : Schema.Option.Kind.values()) {
            if (label(k).equals(label)) {
                return k;
            }
        }
        return null;
    }

    /**
     * True when an option's inferred type is uncertain enough that the user
     * should be offered a way to correct it.
     *
     * <p>Certain means the help stated the type rather than leaving it to be
     * inferred: a metavariable such as {@code <int>}, a name following a known
     * convention such as {@code input_file}, or an enumerated candidate list.
     * In those cases the tool's own documentation settles the question.
     */
    private String buildTooltip(Schema.Option o) {
        // "<html>" and "</html>" are markup, not text, so they stay literal; the
        // plugin's own words in here come from the catalogue. The description is the
        // tool's own help text, so it goes through DescZh; the argument name, default
        // and range values are the tool's data and are appended verbatim.
        StringBuilder sb = new StringBuilder("<html>");
        if (o.description != null && !o.description.isEmpty()) {
            sb.append(escapeHtml(DescZh.translate(o.description)));
        } else if (o.fromUsageLine) {
            // Being honest about the source beats an empty tooltip that looks
            // like a parsing failure (DESIGN.md P18).
            sb.append("<i>").append(I18n.t(Strings.FORM_NO_DESCRIPTION)).append("</i>");
        }
        if (!o.arg.isEmpty()) {
            sb.append("<br><i>").append(I18n.t(Strings.FORM_DESC_ARG)).append("</i> ")
              .append(escapeHtml(o.arg));
        }
        if (o.defaultValue != null && !o.defaultValue.isEmpty()) {
            sb.append("<br><i>").append(I18n.t(Strings.FORM_DESC_DEFAULT)).append("</i> ")
              .append(escapeHtml(o.defaultValue));
        }
        if (o.min != null || o.max != null) {
            sb.append("<br><i>").append(I18n.t(Strings.FORM_DESC_RANGE)).append("</i> ")
              .append(o.min == null ? "?" : o.min)
              .append(" .. ").append(o.max == null ? "?" : o.max);
        }
        sb.append("</html>");
        return sb.toString();
    }

    /**
     * Creates the editor for one option, according to the kind the user chose.
     *
     * <p>Every option starts as a plain text field showing the help text's own
     * description as a grey hint. Nothing is pre-filled and nothing is inferred:
     * a wrong inference used to produce a control that could not accept the right
     * answer at all, so the plugin no longer makes that call.
     *
     * <p>What the user can do instead is state the kind themselves, and the row
     * then becomes the matching control — a file picker for a path, a spinner for
     * a number, a tick box for a switch. Carrying the typed text across means
     * changing the control never loses input.
     */
    private JComponent createEditor(final Schema.Option o) {
        // The hint is the tool's OWN words, taken from its help output — it is data
        // extracted from a third-party program, not this plugin's text. It is not put
        // through I18n for exactly that reason: there is no catalogue of keys that
        // could cover an arbitrary tool's help. It goes through DescZh instead, which
        // is a different kind of thing — not a key catalogue but a best-effort
        // glossary over an open-ended set. Entries it knows render in Chinese; the
        // rest stay as the tool wrote them. The reason it cannot be I18n is unchanged
        // either way, and that is why the flags and argument names beside it are left
        // completely alone: they are not English sentences to be translated.
        final String hint = DescZh.translate(
                o.description == null ? "" : o.description.trim());
        final String carried = carryText.remove(scopedKey(o));
        JComponent editor = buildControl(o, hint, carried);
        // Naming the editor by its key makes it findable — by the tests, and by
        // accessibility tooling that reads a form's structure. The key rather than
        // the flag, so two positionals are told apart.
        editor.setName(key(o));
        // A path row is a panel wrapping the field and its browse button, so the
        // field inside carries the name too. Otherwise the row would be visible
        // but the text in it unreachable.
        JTextField inner = findField(editor);
        if (inner != null) {
            inner.setName(key(o));
        }
        return editor;
    }

    private JComponent buildControl(final Schema.Option o, final String hint,
                                   final String carried) {
        switch (kindOf(o)) {
            case SWITCH: {
                JCheckBox cb = new JCheckBox();
                cb.setSelected("true".equalsIgnoreCase(carried));
                cb.setToolTipText(hint.isEmpty()
                        ? I18n.t(Strings.FORM_TICK_SWITCH) : hint);
                cb.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        fireChange();
                    }
                });
                return cb;
            }
            case NUMBER: {
                double init = 0;
                try {
                    init = Double.parseDouble(carried == null ? "" : carried.trim());
                } catch (Exception ignored) {
                    init = 0;
                }
                SpinnerNumberModel model = new SpinnerNumberModel(init, -1.0e9, 1.0e9, 1);
                final JSpinner sp = new JSpinner(model);
                // A spinner always holds a number, so its value alone says nothing
                // about intent. Recording the starting value lets an untouched
                // spinner contribute nothing (DESIGN.md P12).
                // 显示也按同一规则："0.###" 让 4.0 显示成 4，1.2 仍是 1.2。
            sp.setEditor(new javax.swing.JSpinner.NumberEditor(sp, "0.###"));
            sp.putClientProperty("bioenv.initialNumber", sp.getValue());
                sp.setToolTipText(hint);
                sp.addChangeListener(new javax.swing.event.ChangeListener() {
                    @Override
                    public void stateChanged(javax.swing.event.ChangeEvent e) {
                        fireChange();
                    }
                });
                return sp;
            }
            case INPUT_PATH:
            case OUTPUT_PATH:
            case PATH_LIST:
            case DIRECTORY_PATH: {
                // One shape for every path kind: a field the user can type in, with a
                // chooser beside it. They differ in what the chooser offers and how a
                // choice is written back.
                final JPanel row = new JPanel(new BorderLayout(4, 0));
                final JTextField field = new JTextField("", 20);
                HintText.install(field, hint);
                field.setToolTipText(hint);
                if (carried != null && !carried.isEmpty()) {
                    HintText.clear(field);
                    field.setText(carried);
                }
                field.getDocument().addDocumentListener(new DocListener());
                row.add(field, BorderLayout.CENTER);

                final Schema.Option.Kind kind = kindOf(o);
                // Acted on as soon as the value is entered, rather than when the field
                // is left. Leaving is easy to skip — type a path and go straight to
                // Run — and the run would then read across from Windows without
                // anyone noticing.
                //
                // Typing a path one character at a time means every prefix is seen.
                // Only a path that names something real is copied, so a half-typed
                // one is ignored rather than reported; see copyOnEnter.
                //
                // Only the kinds that name something to be read: an output is written,
                // not read, and copying it in would be pointless.
                if (isInputKind(kind)) {
                    field.getDocument().addDocumentListener(
                            new javax.swing.event.DocumentListener() {
                                @Override
                                public void insertUpdate(
                                        javax.swing.event.DocumentEvent e) {
                                    fireChange();
                                    copyOnEnter(o, field);
                                }

                                @Override
                                public void removeUpdate(
                                        javax.swing.event.DocumentEvent e) {
                                    fireChange();
                                }

                                @Override
                                public void changedUpdate(
                                        javax.swing.event.DocumentEvent e) {
                                    fireChange();
                                }
                            });
                } else {
                    field.getDocument().addDocumentListener(new DocListener());
                }
                // A directory is a directory; everything else is a file. The one
                // exception is the list, which picks several files at once.
                final boolean directory = kind == Schema.Option.Kind.DIRECTORY_PATH;
                final boolean multi = kind == Schema.Option.Kind.PATH_LIST;
                // Whether the chooser offers to create a name rather than open one.
                final boolean save = kind == Schema.Option.Kind.OUTPUT_PATH;

                JButton browse = new JButton("...");
                browse.setMargin(new Insets(0, 6, 0, 6));
                browse.setToolTipText(chooserHint(directory, multi, save));
                final boolean wantDirectory = directory;
                browse.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        // 先问"文件在哪一侧"。
                        //
                        // 原来这里直接开 Windows 的选择器：选中的本地文件会被上传到
                        // Linux 工作目录，参数再改写成远端路径。数据在本机时这是对的，
                        // 但数据<b>本来就在远端</b>时（远端刚跑完一步，结果就躺在那儿）
                        // 用户没法直接选它——只能先弄一份到本机再传回去。
                        //
                        // 所以多问一句。两边写入参数的形式都是"一条路径"：Windows 那条
                        // 照旧（后面 copyOnEnter 会把它传上去并改写），Linux 那条原样写入，
                        // 因为远端路径本来就该长那样，上传逻辑只认 Windows 路径会自动跳过。
                        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
                        javax.swing.JMenuItem win = new javax.swing.JMenuItem(
                                I18n.t(Strings.PICK_WINDOWS_FILE));
                        win.addActionListener(new ActionListener() {
                            @Override
                            public void actionPerformed(ActionEvent a) {
                                pickWindows(field, wantDirectory, multi, save);
                            }
                        });
                        javax.swing.JMenuItem lin = new javax.swing.JMenuItem(
                                I18n.t(Strings.PICK_LINUX_FILE));
                        lin.setEnabled(fileBrowser != null);
                        lin.addActionListener(new ActionListener() {
                            @Override
                            public void actionPerformed(ActionEvent a) {
                                pickLinux(field, wantDirectory, multi, save);
                            }
                        });
                        menu.add(win);
                        menu.add(lin);
                        menu.show(browse, 0, browse.getHeight());
                    }
                });
                row.add(browse, BorderLayout.EAST);
                return row;
            }
            case CHOICE: {
                // A dropdown only when there is something to choose from. A help that
                // named an option without listing its values leaves the user with a
                // choice of one: nothing. A field is the honest control then, and the
                // value they type is passed through as it always was.
                if (o.choices.isEmpty()) {
                    final JTextField field = new JTextField("", 26);
                    HintText.install(field, hint);
                    field.setToolTipText(hint.isEmpty()
                            ? I18n.t(Strings.FORM_NO_VALUES)
                            : hint);
                    if (carried != null && !carried.isEmpty()) {
                        HintText.clear(field);
                        field.setText(carried);
                    }
                    field.getDocument().addDocumentListener(new DocListener());
                    return field;
                }
                final JComboBox<String> box = new JComboBox<String>();
                box.setEditable(true);
                box.addItem("");
                for (String c : o.choices) {
                    box.addItem(c);
                }
                if (carried != null && !carried.isEmpty()) {
                    box.setSelectedItem(carried);
                }
                box.setToolTipText(hint);
                box.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        fireChange();
                    }
                });
                return box;
            }
            case NONE: {
                // No control at all. The parameter is not offered: the user has said
                // the tool does not want it, or the help invented it.
                final JLabel absent = new JLabel("\u2014");
                absent.setForeground(new Color(0x99, 0x99, 0x99));
                absent.setToolTipText(I18n.t(Strings.FORM_SET_TO_NONE));
                // Named so the row is still findable, and so what the user sees has
                // something behind it.
                absent.setName(key(o));
                return absent;
            }
            default: {
                // Text, and the starting point for every option.
                final JTextField field = new JTextField("", 26);
                HintText.install(field, hint);
                field.setToolTipText(hint);
                if (carried != null && !carried.isEmpty()) {
                    HintText.clear(field);
                    field.setText(carried);
                }
                field.getDocument().addDocumentListener(new DocListener());
                return field;
            }
        }
    }

    /**
     * Builds the row that sets the directory the command runs in.
     *
     * <p>Sits directly under the tool's own line rather than at the bottom of the
     * panel, because it applies to the whole command rather than to one option, and
     * the choice of directory is usually the first thing to get right. The browse
     * button matches the one on file parameters, so choosing a directory works the
     * same way as choosing a file.
     */
    private JPanel buildWorkdirRow() {
        JPanel p = new JPanel(new BorderLayout(4, 2));
        workdirLabel = new JLabel(I18n.t(Strings.FORM_WORKDIR));
        // The warning is the point of this row. A Windows path is reached through a
        // share between Windows and the virtual machine, and a tool that creates tens
        // of thousands of small files pays for every one of them; a path on the Linux
        // side is a local disk to the tool. Saying so where the setting is beats
        // discovering it from a run that takes an hour longer than it should.
        String hint = I18n.t(Strings.FORM_WORKDIR_TIP);
        workdirLabel.setToolTipText(hint);
        workdirField.setToolTipText(hint);

        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.add(workdirField, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.RIGHT, 4, 0));

        makeWorkButton = new JButton(PenguinMark.shared());
        makeWorkButton.setMargin(new Insets(1, 5, 1, 5));
        makeWorkButton.setName("bioenv.makeWorkdir");
        makeWorkButton.setToolTipText(I18n.t(Strings.FORM_IN_WSL_TIP));
        makeWorkButton.getAccessibleContext().setAccessibleName(
                I18n.t(Strings.FORM_IN_WSL));
        makeWorkButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // 原来是"自动建一个带时间戳的目录"。那既不让人看见远端长什么样，
                // 也没法选已有的目录 —— 现在改成打开浏览窗口（版式对齐 Windows 的
                // 「打开」窗口），用户能进能退、能新建、也能直接挑一个现成的。
                browseLinuxWorkdir();
            }
        });
        buttons.add(makeWorkButton);

        // No copy button: the files move themselves when a path is entered and a
        // working directory on the Linux side exists. A second button for something
        // that happens on its own is one more thing to remember.

        JButton browse = new JButton(WindowsMark.shared());
        browse.setMargin(new Insets(1, 5, 1, 5));
        browse.setName("bioenv.chooseWorkdir");
        chooseWorkdirButton = browse;
        browse.setToolTipText(I18n.t(Strings.FORM_CHOOSE_WORKDIR));
        browse.getAccessibleContext().setAccessibleName(
                I18n.t(Strings.FORM_CHOOSE_WORKDIR));
        browse.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                java.io.File start = null;
                String current = workdirField.getText().trim();
                if (!current.isEmpty()) {
                    start = new java.io.File(current);
                }
                java.io.File dir = FileDialogs.chooseDirectory(start,
                        SchemaFormPanel.this);
                if (dir != null) {
                    workdirField.setText(dir.getAbsolutePath());
                    fireChange();
                }
            }
        });
        buttons.add(browse);
        chooseWorkdirButton = browse;
        // 建好之后立刻按当前通道摆一次。setRemoteChannel 可能在按钮存在之前就被调用过
        // （宿主在构造早期设的），那时它只能记下标志、碰不到控件；所以这里要补一次，
        // 否则 SSH 下那个按钮照样显示出来。
        applyWorkdirButtonVisibility();
        row.add(buttons, BorderLayout.EAST);

        // 标签独立一行，输入框在下一行占满宽度：路径通常很长，和标签挤一行时
        // 能看到的字符太少。两个图标按钮留在输入框右端。
        p.add(workdirLabel, BorderLayout.NORTH);
        p.add(row, BorderLayout.CENTER);
        return p;
    }

    /** True when a kind names something to be read, so it is worth moving across. */
    private static boolean isInputKind(Schema.Option.Kind k) {
        return k == Schema.Option.Kind.INPUT_PATH
                || k == Schema.Option.Kind.PATH_LIST
                || k == Schema.Option.Kind.DIRECTORY_PATH;
    }

    /**
     * Copies a path across as soon as it is entered.
     *
     * <h2>Why on entering, not on leaving</h2>
     *
     * Leaving the field is easy to skip: type a path, press Run, and the run reads
     * across from Windows without anyone noticing. Acting on the value as it is typed
     * means there is no step to forget.
     *
     * <h2>Why every keystroke is harmless</h2>
     *
     * A path typed one character at a time passes through many prefixes, none of
     * which is a file. Only a path that names something that exists is copied;
     * anything else is ignored in silence, because reporting a half-typed path as a
     * failure would be noise. A path that has already been copied is not copied
     * again — same name, same size counts as already there.
     *
     * <p>Nothing is copied while a copy is in flight, and a copy that was made is
     * recorded so the field being rewritten does not set it off again.
     */
    private void copyOnEnter(final Schema.Option o, final JTextField field) {
        if (copyInFlight || rewritingField) {
            return;
        }
        String text = HintText.realText(field);
        if (text.isEmpty() || !WslBridge.isWindowsPath(firstPathOf(text))) {
            return;
        }
        final String target = wslInputDir();
        if (target == null) {
            // No working directory on the Linux side yet. Said once, and the path is
            // left exactly as typed — the plugin does not invent a destination.
            noteOnce("copydir", I18n.t(Strings.FORM_NOTE_COPYDIR));
            return;
        }
        if (inputCopier == null) {
            return;
        }

        // Only what is really there. A path being typed is not a path yet.
        final java.util.List<String> sources = new java.util.ArrayList<String>();
        java.util.List<String> pieces = new java.util.ArrayList<String>();
        if (kindOf(o) == Schema.Option.Kind.PATH_LIST) {
            for (String piece : text.split(",")) {
                String t = piece.trim();
                if (!t.isEmpty()) {
                    pieces.add(t);
                }
            }
        } else {
            pieces.add(text.trim());
        }
        for (String p : pieces) {
            if (WslBridge.isWindowsPath(p) && new java.io.File(p).exists()) {
                sources.add(p);
            }
        }
        if (sources.isEmpty()) {
            return;
        }
        // Already handled, so the same value does not start a second copy.
        String signature = target + "\u0000" + sources;
        if (copiedAlready.contains(signature)) {
            return;
        }
        copiedAlready.add(signature);

        copyInFlight = true;
        noteOnce("copying", null);
        new javax.swing.SwingWorker<java.util.List<String>, Void>() {
            @Override
            protected java.util.List<String> doInBackground() {
                return inputCopier.copyInto(sources, target);
            }

            @Override
            protected void done() {
                copyInFlight = false;
                java.util.List<String> copied;
                try {
                    copied = get();
                } catch (Exception e) {
                    copied = null;
                }
                if (copied == null || copied.size() != sources.size()) {
                    copiedAlready.clear();
                    if (statusSink != null) {
                        statusSink.accept(I18n.t(Strings.FORM_COPY_FAILED,
                                Integer.valueOf(sources.size())));
                    }
                    return;
                }
                StringBuilder sb = new StringBuilder();
                int ok = 0;
                for (int i = 0; i < sources.size(); i++) {
                    String to = copied.get(i);
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    if (to == null) {
                        sb.append(sources.get(i));
                    } else {
                        sb.append(to);
                        ok++;
                    }
                }
                // Rewritten under a flag, so setting the text does not look like the
                // user typing and start the whole thing again.
                rewritingField = true;
                try {
                    HintText.clear(field);
                    field.setText(sb.toString());
                } finally {
                    rewritingField = false;
                }
                fireChange();
                if (statusSink != null) {
                    statusSink.accept(ok == sources.size()
                            ? I18n.t(Strings.FORM_COPIED_INTO, target)
                            : I18n.t(Strings.FORM_COPIED_OF, Integer.valueOf(ok),
                                    Integer.valueOf(sources.size()))
                                    + I18n.t(Strings.FORM_COPY_REST_UNREADABLE));
                }
            }
        }.execute();
    }

    /** True while a copy is running, so typing does not start another. */
    private boolean copyInFlight;
    /** True while a field is being rewritten, so the change is not taken as typing. */
    private boolean rewritingField;
    /** Values already handled, so the same one does not start a second copy. */
    private final java.util.Set<String> copiedAlready =
            java.util.Collections.synchronizedSet(
                    new java.util.LinkedHashSet<String>());

    /** Says something once, and not again for the same key. */
    private void noteOnce(String key, String message) {
        if (!noted.add(key) || message == null || statusSink == null) {
            return;
        }
        statusSink.accept(message);
    }

    private final java.util.Set<String> noted =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());

    /** The first path in a possibly comma-separated value. */
    private static String firstPathOf(String text) {
        int comma = text.indexOf(',');
        return (comma < 0 ? text : text.substring(0, comma)).trim();
    }

    /** Runs the copy that entering a value would run, for a test. */
    public void copyOnEnterForTest(Schema.Option o, JTextField field) {
        copyOnEnter(o, field);
    }

    /** The directory inputs would be copied into, for a test. */
    public String wslInputDirForTest() {
        return wslInputDir();
    }

    /** The working-directory field, so a test can set it as the button would. */
    public JTextField workdirFieldForTest() {
        return workdirField;
    }

    /**
     * Where inputs go: a directory beside the working directory on the Linux side.
     *
     * <p>Null unless the working directory is a Linux path, which is what says the
     * user has decided to work over there. Kept beside the working directory so
     * everything one run reads and produces sits together, and one copy brings it all
     * back.
     */
    private String wslInputDir() {
        String wd = HintText.realText(workdirField);
        if (wd.isEmpty() || !wd.startsWith("/")) {
            return null;
        }
        return wd.endsWith("/") ? wd + "inputs" : wd + "/inputs";
    }
    /**
     * Makes a working directory on the Linux side and puts its path in the field.
     *
     * <p>Offered because the path is the one thing the user cannot work out for
     * themselves: the plugin knows the home directory, and the user would otherwise
     * have to open a terminal to find out what to type.
     */
    /**
     * 打开浏览窗口选一个 Linux 侧的目录，把选中的路径写进工作目录。
     *
     * <p>浏览窗口由宿主提供（见 {@link DirBrowser}），因为面板不该知道怎么连过去。
     * 窗口返回 null 表示取消——此时<b>保持原值不动</b>，而不是把框清空。
     */
    private void browseLinuxWorkdir() {
        if (dirBrowser == null) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    I18n.t(Strings.FORM_NO_WSL_FOR_WORKDIR));
            return;
        }
        String start = workdirField.getText().trim();
        String picked = dirBrowser.browse(currentTransport,
                SchemaFormPanel.this, start.isEmpty() ? null : start);
        if (picked != null && !picked.trim().isEmpty()) {
            workdirField.setText(picked.trim());
            fireChange();
        }
    }

    /**
     * 当前通道对象，由宿主通过 {@link #setTransport} 放进来。
     *
     * <p>类型是 {@code Object} 而不是 {@code bioenv.core.Transport}：这个面板与传输层
     * 之间只通过若干小接口打交道，不为一个参数把整个 core 依赖引进来。宿主知道它装的
     * 是什么。
     */
    public void setTransport(Object transport) {
        this.currentTransport = transport;
    }

    private Object currentTransport;

    /** SSH 下藏起"选本机目录"那个按钮；WSL 下才显示。 */
    private void applyWorkdirButtonVisibility() {
        if (chooseWorkdirButton != null) {
            chooseWorkdirButton.setVisible(!remoteChannel);
        }
    }

    private void makeLinuxWorkdir() {
        if (workdirMaker == null) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    I18n.t(Strings.FORM_NO_WSL_FOR_WORKDIR));
            return;
        }
        final String label = tool == null ? "work" : tool.command;
        setWorkdirBusy(true);
        new javax.swing.SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                // Off the event thread: starting an environment takes a moment, and
                // the panel must stay usable while it happens.
                return workdirMaker.make(label);
            }

            @Override
            protected void done() {
                setWorkdirBusy(false);
                String path;
                try {
                    path = get();
                } catch (Exception e) {
                    path = null;
                }
                if (path == null || path.isEmpty()) {
                    // 把原因一起说出来。只说「无法创建」的话，四种完全不同的
                    // 失败（连不上 / 命令非零退出 / 没有回显 / 回显的路径不可用）
                    // 长得一模一样，用户和排查的人都只能猜。
                    String why = "";
                    try {
                        String r = workdirMaker.lastFailure();
                        if (r != null && !r.trim().isEmpty()) {
                            why = "\n\n" + r;
                        }
                    } catch (Throwable ignored) {
                        // 拿不到原因也照样要报失败，不能因此再抛一个异常
                    }
                    javax.swing.JOptionPane.showMessageDialog(SchemaFormPanel.this,
                            I18n.t(Strings.FORM_WORKDIR_FAILED) + why);
                    return;
                }
                HintText.clear(workdirField);
                workdirField.setText(path);
                fireChange();
                if (statusSink != null) {
                    statusSink.accept(I18n.t(Strings.FORM_WORKDIR_SET, path));
                }
            }
        }.execute();
    }

    private void setWorkdirBusy(boolean busy) {
        for (Component c : getComponents()) {
            if (c instanceof Container) {
                setEnabledByName((Container) c, "bioenv.makeWorkdir", !busy);
            }
        }
    }

    private static void setEnabledByName(Container c, String name, boolean enabled) {
        for (Component ch : c.getComponents()) {
            if (name.equals(ch.getName())) {
                ch.setEnabled(enabled);
            }
            if (ch instanceof Container) {
                setEnabledByName((Container) ch, name, enabled);
            }
        }
    }

    /**
     * The parameters that name something to be read, with what they currently hold.
     *
     * <p>Found by kind rather than by guessing which one is "the" input: a tool may
     * take several, and the plugin does not decide what a tool requires. Every field
     * whose kind says it holds an input path is offered, and the ones holding nothing
     * are left out.
     *
     * <p>A list parameter is split, so each file in it is copied as its own entry.
     *
     * @return the option and the paths in it, in the order the rows appear
     */
    public java.util.List<InputField> inputFields() {
        java.util.List<InputField> out = new java.util.ArrayList<InputField>();
        if (schema == null) {
            return out;
        }
        for (Schema.Option o : schema.options) {
            // Positionals are included: a tool that reads its input by position
            // needs that file copied into WSL just as much as one that names it with
            // a flag, and skipping them left the file on the Windows side where the
            // tool could not open it.
            Schema.Option.Kind k = kindOf(o);
            if (k != Schema.Option.Kind.INPUT_PATH
                    && k != Schema.Option.Kind.PATH_LIST
                    && k != Schema.Option.Kind.DIRECTORY_PATH) {
                continue;
            }
            JComponent editor = editors.get(key(o));
            if (editor == null) {
                continue;
            }
            JTextField f = findField(editor);
            if (f == null) {
                continue;
            }
            String text = HintText.realText(f);
            if (text.isEmpty()) {
                continue;
            }
            java.util.List<String> paths = new java.util.ArrayList<String>();
            if (k == Schema.Option.Kind.PATH_LIST) {
                for (String piece : text.split(",")) {
                    String t = piece.trim();
                    if (!t.isEmpty()) {
                        paths.add(t);
                    }
                }
            } else {
                paths.add(text.trim());
            }
            out.add(new InputField(o, k, editor, f, paths));
        }
        return out;
    }

    /** One field holding input paths, and the paths in it. */
    public static final class InputField {
        public final Schema.Option option;
        public final Schema.Option.Kind kind;
        public final JComponent editor;
        public final JTextField field;
        public final java.util.List<String> paths;

        InputField(Schema.Option option, Schema.Option.Kind kind, JComponent editor,
                   JTextField field, java.util.List<String> paths) {
            this.option = option;
            this.kind = kind;
            this.editor = editor;
            this.field = field;
            this.paths = paths;
        }

        /** Puts a new value in the field, as though the user had typed it. */
        public void setText(String text) {
            HintText.clear(field);
            field.setText(text);
        }
    }

    /**
     * Makes a directory on the Linux side, so the panel need not know how WSL is
     * reached.
     */
    public interface WorkdirMaker {
        /** 建好的绝对路径；失败返回 null。 */
        String make(String label);

        /**
         * 上一次 {@link #make} 失败的原因；成功或没有原因时返回空串。
         *
         * <p>分开一个方法而不是让 {@code make} 返回错误对象：绝大多数调用只关心
         * 「成了没有」。但失败时界面必须能说出是<b>哪一种</b>失败——连不上、
         * 命令退出码非零、没有回显、回显的路径不可用，这四种的下一步动作完全不同。
         */
        String lastFailure();
    }

    public void setWorkdirMaker(WorkdirMaker maker) {
        this.workdirMaker = maker;
    }

    /**
     * 浏览 Linux 侧的目录，用来选工作目录。
     *
     * <p>为什么要有这个口子：工作目录是<b>远端</b> Linux 上的一条路径，而面板不该知道
     * 怎么连过去（WSL 还是 SSH）。WSL 与 SSH 都实现了 {@code Transport.runBashCapture}，
     * 所以宿主那边一套实现就够了。
     */
    public interface DirBrowser {
        /**
         * 弹出浏览窗口。
         *
         * @param transport 当前通道；宿主自己知道该给哪一个
         * @param parent    父窗口
         * @param startDir  初始目录
         * @return 用户选的绝对路径；取消返回 null
         */
        String browse(Object transport, java.awt.Component parent, String startDir);
    }

    public void setDirBrowser(DirBrowser browser) {
        this.dirBrowser = browser;
    }

    private DirBrowser dirBrowser;

    /**
     * 当前通道是否<b>不是本机</b>（也就是 SSH）。
     *
     * <p>面板据此决定要不要显示那个"选本机目录"的按钮：WSL 下本机与 Linux 之间有共享
     * （{@code /mnt/c}），选本机目录是有意义的；SSH 下远端看不到本机的 {@code C:\}，
     * 那个按钮只会把一条在本机才成立的路径塞进远端的工作目录。
     */
    public void setRemoteChannel(boolean remote) {
        this.remoteChannel = remote;
        applyWorkdirButtonVisibility();
    }

    private boolean remoteChannel;
    private JButton chooseWorkdirButton;

    private WorkdirMaker workdirMaker;

    /**
     * Copies paths into the Linux side, so the panel need not know how WSL is reached.
     */
    public interface InputCopier {
        /**
         * @param sources   paths to copy
         * @param targetDir directory on the Linux side to copy into
         * @return the new path for each source, an entry being null when it failed
         */
        java.util.List<String> copyInto(java.util.List<String> sources,
                                        String targetDir);
    }

    public void setInputCopier(InputCopier copier) {
        this.inputCopier = copier;
    }

    /**
     * 挑一个<b>已经在 Linux 侧</b>的文件，用来填参数。
     *
     * <p>和 {@link InputCopier} 是两件事：那个负责把本机文件传上去，这个负责<b>不传</b>
     * ——文件本来就在远端，直接挑它。面板不该知道怎么连过去，所以开成口子。
     */
    public interface FileBrowser {
        /**
         * @param transport 当前通道
         * @param startDir  初始目录，通常是设置好的工作目录
         * @param multi     允许多选
         * @param save      保存模式（输出参数）：选目录 + 填文件名
         * @return 选中的远端绝对路径；取消返回空表
         */
        java.util.List<String> browseFiles(Object transport, String startDir,
                                           boolean multi, boolean save);
    }

    public void setFileBrowser(FileBrowser browser) {
        this.fileBrowser = browser;
    }

    private FileBrowser fileBrowser;

    /** Windows 那一侧：本地选择器 + 之后的自动上传。 */
    private void pickWindows(JTextField field, boolean directory, boolean multi,
                             boolean save) {
        if (multi) {
            java.util.List<java.io.File> files =
                    FileDialogs.chooseMany(HintText.realText(field),
                            SchemaFormPanel.this);
            if (!files.isEmpty()) {
                HintText.clear(field);
                field.setText(joinPaths(files));
                fireChange();
            }
            return;
        }
        java.io.File f = FileDialogs.choose(HintText.realText(field),
                directory, save, SchemaFormPanel.this);
        if (f != null) {
            HintText.clear(field);
            field.setText(f.getAbsolutePath());
            fireChange();
        }
    }

    /**
     * Linux 那一侧：从远端已有的文件里挑。
     *
     * <p>写进去的是<b>远端路径</b>，不再经过上传——{@code copyOnEnter} 只认 Windows
     * 路径，会自动跳过它。这正是这里要的效果：文件已经在远端，不该再传一遍。
     */
    private void pickLinux(JTextField field, boolean directory, boolean multi,
                           boolean save) {
        if (fileBrowser == null) {
            return;
        }
        // 从哪开始找：工作目录是用户刚挑好的地方，东西多半就在那儿。
        //
        // 直接用工作目录那个框，不另存一份副本——它就是工作目录，多一份副本就多一个
        // 会跟主值不一致的地方。框里若是 Windows 路径（WSL 下允许），对远端没意义，
        // 那就退回主目录。
        String start = null;
        String wd = workdirField == null ? null : workdirField.getText();
        if (wd != null && !wd.trim().isEmpty()
                && !vmadapt.core.RemoteFiles.looksLikeWindowsPath(wd.trim())) {
            start = wd.trim();
        }
        String typed = HintText.realText(field);
        if (typed != null && !typed.trim().isEmpty()
                && !vmadapt.core.RemoteFiles.looksLikeWindowsPath(typed.trim())) {
            start = typed.trim();
        }
        java.util.List<String> picked = fileBrowser.browseFiles(currentTransport,
                start, multi, save && !directory);
        if (picked == null || picked.isEmpty()) {
            return;
        }
        HintText.clear(field);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < picked.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(picked.get(i));
        }
        field.setText(sb.toString());
        fireChange();
    }

    private InputCopier inputCopier;

    private JComponent buildRawArgsRow() {
        JPanel p = new JPanel(new BorderLayout(4, 2));
        extraArgsLabel = new JLabel(I18n.t(Strings.FORM_EXTRA_ARGS));
        extraArgsLabel.setToolTipText(I18n.t(Strings.FORM_EXTRA_ARGS_TIP));
        p.add(extraArgsLabel, BorderLayout.NORTH);
        configureRawArgs();
        p.add(new JScrollPane(rawArgsArea), BorderLayout.CENTER);
        return p;
    }

    private void configureRawArgs() {
        // Attach once: the panel is rebuilt on every tool and subcommand
        // change, and re-attaching would fire duplicate change events.
        if (!rawArgsListenerAttached) {
            rawArgsArea.getDocument().addDocumentListener(new DocListener());
            rawArgsListenerAttached = true;
        }
        rawArgsArea.setLineWrap(true);
        rawArgsArea.setWrapStyleWord(true);
        rawArgsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        rawArgsArea.setRows(2);
    }

    // ------------------------------------------------------------------
    // Data access
    // ------------------------------------------------------------------

    /** Key under which an option's value is stored: its flag, or its label. */
    private static String key(Schema.Option o) {
        return o == null ? "" : o.key();
    }

    /** Current values, keyed by {@link bioenv.core.CommandBuilder#key}. */
    public Map<String, bioenv.core.ParamValue> collect() {
        Map<String, bioenv.core.ParamValue> values =
                new LinkedHashMap<String, bioenv.core.ParamValue>();
        if (schema != null) {
            for (Schema.Option o : schema.options) {
                JComponent editor = editors.get(key(o));
                if (editor == null) {
                    continue;
                }
                // The kind chosen here is written back onto the option before the
                // value is read. The choice of control lives in this panel, but
                // whether the option takes a value is a fact about the option, and
                // the command builder decides from that fact whether to write
                // "-flag" or "-flag value". Without this, setting an option to
                // "switch" gave a tick box whose tick was written as the literal
                // word "true" after the flag.
                applyChosenKind(o);
                bioenv.core.ParamValue pv = new bioenv.core.ParamValue(o);
                readInto(pv, kindOf(o), editor, o);
                values.put(key(o), pv);
            }
        }
        // The working directory is only meaningful when the user set it.
        bioenv.core.ParamValue wd = new bioenv.core.ParamValue(null);
        String wdText = HintText.realText(workdirField);
        if (!wdText.isEmpty()) {
            wd.set(wdText);
        }
        values.put("__workdir__", wd);
        return values;
    }

    /**
     * Applies the outcome of the parameter editor.
     *
     * <p>Everything the editor changed is applied here, on the schema this panel is
     * actually showing, rather than by the caller reaching for a schema of its own.
     * The panel is the only thing that knows what it has loaded, and a caller
     * holding a different object — the tool's schema, or one from the cache — would
     * otherwise change a list nothing is displaying.
     *
     * @param kinds every parameter's kind as the editor left it
     */
    /**
     * Forgets the types remembered for the tool on show.
     *
     * <p>Called when the editor's "Reset this tool" is used. The remembered types
     * live here rather than in the editor, so the editor cannot clear them itself,
     * and a reset that left them would put the parameter list back while the
     * controls kept the types the user had set.
     *
     * @return the number of remembered choices dropped
     */
    public int forgetRememberedKinds() {
        int dropped = 0;
        if (corrections != null) {
            dropped = corrections.forgetCommand(tool == null ? "" : tool.command);
        }
        chosenKind.clear();
        return dropped;
    }

    public void applyEditorResult(java.util.Map<String, Schema.Option.Kind> kinds) {
        if (schema == null) {
            return;
        }
        // The schema is rebuilt from the help it was parsed from, with the edits laid
        // over the result, so hiding and reordering start from the documented list
        // rather than from whatever a previous edit left behind.
        schema.reapplyEdits(tool == null ? "" : tool.command, corrections, customOptions);

        if (kinds != null) {
            for (Schema.Option o : schema.options) {
                Schema.Option.Kind k = kinds.get(key(o));
                if (k == null) {
                    continue;
                }
                chosenKind.put(scopedKey(o), k);
                if (corrections != null) {
                    corrections.remember(scopeName(), key(o), k);
                }
            }
        }
        // The rows are rebuilt from the schema that was just changed, so what is on
        // screen is what the store now says.
        rebuild();
    }

    /** The correction store, so a type chosen in the editor is remembered. */
    public void setCorrections(bioenv.core.CorrectionStore store) {
        this.corrections = store;
    }

    /**
     * The key this panel's tool and subcommand are stored under.
     *
     * <p>The tool name alone is not enough: a tool's subcommands routinely share
     * parameter names, so an edit made for one must not reach another.
     */
    public String currentScopeKey() {
        return scopeName();
    }

    /**
     * Every subcommand of the tool being shown.
     *
     * <p>Used when a tool is read again: a subcommand keeps the schema it was read
     * with, so those have to be discarded too or an edit would not reach them.
     */
    public java.util.List<Schema.Subcommand> allSubcommands() {
        java.util.List<Schema.Subcommand> out =
                new java.util.ArrayList<Schema.Subcommand>();
        if (tool != null && tool.schema != null) {
            out.addAll(tool.schema.subcommands);
        }
        return out;
    }

    private bioenv.core.CorrectionStore corrections;

    /** The client property a row's label carries, naming its parameter. */
    public static final String FLAG_PROPERTY = "bioenv.flag";

    /** The parameters the form is showing, in the order the rows appear. */
    public java.util.List<String> shownFlags() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (FormRow r : formRows) {
            out.add(r.option.flag);
        }
        return out;
    }

    /**
     * Makes the option agree with the control the user is looking at.
     *
     * <p>The command builder decides from the option's own shape whether to write
     * {@code -flag} or {@code -flag value}, and it cannot see the form. So before a
     * value is read, the shape is brought into line with the control that will
     * supply it.
     *
     * <p>Only where the two actually disagree in a way that matters. An option the
     * help gave a type is shown with the control that type calls for — a SWITCH is
     * a tick box, a NUMBER is a spinner — and each of those still takes no value
     * from a text field, so it must keep the shape the parser gave it. What has to
     * be reconciled is the case where the option takes no value but is shown as a
     * text field: typing into it must then mean something, so it stops being a
     * switch and the typed text becomes its value.
     */
    private void applyChosenKind(Schema.Option o) {
        Schema.Option.Kind chosen = chosenKind.get(scopedKey(o));
        if (chosen != null) {
            // The user stated a kind for this parameter; that is the answer.
            o.kind = chosen;
            o.isSwitch = chosen == Schema.Option.Kind.SWITCH;
            return;
        }
        // No statement from the user. Reconcile only when the option is a bare flag
        // that is NOT presented as a tick box.
        //
        // The control decides, because the value is read from the control: a tick
        // box reports its tick as the text "true" (see readInto), and that text must
        // not end up on the command line after the flag. An inferred SWITCH is shown
        // as a tick box by kindOf(), so it keeps isSwitch=true and CommandBuilder
        // writes the bare flag. Downgrading it to TEXT here — which this used to do
        // unconditionally, on the now-stale assumption that a bare flag is always
        // shown as a text field — turned a ticked --reverse into "--reverse true",
        // and seqkit would then read "true" as an input filename.
        if (o.kind == Schema.Option.Kind.SWITCH && o.isSwitch
                && !(editors.get(key(o)) instanceof JCheckBox)) {
            o.kind = Schema.Option.Kind.TEXT;
            o.isSwitch = false;
        }
    }

    /**
     * Reads one control into a {@link bioenv.core.ParamValue}.
     *
     * <p>Nothing starts filled in, so whatever a control holds was put there by the
     * user and belongs on the command line. The one exception is a spinner, whose
     * value it is impossible to leave blank; it therefore records its starting
     * number and stays silent until that number changes (DESIGN.md P12).
     */
    /** 数值转字符串：去掉无意义的小数零尾巴（6.0 → "6"，1.2 → "1.2"）。 */
    private static String cleanNumber(Object v) {
        if (!(v instanceof Number)) {
            return String.valueOf(v);
        }
        try {
            return new java.math.BigDecimal(v.toString())
                    .stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return String.valueOf(v);
        }
    }

    private static void readInto(bioenv.core.ParamValue pv,
                                 Schema.Option.Kind kind,
                                 JComponent editor, Schema.Option o) {
        if (editor instanceof JCheckBox) {
            if (((JCheckBox) editor).isSelected()) {
                pv.set("true");
            }
            return;
        }
        if (editor instanceof JSpinner) {
            JSpinner sp = (JSpinner) editor;
            Object v = sp.getValue();
            if (v == null) {
                return;
            }
            Object initial = sp.getClientProperty("bioenv.initialNumber");
            if (initial != null && String.valueOf(initial).equals(String.valueOf(v))) {
                return;
            }
            // 整数值不留小数点尾巴：6.0 → "6"，1.2 保持 "1.2"。
            //
            // 这里是命令真正的取值处；上面那个 currentText 只管跨重建传递文本，
            // 改它命令不会变。
            pv.set(cleanNumber(v));
            return;
        }
        if (editor instanceof JComboBox) {
            // Unreachable today: a parameter's editor is a tick box, a spinner or a
            // text field, and the only combo boxes in the panel are the subcommand
            // selector and the kind chooser, neither of which is a parameter. Left
            // in as the reading for a dropdown, so that adding such a control does
            // not silently collect nothing.
            Object sel = ((JComboBox<?>) editor).getSelectedItem();
            if (sel != null && !sel.toString().isEmpty()) {
                pv.set(sel.toString());
            }
            return;
        }
        JTextField f = findField(editor);
        if (f == null) {
            return;
        }
        // Hint text is guidance, not input.
        String text = HintText.realText(f);
        if (!text.isEmpty()) {
            pv.set(text);
        }
    }

    private static JTextField findField(JComponent c) {
        if (c == null) {
            return null;
        }
        if (c instanceof JTextField) {
            return (JTextField) c;
        }
        if (c instanceof JPanel) {
            for (Component child : ((JPanel) c).getComponents()) {
                JTextField f = findField((JComponent) child);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    /** Free-form arguments. */
    public String rawArgs() {
        return rawArgsArea.getText();
    }

    /** Active schema, or null. */
    public Schema currentSchema() {
        return schema;
    }

    /** Active subcommand path, or empty. */
    public String currentSubPath() {
        return subPath;
    }

    /**
     * Clears any validation highlighting from a previous run.
     *
     * <p>The plugin no longer decides which options are mandatory, so it no
     * longer marks any as missing. It does still clear the highlight it used to
     * apply, so that a form restored from an older session does not keep a stale
     * red label.
     */
    public void clearValidation() {
        for (JLabel lab : labels.values()) {
            if (lab != null) {
                lab.setForeground(null);
            }
        }
    }

    /**
     * The help text this command's form was built from, as the tool printed it.
     *
     * <p>Read by the tool assistant, which shows it on its first tab. Kept here
     * because this panel is what holds the schema the form was made from.
     *
     * <p>The viewer that used to live here is gone: showing the text and letting
     * the user write their own are the same task seen twice, so both moved into
     * one window. Nothing about the text itself changed.
     */
    public String rawHelpText() {
        return schema == null || schema.rawHelp == null ? "" : schema.rawHelp;
    }

    private void fireChange() {
        changeListener.run();
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Forwards document edits to the change callback. */
    private final class DocListener implements javax.swing.event.DocumentListener {
        @Override
        public void insertUpdate(javax.swing.event.DocumentEvent e) {
            fireChange();
        }

        @Override
        public void removeUpdate(javax.swing.event.DocumentEvent e) {
            fireChange();
        }

        @Override
        public void changedUpdate(javax.swing.event.DocumentEvent e) {
            fireChange();
        }
    }

    // ------------------------------------------------------------------
    // 图标
    // ------------------------------------------------------------------

    /**
     * 画出来的图标基类。
     *
     * <p>为什么不用图片文件：插件是单个 jar，多一个资源就多一处可能在打包或
     * 加载时出问题的地方（路径大小写、打包遗漏、无头环境取不到）。
     * 这两个图案都简单到可以直接画出来，画出来还顺带解决了高分屏缩放。
     */
    private abstract static class DrawnIcon implements javax.swing.Icon {
        private final int size;

        DrawnIcon(int size) {
            this.size = size;
        }

        @Override
        public final int getIconWidth() {
            return size;
        }

        @Override
        public final int getIconHeight() {
            return size;
        }

        @Override
        public final void paintIcon(java.awt.Component c, java.awt.Graphics g,
                                    int x, int y) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try {
                g2.setRenderingHint(
                        java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                paint(g2, x, y, size);
            } finally {
                g2.dispose();
            }
        }

        abstract void paint(java.awt.Graphics2D g, int x, int y, int size);
    }

    /** Windows 徽标：四个方块。 */
    private static final class WindowsMark extends DrawnIcon {
        private static final WindowsMark INSTANCE = new WindowsMark();

        private WindowsMark() {
            super(16);
        }

        static WindowsMark shared() {
            return INSTANCE;
        }

        @Override
        void paint(java.awt.Graphics2D g, int x, int y, int size) {
            // 四个方块，中间留一道缝——就是大家都认得的那面旗。
            g.setColor(new java.awt.Color(0x00A4EF));
            int gap = 1;
            int cell = (size - gap) / 2 - 1;
            int ox = x + 1;
            int oy = y + (size - (cell * 2 + gap)) / 2;
            g.fillRect(ox, oy, cell, cell);
            g.fillRect(ox + cell + gap, oy, cell, cell);
            g.fillRect(ox, oy + cell + gap, cell, cell);
            g.fillRect(ox + cell + gap, oy + cell + gap, cell, cell);
        }
    }

    /**
     * Linux 企鹅（Tux）。
     *
     * <p>用打包进来的 {@code tux.png}。母图是 128×128，绘制时按<b>屏幕缩放</b>重采样：
     * 位图图标被系统放大时会糊（这台机器就是 2 倍缩放，16 逻辑像素要画成 32 个物理
     * 像素），所以这里不是把一张 16×16 的图拉大，而是每次都从母图重新出一张与物理
     * 像素 1:1 的图。逻辑尺寸仍然是 16×16，按钮高度和布局都不受影响。
     *
     * <p>母图入库前做过一次「小尺寸优化」：提对比、提饱和、锐化，再量化到 40 色。
     * 原图是带渐变和阴影的写实渲染，直接缩到 16px 会糊成一团；量化之后轮廓更硬，
     * 小尺寸下才认得出是企鹅。
     *
     * <p>万一图片没打进去，退回旧的手绘版本：按钮不能变成空白，回归测试也要求这个
     * 图标里同时有橙色和黑色。
     */
    private static final class PenguinMark implements javax.swing.Icon {
        /** 逻辑尺寸：和 {@link WindowsMark} 一致，两个按钮才一样高。 */
        private static final int SIZE = 16;

        private static final PenguinMark INSTANCE = new PenguinMark();

        private final java.awt.image.BufferedImage master;

        /** 按物理像素烘好的图；同一档只烘一次。 */
        private java.awt.Image baked;
        private int bakedFor = -1;

        private PenguinMark() {
            java.net.URL url = PenguinMark.class.getResource("tux.png");
            java.awt.image.BufferedImage img = null;
            if (url != null) {
                try {
                    img = javax.imageio.ImageIO.read(url);
                } catch (java.io.IOException ignored) {
                    img = null;          // 读不出来就走手绘兜底
                }
            }
            master = img;
        }

        static PenguinMark shared() {
            return INSTANCE;
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public void paintIcon(java.awt.Component c, java.awt.Graphics g,
                              int x, int y) {
            if (master == null) {
                java.awt.Graphics2D fallback = (java.awt.Graphics2D) g.create();
                try {
                    fallback.setRenderingHint(
                            java.awt.RenderingHints.KEY_ANTIALIASING,
                            java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                    paintFallback(fallback, x, y);
                } finally {
                    fallback.dispose();
                }
                return;
            }
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try {
                g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                        java.awt.RenderingHints.VALUE_RENDER_QUALITY);
                // 画进 SIZE×SIZE 的逻辑矩形；外面那层变换会把它放大到设备像素，
                // 而这张图本身就是按那个尺寸出的 —— 1:1，所以不糊。
                g2.drawImage(imageFor(deviceScale(g)), x, y, SIZE, SIZE, null);
            } finally {
                g2.dispose();
            }
        }

        /** 当前绘制环境的缩放倍数（屏幕 2 倍就是 2.0；取不到就算 1）。 */
        private static double deviceScale(java.awt.Graphics g) {
            if (!(g instanceof java.awt.Graphics2D)) {
                return 1.0;
            }
            java.awt.geom.AffineTransform t =
                    ((java.awt.Graphics2D) g).getTransform();
            if (t == null) {
                return 1.0;
            }
            double sx = Math.abs(t.getScaleX());
            return sx > 0.01 ? sx : 1.0;
        }

        /** 取（必要时先烘）物理像素尺寸的那张图。 */
        private synchronized java.awt.Image imageFor(double scale) {
            int px = Math.max(SIZE, (int) Math.round(SIZE * scale));
            if (baked == null || bakedFor != px) {
                java.awt.image.BufferedImage out =
                        new java.awt.image.BufferedImage(px, px,
                                java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = out.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                        java.awt.RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(master, 0, 0, px, px, null);
                g.dispose();
                baked = out;
                bakedFor = px;
            }
            return baked;
        }

        /** 图片缺失时的兜底：旧的手绘企鹅。 */
        private static void paintFallback(java.awt.Graphics2D g, int x, int y) {
            java.awt.Color orange = new java.awt.Color(0xF5A623);
            g.setColor(orange);
            g.fillOval(x + 3, y + 13, 4, 3);
            g.fillOval(x + 9, y + 13, 4, 3);
            g.setColor(java.awt.Color.BLACK);
            g.fillOval(x + 2, y + 1, 12, 14);
            g.setColor(java.awt.Color.WHITE);
            g.fillOval(x + 5, y + 6, 6, 8);
            g.fillOval(x + 3, y + 3, 4, 4);
            g.fillOval(x + 9, y + 3, 4, 4);
            g.setColor(java.awt.Color.BLACK);
            g.fillOval(x + 4, y + 4, 2, 2);
            g.fillOval(x + 10, y + 4, 2, 2);
            g.setColor(orange);
            g.fillPolygon(new int[]{x + 6, x + 10, x + 8},
                    new int[]{y + 7, y + 7, y + 10}, 3);
        }
    }
}
