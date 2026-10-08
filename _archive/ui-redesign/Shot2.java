import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.i18n.I18n;
import bioenv.i18n.Lang;

/**
 * Throwaway screenshot harness (not part of the plugin).
 *
 * java Shot2 <outPrefix> <system|metal|cross> <main|form|conn|all>
 */
public final class Shot2 {

    public static void main(String[] args) throws Exception {
        String prefix = args.length > 0 ? args[0] : "ui";
        String look = args.length > 1 ? args[1] : "system";
        String what = args.length > 2 ? args[2] : "all";

        if ("metal".equals(look)) {
            UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel");
        } else if ("cross".equals(look)) {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } else {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        }
        System.out.println("LAF = " + UIManager.getLookAndFeel().getName());

        I18n.setFile(new File(System.getProperty("java.io.tmpdir"),
                "shot2_" + System.nanoTime() + ".txt"));
        I18n.resetForTest();
        I18n.set(Lang.ZH);

        if ("main".equals(what) || "all".equals(what)) {
            JPanel host = wrap(newMain(), 1400, 900);
            shoot(host, new File(prefix + "-main.png"));
        }
        if ("form".equals(what) || "all".equals(what)) {
            JPanel host = wrap(newForm(), 1400, 900);
            shoot(host, new File(prefix + "-form.png"));
        }
        if ("conn".equals(what) || "all".equals(what)) {
            JPanel host = wrap(newConn(), 1060, 760);
            shoot(host, new File(prefix + "-conn.png"));
        }
        System.out.println("done");
        System.exit(0);
    }

    private static JComponent newMain() throws Exception {
        return (JComponent) Class.forName("bioenv.ui.MainPanel")
                .getDeclaredConstructor().newInstance();
    }

    private static JComponent newConn() throws Exception {
        return (JComponent) Class.forName("vmadapt.ui.ConnectionPanel")
                .getDeclaredConstructor().newInstance();
    }

    private static JComponent newForm() throws Exception {
        final bioenv.ui.SchemaFormPanel[] f = new bioenv.ui.SchemaFormPanel[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                bioenv.ui.SchemaFormPanel form = new bioenv.ui.SchemaFormPanel();
                Tool t = new Tool();
                t.command = "hisat2";
                t.envName = "rna-seq";
                t.schema = sampleSchema();
                form.showTool(t);
                f[0] = form;
            }
        });
        return f[0];
    }

    private static Schema sampleSchema() {
        Schema s = new Schema();
        s.rawHelp = "Usage: hisat2 [options]\n";
        s.parsed = true;
        s.options.add(opt("-x", "idx", "index basename", null, Schema.Option.Kind.TEXT));
        s.options.add(opt("-1", "FILE", "input file", null, Schema.Option.Kind.INPUT_PATH));
        s.options.add(opt("-2", "FILE", "input file", null, Schema.Option.Kind.INPUT_PATH));
        s.options.add(opt("-S", "FILE", "output file", null, Schema.Option.Kind.OUTPUT_PATH));
        s.options.add(opt("-p", "int", "number of threads", "8", Schema.Option.Kind.NUMBER));
        s.options.add(opt("--rna-strandness", "MODE", "processing mode", null,
                Schema.Option.Kind.CHOICE));
        s.options.add(switchOpt("--no-spliced-alignment", "print version"));
        s.options.add(opt("--label", "TEXT", "label for each record", null,
                Schema.Option.Kind.TEXT));
        return s;
    }

    private static Schema.Option opt(String flag, String arg, String desc,
                                     Object def, Schema.Option.Kind kind) {
        Schema.Option o = new Schema.Option();
        o.flag = flag;
        o.arg = arg;
        o.description = desc;
        o.kind = kind;
        o.takesValue = true;
        if (def != null) {
            o.defaultValue = String.valueOf(def);
        }
        return o;
    }

    private static Schema.Option switchOpt(String flag, String desc) {
        Schema.Option o = new Schema.Option();
        o.flag = flag;
        o.description = desc;
        o.kind = Schema.Option.Kind.SWITCH;
        o.isSwitch = true;
        o.takesValue = false;
        return o;
    }

    private static JPanel wrap(final JComponent body, final int w, final int h)
            throws Exception {
        final JPanel[] holder = new JPanel[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                JPanel host = new JPanel(new BorderLayout());
                host.add(body, BorderLayout.CENTER);
                host.setSize(w, h);
                body.setSize(w, h);
                layoutAll(host);
                holder[0] = host;
            }
        });
        settle();
        final JPanel hp = holder[0];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                layoutAll(hp);
            }
        });
        return holder[0];
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

    private static void shoot(final JPanel panel, final File out) throws Exception {
        final BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                layoutAll(panel);
                int w = Math.max(400, panel.getWidth());
                int h = Math.max(300, panel.getHeight());
                BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = bi.createGraphics();
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, w, h);
                panel.paint(g);
                g.dispose();
                img[0] = bi;
            }
        });
        ImageIO.write(img[0], "png", out);
        System.out.println("wrote " + out.getAbsolutePath() + "  "
                + img[0].getWidth() + "x" + img[0].getHeight());
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

    private Shot2() {
    }
}
