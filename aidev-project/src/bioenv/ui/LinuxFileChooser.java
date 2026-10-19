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
import javax.swing.JComboBox;
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
 * <h2>版式照 Windows 的「打开」窗口</h2>
 *
 * <pre>
 *   查找(I):  [ /home/demo/shili                      v ] [↑][⌂][＋][列表][详细]
 *   ┌──────────────────────────────────────────────────────┐
 *   │  📁 inputs        📁 OrthoFinder                      │
 *   │  📄 At.pep        📄 Os.pep        📄 Ta_A.pep        │
 *   └──────────────────────────────────────────────────────┘
 *   文件夹名(N): [ /home/demo/shili/At.pep ]
 *   文件类型(T): [ 所有文件                v ]
 *                                    [打开] [取消]
 * </pre>
 *
 * <h2>为什么是"一个列表"而不是"左目录右文件"</h2>
 *
 * <p>第一版分了两栏：左边目录、右边文件，各带标题。那不是大家熟悉的做法，用起来要在
 * 两栏之间来回看。Windows 那个窗口是<b>一个列表</b>：文件夹和文件混在一起、<b>文件夹
 * 排在前面</b>，靠<b>图标</b>区分——想进去就双击文件夹，想选就双击文件。用户已经会
 * 这个了，照抄比自创好。
 *
 * <h2>选中的路径是远端路径</h2>
 *
 * <p>写进参数后原样使用：{@code copyOnEnter} 只认 Windows 路径，会自动跳过它，
 * 也就是"东西已经在远端，不要再传一遍"。
 */
public final class LinuxFileChooser {

    /** 列表里的一行。目录和文件在同一个列表里，靠 dir 区分图标与双击行为。 */
    private static final class Row {
        final String name;
        final boolean dir;
        String perms = "";
        String modified = "";

        Row(String name, boolean dir) {
            this.name = name;
            this.dir = dir;
        }
    }

    private final Transport transport;
    private final String startDir;
    private final boolean multi;
    private final boolean save;
    /**
     * 这个参数要的是<b>目录</b>（如 {@code -b <dir>}）。
     *
     * <p>没有它的话，选中的文件夹一律被当成"进去"，于是目录参数根本没法把文件夹当值
     * 带走——用户报的就是这个。文件模式下保持 Windows 的行为（选文件夹＝进去）。
     */
    private final boolean directory;

    private final JDialog dialog;
    /** 查找(I)：路径，可编辑可下拉——走过的地方留在里面，回头一步就能到。 */
    private final JComboBox<String> lookIn = new JComboBox<String>();
    private final JTextField fileName = new JTextField(26);
    private final JComboBox<String> fileType = new JComboBox<String>(
            new String[]{I18n.t(Strings.FILE_TYPE_ALL)});
    private final DefaultListModel<Row> model = new DefaultListModel<Row>();
    private final JList<Row> list = new JList<Row>(model);
    private final JLabel status = new JLabel(" ");

    private final JButton up = toolButton(I18n.t(Strings.DIR_UP));
    private final JButton home = toolButton(I18n.t(Strings.DIR_HOME));
    private final JButton mkdir = toolButton(I18n.t(Strings.DIR_MKDIR));
    private final JButton viewList = toolButton(I18n.t(Strings.DIR_VIEW_LIST));
    private final JButton viewDetails = toolButton(I18n.t(Strings.DIR_VIEW_DETAILS));

    private final JButton open = new JButton(I18n.t(Strings.DIR_OPEN));
    private final JButton cancel = new JButton(I18n.t(Strings.DIR_CANCEL));

    private String current = "";
    private List<String> result = new ArrayList<String>();
    /** 详细信息视图。 */
    private boolean details;

    private LinuxFileChooser(Transport transport, String startDir, boolean multi,
                             boolean save, boolean directory) {
        this.transport = transport;
        this.startDir = startDir;
        this.multi = multi;
        this.save = save;
        this.directory = directory;
        dialog = new JDialog((java.awt.Frame) null, I18n.t(directory
                ? Strings.DIR_TITLE : Strings.FILE_TITLE), true);
        dialog.setName("bioenv.linuxFileChooser");
        dialog.setContentPane(build());
        dialog.pack();
        dialog.setMinimumSize(new Dimension(620, 430));
    }

    /**
     * 弹出窗口，返回选中的<b>远端绝对路径</b>；取消返回空表。
     *
     * @param transport 当前通道（WSL 或 SSH）
     * @param startDir  初始目录，通常是设置好的工作目录；为空时用主目录
     * @param multi     是否允许多选（输入参数通常是）
     * @param save      保存模式：选目录 + 填文件名（输出参数用）
     * @param directory 这个参数要的是目录，选中的文件夹要能当值返回
     */
    public static List<String> open(Transport transport, String startDir,
                                    boolean multi, boolean save, boolean directory) {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return new ArrayList<String>();
        }
        LinuxFileChooser c =
                new LinuxFileChooser(transport, startDir, multi, save, directory);
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

    private static JButton toolButton(String text) {
        JButton b = new JButton(text);
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

        open.setName("bioenv.fileOpen");
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
                makeDirectory();
            }
        });
        viewList.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showDetails(false);
            }
        });
        viewDetails.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showDetails(true);
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

        // 双击：文件夹进去，文件选中。和 Windows 那个窗口一致。
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) {
                    return;
                }
                Row r = list.getSelectedValue();
                if (r == null) {
                    return;
                }
                if (r.dir) {
                    load(LinuxDirList.childOf(current, r.name));
                } else if (save) {
                    fileName.setText(r.name);
                } else {
                    accept();
                }
            }
        });
        // 选中一行就把名字填到"文件夹名"里——按下「打开」之前就看得见选的是谁。
        list.addListSelectionListener(new javax.swing.event.ListSelectionListener() {
            @Override
            public void valueChanged(javax.swing.event.ListSelectionEvent e) {
                if (!e.getValueIsAdjusting()) {
                    Row r = list.getSelectedValue();
                    fileName.setText(r == null ? current
                            : LinuxDirList.childOf(current, r.name));
                }
            }
        });
        lookIn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Object v = lookIn.getEditor().getItem();
                if (v != null) {
                    load(v.toString());
                }
            }
        });
        // 「文件类型」在这个窗口里就是按名字过滤。可编辑，所以也能直接打一段进去。
        fileType.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                load(current.isEmpty() ? initialDir() : current);
            }
        });
        return root;
    }

    private JPanel buildTop() {
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.add(new JLabel(I18n.t(Strings.DIR_LOOK_IN)), BorderLayout.WEST);
        lookIn.setEditable(true);
        lookIn.setPreferredSize(new Dimension(300, lookIn.getPreferredSize().height));
        top.add(lookIn, BorderLayout.CENTER);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        tools.add(up);
        tools.add(home);
        tools.add(mkdir);
        tools.add(viewList);
        tools.add(viewDetails);
        top.add(tools, BorderLayout.EAST);
        return top;
    }

    private JPanel buildMiddle() {
        list.setSelectionMode(multi
                ? ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
                : ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new RowRenderer());
        list.setVisibleRowCount(14);
        JPanel mid = new JPanel(new BorderLayout(0, 4));
        mid.add(new JScrollPane(list), BorderLayout.CENTER);
        mid.add(status, BorderLayout.SOUTH);
        return mid;
    }

    private JPanel buildBottom() {
        JPanel form = new JPanel(new java.awt.GridLayout(2, 1, 0, 4));
        JPanel r1 = new JPanel(new BorderLayout(6, 0));
        r1.add(new JLabel(I18n.t(Strings.FILE_NAME_LABEL)), BorderLayout.WEST);
        r1.add(fileName, BorderLayout.CENTER);
        JPanel r2 = new JPanel(new BorderLayout(6, 0));
        r2.add(new JLabel(I18n.t(Strings.DIR_FILE_TYPE)), BorderLayout.WEST);
        fileType.setEditable(true);
        r2.add(fileType, BorderLayout.CENTER);
        form.add(r1);
        form.add(r2);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.add(open);
        actions.add(cancel);
        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.add(form, BorderLayout.CENTER);
        bottom.add(actions, BorderLayout.SOUTH);
        return bottom;
    }

    /** 当前"文件类型"框里的过滤词；"所有文件"等于不过滤。 */
    private String filterWord() {
        Object v = fileType.isEditable()
                ? fileType.getEditor().getItem() : fileType.getSelectedItem();
        String s = v == null ? "" : v.toString().trim();
        if (s.isEmpty() || s.equals(I18n.t(Strings.FILE_TYPE_ALL))) {
            return "";
        }
        // "*.pep" 这种写法按后缀理解：去掉星号和点，留 "pep"。
        if (s.startsWith("*.")) {
            return s.substring(2);
        }
        if (s.startsWith("*")) {
            return s.substring(1);
        }
        return s;
    }

    private void showDetails(boolean want) {
        details = want;
        list.repaint();
        load(current.isEmpty() ? initialDir() : current);
    }

    /** 「打开」：选中目录就进去，选中文件就带走；保存模式下用"文件名"框。 */
    private void accept() {
        List<Row> sel = list.getSelectedValuesList();
        if (save) {
            String name = fileName.getText() == null ? "" : fileName.getText().trim();
            if (!name.isEmpty()) {
                result = new ArrayList<String>();
                result.add(name.startsWith("/")
                        ? name : LinuxDirList.childOf(current, name));
                dialog.dispose();
            }
            return;
        }
        // 目录参数：选中的文件夹就是<b>值</b>，不是"进去"。
        //
        // 这是被用户指出来的：`-b <dir>`、`-f <dir>` 这类参数要的就是一个目录，可选中
        // 文件夹被一律当成"进去"，于是目录参数根本拿不到值，只能选文件。
        //
        // 什么都没选时给当前这一层，这样"就在这个目录"也能表达——和选工作目录那个
        // 窗口一致。
        if (directory) {
            List<String> dirPicked = new ArrayList<String>();
            for (Row r : sel) {
                if (r.dir) {
                    dirPicked.add(LinuxDirList.childOf(current, r.name));
                } else {
                    // 选中的是文件：它所在的目录才是这个参数要的东西。
                    dirPicked.add(current);
                }
            }
            if (dirPicked.isEmpty()) {
                dirPicked.add(current);
            }
            result = dirPicked;
            dialog.dispose();
            return;
        }

        List<String> picked = new ArrayList<String>();
        for (Row r : sel) {
            if (!r.dir) {
                picked.add(LinuxDirList.childOf(current, r.name));
            }
        }
        if (picked.isEmpty()) {
            // 文件模式下，选中文件夹＝进去，这是 Windows 的行为。
            Row r = sel.isEmpty() ? null : sel.get(0);
            if (r != null && r.dir) {
                load(LinuxDirList.childOf(current, r.name));
            } else {
                status.setText(I18n.t(Strings.FILE_NO_FILES));
            }
            return;
        }
        result = picked;
        dialog.dispose();
    }

    private void makeDirectory() {
        if (transport == null) {
            return;
        }
        String name = javax.swing.JOptionPane.showInputDialog(dialog,
                I18n.t(Strings.DIR_MKDIR_PROMPT), I18n.t(Strings.DIR_MKDIR),
                javax.swing.JOptionPane.PLAIN_MESSAGE);
        if (name == null) {
            return;
        }
        final String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.indexOf('/') >= 0) {
            javax.swing.JOptionPane.showMessageDialog(dialog,
                    I18n.t(Strings.DIR_MKDIR_BAD_NAME), I18n.t(Strings.DIR_MKDIR),
                    javax.swing.JOptionPane.WARNING_MESSAGE);
            return;
        }
        final String full = LinuxDirList.childOf(current, trimmed);
        new javax.swing.SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                transport.runBashCapture(LinuxDirList.mkdirScript(full));
                return null;
            }

            @Override
            protected void done() {
                load(full);
            }
        }.execute();
    }

    private void load(final String dir) {
        if (transport == null) {
            status.setText(I18n.t(Strings.DIR_NO_CHANNEL));
            return;
        }
        busy(true);
        status.setText(I18n.t(Strings.DIR_LOADING));
        final String word = filterWord();
        final boolean wantDetails = details;
        new javax.swing.SwingWorker<LinuxDirList.Listing, Void>() {
            @Override
            protected LinuxDirList.Listing doInBackground() {
                Transport.Result r = transport.runBashCapture(wantDetails
                        ? LinuxDirList.filesDetailsScript(dir, word)
                        : LinuxDirList.filesScript(dir, word));
                return wantDetails
                        ? LinuxDirList.parseFilesDetails(r.stdout(), r.stderr(),
                                r.exitCode())
                        : LinuxDirList.parseFiles(r.stdout(), r.stderr(), r.exitCode());
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
                remember(current);
                model.clear();
                // 文件夹在前、文件在后，和 Windows 一样。
                for (String d : got.dirs) {
                    Row r = new Row(d, true);
                    fillDetail(r, got, d, true);
                    model.addElement(r);
                }
                for (String f : got.files) {
                    Row r = new Row(f, false);
                    fillDetail(r, got, f, false);
                    model.addElement(r);
                }
                fileName.setText(current);
                status.setText(model.isEmpty()
                        ? I18n.t(Strings.FILE_NO_FILES)
                        : I18n.t(Strings.FILE_COUNT,
                                Integer.valueOf(got.files.size())));
            }
        }.execute();
    }

    /** 详细信息视图里把权限/时间填进对应那一行。 */
    private static void fillDetail(Row row, LinuxDirList.Listing got, String name,
                                   boolean dir) {
        for (LinuxDirList.Entry e : got.entries) {
            if (got.dirs.contains(e.name) == dir && e.name.equals(name)) {
                row.perms = e.perms;
                row.modified = e.modified;
                return;
            }
        }
    }

    /** 把走过的目录记进「查找」的下拉里，回头一步就能到。 */
    private void remember(String dir) {
        if (dir == null || dir.isEmpty()) {
            return;
        }
        for (int i = 0; i < lookIn.getItemCount(); i++) {
            if (dir.equals(lookIn.getItemAt(i))) {
                return;
            }
        }
        lookIn.addItem(dir);
        lookIn.setSelectedItem(dir);
    }

    private void busy(boolean on) {
        up.setEnabled(!on);
        home.setEnabled(!on);
        mkdir.setEnabled(!on);
        viewList.setEnabled(!on);
        viewDetails.setEnabled(!on);
        open.setEnabled(!on);
    }

    /**
     * 一行：图标 + 名字；详细信息视图在名字后面接上权限和时间。
     *
     * <p>图标是<b>画出来</b>的，和别处一样不依赖图片资源。文件夹与文件必须一眼分得开
     * ——第一版两者长得一样，被指出来了。
     */
    private final class RowRenderer extends javax.swing.DefaultListCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public java.awt.Component getListCellRendererComponent(
                javax.swing.JList<?> l, Object value, int index,
                boolean selected, boolean focused) {
            super.getListCellRendererComponent(l, value, index, selected, focused);
            Row r = (Row) value;
            setIcon(r.dir ? Icons.FOLDER : Icons.FILE);
            setIconTextGap(6);
            setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            if (details && !r.perms.isEmpty()) {
                setText(r.name + "      " + r.perms + "      " + r.modified);
            } else {
                setText(r.name);
            }
            return this;
        }
    }

    /** 两个小图标，画出来的。 */
    private static final class Icons {
        static final javax.swing.Icon FOLDER = new Mark(true);
        static final javax.swing.Icon FILE = new Mark(false);

        private static final class Mark implements javax.swing.Icon {
            private final boolean folder;

            Mark(boolean folder) {
                this.folder = folder;
            }

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
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                            java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                    if (folder) {
                        g2.setColor(new java.awt.Color(0xF2, 0xC0, 0x3C));
                        g2.fillRect(x + 2, y + 3, 6, 3);
                        g2.setColor(new java.awt.Color(0xFF, 0xD1, 0x54));
                        g2.fillRect(x + 1, y + 5, 13, 8);
                        g2.setColor(new java.awt.Color(0xD8, 0x9E, 0x14));
                        g2.drawRect(x + 1, y + 5, 13, 8);
                    } else {
                        g2.setColor(java.awt.Color.WHITE);
                        g2.fillRect(x + 3, y + 2, 10, 12);
                        g2.setColor(new java.awt.Color(0x7A, 0x7A, 0x7A));
                        g2.drawRect(x + 3, y + 2, 10, 12);
                        // 折角：一个小三角，看起来才像一张纸
                        g2.setColor(new java.awt.Color(0xD0, 0xD0, 0xD0));
                        g2.fillPolygon(new int[]{x + 9, x + 13, x + 13},
                                new int[]{y + 2, y + 2, y + 6}, 3);
                    }
                } finally {
                    g2.dispose();
                }
            }
        }
    }
}
