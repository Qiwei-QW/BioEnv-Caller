package bioenv.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.TitledBorder;

import bioenv.i18n.I18n;
import bioenv.i18n.Strings;

/**
 * A titled section that can be folded away to a single line.
 *
 * <p>Exists because the panel's height is shared between things the user works in
 * and things they only glance at. The option list is where the work happens and is
 * the first to be squeezed: with a tool of sixty parameters, a pane showing six rows
 * makes the form nearly unusable. Folding the parts that are not being used hands
 * that height back.
 *
 * <p>The heading is the control. A separate small button would be cheaper to draw
 * but would sit next to the title saying the same thing, and the title is what the
 * user is already looking at when they want that section.
 */
public final class CollapsiblePanel extends JPanel {

    private final String title;
    private final JComponent content;
    private final JButton heading = new JButton();
    private final JPanel body = new JPanel(new BorderLayout());
    private boolean collapsed;

    /**
     * @param title   the section's name, shown in the heading
     * @param content what the section holds
     * @param collapsed whether it starts folded
     */
    public CollapsiblePanel(String title, JComponent content, boolean collapsed) {
        super(new BorderLayout());
        this.title = title;
        this.content = content;
        this.collapsed = collapsed;

        heading.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        heading.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        heading.setFocusPainted(false);
        heading.setContentAreaFilled(false);
        heading.setOpaque(false);
        heading.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setCollapsed(!isCollapsed());
            }
        });

        body.add(content, BorderLayout.CENTER);
        add(heading, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        apply();
        // Re-apply on a language switch so the heading's tooltip follows. The title
        // itself comes from the caller, who is responsible for re-labelling it.
        I18n.addListener(languageRefresh);
    }

    /**
     * Refreshes this section's own text when the language changes.
     *
     * <p>Held as a field so it can be unregistered: an anonymous listener could not
     * be referenced and would stay in the (static) listener list for the life of the
     * JVM, once per panel ever created.
     */
    private final Runnable languageRefresh = new Runnable() {
        @Override
        public void run() {
            apply();
        }
    };

    /**
     * Unregisters the language listener.
     *
     * <p>Call when this section is discarded. These panels are created per tool
     * selection, so without this the listener list grows on every click.
     */
    public void dispose() {
        I18n.removeListener(languageRefresh);
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    /**
     * Told when the section folds or unfolds.
     *
     * <p>Needed by a container that shares space between this section and another:
     * hiding the content is not enough, because the container keeps reserving the
     * room it was told to share out. The listener is how the space is handed over.
     */
    public interface FoldListener {
        void folded(boolean collapsed);
    }

    private FoldListener foldListener;

    public void setFoldListener(FoldListener l) {
        this.foldListener = l;
    }

    public void setCollapsed(boolean value) {
        if (collapsed == value) {
            return;
        }
        collapsed = value;
        apply();
        if (foldListener != null) {
            foldListener.folded(collapsed);
        }
    }

    /** The section's contents, for callers that need to reach inside. */
    public JComponent content() {
        return content;
    }

    private void apply() {
        body.setVisible(!collapsed);
        heading.setText((collapsed ? "\u25B6  " : "\u25BC  ") + title);
        // Lower-casing the title is an English-language habit ("Show command
        // preview"). It is kept for English and skipped elsewhere by passing the
        // title through unchanged when the catalogue has no such convention: the
        // template itself decides, so no language test leaks into this class.
        heading.setToolTipText(I18n.t(collapsed ? Strings.COLLAPSE_SHOW : Strings.COLLAPSE_HIDE,
                title.toLowerCase()));
        // Folded, the section takes one line and nothing more. Leaving its preferred
        // size alone would keep the split pane reserving room for content nobody can
        // see, which is the opposite of the point.
        if (collapsed) {
            body.setPreferredSize(new Dimension(0, 0));
            body.setMinimumSize(new Dimension(0, 0));
        } else {
            body.setPreferredSize(null);
            body.setMinimumSize(null);
        }
        revalidate();
        repaint();
    }

    /**
     * A section whose content is other panels, with a matching border so a group of
     * folded sections still reads as a set.
     */
    public static CollapsiblePanel of(String title, JComponent content, boolean collapsed) {
        CollapsiblePanel p = new CollapsiblePanel(title, content, collapsed);
        p.setBorder(BorderFactory.createTitledBorder(""));
        return p;
    }

    /** Keeps the heading's font in step with the panel's when it is set. */
    @Override
    public void setFont(java.awt.Font f) {
        super.setFont(f);
        if (heading != null && f != null) {
            heading.setFont(f);
        }
    }

    /** A small strut, for stacking folded sections without them touching. */
    public static Component gap(int height) {
        return Box.createRigidArea(new Dimension(0, height));
    }

    /** Convenience for a section that carries its own titled border. */
    public static CollapsiblePanel titled(String title, JComponent content,
                                          boolean collapsed) {
        CollapsiblePanel p = new CollapsiblePanel(title, content, collapsed);
        p.setBorder(BorderFactory.createTitledBorder((TitledBorder) null, title,
                TitledBorder.LEADING, TitledBorder.TOP));
        return p;
    }

    /** A label showing the section's state, used by tests. */
    public String headingText() {
        return heading.getText();
    }

    /** The heading button, so a test can press it. */
    public JButton headingButton() {
        return heading;
    }

    /** For tests: whether the body is showing. */
    public boolean bodyVisible() {
        return body.isVisible();
    }
}
