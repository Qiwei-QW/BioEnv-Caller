package bioenv.test;

import java.awt.Component;
import java.awt.Container;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;

import bioenv.i18n.I18n;
import bioenv.i18n.Strings;
import bioenv.ui.CollapsiblePanel;
import bioenv.ui.MainPanel;

/**
 * Verifies the panel's arrangement: the toolbar on the left, the form in the
 * middle, and the output as a column on the right.
 *
 * <p>Laid out by hand, headless. An earlier version of this class built a real
 * frame and called {@code setVisible(true)}, on the belief that Swing only assigns
 * sizes once the component is displayable. That is not so for this panel: driving
 * {@code doLayout()} over the tree yields real widths — 260/1120 for the outer
 * split, 954/160 for the form/output column — so the frame bought nothing and cost
 * the test its place in the build, since a frame cannot be shown headless.
 *
 * <p>Labels are looked up through I18n rather than matched as English text: the
 * default language is Chinese, so English literals only ever matched when English
 * had been forced in by hand.
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp &lt;classes&gt; bioenv.test.MainLayoutTest}
 */
public final class MainLayoutTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        final MainPanel[] panel = new MainPanel[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                I18n.loadOnce();
                panel[0] = new MainPanel();
                // Lay it out at a realistic size, as Swing would once shown.
                panel[0].setSize(1400, 900);
                panel[0].doLayout();
                layoutRecursively(panel[0]);
                panel[0].doLayout();

                // A tool has to be open before the form and output are divided. The
                // panel shows an empty state until then, where the only split is the
                // toolbar/tree one, so the checks below would find no output column.
                // (The frame this test used to build did not change that on its own;
                // it only happened to rebuild the panel later.)
                Container form = findFormPanel(panel[0]);
                if (form != null) {
                    selectAnyTool(form);
                    panel[0].setSize(1400, 900);
                    panel[0].doLayout();
                    layoutRecursively(panel[0]);
                    panel[0].doLayout();
                    // The output column's width is chosen in a componentResized
                    // listener, which doLayout() does not fire. Without this the
                    // divider keeps its default and the column measures its bare
                    // minimum, so the width checks would fail against a layout that
                    // is correct. Showing a frame used to dispatch this for us.
                    notifyResized(panel[0]);
                    layoutRecursively(panel[0]);
                    panel[0].doLayout();
                }
            }
        });

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                body(panel[0]);
            }
        });

        line("");
        line("  passed: " + pass + "   failed: " + fail);
        if (fail > 0) {
            line("");
            line("FAILURES PRESENT");
            System.exit(1);
        }
    }

    private static void body(MainPanel p) {
        line("=== Arrangement ===");
        line("");

        line("[the rail is the far-left column, the assistant next to it]");
        // 左列现在是固定宽度的一列（图标栏 + AI 面板），<b>不再是分隔条</b>：
        // 侧栏宽度是设计定的，不该可拖。所以这里不能再从"最外层分隔条"去看它。
        JSplitPane outer = find(p, JSplitPane.class);
        check("a split exists", outer != null);
        if (outer == null) {
            return;
        }
        check("it is horizontal", outer.getOrientation() == JSplitPane.HORIZONTAL_SPLIT);
        // 这一条是**新约定**：最左边那一列现在是 AI 助手（默认收起），
        // 命令树退到它右边。原来这里断言"最左是树"，布局改了，断言也跟着改 ——
        // 但改的是"最左是什么"，不是把数字调松。
        // 约定又往前走了一步：最左是**常驻的图标栏**（Activity bar 那种），
        // AI 面板在它右边、默认收起。图标栏是入口，不能跟着面板一起收。
        // 注意这一层不再是 AiAssistPanel 本身，而是"图标栏 + 面板"那一列。
        check("a rail exists", find(p, bioenv.ui.LeftRail.class) != null);
        // AI 面板<b>和工具树共用同一块地方</b>（两张卡），不是并排的一列，
        // 也不是独立窗口。这样谁的宽度都不会被对方挤掉。
        // 这条断言钉住"共用"这件事：谁再把它改成并排，布局测试立刻报。
        check("the assistant shares the tree's slot",
                find(p, bioenv.ui.AiAssistPanel.class) != null);
        // 两者必须是同一个容器的孩子 —— 那才是"共用一块地方"。
        // 用"AI 的兄弟里有树的滚动面板"来判断，比按类型满树乱找可靠。
        bioenv.ui.AiAssistPanel ai = find(p, bioenv.ui.AiAssistPanel.class);
        check("the tree shares the assistant's card host",
                ai != null && ai.getParent() != null
                && find((Container) ai.getParent(), javax.swing.JScrollPane.class) != null);

        line("[the tree is the left of the next split in]");
        // 按<b>内容</b>找那一层，不按层数：以后再往外包东西也不会坏。
        JSplitPane tree = treeSplit(p);
        check("the tree split exists", tree != null);
        if (tree != null) {
            check("the toolbar is on the left",
                    containsTitle(tree.getLeftComponent(), I18n.t(Strings.TITLE_TREE)));
        }
        line("");

        line("[the output is a column on the right]");
        // The split whose *right* component is the output panel itself. A looser
        // search finds the outer split, whose right side merely contains the output,
        // and then reports the tree's width as if it were the output's.
        JSplitPane columnSplit = splitWithRightTitle(p, I18n.t(Strings.TITLE_OUTPUT));
        check("a split holds the output on its right", columnSplit != null);
        if (columnSplit == null) {
            return;
        }
        check("that split is horizontal",
                columnSplit.getOrientation() == JSplitPane.HORIZONTAL_SPLIT);

        Component form = columnSplit.getLeftComponent();
        Component output = columnSplit.getRightComponent();
        line("      form   x=" + form.getX() + " width=" + form.getWidth());
        line("      output x=" + output.getX() + " width=" + output.getWidth());
        check("the output begins to the right of the form",
                output.getX() > form.getX());
        check("the output column has a usable width", output.getWidth() >= 250);
        check("the form keeps most of the width",
                form.getWidth() > output.getWidth());
        check("the output is not stretched across the form",
                output.getWidth() < 600);
        line("");

        line("[the preview is below the form, and always shown]");
        // It was briefly a foldable section; it is shown outright now, since it is
        // the thing the user checks before pressing Run.
        check("the preview is not a foldable section",
                findSection(p, I18n.t(Strings.TITLE_PREVIEW)) == null);
        check("the preview panel is present",
                findTitled(p, I18n.t(Strings.TITLE_PREVIEW)));
        line("");

        line("[the output is not below the form]");
        // A regression guard: the output used to sit under the preview, and the
        // arrangement being changed back would otherwise pass unnoticed.
        check("no split has the output underneath",
                !splitWithBottomTitle(p, I18n.t(Strings.TITLE_OUTPUT)));
        line("");

        line("The output is a column on the right, and the form keeps the room.");
    }

    /** True when a component anywhere in the tree carries this title. */
    private static boolean findTitled(Container c, String title) {
        for (Component ch : c.getComponents()) {
            if (titleOf(ch).contains(title)) {
                return true;
            }
            if (ch instanceof javax.swing.JLabel) {
                String t = ((javax.swing.JLabel) ch).getText();
                if (t != null && t.contains(title)) {
                    return true;
                }
            }
            if (ch instanceof Container && findTitled((Container) ch, title)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fires componentResized on every split pane in the tree.
     *
     * <p>The panel sizes its dividers from a componentResized listener, which Swing
     * dispatches when a component is really shown. Headless there is no frame to
     * show, so the event is delivered directly; the listeners do the same work they
     * would in a window.
     */
    private static void notifyResized(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JSplitPane) {
                JSplitPane s = (JSplitPane) ch;
                java.awt.event.ComponentEvent e = new java.awt.event.ComponentEvent(
                        s, java.awt.event.ComponentEvent.COMPONENT_RESIZED);
                for (java.awt.event.ComponentListener l : s.getComponentListeners()) {
                    l.componentResized(e);
                }
            }
            if (ch instanceof Container) {
                notifyResized((Container) ch);
            }
        }
    }

    /** Finds the parameter form so a tool can be shown in it. */
    private static Container findFormPanel(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof bioenv.ui.SchemaFormPanel) {
                return (Container) ch;
            }
            if (ch instanceof Container) {
                Container found = findFormPanel((Container) ch);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Shows a tool with a schema, so the form/output column is built.
     *
     * <p>Without a tool the form is in its empty state and the panel has only the
     * toolbar/tree split, so there is no output column to measure.
     */
    private static void selectAnyTool(Container form) {
        bioenv.core.Schema s = new bioenv.core.Schema();
        s.parsed = true;
        s.rawHelp = "Usage: demo [options]";
        bioenv.core.Schema.Option in = new bioenv.core.Schema.Option();
        in.flag = "-in";
        in.arg = "<inputfile>";
        in.description = "Input file";
        in.kind = bioenv.core.Schema.Option.Kind.INPUT_PATH;
        s.options.add(in);
        bioenv.core.Schema.Option n = new bioenv.core.Schema.Option();
        n.flag = "-n";
        n.arg = "<int>";
        n.description = "Count";
        n.kind = bioenv.core.Schema.Option.Kind.NUMBER;
        s.options.add(n);
        bioenv.core.Tool t = new bioenv.core.Tool("demo", "demo_env", "/envs/demo");
        t.schema = s;
        ((bioenv.ui.SchemaFormPanel) form).showTool(t);
    }

    /**
     * Lays the whole tree out, top down then bottom up.
     *
     * <p>A single {@code doLayout()} on the root only sizes the root's own children,
     * because a container assigns its children's bounds from the sizes it computes
     * during its own pass. Walking the tree twice gives every level real bounds.
     */
    private static void layoutRecursively(Container c) {
        c.doLayout();
        for (Component ch : c.getComponents()) {
            if (ch instanceof Container) {
                layoutRecursively((Container) ch);
            }
        }
        c.doLayout();
    }

    /** Finds a foldable section by part of its title. */
    private static CollapsiblePanel findSection(Container c, String title) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof CollapsiblePanel) {
                CollapsiblePanel p = (CollapsiblePanel) ch;
                for (Component inner : p.getComponents()) {
                    if (inner instanceof JButton) {
                        String t = ((JButton) inner).getText();
                        if (t != null && t.contains(title)) {
                            return p;
                        }
                    }
                }
            }
            if (ch instanceof Container) {
                CollapsiblePanel r = findSection((Container) ch, title);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /**
     * The split whose right-hand side contains this title.
     *
     * <p>The output column is no longer marked with a {@link TitledBorder}: the
     * redesign gave it a header row — a bold label on the left, the Clear button on
     * the right — because a border title forced the button onto a line of its own.
     * So the title is looked for either way, as a titled border or as a label, and
     * the search descends the subtree because the header sits inside the column.
     *
     * <p>When several splits qualify, the innermost wins. The outer split's right
     * side contains the output too, merely further down, and answering with that one
     * reports the tree's width as if it were the form's — which is what the width
     * checks below are there to catch.
     */
    private static JSplitPane splitWithRightTitle(Container c, String title) {
        JSplitPane found = null;
        for (Component ch : c.getComponents()) {
            if (ch instanceof JSplitPane) {
                JSplitPane s = (JSplitPane) ch;
                Component right = s.getRightComponent();
                if (right instanceof Container && containsTitle((Container) right, title)) {
                    // Prefer a deeper one if the right side holds a qualifying split.
                    JSplitPane deeper = right instanceof Container
                            ? splitWithRightTitle((Container) right, title) : null;
                    found = deeper != null ? deeper : s;
                    continue;
                }
            }
            if (ch instanceof Container) {
                JSplitPane r = splitWithRightTitle((Container) ch, title);
                if (r != null) {
                    found = r;
                }
            }
        }
        return found;
    }

    /** True when this subtree carries the title, as a border or as a label. */
    private static boolean containsTitle(Container c, String title) {
        for (Component ch : c.getComponents()) {
            if (titleOf(ch).contains(title)) {
                return true;
            }
            if (ch instanceof javax.swing.JLabel) {
                String t = ((javax.swing.JLabel) ch).getText();
                if (t != null && t.contains(title)) {
                    return true;
                }
            }
            if (ch instanceof Container && containsTitle((Container) ch, title)) {
                return true;
            }
        }
        return false;
    }

    /** True when some vertical split has this title on its bottom component. */
    private static boolean splitWithBottomTitle(Container c, String title) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JSplitPane) {
                JSplitPane s = (JSplitPane) ch;
                Component bottom = s.getRightComponent();
                if (s.getOrientation() == JSplitPane.VERTICAL_SPLIT
                        && bottom instanceof Container
                        && containsTitle((Container) bottom, title)) {
                    return true;
                }
            }
            if (ch instanceof Container && splitWithBottomTitle((Container) ch, title)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 找"左半边是命令树"那个水平分隔条。
     *
     * <p>按内容找，不按顺序：AI 助手那一层现在包在最外面，取"第一个分隔条"会拿到它。
     */
    private static JSplitPane treeSplit(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JSplitPane) {
                JSplitPane s = (JSplitPane) ch;
                if (s.getOrientation() == JSplitPane.HORIZONTAL_SPLIT
                        && containsTitle(s.getLeftComponent(),
                                I18n.t(Strings.TITLE_TREE))) {
                    return s;
                }
            }
            if (ch instanceof Container) {
                JSplitPane found = treeSplit((Container) ch);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 这棵子树里有没有带该标题的边框。
     *
     * <p>不能只看组件本身：工具树现在被套进"树 / AI"那个卡片容器里了，标题边框
     * 在<b>容器里面</b>，不在容器上。原来那句只看一眼就直接查不到，测试红了。
     */
    private static boolean containsTitle(Component c, String title) {
        if (titleOf(c).contains(title)) {
            return true;
        }
        if (c instanceof Container) {
            for (Component ch : ((Container) c).getComponents()) {
                if (containsTitle(ch, title)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 在组件树里找第一个某类型的组件。 */
    private static <T extends Component> T find(Container c, Class<T> type) {
        for (Component ch : c.getComponents()) {
            if (type.isInstance(ch)) {
                return type.cast(ch);
            }
            if (ch instanceof Container) {
                T hit = find((Container) ch, type);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static JSplitPane firstSplit(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JSplitPane) {
                return (JSplitPane) ch;
            }
        }
        return null;
    }

    private static String titleOf(Component c) {
        if (c == null) {
            return "";
        }
        if (c instanceof JComponent) {
            Border b = ((JComponent) c).getBorder();
            if (b instanceof TitledBorder) {
                return ((TitledBorder) b).getTitle();
            }
        }
        return "";
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            pass++;
        } else {
            fail++;
        }
        line("  " + (ok ? "PASS" : "FAIL") + "  " + label);
    }

    private static void line(String s) {
        System.out.println(s);
    }
}
