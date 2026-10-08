import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;

/**
 * 只为看树的样子：用与真实面板相同的节点结构喂一棵 JTree。
 * 不是插件的一部分。
 *
 * java TreePreview <out.png> [width] [plain]
 *   plain = 旧画法（等宽字体 + Swing 默认渲染器），用来做同数据的前后对照
 */
public final class TreePreview {

    public static void main(String[] args) throws Exception {
        final String out = args.length > 0 ? args[0] : "tree.png";
        final int w = args.length > 1 ? Integer.parseInt(args[1]) : 300;
        final boolean plain = args.length > 2 && "plain".equals(args[2]);
        System.setProperty("java.awt.headless", "true");
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());

        final JPanel[] holder = new JPanel[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                DefaultMutableTreeNode root = new DefaultMutableTreeNode("conda 环境");
                add(root, "EMBOSS_env  (261)", "emboss");
                add(root, "WGDI_env  (1)", "wgdi");
                add(root, "bio_env  (1)", "bio");
                add(root, "cafe5_env  (1)", "cafe5");
                add(root, "catfasta2phyml_env  (1)", "catfasta2phyml");
                add(root, "clustalw_env  (2)", "clustalw", "clustalw2");
                add(root, "diamond_env  (1)", "diamond");
                add(root, "dos2unix_env  (4)", "dos2unix", "mac2unix",
                        "unix2dos", "unix2mac");
                add(root, "hmmer_env  (43)", "hmmbuild", "hmmsearch", "hmmscan");

                final JTree tree = new JTree(new DefaultTreeModel(root));
                tree.setRootVisible(true);
                tree.setShowsRootHandles(true);
                JScrollPane sp = new JScrollPane(tree);
                if (plain) {
                    // 改造前的那几行，原样搬过来。
                    tree.setFont(new java.awt.Font(java.awt.Font.MONOSPACED,
                            java.awt.Font.PLAIN, 13));
                } else {
                    bioenv.ui.ToolTreeStyle.apply(tree, sp);
                }

                JPanel host = new JPanel(new BorderLayout());
                host.add(sp, BorderLayout.CENTER);
                host.setSize(w, 1184 * w / 530);
                layoutAll(host);
                for (int i = 0; i < tree.getRowCount(); i++) {
                    tree.expandRow(i);
                }
                layoutAll(host);
                // 选中一个工具行，顺带看看选中态
                if (tree.getRowCount() > 2) {
                    tree.setSelectionRow(2);
                }
                layoutAll(host);
                holder[0] = host;
            }
        });
        settle();

        final BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                layoutAll(holder[0]);
                BufferedImage bi = new BufferedImage(holder[0].getWidth(),
                        holder[0].getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = bi.createGraphics();
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, bi.getWidth(), bi.getHeight());
                holder[0].paint(g);
                g.dispose();
                img[0] = bi;
            }
        });
        ImageIO.write(img[0], "png", new File(out));
        System.out.println("wrote " + out + " " + img[0].getWidth() + "x"
                + img[0].getHeight());
        System.exit(0);
    }

    private static void add(DefaultMutableTreeNode root, String env,
                            String... tools) {
        DefaultMutableTreeNode e = new DefaultMutableTreeNode(env);
        for (String t : tools) {
            e.add(new DefaultMutableTreeNode(t));
        }
        root.add(e);
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
        for (int i = 0; i < 10; i++) {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                }
            });
            Thread.sleep(30);
        }
    }

    private TreePreview() {
    }
}
