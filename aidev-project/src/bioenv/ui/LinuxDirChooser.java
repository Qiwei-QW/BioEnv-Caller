package bioenv.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

import bioenv.core.LinuxDirList;
import bioenv.core.Transport;
import bioenv.i18n.I18n;
import bioenv.i18n.Strings;

/**
 * 浏览 Linux 侧的目录：布局照 Windows 的「打开」窗口来。
 *
 * <pre>
 *   查找(I):  [ /home/you                       v ] [↑][⌂][＋][列表][详细]
 *   ┌────────────────────────────────────────────────┐
 *   │  📁 A          📁 EndNote                       │
 *   │  📁 Adobe      📁 GitHub                        │
 *   └────────────────────────────────────────────────┘
 *   文件夹名(N): [ /home/you                    ]
 *   文件类型(T): [ 文件夹                      v ]
 *                                       [打开] [取消]
 * </pre>
 *
 * <h2>为什么要做成这个样子</h2>
 *
 * <p>工作目录是<b>远端</b> Linux 上的一条路径，而原来那一行上的按钮要么选的是本机
 * Windows 的目录（WSL 下能用，因为 {@code /mnt/c} 是通往 Windows 的共享；SSH 下毫无
 * 意义），要么只会自动建一个带时间戳的目录。用户没法浏览、没法选已有的目录。
 *
 * <p>版式照着 Windows 那个窗口来，是因为用户已经会用它了——向上、主目录、新建、
 * 列表/详细信息这几个位置和他的肌肉记忆一致，不用重新学。
 *
 * <h2>WSL 与 SSH 共用同一套</h2>
 *
 * <p>列目录走 {@link Transport#runBashCapture}，两种通道都实现了它，所以这里不需要
 * 知道自己连的是哪一种。脚本与解析放在 {@link LinuxDirList}——那些是纯函数，可以无头
 * 断言；这里只负责画窗口、接结果。
 *
 * <h2>读取一律异步</h2>
 *
 * <p>SSH 一次往返可能要几秒，放在事件线程上整个界面会定住。所以每次读取都交给
 * {@link javax.swing.SwingWorker}，读取期间把按钮禁掉并显示「正在读取…」。
 */
public final class LinuxDirChooser {

    private final Transport transport;
    private final String startDir;

    private final JDialog dialog;
    private final JTextField lookIn = new JTextField(28);
    private final JTextField folderName = new JTextField(28);
    private final JComboBox<String> fileType = new JComboBox<String>(
            new String[]{I18n.t(Strings.DIR_TYPE_FOLDERS)});

    private final DefaultListModel<String> iconModel = new DefaultListModel<String>();
    private final JList<String> iconList = new JList<String>(iconModel);
    private final JScrollPane iconPane = new JScrollPane(iconList);

    private final DefaultTableModel detailModel = new DefaultTableModel(
            new Object[]{I18n.t(Strings.DIR_COL_NAME), I18n.t(Strings.DIR_COL_PERMS),
                    I18n.t(Strings.DIR_COL_MODIFIED)}, 0) {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable detailTable = new JTable(detailModel);
    private final JScrollPane detailPane = new JScrollPane(detailTable);

    private final JLabel status = new JLabel(" ");
    private final JButton up = iconButton(Strings.DIR_UP);
    private final JButton home = iconButton(Strings.DIR_HOME);
    private final JButton mkdir = iconButton(Strings.DIR_MKDIR);
    private final JButton viewList = iconButton(Strings.DIR_VIEW_LIST);
    private final JButton viewDetails = iconButton(Strings.DIR_VIEW_DETAILS);

    private final JButton open = new JButton(I18n.t(Strings.DIR_OPEN));
    private final JButton cancel = new JButton(I18n.t(Strings.DIR_CANCEL));

    /** 当前所在目录（解析后的绝对路径）。 */
    private String current = "";
    /** 用户选定的目录；取消时为 null。 */
    private String result;
    /**
     * 中间那块放列表/详情的地方。
     *
     * <p>存成字段，而不是每次从 {@code dialog.getContentPane().getComponent(1)} 里按序号
     * 捞。原来就是那样写的，结果 <b>build() 自己调 showDetails() 时崩了</b>：
     * {@code setContentPane(build())} 还没返回，内容面板还是默认的空壳，第 1 个子组件
     * 压根不存在 —— {@code ArrayIndexOutOfBoundsException: No such child: 1}。
     * 按序号取组件本来就脆，版式一改就错位。
     */
    private JPanel midPanel;

    /** 详细信息视图。 */
    private boolean details;

    private LinuxDirChooser(Transport transport, java.awt.Component parent,
                            String startDir) {
        this.transport = transport;
        this.startDir = startDir;
        dialog = new JDialog((java.awt.Frame) null, I18n.t(Strings.DIR_TITLE), true);
        dialog.setName("bioenv.linuxDirChooser");
        dialog.setContentPane(build());
        dialog.pack();
        dialog.setMinimumSize(new Dimension(560, 420));
        dialog.setLocationRelativeTo(parent);
    }

    /**
     * 弹出对话框并返回用户选的路径；取消或没有窗口时返回 null。
     *
     * @param transport 当前通道（WSL 或 SSH），用它的 {@code runBashCapture} 列目录
     * @param parent    父窗口，仅用于定位
     * @param startDir  初始目录；为空时用约定的工作根目录
     */
    public static String open(Transport transport, java.awt.Component parent,
                              String startDir) {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            // 无头环境没有窗口。返回 null 让调用方保持原值，而不是假装选了什么。
            return null;
        }
        LinuxDirChooser c = new LinuxDirChooser(transport, parent, startDir);
        c.load(c.initialDir());
        c.dialog.setVisible(true);
        return c.result;
    }

    /**
     * 初始目录：调用方给了就用它，否则从<b>用户主目录</b>开始。
     *
     * <p>原来默认落在 {@code ~/vmadapt_work}（那个自动建工作目录的约定路径）。那是错的：
     * 用户在这里是要<b>挑</b>一个目录，不是接受一个已经替他选好的。一进来就钻进
     * {@code vmadapt_work}，等于把"你想放哪"这个决定替他做了，他还得先往上退一层才
     * 看得见别的目录。
     *
     * <p>从主目录开始则是中立的：{@code vmadapt_work} 就在列表里，想用它点一下就行，
     * 想用别的也一眼看得见。而且 {@code ~} 是交给远端展开的，所以"qw"这种每台机器都
     * 不一样的用户名不需要我们知道。
     */
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

        open.setName("bioenv.dirOpen");
        cancel.setName("bioenv.dirCancel");
        mkdir.setName("bioenv.dirMkdir");
        up.setName("bioenv.dirUp");
        home.setName("bioenv.dirHome");
        viewList.setName("bioenv.dirViewList");
        viewDetails.setName("bioenv.dirViewDetails");

        open.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // 「打开」＝进到选中的子目录；没选中就是选中当前这一层。
                String sel = selectedName();
                if (sel == null) {
                    result = current;
                    dialog.dispose();
                } else {
                    load(LinuxDirList.childOf(current, sel));
                }
            }
        });
        cancel.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                result = null;
                dialog.dispose();
            }
        });
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
        // 双击进入目录，和 Windows 那个窗口一致。
        MouseAdapter enter = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String sel = selectedName();
                    if (sel != null) {
                        load(LinuxDirList.childOf(current, sel));
                    }
                }
            }
        };
        iconList.addMouseListener(enter);
        detailTable.addMouseListener(enter);
        // 选中列表里的目录时，把它显示到「文件夹名」里——这是那个窗口的行为，
        // 用户在按下「打开」之前就能看清自己选的是哪个。
        iconList.addListSelectionListener(
                new javax.swing.event.ListSelectionListener() {
                    @Override
                    public void valueChanged(javax.swing.event.ListSelectionEvent e) {
                        if (!e.getValueIsAdjusting()) {
                            syncFolderNameFromSelection();
                        }
                    }
                });
        detailTable.getSelectionModel().addListSelectionListener(
                new javax.swing.event.ListSelectionListener() {
                    @Override
                    public void valueChanged(javax.swing.event.ListSelectionEvent e) {
                        if (!e.getValueIsAdjusting()) {
                            syncFolderNameFromSelection();
                        }
                    }
                });
        lookIn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                load(lookIn.getText());
            }
        });
        showDetails(false);
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
        icons.add(viewList);
        icons.add(viewDetails);
        top.add(icons, BorderLayout.EAST);
        return top;
    }

    private JPanel buildMiddle() {
        midPanel = new JPanel(new BorderLayout(0, 4));
        iconList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // 图标视图：多列铺开，像 Windows 那个窗口一样。
        iconList.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        iconList.setVisibleRowCount(-1);
        iconList.setFixedCellWidth(160);
        detailTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        midPanel.add(iconPane, BorderLayout.CENTER);
        midPanel.add(status, BorderLayout.SOUTH);
        return midPanel;
    }

    private JPanel buildBottom() {
        JPanel form = new JPanel(new GridLayout(2, 1, 0, 4));
        JPanel r1 = new JPanel(new BorderLayout(6, 0));
        r1.add(new JLabel(I18n.t(Strings.DIR_FOLDER_NAME)), BorderLayout.WEST);
        r1.add(folderName, BorderLayout.CENTER);
        JPanel r2 = new JPanel(new BorderLayout(6, 0));
        r2.add(new JLabel(I18n.t(Strings.DIR_FILE_TYPE)), BorderLayout.WEST);
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

    /** 选中项的目录名；没选中返回 null。 */
    private String selectedName() {
        if (details) {
            int row = detailTable.getSelectedRow();
            if (row < 0) {
                return null;
            }
            Object v = detailModel.getValueAt(row, 0);
            return v == null ? null : v.toString();
        }
        return iconList.getSelectedValue();
    }

    private void syncFolderNameFromSelection() {
        String sel = selectedName();
        folderName.setText(sel == null ? current : LinuxDirList.childOf(current, sel));
    }

    /**
     * 在列表视图与详细信息视图之间切换。
     *
     * <p>只动 {@link #midPanel} 自己的子组件，<b>不</b>去 {@code dialog.getContentPane()}
     * 里按序号捞组件——那正是崩溃的原因：{@code build()} 自己会调到这里，而那一刻
     * {@code setContentPane(build())} 还没返回，内容面板仍是默认空壳，第 1 个子组件
     * 不存在。
     *
     * <p>还没建好时就只记下选择：真正的摆放由 {@code buildMiddle()} 完成。这样"先记意图、
     * 后落地"的顺序对任何调用时机都成立。
     */
    private void showDetails(boolean want) {
        details = want;
        if (midPanel == null) {
            return;
        }
        midPanel.removeAll();
        midPanel.add(want ? detailPane : iconPane, BorderLayout.CENTER);
        midPanel.add(status, BorderLayout.SOUTH);
        midPanel.revalidate();
        midPanel.repaint();
        load(current.isEmpty() ? initialDir() : current);
    }

    private void load(final String dir) {
        if (transport == null) {
            status.setText(I18n.t(Strings.DIR_NO_CHANNEL));
            return;
        }
        busy(true);
        status.setText(I18n.t(Strings.DIR_LOADING));
        final boolean wantDetails = details;
        new javax.swing.SwingWorker<LinuxDirList.Listing, Void>() {
            @Override
            protected LinuxDirList.Listing doInBackground() {
                Transport.Result r = transport.runBashCapture(wantDetails
                        ? LinuxDirList.detailsScript(dir)
                        : LinuxDirList.script(dir));
                return wantDetails
                        ? LinuxDirList.parseDetails(r.stdout(), r.stderr(), r.exitCode())
                        : LinuxDirList.parse(r.stdout(), r.stderr(), r.exitCode());
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
                iconModel.clear();
                detailModel.setRowCount(0);
                for (String d : got.dirs) {
                    iconModel.addElement(d);
                }
                for (LinuxDirList.Entry e : got.entries) {
                    detailModel.addRow(new Object[]{e.name, e.perms, e.modified});
                }
                folderName.setText(current);
                status.setText(got.dirs.isEmpty()
                        ? I18n.t(Strings.DIR_EMPTY)
                        : I18n.t(Strings.DIR_COUNT, Integer.valueOf(got.dirs.size())));
            }
        }.execute();
    }

    private void makeDirectory() {
        if (transport == null) {
            return;
        }
        String name = JOptionPane.showInputDialog(dialog,
                I18n.t(Strings.DIR_MKDIR_PROMPT), I18n.t(Strings.DIR_MKDIR),
                JOptionPane.PLAIN_MESSAGE);
        if (name == null) {
            return;
        }
        final String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.indexOf('/') >= 0) {
            // 带斜杠它就不是"一个名字"而是一段路径了。这个对话框一次建一层，
            // 免得用户以为能一口气建出多层。
            JOptionPane.showMessageDialog(dialog,
                    I18n.t(Strings.DIR_MKDIR_BAD_NAME), I18n.t(Strings.DIR_MKDIR),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        final String full = LinuxDirList.childOf(current, trimmed);
        busy(true);
        new javax.swing.SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                transport.runBashCapture(LinuxDirList.mkdirScript(full));
                return null;
            }

            @Override
            protected void done() {
                busy(false);
                // 建完直接进去：用户建目录就是为了用它，让他再找一次没有意义。
                // 失败也不会静默——load 会把真实的失败原因显示出来。
                load(full);
            }
        }.execute();
    }

    private void busy(boolean on) {
        up.setEnabled(!on);
        home.setEnabled(!on);
        mkdir.setEnabled(!on);
        viewList.setEnabled(!on);
        viewDetails.setEnabled(!on);
        open.setEnabled(!on);
    }

    /** 供自检读取文案，不打开窗口。 */
    static String[] selfCheckTexts() {
        return new String[]{
            I18n.t(Strings.DIR_TITLE),
            I18n.t(Strings.DIR_LOOK_IN),
            I18n.t(Strings.DIR_FOLDER_NAME),
            I18n.t(Strings.DIR_FILE_TYPE),
            I18n.t(Strings.DIR_UP),
            I18n.t(Strings.DIR_HOME),
            I18n.t(Strings.DIR_MKDIR),
            I18n.t(Strings.DIR_VIEW_LIST),
            I18n.t(Strings.DIR_VIEW_DETAILS),
            I18n.t(Strings.DIR_OPEN),
            I18n.t(Strings.DIR_CANCEL),
        };
    }
}
