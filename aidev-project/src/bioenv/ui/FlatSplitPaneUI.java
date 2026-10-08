package bioenv.ui;

import java.awt.Graphics;

import javax.swing.JSplitPane;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;

/**
 * 扁平分隔条。
 *
 * <p>原生分隔条是一条带凹槽和两个小三角的立体灰条 —— 深色卡片上就是一道白杠，
 * 比它分隔的内容还抢眼。这里只画一条居中细线，其余留白，让卡片底色自己连过去。
 *
 * <p>尺寸不动：回归测试要求分隔条至少 4 像素（太细就拖不动了），这里只改画法。
 */
public final class FlatSplitPaneUI extends BasicSplitPaneUI {

    @Override
    public BasicSplitPaneDivider createDefaultDivider() {
        return new BasicSplitPaneDivider(this) {
            private static final long serialVersionUID = 1L;

            @Override
            public void paint(Graphics g) {
                int w = getWidth();
                int h = getHeight();
                // 底色跟着卡片走，分隔感只靠中间那一条线。
                g.setColor(UiTheme.surface());
                g.fillRect(0, 0, w, h);
                g.setColor(UiTheme.hair());
                int orient = getBasicSplitPaneUI().getOrientation();
                if (orient == JSplitPane.HORIZONTAL_SPLIT) {
                    // 左右分栏：分隔条是竖的
                    g.fillRect(w / 2, 0, 1, h);
                } else {
                    g.fillRect(0, h / 2, w, 1);
                }
            }
        };
    }

    /**
     * 给分隔条换皮肤。
     *
     * <p>换 UI 会按首选尺寸重算分隔位置，所以先把当前位置记下来再放回去 —— 否则
     * 用户拖好的分栏宽度会在切风格时被弹回默认值。
     */
    static void apply(JSplitPane split) {
        if (split == null) {
            return;
        }
        boolean flat = split.getUI() instanceof FlatSplitPaneUI;
        if (UiTheme.flat() == flat) {
            return;
        }
        if (UiTheme.flat()) {
            int loc = split.getDividerLocation();
            split.setUI(new FlatSplitPaneUI());
            if (loc > 0) {
                split.setDividerLocation(loc);
            }
        } else {
            split.updateUI();
        }
        split.setBorder(null);
    }
}
