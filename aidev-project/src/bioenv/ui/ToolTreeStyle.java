package bioenv.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.plaf.basic.BasicTreeUI;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;

/**
 * 左侧「命令（按环境分组）」工具树的外观。
 *
 * <h2>这里改了什么</h2>
 *
 * <p>只有这棵树：每个节点前面的彩色圆点、连接父子节点的引导线、名称与计数的
 * 字重分层、行高与行间距、以及树的底色和选中态。面板的其它部分——工具条、
 * 参数表单、命令预览、输出区、按钮行、状态栏——一行都没有动。
 *
 * <h2>为什么不换渲染器就行</h2>
 *
 * <p>{@link javax.swing.tree.DefaultTreeCellRenderer} 只能给出「一个图标 + 一段文字」，
 * 而这里要的是：圆点按层级换色、环境名加粗而计数不加粗且更小、工具名用次级灰、
 * 以及画在缩进空白里的引导线。所以渲染器整支换掉。
 *
 * <h2>为什么不直接改节点数据</h2>
 *
 * <p>节点的 {@code userObject} 仍然是原来的字符串（{@code "EMBOSS_env  (261)"}、工具名）。
 * 选中逻辑靠 {@code nodeToTool} 查这张表，改字符串格式会把查表打断。所以这里只按
 * <b>层级</b>（{@code TreePath} 深度）和 <b>是不是叶子</b> 判断该画成哪一种，
 * 界面的数据层保持原样。
 *
 * <h2>引导线为什么画在 UI 里而不是渲染器里</h2>
 *
 * <p>单元格渲染器拿到的绘制区域从「节点自己的缩进位置」开始，缩进空白不在它的范围里，
 * 而引导线正好画在那片空白上。所以线由 {@link StyledTreeUI} 在整棵树的坐标系里画。
 *
 * <h2>展开手柄</h2>
 *
 * <p>{@code ±} 方框被去掉了（设计里没有）。展开/折叠仍然是 Swing 的原生行为：
 * 双击行、或选中后用左右方向键。
 */
public final class ToolTreeStyle {

    // ------------------------------------------------------------------
    // 尺寸
    // ------------------------------------------------------------------

    /** 行高：设计稿换算到 13px 字号下约 28px。 */
    private static final int ROW_H = 28;
    /** 每一层的缩进。 */
    private static final int INDENT = 22;
    /** 圆点圆心到节点左边缘的距离。 */
    private static final int DOT_CX = 13;
    /** 环境（及包分组）的圆点直径。 */
    private static final int ENV_DOT_D = 10;
    /** 工具的圆点直径，比环境小一档。 */
    private static final int TOOL_DOT_D = 8;
    /** 名称文字的起始 x（相对节点左边缘）。 */
    private static final int TEXT_X = 30;
    /** 根节点没有圆点，用一小段横线代替，文字更靠左。 */
    private static final int ROOT_TEXT_X = 18;
    private static final int ROOT_DASH_W = 11;

    // ------------------------------------------------------------------
    // 配色
    // ------------------------------------------------------------------

    private static Color BG = new Color(0xF7, 0xF7, 0xF9);
    private static Color GUIDE = new Color(0xB8, 0xCF, 0xE5);
    private static Color ENV_TEXT = new Color(0x11, 0x11, 0x14);
    private static Color TOOL_TEXT = new Color(0x47, 0x47, 0x51);
    private static Color COUNT_TEXT = new Color(0x11, 0x11, 0x14);
    private static final Color ROOT_DASH = new Color(0x9E, 0x9E, 0xA8);
    private static Color SEL_BG = new Color(0xE7, 0xE7, 0xFB);
    private static Color SEL_TEXT = new Color(0x2C, 0x25, 0xC0);

    /**
     * 环境的圆点：饱和、实心，按树的顺序循环。
     *
     * <p>取值来自设计稿上量出来的像素，不是估的。
     */
    private static final Color[] ENV_DOTS = {
        new Color(0x4F, 0x46, 0xE5), new Color(0x05, 0x96, 0x69),
        new Color(0xD9, 0x77, 0x06), new Color(0xDB, 0x27, 0x77),
        new Color(0x08, 0x91, 0xB2), new Color(0x7C, 0x3A, 0xED),
        new Color(0xE1, 0x1D, 0x48), new Color(0x0D, 0x94, 0x88),
    };

    /** 工具的圆点：同一族里更亮的一档，按同一父节点下的顺序循环。 */
    private static final Color[] TOOL_DOTS = {
        new Color(0x74, 0x6C, 0xF0), new Color(0x22, 0xB5, 0x82),
        new Color(0xF2, 0xA4, 0x2E), new Color(0xF0, 0x6A, 0xA8),
        new Color(0x2C, 0xB6, 0xCE), new Color(0x9B, 0x6B, 0xF5),
        new Color(0xF2, 0x6B, 0x60), new Color(0x2E, 0xC4, 0xB4),
        new Color(0xF9, 0x8A, 0x3C), new Color(0x8F, 0xC8, 0x2E),
    };

    // ------------------------------------------------------------------
    // 字体
    // ------------------------------------------------------------------

    /**
     * 按顺序尝试的中文字体。
     *
     * <p>不能只写一个：插件要装到别人的机器上，各家 Windows 自带的字体并不一致。
     * 逐个试到存在为止，最后退回逻辑字体（逻辑字体一定在，只是样子普通）。
     */
    private static final String[] FAMILIES = {
        "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC",
        "Noto Sans CJK SC", "Source Han Sans SC", "SimHei",
    };

    private static String family;

    private static synchronized String family() {
        if (family == null) {
            Set<String> have = new HashSet<String>(Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment()
                            .getAvailableFontFamilyNames()));
            for (String f : FAMILIES) {
                if (have.contains(f)) {
                    family = f;
                    break;
                }
            }
            if (family == null) {
                family = Font.SANS_SERIF;
            }
        }
        return family;
    }

    private static Font nameFont(int style, int size) {
        return new Font(family(), style, size);
    }

    private ToolTreeStyle() {
    }

    // ------------------------------------------------------------------
    // 安装
    // ------------------------------------------------------------------

    /**
     * 把外观装到这棵树上。
     *
     * @param tree   左侧工具树
     * @param scroll 包着它的滚动面板；用于把视口底色对齐，否则行下方的空白仍是白的
     */
    public static void apply(JTree tree, JScrollPane scroll) {
        // 取值跟着主题走：新风格用卡片底色 + 靛蓝引导线，经典保持原来那套。
        if (UiTheme.flat()) {
            BG = UiTheme.surface();
            GUIDE = UiTheme.accentLine();
            ENV_TEXT = UiTheme.text();
            TOOL_TEXT = UiTheme.text2();
            COUNT_TEXT = UiTheme.text3();
            SEL_BG = UiTheme.selBg();
            SEL_TEXT = UiTheme.selText();
        } else {
            BG = new Color(0xF7, 0xF7, 0xF9);
            GUIDE = new Color(0xB8, 0xCF, 0xE5);
            ENV_TEXT = new Color(0x11, 0x11, 0x14);
            TOOL_TEXT = new Color(0x47, 0x47, 0x51);
            COUNT_TEXT = new Color(0x11, 0x11, 0x14);
            SEL_BG = new Color(0xE7, 0xE7, 0xFB);
            SEL_TEXT = new Color(0x2C, 0x25, 0xC0);
        }
        StyledTreeUI ui = new StyledTreeUI();
        tree.setUI(ui);
        // 必须在 setUI 之后：BasicTreeUI.installDefaults() 会从 UIManager 读这两个
        // 值覆盖掉，先设等于没设。左 + 右 = 每层缩进。
        ui.setLeftChildIndent(INDENT / 2);
        ui.setRightChildIndent(INDENT - INDENT / 2);

        tree.setRowHeight(ROW_H);
        // 同上，installDefaults() 会先按 UIManager 刷一遍前景/背景。
        tree.setBackground(BG);
        tree.setOpaque(true);
        tree.setCellRenderer(new NodeRenderer());
        // 去掉 L&F 自带的折线；引导线由 StyledTreeUI 自己画。
        tree.putClientProperty("JTree.lineStyle", "None");

        if (scroll != null) {
            scroll.setBackground(BG);
            JViewport vp = scroll.getViewport();
            if (vp != null) {
                vp.setOpaque(true);
                vp.setBackground(BG);
            }
        }
    }

    // ------------------------------------------------------------------
    // 引导线
    // ------------------------------------------------------------------

    private static final class StyledTreeUI extends BasicTreeUI {

        @Override
        protected void paintHorizontalLine(Graphics g, JComponent c, int y,
                                           int left, int right) {
            // 交给我们自己画（见 paint）。L&F 的默认折线不要。
        }

        @Override
        protected void paintVerticalLine(Graphics g, JComponent c, int x,
                                         int top, int bottom) {
            // 同上。
        }

        @Override
        protected void paintExpandControl(Graphics g, Rectangle clipBounds,
                                          Insets insets, Rectangle bounds,
                                          TreePath path, int row,
                                          boolean isExpanded,
                                          boolean hasBeenExpanded,
                                          boolean isLeaf) {
            // 设计里没有 ± 方框。展开/折叠仍可用双击或方向键。
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            super.paint(g, c);

            JTree tree = (JTree) c;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(GUIDE);
            int rows = tree.getRowCount();
            for (int row = 0; row < rows; row++) {
                TreePath path = tree.getPathForRow(row);
                if (path == null || !(path.getLastPathComponent()
                        instanceof DefaultMutableTreeNode)) {
                    continue;
                }
                DefaultMutableTreeNode node =
                        (DefaultMutableTreeNode) path.getLastPathComponent();
                if (node.isLeaf()) {
                    continue;
                }
                if (!tree.isExpanded(row)) {
                    continue;
                }
                Rectangle pb = tree.getRowBounds(row);
                if (pb == null) {
                    continue;
                }

                int lastChild = -1;
                for (int r = row + 1; r < rows; r++) {
                    TreePath p = tree.getPathForRow(r);
                    if (p == null || p.getPathCount() <= path.getPathCount()) {
                        break;
                    }
                    if (p.getPathCount() == path.getPathCount() + 1) {
                        lastChild = r;
                    }
                }
                if (lastChild < 0) {
                    continue;
                }
                Rectangle lb = tree.getRowBounds(lastChild);
                if (lb == null) {
                    continue;
                }

                int lineX = pb.x + INDENT / 2;
                int yStart = pb.y + pb.height / 2
                        + (path.getPathCount() == 1 ? 6 : ENV_DOT_D / 2 + 3);
                int yEnd = lb.y + lb.height / 2;
                g2.drawLine(lineX, yStart, lineX, yEnd);

                for (int r = row + 1; r <= lastChild; r++) {
                    TreePath p = tree.getPathForRow(r);
                    if (p == null || p.getPathCount() != path.getPathCount() + 1) {
                        continue;
                    }
                    Rectangle cb = tree.getRowBounds(r);
                    if (cb == null) {
                        continue;
                    }
                    // 到子节点圆点左侧三像素为止，不压住圆点。
                    int xEnd = cb.x + DOT_CX - TOOL_DOT_D / 2 - 3;
                    int cy = cb.y + cb.height / 2;
                    if (xEnd > lineX) {
                        g2.drawLine(lineX, cy, xEnd, cy);
                    }
                }
            }
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------
    // 渲染器
    // ------------------------------------------------------------------

    /**
     * 单元格渲染器。
     *
     * <p>{@code getTreeCellRendererComponent} 每次只准备状态，真正的画在
     * {@link #paintComponent} 里；返回的永远是同一个实例，不每行 new 一个。
     */
    private static final class NodeRenderer extends JComponent
            implements TreeCellRenderer {

        private static final long serialVersionUID = 1L;

        /** 0 = 根，1 = 环境或包分组，2 = 工具。 */
        private int kind;
        private String name = "";
        private String count = "";
        private Color dot = ENV_DOTS[0];
        private boolean selected;
        private boolean focused;
        private int prefWidth = 160;

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean sel, boolean expanded, boolean leaf, int row,
                boolean hasFocus) {
            selected = sel;
            focused = hasFocus;
            name = value == null ? "" : String.valueOf(value);
            count = "";

            TreePath path = tree.getPathForRow(row);
            int level = path == null ? 0 : path.getPathCount() - 1;

            if (level == 0) {
                kind = 0;
            } else if (!leaf) {
                kind = 1;
                // userObject 的格式是「名称 + 两个空格 + (计数)」，由 MainPanel 组装。
                // 这里只把它拆开分别上色，字符串本身没有改。
                int cut = name.lastIndexOf("  (");
                if (cut > 0 && name.endsWith(")")) {
                    count = name.substring(cut + 2);
                    name = name.substring(0, cut);
                }
                dot = ENV_DOTS[indexOf(value) % ENV_DOTS.length];
            } else {
                kind = 2;
                dot = TOOL_DOTS[indexOf(value) % TOOL_DOTS.length];
            }

            setOpaque(false);
            prefWidth = measure();
            return this;
        }

        /** 节点在其父节点下的位置：颜色靠它按顺序循环，折叠与否都不影响。 */
        private static int indexOf(Object value) {
            if (value instanceof DefaultMutableTreeNode) {
                DefaultMutableTreeNode n = (DefaultMutableTreeNode) value;
                if (n.getParent() != null) {
                    return n.getParent().getIndex(n);
                }
            }
            return 0;
        }

        private Font fontFor() {
            // 三种节点都用粗体：环境、包分组、以及具体的工具名。以前只有前两种加粗，
            // 工具名用常规，结果一列工具看着比环境"轻"了一档；层级现在靠颜色分
            // （环境近黑、工具次级灰），不再靠字重。
            return nameFont(Font.BOLD, 13);
        }

        private int measure() {
            int w = TEXT_X + getFontMetrics(fontFor()).stringWidth(name) + 8;
            if (!count.isEmpty()) {
                w += 6 + getFontMetrics(nameFont(Font.PLAIN, 11))
                        .stringWidth(count);
            }
            // 长命令名让树横向滚动没有意义，截断显示即可（与换渲染器之前一致）。
            return Math.min(w, 250);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(prefWidth, ROW_H);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int h = getHeight();
            int cy = h / 2;

            if (selected) {
                g2.setColor(SEL_BG);
                g2.fillRoundRect(2, 2, Math.max(0, getWidth() - 6), h - 4, 8, 8);
            }

            if (kind == 0) {
                g2.setColor(ROOT_DASH);
                g2.fillRoundRect(0, cy - 1, ROOT_DASH_W, 2, 2, 2);
                g2.setColor(selected ? SEL_TEXT : ENV_TEXT);
                g2.setFont(nameFont(Font.BOLD, 13));
                g2.drawString(name, ROOT_TEXT_X,
                        cy + textOffset(g2.getFontMetrics()));
                g2.dispose();
                return;
            }

            int d = kind == 1 ? ENV_DOT_D : TOOL_DOT_D;
            g2.setColor(dot);
            g2.fillOval(DOT_CX - d / 2, cy - d / 2, d, d);

            int x = TEXT_X;
            g2.setColor(selected ? SEL_TEXT : (kind == 1 ? ENV_TEXT : TOOL_TEXT));
            g2.setFont(fontFor());
            g2.drawString(name, x, cy + textOffset(g2.getFontMetrics()));
            x += g2.getFontMetrics().stringWidth(name);

            if (!count.isEmpty()) {
                g2.setColor(selected ? SEL_TEXT : COUNT_TEXT);
                g2.setFont(nameFont(Font.PLAIN, 11));
                x += 6;
                g2.drawString(count, x, cy + textOffset(g2.getFontMetrics()));
            }
            g2.dispose();
        }

        /** 让文字在行里垂直居中：基线 = 中心 + (升部 - 降部) / 2 的近似。 */
        private static int textOffset(java.awt.FontMetrics fm) {
            return (fm.getAscent() - fm.getDescent()) / 2;
        }
    }
}
