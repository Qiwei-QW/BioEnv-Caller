package bioenv.tools;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import bioenv.core.ToolNotesStore;
import bioenv.i18n.I18n;
import bioenv.ui.ToolAssistantPanel;

/**
 * Paints the real tool-assistant panel into a PNG, headless.
 *
 * <p>Not a mockup: this builds the actual {@link ToolAssistantPanel} and paints
 * it, so the picture shows what the code produces - the same widgets, the same
 * fonts, the same wording. Useful for reviewing a layout without opening
 * TBtools, and for showing someone what a change did.
 *
 * <p>Usage: {@code AssistantShot <outDir>}
 */
public final class AssistantShot {

    /** A plausible help document, so the first tab is not an empty box. */
    private static final String HELP =
            "trimAl v1.5.rev1 build[2025-11-25]. 2009-2020.\n"
            + "Victor Fernandez-Rodriguez, Toni Gabaldon, Salvador Capella-Gutierrez\n"
            + "\n"
            + "Basic usage\n"
            + "  trimal -in <inputfile> -out <outputfile> -(other options)\n"
            + "\n"
            + "Common options:\n"
            + "  -in <inputfile>     Input file in several formats\n"
            + "                      (clustal, fasta, NBRF/PIR, nexus, ...)\n"
            + "  -out <outputfile>   Output alignment (default stdout)\n"
            + "  -fasta              Output in FASTA format\n"
            + "  -clustal            Output in CLUSTAL format\n"
            + "  -gappyout           Automated selection, gappyout method\n"
            + "  -gt <n>             1 - (fraction of seqs with gap allowed)\n"
            + "  -st <n>             Minimum average similarity allowed\n"
            + "  -cons <n>           Minimum percentage of positions kept\n"
            + "  -w <n>              (half) Window size\n"
            + "  -sgc                Print gap scores for each column\n"
            + "  -sident             Print identity scores matrix\n"
            + "  -complementary      Get the complementary alignment\n"
            + "  -colnumbering       Get the number of columns of the input\n"
            + "\n"
            + "... (59 options parsed)";

    /** What a user might reasonably have written for themselves. */
    private static final String NOTES =
            "# 常用命令\n"
            + "trimal -in aln.fa -out trimmed.fa -automated1\n"
            + "\n"
            + "# 参数备注（工具 help 没讲清的）\n"
            + "-in     输入比对文件，支持 fasta / clustal / nexus\n"
            + "-out    输出文件；不写就打印到屏幕\n"
            + "-gappyout   最常用：去掉空位列多的位置\n"
            + "-gt     取值 0~1，越小保留的列越多\n"
            + "\n"
            + "# 容易踩的坑\n"
            + "-w 的窗口宽度是「半宽」，不是全宽。\n"
            + "写成 -w 3，实际看的是前后各 3 列。\n"
            + "\n"
            + "# 我的标准流程\n"
            + "trimal -in raw.fa -gt 0.8 -st 0.001 \\\n"
            + "       -out clean.fa -fasta";

    public static void main(String[] args) throws Exception {
        final File outDir = new File(args.length > 0 ? args[0] : ".");
        outDir.mkdirs();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    I18n.loadOnce();

                    // First tab: the help, with no notes written yet.
                    File notes1 = new File(outDir, "shot1-notes.properties");
                    ToolAssistantPanel fresh = new ToolAssistantPanel(
                            "trimal", HELP, "trimal", new ToolNotesStore(notes1));
                    shot(fresh, 0, new File(outDir, "assistant-tab1-help.png"));

                    // Second tab: the notes, as a user would have left them.
                    File notes2 = new File(outDir, "shot2-notes.properties");
                    ToolNotesStore store = new ToolNotesStore(notes2);
                    store.set("trimal", NOTES);
                    ToolAssistantPanel withNotes = new ToolAssistantPanel(
                            "trimal", HELP, "trimal", store);
                    shot(withNotes, 1, new File(outDir, "assistant-tab2-notes.png"));

                    System.out.println("wrote assistant-tab1-help.png");
                    System.out.println("wrote assistant-tab2-notes.png");
                } catch (Exception e) {
                    System.out.println("failed: " + e);
                    e.printStackTrace();
                }
            }
        });
    }

    /** Paints one tab of the panel into a PNG at a realistic window size. */
    private static void shot(ToolAssistantPanel panel, int tab, File out)
            throws Exception {
        panel.setSize(880, 620);
        panel.tabs().setSelectedIndex(tab);
        layoutRecursively(panel);
        panel.doLayout();

        BufferedImage img = new BufferedImage(panel.getWidth(), panel.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        // Swing components are not opaque by default; without a fill the PNG comes
        // out with black where the panel does not paint.
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        panel.paint(g);
        g.dispose();

        ImageIO.write(img, "png", out);
        panel.dispose();
    }

    /**
     * Lays the whole tree out, top down then bottom up.
     *
     * <p>A single {@code doLayout()} on the root only sizes the root's own
     * children, so the inner panels would report zero size and paint nothing.
     */
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
