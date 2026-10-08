package bioenv.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * 细圆角滚动条。
 *
 * <p>原生滚动条是带箭头的立体条：在浅色卡片上已经显得旧，压在深色卡片上更突兀 ——
 * 一列深色里横着一条浅灰的立体条。这里只留一根圆角滑杆，两端的箭头按钮收成 0 尺寸。
 */
public final class FlatScrollBarUI extends BasicScrollBarUI {

    private static final int THICK = 10;

    @Override
    protected JButton createDecreaseButton(int orientation) {
        return zero();
    }

    @Override
    protected JButton createIncreaseButton(int orientation) {
        return zero();
    }

    private static JButton zero() {
        JButton b = new JButton();
        b.setPreferredSize(new Dimension(0, 0));
        b.setMinimumSize(new Dimension(0, 0));
        b.setMaximumSize(new Dimension(0, 0));
        b.setBorder(null);
        b.setFocusable(false);
        return b;
    }

    @Override
    public Dimension getPreferredSize(JComponent c) {
        // 只有一处尺寸定义，横向条也走这里：细一点，但不至于点不中。
        return new Dimension(THICK, THICK);
    }

    @Override
    protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
        // 滑道什么都不画。
        //
        // 原先填的是 sunken()，在白卡片上就是一条贯穿整块面板的浅灰带子 —— 明明没有
        // 滚动需求也一直在那儿，比原生滚动条还显眼。滑道不画，卡片底色直接透出来，
        // 没滑动时就只剩一根淡淡的滑杆，滑动时才需要注意它。
    }

    @Override
    protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
        if (r.isEmpty() || !scrollbar.isEnabled()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            boolean hot = isThumbRollover() || isDragging;
            g2.setColor(hot ? UiTheme.text3() : UiTheme.hair());
            int t = Math.max(6, Math.min(r.width, r.height) - 4);
            if (r.width > r.height) {
                g2.fillRoundRect(r.x + 2, r.y + (r.height - t) / 2 + 1,
                        r.width - 4, t, t, t);
            } else {
                g2.fillRoundRect(r.x + (r.width - t) / 2 + 1, r.y + 2,
                        t, r.height - 4, t, t);
            }
        } finally {
            g2.dispose();
        }
    }

    /** 让滚动条宽度真的收窄：默认 UI 会按 ScrollBar.width 撑开。 */
    public static void apply(JScrollBar bar) {
        if (bar == null) {
            return;
        }
        if (!UiTheme.flat()) {
            if (bar.getUI() instanceof FlatScrollBarUI) {
                bar.updateUI();
            }
            return;
        }
        bar.setUI(new FlatScrollBarUI());
        bar.setBackground(UiTheme.sunken());
    }

    /** 只给内部用：判断当前是不是按住了滑杆。 */
    private boolean isDragging;

    @Override
    protected TrackListener createTrackListener() {
        return new TrackListener() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                isDragging = !getThumbBounds().isEmpty()
                        && getThumbBounds().contains(e.getPoint());
                super.mousePressed(e);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                isDragging = false;
                super.mouseReleased(e);
            }
        };
    }

    /** 当前主题下这根滑杆该用的颜色（给别处复用）。 */
    static Color thumbColor() {
        return UiTheme.text3();
    }
}
