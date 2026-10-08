package bioenv.i18n;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;

/**
 * 语言切换按钮：点一下在中英之间切换。
 *
 * <p>把控件做成一个类而不是让每个面板自己写三行代码，原因有两层：
 * <ol>
 *   <li><b>一致性。</b>按钮文案必须是「目标语言」（中文界面显示
 *       {@code English}），并且要在切换后立刻更新成新的目标语言。
 *       这个逻辑写错一次，界面上就会出现一个永远写着 {@code English}
 *       的按钮，用户点了两次才发现有问题。</li>
 *   <li><b>自注册注销。</b>按钮把自己注册为 {@link I18n} 的监听器，
 *       语言一变就刷新自己的文字。调用方不需要记得注册。</li>
 * </ol>
 *
 * <h2>用法</h2>
 * <pre>
 *   JButton switchLang = new LangToggle();      // 直接就是 JButton
 *   toolBar.add(switchLang);
 * </pre>
 *
 * <p>这类继承 {@link JButton} 而不是做成静态工厂方法返回 {@code JButton}：
 * 调用方常常需要设置尺寸、加进工具条、或统一改字体，继承让这些操作
 * 不需要强转。
 *
 * <h2>无图形环境</h2>
 *
 * <p>构造 {@link JButton} 本身在 headless JVM 下是安全的，但把按钮放进
 * 窗口或调用 {@code setVisible} 会抛
 * {@link java.awt.HeadlessException}。自检类因此只验证
 * {@link I18n#toggleText()} 这个纯字符串逻辑，不去实例化窗口。
 */
public final class LangToggle extends JButton {

    private static final long serialVersionUID = 1L;

    /**
     * 语言变化时刷新自己的文字。
     *
     * <p>持为字段而不是匿名内部类，是为了在
     * {@link #dispose()} 时能精确地把自己注销掉——用匿名类就没法引用，
     * 只能永久留在监听器列表里，导致对话框反复打开时累积大量失效引用。
     */
    private final Runnable refresh;

    /** 创建切换按钮，文案与提示文字都跟随当前语言。 */
    public LangToggle() {
        this.refresh = new Runnable() {
            @Override
            public void run() {
                applyText();
            }
        };
        applyText();
        addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // toggle() 会自己通知监听器，而本按钮就是监听器之一，
                // 所以这里不需要手动 applyText()：手动调用会让文案刷新两次。
                I18n.toggle();
            }
        });
        I18n.addListener(refresh);
    }

    /**
     * 从监听器列表里注销自己。
     *
     * <p>面板或对话框被销毁时必须调用。不调用不会有即时错误，
     * 但每次重新创建都会多留一个引用，注册表会持续增长，
     * 且失效的按钮仍会收到刷新回调——
     * 对一个长期驻留的 TBtools 主窗口来说这是真实的内存与性能问题。
     */
    public void dispose() {
        I18n.removeListener(refresh);
    }

    /**
     * 把按钮文字与提示更新为「切到对方语言」。
     *
     * <p>用 {@code setText} / {@code setToolTipText} 而不是构造新按钮：
     * 换按钮会让它丢失焦点、改变在布局里的位置，用户会觉得界面闪了一下。
     */
    private void applyText() {
        setText(I18n.toggleText());
        setToolTipText(I18n.t(Strings.LANG_TOOLTIP));
    }
}
