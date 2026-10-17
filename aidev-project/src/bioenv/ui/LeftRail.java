package bioenv.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.border.EmptyBorder;

/**
 * 最左侧那条图标栏（activity bar）。
 *
 * <pre>
 *   ┌────┬──────────────┬───────────────┬──────────┐
 *   │ 💬 │              │               │          │
 *   │    │  侧栏内容     │   主内容区     │          │
 *   │    │              │               │          │
 *   └────┴──────────────┴───────────────┴──────────┘
 *     ↑ 这一条常驻：它是入口，不能跟着面板一起收起
 * </pre>
 *
 * <p>参照 Positron / VS Code 的 activity bar：图标栏本身<b>永远在</b>，
 * 点某个图标决定它右边那一列显示什么。这样以后加"运行历史""设置"之类，
 * 都只是往这里多挂一个图标，不用再动整体布局。
 *
 * <h2>图标是画出来的，不是字体或图片</h2>
 *
 * <p>用 emoji 之类的字形不可靠 —— 换台机器字体就可能缺，显示成方框。
 * 外部图片又要多维护几个资源。这里的图标全部用 {@link Graphics2D} 画，
 * <b>跟着主题取色</b>，深色浅色都不会出现看不见的图标。
 */
public final class LeftRail extends JPanel {

    /** 图标栏宽度。够放 24px 的图标和一点边距。 */
    public static final int WIDTH = 40;

    /** 选中某个图标时回调。 */
    public interface Action {
        void run();
    }

    private final ButtonGroup group = new ButtonGroup();
    private final JPanel top = new JPanel();
    /**
     * 底那一栏。此刻只有齿轮一个按钮 —— 上面是“工作区”，
     * 下面是“设置”，这是 VS Code 那条栏的排法：用的在上、配的在下。
     */
    private final JPanel bottom = new JPanel();

    public LeftRail() {
        setLayout(new java.awt.BorderLayout());
        setPreferredSize(new Dimension(WIDTH, 0));
        setMinimumSize(new Dimension(WIDTH, 0));
        setMaximumSize(new Dimension(WIDTH, Integer.MAX_VALUE));
        setBorder(new EmptyBorder(6, 0, 6, 0));

        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);
        add(top, java.awt.BorderLayout.NORTH);

        // 底部留一块，将来放"设置"这类入口（Positron 就是这么排的）。

        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setOpaque(false);
        add(bottom, java.awt.BorderLayout.SOUTH);
    }

    /**
     * 加一个图标。
     *
     * <p><b>这就是扩展点</b>：以后加"运行历史""设置"，调用一次这个方法就行，
     * 布局不用改。
     *
     * @param id      标识，用来查询选中态
     * @param tooltip 悬停说明
     * @param icon    图标（用 {@link #chatIcon()} 之类现成的，或自己实现）
     * @param action  点击回调；<b>点已经选中的图标也会回调</b>，由调用方决定是"收起"
     */
    public JToggleButton add(final String id, final String tooltip, final Icon icon,
                             final Action action) {
        return addTo(top, id, tooltip, icon, action);
    }

    /** 加到最底那一栏。齿轮用它。 */
    public JToggleButton addBottom(final String id, final String tooltip, final Icon icon,
                                   final Action action) {
        return addTo(bottom, id, tooltip, icon, action);
    }

    private JToggleButton addTo(final JPanel parent, final String id, final String tooltip,
                                final Icon icon, final Action action) {
        final JToggleButton b = new JToggleButton(icon);
        b.setName("rail." + id);
        b.setToolTipText(tooltip);
        b.setFocusable(false);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setOpaque(false);
        b.setAlignmentX(Component.CENTER_ALIGNMENT);
        b.setPreferredSize(new Dimension(WIDTH, 36));
        b.setMaximumSize(new Dimension(WIDTH, 36));
        b.setBorder(new EmptyBorder(6, 8, 6, 8));
        b.setIcon(new Themed(icon, b));
        b.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (action != null) {
                    action.run();
                }
            }
        });
        group.add(b);
        parent.add(b);
        parent.add(Box.createVerticalStrut(2));
        return b;
    }

    /** 只选中不触发回调（用于程序化设置初值）。 */
    public void select(String id) {
        for (JToggleButton b : all()) {
            if (b.getName().equals("rail." + id)) {
                b.setSelected(true);
                return;
            }
        }
    }

    /**
     * 上下两栏里所有的图标按钮。
     *
     * <p>齿轮移到下面之后，只扫 {@code top} 的地方就找不到它了 ——
     * 三个查询方法都要走这里。
     */
    private java.util.List<JToggleButton> all() {
        java.util.List<JToggleButton> out = new java.util.ArrayList<JToggleButton>();
        for (JPanel p : new JPanel[] {top, bottom}) {
            Component[] kids = p.getComponents();
            for (int i = 0; i < kids.length; i++) {
                if (kids[i] instanceof JToggleButton) {
                    out.add((JToggleButton) kids[i]);
                }
            }
        }
        return out;
    }

    /** 全部取消选中（AI 收起来之后，图标不该还亮着）。 */
    public void clearSelection() {
        group.clearSelection();
        for (JToggleButton b : all()) {
            b.repaint();
        }
    }

    /** 当前是否有某个 id 处于选中态。 */
    public boolean isSelected(String id) {
        for (JToggleButton b : all()) {
            if (b.getName().equals("rail." + id)) {
                return b.isSelected();
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 现成的图标
    // ------------------------------------------------------------------

    /** 一棵树：工具树。 */
    /**
     * 工具树：一段弯边 + 一段直边，共享一个顶点，三个末端各一个点。
     *
     * <p>按用户手画的那张来的。它不是“一条主干分叉成两支”（我第一版画错了），
     * 而是一条路径：从左下弯上去到顶点，再从顶点竖直落下来。
     *
     * <p>弯的那边向左鼓 —— 向右鼓就变成了一个“反弯”，和手画的不一样。
     */
    public static Icon treeIcon() {
        return new Shape() {
            @Override
            public void draw(Graphics2D g, int size, Color color) {
                g.setColor(color);
                g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
                // 弯边：左下的点往左鼓着上去，到顶点。
                p.moveTo(5.0, 19.0);
                p.quadTo(5.6, 11.4, 12.0, 7.6);
                // 直边：从顶点竖直向下。
                p.moveTo(12.0, 7.6);
                p.lineTo(12.0, 18.2);
                g.draw(p);
                node(g, 5.0, 19.0);
                node(g, 12.0, 7.6);
                node(g, 12.0, 18.2);
            }
        };
    }

    /** 枝末端那个点。 */
    private static void node(Graphics2D g, double x, double y) {
        double r = 1.9;
        g.fill(new java.awt.geom.Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
    }

    /** 小机器人：AI 助手。 */
    /**
     * AI 助手：四角星芒。
     *
     * <p>原来是一张拟人的机器人脸（眼睛两个 3px 的圆、嘴一根 1.7px 的线）。
     * 放大看是个笑脸，缩到 24px 就糊成一团 —— 它靠的是细节，而细节在这个尺寸下不成立。
     *
     * <p>星芒只有四条凹边，怎么缩都还是它，而且现在就是“AI”的通用记号。
     */
    public static Icon robotIcon() {
        return new Shape() {
            @Override
            public void draw(Graphics2D g, int size, Color color) {
                g.setColor(color);
                java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
                double c = 12.0;
                double outer = 9.3;
                double inner = 2.5;
                for (int i = 0; i < 4; i++) {
                    double a = -Math.PI / 2 + i * Math.PI / 2;
                    double tipX = c + Math.cos(a) * outer;
                    double tipY = c + Math.sin(a) * outer;
                    double nextX = c + Math.cos(a + Math.PI / 2) * outer;
                    double nextY = c + Math.sin(a + Math.PI / 2) * outer;
                    double ctrlX = c + Math.cos(a + Math.PI / 4) * inner;
                    double ctrlY = c + Math.sin(a + Math.PI / 4) * inner;
                    if (i == 0) {
                        p.moveTo(tipX, tipY);
                    } else {
                        p.lineTo(tipX, tipY);
                    }
                    p.quadTo(ctrlX, ctrlY, nextX, nextY);
                }
                p.closePath();
                g.fill(p);
            }
        };
    }

    /** 列表：运行历史（留给以后）。 */
    public static Icon listIcon() {
        return new Shape() {
            @Override
            public void draw(Graphics2D g, int size, Color color) {
                g.setColor(color);
                g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                for (int i = 0; i < 3; i++) {
                    int y = 6 + i * 6;
                    g.fillOval(3, y - 1, 3, 3);
                    g.drawLine(9, y, size - 3, y);
                }
            }
        };
    }

    /** 齿轮：设置（留给以后）。 */
    /**
     * 设置：齿轮。
     *
     * <p>原来是一个圆圈加八条向外的直线 —— 数学上确实是“齿轮状”，
     * 但看起来是太阳。齿轮靠的是“齿的形状”，不是“有没有射线”。
     *
     * <p>现在直接把轮廓画成齿轮形：半径随角度做八次起伏，就是八个圆齿。
     * 中间留一个孔（描边，不填）—— 齿轮就是这么认出来的。
     */
    public static Icon gearIcon() {
        return new Shape() {
            @Override
            public void draw(Graphics2D g, int size, Color color) {
                g.setColor(color);
                g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                double c = 12.0;
                java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
                int steps = 192;
                for (int i = 0; i <= steps; i++) {
                    double th = i * 2 * Math.PI / steps;
                    double r = 6.5 + 1.7 * Math.cos(8 * th);
                    double x = c + Math.cos(th) * r;
                    double y = c + Math.sin(th) * r;
                    if (i == 0) {
                        p.moveTo(x, y);
                    } else {
                        p.lineTo(x, y);
                    }
                }
                p.closePath();
                g.draw(p);
                g.draw(new java.awt.geom.Ellipse2D.Double(c - 2.1, c - 2.1, 4.2, 4.2));
            }
        };
    }

    // ------------------------------------------------------------------

    /** 图标画法。颜色由外面给，所以能跟着主题变。 */
    private abstract static class Shape implements Icon {
        abstract void draw(Graphics2D g, int size, Color color);

        @Override
        public int getIconWidth() {
            return 24;
        }

        @Override
        public int getIconHeight() {
            return 24;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.translate(x, y);
                draw(g2, 24, colorFor(c));
            } finally {
                g2.dispose();
            }
        }

        /**
         * 取色。
         *
         * <p>不走 {@code UiTheme.role}：图标是<b>自己画的像素</b>，不是控件文字，
         * 拿不到那套角色色。这里直接按主题给两组对比度足够的灰，深色浅色都看得见。
         */
        private static Color colorFor(Component c) {
            boolean dark = UiTheme.DARK.equals(UiTheme.current());
            boolean on = c instanceof JToggleButton && ((JToggleButton) c).isSelected();
            if (dark) {
                return on ? new Color(0xFF, 0xFF, 0xFF) : new Color(0xB4, 0xB6, 0xBE);
            }
            return on ? new Color(0x10, 0x12, 0x16) : new Color(0x5A, 0x5D, 0x66);
        }
    }

    /**
     * 包一层：<b>选中状态一变就重画图标</b>。
     *
     * <p>图标颜色取决于 {@code isSelected()}，而 Swing 不会因为选中态变化就重画图标 ——
     * 不套这一层的话，点来点去颜色不会变。
     */
    private static final class Themed implements Icon {
        private final Icon inner;
        private final JToggleButton owner;

        Themed(Icon inner, JToggleButton owner) {
            this.inner = inner;
            this.owner = owner;
        }

        @Override
        public int getIconWidth() {
            return inner.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return inner.getIconHeight();
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            inner.paintIcon(owner, g, x, y);
        }
    }
}
