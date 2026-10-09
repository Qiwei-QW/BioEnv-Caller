package vmadapt.ui;

import java.util.HashMap;
import java.util.Map;

/**
 * vmadapt 界面的双语文案表。
 *
 * <p>这里存放所有面向用户的文字，中英各一份，用键名索引。
 * 单独成类的原因：文案集中在一处才看得见全貌，也才有可能交给别人校对；
 * 散落在几十个 {@code new JButton("...")} 里时，漏译和口径不一致是必然的。
 *
 * <h2>回退顺序</h2>
 *
 * <p>{@link #get(String, String)} 的回退顺序是
 * 「按语言取 → 英文 → 键名本身」。最后回退到键名是为了让漏译一眼可见
 * （界面上出现 {@code btn.test} 这种字样，而不是一片空白或 "null"）。
 *
 * <h2>技术标识符不译</h2>
 *
 * <p>{@code ssh.exe}、{@code ~/.ssh}、{@code authorized_keys}、
 * {@code 127.0.0.1}、{@code VMnet1}、{@code 2222}、{@code chmod 700} 等
 * 命令、路径、端口、权限数字在两种语言里<b>原样保留</b>，这不是偷懒：
 * 用户要照着这些字面量敲进终端，翻译它们等于制造错误。
 */
final class UiText {

    /** 语言代码：中文。 */
    static final String ZH = "zh";

    /** 语言代码：英文。 */
    static final String EN = "en";

    /** 英文文案。 */
    private static final Map<String, String> EN_MAP = new HashMap<String, String>();

    /** 中文文案。 */
    private static final Map<String, String> ZH_MAP = new HashMap<String, String>();

    private UiText() {
        // 纯静态工具类。
    }

    /**
     * 取文案。
     *
     * @param key    键名
     * @param code   语言代码，{@code "zh"} 表示中文，其余（含 null）按英文处理
     * @return 文案；永不返回 null
     */
    static String get(String key, String code) {
        if (key == null) {
            return "";
        }
        if (ZH.equals(code)) {
            String zh = ZH_MAP.get(key);
            if (zh != null) {
                return zh;
            }
        }
        String en = EN_MAP.get(key);
        if (en != null) {
            return en;
        }
        String zh = ZH_MAP.get(key);
        if (zh != null) {
            return zh;
        }
        // 两边都没有：回退到键名。难看，但可搜索、能一眼认出是漏译。
        return key;
    }

    /** 该键是否同时有中英两版（自检用）。 */
    static boolean hasBoth(String key) {
        return EN_MAP.containsKey(key) && ZH_MAP.containsKey(key);
    }

    /** 全部键名（自检用）。 */
    static java.util.Set<String> keys() {
        java.util.Set<String> all = new java.util.TreeSet<String>();
        all.addAll(EN_MAP.keySet());
        all.addAll(ZH_MAP.keySet());
        return all;
    }

    private static void en(String key, String text) {
        EN_MAP.put(key, text);
    }

    private static void zh(String key, String text) {
        ZH_MAP.put(key, text);
    }

    /** 中英一起登记，保证两边键名不会写歪。 */
    private static void put(String key, String english, String chinese) {
        EN_MAP.put(key, english);
        ZH_MAP.put(key, chinese);
    }

    // ==================================================================
    // 标签页
    // ==================================================================

    // 只有两页。「连接结果」那一页已删除：结果改由弹窗呈现，见
    // ConnectionPanel 构造函数里加标签页处的说明。
    static final String TAB_CONNECTION = "tab.connection";
    static final String TAB_NETWORK = "tab.network";

    static final String TAB_CONNECTION_TIP = "tab.connection.tip";
    static final String TAB_NETWORK_TIP = "tab.network.tip";

    // ==================================================================
    // 表单标签
    // ==================================================================

    static final String LBL_HOST = "lbl.host";
    static final String LBL_PORT = "lbl.port";
    static final String LBL_USER = "lbl.user";
    static final String LBL_KEY = "lbl.key";
    static final String LBL_NAME = "lbl.name";
    static final String LBL_NOTE = "lbl.note";
    static final String LBL_TIMEOUT = "lbl.timeout";
    static final String LBL_SAVED = "lbl.saved";

    // ==================================================================
    // 字段悬停提示
    // ==================================================================

    static final String TIP_HOST = "tip.host";
    static final String TIP_PORT = "tip.port";
    static final String TIP_USER = "tip.user";
    static final String TIP_KEY = "tip.key";
    static final String TIP_NAME = "tip.name";
    static final String TIP_NOTE = "tip.note";
    static final String TIP_TIMEOUT = "tip.timeout";
    static final String TIP_SAVED = "tip.saved";

    // ==================================================================
    // 输入框占位提示
    // ==================================================================

    static final String PH_HOST = "ph.host";
    static final String PH_USER = "ph.user";
    static final String PH_KEY = "ph.key";
    static final String PH_NAME = "ph.name";
    static final String PH_NOTE = "ph.note";

    // ==================================================================
    // 按钮
    // ==================================================================

    static final String BTN_BROWSE = "btn.browse";
    static final String BTN_LOAD = "btn.load";
    static final String BTN_TEST = "btn.test";
    static final String BTN_SAVE = "btn.save";
    static final String BTN_DELETE = "btn.delete";
    static final String BTN_GEN_KEY = "btn.genkey";
    static final String BTN_COPY_KEY = "btn.copykey";

    static final String BTN_BROWSE_TIP = "btn.browse.tip";
    static final String BTN_LOAD_TIP = "btn.load.tip";
    static final String BTN_TEST_TIP = "btn.test.tip";
    static final String BTN_SAVE_TIP = "btn.save.tip";
    static final String BTN_DELETE_TIP = "btn.delete.tip";
    static final String BTN_GEN_KEY_TIP = "btn.genkey.tip";
    static final String BTN_COPY_KEY_TIP = "btn.copykey.tip";

    // ==================================================================
    // 区块标题与提示
    // ==================================================================

    static final String SEC_CONNECTION = "sec.connection";
    static final String SEC_ACTIONS = "sec.actions";
    static final String SEC_NETWORK = "sec.network";
    static final String HINT_BACKGROUND = "hint.background";

    /**
     * 第一页的三个分组标题。
     *
     * <p>分组是为了回答"我现在在做哪一步"：一张表上八行输入加七个按钮，用户得自己
     * 判断先填什么、能点哪个。分开之后每组只讲一件事——这台机器是什么、怎么登录、
     * 这条配置叫什么。
     */
    static final String SEC_MACHINE = "sec.machine";
    static final String SEC_LOGIN = "sec.login";
    static final String SEC_SAVEAS = "sec.saveas";

    /** 按钮：测试连接进行中的文案。 */
    static final String BTN_TESTING = "btn.testing";

    /** 临时：评审用布局变体的文案。选定后保留需要的那些。 */
    static final String SEC_ADVANCED = "sec.advanced";
    static final String BTN_ADVANCED = "btn.advanced";
    static final String BTN_ADVANCED_HIDE = "btn.advancedHide";
    static final String HINT_TEST_FIRST = "hint.testFirst";

    /** 按钮：设置密钥（生成 + 安装到远端，一步完成）。 */
    static final String BTN_SETUP_KEY = "btn.setupKey";
    static final String BTN_SETUP_KEY_TIP = "btn.setupKey.tip";

    /** 错误：设置密钥时表单还没填全。 */
    static final String MSG_SETUP_NEED_FORM = "msg.setupNeedForm";

    /** 主面板：唯一入口按钮「连接虚拟机…」。 */
    static final String BTN_CONNECT = "btn.connect";
    static final String BTN_CONNECT_TIP = "btn.connect.tip";

    /** 主面板：还没有配置任何机器。 */
    static final String PANEL_NO_TARGET = "panel.noTarget";
    static final String PANEL_NO_TARGET_HINT = "panel.noTargetHint";
    static final String PANEL_READY_HINT = "panel.readyHint";

    // 向导各步的标题、提示与按钮。
    static final String DIALOG_CONNECTION_TITLE_WIZARD = "dialog.wizardTitle";
    static final String WIZ_HOST_TITLE = "wiz.host.title";
    static final String WIZ_HOST_HINT = "wiz.host.hint";
    static final String WIZ_PORT_TITLE = "wiz.port.title";
    static final String WIZ_PORT_HINT = "wiz.port.hint";
    static final String WIZ_PORT_PH = "wiz.port.ph";
    static final String WIZ_USER_TITLE = "wiz.user.title";
    static final String WIZ_USER_HINT = "wiz.user.hint";
    static final String WIZ_KEY_TITLE = "wiz.key.title";
    static final String WIZ_KEY_HINT = "wiz.key.hint";
    static final String WIZ_KEY_PH = "wiz.key.ph";
    static final String WIZ_KEY_DEFAULT = "wiz.key.default";
    static final String WIZ_CONFIRM_TITLE = "wiz.confirm.title";
    static final String WIZ_CONFIRM_HINT = "wiz.confirm.hint";
    static final String WIZ_PWD_TITLE = "wiz.pwd.title";
    static final String WIZ_PWD_HINT = "wiz.pwd.hint";
    static final String WIZ_PWD_NOTE = "wiz.pwd.note";
    static final String WIZ_WORKING = "wiz.working";
    static final String WIZ_OK_TITLE = "wiz.ok.title";
    static final String WIZ_OK_HINT = "wiz.ok.hint";
    static final String WIZ_NAME_NOTE = "wiz.name.note";
    static final String WIZ_FAIL_TITLE = "wiz.fail.title";
    static final String WIZ_FAIL_HINT = "wiz.fail.hint";
    static final String WIZ_MANUAL_TITLE = "wiz.manual.title";
    static final String WIZ_OLD_SSH_TITLE = "wiz.oldSsh.title";
    static final String WIZ_OLD_SSH_REASON = "wiz.oldSsh.reason";
    static final String WIZ_NEED_HOST = "wiz.needHost";
    static final String WIZ_NEED_USER = "wiz.needUser";
    static final String WIZ_NEED_PASSWORD = "wiz.needPassword";
    static final String BTN_NEXT = "btn.next";
    static final String BTN_BACK = "btn.back";
    static final String BTN_START = "btn.start";
    static final String BTN_CANCEL = "btn.cancel";
    static final String BTN_CLOSE = "btn.close";
    static final String BTN_SKIP = "btn.skip";

    /** 选择私钥文件的对话框。 */
    static final String DLG_CHOOSE_KEY = "dlg.choosekey";
    static final String FILTER_PRIVKEY = "dlg.filter.privkey";

    // ==================================================================
    // 状态区
    // ==================================================================

    static final String STATUS_NOT_TESTED = "status.nottested";
    static final String STATUS_INTRO = "status.intro";

    // ==================================================================
    // 连接过程
    // ==================================================================

    static final String MSG_INCOMPLETE = "msg.incomplete";
    static final String MSG_CONNECTING_TO = "msg.connectingto";
    static final String MSG_CONNECTING_BG = "msg.connectingbg";
    static final String MSG_CONNECT_THREW = "msg.connectthrew";
    static final String MSG_NULL_REPORT = "msg.nullreport";
    static final String MSG_NULL_REPORT_REASON = "msg.nullreport.reason";
    static final String MSG_CONNECTED = "msg.connected";
    static final String MSG_CONNECTED_SHORT = "msg.connected.short";
    static final String MSG_CONNECT_FAILED = "msg.connectfailed";
    static final String MSG_NO_REASON = "msg.noreason";

    static final String RPT_OS = "rpt.os";
    static final String RPT_KERNEL = "rpt.kernel";
    static final String RPT_HOME = "rpt.home";
    static final String RPT_UNRECOGNIZED = "rpt.unrecognized";
    static final String RPT_NOTES = "rpt.notes";
    static final String RPT_SUGGESTIONS = "rpt.suggestions";
    static final String RPT_NEXT = "rpt.next";
    static final String RPT_REASON = "rpt.reason";
    static final String RPT_UNKNOWN = "rpt.unknown";
    static final String RPT_SEE_NETWORK = "rpt.seenetwork";
    static final String RPT_RAW = "rpt.raw";

    static final String TIP_FALLBACK_1 = "tipfallback.1";
    static final String TIP_FALLBACK_2 = "tipfallback.2";
    static final String TIP_FALLBACK_3 = "tipfallback.3";
    static final String TIP_FALLBACK_4 = "tipfallback.4";
    static final String TIP_FALLBACK_5 = "tipfallback.5";

    static final String ERR_PORT_NUMBER = "err.portnumber";
    static final String ERR_TIMEOUT_NUMBER = "err.timeoutnumber";

    // ------------------------------------------------------------------
    // 连接参数校验（ConnectionPanel.validateLocalized）
    //
    // vmadapt.core.SshTarget.validate() 返回的是固定英文（core 是纯逻辑层，
    // 不认识界面语言），这里按同样的条件给出本地化文案。
    // ------------------------------------------------------------------

    static final String VAL_HOST_EMPTY = "val.hostempty";
    static final String VAL_HOST_SPACE = "val.hostspace";
    static final String VAL_HOST_PREFIX = "val.hostprefix";
    static final String VAL_HOST_AT = "val.hostat";
    static final String VAL_USER_EMPTY = "val.userempty";
    static final String VAL_PORT_RANGE = "val.portrange";
    static final String VAL_TIMEOUT_RANGE = "val.timeoutrange";
    static final String VAL_KEY_MISSING = "val.keymissing";

    // ==================================================================
    // 保存 / 删除 / 载入配置
    // ==================================================================

    static final String MSG_NAME_EMPTY = "msg.nameempty";
    static final String MSG_NAME_EMPTY_REASON = "msg.nameempty.reason";
    static final String MSG_NO_HOST = "msg.nohost";
    static final String MSG_NO_HOST_REASON = "msg.nohost.reason";
    static final String MSG_SAVE_FAILED = "msg.savefailed";
    static final String MSG_SAVE_REJECTED = "msg.saverejected";
    static final String MSG_SAVE_REJECTED_REASON = "msg.saverejected.reason";
    static final String MSG_SAVED_TITLE = "msg.saved.title";
    static final String MSG_SAVED_BODY = "msg.saved.body";
    static final String MSG_SAVED_TARGET = "msg.saved.target";
    static final String MSG_SAVED_KEY = "msg.saved.key";
    static final String MSG_SAVED_KEY_NONE = "msg.saved.keynone";

    static final String MSG_NO_SELECTION = "msg.noselection";
    static final String MSG_NO_SELECTION_DELETE = "msg.noselection.delete";
    static final String MSG_NO_SELECTION_LOAD = "msg.noselection.load";
    static final String MSG_CONFIRM_DELETE = "msg.confirmdelete";
    static final String MSG_CONFIRM_DELETE_TITLE = "msg.confirmdelete.title";
    static final String MSG_DELETE_FAILED = "msg.deletefailed";
    static final String MSG_DELETED_TITLE = "msg.deleted.title";
    static final String MSG_DELETED_BODY = "msg.deleted.body";
    static final String MSG_MISSING_TITLE = "msg.missing.title";
    static final String MSG_MISSING_DELETED = "msg.missing.deleted";
    static final String MSG_LOAD_FAILED = "msg.loadfailed";
    static final String MSG_MISSING = "msg.missing";
    static final String MSG_LOADED_TITLE = "msg.loaded.title";
    static final String MSG_LOADED_BODY = "msg.loaded.body";

    // ==================================================================
    // 密钥生成
    // ==================================================================

    static final String MSG_NO_KEYGEN = "msg.nokeygen";
    static final String MSG_NO_KEYGEN_REASON = "msg.nokeygen.reason";
    static final String MSG_NO_HOME = "msg.nohome";
    static final String MSG_NO_HOME_REASON = "msg.nohome.reason";
    static final String MSG_KEY_EXISTS = "msg.keyexists";
    static final String MSG_KEY_EXISTS_TITLE = "msg.keyexists.title";
    static final String MSG_KEY_DELETE_FAILED = "msg.keydeletefailed";
    static final String MSG_GEN_RUNNING = "msg.genrunning";
    static final String MSG_GEN_RUNNING_BODY = "msg.genrunning.body";
    static final String MSG_GEN_OUTFILES = "msg.gen.outfiles";
    static final String MSG_MKDIR_FAILED = "msg.mkdirfailed";
    static final String MSG_GEN_FAILED = "msg.genfailed";
    static final String MSG_GEN_FAILED_CODE = "msg.genfailed.code";
    static final String MSG_GEN_OK_TITLE = "msg.genok.title";
    static final String MSG_GEN_OK_BODY = "msg.genok.body";
    static final String MSG_GEN_PRIVKEY = "msg.gen.privkey";
    static final String MSG_GEN_PUBKEY = "msg.gen.pubkey";
    static final String MSG_GEN_PASSPHRASE = "msg.gen.passphrase";
    static final String MSG_GEN_TAIL = "msg.gen.tail";
    static final String MSG_GEN_OUTPUT = "msg.gen.output";

    static final String TIP_KEYGEN_1 = "tipkeygen.1";
    static final String TIP_KEYGEN_2 = "tipkeygen.2";
    static final String TIP_KEYGEN_3 = "tipkeygen.3";
    static final String TIP_KEYGEN_4 = "tipkeygen.4";

    // ==================================================================
    // 复制公钥
    // ==================================================================

    static final String MSG_INCOMPLETE_COPY = "msg.incompletecopy";
    static final String MSG_NO_PRIVKEY = "msg.noprivkey";
    static final String MSG_NO_PRIVKEY_REASON = "msg.noprivkey.reason";
    static final String MSG_NO_PUBFILE = "msg.nopubfile";
    static final String MSG_NO_PUBFILE_REASON = "msg.nopubfile.reason";
    static final String MSG_NO_SSH = "msg.nossh";
    static final String MSG_NO_SSH_REASON = "msg.nossh.reason";
    static final String MSG_CONFIRM_COPY = "msg.confirmcopy";
    static final String MSG_CONFIRM_COPY_TITLE = "msg.confirmcopy.title";
    static final String MSG_CONFIRM_COPY_PUB = "msg.confirmcopy.pub";
    static final String MSG_CONFIRM_COPY_TARGET = "msg.confirmcopy.target";
    static final String MSG_COPYING = "msg.copying";
    static final String MSG_COPYING_BODY = "msg.copying.body";
    static final String MSG_READ_PUB_FAILED = "msg.readpubfailed";
    static final String MSG_PUB_EMPTY = "msg.pubempty";
    static final String MSG_COPY_FAILED = "msg.copyfailed";
    static final String MSG_COPY_FAILED_CODE = "msg.copyfailed.code";
    static final String MSG_COPY_OK_TITLE = "msg.copyok.title";
    static final String MSG_COPY_OK_BODY = "msg.copyok.body";
    static final String MSG_COPY_OK_TAIL = "msg.copyok.tail";
    static final String MSG_CMD_OUTPUT = "msg.cmdoutput";

    static final String TIP_COPY_1 = "tipcopy.1";
    static final String TIP_COPY_2 = "tipcopy.2";
    static final String TIP_COPY_3 = "tipcopy.3";
    static final String TIP_COPY_4 = "tipcopy.4";
    static final String TIP_COPY_5 = "tipcopy.5";

    // ==================================================================
    // 执行失败
    // ==================================================================

    static final String MSG_EXEC_FAILED = "msg.execfailed";
    static final String MSG_EXEC_CMD = "msg.execcmd";

    /** 只当前缀用的短句，拼在异常或命令前面。 */
    static final String PFX_EXEC_FAILED = "pfx.execfailed";
    static final String PFX_EXEC_CMD = "pfx.execcmd";

    // ==================================================================
    // 独立验证窗口（MainFrame）
    // ==================================================================

    static final String FRAME_TITLE = "frame.title";
    static final String FRAME_HEAD_TITLE = "frame.head.title";
    static final String FRAME_HEAD_SUB = "frame.head.sub";
    static final String FRAME_GUIDE_BTN = "frame.guide.btn";
    static final String FRAME_GUIDE_TITLE = "frame.guide.title";
    static final String FRAME_GUIDE_FAILED = "frame.guide.failed";
    static final String FRAME_GUIDE_EMPTY = "frame.guide.empty";
    static final String FRAME_ENV = "frame.env";
    static final String FRAME_ENV_NO_SSH = "frame.env.nossh";

    // ==================================================================
    // 命令行用法与无头提示（MainFrame）
    // ==================================================================

    static final String USAGE_TITLE = "usage.title";
    static final String USAGE_LINE1 = "usage.line1";
    static final String USAGE_OPTS = "usage.opts";
    static final String USAGE_HOST = "usage.host";
    static final String USAGE_PORT = "usage.port";
    static final String USAGE_USER = "usage.user";
    static final String USAGE_KEY = "usage.key";
    static final String USAGE_NAME = "usage.name";
    static final String USAGE_HELP = "usage.help";
    static final String USAGE_EXAMPLE = "usage.example";

    static final String HL_BANNER = "hl.banner";
    static final String HL_TITLE = "hl.title";
    static final String HL_BODY = "hl.body";
    static final String HL_NOT_ERROR = "hl.noterror";
    static final String HL_CAUSES = "hl.causes";
    static final String HL_CAUSE_1 = "hl.cause1";
    static final String HL_CAUSE_2 = "hl.cause2";
    static final String HL_CAUSE_3 = "hl.cause3";
    static final String HL_TESTS = "hl.tests";
    static final String HL_TESTS_EXAMPLE = "hl.tests.example";
    static final String HL_RETRY = "hl.retry";

    // ==================================================================
    // Network modes 整段
    // ==================================================================

    static final String NET_HELP = "net.help";

    static {
        // 注意：Network modes 整段（NET_HELP_EN / NET_HELP_ZH）的登记**不在这里**。
        // 那两个字段声明在文件末尾（static 块之后），而在初始化器里引用声明在后面
        // 的字段属于「非法前向引用」，javac 会直接报错：它们不是编译期常量。
        // 它们的 put(...) 放在字段声明之后（见文件末尾）。
        put(TAB_CONNECTION, "Connection", "连接配置");
        put(TAB_NETWORK, "Network modes", "网络模式速查");

        put(TAB_CONNECTION_TIP,
                "Connection settings: host, port, user, key, profile name.",
                "连接参数：主机、端口、用户名、密钥路径、配置名称。");
        put(TAB_NETWORK_TIP,
                "How VMware NAT, bridged and host-only modes affect SSH.",
                "VMware 的 NAT、桥接、仅主机三种模式对 SSH 连接的影响。");

        put(LBL_HOST, "Host / IP:", "主机 / IP：");
        put(LBL_PORT, "Port:", "端口：");
        put(LBL_USER, "User:", "用户名：");
        put(LBL_KEY, "Private key:", "密钥路径：");
        put(LBL_NAME, "Profile name:", "配置名称：");
        put(LBL_NOTE, "Note:", "备注：");
        put(LBL_TIMEOUT, "Timeout (s):", "超时（秒）：");
        put(LBL_SAVED, "Saved profiles:", "已存配置：");

        put(TIP_HOST,
                "VM IP address or host name. Under VMware NAT, use 127.0.0.1 "
                        + "with a port forwarding rule in place.",
                "虚拟机的 IP 或主机名。VMware NAT 模式下填 127.0.0.1 并配好端口转发。");
        put(TIP_PORT,
                "SSH port, 22 by default. NAT port forwarding usually maps it to 2222.",
                "SSH 端口，默认 22。NAT 端口转发常映射成 2222。");
        put(TIP_USER,
                "Login user name, for example ubuntu, root or centos.",
                "登录用户名，例如 ubuntu、root、centos。");
        put(TIP_KEY,
                "Private key file (a local Windows path). Leave empty to use the "
                        + "default ssh keys or ssh-agent.",
                "私钥文件（Windows 本地路径）。留空则用 ssh 默认密钥或 ssh-agent。");
        put(TIP_NAME,
                "A name for this profile; it is the key used when saving, "
                        + "for example \"seq-analysis-box\".",
                "给这套配置起的别名，保存时用它做键，例如「测序分析机」。");
        put(TIP_NOTE,
                "Free-form note, kept for reference only. Not used for connecting.",
                "自由备注，只作备忘，不参与连接。");
        put(TIP_TIMEOUT,
                "Connection timeout. 15 seconds by default; when the network is "
                        + "unreachable the wait lasts that long.",
                "连接超时。默认 15 秒；网络不通时会一直等到这个时间。");
        put(TIP_SAVED,
                "Pick a saved profile and load it into the form.",
                "从本地配置里选一条载入到表单。");

        put(PH_HOST, "e.g. 192.168.1.100   (NAT: 127.0.0.1)",
                "例：192.168.1.100   （NAT 模式：127.0.0.1）");
        put(PH_USER, "e.g. ubuntu", "例：ubuntu");
        put(PH_KEY, "leave empty to use the default key", "留空则使用默认密钥");
        put(PH_NAME, "e.g. seq-analysis-box", "例：测序分析机");
        put(PH_NOTE, "optional", "可选");

        put(BTN_BROWSE, "Browse...", "浏览…");
        put(BTN_LOAD, "Load", "载入");
        put(BTN_TEST, "Test connection", "测试连接");
        put(BTN_SAVE, "Save", "保存配置");
        put(BTN_DELETE, "Delete", "删除配置");
        put(BTN_GEN_KEY, "Generate key", "生成密钥");
        put(BTN_COPY_KEY, "Copy public key to VM", "复制公钥到远端");

        put(BTN_BROWSE_TIP, "Pick the local private key file to use.",
                "选择本地私钥文件。");
        put(BTN_LOAD_TIP, "Load the selected saved profile into the form.",
                "把选中的已存配置读进表单。");
        put(BTN_TEST_TIP,
                "Connect once and report the result on the Result tab. Nothing is saved.",
                "真的连一次，并把结果写到「连接结果」页；不保存任何东西。");
        put(BTN_SAVE_TIP,
                "Save this profile to %USERPROFILE%\\.vmadapt\\connections.json. "
                        + "Passwords are never written to disk (key-based auth only).",
                "把这套配置存到 %USERPROFILE%\\.vmadapt\\connections.json。"
                        + "密码永不落盘（本方案只支持密钥）。");
        put(BTN_DELETE_TIP,
                "Delete the selected entry from Saved profiles. "
                        + "Disabled when nothing is selected.",
                "删除「已存配置」里选中的那一条。未选中时不可用。");
        put(BTN_GEN_KEY_TIP,
                "Run ssh-keygen to create a new key pair. This only creates "
                        + "the files; nothing is installed on the VM yet.",
                "调用 ssh-keygen 生成一对新密钥。只是生成文件，还没装到虚拟机上。");
        put(BTN_COPY_KEY_TIP,
                "Append the public key to the VM's ~/.ssh/authorized_keys "
                        + "so passwordless login works.",
                "把公钥追加到虚拟机的 ~/.ssh/authorized_keys，免密登录才会生效。");

        put(SEC_CONNECTION, "Connection", "连接参数");
        put(SEC_ACTIONS, "Actions", "操作");
        put(SEC_NETWORK, "VMware network modes", "VMware 网络模式速查");

        put(SEC_MACHINE, "This machine", "这台机器");
        put(SEC_LOGIN, "How to log in", "登录方式");
        put(SEC_SAVEAS, "Save / load a profile", "保存与载入配置");
        put(BTN_TESTING, "Connecting...", "正在连接…");

        put(SEC_ADVANCED, "Advanced", "高级");
        put(BTN_ADVANCED, "\u25b8 Advanced (timeout, profile name, keys)",
                "\u25b8 高级（超时、配置名称、密钥工具）");
        put(BTN_ADVANCED_HIDE, "\u25be Advanced (timeout, profile name, keys)",
                "\u25be 高级（超时、配置名称、密钥工具）");
        put(HINT_TEST_FIRST, "(test it first, then save)",
                "（先测通，再保存）");

        put(BTN_SETUP_KEY, "Set up key", "设置密钥");
        put(BTN_SETUP_KEY_TIP,
                "Generate a key if there is none, then install its public half "
                        + "on the VM so login needs no password. Uses the host, "
                        + "user and port filled in above.",
                "还没有密钥就生成一把，然后把公钥装到虚拟机上，之后登录免密。"
                        + "用上面填好的主机、用户名和端口。");
        put(MSG_SETUP_NEED_FORM, "Set up key needs these first:",
                "要设置密钥，请先填好：");

        put(BTN_CONNECT, "Connect to a VM...", "连接虚拟机…");
        put(BTN_CONNECT_TIP,
                "Opens a step-by-step wizard: address, port, user, key, "
                        + "then connect and save.",
                "打开一步步的向导：地址、端口、用户名、密钥，然后连接并保存。");
        put(PANEL_NO_TARGET, "No VM configured yet", "还没有配置虚拟机");
        put(PANEL_NO_TARGET_HINT,
                "Click 'Connect to a VM...' to set one up. It takes five short steps.",
                "点「连接虚拟机…」开始配置，一共五步。");
        put(PANEL_READY_HINT,
                "Click 'Connect to a VM...' to test the connection or switch machine.",
                "点「连接虚拟机…」可以测试连接，或换一台机器。");

        put(DIALOG_CONNECTION_TITLE_WIZARD, "Linux VM connection",
                "虚拟机连接配置");
        put(WIZ_HOST_TITLE, "Where is the VM?", "虚拟机在哪里？");
        put(WIZ_HOST_HINT,
                "Enter the VM's IP address. Run ip addr inside the VM to see it; "
                        + "it is usually 192.168.x.x.",
                "填虚拟机的 IP 地址。在虚拟机的终端里执行 ip addr 可以看到，"
                        + "通常是 192.168.x.x。");
        put(WIZ_PORT_TITLE, "Which port?", "用哪个端口？");
        put(WIZ_PORT_HINT,
                "SSH is 22 unless you changed it in the VM's sshd config.",
                "SSH 默认是 22。只有在虚拟机上改过 sshd 配置时才需要改这里。");
        put(WIZ_PORT_PH, "Default 22", "默认 22");
        put(WIZ_USER_TITLE, "Which account?", "用哪个账号登录？");
        put(WIZ_USER_HINT,
                "The login name inside the VM - the one you set when installing it, "
                        + "often ubuntu.",
                "虚拟机里你的登录名。装系统时设的那个，常见是 ubuntu。");
        put(WIZ_KEY_TITLE, "Which key?", "用哪个密钥？");
        put(WIZ_KEY_HINT,
                "Leave it blank and the plugin generates a dedicated key in ~/.ssh. "
                        + "If you already have your own key, give its full path.",
                "可以留空——插件会在 ~/.ssh 下生成一把专用密钥。"
                        + "已经有自己的密钥时，填它的完整路径。");
        put(WIZ_KEY_PH, "Leave blank for the default key (recommended)",
                "留空则使用默认密钥（推荐）");
        put(WIZ_KEY_DEFAULT, "(default key)", "（使用默认密钥）");
        put(WIZ_CONFIRM_TITLE, "Check before connecting", "确认一下");
        put(WIZ_CONFIRM_HINT,
                "This will create an SSH key pair on this computer. The private key "
                        + "stays here; the public key is appended to your VM.",
                "点「开始连接」后：会在本机生成 SSH 密钥对，私钥留在本机，"
                        + "公钥追加到虚拟机上。");
        put(WIZ_PWD_TITLE, "Enter the VM login password",
                "请输入虚拟机登录密码");
        put(WIZ_PWD_HINT,
                "This is the last time a password is needed. Once the public key is "
                        + "installed, connections use the key instead.",
                "这是最后一次需要密码。装上公钥之后，以后连接都用免密。");
        put(WIZ_PWD_NOTE,
                "Used once to install the public key. The password is not saved.",
                "只用于这一次安装公钥，密码不会被保存。");
        put(WIZ_WORKING, "Connecting, please wait...", "正在连接，请稍候…");
        put(WIZ_OK_TITLE, "Connected", "连接成功");
        put(WIZ_OK_HINT,
                "Give this machine a name so you can pick it next time.",
                "给这台机器起个名字，下次就能直接选它。");
        put(WIZ_NAME_NOTE,
                "Leave blank to use \"user@host:port\". You can rename it later.",
                "留空则用「用户名@主机:端口」。以后可以随时改。");
        put(WIZ_FAIL_TITLE, "Connection failed", "连接失败");
        put(WIZ_FAIL_HINT,
                "Nothing was changed on the VM. Check the reason below and try again.",
                "虚拟机上没有做任何改动。请对照下面的原因重试。");
        put(WIZ_MANUAL_TITLE, "OpenSSH is not usable on this computer",
                "这台电脑的 OpenSSH 不可用");
        put(WIZ_OLD_SSH_TITLE, "OpenSSH is too old for automatic setup",
                "OpenSSH 版本太旧，无法自动安装公钥");
        put(WIZ_OLD_SSH_REASON,
                "The plugin needs OpenSSH 8.4 or newer to supply a password "
                        + "without putting it on the command line. This computer has: {0}",
                "插件需要 OpenSSH 8.4 或更新版本，才能在不把密码写进命令行的前提下"
                        + "使用密码。这台电脑上是：{0}");
        put(WIZ_NEED_HOST, "Enter the VM's IP address first.",
                "请先填虚拟机的 IP 地址。");
        put(WIZ_NEED_USER, "Enter the login user name first.",
                "请先填登录用户名。");
        put(WIZ_NEED_PASSWORD, "Enter the password, or click Back.",
                "请输入密码，或点「上一步」返回。");
        put(BTN_NEXT, "Next", "下一步");
        put(BTN_BACK, "Back", "上一步");
        put(BTN_START, "Start connecting", "开始连接");
        put(BTN_CANCEL, "Cancel", "取消");
        put(BTN_CLOSE, "Close", "关闭");
        put(BTN_SKIP, "Skip", "跳过");
        put(HINT_BACKGROUND,
                "Tip: every long-running task runs on a background thread, "
                        + "so the window stays responsive while connecting.",
                "提示：所有耗时操作都在后台线程执行，连接期间界面不会卡住。");

        put(DLG_CHOOSE_KEY,
                "Select the SSH private key file (usually id_rsa, no .pub suffix)",
                "选择 SSH 私钥文件（通常是 id_rsa，没有 .pub 后缀）");
        put(FILTER_PRIVKEY,
                "Private key files (id_rsa, id_ed25519, ...)",
                "私钥文件（id_rsa / id_ed25519 等）");

        put(STATUS_NOT_TESTED, "Not tested yet.", "尚未测试连接。");
        put(STATUS_INTRO,
                "Click \"Test connection\" and this area shows the "
                        + "operating system, kernel version and home directory detected by "
                        + "vmadapt.core, along with troubleshooting advice when it fails.",
                "点「测试连接」后，这里会显示 vmadapt.core 探测到的操作系统、"
                        + "内核版本、家目录，以及失败时的排查建议。");

        put(MSG_INCOMPLETE, "Incomplete connection settings", "连接参数不完整");
        put(MSG_CONNECTING_TO, "Connecting to {0} ...", "正在连接 {0} …");
        put(MSG_CONNECTING_BG,
                "Connecting in the background, waiting up to {0} seconds.\n"
                        + "If nothing happens, check the \"Network modes\" tab first.",
                "正在后台连接，最长等待 {0} 秒。\n"
                        + "如果一直没反应，请先看「网络模式速查」页。");
        put(MSG_CONNECT_THREW, "The connection attempt threw an exception",
                "连接过程抛出异常");
        put(MSG_NULL_REPORT, "The probe returned no result", "探测没有返回结果");
        put(MSG_NULL_REPORT_REASON, "vmadapt.core returned a null report.",
                "vmadapt.core 返回了 null 报告。");
        put(MSG_CONNECTED, "Connected: {0}", "连接成功：{0}");
        put(MSG_CONNECTED_SHORT, "Connected - {0}", "连接成功 — {0}");
        put(MSG_CONNECT_FAILED, "Connection failed - {0}", "连接失败 — {0}");
        put(MSG_NO_REASON, "No specific reason was given.", "没有给出具体原因。");

        put(RPT_OS, "Operating system : {0}", "操作系统   : {0}");
        put(RPT_KERNEL, "Kernel           : {0}", "内核版本   : {0}");
        put(RPT_HOME, "Remote home dir  : {0}", "远端家目录 : {0}");
        put(RPT_UNRECOGNIZED, "(unrecognized)", "(未识别)");
        put(RPT_NOTES, "Notes", "提示");
        put(RPT_SUGGESTIONS, "Suggestions", "建议");
        put(RPT_NEXT,
                "Next: click \"Copy public key to VM\" for passwordless login, "
                        + "or use the \"Network modes\" tab to confirm the transport path.",
                "下一步：可以点「复制公钥到远端」免密登录，"
                        + "或用「网络模式速查」确认传输路径。");
        put(RPT_REASON, "Reason: {0}", "原因：{0}");
        put(RPT_UNKNOWN, "unknown", "未知");
        put(RPT_SEE_NETWORK,
                "See the \"Network modes\" tab for detailed troubleshooting.",
                "详细排查请看「网络模式速查」页。");
        put(RPT_RAW,
                "Raw output from the remote command:",
                "远端命令的原始输出：");

        put(TIP_FALLBACK_1,
                "Check that the VM's IP, port and user name are correct and that "
                        + "the VM is powered on.",
                "确认虚拟机的 IP、端口、用户名填对了，虚拟机处于开机状态。");
        put(TIP_FALLBACK_2,
                "In NAT mode you must forward port 22 to a host port (2222 is "
                        + "common) first, and then enter 127.0.0.1 as the host.",
                "NAT 模式必须先把 22 端口转发到宿主机端口（常见 2222），"
                        + "然后主机填 127.0.0.1。");
        put(TIP_FALLBACK_3,
                "In host-only mode, remember that the Windows firewall blocks "
                        + "inbound connections on VMnet1.",
                "仅主机模式注意 Windows 防火墙会拦截 VMnet1 的入站连接。");
        put(TIP_FALLBACK_4,
                "Run systemctl status ssh on the VM to confirm sshd is running.",
                "在虚拟机里执行 systemctl status ssh，确认 sshd 已启动。");
        put(TIP_FALLBACK_5,
                "If public key authentication fails, check that the private key "
                        + "path is correct and that authorized_keys already contains the "
                        + "matching public key.",
                "公钥认证失败时，检查私钥路径是否正确、"
                        + "以及 authorized_keys 是否已包含对应公钥。");

        put(ERR_PORT_NUMBER,
                "The port must be a number; the current value is \"{0}\".",
                "端口必须是数字，当前填的是「{0}」。");
        put(ERR_TIMEOUT_NUMBER,
                "The timeout must be a number of seconds; the current value is \"{0}\".",
                "超时必须是数字（秒），当前填的是「{0}」。");

        // 与 vmadapt.core.SshTarget.validate() 的各分支一一对应。
        put(VAL_HOST_EMPTY,
                "Enter the VM's host name or IP address.",
                "请填写虚拟机的主机名或 IP 地址。");
        put(VAL_HOST_SPACE,
                "The host name must not contain spaces: {0}",
                "主机名中不能包含空格：{0}");
        put(VAL_HOST_PREFIX,
                "Do not include the ssh:// prefix in the host name - enter just the IP "
                        + "or domain, for example 192.168.1.100.",
                "主机名里不要带 ssh:// 前缀，请只填 IP 或域名，例如 192.168.1.100。");
        put(VAL_HOST_AT,
                "Do not include the user name in the host name - split {0} into the "
                        + "user name and host fields.",
                "主机名里不要带用户名，请把 {0} 拆成用户名和主机两栏。");
        put(VAL_USER_EMPTY,
                "Enter the login user name, for example ubuntu or root.",
                "请填写登录用户名，例如 ubuntu 或 root。");
        put(VAL_PORT_RANGE,
                "The port must be between 1 and 65535; the current value is {0}.",
                "端口号必须在 1 到 65535 之间，当前是 {0}。");
        put(VAL_TIMEOUT_RANGE,
                "The connection timeout must be between 1 and 600 seconds; the current "
                        + "value is {0}.",
                "连接超时必须在 1 到 600 秒之间，当前是 {0}。");
        put(VAL_KEY_MISSING,
                "Private key file not found: {0}. Check that the path is correct (use "
                        + "backslashes or forward slashes for Windows paths).",
                "找不到私钥文件：{0}。请确认路径正确（Windows 路径请用反斜杠或正斜杠）。");

        put(MSG_NAME_EMPTY, "The profile name is empty", "配置名称为空");
        put(MSG_NAME_EMPTY_REASON,
                "Saving a profile requires a \"Profile name\" first; it identifies "
                        + "this entry in the local profile store.",
                "保存配置需要先填「配置名称」，它用来在本地配置里标识这一条记录。");
        put(MSG_NO_HOST, "No host entered", "主机未填写");
        put(MSG_NO_HOST_REASON,
                "Enter the VM's IP address or host name before saving.",
                "请先填写虚拟机的 IP 或主机名再保存。");
        put(MSG_SAVE_FAILED, "Saving the profile failed", "保存配置失败");
        put(MSG_SAVE_REJECTED, "The profile was rejected", "保存配置被拒绝");
        put(MSG_SAVE_REJECTED_REASON,
                "The store did not accept the profile \"{0}\"; "
                        + "the name may be empty or contain invalid "
                        + "characters. Try a different name.",
                "存储层没有接受配置「{0}」，可能是名称为空或含有"
                        + "非法字符。请换一个名称再试。");
        put(MSG_SAVED_TITLE, "Saved profile \"{0}\"", "已保存配置「{0}」");
        put(MSG_SAVED_BODY, "Profile \"{0}\" was saved locally.",
                "配置「{0}」已保存到本地。");
        put(MSG_SAVED_TARGET, "Target      : {0}", "连接目标：{0}");
        put(MSG_SAVED_KEY, "Private key : {0}", "私钥路径：{0}");
        put(MSG_SAVED_KEY_NONE, "(not set)", "(未指定)");

        put(MSG_NO_SELECTION, "No profile selected", "没有选中配置");
        put(MSG_NO_SELECTION_DELETE,
                "Select the profile you want to delete under \"Saved profiles\" first.",
                "请先在「已存配置」里选一条要删除的记录。");
        put(MSG_NO_SELECTION_LOAD,
                "Select a profile under \"Saved profiles\" first.",
                "请先在「已存配置」里选一条记录。");
        put(MSG_CONFIRM_DELETE,
                "Delete the profile \"{0}\"? This cannot be undone.",
                "确定要删除配置「{0}」吗？此操作不可撤销。");
        put(MSG_CONFIRM_DELETE_TITLE, "Delete profile", "删除配置");
        put(MSG_DELETE_FAILED, "Deleting the profile failed", "删除配置失败");
        put(MSG_DELETED_TITLE, "Deleted profile \"{0}\"", "已删除配置「{0}」");
        put(MSG_DELETED_BODY, "Profile \"{0}\" was removed from the local store.",
                "配置「{0}」已从本地删除。");
        put(MSG_MISSING_TITLE, "The profile does not exist", "配置不存在");
        put(MSG_MISSING_DELETED,
                "No profile named \"{0}\" was found in the local store; "
                        + "it may have been deleted already.",
                "本地配置里没有找到名为「{0}」的记录，可能已被删除。");
        put(MSG_LOAD_FAILED, "Loading the profile failed", "载入配置失败");
        put(MSG_MISSING, "No profile named \"{0}\" was found in the local store.",
                "本地配置里没有找到名为「{0}」的记录。");
        put(MSG_LOADED_TITLE, "Loaded profile \"{0}\"", "已载入配置「{0}」");
        put(MSG_LOADED_BODY,
                "Profile \"{0}\" was loaded into the form; click \"Test connection\" "
                        + "to verify it.\nTarget: {1}",
                "配置「{0}」已载入表单，可以点「测试连接」验证。\n连接目标：{1}");

        put(MSG_NO_KEYGEN, "ssh-keygen.exe was not found", "找不到 ssh-keygen.exe");
        put(MSG_NO_KEYGEN_REASON,
                "ssh-keygen.exe was not found in C:\\WINDOWS\\System32\\OpenSSH\\ "
                        + "or anywhere on PATH.",
                "在 C:\\WINDOWS\\System32\\OpenSSH\\ 和 PATH 里都没有找到 ssh-keygen.exe。");
        put(MSG_NO_HOME, "Cannot determine the user home directory", "无法确定用户目录");
        put(MSG_NO_HOME_REASON,
                "The system property user.home is empty, so there is nowhere to "
                        + "put the new key.",
                "系统属性 user.home 为空，没地方放新生成的密钥。");
        put(MSG_KEY_EXISTS,
                "The key file already exists:\n{0}\n\n"
                        + "Overwriting generates a brand new key pair, and the "
                        + "old key stops matching the authorized_keys entry on the "
                        + "remote side. Overwrite it?",
                "密钥文件已存在：\n{0}\n\n"
                        + "覆盖会生成一对新密钥，旧密钥对应远端的 authorized_keys 将失效。"
                        + "确定覆盖吗？");
        put(MSG_KEY_EXISTS_TITLE, "Key already exists", "密钥已存在");
        put(MSG_KEY_DELETE_FAILED,
                "Could not delete the old key file:\n{0}\n\n"
                        + "Generation stopped on purpose: ssh-keygen would ask "
                        + "\"Overwrite?\" and wait for an answer that a windowed "
                        + "program can never give, so it would hang. Close anything "
                        + "holding the file (or delete it by hand) and try again.",
                "无法删除旧的密钥文件：\n{0}\n\n"
                        + "这里刻意停下：ssh-keygen 会接着问「是否覆盖」并等一个"
                        + "窗口程序永远给不出的回答，结果就是一直卡住。"
                        + "请关掉占用该文件的程序（或手工删掉它）后重试。");
        put(MSG_GEN_RUNNING,
                "Generating the key, please wait... (RSA generation can take a few seconds)",
                "正在生成密钥，请稍候…（RSA 生成可能需要几秒）");
        put(MSG_GEN_RUNNING_BODY, "Running:", "正在调用：");
        put(MSG_GEN_OUTFILES, "Output files:", "输出文件：");
        put(MSG_MKDIR_FAILED, "Could not create the directory: {0}",
                "无法创建目录：{0}");
        put(MSG_GEN_FAILED, "Generating the key failed", "生成密钥失败");
        put(MSG_GEN_FAILED_CODE, "Generating the key failed (exit code {0})",
                "生成密钥失败（退出码 {0}）");
        put(MSG_GEN_OK_TITLE, "Key generated", "密钥已生成");
        put(MSG_GEN_OK_BODY, "Key generated:", "密钥已生成：");
        put(MSG_GEN_PRIVKEY, "Private key: {0}", "私钥：{0}");
        put(MSG_GEN_PUBKEY, "Public key : {0}", "公钥：{0}");
        put(MSG_GEN_PASSPHRASE,
                "Passphrase : none (empty passphrase, so login is automatic)",
                "口令：无（空口令，便于自动登录）");
        put(MSG_GEN_TAIL,
                "The \"Private key\" field in the form has been filled in; "
                        + "you can now click \"Copy public key to VM\" to install the "
                        + "public key on the VM.",
                "表单里的「密钥路径」已自动填好，现在可以点"
                        + "「复制公钥到远端」把公钥装到虚拟机上。");
        put(MSG_GEN_OUTPUT, "ssh-keygen output:", "ssh-keygen 输出：");

        put(TIP_KEYGEN_1,
                "Windows 10/11 ships with an OpenSSH client; check under Settings -> "
                        + "Apps -> Optional features that \"OpenSSH Client\" is installed.",
                "Windows 10/11 一般自带 OpenSSH 客户端，可在「设置 → 应用 → "
                        + "可选功能」里确认「OpenSSH 客户端」已安装。");
        put(TIP_KEYGEN_2,
                "Make sure the file exists: C:\\WINDOWS\\System32\\OpenSSH\\ssh-keygen.exe.",
                "确认文件存在：C:\\WINDOWS\\System32\\OpenSSH\\ssh-keygen.exe。");
        put(TIP_KEYGEN_3,
                "If security software has locked the .ssh directory, run ssh-keygen "
                        + "manually on the command line once.",
                "如果 .ssh 目录被安全软件锁定，请手动在命令行执行一次 ssh-keygen。");
        put(TIP_KEYGEN_4,
                "You can also generate it by hand: ssh-keygen -t rsa -b 4096 -N \"\" "
                        + "-f %USERPROFILE%\\.ssh\\id_rsa_tbvm",
                "也可以手工生成：ssh-keygen -t rsa -b 4096 -N \"\" "
                        + "-f %USERPROFILE%\\.ssh\\id_rsa_tbvm");

        put(MSG_INCOMPLETE_COPY,
                "Incomplete settings, cannot copy the public key",
                "参数不完整，无法复制公钥");
        put(MSG_NO_PRIVKEY, "No private key specified", "没有指定私钥");
        put(MSG_NO_PRIVKEY_REASON,
                "\"Copy public key to VM\" needs a private key path first, so that "
                        + "the matching .pub file can be found.",
                "「复制公钥到远端」需要先指定私钥路径，以便找到同名的 .pub 公钥文件。");
        put(MSG_NO_PUBFILE, "The public key file was not found", "找不到公钥文件");
        put(MSG_NO_PUBFILE_REASON,
                "The expected public key file does not exist: {0}\n"
                        + "(The private key is there but the .pub file is missing; "
                        + "click \"Generate key\" to create a new key pair.)",
                "期望的公钥文件不存在：{0}\n"
                        + "（私钥存在但 .pub 不见了，可以点「生成密钥」重新生成一对。）");
        put(MSG_NO_SSH, "ssh.exe was not found", "找不到 ssh.exe");
        put(MSG_NO_SSH_REASON,
                "ssh.exe was not found in C:\\WINDOWS\\System32\\OpenSSH\\ or anywhere "
                        + "on PATH.",
                "在 C:\\WINDOWS\\System32\\OpenSSH\\ 和 PATH 里都没有找到 ssh.exe。");
        put(MSG_CONFIRM_COPY,
                "Copy the public key to the VM?\n\n"
                        + "Public key: {0}\nTarget    : {1}\n\n"
                        + "This runs on the remote side: mkdir -p ~/.ssh && chmod 700 ~/.ssh && "
                        + "cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys\n"
                        + "If the same key is already there, one duplicate line is appended "
                        + "(harmless).",
                "把公钥复制到远端？\n\n"
                        + "公钥：{0}\n目标：{1}\n\n"
                        + "会在远端执行：mkdir -p ~/.ssh && chmod 700 ~/.ssh && "
                        + "cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys\n"
                        + "如果同样的公钥已存在，会重复追加一行（不影响使用）。");
        put(MSG_CONFIRM_COPY_TITLE, "Copy public key to VM", "复制公钥到远端");
        put(MSG_CONFIRM_COPY_PUB, "Public key:", "公钥：");
        put(MSG_CONFIRM_COPY_TARGET, "Target    :", "目标：");
        put(MSG_COPYING, "Copying the public key to {0} ...",
                "正在把公钥复制到 {0} …");
        put(MSG_COPYING_BODY,
                "Installing the public key in the background, waiting up to {0} "
                        + "seconds.\n"
                        + "This step still needs a connection that can log in (with a "
                        + "password or an existing key).\n"
                        + "If the remote side has never had a public key installed, "
                        + "ssh.exe cannot enter a password non-interactively; log in once "
                        + "inside the VM with a password, or use the VMware console.",
                "正在后台执行公钥安装，最长等待 {0} 秒。\n"
                        + "这一步仍然需要一次能够登录的连接（用密码或已有密钥）。\n"
                        + "如果远端从未装过任何公钥，ssh.exe 无法非交互式输入密码，"
                        + "请先在虚拟机里用密码登录一次，或用 VMware 控制台操作。");
        put(MSG_READ_PUB_FAILED, "Could not read the public key file: {0}",
                "读取公钥文件失败：{0}");
        put(MSG_PUB_EMPTY, "The public key file is empty: {0}",
                "公钥文件是空的：{0}");
        put(MSG_COPY_FAILED, "Copying the public key failed", "复制公钥失败");
        put(MSG_COPY_FAILED_CODE, "Copying the public key failed (exit code {0})",
                "复制公钥失败（退出码 {0}）");
        put(MSG_COPY_OK_TITLE, "Public key installed on {0}",
                "公钥已安装到 {0}");
        put(MSG_COPY_OK_BODY,
                "The public key was written to the remote ~/.ssh/authorized_keys.\n"
                        + "You can now click \"Test connection\" to check that "
                        + "passwordless login works.",
                "公钥已写入远端 ~/.ssh/authorized_keys。\n"
                        + "现在可以点「测试连接」验证免密登录是否生效。");
        put(MSG_COPY_OK_TAIL, "Command output:", "命令输出：");
        put(MSG_CMD_OUTPUT, "Command output:", "命令输出：");

        put(TIP_COPY_1,
                "The first connection asks you to confirm the host fingerprint; here "
                        + "accept-new accepts it automatically. If it still fails, run ssh "
                        + "manually once so the fingerprint is stored in known_hosts.",
                "第一次连接会提示确认主机指纹；这里用 accept-new 自动接受。"
                        + "如仍失败，先在命令行手动 ssh 一次把指纹存入 known_hosts。");
        put(TIP_COPY_2,
                "When the remote side has no usable public key yet, ssh.exe cannot "
                        + "type a password non-interactively. Log in to the VM in the VMware "
                        + "console first, or complete one command-line login with a password.",
                "远端还没有任何可用公钥时，ssh.exe 不能非交互式输密码。"
                        + "请先在 VMware 控制台里登录虚拟机，或用密码完成一次命令行登录。");
        put(TIP_COPY_3,
                "Make sure the remote sshd allows public key authentication: "
                        + "PubkeyAuthentication yes in /etc/ssh/sshd_config, then restart sshd.",
                "确认远端的 sshd 允许公钥认证：/etc/ssh/sshd_config 里 "
                        + "PubkeyAuthentication yes，改完记得重启 sshd。");
        put(TIP_COPY_4,
                "Make sure the remote ~/.ssh is 700 and authorized_keys is 600, "
                        + "otherwise sshd refuses to read the public key.",
                "确认远端 ~/.ssh 权限是 700、authorized_keys 是 600，"
                        + "否则 sshd 会拒绝读取公钥。");
        put(TIP_COPY_5,
                "If you see Permission denied (publickey), the private key and the "
                        + "installed public key do not match; generate a new pair and copy it again.",
                "若提示 Permission denied (publickey)，说明私钥和远端已装公钥不配对，"
                        + "可重新生成一对并再次复制。");

        put(MSG_EXEC_FAILED, "Execution failed: {0}", "执行失败：{0}");
        put(MSG_EXEC_CMD, "Command: {0}", "命令：{0}");
        put(PFX_EXEC_FAILED, "Execution failed: ", "执行失败：");
        put(PFX_EXEC_CMD, "Command: ", "命令：");

        put(FRAME_TITLE, "VM Adapter - SSH Connection Console",
                "虚拟机适配 — SSH 连接控制台");
        put(FRAME_HEAD_TITLE,
                "SSH Connection Console (standalone verification window)",
                "虚拟机 SSH 连接控制台（独立验证窗口）");
        put(FRAME_HEAD_SUB,
                "This window does not depend on TBtools and "
                        + "exists to verify the connection panel on its own. Inside the TBtools "
                        + "plugin the same panel is embedded directly in the host UI.",
                "这个窗口不依赖 TBtools，用于单独验证连接面板。"
                        + "在 TBtools 插件里，同样的面板会被直接嵌进宿主界面。");
        put(FRAME_GUIDE_BTN, "Remote conda install guide", "查看远端 conda 安装指引");
        put(FRAME_GUIDE_TITLE, "Remote conda install guide", "远端 conda 安装指引");
        put(FRAME_GUIDE_FAILED,
                "The conda install guide is not available right now: {0}\n\n"
                        + "(The call to vmadapt.core.RemoteSetup.condaInstallGuide() "
                        + "failed; check that the core package is compiled and passing tests.)",
                "暂时拿不到 conda 安装指引：{0}\n\n"
                        + "（vmadapt.core.RemoteSetup.condaInstallGuide() 调用失败，"
                        + "请确认核心包已编译并通过测试。）");
        put(FRAME_GUIDE_EMPTY, "The core package returned an empty install guide.",
                "核心包返回了空的安装指引。");
        put(FRAME_ENV, "Java {0}  |  ssh.exe: {1}", "Java {0}　|　ssh.exe: {1}");
        put(FRAME_ENV_NO_SSH, "not found (install the OpenSSH client)",
                "未找到（请安装 OpenSSH 客户端）");

        put(USAGE_TITLE, "VM Adapter - SSH Connection Console: usage",
                "虚拟机适配 — SSH 连接控制台 用法");
        put(USAGE_LINE1, "  java vmadapt.ui.MainFrame [options]",
                "  java vmadapt.ui.MainFrame [选项]");
        put(USAGE_OPTS,
                "Options (all optional; when omitted the form is empty and the port defaults to 22):",
                "选项（都可省略，省略时表单为空，端口默认 22）：");
        put(USAGE_HOST, "  --host=192.168.1.100   VM IP address or host name",
                "  --host=192.168.1.100   虚拟机 IP 或主机名");
        put(USAGE_PORT,
                "  --port=22              SSH port (NAT port forwarding commonly uses 2222)",
                "  --port=22              SSH 端口（NAT 端口转发常见 2222）");
        put(USAGE_USER, "  --user=ubuntu          Login user name",
                "  --user=ubuntu          登录用户名");
        put(USAGE_KEY,
                "  --key=C:\\Users\\me\\.ssh\\id_rsa_tbvm   Private key path",
                "  --key=C:\\Users\\me\\.ssh\\id_rsa_tbvm   私钥路径");
        put(USAGE_NAME, "  --name=seq-box         Profile name",
                "  --name=seq-box         配置名称");
        put(USAGE_HELP, "  -h, --help             Show this help",
                "  -h, --help             显示本帮助");
        put(USAGE_EXAMPLE, "Example:", "示例：");

        put(HL_BANNER, "======================================================",
                "======================================================");
        put(HL_TITLE, " VM Adapter - cannot start the graphical interface",
                " 虚拟机适配 — 图形界面无法启动");
        put(HL_BODY,
                "This environment is headless, so a Swing window cannot be created.",
                "检测到当前是「无图形界面（headless）」环境，Swing 窗口无法创建。");
        put(HL_NOT_ERROR,
                "This is not a program error: by design the tool exits normally "
                        + "here with status code 0.",
                "这不是程序错误，本工具按设计在此环境下正常退出（退出码 0）。");
        put(HL_CAUSES, "Common causes:", "常见原因：");
        put(HL_CAUSE_1,
                "  1. Running over ssh or a remote session without X11 forwarding.",
                "  1. 通过 ssh / 远程会话运行，且未开启 X11 转发；");
        put(HL_CAUSE_2,
                "  2. The JVM was started with -Djava.awt.headless=true.",
                "  2. JVM 启动参数里带了 -Djava.awt.headless=true；");
        put(HL_CAUSE_3,
                "  3. Running on a server or CI container with no desktop environment.",
                "  3. 运行在无桌面环境的服务器或持续集成容器里。");
        put(HL_TESTS,
                "To verify the core logic only, run the unit test classes instead; "
                        + "they do not need a graphical interface. For example:",
                "如果只想验证核心逻辑，请直接跑单元测试类，它们不依赖图形界面；例如：");
        put(HL_TESTS_EXAMPLE,
                "  java -cp build\\classes vmadapt.test.SshTargetTest",
                "  java -cp build\\classes vmadapt.test.SshTargetTest");
        put(HL_RETRY,
                "If this machine does have a desktop, remove "
                        + "-Djava.awt.headless=true and try again.",
                "如果本机确实有桌面，请去掉 -Djava.awt.headless=true 后重试。");
    }

    // ------------------------------------------------------------------
    // Network modes 整段（中英各一版）
    // ------------------------------------------------------------------

    private static final String NET_HELP_EN =
            "When you cannot reach a VM, nine times out of ten the cause is the "
                    + "VMware network mode. First check which mode the VM's network "
                    + "adapter uses, then follow the matching steps below.\n"
                    + "\n"
                    + "[NAT mode]\n"
                    + "  The VM sits on a private VMware subnet, so the host cannot\n"
                    + "  reach it directly.\n"
                    + "  Setup: VM -> Settings -> Network Adapter -> choose \"NAT\", then in\n"
                    + "         Edit -> Virtual Network Editor -> VMnet8 -> NAT Settings ->\n"
                    + "         Add, create a port forwarding rule: host port 2222 ->\n"
                    + "         port 22 of the VM's IP address.\n"
                    + "  Fill in this panel: host 127.0.0.1, port 2222.\n"
                    + "  Note: without port forwarding the connection cannot work, and\n"
                    + "        entering the VM's internal IP does not help either.\n"
                    + "\n"
                    + "[Bridged mode]\n"
                    + "  The VM and the host share the same physical LAN, each with its\n"
                    + "  own IP address.\n"
                    + "  Setup: make sure the VM's adapter has an IP assigned by the\n"
                    + "         router (check with ip addr), then ping that IP from the host.\n"
                    + "  Fill in this panel: the VM's real IP, port 22.\n"
                    + "  Note: the IP changes when you switch networks or routers, so\n"
                    + "        confirm it again with ip addr instead of reusing the old one.\n"
                    + "\n"
                    + "[Host-only mode]\n"
                    + "  The VM and the host are connected through the VMnet1 virtual\n"
                    + "  adapter and are isolated from everything else.\n"
                    + "  Setup: on the host, run ipconfig and find the address of\n"
                    + "         \"VMware Network Adapter VMnet1\" (commonly 192.168.x.1);\n"
                    + "         the VM's IP is usually on the same subnet.\n"
                    + "  Fill in this panel: the VM's IP inside the VMnet1 subnet, port 22.\n"
                    + "  Note: this mode most often gets stuck on the Windows firewall,\n"
                    + "        which treats VMnet1 as a public network and blocks inbound\n"
                    + "        connections. Either allow sshd through the firewall or\n"
                    + "        change the VMnet1 network category to private.\n"
                    + "\n"
                    + "[General troubleshooting order]\n"
                    + "  1. On the VM, run systemctl status ssh to confirm sshd is running.\n"
                    + "  2. On the host, run ssh -p PORT -i KEY USER@HOST -v to see where\n"
                    + "     it stops.\n"
                    + "  3. From the host, ping the VM: if that fails it is a network\n"
                    + "     problem; if it succeeds, it is a port or authentication problem.\n"
                    + "  4. \"Permission denied\" points at the key; a timeout points at the\n"
                    + "     network mode.\n"
                    + "  5. Key permissions: on Linux ~/.ssh must be 700 and\n"
                    + "     authorized_keys must be 600.\n";

    private static final String NET_HELP_ZH =
            "连不上虚拟机时，九成问题出在 VMware 的网络模式上。先确认虚拟机的"
                    + "网卡是哪种模式，再对照下面的办法。\n"
                    + "\n"
                    + "【NAT 模式】\n"
                    + "  虚拟机在 VMware 的私有网段里，宿主机不能直接访问它。\n"
                    + "  做法：虚拟机 → 设置 → 网络适配器 → 选「NAT」，然后在\n"
                    + "        「编辑 → 虚拟网络编辑器 → VMnet8 → NAT 设置 → 添加」里\n"
                    + "        加一条端口转发：主机端口 2222 → 虚拟机 IP 的 22 端口。\n"
                    + "  本面板填：主机 127.0.0.1，端口 2222。\n"
                    + "  注意：不配端口转发就一定连不上，改填虚拟机内网 IP 也没用。\n"
                    + "\n"
                    + "【桥接模式】\n"
                    + "  虚拟机和宿主机在同一个物理局域网里，各自有独立 IP。\n"
                    + "  做法：确认虚拟机网卡拿到的是路由器分配的 IP（ip addr 查看），\n"
                    + "        宿主机直接 ping 一下这个 IP 通不通。\n"
                    + "  本面板填：虚拟机的真实 IP，端口 22。\n"
                    + "  注意：换网络、换路由器后 IP 会变，要用 ip addr 重新确认，\n"
                    + "        不要照抄上次的地址。\n"
                    + "\n"
                    + "【仅主机模式（Host-Only）】\n"
                    + "  虚拟机和宿主机通过 VMnet1 这块虚拟网卡互联，与外界隔离。\n"
                    + "  做法：在宿主机用 ipconfig 找到「VMware Network Adapter VMnet1」\n"
                    + "        的地址（常见 192.168.x.1），虚拟机 IP 通常同网段。\n"
                    + "  本面板填：VMnet1 网段内虚拟机的 IP，端口 22。\n"
                    + "  注意：这一模式最容易卡在 Windows 防火墙 —— 它会把 VMnet1 当成\n"
                    + "        「公用网络」而拦掉入站连接。要么允许 sshd 通过防火墙，\n"
                    + "        要么把 VMnet1 的网络类别改成「专用」。\n"
                    + "\n"
                    + "【通用排查顺序】\n"
                    + "  1. 在虚拟机里跑 systemctl status ssh，确认 sshd 正在运行。\n"
                    + "  2. 在宿主机跑 ssh -p 端口 -i 私钥 用户@主机 -v，看卡在哪一步。\n"
                    + "  3. 宿主机 ping 虚拟机：不通是网络问题，通了是端口或认证问题。\n"
                    + "  4. 报「Permission denied」看密钥对不对；报超时看网络模式。\n"
                    + "  5. 密钥权限：Linux 上 ~/.ssh 要 700，authorized_keys 要 600。\n";

    // ------------------------------------------------------------------
    // 登记放在两个字段都声明完之后。
    //
    // 这两句**不能**放进上面的 static 块：NET_HELP_EN / NET_HELP_ZH 是用 + 拼接
    // 出来的 static 字段，不是编译期常量，在声明之前引用属于非法前向引用，
    // javac 会报「非法前向引用」。放在这里既合法，也不用把几百行字面量内联进
    // put(...) 调用里。
    // ------------------------------------------------------------------

    static {
        put(NET_HELP, NET_HELP_EN, NET_HELP_ZH);
    }
}
