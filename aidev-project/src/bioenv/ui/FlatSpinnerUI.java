package bioenv.ui;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import javax.swing.JButton;
import javax.swing.plaf.basic.BasicSpinnerUI;

/**
 * 跟着主题画的微调箭头。
 *
 * <p>数字参数右边那对上下箭头原来是原生 {@code BasicArrowButton}。它的三角形是
 * <b>组件自己 {@code paint} 出来的</b>，不走 UI —— 所以换 UI、改尺寸都改不掉那套
 * 原生 3D 配色，在浅色/深色里很突兀。
 *
 * <p>这里换成"自己画三角形"的按钮。{@code BasicSpinnerUI} 仍然负责把那对按钮
 * 接上微调动作（它调用 {@code createArrowButton}，我们只换返回的组件），
 * 所以功能和键盘操作都不受影响。
 */
final class FlatSpinnerUI extends BasicSpinnerUI {

    @Override
    protected java.awt.Component createNextButton() {
        Arrow b = new Arrow(javax.swing.SwingConstants.NORTH);
        installNextButtonListeners(b);
        return b;
    }

    @Override
    protected java.awt.Component createPreviousButton() {
        Arrow b = new Arrow(javax.swing.SwingConstants.SOUTH);
        installPreviousButtonListeners(b);
        return b;
    }

    /**
     * 自己排版：编辑框铺满整个控件，两个箭头叠在它的右端内侧。
     *
     * <p>原生用 {@code BorderLayout}：编辑框在 CENTER、按钮在 EAST，于是箭头落在
     * 输入框<b>外面</b>，看着像两个并排的控件。这里让编辑框占满宽度，箭头压在它的
     * 右侧 —— 视觉上就是"箭头在框内"。
     *
     * <p>箭头后加入，所以画在编辑框之上；编辑框的右边距（见 {@code UiTheme.applyField}）
     * 留出同样宽度，文字不会钻到箭头下面。
     */
    @Override
    protected java.awt.LayoutManager createLayout() {
        return new java.awt.LayoutManager() {
            @Override
            public void addLayoutComponent(String name, java.awt.Component comp) {
            }

            @Override
            public void removeLayoutComponent(java.awt.Component comp) {
            }

            @Override
            public java.awt.Dimension preferredLayoutSize(java.awt.Container parent) {
                java.awt.Dimension d = new java.awt.Dimension(0, 0);
                for (java.awt.Component c : parent.getComponents()) {
                    java.awt.Dimension p = c.getPreferredSize();
                    d.width = Math.max(d.width, p.width);
                    d.height = Math.max(d.height, p.height);
                }
                return d;
            }

            @Override
            public java.awt.Dimension minimumLayoutSize(java.awt.Container parent) {
                return preferredLayoutSize(parent);
            }

            @Override
            public void layoutContainer(java.awt.Container parent) {
                int w = parent.getWidth();
                int h = parent.getHeight();
                java.util.List<java.awt.Component> arrows = new java.util.ArrayList<>();
                for (java.awt.Component c : parent.getComponents()) {
                    if (c instanceof Arrow) {
                        arrows.add(c);
                    } else {
                        c.setBounds(0, 0, w, h);            // 编辑框铺满
                    }
                }
                int half = Math.max(1, h / 2);
                for (int i = 0; i < arrows.size(); i++) {
                    java.awt.Component c = arrows.get(i);
                    boolean north = ((Arrow) c).pointsUp();
                    c.setBounds(w - ARROW_W, north ? 0 : h - half, ARROW_W, half);
                }
            }
        };
    }

    /** 箭头宽度：编辑框的右边距要和它一致，文字才不会钻到箭头下面。 */
    static final int ARROW_W = 20;

    /** 只画一个三角形，颜色跟着主题。 */
    private static final class Arrow extends JButton {

        private final int direction;

        boolean pointsUp() {
            return direction == javax.swing.SwingConstants.NORTH;
        }

        Arrow(int direction) {
            this.direction = direction;
            setBorder(null);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusable(false);
            setOpaque(false);
            setPreferredSize(new Dimension(18, 15));
            setMinimumSize(new Dimension(18, 15));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            int w = 8;
            int h = 5;
            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            Polygon tri = new Polygon();
            if (direction == javax.swing.SwingConstants.NORTH) {
                tri.addPoint(cx, cy - h / 2);
                tri.addPoint(cx - w / 2, cy + h / 2);
                tri.addPoint(cx + w / 2, cy + h / 2);
            } else {
                tri.addPoint(cx, cy + h / 2);
                tri.addPoint(cx - w / 2, cy - h / 2);
                tri.addPoint(cx + w / 2, cy - h / 2);
            }

            if (!isEnabled()) {
                g2.setColor(UiTheme.text3());
            } else if (getModel().isRollover() || getModel().isPressed()) {
                g2.setColor(UiTheme.accent());
            } else {
                g2.setColor(UiTheme.text2());
            }
            g2.fillPolygon(tri);
            g2.setStroke(new BasicStroke(1f));
            g2.dispose();
        }
    }
}
