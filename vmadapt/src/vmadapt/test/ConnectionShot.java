package vmadapt.test;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import vmadapt.ui.ConnectionPanel;

/**
 * 把真实的 {@link ConnectionPanel} 画成 PNG，用于评审界面。
 *
 * <p>不是设计稿：这里构造的是真控件、真布局、真文案，所以图上的东西就是代码产出的
 * 东西。审查一个"按钮太多、层次不清"的界面时，看真图比看代码有效得多。
 *
 * <p>用法：{@code ConnectionShot <输出目录>}。三个标签页各出一张。
 */
public final class ConnectionShot {

    public static void main(String[] args) throws Exception {
        final File out = new File(args.length > 0 ? args[0] : ".");
        out.mkdirs();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    ConnectionPanel panel = new ConnectionPanel();
                    panel.setSize(1180, 900);
                    javax.swing.JTabbedPane tabs = tabsOf(panel);
                    if (tabs == null) {
                        System.out.println("failed: 找不到标签页控件，界面结构可能变了");
                        return;
                    }
                    // 只有两页：0 连接配置、1 网络模式速查。「连接结果」页已删除
                    // （结果改由弹窗呈现），所以这里也只画两页。
                    int pages = Math.min(2, tabs.getTabCount());
                    for (int i = 0; i < pages; i++) {
                        tabs.setSelectedIndex(i);
                        shot(panel, out, "conn-tab" + (i + 1) + ".png");
                    }
                    System.out.println("wrote " + pages + " images to " + out);
                } catch (Exception e) {
                    System.out.println("failed: " + e);
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * 取面板内部的标签页控件。
     *
     * <p>用反射而不是给生产类加一个访问器：这个访问器的唯一使用者是截图自检，
     * 为它扩大公开 API 不划算。字段名变了这里会返回 null 并明说，不会静默出错图。
     */
    private static javax.swing.JTabbedPane tabsOf(ConnectionPanel panel) {
        try {
            java.lang.reflect.Field f =
                    ConnectionPanel.class.getDeclaredField("tabs");
            f.setAccessible(true);
            Object v = f.get(panel);
            return (v instanceof javax.swing.JTabbedPane)
                    ? (javax.swing.JTabbedPane) v : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void shot(ConnectionPanel panel, File dir, String name)
            throws Exception {
        layoutRecursively(panel);
        panel.doLayout();

        BufferedImage img = new BufferedImage(panel.getWidth(), panel.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        panel.paint(g);
        g.dispose();
        ImageIO.write(img, "png", new File(dir, name));
    }

    /** 自顶向下再自底向上铺一遍。 */
    private static void layoutRecursively(Container c) {
        c.doLayout();
        for (Component ch : c.getComponents()) {
            if (ch instanceof Container) {
                layoutRecursively((Container) ch);
            }
        }
        c.doLayout();
    }
}
