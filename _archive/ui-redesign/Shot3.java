import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import bioenv.core.Schema;
import bioenv.core.Tool;

/**
 * 给 MainPanel 塞一个工具并选中，再离屏渲染 —— 这样中间面板是"有内容"的状态。
 * 只用于出图，不是插件的一部分。
 *
 * java Shot3 <out.png>
 */
public final class Shot3 {

    public static void main(String[] args) throws Exception {
        final String out = args.length > 0 ? args[0] : "mp.png";
        final int zoom = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        final String theme = args.length > 2 ? args[2] : "classic";
        final String then = (args.length > 3 && !"-".equals(args[3])) ? args[3] : null;
        final boolean langSwitch = args.length > 4 && "lang".equals(args[4]);
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());

        final JPanel[] holder = new JPanel[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                JPanel panel = null;
                try {
                    // 真实路径：用户上次选的风格在构造界面前就生效，而不是建完再刷。
                    bioenv.ui.UiTheme.set(theme);
                    panel = (JPanel) Class.forName("bioenv.ui.MainPanel")
                            .getDeclaredConstructor().newInstance();
                    inject(panel);
                    if (langSwitch) {
                        // 切两次切回原语言：文字回到原样，剩下的差异就是纯粹的样式损伤。
                        bioenv.i18n.I18n.toggle();
                        bioenv.i18n.I18n.toggle();
                    }
                    if (then != null) {
                        bioenv.ui.UiTheme.set(then);   // 建好之后再切一次，验证"切得回来"
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                    throw new RuntimeException(t);
                }
                JPanel host = new JPanel(new BorderLayout());
                host.add(panel, BorderLayout.CENTER);
                host.setSize(1400, 900);
                layoutAll(host);
                // 出图用：把「运行」点亮，好让主按钮的样子也进画面（真实使用中选了输入后就是这个状态）
                try {
                    java.lang.reflect.Field rb = panel.getClass().getDeclaredField("runButton");
                    rb.setAccessible(true);
                    ((javax.swing.AbstractButton) rb.get(panel)).setEnabled(true);
                } catch (Throwable ignored) {
                }
                holder[0] = host;
            }
        });
        settle();

        final BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                layoutAll(holder[0]);
                BufferedImage bi = new BufferedImage(1400 * zoom, 900 * zoom,
                        BufferedImage.TYPE_INT_RGB);
                Graphics2D g = bi.createGraphics();
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, 1400 * zoom, 900 * zoom);
                g.scale(zoom, zoom);
                holder[0].paint(g);
                g.dispose();
                img[0] = bi;
            }
        });
        ImageIO.write(img[0], "png", new File(out));
        System.out.println("wrote " + out);
        System.exit(0);
    }

    @SuppressWarnings("unchecked")
    private static void inject(JPanel panel) throws Exception {
        Class<?> c = panel.getClass();
        Field fRoot = c.getDeclaredField("treeRoot");
        Field fModel = c.getDeclaredField("treeModel");
        Field fMap = c.getDeclaredField("nodeToTool");
        Field fTree = c.getDeclaredField("toolTree");
        fRoot.setAccessible(true);
        fModel.setAccessible(true);
        fMap.setAccessible(true);
        fTree.setAccessible(true);

        DefaultMutableTreeNode root = (DefaultMutableTreeNode) fRoot.get(panel);
        DefaultTreeModel model = (DefaultTreeModel) fModel.get(panel);
        Map<String, Tool> map = (Map<String, Tool>) fMap.get(panel);
        JTree tree = (JTree) fTree.get(panel);

        Tool t = new Tool();
        t.command = "orthofinder";
        t.envName = "orthofinder_env";
        t.schema = sample();

        root.removeAllChildren();
        DefaultMutableTreeNode env = new DefaultMutableTreeNode("orthofinder_env  (4)");
        DefaultMutableTreeNode leaf = new DefaultMutableTreeNode("orthofinder");
        env.add(leaf);
        root.add(env);
        model.reload();
        map.clear();
        map.put("orthofinder", t);

        TreePath p = new TreePath(new Object[]{root, env, leaf});
        tree.expandPath(new TreePath(new Object[]{root, env}));
        tree.setSelectionPath(p);
    }

    private static Schema sample() {
        Schema s = new Schema();
        s.rawHelp = "Usage: orthofinder [options]\n";
        s.parsed = true;
        s.options.add(opt("-op", "", "Stop after preparing input files for BLAST",
                Schema.Option.Kind.TEXT));
        s.options.add(opt("-b", "<dir>",
                "Start OrthoFinder from pre-computed BLAST results in <dir>",
                Schema.Option.Kind.INPUT_PATH));
        s.options.add(opt("-h", "", "Print this help text", Schema.Option.Kind.TEXT));
        s.options.add(opt("-v", "", "Print version", Schema.Option.Kind.SWITCH));
        s.options.add(opt("-t", "<int>", "Number of threads", Schema.Option.Kind.NUMBER));
        return s;
    }

    private static Schema.Option opt(String flag, String arg, String desc,
                                     Schema.Option.Kind kind) {
        Schema.Option o = new Schema.Option();
        o.flag = flag;
        o.arg = arg;
        o.description = desc;
        o.kind = kind;
        o.isSwitch = kind == Schema.Option.Kind.SWITCH;
        o.takesValue = !o.isSwitch;
        return o;
    }

    private static void layoutAll(Container c) {
        c.validate();
        c.doLayout();
        for (Component comp : c.getComponents()) {
            if (comp instanceof Container) {
                layoutAll((Container) comp);
            }
        }
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 14; i++) {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                }
            });
            Thread.sleep(40);
        }
    }

    private Shot3() {
    }
}
