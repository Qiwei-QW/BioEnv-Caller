package bioenv.ui;

import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.JComboBox;
import javax.swing.Timer;
import javax.swing.plaf.basic.BasicComboPopup;

/**
 * 带"托下来"动画的下拉层。
 *
 * <p>原生弹出层是整块蹦出来的，很生硬。这里在弹出之后用约 100 毫秒把高度从
 * 一小截拉到完整高度，读起来就像是被"托"下来的。
 *
 * <p>做法是逐帧改弹出窗的高度（{@code setPopupSize}）。只动高度、不动宽度，
 * 所以列表内容不会重排，看起来是"露出更多"而不是"缩放"。
 *
 * <p>任何一步出问题都直接放弃动画、保持原生行为（{@code catch} 住
 * {@code RuntimeException}）—— 下拉框打不开是远比"不够顺滑"严重的问题。
 */
final class SlideComboPopup extends BasicComboPopup {

    /** 每帧间隔（毫秒）。 */
    private static final int FRAME_MS = 12;
    /** 分成几帧长到全高。9 帧 × 12 毫秒 ≈ 110 毫秒。 */
    private static final int FRAMES = 9;
    /** 起手高度：太矮了就看不见列表第一项。 */
    private static final int MIN_START = 16;

    @SuppressWarnings({"unchecked", "rawtypes"})
    SlideComboPopup(JComboBox box) {
        super(box);
    }

    /**
     * 把弹出层本身的样式也接过来。
     *
     * <p>不处理的话，列表外面套着原生边框 —— 那条 1 像素的<b>黑框</b>在浅色/深色下
     * 都很扎眼（列表项的底色、选中色虽然跟着主题了，外面这一圈没有）。
     */
    private void themePopup() {
        // 弹出层必须是"实心"的。
        //
        // 它不像普通控件那样能透明绘制：用圆角边框的话，圆角外那圈没东西盖住，
        // 底下的输入框会透上来；窗口不透明而内容留白，边缘还会出现波浪状杂色。
        // 所以这里用不透明底 + 方形细线。
        setOpaque(true);
        setBackground(UiTheme.fieldBg());
        // 不加边框：实测它会在下沿和左右两端让出一圈，看着和输入框"接不上"。
        setBorder(null);
        setBorderPainted(false);
        javax.swing.JList<?> list = getList();
        if (list == null) {
            return;
        }
        list.setBackground(UiTheme.fieldBg());
        list.setForeground(UiTheme.text());
        list.setSelectionBackground(UiTheme.accentSoft());
        list.setSelectionForeground(UiTheme.accent());
        list.setBorder(null);                       // 去掉列表自带的那圈线
        list.setOpaque(true);
        java.awt.Container p = list.getParent();    // viewport
        if (p != null) {
            if (p instanceof javax.swing.JComponent) {
                javax.swing.JComponent vp = (javax.swing.JComponent) p;
                vp.setBorder(null);
                vp.setOpaque(true);
                vp.setBackground(UiTheme.fieldBg());
            }
            java.awt.Container sp = p.getParent();  // scroll pane
            if (sp instanceof javax.swing.JScrollPane) {
                javax.swing.JScrollPane sc = (javax.swing.JScrollPane) sp;
                sc.setBorder(null);
                sc.setOpaque(true);
                sc.setBackground(UiTheme.fieldBg());
                sc.getViewport().setOpaque(true);
                sc.getViewport().setBackground(UiTheme.fieldBg());
            }
        }
    }

    @Override
    public void show() {
        if (UiTheme.flat()) {
            themePopup();
        }
        super.show();
        if (!UiTheme.flat()) {
            return;
        }
        try {
            animateOpen();
        } catch (RuntimeException ignored) {
            // 动画只是锦上添花：失败了就让它保持原样，绝不能影响能不能拉开。
        }
    }

    /**
     * 淡入。默认不开，需要时加启动参数 {@code -Dbioenv.popup.animate=true}。
     *
     * <p>为什么默认关：弹出层的三种动画各有问题 —— 逐帧改高度会在上边缘露角，
     * 挪窗口位置会让整屏"跳一下"。淡入不动尺寸也不动位置，理论上最干净，
     * 但它依赖平台支持窗口透明度，不支持时会抛异常，所以放在开关后面。
     */
    /** 高度展开动画：从四分之一高度长到全高，约 110 毫秒（最初那版，原样）。 */
    private void animateOpen() {
        final java.awt.Dimension full = getSize();
        if (full == null || full.height <= MIN_START + 8 || full.width <= 0) {
            return;
        }
        final int start = Math.max(MIN_START, full.height / 4);
        final int step = Math.max(6, (full.height - start) / FRAMES);
        // 关键：光改"弹出层组件"的尺寸没用 —— 显示在屏幕上的是它外层的原生窗口，
        // 窗口不动，画面上就什么都看不到（这正是"怎么改都没有动画"的原因）。
        // 所以每帧连窗口一起改。
        setPopupSize(new java.awt.Dimension(full.width, start));
        final int[] h = {start};
        Timer t = new Timer(FRAME_MS, null);
        t.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                h[0] += step;
                boolean done = h[0] >= full.height;
                int now = done ? full.height : h[0];
                // 实测：setPopupSize 只改"首选尺寸"，弹出层实际尺寸纹丝不动；
                // 而 getWindowAncestor 返回的是主窗口，改它会缩放 TBtools 主窗口
                // （之前"屏幕跳一下"就是这个）。所以直接 setSize 弹出层自己。
                setPopupSize(new java.awt.Dimension(full.width, now));
                setSize(full.width, now);
                revalidate();
                repaint();
                if (done) {
                    ((Timer) e.getSource()).stop();
                }
            }
        });
        t.start();
    }
}
