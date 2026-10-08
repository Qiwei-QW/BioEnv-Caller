import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.Map;

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
 * 把 MainPanel 整棵组件树的几何（位置／大小／字体）dump 成文本。
 *
 * <p>用途：换风格后"内容挪了一两个像素"这种问题，靠比对渲染出来的像素只能猜是哪一层；
 * 直接把切换前后的几何做差，差在哪个控件上是一目了然的。
 *
 * <p>只用于排查，不是插件的一部分。
 *
 * java GeomDump <初始风格> <切换到的风格或-> <输出文件>
 */
public final class GeomDump {

    public static void main(String[] args) throws Exception {
        final String theme = args.length > 0 ? args[0] : "classic";
        final String then = (args.length > 1 && !"-".equals(args[1])) ? args[1] : null;
        final String out = args.length > 2 ? args[2] : "geom.txt";
        final boolean langSwitch = args.length > 3 && "lang".equals(args[3]);
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());

        final JPanel[] holder = new JPanel[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    bioenv.ui.UiTheme.set(theme);
                    JPanel panel = (JPanel) Class.forName("bioenv.ui.MainPanel")
                            .getDeclaredConstructor().newInstance();
                    inject(panel);
                    if (langSwitch) {
                        bioenv.i18n.I18n.toggle();
                        bioenv.i18n.I18n.toggle();
                    }
                    if (then != null) {
                        bioenv.ui.UiTheme.set(then);
                    }
                    JPanel host = new JPanel(new BorderLayout());
                    host.add(panel, BorderLayout.CENTER);
                    host.setSize(1400, 900);
                    layoutAll(host);
                    holder[0] = host;
                } catch (Throwable t) {
                    t.printStackTrace();
                    throw new RuntimeException(t);
                }
            }
        });
        settle();

        final StringBuilder sb = new StringBuilder();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                layoutAll(holder[0]);
                dump(holder[0], "", sb);
            }
        });
        PrintWriter w = new PrintWriter(new File(out), "UTF-8");
        w.print(sb);
        w.close();
        System.out.println("wrote " + out);
        System.exit(0);
    }

    private static void dump(Container c, String path, StringBuilder sb) {
        Component[] kids = c.getComponents();
        for (int i = 0; i < kids.length; i++) {
            Component k = kids[i];
            String p = path.isEmpty() ? String.valueOf(i) : path + "/" + i;
            java.awt.Rectangle r = k.getBounds();
            String cls = k.getClass().getName();
            String name = k.getName() == null ? "" : k.getName();
            String font = "";
            if (k instanceof javax.swing.JComponent) {
                Object role = ((javax.swing.JComponent) k)
                        .getClientProperty("bioenv.theme.role");
                if (role != null) {
                    name = name + " role=" + role;
                }
                java.awt.Font f = k.getFont();
                if (f != null) {
                    font = f.getFamily() + "/" + f.getStyle() + "/" + f.getSize();
                }
            }
            sb.append(p).append('\t').append(cls).append('\t').append(name)
              .append('\t').append(r.x).append(',').append(r.y).append(',')
              .append(r.width).append(',').append(r.height).append('\t')
              .append(font).append('\t')
              .append(k.getPreferredSize().width).append('x')
              .append(k.getPreferredSize().height).append('\n');
            if (k instanceof Container) {
                dump((Container) k, p, sb);
            }
        }
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

    private GeomDump() {
    }
}
