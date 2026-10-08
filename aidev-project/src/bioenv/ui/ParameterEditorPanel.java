package bioenv.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import bioenv.core.CustomOptionStore;
import bioenv.core.Schema;
import bioenv.i18n.I18n;
import bioenv.i18n.Strings;

/**
 * The panel for correcting a tool's parameter list.
 *
 * <h2>What it is for</h2>
 *
 * A tool's help is often wrong in ways the reader can see but the parser cannot
 * know: an option appears only in the manual, a family is listed with one example,
 * a line of prose is read as a parameter that does not exist. The form is only as
 * good as the help it was built from, so the user needs a way to say what the help
 * got wrong.
 *
 * <p>Everything the form can express about the list is editable here — which
 * parameters exist, what type each is, and the order they appear in — and nothing is
 * written back until OK is pressed. Cancel leaves the tool exactly as it was, which
 * matters because a mistyped parameter is otherwise indistinguishable from a
 * considered one.
 *
 * <h2>Why it is a separate class</h2>
 *
 * It grew out of a list of stored type corrections, which was a small dialog. It is
 * now a full editor with its own state, its own undo-on-cancel rule and its own
 * ordering rules; keeping it inside the main panel would have put all of that in the
 * same class as the tool tree, the command preview and the run buttons.
 */
public final class ParameterEditorPanel extends JPanel {

    /** One row: a parameter, its kind, and whether the user added or removed it. */
    private static final class Row {
        /**
         * The parameter's key, as {@link Schema.Option#key()} defines it.
         *
         * <p>A flag for a named parameter; {@code @} plus the label for one given by
         * position. Row identity everywhere in this panel — component names, the
         * rename record, the stored edits — is this key, so it has to match what the
         * schema will produce when the edits are read back.
         */
        String flag;
        /**
         * What the name field shows.
         *
         * <p>Differs from {@link #flag} only for a positional: the user writes
         * {@code <input>} there, which is the label, while the key carries the
         * {@code @} marker that keeps it apart from a flag.
         */
        String display;
        String description;
        Schema.Option.Kind kind;
        /** True when the user added this parameter, so it can be removed outright. */
        boolean added;
        /**
         * True when this parameter should be gone from the list.
         *
         * <p>Applied on OK rather than at once, so Cancel is a real escape. A
         * documented parameter is hidden rather than deleted, which is what makes it
         * restorable.
         */
        boolean removed;
        /**
         * True when this parameter was deleted for good rather than set aside.
         *
         * <p>Both kinds of removal live in the store; the difference is whether the
         * row offers a way back. "Restore removed" skips these, which is what makes
         * the delete final without the help text having to be rewritten to achieve
         * it.
         */
        boolean purged;

        Row(String flag, String display, String description,
            Schema.Option.Kind kind, boolean added) {
            this.flag = flag;
            this.display = display;
            this.description = description;
            this.kind = kind;
            this.added = added;
        }

        /**
         * True when a typed name denotes an argument given by position.
         *
         * <p>Angle brackets are how a user says "this one has no flag". They are the
         * convention every help text already uses for a placeholder, so the panel
         * reads the same notation rather than inventing a second one.
         */
        static boolean isPositional(String typed) {
            String t = typed == null ? "" : typed.trim();
            return t.length() > 2 && t.startsWith("<") && t.endsWith(">");
        }

        /** The key a typed name would be stored under. */
        static String keyOf(String typed) {
            String t = typed == null ? "" : typed.trim();
            return isPositional(t) ? ("@" + t) : t;
        }
    }

    private final List<Row> rows = new ArrayList<Row>();
    private final CustomOptionStore store;
    private final String scope;
    /** The kinds as they were when the panel opened, for a type-only correction. */
    private final Map<String, Schema.Option.Kind> originalKinds =
            new LinkedHashMap<String, Schema.Option.Kind>();
    private final Map<String, Schema.Option> originalOptions =
            new LinkedHashMap<String, Schema.Option>();

    private final JPanel listPanel = new JPanel();
    private final JLabel summary = new JLabel();
    private final JButton addButton = new JButton(I18n.t(Strings.PED_ADD));
    private final JButton restoreButton = new JButton("Restore removed");
    private final JButton resetButton = new JButton(I18n.t(Strings.PED_RESET));

    /** The row being dragged, or null. */
    private Row dragRow;
    private boolean accepted;
    /** Set while the list is being rebuilt, so row listeners do not fire. */
    private boolean rebuilding;

    /**
     * @param schema the parameters as the form currently shows them
     * @param scope  the tool and subcommand the edits belong to
     * @param store  where the edits are kept
     */
    public ParameterEditorPanel(Schema schema, String scope, CustomOptionStore store) {
        super(new BorderLayout(6, 6));
        this.store = store;
        this.scope = scope == null ? "" : scope;

        for (Schema.Option o : schema.options) {
            // A positional is shown by its label, because that is the only thing it
            // has; its key carries the marker that keeps it distinct.
            String display = o.flag.isEmpty()
                    ? (o.arg.isEmpty() ? I18n.t(Strings.PED_ARG_PLACEHOLDER) : o.arg)
                    : o.flag;
            Row r = new Row(o.key(), display, o.description, o.kind, o.userAdded);
            rows.add(r);
            originalKinds.put(o.key(), o.kind);
            originalOptions.put(o.key(), o);
        }

        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(buildHeader(), BorderLayout.NORTH);
        add(buildList(), BorderLayout.CENTER);
        add(buildButtons(), BorderLayout.SOUTH);
        refresh();
        // Registered last so every component the callback touches already exists.
        // The callback runs on the EDT (I18n dispatches there), so plain setText is
        // correct. Row labels are rebuilt by refresh(), which re-derives its text
        // from the catalogue.
        I18n.addListener(languageRefresh);
    }

    /**
     * Re-labels the panel's own chrome when the language changes.
     *
     * <p>Held as a field so it can be unregistered in {@link #dispose()}: an
     * anonymous listener could not be referenced and would stay registered for the
     * life of the JVM, once per editor window ever opened.
     *
     * <p>The intro paragraph is rebuilt from {@code scope} rather than cached, so it
     * stays correct if the panel is reused for another tool.
     */
    private final Runnable languageRefresh = new Runnable() {
        @Override
        public void run() {
            addButton.setText(I18n.t(Strings.PED_ADD));
            addButton.setToolTipText(I18n.t(Strings.PED_ADD_TIP));
            resetButton.setText(I18n.t(Strings.PED_RESET));
            resetButton.setToolTipText(I18n.t(Strings.PED_RESET_TIP));
            if (okButton != null) {
                okButton.setText(I18n.t(Strings.PED_OK));
            }
            if (cancelButton != null) {
                cancelButton.setText(I18n.t(Strings.PED_CANCEL));
            }
            if (introLabel != null) {
                introLabel.setText(I18n.t(Strings.PED_INTRO, escape(scope)));
            }
            if (listBorder != null) {
                listBorder.setTitle(I18n.t(Strings.PED_LIST_TITLE));
            }
            // Rows carry translated text (the removed mark, the Keep button), and
            // refresh() rebuilds them from the catalogue.
            refresh();
        }
    };

    /**
     * Unregisters the language listener.
     *
     * <p>Call when the editor is closed. Not calling it is not an immediate error,
     * but the static listener list would grow by one per opened editor and each
     * stale entry would still run on every language switch.
     */
    public void dispose() {
        I18n.removeListener(languageRefresh);
    }

    /** The intro paragraph; a field so a language switch can rewrite it. */
    private JLabel introLabel;
    /** The list's titled border; a field because a TitledBorder is not a component. */
    private javax.swing.border.TitledBorder listBorder;
    /** The OK / Cancel buttons; fields so a language switch can relabel them. */
    private JButton okButton;
    private JButton cancelButton;

    private JComponentHeader buildHeader() {
        JComponentHeader p = new JComponentHeader();
        // The scope is passed as an argument, not spliced into the text: it is a
        // command name and must read identically in both languages.
        introLabel = new JLabel(I18n.t(Strings.PED_INTRO, escape(scope)));
        p.add(introLabel, BorderLayout.NORTH);

        addButton.setMargin(new Insets(2, 10, 2, 10));
        addButton.setToolTipText(I18n.t(Strings.PED_ADD_TIP));
        addButton.setName("bioenv.editor.add");
        addButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                promptForParameter();
            }
        });

        // "Restore removed" is gone. Bringing back everything that had been set aside
        // was a second way to do what "Reset this tool" does, and the row's own Keep
        // button already covers the one case worth undoing immediately.

        resetButton.setMargin(new Insets(2, 10, 2, 10));
        resetButton.setToolTipText(I18n.t(Strings.PED_RESET_TIP));
        resetButton.setName("bioenv.editor.reset");
        resetButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                resetEverything();
            }
        });

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.add(addButton);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(resetButton);
        p.add(bar, BorderLayout.SOUTH);
        return p;
    }

    private JScrollPane buildList() {
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        JScrollPane sp = new JScrollPane(listPanel);
        listBorder = BorderFactory.createTitledBorder(I18n.t(Strings.PED_LIST_TITLE));
        sp.setBorder(listBorder);
        sp.getVerticalScrollBar().setUnitIncrement(24);
        sp.setPreferredSize(new Dimension(660, 380));
        return sp;
    }

    private JPanel buildButtons() {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        summary.setForeground(new java.awt.Color(0x66, 0x66, 0x66));
        p.add(summary, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        okButton = new JButton(I18n.t(Strings.PED_OK));
        okButton.setName("bioenv.editor.ok");
        okButton.setMargin(new Insets(3, 16, 3, 16));
        okButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                accepted = true;
                closeDialog();
                // The caller is told here rather than being asked afterwards.
                // Showing a frame does not block — unlike a modal dialog, which is
                // what this used to be — so there is no point after setVisible at
                // which the answer is known. A caller that checked afterwards read
                // "not accepted" and did nothing, and the press came later with
                // nobody listening.
                if (acceptHandler != null) {
                    acceptHandler.accepted(ParameterEditorPanel.this);
                }
            }
        });
        cancelButton = new JButton(I18n.t(Strings.PED_CANCEL));
        cancelButton.setName("bioenv.editor.cancel");
        cancelButton.setMargin(new Insets(3, 16, 3, 16));
        cancelButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                closeDialog();
            }
        });
        right.add(okButton);
        right.add(cancelButton);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    /** True when the user pressed OK rather than Cancel or the close box. */
    public boolean isAccepted() {
        return accepted;
    }

    /**
     * What to do when OK is pressed.
     *
     * <p>Run from the button's own callback, not by the caller after the window is
     * shown: showing a frame returns immediately, so a caller that checked
     * {@link #isAccepted()} afterwards would always be told no.
     */
    public interface AcceptHandler {
        void accepted(ParameterEditorPanel editor);
    }

    private AcceptHandler acceptHandler;

    public void setAcceptHandler(AcceptHandler h) {
        this.acceptHandler = h;
    }

    /**
     * The window this panel is shown in.
     *
     * <p>Set before the window is made visible. The panel offers a button that fills
     * the screen with it, and only the window knows whether it is currently
     * maximised.
     */
    public void setHostWindow(java.awt.Window w) {
        this.hostWindow = w;
    }

    private java.awt.Window hostWindow;

    /** Fills the screen with this panel's window, or restores it. */
    public void toggleFullScreen() {
        if (!(hostWindow instanceof java.awt.Frame)) {
            return;
        }
        java.awt.Frame f = (java.awt.Frame) hostWindow;
        if ((f.getExtendedState() & java.awt.Frame.MAXIMIZED_BOTH) != 0) {
            f.setExtendedState(java.awt.Frame.NORMAL);
        } else {
            f.setExtendedState(java.awt.Frame.MAXIMIZED_BOTH);
        }
    }

    private void closeDialog() {
        java.awt.Window w = SwingUtilities.getWindowAncestor(this);
        if (w != null) {
            w.setVisible(false);
            w.dispose();
        }
    }

    // ------------------------------------------------------------------
    // Building the rows
    // ------------------------------------------------------------------

    /** Rebuilds every row from the current state. */
    private void refresh() {
        rebuilding = true;
        try {
            listPanel.removeAll();
            rowComponents.clear();
            for (int i = 0; i < rows.size(); i++) {
                Component c = buildRow(rows.get(i), i);
                if (c == null) {
                    // A parameter deleted for good is not shown at all. It stays in
                    // the list so the change is still recorded on OK — dropping it
                    // here is what once left the store entry behind.
                    continue;
                }
                rowComponents.put(rows.get(i).flag, c);
                listPanel.add(c);
                listPanel.add(Box.createVerticalStrut(2));
            }
            listPanel.add(Box.createVerticalGlue());
        } finally {
            rebuilding = false;
        }
        updateSummary();
        listPanel.revalidate();
        listPanel.repaint();
    }

    private JComponentHeader buildRow(final Row row, final int index) {
        JComponentHeader p = new JComponentHeader();
        p.setName("bioenv.editor.row." + row.flag);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(1, 2, 1, 2),
                BorderFactory.createMatteBorder(0, 0, 1, 0,
                        new java.awt.Color(0xE0, 0xE0, 0xE0))));

        if (row.removed) {
            // A parameter set aside with the × stays visible and struck through, so
            // the removal can be seen and undone from the row itself.
            //
            // One deleted for good is not shown at all: that is what was asked for,
            // and leaving a struck-through husk of it on screen made a final action
            // look like a pending one.
            if (row.purged) {
                return null;
            }
            JLabel gone = new JLabel(I18n.t(Strings.PED_REMOVED_LABEL, escape(row.display)));
            gone.setForeground(new java.awt.Color(0x99, 0x33, 0x33));
            p.add(gone, BorderLayout.CENTER);
            JButton undo = new JButton(I18n.t(Strings.PED_KEEP));
            undo.setMargin(new Insets(0, 8, 0, 8));
            undo.setName("bioenv.editor.keep." + row.flag);
            undo.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    row.removed = false;
                    refresh();
                }
            });
            p.add(undo, BorderLayout.EAST);
            return p;
        }

        // The grip. Dragging it, or the row, moves the parameter in the list.
        JLabel grip = new JLabel("\u2261");
        grip.setToolTipText(I18n.t(Strings.PED_DRAG_TIP));
        grip.setForeground(new java.awt.Color(0x88, 0x88, 0x88));
        grip.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 6));
        p.add(grip, BorderLayout.WEST);

        JPanel middle = new JPanel(new BorderLayout(6, 0));
        // The name is editable, not a label. A help can be misread — a family of
        // options listed with one example, or ambiguous columns — and the correction
        // is a rename. The reader can see the name is wrong; this is how they fix it.
        final JTextField name = new JTextField(row.display);
        name.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        name.setPreferredSize(new Dimension(140, 22));
        name.setToolTipText(I18n.t(Strings.PED_NAME_TIP));
        name.setName("bioenv.editor.name." + row.flag);
        name.getDocument().addDocumentListener(new SimpleDocListener() {
            @Override
            public void changed() {
                if (!rebuilding) {
                    renameRow(row, name.getText().trim());
                }
            }
        });
        middle.add(name, BorderLayout.WEST);

        JTextField desc = new JTextField(row.description == null ? "" : row.description);
        desc.setToolTipText(I18n.t(Strings.PED_DESC_TIP));
        desc.setName("bioenv.editor.desc." + row.flag);
        desc.getDocument().addDocumentListener(new SimpleDocListener() {
            @Override
            public void changed() {
                if (!rebuilding) {
                    row.description = desc.getText();
                    // Recorded so it can be written into the help on OK. A parameter
                    // read from the help is described by the help on every rebuild, so
                    // a correction kept only here would be replaced by the old text.
                    String root = originalOf(row.flag);
                    if (originalOptions.get(root) != null) {
                        descriptionEdits.put(root, row.description);
                    }
                }
            }
        });
        middle.add(desc, BorderLayout.CENTER);
        p.add(middle, BorderLayout.CENTER);

        JPanel tail = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        final JComboBox<String> kind = new JComboBox<String>();
        kind.setName("bioenv.editor.kind." + row.flag);
        for (Schema.Option.Kind k : Schema.Option.Kind.values()) {
            kind.addItem(SchemaFormPanel.label(k));
        }
        kind.setSelectedItem(SchemaFormPanel.label(row.kind));
        kind.setToolTipText(I18n.t(Strings.PED_KIND_TIP));
        kind.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (rebuilding) {
                    return;
                }
                Schema.Option.Kind k = SchemaFormPanel.kindFor(
                        String.valueOf(kind.getSelectedItem()));
                if (k != null) {
                    row.kind = k;
                    updateSummary();
                }
            }
        });
        tail.add(kind);

        JButton remove = new JButton("\u00D7");
        remove.setMargin(new Insets(0, 8, 0, 8));
        remove.setName("bioenv.editor.remove." + row.flag);
        remove.setToolTipText(row.added
                ? I18n.t(Strings.PED_DELETE_ADDED_TIP)
                : I18n.t(Strings.PED_REMOVE_TIP));
        remove.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                row.removed = true;
                refresh();
            }
        });
        tail.add(remove);

        // Deletion, as against removal. The cross takes the parameter out of the list
        // for now; this takes it out of the help the list is built from, so it does
        // not come back on the next edit, the next restart, or the next read of the
        // cache. Offered because a help can be wrong in a way that is not worth
        // working around on every visit.
        if (row.flag != null && !row.flag.isEmpty()) {
            JButton purge = new JButton("\u2716");
            purge.setMargin(new Insets(0, 6, 0, 6));
            purge.setName("bioenv.editor.purge." + row.flag);
            purge.setForeground(new java.awt.Color(0x99, 0x22, 0x22));
            purge.setToolTipText(I18n.t(Strings.PED_PURGE_TIP, row.flag));
            purge.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    confirmAndPurge(row);
                }
            });
            tail.add(purge);
        }
        p.add(tail, BorderLayout.EAST);

        installDrag(grip, row);
        return p;
    }

    /**
     * Lets a row be dragged to another position using its grip.
     *
     * <p>Attached to the grip and not to the row. A row contains a description field,
     * a type chooser and a remove button, all of which want the mouse themselves;
     * listening on the whole row showed a move cursor over those controls and lost
     * the drag to whichever one the pointer happened to be over.
     *
     * <p>The target is worked out from where the pointer is, not from how far it has
     * moved. Moving by one position per event meant a drag across the list shuffled
     * the row along one place at a time and never let it be dropped somewhere far
     * away.
     */
    private void installDrag(final JLabel grip, final Row row) {
        grip.setToolTipText(I18n.t(Strings.PED_DRAG_TIP));
        grip.setCursor(java.awt.Cursor.getPredefinedCursor(
                java.awt.Cursor.HAND_CURSOR));
        grip.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                dragRow = row;
                grip.setCursor(java.awt.Cursor.getPredefinedCursor(
                        java.awt.Cursor.MOVE_CURSOR));
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                dragRow = null;
                grip.setCursor(java.awt.Cursor.getPredefinedCursor(
                        java.awt.Cursor.HAND_CURSOR));
            }
        });
        grip.addMouseMotionListener(new java.awt.event.MouseMotionListener() {
            @Override
            public void mouseDragged(java.awt.event.MouseEvent e) {
                if (dragRow == null) {
                    return;
                }
                int from = rows.indexOf(dragRow);
                if (from < 0) {
                    return;
                }
                int target = indexAtPoint(SwingUtilities.convertPoint(
                        grip, e.getPoint(), listPanel));
                if (target >= 0 && target != from) {
                    moveTo(dragRow.flag, target);
                }
            }

            @Override
            public void mouseMoved(java.awt.event.MouseEvent e) {
                // nothing to do
            }
        });
    }

    /** The row the pointer is over, by geometry rather than by event source. */
    private int indexAtPoint(java.awt.Point p) {
        int count = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).removed) {
                continue;
            }
            Component c = rowComponentAt(count);
            count++;
            if (c == null) {
                continue;
            }
            Rectangle r = SwingUtilities.convertRectangle(
                    c.getParent(), c.getBounds(), listPanel);
            if (p.y < r.y + r.height / 2) {
                return i;
            }
        }
        // Below every row: the end of the list.
        return rows.size() - 1;
    }

    /** The nth visible row component. */
    private Component rowComponentAt(int n) {
        int seen = 0;
        for (Component c : listPanel.getComponents()) {
            if (!(c instanceof JComponentHeader)) {
                continue;
            }
            if (seen == n) {
                return c;
            }
            seen++;
        }
        return null;
    }

    private void updateSummary() {
        int visible = 0;
        int removed = 0;
        for (Row r : rows) {
            if (r.removed) {
                removed++;
            } else {
                visible++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(I18n.t(Strings.PED_SUMMARY_COUNT, Integer.valueOf(visible)));
        if (removed > 0) {
            sb.append("  \u00B7  ")
              .append(I18n.t(Strings.PED_SUMMARY_REMOVED, Integer.valueOf(removed)));
        }
        if (store != null && store.hasEdits(scope)) {
            sb.append("  \u00B7  ").append(I18n.t(Strings.PED_SUMMARY_EDITED));
        }
        summary.setText(sb.toString());
    }

    // ------------------------------------------------------------------
    // Adding
    // ------------------------------------------------------------------

    private void promptForParameter() {
        JTextField name = new JTextField("", 16);
        JTextField desc = new JTextField("", 30);
        JPanel form = new JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints g = new java.awt.GridBagConstraints();
        g.insets = new Insets(3, 3, 3, 3);
        g.anchor = java.awt.GridBagConstraints.WEST;
        g.gridx = 0; g.gridy = 0;
        form.add(new JLabel(I18n.t(Strings.PED_COL_PARAM)), g);
        g.gridx = 1;
        form.add(name, g);
        g.gridx = 0; g.gridy = 1;
        form.add(new JLabel(I18n.t(Strings.PED_COL_DESC)), g);
        g.gridx = 1;
        form.add(desc, g);
        g.gridx = 0; g.gridy = 2; g.gridwidth = 2;
        JLabel hint = new JLabel(I18n.t(Strings.PED_ADD_PROMPT));
        hint.setForeground(new java.awt.Color(0x66, 0x66, 0x66));
        form.add(hint, g);

        while (true) {
            int r = JOptionPane.showConfirmDialog(this, form,
                    I18n.t(Strings.PED_ADD_DIALOG_TITLE),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                return;
            }
            String typed = name.getText().trim();
            if (typed.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        I18n.t(Strings.PED_NAME_REQUIRED));
                continue;
            }
            // The key is what identifies the row and what the store records; the
            // typed text is what the field shows. For a positional the two differ,
            // because the key carries the marker that keeps it from looking like a
            // flag of the same spelling.
            String key = Row.keyOf(typed);
            Row existing = find(key);
            if (existing != null) {
                if (existing.removed) {
                    // Adding back something that was removed is a change of mind.
                    existing.removed = false;
                    if (!desc.getText().trim().isEmpty()) {
                        existing.description = desc.getText().trim();
                    }
                    refresh();
                    return;
                }
                JOptionPane.showMessageDialog(this,
                        I18n.t(Strings.PED_ALREADY_LISTED, typed));
                continue;
            }
            Row row = new Row(key, typed, desc.getText().trim(),
                    Schema.Option.Kind.TEXT, true);
            rows.add(row);
            refresh();
            return;
        }
    }

    private Row find(String flag) {
        for (Row r : rows) {
            if (r.flag.equals(flag)) {
                return r;
            }
        }
        return null;
    }

    private void resetEverything() {
        int confirm = JOptionPane.showConfirmDialog(this,
                I18n.t(Strings.PED_RESET_CONFIRM),
                I18n.t(Strings.PED_RESET), JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        // Nothing is undone here by hand. The caller drops the stored edits and asks
        // the tool for its help again, which is what actually restores the list;
        // trying to reverse each decision locally missed the ones written into the
        // help text and the ones kept by the form.
        resetRequested = true;
        closeDialog();
        // The caller has to be told, exactly as it is when OK is pressed. Closing the
        // window only hides it: showing a frame does not block, so there is no later
        // point at which the answer could be read, and a reset that only disposed the
        // window left the tool untouched and the button looking broken.
        if (acceptHandler != null) {
            acceptHandler.accepted(ParameterEditorPanel.this);
        }
    }

    /**
     * True when the user asked for everything about this tool to be forgotten.
     *
     * <p>The type choices live in a store this panel does not hold, so the caller
     * clears them; the flag says so rather than the caller inferring it, which would
     * mean guessing from an empty result whether nothing had happened or everything
     * had been undone.
     */
    public boolean isReset() {
        return resetRequested;
    }

    /** The tool-and-subcommand these edits belong to. */
    public String scope() {
        return scope;
    }

    private boolean resetRequested;

    // ------------------------------------------------------------------
    // Applying
    // ------------------------------------------------------------------

    /**
     * Writes every change to the store.
     *
     * <p>Called only after OK. The order is written last because it refers to the
     * final set of names.
     *
     * @return a line describing what changed, for the status bar
     */
    public String applyChanges() {
        if (store == null) {
            return "";
        }
        int added = 0;
        int hidden = 0;
        int restored = 0;
        int retyped = 0;

        for (Row r : rows) {
            // Worked from the name the help has, not the one on screen: a renamed
            // row's name no longer matches anything the help or the store knows it
            // by, and the rename itself is written separately.
            String root = originalOf(r.flag);
            Schema.Option original = originalOptions.get(root);
            if (r.removed) {
                if (r.added || store.isAdded(scope, root)) {
                    if (store.remove(scope, root)) {
                        added--;
                    }
                } else if (store.hide(scope, root)) {
                    hidden++;
                }
                continue;
            }
            if (original == null) {
                // Newly added here. Under its final name, which is the one the user
                // will look for.
                if (store.isAdded(scope, root)) {
                    // Renamed after being added, so the old name is dropped.
                    if (!root.equals(r.flag)) {
                        store.remove(scope, root);
                        added--;
                    }
                }
                if (store.add(scope, r.flag, r.description)) {
                    added++;
                }
            } else if (store.hiddenFor(scope).contains(root)) {
                store.unhide(scope, root);
                restored++;
            } else if (r.added || store.isAdded(scope, root)) {
                // An added parameter's description is the user's to rewrite; one read
                // from the help gets its description from the help again.
                if (r.added || !r.description.equals(original.description)
                        || !root.equals(r.flag)) {
                    if (!root.equals(r.flag)) {
                        store.remove(scope, root);
                    }
                    store.add(scope, r.flag, r.description);
                }
            }
            // The kind is kept in the correction store, which the form owns, so it
            // is reported back through the list of changes below rather than written
            // here.
            if (original != null && r.kind != originalKinds.get(root)) {
                retyped++;
            }
        }

        List<String> order = new ArrayList<String>();
        for (Row r : rows) {
            if (!r.removed) {
                order.add(r.flag);
            }
        }
        store.setOrder(scope, order);

        // Renames and deletions go into the help the schema is built from, so they
        // survive the rebuild that follows. Done here rather than by the caller,
        // because the panel is what knows which names changed; a caller that only
        // read renames() would have to remember to apply them, and one that did not
        // would leave the form showing a name the help contradicts.
        if (currentSchema != null) {
            // A deleted parameter is recorded as hidden, never cut out of the help.
            // Removing the line was the old way, and it destroyed what the tool said
            // about a parameter the user might want back — besides being invisible
            // unless the raw help was opened and compared. The store remembers the
            // decision instead, which is reversible and leaves the document alone.
            // Descriptions first, then names: the description is located by the name
            // the help still uses, and renaming first would mean looking for a line
            // that no longer begins with it.
            for (Map.Entry<String, String> e : descriptionEdits.entrySet()) {
                if (purged.contains(e.getKey())) {
                    continue;
                }
                if (!currentSchema.setDescriptionInHelp(e.getKey(), e.getValue())) {
                    // The parameter has no declaration line of its own — it is named
                    // only in the usage line, which the tool documents without
                    // explaining. There is nothing in the help to rewrite, so the
                    // description is kept with the edits instead, which is where the
                    // rest of what the user states about a parameter already lives.
                    store.add(scope, e.getKey(), e.getValue());
                    if (originalOptions.get(e.getKey()) != null
                            && renames.containsKey(e.getKey())) {
                        store.remove(scope, e.getKey());
                        store.add(scope, renames.get(e.getKey()), e.getValue());
                    }
                }
            }
            for (Map.Entry<String, String> e : renames.entrySet()) {
                if (!purged.contains(e.getKey())) {
                    currentSchema.renameInHelp(e.getKey(), e.getValue());
                }
            }
        }
        store.applyTo(currentSchema, scope);

        StringBuilder sb = new StringBuilder();
        if (added > 0) {
            sb.append(I18n.t(Strings.PED_CHANGED_ADDED, Integer.valueOf(added)));
        }
        if (hidden > 0) {
            append(sb, I18n.t(Strings.PED_CHANGED_REMOVED, Integer.valueOf(hidden)));
        }
        if (restored > 0) {
            append(sb, I18n.t(Strings.PED_CHANGED_RESTORED, Integer.valueOf(restored)));
        }
        if (retyped > 0) {
            append(sb, I18n.t(Strings.PED_CHANGED_RETYPED, Integer.valueOf(retyped)));
        }
        if (sb.length() == 0) {
            return "";
        }
        // The whole summary is one template: the list of clauses is built above, but
        // the surrounding frame is translated as a unit so the sentence reads
        // naturally in either language.
        return I18n.t(Strings.PED_CHANGES_PREFIX, sb.toString());
    }

    private static void append(StringBuilder sb, String part) {
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(part);
    }

    /**
     * Records a rename as the user types.
     *
     * <p>The row's name is updated so the rest of the panel works from the new one,
     * and the change is noted so it can be written to the help on OK. Typing is not
     * validated here: a name half-typed is not yet wrong, and complaining while the
     * user is still writing would be noise. What matters is checked when OK is
     * pressed.
     */
    private void renameRow(Row row, String typed) {
        String t = typed.trim();
        if (t.isEmpty() || t.equals(row.display)) {
            return;
        }
        String newKey = Row.keyOf(t);
        if (row.flag.startsWith("@")) {
            // A positional has no line in the help to rename, so nothing is recorded
            // against the help. Its label is only what the form shows, and writing
            // <output> over <input> is a correction to that label, not a rename of
            // anything the program knows by name.
            row.display = t;
            row.flag = newKey;
            updateSummary();
            return;
        }
        String root = originalOf(row.flag);
        // A rename is remembered as a pair, the original name being what the help
        // still says. Two renames of the same row collapse into one, so a name typed
        // a character at a time does not leave a chain behind.
        renames.put(root, newKey);
        row.display = t;
        row.flag = newKey;
        updateSummary();
    }

    /** The name the help still uses for a row, following any rename already made. */
    private String originalOf(String flag) {
        for (Map.Entry<String, String> e : renames.entrySet()) {
            if (e.getValue().equals(flag)) {
                return e.getKey();
            }
        }
        return flag;
    }

    /**
     * Descriptions the user rewrote for parameters read from the help: the name the
     * help has, and the text it should carry.
     */
    private final Map<String, String> descriptionEdits =
            new LinkedHashMap<String, String>();

    /** The descriptions to write into the help, on OK. */
    public Map<String, String> descriptionEdits() {
        return new LinkedHashMap<String, String>(descriptionEdits);
    }

    /**
     * Renames to write into the help on OK: the name the help has, and the name it
     * should have.
     */
    private final Map<String, String> renames = new LinkedHashMap<String, String>();

    public Map<String, String> renames() {
        return new LinkedHashMap<String, String>(renames);
    }

    /**
     * Deletes a parameter from the help, after asking.
     *
     * <p>Asked because this one is not undone by the buttons beside it: the
     * parameter is taken out of the help text the list is built from, so it is gone
     * on the next restart too. Reset this tool is the way back.
     */
    private void confirmAndPurge(Row row) {
        boolean go;
        if (purgeConfirmer != null) {
            go = purgeConfirmer.confirm(row.flag);
        } else {
            int answer = JOptionPane.showConfirmDialog(this,
                    I18n.t(Strings.PED_DELETE_CONFIRM, row.display),
                    I18n.t(Strings.PED_DELETE_FOREVER), JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            go = answer == JOptionPane.YES_OPTION;
        }
        if (!go) {
            return;
        }
        // The row stays in the list, marked removed, so the ordinary removal path in
        // applyChanges records it. Taking the row out here is what left a deleted
        // parameter in the store: nothing was left to iterate over, so nothing
        // removed it, and the next load added it straight back — at the bottom of the
        // option list, because the recorded order no longer mentioned it.
        row.removed = true;
        row.purged = true;
        purged.add(row.flag);
        refresh();
    }

    /**
     * Asks whether to delete a parameter for good.
     *
     * <p>Replaceable so a test can drive the deletion without a modal dialog, which
     * cannot be answered from the event thread the test itself runs on.
     */
    public interface PurgeConfirmer {
        boolean confirm(String flag);
    }

    public void setPurgeConfirmer(PurgeConfirmer c) {
        this.purgeConfirmer = c;
    }

    private PurgeConfirmer purgeConfirmer;

    /**
     * The parameters marked for deletion from the help.
     *
     * <p>Held until OK, so Cancel is still an escape: nothing reaches the help text
     * until the user has accepted the whole panel.
     */
    private final List<String> purged = new ArrayList<String>();

    /** The parameters to delete from the help, once OK is pressed. */
    public List<String> purgedFlags() {
        return new ArrayList<String>(purged);
    }

    /** The schema being edited, so the store's result can be grafted onto it. */
    private Schema currentSchema;

    public void setCurrentSchema(Schema s) {
        this.currentSchema = s;
    }

    /**
     * The kinds chosen here, keyed by parameter name.
     *
     * <p>Returned rather than written, because the form owns the store of kinds and
     * the two stores are keyed differently.
     */
    public Map<String, Schema.Option.Kind> chosenKinds() {
        Map<String, Schema.Option.Kind> out = new LinkedHashMap<String, Schema.Option.Kind>();
        for (Row r : rows) {
            if (!r.removed) {
                out.put(r.flag, r.kind);
            }
        }
        return out;
    }

    /** The parameters in the order shown, for callers that need the list. */
    public List<String> orderedFlags() {
        List<String> out = new ArrayList<String>();
        for (Row r : rows) {
            if (!r.removed) {
                out.add(r.flag);
            }
        }
        return out;
    }

    /**
     * Moves a parameter to a position in the list.
     *
     * <p>The drag calls this; it is also the way a test can exercise the ordering
     * without a display, since a drag is pure geometry and a headless run has no
     * geometry to give it.
     *
     * @param flag  the parameter to move
     * @param toIndex where it should end up, counted among the visible rows
     * @return true when the list changed
     */
    public boolean moveTo(String flag, int toIndex) {
        int from = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).flag.equals(flag)) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            return false;
        }
        Row moved = rows.remove(from);
        int at = Math.max(0, Math.min(toIndex, rows.size()));
        rows.add(at, moved);
        // During a drag the rows are only moved, not rebuilt. Rebuilding would
        // destroy the grip the mouse is holding, which ends the drag: the pointer
        // would have to be pressed again for every position it passed over, and the
        // row could never be carried across the list in one gesture.
        if (dragRow != null) {
            moveRowComponents();
        } else {
            refresh();
        }
        return true;
    }

    /**
     * Reorders the row components to match the list, without rebuilding them.
     *
     * <p>Each row component is kept in a map keyed by its parameter, so a move is a
     * removal and a re-insertion of the same component rather than a fresh one.
     */
    private void moveRowComponents() {
        if (rowComponents.isEmpty()) {
            return;
        }
        listPanel.removeAll();
        for (Row r : rows) {
            Component c = rowComponents.get(r.flag);
            if (c != null) {
                listPanel.add(c);
                listPanel.add(Box.createVerticalStrut(2));
            }
        }
        listPanel.add(Box.createVerticalGlue());
        listPanel.revalidate();
        listPanel.repaint();
    }

    /** The component drawn for each parameter, so a drag can move rather than redraw. */
    private final Map<String, Component> rowComponents =
            new LinkedHashMap<String, Component>();

    /**
     * Marks a drag as started, so a move reorders the existing rows.
     *
     * <p>Exposed so a test can exercise the difference between the two ways of
     * reordering, which is otherwise only reachable by holding the mouse down.
     */
    public void beginDragForTest(String flag) {
        for (Row r : rows) {
            if (r.flag.equals(flag)) {
                dragRow = r;
                return;
            }
        }
    }

    /** Ends a drag started with {@link #beginDragForTest}. */
    public void endDragForTest() {
        dragRow = null;
        refresh();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * A panel whose layout is chosen for it, kept as a named type so the row lookup
     * can tell a row from the spacing struts between them.
     */
    static final class JComponentHeader extends JPanel {
        JComponentHeader() {
            super(new BorderLayout(4, 0));
        }
    }

    /** Document listener with one method, so the rows stay readable. */
    abstract static class SimpleDocListener implements javax.swing.event.DocumentListener {
        abstract void changed();

        @Override
        public void insertUpdate(javax.swing.event.DocumentEvent e) {
            changed();
        }

        @Override
        public void removeUpdate(javax.swing.event.DocumentEvent e) {
            changed();
        }

        @Override
        public void changedUpdate(javax.swing.event.DocumentEvent e) {
            changed();
        }
    }
}
