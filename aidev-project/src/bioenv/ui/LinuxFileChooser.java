package bioenv.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

import bioenv.core.LinuxDirList;
import bioenv.core.Transport;
import bioenv.i18n.I18n;
import bioenv.i18n.Strings;

/**
 * 挑一个<b>已经在 Linux 侧</b>的文件（或几个）。
 *
 * <h2>为什么要有它</h2>
 *
 * <p>参数的「...」原来只给 Windows 侧的文件：选中的本地文件会被上传到 Linux 工作目录，
 * 再把参数改写成远端路径。这在"数据在本机"时是对的，可数据<b>本来就在远端</b>时
 * （远端跑过一步、结果就躺在那儿）用户没法直接选它，只能先想办法弄一份到本机再传回去。
 *
 * <p>所以「...」现在先问一句"文件在哪一侧"，这一侧就是 Linux 的答案。选中的路径
 * <b>原样写进参数</b>——它本来就是远端路径，上传那套逻辑（{@code copyOnEnter}）只认
 * Windows 路径，会自动跳过它。
 *
 * <h2>默认打开设置好的工作目录</h2>
 *
 * <p>工作目录是用户刚刚挑好的地方，东西多半就在那儿。没设过就从主目录开始。
 *
 * <h2>两种模式</h2>
 *
 * <ul>
 *   <li><b>打开</b>（输入）：列现有文件，可单选或多选。</li>
 *   <li><b>保存</b>（输出）：输出通常还不存在，所以是"选一个目录 + 在下面填文件名"，
 *       和 Windows 侧的保存对话框语义一致。</li>
 * </ul>
 *
 * <p>布局与 {@link LinuxDirChooser} 一致，也就和 Windows 那个「打开」窗口一致：
 * 查找、上一级、主目录、新建文件夹、列表、详细信息、文件名、打开/取消。
 */
public final class LinuxFileChooser {

    private final Transport transport;
    private final String startDir;
    private final boolean multi;
    private final boolean save;

    private final JDialog dialog;
    private final JTextField lookIn = new JTextField(26);
    private final JTextField fileName = new JTextField(26);
    private final JTextField filter = new JTextField(18);

    private final DefaultListModel<String> dirModel = new DefaultListModel<String>();
    private final JList<String> dirList = new JList<String>(dirModel);
    private final DefaultListModel<String> fileModel = new DefaultListModel<String>();
    private final JList<String> fileList = new JList<String>(fileModel);

    private final JLabel status = new JLabel(" ");
    private final JButton up = iconButton(Strings.DIR_UP);
    private final JButton home = iconButton(Strings.DIR_HOME);
    private final JButton mkdir = iconButton(Strings.DIR_MKDIR);
    private final JButton open = new JButton(I18n.t(Strings.DIR_OPEN));
    private final JButton cancel = new JButton(I18n.t(Strings.DIR_CANCEL));

    /** 中间那块：建的时候存下来，切换视图只动它，不去内容面板里按序号捞。 */
    private JPanel midPanel;

    private String current = "";
    private List<String> result = new ArrayList<String>();

    private LinuxFileChooser(Transport transport, String startDir, boolean multi,
                             boolean save) {
        this.transport = transport;
        this.startDir = startDir;
        this.multi = multi;
        this.save = save;
        String title = save ? I18n.t(Strings.DIR_TITLE) : I18n.t(Strings.FILE_TITLE);
        dialog = new JDialog((java.awt.Frame) null, title, true);
        dialog.setName("bioenv.linuxFileChooser");
        dialog.setContentPane(build());
        dialog.pack();
        dialog.setMinimumSize(new Dimension(600, 460));
    }

    /**
     * 弹出窗口，返回选中的<b>远端绝对路径</b>；取消返回空表。
     *
     * @param transport 当前通道（WSL 或 SSH）
     * @param startDir  初始目录，通常是设置好的工作目录；为空时用主目录
     * @param multi     是否允许多选（输入参数通常是）
     * @param save      保存模式：选目录 + 填文件名（输出参数用）
     */
    public static List<String> open(Transport transport, String startDir,
                                    boolean multi, boolean save) {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return new ArrayList<String>();
        }
        LinuxFileChooser c = new LinuxFileChooser(transport, startDir, multi, save);
        c.dialog.setLocationRelativeTo(null);
        c.load(c.initialDir());
        c.dialog.setVisible(true);
        return c.result;
    }

    private String initialDir() {
        if (startDir != null && !startDir.trim().isEmpty()) {
            return startDir.trim();
        }
        return "~";
    }

    private static JButton iconButton(String key) {
        JButton b = new JButton(I18n.t(key));
        b.setMargin(new java.awt.Insets(2, 6, 2, 6));
        b.setFocusable(false);
        return b;
    }

    private JPanel build() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        root.add(buildTop(), BorderLayout.NORTH);
        root.add(buildMiddle(), BorderLayout.CENTER);
        root.add(buildBottom(), BorderLayout.SOUTH);

        up.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                load(LinuxDirList.parentOf(current));
            }
        });
        home.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                load("~");
            }
        });
        mkdir.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // 这个窗口不建目录：要建就先用工作目录那个按钮建好再来。
                // 放一个按不动的按钮不如放一句说明。
                status.setText(I18n.t(Strings.FILE_SAVE_HINT));
            }
        });
        open.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                accept();
            }
        });
        cancel.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                result = new ArrayList<String>();
                dialog.dispose();
            }
        });
        // 双击目录进去；双击文件＝选中它（保存模式下则是把名字填进文件名框）。
        MouseAdapter nav = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) {
                    return;
                }
                String d = dirList.getSelectedValue();
                if (d != null) {
                    load(LinuxDirList.childOf(current, d));
                    return;
                }
                String f = fileList.getSelectedValue();
                if (f != null) {
                    if (save) {
                        fileName.setText(f);
                    } else {
                        accept();
                    }
                }
            }
        };
        dirList.addMouseListener(nav);
        fileList.addMouseListener(nav);
        lookIn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                load(lookIn.getText());
            }
        });
        filter.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                load(current);
            }
        });
        return root;
    }

    private JPanel buildTop() {
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.add(new JLabel(I18n.t(Strings.DIR_LOOK_IN)), BorderLayout.WEST);
        top.add(lookIn, BorderLayout.CENTER);
        JPanel icons = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        icons.add(up);
        icons.add(home);
        icons.add(mkdir);
        top.add(icons, BorderLayout.EAST);
        return top;
    }

    private JPanel buildMiddle() {
        dirList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileList.setSelectionMode(multi
                ? ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
                : ListSelectionModel.SINGLE_SELECTION);
        dirList.setVisibleRowCount(7);
        fileList.setVisibleRowCount(12);
        dirList.setCellRenderer(new MarkRenderer(true));
        fileList.setCellRenderer(new MarkRenderer(false));

        // 两栏各自说清是什么。
        //
        // 原来左栏顶着一个"筛选"标签——那是我写错了：左栏是目录列表，跟筛选毫无关系，
        // 而真正的筛选输入框在右栏。同一个词出现两次、又都不在图里该在的位置，看的人
        // 只能猜。现在左栏是"文件夹（点进去）"，右栏是"文件（选中带走）"，筛选那行
        // 写在右栏标题下面、并标出它是干什么的。
        JPanel dirs = new JPanel(new BorderLayout(0, 2));
        dirs.add(new JLabel(I18n.t(Strings.FILE_COL_DIRS)), BorderLayout.NORTH);
        JScrollPane dp = new JScrollPane(dirList);
        dp.setPreferredSize(new Dimension(200, 250));
        dirs.add(dp, BorderLayout.CENTER);

        JPanel files = new JPanel(new BorderLayout(0, 2));
        JPanel head = new JPanel(new BorderLayout(4, 2));
        head.add(new JLabel(I18n.t(Strings.FILE_COL_FILES)), BorderLayout.NORTH);
        JPanel filterRow = new JPanel(new BorderLayout(4, 0));
        filterRow.add(new JLabel(I18n.t(Strings.FILE_FILTER)), BorderLayout.WEST);
        filterRow.add(filter, BorderLayout.CENTER);
        filter.setToolTipText(I18n.t(Strings.FILE_FILTER_TIP));
        head.add(filterRow, BorderLayout.CENTER);
        files.add(head, BorderLayout.NORTH);
        files.add(new JScrollPane(fileList), BorderLayout.CENTER);

        midPanel = new JPanel(new BorderLayout(6, 0));
        midPanel.add(dirs, BorderLayout.WEST);
        midPanel.add(files, BorderLayout.CENTER);
        JPanel wrap = new JPanel(new BorderLayout(0, 4));
        wrap.add(midPanel, BorderLayout.CENTER);
        wrap.add(status, BorderLayout.SOUTH);
        return wrap;
    }

    private JPanel buildBottom() {
        JPanel r1 = new JPanel(new BorderLayout(6, 0));
        r1.add(new JLabel(I18n.t(Strings.FILE_NAME_LABEL)), BorderLayout.WEST);
        r1.add(fileName, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.add(open);
        actions.add(cancel);
        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.add(r1, BorderLayout.CENTER);
        bottom.add(actions, BorderLayout.SOUTH);
        return bottom;
    }

    /** 「打开」：把选中的东西变成结果。 */
    private void accept() {
        List<String> picked = new ArrayList<String>();
        if (save) {
            // 保存模式：文件名框里的名字（可以用选中的文件预填）落在当前目录下。
            String name = fileName.getText() == null ? "" : fileName.getText().trim();
            if (!name.isEmpty()) {
                if (name.startsWith("/")) {
                    picked.add(name);
                } else {
                    picked.add(LinuxDirList.childOf(current, name));
                }
            }
        } else if (multi) {
            for (Object o : fileList.getSelectedValues()) {
                if (o != null) {
                    picked.add(LinuxDirList.childOf(current, o.toString()));
                }
            }
        } else {
            String f = fileList.getSelectedValue();
            if (f != null) {
                picked.add(LinuxDirList.childOf(current, f));
            }
        }
        if (picked.isEmpty()) {
            status.setText(I18n.t(Strings.FILE_NO_FILES));
            return;
        }
        result = picked;
        dialog.dispose();
    }

    private void load(final String dir) {
        if (transport == null) {
            status.setText(I18n.t(Strings.DIR_NO_CHANNEL));
            return;
        }
        busy(true);
        status.setText(I18n.t(Strings.DIR_LOADING));
        final String nameFilter = filter.getText();
        new javax.swing.SwingWorker<LinuxDirList.Listing, Void>() {
            @Override
            protected LinuxDirList.Listing doInBackground() {
                Transport.Result r = transport.runBashCapture(
                        LinuxDirList.filesScript(dir, nameFilter));
                return LinuxDirList.parseFiles(r.stdout(), r.stderr(), r.exitCode());
            }

            @Override
            protected void done() {
                busy(false);
                LinuxDirList.Listing got;
                try {
                    got = get();
                } catch (Exception ex) {
                    status.setText(I18n.t(Strings.DIR_LOAD_FAILED, String.valueOf(ex)));
                    return;
                }
                if (!got.ok()) {
                    status.setText(got.problem);
                    return;
                }
                current = got.path;
                lookIn.setText(current);
                dirModel.clear();
                fileModel.clear();
                // 上一级永远在第一项，不用退回再进——文件目录常常很深。
                dirModel.addElement("..");
                for (String d : got.dirs) {
                    dirModel.addElement(d);
                }
                for (String f : got.files) {
                    fileModel.addElement(f);
                }
                if (fileModel.isEmpty()) {
                    status.setText(I18n.t(Strings.FILE_NO_FILES));
                } else {
                    status.setText(I18n.t(Strings.FILE_COUNT,
                            Integer.valueOf(fileModel.size())));
                }
                if (save && fileName.getText() != null
                        && fileName.getText().trim().isEmpty()) {
                    fileName.setText("");
                }
                dialog.setTitle(save
                        ? I18n.t(Strings.DIR_TITLE) : I18n.t(Strings.FILE_TITLE));
            }
        }.execute();
    }

    private void busy(boolean on) {
        up.setEnabled(!on);
        home.setEnabled(!on);
        open.setEnabled(!on);
    }

    /** 目录与文件的图标（画出来的，和别处一样不依赖图片资源）。 */
    private static final class MarkRenderer
            extends javax.swing.DefaultListCellRenderer {
        private static final long serialVersionUID = 1L;
        private final boolean folder;
        /** 目录列的 ".." 不配图标——它不是个目录，是个动作。 */
        private final javax.swing.Icon folderIcon = new javax.swing.Icon() {
            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }

            @Override
            public void paintIcon(java.awt.Component c, java.awt.Graphics g,
                                  int x, int y) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                try {
                    g2.setColor(new java.awt.Color(0xF2, 0xC0, 0x3C));
                    g2.fillRect(x + 2, y + 3, 6, 3);
                    g2.setColor(new java.awt.Color(0xFF, 0xD1, 0x54));
                    g2.fillRect(x + 1, y + 5, 13, 8);
                    g2.setColor(new java.awt.Color(0xE0, 0xA8, 0x1E));
                    g2.drawRect(x + 1, y + 5, 13, 8);
                } finally {
                    g2.dispose();
                }
            }
        };
        private final javax.swing.Icon fileIcon = new javax.swing.Icon() {
            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }

            @Override
            public void paintIcon(java.awt.Component c, java.awt.Graphics g,
                                  int x, int y) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                try {
                    g2.setColor(java.awt.Color.WHITE);
                    g2.fillRect(x + 3, y + 2, 10, 12);
                    g2.setColor(new java.awt.Color(0x8A, 0x8A, 0x8A));
                    g2.drawRect(x + 3, y + 2, 10, 12);
                    // 折角
                    g2.fillRect(x + 9, y + 2, 4, 4);
                } finally {
                    g2.dispose();
                }
            }
        };

        MarkRenderer(boolean folder) {
            this.folder = folder;
        }

        @Override
        public java.awt.Component getListCellRendererComponent(
                javax.swing.JList<?> list, Object value, int index,
                boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
            String text = value == null ? "" : value.toString();
            setIcon("..".equals(text) ? null : (folder ? folderIcon : fileIcon));
            setIconTextGap(6);
            setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            return this;
        }
    }
}
