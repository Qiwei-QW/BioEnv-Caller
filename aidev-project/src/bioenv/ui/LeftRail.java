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
        JPanel bottom = new JPanel();
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
        top.add(b);
        top.add(Box.createVerticalStrut(2));
        return b;
    }

    /** 只选中不触发回调（用于程序化设置初值）。 */
    public void select(String id) {
        Component[] kids = top.getComponents();
        for (int i = 0; i < kids.length; i++) {
            if (kids[i] instanceof JToggleButton) {
                JToggleButton b = (JToggleButton) kids[i];
                if (b.getName().equals("rail." + id)) {
                    b.setSelected(true);
                    return;
                }
            }
        }
    }

    /** 当前是否有某个 id 处于选中态。 */
    public boolean isSelected(String id) {
        Component[] kids = top.getComponents();
        for (int i = 0; i < kids.length; i++) {
            if (kids[i] instanceof JToggleButton) {
                JToggleButton b = (JToggleButton) kids[i];
                if (b.getName().equals("rail." + id)) {
                    return b.isSelected();
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 现成的图标
    // ------------------------------------------------------------------

    /** 小机器人：AI 助手。 */
    public static Icon robotIcon() {
        return new Shape() {
            @Override
            public void draw(Graphics2D g, int size, Color color) {
                g.setColor(color);
                g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                // 天线
                g.drawLine(12, 2, 12, 5);
                g.fillOval(10, 0, 4, 4);
                // 头
                g.drawRoundRect(4, 6, 16, 13, 5, 5);
                // 眼睛
                g.fillOval(8, 10, 3, 3);
                g.fillOval(14, 10, 3, 3);
                // 嘴
                g.drawLine(9, 15, 15, 15);
                // 两侧的耳朵
                g.drawLine(4, 11, 2, 11);
                g.drawLine(20, 11, 22, 11);
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
    public static Icon gearIcon() {
        return new Shape() {
            @Override
            public void draw(Graphics2D g, int size, Color color) {
                g.setColor(color);
                g.setStroke(new BasicStroke(1.6f));
                int c = size / 2;
                int r = size / 2 - 6;
                g.drawOval(c - r, c - r, r * 2, r * 2);
                g.fillOval(c - 2, c - 2, 4, 4);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    int x1 = (int) Math.round(c + Math.cos(a) * r);
                    int y1 = (int) Math.round(c + Math.sin(a) * r);
                    int x2 = (int) Math.round(c + Math.cos(a) * (r + 3));
                    int y2 = (int) Math.round(c + Math.sin(a) * (r + 3));
                    g.drawLine(x1, y1, x2, y2);
                }
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
