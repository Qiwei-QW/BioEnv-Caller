package bioenv.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicComboBoxUI;

/**
 * 扁平下拉框。
 *
 * <p>参数类型那一列是 {@code JComboBox}，原生外观是"凹槽 + 立体箭头 + 灰底"。它是整个
 * 表单里最容易露馅的控件：浅色卡片上显得旧，深色卡片上直接是一块亮斑。这里把底色、
 * 箭头按钮和弹层底色统一到当前风格。
 *
 * <p>只换"皮肤"：{@code ComboBoxModel}、选中项、监听器都不动 —— 回归测试是靠
 * {@code setSelectedIndex} 驱动它的。
 */
public final class FlatComboBoxUI extends BasicComboBoxUI {

    /** 原生渲染器存在这里，切回经典时装回去。 */
    private static final String KEY_RENDERER = "bioenv.combo.renderer";

    @Override
    protected JButton createArrowButton() {
        // 自己画三角，不用字符："▾" 在有些字体下会缺字，渲染成一个空方块。
        JButton b = new JButton() {
            private static final long serialVersionUID = 1L;

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(20, 18);
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                            java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(UiTheme.flat() ? UiTheme.text3() : getForeground());
                    int cx = getWidth() / 2;
                    int cy = getHeight() / 2;
                    java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
                    p.moveTo(cx - 4f, cy - 2f);
                    p.lineTo(cx + 4f, cy - 2f);
                    p.lineTo(cx, cy + 3f);
                    p.closePath();
                    g2.fill(p);
                } finally {
                    g2.dispose();
                }
            }
        };
        b.setBorder(null);
        b.setContentAreaFilled(false);
        b.setFocusable(false);
        b.setOpaque(false);
        return b;
    }

    /**
     * 圆角填充画在这里。
     *
     * <p>{@code ComponentUI.update} 在 {@code paint} 之前运行，也就是在"画当前值"之前 ——
     * 填充放在这一步，文字才不会被盖住。交给边框去填是不行的：边框在 UI 之后才画。
     */
    @Override
    public void update(Graphics g, JComponent c) {
        if (UiTheme.flat() && c.isOpaque()) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            // 先把整个矩形铺成父容器的底色，再盖圆角填充。
            //
            // 和输入框同一个问题（FlatTextUIs.fill 那边已修）：圆角的四个角是
            // "透明"的，下面压着一层没接管的白色容器，点别的控件触发重绘时就露出来，
            // 看着是两端各多一块白 —— 只出现在有下拉框的参数行，就是因为它走的是
            // 这里，而不是输入框那条路。
            int w = c.getWidth();
            int h = c.getHeight();
            int r = UiTheme.radius();
            java.awt.Container p = c.getParent();
            java.awt.Color under = UiTheme.surface();
            if (p instanceof JComponent && p.isOpaque()) {
                under = p.getBackground();
            }
            g2.setColor(under);
            g2.fillRect(0, 0, w, h);

            g2.setColor(UiTheme.fieldBg());
            g2.fillRoundRect(0, 0, w, h, r, r);
            g2.dispose();
        }
        paint(g, c);
    }

    @Override
    public void paintCurrentValueBackground(Graphics g, Rectangle bounds,
                                            boolean hasFocus) {
        if (!UiTheme.flat() || bounds == null) {
            super.paintCurrentValueBackground(g, bounds, hasFocus);
            return;
        }
        // 什么都不画：圆角填充和细线都由 CardBorder 统一画了。
        //
        // 这里再填一个方形色块，就会在圆角里露出方角 —— 看着就是"外圈套内圈"。
        // 用户报的那个双层边界，很可能就是它。
    }

    /**
     * 用 Swing 原生的弹出层。
     *
     * <p>自绘弹出层试过几轮都不成：加边框后接头处露白、逐帧改高度上边缘露角、
     * 挪窗口位置又会跳屏 —— 毛病都出在"弹出层是个特殊控件"上。与其反复打补丁，
     * 不如还回原生：样式朴素，但行为永远是对的。
     */
    @Override
    protected javax.swing.plaf.basic.ComboPopup createPopup() {
        // 用自己的弹出层：带主题化 + 展开动画。
        //
        // 注意：这里曾经被改回 super.createPopup()（一次"已中断但实际写入"的编辑），
        // 结果是 SlideComboPopup 整类成了死代码 —— 后面所有动画改动都不生效，
        // 表现就是"怎么改都没有动画"。用 PopupProbe 打运行时日志才定位到。
        return UiTheme.flat() ? new SlideComboPopup(comboBox) : super.createPopup();
    }

    /** 弹层：底色和文字跟着主题，否则深色下会弹出一片白。 */
    /** 供静态入口取一份渲染器；{@code createRenderer} 是 protected。 */
    javax.swing.ListCellRenderer<Object> renderer() {
        return createRenderer();
    }

    @Override
    protected javax.swing.ListCellRenderer<Object> createRenderer() {
        return new javax.swing.plaf.basic.BasicComboBoxRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public java.awt.Component getListCellRendererComponent(
                    javax.swing.JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                java.awt.Component c = super.getListCellRendererComponent(
                        list, value, index, selected, focus);
                if (UiTheme.flat() && c instanceof JComponent) {
                    // 只改配色，不动边框和字体。
                    //
                    // 这里原来给渲染器加了 5 像素上下内边距、还换过字体 —— 两者都会
                    // 改变渲染器的首选高度，而 JComboBox 的高度正是由渲染器决定的。
                    // 于是"新风格下算出的高度"留在下拉框上，切回经典时顶栏仍高两像素，
                    // 整个面板跟着矮两像素：看上去就是内容挪了一两个像素。
                    JComponent j = (JComponent) c;
                    // 只有下拉列表里的行才自己画底（选中高亮需要）。
                    //
                    // 下拉框本身的显示区也是走这个渲染器，但 BasicComboBoxUI 调用它时
                    // index = -1。以前不区分，显示区就会画出一个方形色块压在圆角填充上
                    // —— 那就是"外圈套内圈"里的内圈。
                    boolean inList = index >= 0;
                    j.setOpaque(inList);
                    if (inList) {
                        j.setBackground(selected ? UiTheme.accentSoft()
                                : UiTheme.fieldBg());
                    }
                    j.setForeground(selected ? UiTheme.accent() : UiTheme.text());
                }
                return c;
            }
        };
    }

    /** 装上一个下拉框（新风格才动它）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void apply(javax.swing.JComboBox box) {
        if (box == null) {
            return;
        }
        UiTheme.bg(box, UiTheme.fieldBg());
        UiTheme.fg(box, UiTheme.text());
        UiTheme.font(box, UiTheme.ui(12, false));
        // 边框只画"圆角细线"，不画填充。
        //
        // 填充必须由 update() 负责：边框是在 UI 画完"当前值"之后才画的，
        // 让边框去填就会把选中的文字盖住（"框里看不见字"）。
        UiTheme.border(box, new RoundBorder(UiTheme.line(), UiTheme.radius(),
                new java.awt.Insets(3, 8, 3, 4)));
        // 非透明 + 方角底色，会从 CardBorder 的圆角里露出方角；所以设成非透明。
        // 底色和圆角填充都由 CardBorder 画，当前值区域那边不再自己填色。
        // 不透明：update() 里画圆角填充，那一步在"画当前值"之前。
        UiTheme.opaque(box, true);
        if (UiTheme.flat()) {
            if (box.getUI() instanceof FlatComboBoxUI) {
                // 已经装过了就别再装一遍。
                //
                // 以前每走一次 decorate() 都会 setUI(new FlatComboBoxUI())，
                // 而 installUI() 会把下拉框的尺寸状态重置一遍 —— 表现就是空的下拉框
                // 在切风格后从 150 宽缩成 39 宽，顶栏布局跟着整体重排。
                return;
            }
            if (box.getClientProperty("bioenv.combo.renderer") == null) {
                box.putClientProperty("bioenv.combo.renderer", box.getRenderer());
            }
            FlatComboBoxUI ui = new FlatComboBoxUI();
            box.setUI(ui);
            box.setRenderer(ui.renderer());
        } else {
            box.updateUI();
            Object saved = box.getClientProperty("bioenv.combo.renderer");
            if (saved instanceof javax.swing.ListCellRenderer) {
                box.setRenderer((javax.swing.ListCellRenderer) saved);
            }
            box.setPreferredSize(null);
            box.invalidate();
            box.revalidate();
        }
    }

    /** 图个安静：不画焦点虚线框。 */
    @Override
    public void paintCurrentValue(Graphics g, Rectangle bounds, boolean hasFocus) {
        super.paintCurrentValue(g, bounds, false);
    }

    static Color bg() {
        return UiTheme.fieldBg();
    }
}
