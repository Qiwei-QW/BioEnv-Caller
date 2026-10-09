package bioenv.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;

import bioenv.core.ToolNotesStore;
import bioenv.i18n.I18n;
import bioenv.i18n.Strings;

/**
 * The "Tool assistant" window: the tool's own help on one tab, and the user's
 * own notes on another.
 *
 * <h2>Two tabs, two different kinds of text</h2>
 *
 * <p>The first tab is what the tool printed, exactly as it came, read-only. It is
 * evidence about the tool, and the point of it is that nothing in this plugin can
 * alter it - including when the parser could make nothing of it, which is when it
 * matters most.
 *
 * <p>The second tab is the user's own writing: what a parameter really means, a
 * command line that worked, a trap. It is editable and it is saved per command.
 *
 * <p><b>The notes are never parsed.</b> They do not reach the parser, the schema,
 * or the parameter form. That is a design decision rather than an omission, and
 * {@link ToolNotesStore} records the reasoning. The tab says so in words as well,
 * because a user who wrote a parameter list and did not see the form change would
 * otherwise reasonably conclude the plugin was broken.
 *
 * <p>Laid out to be testable without a window: this is a {@link JPanel}, so a
 * headless test can build it, switch tabs and read both areas. The frame around
 * it is the caller's business.
 */
public final class ToolAssistantPanel extends JPanel {

    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextArea helpArea = new JTextArea();
    private final JTextArea notesArea = new JTextArea();

    private final JLabel helpHint = new JLabel();
    private final JLabel notesHint = new JLabel();
    private final JLabel notesNote = new JLabel();

    private final JButton clearButton = new JButton();
    private final JButton saveButton = new JButton();
    private final JButton cancelButton = new JButton();

    private final String command;
    private final String scope;
    private final ToolNotesStore store;
    /**
     * What the store held when this window opened, so an unsaved edit is
     * detectable.
     *
     * <p>Not final: a save makes the text now in the area the new baseline.
     * Leaving it at the opening value made {@link #changed()} stay true after a
     * successful save, which would report unsaved work that was already written.
     */
    private String loaded;

    private Runnable onSave;
    private Runnable onCancel;

    /**
     * The language refresh, held so it can be unregistered.
     *
     * <p>The listener list is static, so a panel that registered one and was never
     * disposed of would keep refreshing forever - once per window the user ever
     * opened. {@link #dispose()} is what the caller must run when the window goes.
     */
    private final Runnable languageRefresh = new Runnable() {
        @Override
        public void run() {
            applyText();
        }
    };

    /**
     * @param command the tool this window is about, used in titles
     * @param rawHelp the help text as the tool printed it; may be empty
     * @param scope   the key the notes are stored under, e.g. {@code "trimal"}
     * @param store   where notes are kept
     */
    public ToolAssistantPanel(String command, String rawHelp,
                              String scope, ToolNotesStore store) {
        this.command = command == null ? "" : command;
        this.scope = scope == null ? "" : scope;
        this.store = store;
        this.loaded = store == null ? "" : store.get(this.scope);

        setLayout(new BorderLayout(0, 0));
        setName("bioenv.toolAssistant");

        tabs.setName("bioenv.assistantTabs");
        tabs.addTab("", buildHelpTab(rawHelp));
        tabs.addTab("", buildNotesTab());
        add(tabs, BorderLayout.CENTER);
        add(buildButtons(), BorderLayout.SOUTH);

        applyText();
        I18n.addListener(languageRefresh);
    }

    // ------------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------------

    private JPanel buildHelpTab(String rawHelp) {
        JPanel page = new JPanel(new BorderLayout(0, 6));
        page.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        helpHint.setForeground(new java.awt.Color(0x6C, 0x72, 0x7C));
        page.add(helpHint, BorderLayout.NORTH);

        helpArea.setName("bioenv.assistantHelp");
        helpArea.setEditable(false);
        // Monospaced and unwrapped: help is laid out in columns, and re-wrapping it
        // would destroy the alignment that makes the option list readable.
        helpArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        helpArea.setLineWrap(false);
        helpArea.setBackground(new java.awt.Color(0xF7, 0xF8, 0xFA));
        helpArea.setText(rawHelp == null ? "" : rawHelp);
        // The synopsis is at the top, so that is where to look first.
        helpArea.setCaretPosition(0);

        JScrollPane scroll = new JScrollPane(helpArea);
        scroll.setPreferredSize(new Dimension(760, 420));
        page.add(scroll, BorderLayout.CENTER);
        return page;
    }

    private JPanel buildNotesTab() {
        JPanel page = new JPanel(new BorderLayout(0, 6));
        page.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        notesHint.setForeground(new java.awt.Color(0x6C, 0x72, 0x7C));
        page.add(notesHint, BorderLayout.NORTH);

        notesArea.setName("bioenv.assistantNotes");
        notesArea.setEditable(true);
        notesArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        notesArea.setLineWrap(true);
        notesArea.setWrapStyleWord(true);
        notesArea.setText(loaded);
        notesArea.setCaretPosition(0);
        // The hint above the area changes once there is something to describe, so
        // it has to follow the text. Refreshing it only on a language switch left
        // it inviting the user to write while they were already writing.
        notesArea.getDocument().addDocumentListener(
                new javax.swing.event.DocumentListener() {
                    @Override
                    public void insertUpdate(javax.swing.event.DocumentEvent e) {
                        updateNotesHint();
                    }

                    @Override
                    public void removeUpdate(javax.swing.event.DocumentEvent e) {
                        updateNotesHint();
                    }

                    @Override
                    public void changedUpdate(javax.swing.event.DocumentEvent e) {
                        updateNotesHint();
                    }
                });

        JScrollPane scroll = new JScrollPane(notesArea);
        scroll.setPreferredSize(new Dimension(760, 380));
        page.add(scroll, BorderLayout.CENTER);

        JPanel foot = new JPanel(new BorderLayout(8, 0));
        // Stated in the window, not only in a doc comment: a user who writes a
        // parameter list here and sees the form unchanged has to be told why.
        notesNote.setForeground(new java.awt.Color(0x6C, 0x72, 0x7C));
        foot.add(notesNote, BorderLayout.CENTER);
        clearButton.setName("bioenv.assistantClear");
        clearButton.setMargin(new Insets(2, 10, 2, 10));
        clearButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                notesArea.setText("");
                notesArea.requestFocusInWindow();
            }
        });
        foot.add(clearButton, BorderLayout.EAST);
        page.add(foot, BorderLayout.SOUTH);
        return page;
    }

    private JPanel buildButtons() {
        JPanel bar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 8));
        bar.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));

        cancelButton.setName("bioenv.assistantCancel");
        cancelButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (onCancel != null) {
                    onCancel.run();
                }
            }
        });

        saveButton.setName("bioenv.assistantSave");
        saveButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                save();
                if (onSave != null) {
                    onSave.run();
                }
            }
        });

        bar.add(cancelButton);
        bar.add(saveButton);
        return bar;
    }

    // ------------------------------------------------------------------
    // Behaviour
    // ------------------------------------------------------------------

    /**
     * Writes the notes area to the store. Returns true when the stored text
     * changed.
     *
     * <p>The saved text becomes the new baseline whether or not the store had
     * anything to write: saving an unedited note is not a pending change either.
     */
    public boolean save() {
        if (store == null) {
            return false;
        }
        String now = notesArea.getText() == null ? "" : notesArea.getText();
        boolean wrote = store.set(scope, now);
        loaded = now;
        return wrote;
    }

    /** The text currently in the notes area. */
    public String notes() {
        return notesArea.getText();
    }

    /** True when the notes differ from what the store held at open. */
    public boolean changed() {
        String now = notesArea.getText() == null ? "" : notesArea.getText();
        return !now.equals(loaded == null ? "" : loaded);
    }

    /** The scope these notes belong to. */
    public String scope() {
        return scope;
    }

    /** The tabs, so a test can count them and switch without the frame. */
    public JTabbedPane tabs() {
        return tabs;
    }

    /** The read-only help area. */
    public JTextArea helpArea() {
        return helpArea;
    }

    /** The editable notes area. */
    public JTextArea notesArea() {
        return notesArea;
    }

    public void setOnSave(Runnable r) {
        this.onSave = r;
    }

    public void setOnCancel(Runnable r) {
        this.onCancel = r;
    }

    /** Unregisters the language listener. The caller must run this on close. */
    public void dispose() {
        I18n.removeListener(languageRefresh);
    }

    /** Puts the current language into every label and tab. */
    public void applyText() {
        setTabTitle(0, I18n.t(Strings.ASSISTANT_TAB_HELP));
        setTabTitle(1, I18n.t(Strings.ASSISTANT_TAB_NOTES));
        helpHint.setText(I18n.t(Strings.ASSISTANT_HELP_HINT));
        updateNotesHint();
        notesNote.setText(I18n.t(Strings.ASSISTANT_NOTES_NOTE));
        clearButton.setText(I18n.t(Strings.ASSISTANT_CLEAR));
        saveButton.setText(I18n.t(Strings.ASSISTANT_SAVE));
        cancelButton.setText(I18n.t(Strings.ASSISTANT_CANCEL));
    }

    /**
     * The hint above the notes area.
     *
     * <p>An empty area gets an invitation with examples of what belongs there; one
     * that already has text gets the shorter description, because the examples
     * have served their purpose.
     */
    private void updateNotesHint() {
        notesHint.setText(notesArea.getText().trim().isEmpty()
                ? I18n.t(Strings.ASSISTANT_NOTES_EMPTY)
                : I18n.t(Strings.ASSISTANT_NOTES_HINT));
    }

    private void setTabTitle(int index, String title) {
        if (index >= 0 && index < tabs.getTabCount()) {
            tabs.setTitleAt(index, title);
        }
    }
}
