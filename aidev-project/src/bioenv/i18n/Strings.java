package bioenv.i18n;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全部界面文案，中英两套，直接写在 Java 源码里。
 *
 * <h2>为什么不用 ResourceBundle + .properties</h2>
 *
 * <p>这是本包最重要的一个决定，理由有三条，任何一条都足以否掉 properties：
 *
 * <ol>
 *   <li><b>编码。</b>{@code java.util.Properties} 按规范是 ISO-8859-1，
 *       中文必须写成 {@code \u4e2d\u6587} 这种转义。几百条中文文案全部转义之后，
 *       源码就没法读了——校对的人看不到中文，改一个字要重新转义，
 *       review 时也无法判断译得对不对。用 {@code Reader} 加载能绕过，
 *       但那又要处理 BOM、换行和「文件到底存成 UTF-8 还是 GBK」的问题。</li>
 *   <li><b>交付形态。</b>本插件以<b>单个 JAR</b> 交付。properties 必须随 JAR
 *       一起打包，且不能和 Java 代码分离，否则就得在运行时拼路径去读——
 *       而 TBtools 的类加载器行为未知，{@code getResourceAsStream} 未必按预期工作。
 *       写在源码里的静态 Map 编译进 class，跟着类一起被加载，不依赖任何资源查找。</li>
 *   <li><b>失败模式。</b>资源缺失时 {@code ResourceBundle} 抛
 *       {@link java.util.MissingResourceException}，而且是在第一次取文案时抛，
 *       往往发生在界面已经画到一半的时候。静态 Map 不可能「缺失」——
 *       最坏情况是某个键没写，此时 {@link I18n#t(String)} 返回键名本身，
 *       界面显示一个丑陋但可读的键名，不会崩。</li>
 * </ol>
 *
 * <h2>键名规范</h2>
 *
 * <p>{@code 区域.用途}，点分层级、全小写驼峰式的段、语义化英文：
 * <pre>
 *   menu.detect           菜单/按钮文案
 *   status.ready          状态栏文案
 *   conn.host             连接表单里的字段标签
 *   err.portInvalid       错误提示
 *   hint.filterField      控件上的提示文本（tooltip / placeholder）
 * </pre>
 *
 * <p>禁止：拼音（{@code zhuangtai}）、数字编号（{@code text1}）、
 * 用中文当键、把变量值拼进键名。键名是<b>代码</b>，改文案不该改键名——
 * 这正是把键和文案分开的意义：漏译、错译在 diff 里立刻可见。
 *
 * <h2>占位符</h2>
 *
 * <p>用 {@code {0}}、{@code {1}}，从 0 开始。替换由
 * {@link I18n#t(String, Object...)} 自己实现，<b>刻意不用
 * {@link java.text.MessageFormat}</b>：它把单引号当转义符，文案里
 * 「{@code Click 'Detect conda environments'}」会因为那个 {@code '}
 * 吞掉后面的占位符，产出 {@code {0}} 这样的字面量。这个坑在英文原文里
 * 遍地都是单引号的场景下一定会踩，而自己写替换只有几行。
 *
 * <h2>翻译时的注意事项</h2>
 *
 * <p>文案里可以放 {@code \n} 换行，但<b>不要</b>为了让某一段在中文下更短
 * 而用空格对齐，两种语言的宽度差异会让对齐失效。
 */
public final class Strings {

    private Strings() {
        // 纯常量容器，不实例化。
    }

    // ------------------------------------------------------------------
    // 示例键（示范用，共 15 组 / 30 条文案）
    //
    // 这些键全部取自 MainPanel 里真实存在的字符串，不是编造的占位内容，
    // 目的是让后续批量迁移时可以直接照抄格式。键名以真实调用点的语义为准。
    // ------------------------------------------------------------------

    /** 主按钮：扫描 conda 环境。 */
    public static final String MENU_DETECT = "menu.detect";

    /** 主按钮：从表单重建命令预览。 */
    public static final String MENU_REBUILD_PREVIEW = "menu.rebuildPreview";

    /** 状态栏：就绪。 */
    public static final String STATUS_READY = "status.ready";

    /** 状态栏：就绪，但 WSL 不可用时的变体。 */
    public static final String STATUS_READY_NO_WSL = "status.readyNoWsl";

    /** 状态栏：正在定位 conda 并枚举环境。 */
    public static final String STATUS_LOCATING_CONDA = "status.locatingConda";

    /** 状态栏：正在扫描环境里的命令。 */
    public static final String STATUS_SCANNING = "status.scanning";

    /** 状态栏：找到了环境（带一个白名占位）。 */
    public static final String STATUS_FOUND_ENVS = "status.foundEnvs";

    /** 状态栏：退出码非零（带退出码占位）。 */
    public static final String STATUS_EXITED_WITH_CODE = "status.exitedWithCode";

    /** 连接：来源（发行版 / 连接配置）标签。 */
    public static final String CONN_SOURCE = "conn.source";

    /** 连接：主机地址字段标签。 */
    public static final String CONN_HOST = "conn.host";

    /** 连接：用户名。 */
    public static final String CONN_USER = "conn.user";

    /** 错误：端口号非法（带非法值占位）。 */
    public static final String ERR_PORT_INVALID = "err.portInvalid";

    /** 错误：还没选连接配置。 */
    public static final String ERR_CHOOSE_PROFILE = "err.chooseProfile";

    /** 错误：所选连接配置已不存在（带配置名占位）。 */
    public static final String ERR_PROFILE_MISSING = "err.profileMissing";

    /** 提示：连接方式下拉框的说明。 */
    public static final String HINT_MODE_BOX = "hint.modeBox";

    /** 提示：过滤框的说明。 */
    public static final String HINT_FILTER_FIELD = "hint.filterField";

    /** 提示：命令预览区可以手写。 */
    public static final String HINT_MANUAL_EDIT = "hint.manualEdit";

    /** 语言切换按钮上的说明（中文界面 → 显示 English）。 */
    public static final String LANG_SWITCH_TO_EN = "lang.switchToEn";

    /** 语言切换按钮上的说明（英文界面 → 显示 中文）。 */
    public static final String LANG_SWITCH_TO_ZH = "lang.switchToZh";

    /** 语言切换工具提示。 */
    public static final String LANG_TOOLTIP = "lang.tooltip";

    // ------------------------------------------------------------------
    // MainPanel：按钮
    // ------------------------------------------------------------------

    /** 按钮：运行。 */
    public static final String MENU_RUN = "menu.run";

    /** 按钮：停止。 */
    public static final String MENU_STOP = "menu.stop";

    /** 按钮：清空日志。 */
    public static final String MENU_CLEAR_LOG = "menu.clearLog";

    /** 按钮：编辑参数。 */
    public static final String MENU_EDIT_PARAMS = "menu.editParams";

    /** 按钮：重建预览（带提示）。 */
    public static final String HINT_REBUILD_PREVIEW = "hint.rebuildPreview";

    // ------------------------------------------------------------------
    // MainPanel：发现栏与面板标题
    // ------------------------------------------------------------------

    /** 标签：连接方式一栏的名字。 */
    public static final String CONN_MODE_LABEL = "conn.modeLabel";

    /** 连接方式下拉项：本机 WSL。技术名，中英一致。 */
    public static final String MODE_WSL = "mode.wsl";

    /**
     * 连接方式下拉项：Linux 虚拟机（走 SSH 通道）。
     *
     * <p>中文文案是「Linux 虚拟机」而不是「SSH 虚拟机」——用户是按"这是一台 Linux
     * 虚拟机"来理解这个选项的，SSH 只是实现手段。改回「SSH 虚拟机」会让选项读起来
     * 像在选一种协议。英文侧一直是 {@code Linux VM}，两边现在指的是同一件事。
     */
    public static final String MODE_VM = "mode.vm";

    /** 标签：本地 WSL 发行版来源。 */
    public static final String CONN_LABEL_WSL = "conn.labelWsl";

    /** 标签：已保存的虚拟机配置来源。 */
    public static final String CONN_LABEL_SAVED = "conn.labelSaved";

    /** 标签：过滤框前缀。 */
    public static final String LABEL_FILTER = "label.filter";

    /** 标签：树根（conda 环境）。 */
    public static final String LABEL_TREE_ROOT = "label.treeRoot";

    /** 标题：命令树面板。 */
    public static final String TITLE_TREE = "title.tree";

    /** 标题：输出面板。 */
    public static final String TITLE_OUTPUT = "title.output";

    /** 标题：命令预览（未编辑，跟随表单）。 */
    public static final String TITLE_PREVIEW = "title.preview";

    /** 标题：命令预览（已被手工编辑）。 */
    public static final String TITLE_PREVIEW_EDITED = "title.previewEdited";

    /** 提示：命令预览可手工输入。 */
    public static final String HINT_COMMAND_AREA = "hint.commandArea";

    /** 提示：命令预览长路径的显示规则。 */
    public static final String HINT_COMMAND_AREA_LONG = "hint.commandAreaLong";

    /** 提示：预览框被手工编辑后的说明。 */
    public static final String HINT_PREVIEW_EDITED = "hint.previewEdited";

    /** 提示：命令预览已从表单重建。 */
    public static final String STATUS_PREVIEW_REBUILT = "status.previewRebuilt";

    /** 提示：来源下拉框的说明（WSL 方式）。 */
    public static final String HINT_DISTRO_BOX = "hint.distroBox";

    /** 提示：来源下拉框的说明（虚拟机方式）。 */
    public static final String HINT_PROFILE_BOX = "hint.profileBox";

    // ------------------------------------------------------------------
    // MainPanel：状态栏
    // ------------------------------------------------------------------

    /** 状态栏：发现流程即将开始（虚拟机方式）。 */
    public static final String STATUS_PREPARING_CONNECTION = "status.preparingConnection";

    /** 状态栏：正在查找 WSL 发行版。 */
    public static final String STATUS_LOOKING_WSL = "status.lookingWsl";

    /** 状态栏：正在读取连接配置。 */
    public static final String STATUS_READING_PROFILES = "status.readingProfiles";

    /** 状态栏：已在后台连接虚拟机。 */
    public static final String STATUS_CONNECTING = "status.connecting";

    /** 状态栏：退出码非零的简版（带退出码占位）。 */
    public static final String STATUS_EXITED_CODE = "status.exitedCode";

    /** 状态栏：已取消。 */
    public static final String STATUS_CANCELLED = "status.cancelled";

    /** 状态栏：正在运行某个命令（带命令名占位）。 */
    public static final String STATUS_RUNNING = "status.running";

    /** 状态栏：扫描失败（带原因占位）。 */
    public static final String STATUS_DISCOVERY_FAILED = "status.discoveryFailed";

    /** 状态栏：扫描没有产出结果。 */
    public static final String STATUS_DISCOVERY_NO_RESULT = "status.discoveryNoResult";

    /** 状态栏：无法恢复上次结果。 */
    public static final String STATUS_RESTORE_FAILED = "status.restoreFailed";

    /** 状态栏：正在检查某个发行版/目标里的环境（带来源占位）。 */
    public static final String STATUS_CHECKING_ENVS = "status.checkingEnvs";

    /** 状态栏：正在使用某个发行版（带发行版占位）。 */
    public static final String STATUS_USING_DISTRO = "status.usingDistro";

    /** 状态栏：没有可用的 conda 安装。 */
    public static final String STATUS_NO_CONDA = "status.noConda";

    /** 状态栏：连不上虚拟机（带目标占位）。 */
    public static final String STATUS_NO_CONNECTION = "status.noConnection";

    /**
     * 状态栏：Linux VM 方式下还没有可用的连接配置，所以根本没尝试连接。
     *
     * <p>必须与 {@link #STATUS_NO_CONNECTION} 分开：一个是「试了没连上」，
     * 一个是「还没得可试」，两者的下一步动作完全不同。也刻意不复用
     * {@link #STATUS_NO_PROFILE}——那句是为对话框写的、结尾带冒号，
     * 用在状态栏里语义对但读着别扭。
     */
    public static final String STATUS_NO_PROFILE_TO_SCAN = "status.noProfileToScan";

    /** 状态栏：已选好配置，等待点击 Detect（带目标占位）。 */
    public static final String STATUS_PROFILE_SELECTED = "status.profileSelected";

    /** 状态栏：读取连接配置失败（带原因占位）。 */
    public static final String STATUS_PROFILE_READ_FAILED = "status.profileReadFailed";

    /** 状态栏：还没有保存任何配置。 */
    public static final String STATUS_NO_PROFILE = "status.noProfile";

    /** 状态栏：已载入若干配置（带数量占位）。 */
    public static final String STATUS_PROFILES_LOADED = "status.profilesLoaded";

    /** 状态栏：请求取消。 */
    public static final String STATUS_CANCELLING = "status.cancelling";

    /** 状态栏：完成，耗时若干秒（带秒数占位）。 */
    public static final String STATUS_DONE_IN = "status.doneIn";

    /** 状态栏：正在读取某个命令的帮助（带命令占位）。 */
    public static final String STATUS_READING_HELP = "status.readingHelp";

    /** 状态栏：已记住某个选项的类型（带命令、选项占位）。 */
    public static final String STATUS_REMEMBERED_TYPE = "status.rememberedType";

    /** 状态栏：子命令的选项数（带子命令、数量占位）。 */
    public static final String STATUS_SUB_OPTIONS = "status.subOptions";

    /** 状态栏：读不出子命令的选项（带子命令占位）。 */
    public static final String STATUS_SUB_NO_OPTIONS = "status.subNoOptions";

    /** 状态栏：若干命令已恢复（带数量占位）。 */
    public static final String STATUS_RESTORED = "status.restored";

    /** 状态栏：上次的结果已恢复（带来源占位）。 */
    public static final String STATUS_RESTORED_FOR = "status.restoredFor";

    /** 状态栏：环境有变化，重新扫描。 */
    public static final String STATUS_RESCANNING = "status.rescanning";

    /** 状态栏：还没有上次结果，开始扫描。 */
    public static final String STATUS_NO_CACHE_SCAN = "status.noCacheScan";

    /**
     * 状态栏：首次使用，请先选连接方式再扫描。
     *
     * <p>与 {@link #STATUS_NO_CACHE_SCAN} 的区别是"说没说到做到"：那一句用在
     * <b>马上就要调用 scan()</b> 的地方，这一句用在<b>决定不扫、把扫描留给用户</b>
     * 的地方。曾经两处共用同一句，于是自动路径一边放弃扫描、一边显示"正在扫描"。
     */
    public static final String STATUS_FIRST_RUN_GUIDE = "status.firstRunGuide";

    /** 状态栏：这个目标还没扫过，请点扫描（带目标占位）。 */
    public static final String STATUS_NEEDS_FIRST_SCAN = "status.needsFirstScan";

    /** 状态栏：就绪，可用命令数与来源（带数量、来源占位）。 */
    public static final String STATUS_READY_COUNT = "status.readyCount";

    /** 状态栏：就绪，跨环境统计（带命令数、环境数占位）。 */
    public static final String STATUS_READY_ACROSS = "status.readyAcross";

    /** 状态栏：命令帮助无法解析（带命令占位）。 */
    public static final String STATUS_HELP_UNPARSED = "status.helpUnparsed";

    /** 状态栏：发现了若干选项（带命令、数量占位）。 */
    public static final String STATUS_OPTIONS_FOUND = "status.optionsFound";

    /** 状态栏：没有图形界面，对话框打不开。 */
    public static final String STATUS_NO_DISPLAY = "status.noDisplay";

    /** 状态栏：失败（带原因占位）。 */
    public static final String STATUS_FAILED = "status.failed";

    /** 状态栏：参数已更新。 */
    public static final String STATUS_PARAMS_UPDATED = "status.paramsUpdated";

    /** 状态栏：已重新读取帮助，参数恢复默认（带命令占位）。 */
    public static final String STATUS_HELP_REREAD = "status.helpReread";

    /** 状态栏：无法重新读取帮助。 */
    public static final String STATUS_HELP_REREAD_FAILED = "status.helpRereadFailed";

    /** 状态栏：删除了若干自定义参数（带数量占位）。 */
    public static final String STATUS_PARAMS_DELETED = "status.paramsDeleted";

    /** 状态栏：重命名了若干自定义参数（带数量占位）。 */
    public static final String STATUS_PARAMS_RENAMED = "status.paramsRenamed";

    /** 状态栏：需要先选一个命令。 */
    public static final String STATUS_SELECT_TOOL_FIRST = "status.selectToolFirst";

    /** 状态栏：该命令的参数还不可用。 */
    public static final String STATUS_PARAMS_UNAVAILABLE = "status.paramsUnavailable";

    /** 状态栏：树状列表的汇总（带若干数量占位）。 */
    public static final String STATUS_TREE_SUMMARY = "status.treeSummary";

    /** 状态栏：树状汇总里「按过滤词匹配」那一句（带过滤词占位）。 */
    public static final String STATUS_TREE_MATCHING = "status.treeMatching";

    /** 状态栏：树状汇总里「折叠了多少条」那一句（带数量占位）。 */
    public static final String STATUS_TREE_FOLDED = "status.treeFolded";

    /** 树节点：某个包下有多少命令（带数量占位）。 */
    public static final String TREE_PACKAGE_COUNT = "tree.packageCount";

    /** 状态栏：附带子命令数量（带数量占位）。 */
    public static final String STATUS_SUBCOMMAND_COUNT = "status.subcommandCount";

    // ------------------------------------------------------------------
    // MainPanel：日志
    // ------------------------------------------------------------------

    /** 日志：命令发现小节标题。 */
    public static final String LOG_COMMAND_DISCOVERY = "log.commandDiscovery";

    /**
     * 日志：本次扫描相对上次的增量（4 个占位：新增 / 移除 / 保留 / 重新探测）。
     *
     * <p>单独一行说清楚「保留了多少个」，是因为用户最担心的一次扫描把设置冲掉，
     * 而保留的那些确实原样留着——这句话要能被看见。
     */
    public static final String LOG_SCAN_DELTA = "log.scanDelta";

    /** 日志：这份列表是什么时候扫出来的（带时间占位）。 */
    public static final String LOG_SCANNED_AT = "log.scannedAt";

    /** 日志/状态：这个目标还没扫过，需要用户点一次扫描。 */
    public static final String LOG_NO_CACHE_MANUAL = "log.noCacheManual";

    /** 状态栏：虚拟机不可达，自动连接没有进行。 */
    public static final String STATUS_VM_NOT_REACHABLE = "status.vmNotReachable";

    /** 日志：自动连接前先做可达性检查。 */
    public static final String LOG_REACH_HEAD = "log.reachHead";
    /** 日志：端口被拒绝（主机活着，sshd 没在听）。 */
    public static final String LOG_REACH_REFUSED = "log.reachRefused";
    /** 日志：超时（关机 / 还在启动 / 地址变了 / 被防火墙丢弃）。 */
    public static final String LOG_REACH_TIMEOUT = "log.reachTimeout";
    /** 日志：地址解析不了或没有路由。 */
    public static final String LOG_REACH_UNRESOLVED = "log.reachUnresolved";

    /** 日志：环境检查小节标题。 */
    public static final String LOG_ENV_CHECK = "log.envCheck";

    /** 日志：提示行前缀。 */
    public static final String LOG_HINT_PREFIX = "log.hintPrefix";

    /** 日志：错误行前缀。 */
    public static final String LOG_ERROR_PREFIX = "log.errorPrefix";

    /** 日志：恢复后的说明（带来源占位）。 */
    public static final String LOG_RESTORED_FOR = "log.restoredFor";

    /** 日志：提示可以再点 Detect 拾取新装的工具。 */
    public static final String LOG_DETECT_FOR_NEW = "log.detectForNew";

    /** 日志：虚拟机通道上没有 conda（带目标占位）。 */
    public static final String LOG_NO_CONDA_VM = "log.noCondaVm";

    /** 日志：WSL 通道上没有 conda。 */
    public static final String LOG_NO_CONDA_WSL = "log.noCondaWsl";

    /** 日志：连不上虚拟机，无法扫描（带目标占位）。 */
    public static final String LOG_UNREACHABLE_VM = "log.unreachableVm";

    /**
     * 日志：Linux VM 方式下没有选中任何配置，无从扫描。
     *
     * <p>这条是补一个真实误报：以前这种情况下 {@code dr.sshTarget} 还是 null，
     * 于是走了 WSL 分支，在 Linux VM 模式下打出「WSL 不可达」——和用户在做的事
     * 毫无关系，把人往错方向带。
     */
    public static final String LOG_NO_PROFILE = "log.noProfile";

    /** 日志：WSL 不可达，无法扫描。 */
    public static final String LOG_UNREACHABLE_WSL = "log.unreachableWsl";

    /** 日志：下一步怎么用（命令列表说明）。 */
    public static final String LOG_PICK_COMMAND = "log.pickCommand";

    /** 日志：一次运行的开始分隔行（带命令占位）。 */
    public static final String LOG_RUN_STARTED = "log.runStarted";

    /** 日志：一次运行被取消（带秒数占位）。 */
    public static final String LOG_RUN_CANCELLED = "log.runCancelled";

    /** 日志：一次运行完成（带秒数占位）。 */
    public static final String LOG_RUN_FINISHED = "log.runFinished";

    /** 日志：一次运行以非零码结束（带退出码、秒数占位）。 */
    public static final String LOG_RUN_EXITED = "log.runExited";

    /** 日志：运行非零退出码时的排查提示。 */
    public static final String LOG_RUN_HINT = "log.runHint";

    /** 日志：无法构建命令（带原因占位）。 */
    public static final String LOG_CANNOT_BUILD = "log.cannotBuild";

    /** 日志：帮助无法解析（带命令占位）。 */
    public static final String LOG_HELP_UNPARSED = "log.helpUnparsed";

    // ------------------------------------------------------------------
    // FileDialogs
    // ------------------------------------------------------------------

    /** 文件对话框：覆盖确认（带文件路径占位）。 */
    public static final String FILE_OVERWRITE_QUESTION = "file.overwriteQuestion";

    /** 文件对话框：覆盖确认的标题。 */
    public static final String FILE_OVERWRITE_TITLE = "file.overwriteTitle";

    // ------------------------------------------------------------------
    // CollapsiblePanel
    // ------------------------------------------------------------------

    /** 可折叠面板：展开（带标题占位）。 */
    public static final String COLLAPSE_SHOW = "collapse.show";

    /** 可折叠面板：收起（带标题占位）。 */
    public static final String COLLAPSE_HIDE = "collapse.hide";

    // ------------------------------------------------------------------
    // ParameterEditorPanel（参数编辑面板）
    // ------------------------------------------------------------------

    /** 参数编辑：面板顶部说明（带命令/子命令占位）。 */
    public static final String PED_INTRO = "ped.intro";

    /** 参数编辑：新增按钮。 */
    public static final String PED_ADD = "ped.add";

    /** 参数编辑：新增按钮的提示。 */
    public static final String PED_ADD_TIP = "ped.addTip";

    /** 参数编辑：重置按钮。 */
    public static final String PED_RESET = "ped.reset";

    /** 参数编辑：重置按钮的提示。 */
    public static final String PED_RESET_TIP = "ped.resetTip";

    /** 参数编辑：重置确认（带命令占位）。 */
    public static final String PED_RESET_CONFIRM = "ped.resetConfirm";

    /** 参数编辑：保留按钮（行内）。 */
    public static final String PED_KEEP = "ped.keep";

    /** 参数编辑：列表边框标题。 */
    public static final String PED_LIST_TITLE = "ped.listTitle";

    /** 参数编辑：确定按钮。 */
    public static final String PED_OK = "ped.ok";

    /** 参数编辑：取消按钮。 */
    public static final String PED_CANCEL = "ped.cancel";

    /** 参数编辑：表头「参数」。 */
    public static final String PED_COL_PARAM = "ped.colParam";

    /** 参数编辑：表头「说明」。 */
    public static final String PED_COL_DESC = "ped.colDesc";

    /** 参数编辑：新增参数对话框标题。 */
    public static final String PED_ADD_DIALOG_TITLE = "ped.addDialogTitle";

    /** 参数编辑：新增参数需要名称。 */
    public static final String PED_NAME_REQUIRED = "ped.nameRequired";

    /** 参数编辑：参数名提示（对话框正文）。 */
    public static final String PED_ADD_PROMPT = "ped.addPrompt";

    /** 参数编辑：删除确认（带参数名占位）。 */
    public static final String PED_DELETE_CONFIRM = "ped.deleteConfirm";

    /** 参数编辑：汇总行的参数个数（带数量占位）。 */
    public static final String PED_SUMMARY_COUNT = "ped.summaryCount";

    /** 参数编辑：汇总行已删除个数（带数量占位）。 */
    public static final String PED_SUMMARY_REMOVED = "ped.summaryRemoved";

    /** 参数编辑：汇总行「此工具已有保存的编辑」。 */
    public static final String PED_SUMMARY_EDITED = "ped.summaryEdited";

    /** 参数编辑：位置参数在列表里的占位显示。 */
    public static final String PED_ARG_PLACEHOLDER = "ped.argPlaceholder";

    /** 参数编辑：拖动手柄的提示。 */
    public static final String PED_DRAG_TIP = "ped.dragTip";

    /** 参数编辑：参数名栏的提示。 */
    public static final String PED_NAME_TIP = "ped.nameTip";

    /** 参数编辑：说明栏的提示。 */
    public static final String PED_DESC_TIP = "ped.descTip";

    /** 参数编辑：类型下拉的提示。 */
    public static final String PED_KIND_TIP = "ped.kindTip";

    /** 参数编辑：彻底删除该参数（自定义参数）。 */
    public static final String PED_DELETE_ADDED_TIP = "ped.deleteAddedTip";

    /** 参数编辑：从列表移除该参数（帮助里读出来的）。 */
    public static final String PED_REMOVE_TIP = "ped.removeTip";

    /** 参数编辑：该名称已在列表里（带名称占位）。 */
    public static final String PED_ALREADY_LISTED = "ped.alreadyListed";

    /** 参数编辑：变更摘要的各段（带数量占位）。 */
    public static final String PED_CHANGED_ADDED = "ped.changedAdded";

    /** 参数编辑：变更摘要·删除。 */
    public static final String PED_CHANGED_REMOVED = "ped.changedRemoved";

    /** 参数编辑：变更摘要·恢复。 */
    public static final String PED_CHANGED_RESTORED = "ped.changedRestored";

    /** 参数编辑：变更摘要·改类型。 */
    public static final String PED_CHANGED_RETYPED = "ped.changedRetyped";

    /** 参数编辑：变更摘要·重命名。 */
    public static final String PED_CHANGED_RENAMED = "ped.changedRenamed";

    /** 参数编辑：变更摘要·改说明。 */
    public static final String PED_CHANGED_DESCRIBED = "ped.changedDescribed";

    /** 参数编辑：变更摘要的前缀。 */
    public static final String PED_CHANGES_PREFIX = "ped.changesPrefix";

    /** 参数编辑：彻底删除按钮。 */
    public static final String PED_DELETE_FOREVER = "ped.deleteForever";

    /** 参数编辑：行内「已删除」标记（带参数名占位，含 HTML）。 */
    public static final String PED_REMOVED_LABEL = "ped.removedLabel";

    /** 参数编辑：彻底删除按钮的提示（带参数名占位）。 */
    public static final String PED_PURGE_TIP = "ped.purgeTip";

    // ------------------------------------------------------------------
    // SchemaFormPanel（表单面板）
    // ------------------------------------------------------------------

    /** 表单：选项搜索框提示。 */
    public static final String FORM_SEARCH_TIP = "form.searchTip";

    /** 表单：搜索框的标签。 */
    public static final String FORM_FIND = "form.find";

    /** 表单：在新窗口打开选项。 */
    public static final String FORM_OPEN_WINDOW = "form.openWindow";

    /** 表单：新窗口按钮的提示。 */
    public static final String FORM_OPEN_WINDOW_TIP = "form.openWindowTip";

    /** 表单：没有工具被选中。 */
    public static final String FORM_NO_TOOL = "form.noTool";

    /** 表单：提示从左侧列表选一个工具。 */
    public static final String FORM_PICK_TOOL = "form.pickTool";

    /** 表单：提示选一个工具来配置。 */
    public static final String FORM_SELECT_TOOL = "form.selectTool";

    /** 表单：正在读取某个子命令的帮助（带子命令占位）。 */
    public static final String FORM_READING_SUB = "form.readingSub";

    /** 表单：子命令帮助正在加载。 */
    public static final String FORM_LOADING = "form.loading";

    /** 表单：该子命令没有选项。 */
    public static final String FORM_SUB_NO_OPTIONS = "form.subNoOptions";

    /** 表单：工作目录标签。 */
    public static final String FORM_WORKDIR = "form.workdir";

    /** 表单：工作目录说明（HTML）。 */
    public static final String FORM_WORKDIR_TIP = "form.workdirTip";

    /** 表单：在 WSL 里建工作目录的按钮。 */
    public static final String FORM_IN_WSL = "form.inWsl";

    /** 表单：在 WSL 建目录按钮的提示。 */
    public static final String FORM_IN_WSL_TIP = "form.inWslTip";

    /** 表单：选择工作目录。 */
    public static final String FORM_CHOOSE_WORKDIR = "form.chooseWorkdir";

    /** 表单：附加参数标签。 */
    public static final String FORM_EXTRA_ARGS = "form.extraArgs";

    /** 表单：附加参数的提示。 */
    public static final String FORM_EXTRA_ARGS_TIP = "form.extraArgsTip";

    /** 表单：还没有读取过帮助文本。 */
    public static final String FORM_NO_HELP_YET = "form.noHelpYet";

    /** 表单：该命令没有输出帮助（带命令占位）。 */
    public static final String FORM_NO_HELP_OUTPUT = "form.noHelpOutput";

    /** 表单：找不到匹配项。 */
    public static final String FORM_NO_MATCH = "form.noMatch";

    /** 表单：选项窗口的标题（带命令占位）。 */
    public static final String FORM_OPTIONS_WINDOW = "form.optionsWindow";

    /** 表单：关闭窗口并运行命令。 */
    public static final String FORM_RUN_THIS = "form.runThis";

    /** 表单：运行按钮的提示。 */
    public static final String FORM_RUN_THIS_TIP = "form.runThisTip";

    /** 表单：可在其中编辑的命令标签。 */
    public static final String FORM_COMMAND_LABEL = "form.commandLabel";

    /** 表单：命令预览的分区标题。 */
    public static final String FORM_COMMAND_PREVIEW = "form.commandPreview";

    /** 表单：原始参数标签。 */
    public static final String FORM_RAW_ARGS = "form.rawArgs";

    /** 表单：返回到顶层。 */
    public static final String FORM_BACK_TO_TOP = "form.backToTop";

    /** 表单：返回顶层按钮的提示（带命令占位）。 */
    public static final String FORM_BACK_TO_TOP_TIP = "form.backToTopTip";

    /** 表单：子命令选择标签。 */
    public static final String FORM_SUBCOMMAND = "form.subcommand";

    /** 表单：子命令下拉的提示（带命令占位）。 */
    public static final String FORM_SUBCOMMAND_TIP = "form.subcommandTip";

    /** 表单：子命令下拉里「顶层」那一项。 */
    public static final String FORM_TOP_LEVEL = "form.topLevel";

    /** 表单：子命令下拉里「此命令」的占位。 */
    public static final String FORM_THIS_COMMAND = "form.thisCommand";

    /** 表单：选择文件夹。 */
    public static final String FORM_CHOOSE_FOLDER = "form.chooseFolder";

    /** 表单：选择一个或多个文件。 */
    public static final String FORM_CHOOSE_FILES = "form.chooseFiles";

    /** 表单：选择写入位置。 */
    public static final String FORM_CHOOSE_SAVE = "form.chooseSave";

    /** 表单：选择一个文件。 */
    public static final String FORM_CHOOSE_FILE = "form.chooseFile";

    /** 表单：类型徽标的提示（带类型名占位）。类型名本身不翻译，见 SchemaFormPanel.label()。 */
    public static final String FORM_KIND_BADGE_TIP = "form.kindBadgeTip";

    /** 表单：类型下拉的提示。 */
    public static final String FORM_KIND_TIP = "form.kindTip";

    /** 表单：勾选以启用此开关。 */
    public static final String FORM_TICK_SWITCH = "form.tickSwitch";

    /** 表单：该参数没有列出可选值。 */
    public static final String FORM_NO_VALUES = "form.noValues";

    /** 表单：该参数被设为 None，故不显示。 */
    public static final String FORM_SET_TO_NONE = "form.setToNone";

    /** 表单：没有说明的参数（HTML）。 */
    public static final String FORM_NO_DESCRIPTION = "form.noDescription";

    /** 表单：无选项可读（HTML，带命令占位）。 */
    public static final String FORM_NO_OPTIONS_HTML = "form.noOptionsHtml";

    /** 表单：结构可识别但读不出选项（HTML，带命令占位）。 */
    public static final String FORM_UNREADABLE_HTML = "form.unreadableHtml";

    /** 表单：选项计数（带数量占位）。 */
    public static final String FORM_OPTION_COUNT = "form.optionCount";

    /** 表单：筛选后「显示 N / 共 M」（带两个占位）。 */
    public static final String FORM_OPTION_SHOWN_OF = "form.optionShownOf";

    /** 表单：选项列表的边框标题。 */
    public static final String FORM_OPTIONS_TITLE = "form.optionsTitle";

    /** 表单：提示先建工作目录再复制文件。 */
    public static final String FORM_NOTE_COPYDIR = "form.noteCopydir";

    /** 表单：复制失败（带文件数占位）。 */
    public static final String FORM_COPY_FAILED = "form.copyFailed";

    /** 表单：已复制到某目录（带目录占位）。 */
    public static final String FORM_COPIED_INTO = "form.copiedInto";

    /** 表单：复制了 N / M 个（带两个占位）。 */
    public static final String FORM_COPIED_OF = "form.copiedOf";

    /** 表单：其余文件读不出来。 */
    public static final String FORM_COPY_REST_UNREADABLE = "form.copyRestUnreadable";

    /** 表单：WSL 不可用，无法建工作目录。 */
    public static final String FORM_NO_WSL_FOR_WORKDIR = "form.noWslForWorkdir";

    /** 表单：无法在 WSL 里建工作目录。 */
    public static final String FORM_WORKDIR_FAILED = "form.workdirFailed";

    /** 表单：工作目录路径回显（带路径占位）。 */
    public static final String FORM_WORKDIR_SET = "form.workdirSet";

    /** 表单：参数说明的「参数」标记。 */
    public static final String FORM_DESC_ARG = "form.descArg";

    /** 表单：参数说明的「默认值」标记。 */
    public static final String FORM_DESC_DEFAULT = "form.descDefault";

    /** 表单：参数说明的「取值范围」标记。 */
    public static final String FORM_DESC_RANGE = "form.descRange";

    /** 表单：说明里错误行之前的标记。 */
    public static final String FORM_DESC_ERR = "form.descErr";

    /** 表单：已删除某个参数（带参数名占位）。 */
    public static final String FORM_REMOVED_FLAG = "form.removedFlag";

    /** 表单：状态行的「加载中」标记。 */
    public static final String FORM_STATUS_LOADING = "form.statusLoading";

    /** 表单：状态行的「帮助未描述任何参数」标记。 */
    public static final String FORM_STATUS_NO_OPTIONS = "form.statusNoOptions";

    /** 表单：状态行的「读不出参数」标记。 */
    public static final String FORM_STATUS_UNREADABLE = "form.statusUnreadable";

    /** 表单：状态行的「N 个参数」标记（带数量占位）。 */
    public static final String FORM_STATUS_OPTIONS = "form.statusOptions";

    /** 表单：状态行的「子命令」标记（带子命令占位）。 */
    public static final String FORM_STATUS_SUBCOMMAND = "form.statusSubcommand";

    /** 表单：状态行里环境名之前的连接符。 */
    public static final String FORM_STATUS_AT = "form.statusAt";

    /** 表单：状态行里的分隔符。 */
    public static final String FORM_STATUS_SEP = "form.statusSep";

    /** 表单：状态行里「未知」（格式无法识别时）。 */
    public static final String FORM_UNKNOWN = "form.unknown";

    /** 表单：该命令的帮助没有结构，无法生成表单。 */
    public static final String FORM_UNSTRUCTURED = "form.unstructured";

    /** 表单：帮助有结构但读不出参数（带格式占位）。 */
    public static final String FORM_STRUCTURED_UNREADABLE = "form.structuredUnreadable";

    /** 表单：子命令说明（有子命令时）。 */
    public static final String FORM_DESC_SUBCOMMANDS = "form.descSubcommands";

    /** 表单：子命令说明（无子命令时）。 */
    public static final String FORM_DESC_OPTIONS = "form.descOptions";

    // ------------------------------------------------------------------
    // MainPanel：WSL 专属提示（发现失败时）
    // ------------------------------------------------------------------

    /** 提示：一个 WSL 发行版都没有。 */
    public static final String HINT_NO_WSL_DISTRO = "hint.noWslDistro";

    /** 提示：只有容器引擎自带的系统（带发行版名列表占位）。 */
    public static final String HINT_ONLY_CONTAINER_DISTRO = "hint.onlyContainerDistro";

    /** 提示：连不上虚拟机时的通用建议。 */
    public static final String HINT_NO_CONNECTION = "hint.noConnection";

    /** 报告行：连不上虚拟机（替换扫描器给出的 WSL 说法，带目标占位）。 */
    public static final String NOTE_VM_UNREACHABLE = "note.vmUnreachable";

    /** 提示：检查虚拟机里的 sshd（服务名命令是技术标识符，不翻译）。 */
    public static final String HINT_SSH_SSHD = "hint.sshSshd";

    /** 提示：检查宿主机能否 ping 通虚拟机（带主机占位）。 */
    public static final String HINT_SSH_PING = "hint.sshPing";

    /** 提示：确认端口（带端口占位）。 */
    public static final String HINT_SSH_PORT = "hint.sshPort";

    /** 提示：检查私钥与权限。 */
    public static final String HINT_SSH_KEY = "hint.sshKey";
    /** 提示：用命令行先验证一次（带端口、密钥、用户、主机占位）。 */
    public static final String HINT_SSH_CLI = "hint.sshCli";

    // ------------------------------------------------------------------
    // MainPanel：对话框
    // ------------------------------------------------------------------

    /** 对话框标题：虚拟机连接配置。 */
    public static final String DIALOG_CONNECTION_TITLE = "dialog.connectionTitle";

    /** 对话框标题：参数编辑（带命令占位）。 */
    public static final String DIALOG_PARAMS_TITLE = "dialog.paramsTitle";

    /** 对话框：参数编辑里需要先选命令。 */
    public static final String DIALOG_SELECT_TOOL_FIRST = "dialog.selectToolFirst";

    /** 对话框：该命令的参数尚不可用。 */
    public static final String DIALOG_PARAMS_UNAVAILABLE = "dialog.paramsUnavailable";

    // ------------------------------------------------------------------
    // 工具助手（帮助原文 + 我的帮助）
    // ------------------------------------------------------------------

    /** 工具助手：窗口标题（带命令占位）。 */
    public static final String ASSISTANT_TITLE = "assistant.title";

    /** 工具助手：按钮文案。 */
    public static final String ASSISTANT_BUTTON = "assistant.button";

    /** 工具助手：标签页「帮助原文」。 */
    public static final String ASSISTANT_TAB_HELP = "assistant.tabHelp";

    /** 工具助手：标签页「我的帮助」。 */
    public static final String ASSISTANT_TAB_NOTES = "assistant.tabNotes";

    /** 工具助手：帮助原文页的说明。 */
    public static final String ASSISTANT_HELP_HINT = "assistant.helpHint";

    /** 工具助手：我的帮助页的说明。 */
    public static final String ASSISTANT_NOTES_HINT = "assistant.notesHint";

    /** 工具助手：明确告知备注不参与解析。 */
    public static final String ASSISTANT_NOTES_NOTE = "assistant.notesNote";

    /** 工具助手：我的帮助页还没有内容时的占位提示。 */
    public static final String ASSISTANT_NOTES_EMPTY = "assistant.notesEmpty";

    /** 工具助手：清空按钮。 */
    public static final String ASSISTANT_CLEAR = "assistant.clear";

    /** 工具助手：保存按钮。 */
    public static final String ASSISTANT_SAVE = "assistant.save";

    /** 工具助手：取消按钮。 */
    public static final String ASSISTANT_CANCEL = "assistant.cancel";

    /** 工具助手：已保存的状态提示。 */
    public static final String ASSISTANT_SAVED = "assistant.saved";

    // ------------------------------------------------------------------
    // 语言包本体
    // ------------------------------------------------------------------

    /**
     * 中文文案。
     *
     * <p>用 {@link LinkedHashMap} 而不是 {@code HashMap}：顺序稳定，
     * 排查「漏了哪条」时两份表可以逐行对照读。
     *
     * <p>键必须用 {@code Strings} 里声明的常量，不要在这里写字符串字面量，
     * 否则改键名时编译器帮不上忙。
     */
    private static final Map<String, String> ZH_TEXT = new LinkedHashMap<String, String>();

    /** 英文文案。键集合必须与 {@link #ZH_TEXT} 完全一致，见 {@link #missingKeys()}。 */
    private static final Map<String, String> EN_TEXT = new LinkedHashMap<String, String>();

    static {
        put(ZH_TEXT, EN_TEXT, MENU_DETECT, "扫描 conda 环境", "Detect conda environments");
        put(ZH_TEXT, EN_TEXT, MENU_REBUILD_PREVIEW, "从表单重建", "Rebuild from form");

        put(ZH_TEXT, EN_TEXT, STATUS_READY, "就绪。点击「扫描 conda 环境」。",
                "Ready. Click 'Detect conda environments'.");
        put(ZH_TEXT, EN_TEXT, STATUS_READY_NO_WSL,
                "就绪。WSL 可用时点击「扫描 conda 环境」。",
                "Ready. Click 'Detect conda environments' when WSL is available.");
        put(ZH_TEXT, EN_TEXT, STATUS_LOCATING_CONDA, "正在定位 conda 并枚举环境…",
                "Locating conda and enumerating environments...");
        put(ZH_TEXT, EN_TEXT, STATUS_SCANNING, "正在扫描环境中的命令…",
                "Scanning environments for commands...");
        put(ZH_TEXT, EN_TEXT, STATUS_FOUND_ENVS, "找到 {0} 个环境。",
                "Found {0} environment(s).");
        put(ZH_TEXT, EN_TEXT, STATUS_EXITED_WITH_CODE, "退出码 {0}，详见输出面板。",
                "Exited with code {0}. See the output pane.");

        put(ZH_TEXT, EN_TEXT, CONN_SOURCE, "来源", "Source");
        put(ZH_TEXT, EN_TEXT, CONN_HOST, "主机地址", "Host");
        put(ZH_TEXT, EN_TEXT, CONN_USER, "用户名", "User name");

        put(ZH_TEXT, EN_TEXT, ERR_PORT_INVALID, "端口号必须在 1 到 65535 之间，当前是 {0}。",
                "The port must be between 1 and 65535; it is currently {0}.");
        put(ZH_TEXT, EN_TEXT, ERR_CHOOSE_PROFILE, "请先选择一个已保存的 Linux 虚拟机配置。",
                "Choose a saved Linux VM profile first.");
        put(ZH_TEXT, EN_TEXT, ERR_PROFILE_MISSING, "配置「{0}」已不存在，请另选一个。",
                "Profile \"{0}\" no longer exists. Pick another one.");

        put(ZH_TEXT, EN_TEXT, HINT_MODE_BOX, "命令在哪里执行：本机 WSL，或通过 SSH 连接 Linux 虚拟机。",
                "Where commands run: local WSL, or a Linux VM over SSH.");
        put(ZH_TEXT, EN_TEXT, HINT_FILTER_FIELD, "只显示名称包含此文本的命令。",
                "Show only commands whose name contains this text.");
        put(ZH_TEXT, EN_TEXT, HINT_MANUAL_EDIT,
                "如果你熟悉这个工具，可以直接在这里输入。长路径显示为末级名称，实际执行的是完整路径。",
                "You can type here directly if you know the tool. "
                        + "Long paths are shown as their last name; the full path runs.");

        put(ZH_TEXT, EN_TEXT, LANG_SWITCH_TO_EN, "English", "English");
        put(ZH_TEXT, EN_TEXT, LANG_SWITCH_TO_ZH, "中文", "中文");
        put(ZH_TEXT, EN_TEXT, LANG_TOOLTIP, "点击切换界面语言 / Click to switch interface language",
                "Click to switch interface language / 点击切换界面语言");

        // --- MainPanel：按钮 ---
        put(ZH_TEXT, EN_TEXT, MENU_RUN, "运行", "Run");
        put(ZH_TEXT, EN_TEXT, MENU_STOP, "停止", "Stop");
        put(ZH_TEXT, EN_TEXT, MENU_CLEAR_LOG, "清空", "Clear");
        put(ZH_TEXT, EN_TEXT, MENU_EDIT_PARAMS, "编辑参数…", "Edit parameters...");
        put(ZH_TEXT, EN_TEXT, HINT_REBUILD_PREVIEW,
                "用表单当前描述的命令替换上面的文字。",
                "Replace the text above with the command the form currently describes.");

        // --- MainPanel：发现栏与面板标题 ---
        put(ZH_TEXT, EN_TEXT, CONN_MODE_LABEL, "连接方式", "Connection");
        put(ZH_TEXT, EN_TEXT, MODE_WSL, "WSL", "WSL");
        put(ZH_TEXT, EN_TEXT, MODE_VM, "Linux 虚拟机", "Linux VM");
        put(ZH_TEXT, EN_TEXT, CONN_LABEL_WSL, "WSL 发行版", "WSL distro");
        put(ZH_TEXT, EN_TEXT, CONN_LABEL_SAVED, "已保存配置", "Saved profile");
        put(ZH_TEXT, EN_TEXT, LABEL_FILTER, "工具检索", "Tool search");
        put(ZH_TEXT, EN_TEXT, LABEL_TREE_ROOT, "conda 环境", "Conda environments");
        put(ZH_TEXT, EN_TEXT, TITLE_TREE, "命令（按环境分组）", "Commands, by environment");
        put(ZH_TEXT, EN_TEXT, TITLE_OUTPUT, "输出", "Output");
        put(ZH_TEXT, EN_TEXT, TITLE_PREVIEW, "命令预览（这是将要运行的内容）",
                "Command preview (this is what you asked to run)");
        put(ZH_TEXT, EN_TEXT, TITLE_PREVIEW_EDITED,
                "命令预览（已手工编辑——表单不再改动它）",
                "Command preview (edited by hand - the form no longer changes it)");
        put(ZH_TEXT, EN_TEXT, HINT_COMMAND_AREA,
                "长路径显示为末级名称，实际执行的是完整路径。",
                "Long paths are shown as their last name. The full path is what runs.");
        put(ZH_TEXT, EN_TEXT, HINT_COMMAND_AREA_LONG,
                "如果你熟悉这个工具，可以直接在这里输入。长路径显示为末级名称，实际执行的是完整路径。",
                "You can type here directly if you know the tool. "
                        + "Long paths are shown as their last name; the full path runs.");
        put(ZH_TEXT, EN_TEXT, HINT_PREVIEW_EDITED,
                "命令预览已被手工编辑，不再跟随表单；点「从表单重建」可以交还给表单。",
                "Command preview edited by hand. It will no longer follow the form; "
                        + "press 'Rebuild from form' to hand it back.");
        put(ZH_TEXT, EN_TEXT, STATUS_PREVIEW_REBUILT, "命令预览已从表单重建。",
                "Command preview rebuilt from the form.");
        put(ZH_TEXT, EN_TEXT, HINT_DISTRO_BOX, "要使用的 WSL 发行版。",
                "The WSL distribution to use.");
        put(ZH_TEXT, EN_TEXT, HINT_PROFILE_BOX,
                "选一台已保存的虚拟机配置。第一次切到虚拟机方式时会自动建一条。",
                "Pick a saved Linux VM profile. One is created automatically "
                        + "the first time you switch to Linux VM.");

        // --- MainPanel：状态栏 ---
        put(ZH_TEXT, EN_TEXT, STATUS_PREPARING_CONNECTION, "正在准备连接…",
                "Preparing connection...");
        put(ZH_TEXT, EN_TEXT, STATUS_LOOKING_WSL, "正在查找 WSL 发行版…",
                "Looking for WSL distributions...");
        put(ZH_TEXT, EN_TEXT, STATUS_READING_PROFILES, "正在读取连接配置…",
                "Reading connection profiles...");
        put(ZH_TEXT, EN_TEXT, STATUS_CONNECTING, "正在连接到 {0}…", "Connecting to {0}...");
        put(ZH_TEXT, EN_TEXT, STATUS_EXITED_CODE, "退出码 {0}。详见输出面板。",
                "Exited with code {0}. See the output pane.");
        put(ZH_TEXT, EN_TEXT, STATUS_CANCELLED, "已取消。", "Cancelled.");
        put(ZH_TEXT, EN_TEXT, STATUS_RUNNING, "正在运行 {0}…", "Running {0}...");
        put(ZH_TEXT, EN_TEXT, STATUS_DISCOVERY_FAILED, "扫描失败：{0}", "Discovery failed: {0}");
        put(ZH_TEXT, EN_TEXT, STATUS_DISCOVERY_NO_RESULT, "扫描没有产出结果。",
                "Discovery produced no result.");
        put(ZH_TEXT, EN_TEXT, STATUS_RESTORE_FAILED, "无法恢复上次的结果。",
                "Could not restore previous results.");
        put(ZH_TEXT, EN_TEXT, STATUS_CHECKING_ENVS, "正在检查 {0} 里的环境…",
                "Checking environments in {0}...");
        put(ZH_TEXT, EN_TEXT, STATUS_USING_DISTRO, "正在使用发行版 {0}…",
                "Using distro: {0}");
        put(ZH_TEXT, EN_TEXT, STATUS_NO_CONDA, "没有找到可用的 conda 安装。",
                "No usable conda installation found.");
        put(ZH_TEXT, EN_TEXT, STATUS_NO_CONNECTION, "连不上 Linux 虚拟机，详见日志中的排查建议。",
                "Could not connect to the Linux VM. See the hints in the log.");
        put(ZH_TEXT, EN_TEXT, STATUS_NO_PROFILE_TO_SCAN,
                "还没有可用的 Linux 虚拟机配置，请先在上面选一个，或新建并保存一个。",
                "No Linux VM profile is available yet. Pick one above, or create and save one.");
        put(ZH_TEXT, EN_TEXT, STATUS_PROFILE_SELECTED, "已选择 {0}，点「扫描 conda 环境」开始扫描。",
                "Selected {0}. Click Detect to scan.");
        put(ZH_TEXT, EN_TEXT, STATUS_PROFILE_READ_FAILED, "读取连接配置失败：{0}",
                "Could not read connection profiles: {0}");
        put(ZH_TEXT, EN_TEXT, STATUS_NO_PROFILE, "还没有保存任何配置，填好后会创建一条：",
                "No saved Linux VM profile yet. Fill this in to create one:");
        put(ZH_TEXT, EN_TEXT, STATUS_PROFILES_LOADED, "已载入 {0} 个连接配置。选一个再点「扫描 conda 环境」。",
                "Loaded {0} connection profile(s). Pick one, then click Detect.");
        put(ZH_TEXT, EN_TEXT, STATUS_CANCELLING, "正在请求取消…", "Cancellation requested...");
        put(ZH_TEXT, EN_TEXT, STATUS_DONE_IN, "{0} 秒完成。", "Done in {0}s.");
        put(ZH_TEXT, EN_TEXT, STATUS_READING_HELP, "正在读取 {0} 的帮助…", "Reading help for {0}...");
        put(ZH_TEXT, EN_TEXT, STATUS_REMEMBERED_TYPE, "已记住：{0} {1} 是一个 {2}。",
                "Remembered: {0} {1} is a {2}.");
        put(ZH_TEXT, EN_TEXT, STATUS_SUB_OPTIONS, "{0} {1}：{2} 个选项。",
                "{0} {1}: {2} option(s).");
        put(ZH_TEXT, EN_TEXT, STATUS_SUB_NO_OPTIONS,
                "{0} {1}：读不出任何选项；请改用原始参数栏。",
                "{0} {1}: no options could be read; use the raw arguments box.");
        put(ZH_TEXT, EN_TEXT, STATUS_RESTORED, "已从上次会话恢复 {0} 个命令。",
                "Restored {0} command(s) from the previous session.");
        put(ZH_TEXT, EN_TEXT, STATUS_RESTORED_FOR, "已恢复 {0} 的命令列表。",
                "Restored the command list for {0}.");
        put(ZH_TEXT, EN_TEXT, STATUS_RESCANNING, "环境与上次不同，正在重新扫描…",
                "Environments changed since last time; rescanning...");
        put(ZH_TEXT, EN_TEXT, STATUS_NO_CACHE_SCAN, "没有上次结果，正在扫描环境…",
                "No previous results; scanning environments...");
        put(ZH_TEXT, EN_TEXT, STATUS_FIRST_RUN_GUIDE,
                "首次使用：请选择连接方式，然后点「扫描 conda 环境」开始。",
                "First run: choose how to connect, then click 'Detect conda "
                        + "environments' to begin.");
        put(ZH_TEXT, EN_TEXT, STATUS_NEEDS_FIRST_SCAN,
                "{0} 还没有扫描过，点「扫描 conda 环境」开始（扫完会记住，下次自动恢复）。",
                "{0} has not been scanned yet. Click 'Detect conda environments' "
                        + "to begin; the result is remembered and restored next time.");
        put(ZH_TEXT, EN_TEXT, STATUS_READY_COUNT, "就绪。{0} 个命令可用，来自 {1}。",
                "Ready. {0} command(s) available in {1}.");
        put(ZH_TEXT, EN_TEXT, STATUS_READY_ACROSS, "就绪。{0} 个命令，分布在 {1} 个环境里。",
                "Ready. {0} command(s) across {1} environment(s).");
        put(ZH_TEXT, EN_TEXT, STATUS_HELP_UNPARSED, "{0}：帮助内容无法解析。",
                "{0}: help could not be interpreted.");
        put(ZH_TEXT, EN_TEXT, STATUS_OPTIONS_FOUND, "{0}：发现了 {1} 个选项。",
                "{0}: {1} option(s) discovered");
        put(ZH_TEXT, EN_TEXT, STATUS_NO_DISPLAY,
                "当前没有图形界面，连接配置对话框打不开。配置保存在 {0}。",
                "No graphical display, so the connection dialog cannot open. "
                        + "Connection profiles live in {0}.");
        put(ZH_TEXT, EN_TEXT, STATUS_FAILED, "失败：{0}", "Failed: {0}");
        put(ZH_TEXT, EN_TEXT, STATUS_PARAMS_UPDATED, "参数已更新。", "Parameters updated.");
        put(ZH_TEXT, EN_TEXT, STATUS_HELP_REREAD,
                "已重新读取工具的帮助，参数恢复为它声明的方式。",
                "Re-read the tool's help; its parameters are back to how "
                        + "the tool declares them.");
        put(ZH_TEXT, EN_TEXT, STATUS_HELP_REREAD_FAILED, "无法重新读取工具的帮助。",
                "Could not re-read the tool's help.");
        put(ZH_TEXT, EN_TEXT, STATUS_PARAMS_DELETED, "已删除 {0} 个自定义参数。",
                "Deleted {0} parameter(s).");
        put(ZH_TEXT, EN_TEXT, STATUS_PARAMS_RENAMED, "已重命名 {0} 个自定义参数。",
                "Renamed {0} parameter(s).");
        put(ZH_TEXT, EN_TEXT, STATUS_SELECT_TOOL_FIRST,
                "请先选一个命令。这个面板只编辑那一个命令的参数。",
                "Select a tool first. This panel edits the parameters of one tool.");
        put(ZH_TEXT, EN_TEXT, STATUS_PARAMS_UNAVAILABLE,
                "这个命令的参数还不可用。先打开它，然后重试。",
                "This tool's parameters are not available yet. Open it and try again.");
        put(ZH_TEXT, EN_TEXT, STATUS_TREE_SUMMARY, "已列出 {0} 个命令，共 {1} 个环境",
                "{0} command(s) in {1} environment(s)");
        put(ZH_TEXT, EN_TEXT, STATUS_TREE_MATCHING, "，匹配「{0}」",
                " matching \"{0}\"");
        put(ZH_TEXT, EN_TEXT, STATUS_TREE_FOLDED,
                "；另有 {0} 个已折叠——点「全部展开」那一行，或在过滤框里输入",
                "; {0} folded into collapsed groups - click a \"Show all\" "
                        + "row, or type in the filter box");
        put(ZH_TEXT, EN_TEXT, TREE_PACKAGE_COUNT, "{0} 个命令", "{0} commands");
        put(ZH_TEXT, EN_TEXT, STATUS_SUBCOMMAND_COUNT, "，{0} 个子命令",
                ", {0} subcommand(s)");

        // --- MainPanel：日志 ---
        put(ZH_TEXT, EN_TEXT, LOG_COMMAND_DISCOVERY, "=== 命令发现 ===",
                "=== Command discovery ===");
        put(ZH_TEXT, EN_TEXT, LOG_SCAN_DELTA,
                "本次扫描：新增 {0} 个、移除 {1} 个、原样保留 {2} 个"
                        + "（保留的连同你在其中做过的设置）、因版本变化重新读取 {3} 个。",
                "This scan: {0} added, {1} removed, {2} kept as they were "
                        + "(settings included), {3} re-read because the version changed.");
        put(ZH_TEXT, EN_TEXT, LOG_SCANNED_AT, "上次扫描：{0}", "Last scanned: {0}");
        put(ZH_TEXT, EN_TEXT, LOG_NO_CACHE_MANUAL,
                "这个目标还没有扫描过，所以没有可直接恢复的列表。"
                        + "请点「扫描 conda 环境」开始（扫完就会记住，下次自动恢复）。",
                "This target has never been scanned, so there is nothing to restore. "
                        + "Click Detect to scan it once; from then on it is remembered "
                        + "and restored automatically.");
        put(ZH_TEXT, EN_TEXT, STATUS_VM_NOT_REACHABLE,
                "虚拟机不可达，未自动连接。详见日志。",
                "The Linux VM is not reachable, so nothing was connected. See the log.");
        put(ZH_TEXT, EN_TEXT, LOG_REACH_HEAD,
                "自动连接前先检查虚拟机是否可达：",
                "Checking whether the VM is reachable before connecting automatically:");
        put(ZH_TEXT, EN_TEXT, LOG_REACH_REFUSED,
                "  主机是活的，但 {0} 上没有服务在听——sshd 多半还没启动完。"
                        + "等几秒再点「扫描 conda 环境」即可。",
                "  The host is up, but nothing is listening on {0} - sshd has probably "
                        + "not finished starting. Wait a few seconds, then click Detect.");
        put(ZH_TEXT, EN_TEXT, LOG_REACH_TIMEOUT,
                "  连不上 {0}，对端没有应答。常见原因：虚拟机没开机、还在启动中、"
                        + "IP 变了、或被防火墙丢弃。确认之后点「扫描 conda 环境」重试。",
                "  No answer from {0}. Usual causes: the VM is powered off, still "
                        + "booting, its address changed, or a firewall is dropping the "
                        + "traffic. Once it is up, click Detect to retry.");
        put(ZH_TEXT, EN_TEXT, LOG_REACH_UNRESOLVED,
                "  无法解析或到达 {0}。请检查连接配置里的主机地址是否填对。",
                "  {0} could not be resolved or reached. Check the host address in the "
                        + "connection profile.");
        put(ZH_TEXT, EN_TEXT, LOG_ENV_CHECK, "=== 环境检查 ===", "=== Environment check ===");
        put(ZH_TEXT, EN_TEXT, LOG_HINT_PREFIX, "  提示：", "  HINT: ");
        put(ZH_TEXT, EN_TEXT, LOG_ERROR_PREFIX, "错误：", "ERROR: ");
        put(ZH_TEXT, EN_TEXT, LOG_RESTORED_FOR, "已恢复 {0} 的命令列表。",
                "Restored the command list for {0}.");
        put(ZH_TEXT, EN_TEXT, LOG_DETECT_FOR_NEW,
                "点「扫描 conda 环境」可以拾取新装的工具。",
                "Click 'Detect conda environments' to pick up newly installed tools.");
        put(ZH_TEXT, EN_TEXT, LOG_NO_CONDA_VM,
                "虚拟机 {0} 上没有找到 conda 安装，所以没有可列出的命令。请在虚拟机里装好 "
                        + "Miniconda 并至少创建一个环境，然后重新扫描。",
                "No conda installation was found on Linux VM {0}, so there is nothing "
                        + "to list. Install Miniconda inside the VM and create at least "
                        + "one environment, then detect again.");
        put(ZH_TEXT, EN_TEXT, LOG_NO_CONDA_WSL,
                "WSL 里没有找到 conda 安装，所以没有可列出的命令。请在 WSL 里装好 "
                        + "Miniconda 并至少创建一个环境，然后重新扫描。",
                "No conda installation was found in WSL, so there is nothing to list. "
                        + "Install Miniconda inside WSL and create at least one "
                        + "environment, then detect again.");
        put(ZH_TEXT, EN_TEXT, LOG_UNREACHABLE_VM,
                "无法扫描：连不上虚拟机 {0}。请按上面的提示排查，然后重新扫描。",
                "Could not scan: no connection to Linux VM {0}. "
                        + "Work through the hints above, then detect again.");
        put(ZH_TEXT, EN_TEXT, LOG_NO_PROFILE,
                "无法扫描：还没有选中可用的 Linux 虚拟机配置。请先在「已保存配置」里选一个"
                        + "（没有就点连接窗口里的「保存配置」新建一个），然后重新扫描。",
                "Could not scan: no usable Linux VM profile is selected. Choose one in the "
                        + "\"Saved profiles\" box first (or create one with \"Save\" in the "
                        + "connection window), then detect again.");
        put(ZH_TEXT, EN_TEXT, LOG_UNREACHABLE_WSL,
                "无法扫描：WSL 不可达。请按上面的提示处理，然后重新扫描。",
                "Nothing could be scanned because WSL was not reachable. "
                        + "See the hint above, then detect again.");
        put(ZH_TEXT, EN_TEXT, LOG_PICK_COMMAND,
                "在左侧选一个命令。它的选项是从命令自己的帮助输出里读出来的，"
                        + "所以 conda 环境里装了什么就能用什么，不需要额外配置。",
                "Pick a command on the left. Its options are read from the "
                        + "command's own help output, so anything installed in a conda "
                        + "environment is usable without configuration.");
        put(ZH_TEXT, EN_TEXT, LOG_RUN_STARTED, "--- 开始运行：{0} ---", "--- run started: {0} ---");
        put(ZH_TEXT, EN_TEXT, LOG_RUN_CANCELLED, "--- 已取消，运行 {0} 秒 ---",
                "--- cancelled after {0}s ---");
        put(ZH_TEXT, EN_TEXT, LOG_RUN_FINISHED, "--- 完成，用时 {0} 秒 ---",
                "--- finished in {0}s ---");
        put(ZH_TEXT, EN_TEXT, LOG_RUN_EXITED, "--- 退出码 {0}，用时 {1} 秒 ---",
                "--- exited with code {0} after {1}s ---");
        put(ZH_TEXT, EN_TEXT, LOG_RUN_HINT,
                "提示：检查必需参数是否都填了、路径是否存在。实际运行的命令就在上面的预览里。",
                "Hint: check that required arguments are present and that paths exist. "
                        + "The command that ran is shown in the preview above.");
        put(ZH_TEXT, EN_TEXT, LOG_CANNOT_BUILD, "（无法构建命令：{0}）",
                "(cannot build command: {0})");
        put(ZH_TEXT, EN_TEXT, LOG_HELP_UNPARSED,
                "注意：无法解析 {0} 的帮助输出。它仍然可以通过原始参数栏运行。",
                "Note: could not interpret the help output of {0}. "
                        + "It is still runnable via the raw arguments box.");

        // --- MainPanel：提示 ---
        put(ZH_TEXT, EN_TEXT, HINT_NO_WSL_DISTRO,
                "没有找到任何 WSL 发行版。请在管理员终端里运行：wsl --install -d Ubuntu",
                "No WSL distribution found. Run "
                        + "'wsl --install -d Ubuntu' in an elevated terminal.");
        put(ZH_TEXT, EN_TEXT, HINT_ONLY_CONTAINER_DISTRO,
                "WSL 里没有能承载工具的发行版——只有容器引擎自带的系统（{0}），"
                        + "它没有 bash。请装一个真正的发行版：wsl --install -d Ubuntu",
                "WSL holds no distribution that can host tools - only a container "
                        + "engine's own system ({0}), which has no bash. "
                        + "Install a real one: 'wsl --install -d Ubuntu'.");
        put(ZH_TEXT, EN_TEXT, HINT_NO_CONNECTION,
                "请确认虚拟机的 IP、端口、用户名，以及虚拟机处于开机状态。",
                "Check the VM's IP, port and user, and that the VM is powered on.");
        put(ZH_TEXT, EN_TEXT, NOTE_VM_UNREACHABLE, "无法连接到虚拟机 {0}。",
                "Could not connect to Linux VM {0}.");
        put(ZH_TEXT, EN_TEXT, HINT_SSH_SSHD,
                "检查虚拟机里 sshd 是否在运行：systemctl status ssh（或 service ssh status）。",
                "Check that sshd is running inside the VM: systemctl status ssh "
                        + "(or: service ssh status).");
        put(ZH_TEXT, EN_TEXT, HINT_SSH_PING,
                "检查宿主机能否 ping 通 {0}；不通多半是 VMware 网络模式问题（NAT 需要端口转发）。",
                "Check that the host is reachable: ping {0} from Windows. "
                        + "If that fails it is usually the VMware network mode "
                        + "(NAT needs a port forward).");
        put(ZH_TEXT, EN_TEXT, HINT_SSH_PORT,
                "端口填的是 {0}，请确认 sshd 确实监听这个端口（ss -lntp | grep sshd）。",
                "The port is set to {0} - confirm sshd really listens there "
                        + "(ss -lntp | grep sshd).");
        put(ZH_TEXT, EN_TEXT, HINT_SSH_KEY,
                "检查私钥与权限：私钥路径要指向真实文件，远端 ~/.ssh 需 700、"
                        + "authorized_keys 需 600。",
                "Check the key and its permissions: the key path must point at a "
                        + "real file, and on the VM ~/.ssh must be 700 with "
                        + "authorized_keys 600.");
        put(ZH_TEXT, EN_TEXT, HINT_SSH_CLI,
                "先用命令行验证一次：ssh -p {0}{1} {2}@{3}；命令行能通说明问题在插件参数上。",
                "Verify once from the command line: ssh -p {0}{1} {2}@{3} - "
                        + "if that works, the problem is in the plugin's arguments.");

        // --- MainPanel：对话框 ---
        put(ZH_TEXT, EN_TEXT, DIALOG_CONNECTION_TITLE, "虚拟机连接配置",
                "Linux VM Connection Profile");
        put(ZH_TEXT, EN_TEXT, DIALOG_PARAMS_TITLE, "参数 - {0}", "Parameters - {0}");
        put(ZH_TEXT, EN_TEXT, DIALOG_SELECT_TOOL_FIRST,
                "请先选一个命令。这个面板只编辑那一个命令的参数。",
                "Select a tool first. This panel edits the parameters of one tool.");
        put(ZH_TEXT, EN_TEXT, DIALOG_PARAMS_UNAVAILABLE,
                "这个命令的参数还不可用。先打开它，然后重试。",
                "This tool's parameters are not available yet. Open it and try again.");

        // --- 工具助手 ---
        put(ZH_TEXT, EN_TEXT, ASSISTANT_TITLE, "工具助手 - {0}",
                "Tool assistant - {0}");
        put(ZH_TEXT, EN_TEXT, ASSISTANT_BUTTON, "工具助手", "Tool assistant");
        put(ZH_TEXT, EN_TEXT, ASSISTANT_TAB_HELP, "帮助原文", "Original help");
        put(ZH_TEXT, EN_TEXT, ASSISTANT_TAB_NOTES, "我的帮助", "My notes");
        put(ZH_TEXT, EN_TEXT, ASSISTANT_HELP_HINT,
                "工具自己打印的文本，一字不改。",
                "The text the tool itself printed, kept as it came.");
        put(ZH_TEXT, EN_TEXT, ASSISTANT_NOTES_HINT,
                "写下工具没讲清楚的地方，或你自己的备注。只对这个工具有效。",
                "Write down what the tool does not explain, or your own reminders. "
                        + "Only this tool sees them.");
        put(ZH_TEXT, EN_TEXT, ASSISTANT_NOTES_NOTE,
                "备注只供查看，不会参与参数解析，也不会改变上面的参数表。",
                "Notes are for reading only: they are not parsed and never change "
                        + "the parameter form.");
        put(ZH_TEXT, EN_TEXT, ASSISTANT_NOTES_EMPTY,
                "还没有写过备注。可以记下常用参数、容易踩的坑、或者一段能跑通的命令。",
                "No notes yet. Useful things to keep here: the parameters you always "
                        + "use, a trap you hit, or a command line that worked.");
        put(ZH_TEXT, EN_TEXT, ASSISTANT_CLEAR, "清空", "Clear");
        put(ZH_TEXT, EN_TEXT, ASSISTANT_SAVE, "保存", "Save");
        put(ZH_TEXT, EN_TEXT, ASSISTANT_CANCEL, "取消", "Cancel");
        put(ZH_TEXT, EN_TEXT, ASSISTANT_SAVED, "已保存 {0} 的备注。",
                "Notes for {0} saved.");

        // --- FileDialogs ---
        put(ZH_TEXT, EN_TEXT, FILE_OVERWRITE_QUESTION,
                "文件已存在，要覆盖吗？\n{0}", "Overwrite existing file?\n{0}");
        put(ZH_TEXT, EN_TEXT, FILE_OVERWRITE_TITLE, "确认覆盖", "Confirm overwrite");

        // --- CollapsiblePanel ---
        put(ZH_TEXT, EN_TEXT, COLLAPSE_SHOW, "展开{0}", "Show {0}");
        put(ZH_TEXT, EN_TEXT, COLLAPSE_HIDE, "收起{0}", "Hide {0}");

        // --- ParameterEditorPanel ---
        put(ZH_TEXT, EN_TEXT, PED_INTRO,
                "<html>修正帮助信息里关于 <b>{0}</b> 的错误。<br>"
                        + "拖动行可以调整顺序、修改类型，或删掉不存在的参数。"
                        + "在你按「确定」之前不会生效。</html>",
                "<html>Correct what the help got wrong for <b>{0}</b>.<br>"
                        + "Drag a row to reorder it, change a type, or remove a parameter "
                        + "that is not real. Nothing changes until you press OK.</html>");
        put(ZH_TEXT, EN_TEXT, PED_ADD, "+ 添加参数", "+ Add parameter");
        put(ZH_TEXT, EN_TEXT, PED_ADD_TIP, "添加一个帮助信息里没有描述的参数。",
                "Add a parameter the help does not describe.");
        put(ZH_TEXT, EN_TEXT, PED_RESET, "重置此工具", "Reset this tool");
        put(ZH_TEXT, EN_TEXT, PED_RESET_TIP,
                "重新读取此工具的帮助并重新列出参数，丢弃在这里做过的所有修改。",
                "Read this tool's help again and list its parameters afresh, "
                        + "discarding every change made here.");
        put(ZH_TEXT, EN_TEXT, PED_RESET_CONFIRM,
                "重新读取此工具的帮助？\n\n"
                        + "在这里做过的所有修改都会被丢弃——新增的参数、删除、"
                        + "重命名、改写的说明、选定的类型——列表会按工具自己声明的内容重建。"
                        + "其它工具不受影响。",
                "Read this tool's help again?\n\n"
                        + "Every change made here is discarded - added parameters, deletions, "
                        + "renamed parameters, rewritten descriptions and chosen types - and "
                        + "the list is rebuilt from what the tool itself declares. No other "
                        + "tool is affected.");
        put(ZH_TEXT, EN_TEXT, PED_KEEP, "保留", "Keep");
        put(ZH_TEXT, EN_TEXT, PED_LIST_TITLE, "参数", "Parameters");
        put(ZH_TEXT, EN_TEXT, PED_OK, "确定", "OK");
        put(ZH_TEXT, EN_TEXT, PED_CANCEL, "取消", "Cancel");
        put(ZH_TEXT, EN_TEXT, PED_COL_PARAM, "参数", "Parameter");
        put(ZH_TEXT, EN_TEXT, PED_COL_DESC, "说明", "Description");
        put(ZH_TEXT, EN_TEXT, PED_ADD_DIALOG_TITLE, "添加参数", "Add a parameter");
        put(ZH_TEXT, EN_TEXT, PED_ADD_PROMPT,
                "<html><i>具名选项写 <b>-x</b> 或 <b>--threads</b>。<br>"
                        + "按位置传的参数，用尖括号写参数自己的名字，"
                        + "例如 <b>&lt;参数名&gt;</b>。<br>"
                        + "尖括号表示它没有 flag，不要在命令行里照打。<br>说明可以留空。</i></html>",
                "<html><i>For a named option write <b>-x</b> or <b>--threads</b>.<br>"
                        + "For an argument given by position write the parameter's own "
                        + "name in angle brackets, <b>&lt;parameter name&gt;</b>."
                        + "<br>The brackets say it has no flag; they are not typed on the "
                        + "command line.<br>The description is optional.</i></html>");
        put(ZH_TEXT, EN_TEXT, PED_NAME_REQUIRED,
                "参数需要一个名称。\n\n"
                        + "具名选项写 -x；按位置传的参数写它自己的名字并用尖括号括起来，"
                        + "例如 <参数名>。",
                "A parameter needs a name.\n\n"
                        + "Write -x for a named option, or the parameter's own name "
                        + "in angle brackets such as <parameter name> for an argument "
                        + "given by position.");
        // 汇总行的三段拼装：数量、已删除数、是否已保存过编辑
        put(ZH_TEXT, EN_TEXT, PED_SUMMARY_COUNT, "{0} 个参数", "{0} parameter(s)");
        put(ZH_TEXT, EN_TEXT, PED_SUMMARY_REMOVED, "已删除 {0} 个", "{0} removed");
        put(ZH_TEXT, EN_TEXT, PED_SUMMARY_EDITED, "此工具已有保存的编辑",
                "this tool has saved edits");
        put(ZH_TEXT, EN_TEXT, PED_ARG_PLACEHOLDER, "（位置参数）", "(argument)");
        put(ZH_TEXT, EN_TEXT, PED_DRAG_TIP, "拖动可以调整这个参数的位置。",
                "Drag to move this parameter.");
        put(ZH_TEXT, EN_TEXT, PED_NAME_TIP,
                "程序实际接受的写法，例如 -h。如果帮助里读错了可以改。"
                        + "把参数自己的名字写进尖括号（例如 <参数名>）就表示它是"
                        + "按位置传的参数，程序读它时不带 flag。",
                "The parameter as the program accepts it, e.g. -h. "
                        + "Change it if the help was read wrongly. Write the parameter's own "
                        + "name in angle brackets, such as <parameter name>, to make it an "
                        + "argument given by position, which the program reads without a flag.");
        put(ZH_TEXT, EN_TEXT, PED_DESC_TIP, "这个参数是做什么的。会显示在表单里它旁边。",
                "What this parameter does. Shown beside it in the form.");
        put(ZH_TEXT, EN_TEXT, PED_KIND_TIP, "这个参数接受什么类型的值。",
                "What kind of value this parameter takes.");
        put(ZH_TEXT, EN_TEXT, PED_DELETE_ADDED_TIP, "删除这个参数。",
                "Delete this parameter.");
        put(ZH_TEXT, EN_TEXT, PED_REMOVE_TIP,
                "把这个参数从列表里移除。之后可以用「恢复已移除」或「重置此工具」找回来。",
                "Remove this parameter from the list. You can bring it back with "
                        + "Restore removed, or by pressing Reset this tool.");
        put(ZH_TEXT, EN_TEXT, PED_ALREADY_LISTED, "「{0}」已经在列表里了。",
                "'{0}' is already in the list.");
        put(ZH_TEXT, EN_TEXT, PED_CHANGED_ADDED, "新增 {0}", "added {0}");
        put(ZH_TEXT, EN_TEXT, PED_CHANGED_REMOVED, "移除 {0}", "removed {0}");
        put(ZH_TEXT, EN_TEXT, PED_CHANGED_RESTORED, "恢复 {0}", "restored {0}");
        put(ZH_TEXT, EN_TEXT, PED_CHANGED_RETYPED, "改类型 {0}", "retyped {0}");
        put(ZH_TEXT, EN_TEXT, PED_CHANGED_RENAMED, "重命名 {0}", "renamed {0}");
        put(ZH_TEXT, EN_TEXT, PED_CHANGED_DESCRIBED, "改说明 {0}", "described {0}");
        put(ZH_TEXT, EN_TEXT, PED_CHANGES_PREFIX, "参数变更：{0}。",
                "Parameters: {0}.");
        put(ZH_TEXT, EN_TEXT, PED_DELETE_CONFIRM,
                "要彻底删除「{0}」吗？\n\n"
                        + "该参数会从此工具中移除，不会恢复。工具自己的帮助文本不受影响；"
                        + "「重置此工具」可以把它找回来。",
                "Delete '{0}' for good?\n\n"
                        + "The parameter is dropped for this tool and will not come "
                        + "back. The tool's help text is left alone; 'Reset this tool' "
                        + "brings it back.");
        put(ZH_TEXT, EN_TEXT, PED_DELETE_FOREVER, "彻底删除", "Delete for good");
        put(ZH_TEXT, EN_TEXT, PED_REMOVED_LABEL,
                "<html><strike>{0}</strike> <i>已删除</i></html>",
                "<html><strike>{0}</strike> <i>removed</i></html>");
        put(ZH_TEXT, EN_TEXT, PED_PURGE_TIP,
                "彻底删除「{0}」：会从工具的帮助文本里移除，不会再回来。"
                        + "「重置此工具」可以恢复。",
                "Delete '{0}' for good: it is removed from the tool's help text, "
                        + "so it does not come back. Reset this tool brings it back.");

        // --- SchemaFormPanel ---
        put(ZH_TEXT, EN_TEXT, FORM_SEARCH_TIP,
                "只显示名称、别名或说明里包含这段文字的参数。",
                "Show only options whose name, alias or description contains this text.");
        put(ZH_TEXT, EN_TEXT, FORM_FIND, "查找", "Find");
        put(ZH_TEXT, EN_TEXT, FORM_OPEN_WINDOW, "在新窗口中打开", "Open in a new window");
        put(ZH_TEXT, EN_TEXT, FORM_OPEN_WINDOW_TIP,
                "把这些参数放进一个大窗口里显示。",
                "Show these options in a large window of their own.");
        put(ZH_TEXT, EN_TEXT, FORM_NO_TOOL, "未选择工具", "No tool selected");
        put(ZH_TEXT, EN_TEXT, FORM_PICK_TOOL, "请在左侧列表里选一个工具。",
                "Pick a tool from the list on the left.");
        put(ZH_TEXT, EN_TEXT, FORM_SELECT_TOOL, "选一个工具即可配置它。",
                "Select a tool to configure it.");
        put(ZH_TEXT, EN_TEXT, FORM_READING_SUB, "正在读取「{0}」的帮助…",
                "Reading help for '{0}'...");
        put(ZH_TEXT, EN_TEXT, FORM_LOADING, "正在读取该子命令的帮助…",
                "Reading that subcommand's help...");
        put(ZH_TEXT, EN_TEXT, FORM_SUB_NO_OPTIONS, "该子命令没有可配置的参数。",
                "This subcommand exposes no options.");
        put(ZH_TEXT, EN_TEXT, FORM_WORKDIR, "工作目录", "Working dir");
        put(ZH_TEXT, EN_TEXT, FORM_WORKDIR_TIP,
                "<html>命令运行时所在目录。<br>"
                        + "Linux 侧的路径（如 <b>/home/you/work</b>）比 Windows 路径"
                        + "（<b>C:\\...</b>）快得多：Windows 磁盘是经共享目录访问的，"
                        + "需要写很多小文件的工具会很慢。<br>"
                        + "要拿到完整收益，<i>输入和输出文件</i>也应该放在那里，"
                        + "而不只是这一个设置。</html>",
                "<html>Directory the command runs in.<br>"
                        + "A path on the Linux side (e.g. <b>/home/you/work</b>) is much "
                        + "faster than a Windows one (<b>C:\\...</b>): the Windows disks are "
                        + "reached over a share, and tools that write many small files are "
                        + "slow through it.<br>"
                        + "For the full benefit the <i>input and output files</i> should be "
                        + "there too, not just this setting.</html>");
        put(ZH_TEXT, EN_TEXT, FORM_IN_WSL, "在 Linux 侧新建", "Create on Linux");
        put(ZH_TEXT, EN_TEXT, FORM_IN_WSL_TIP,
                "在 Linux 侧建一个工作目录并填到这里。对需要写大量文件的工具快很多。",
                "Make a working directory on the Linux side, and use it here. "
                        + "Much faster for tools that write many files.");
        put(ZH_TEXT, EN_TEXT, FORM_CHOOSE_WORKDIR, "选择本机的目录",
                "Choose a folder on this computer");
        put(ZH_TEXT, EN_TEXT, FORM_EXTRA_ARGS, "附加参数（每行一个）",
                "Additional arguments (one per line)");
        put(ZH_TEXT, EN_TEXT, FORM_EXTRA_ARGS_TIP,
                "表单里没有显示的参数，每行写一个。它们会按书写顺序追加到命令后面，"
                        + "所以帮助信息里从未描述过的参数也能传进去。",
                "Arguments the form does not show, written one per line. "
                        + "They are appended to the command in the order written, so a "
                        + "parameter the help never described can still be passed.");
        put(ZH_TEXT, EN_TEXT, FORM_NO_HELP_YET, "还没有读取过这个命令的帮助文本。",
                "No help text has been read for this command yet.");
        put(ZH_TEXT, EN_TEXT, FORM_NO_HELP_OUTPUT, "{0} 没有输出任何帮助文本。\n\n"
                        + "有些工具只在别处说明自己：可以试着不带参数直接运行，"
                        + "或者查阅它的手册。",
                "{0} printed no help text.\n\n"
                        + "Some tools document themselves only elsewhere: try running "
                        + "the command with no arguments, or consult its manual.");
        put(ZH_TEXT, EN_TEXT, FORM_NO_MATCH, "无匹配", "no match");
        put(ZH_TEXT, EN_TEXT, FORM_OPTIONS_WINDOW, "选项 - {0}", "Options \u2014 {0}");
        put(ZH_TEXT, EN_TEXT, FORM_RUN_THIS, "运行此命令", "Run this command");
        put(ZH_TEXT, EN_TEXT, FORM_RUN_THIS_TIP, "关闭此窗口并运行上面显示的命令。",
                "Close this window and run the command shown above.");
        put(ZH_TEXT, EN_TEXT, FORM_COMMAND_LABEL,
                "命令（可以修改后再运行）", "Command (you can edit this, then run it)");
        put(ZH_TEXT, EN_TEXT, FORM_COMMAND_PREVIEW, "命令预览", "Command preview");
        put(ZH_TEXT, EN_TEXT, FORM_RAW_ARGS, "原始参数", "Raw arguments");
        put(ZH_TEXT, EN_TEXT, FORM_BACK_TO_TOP, "返回顶层", "Back to top level");
        put(ZH_TEXT, EN_TEXT, FORM_BACK_TO_TOP_TIP, "回到 {0} 的参数。",
                "Go back to the options of {0} itself.");
        put(ZH_TEXT, EN_TEXT, FORM_SUBCOMMAND, "子命令", "Subcommand");
        put(ZH_TEXT, EN_TEXT, FORM_SUBCOMMAND_TIP,
                "选择此命令的一个子命令，或选「{0}」来配置命令本身。",
                "Choose one of this command's subcommands, or '{0}' "
                        + "to configure the command itself.");
        put(ZH_TEXT, EN_TEXT, FORM_TOP_LEVEL, "（顶层）", "(top level)");
        put(ZH_TEXT, EN_TEXT, FORM_THIS_COMMAND, "此命令", "this command");
        put(ZH_TEXT, EN_TEXT, FORM_CHOOSE_FOLDER, "请选择一个文件夹。", "Choose a folder.");
        put(ZH_TEXT, EN_TEXT, FORM_CHOOSE_FILES,
                "请选择一个或多个文件。它们会写成逗号分隔的一个参数。",
                "Choose one or more files. They are written as a comma-separated "
                        + "single argument.");
        put(ZH_TEXT, EN_TEXT, FORM_CHOOSE_SAVE, "请选择文件的写入位置。",
                "Choose where to write the file.");
        put(ZH_TEXT, EN_TEXT, FORM_CHOOSE_FILE, "请选择一个文件。", "Choose a file.");
        put(ZH_TEXT, EN_TEXT, FORM_KIND_BADGE_TIP,
                "该参数的类型是「{0}」。点击可以修改。",
                "This parameter is set to '{0}'. Click to change it.");
        put(ZH_TEXT, EN_TEXT, FORM_KIND_TIP,
                "这个参数接受什么类型的值。行内控件会随之改变，"
                        + "而且你的选择会为这个命令记住。",
                "What kind of value this option takes. The row changes "
                        + "to match, and your choice is remembered for this command.");
        put(ZH_TEXT, EN_TEXT, FORM_TICK_SWITCH, "勾选以启用这个开关",
                "Tick to include this switch");
        put(ZH_TEXT, EN_TEXT, FORM_NO_VALUES,
                "这个参数没有列出可选值，请直接输入一个。",
                "No values were listed for this parameter, so type one.");
        put(ZH_TEXT, EN_TEXT, FORM_SET_TO_NONE, "未显示：该参数被设为 None。",
                "Not shown: this parameter is set to None.");
        put(ZH_TEXT, EN_TEXT, FORM_NO_DESCRIPTION,
                "没有说明：这个参数只在命令的用法行里出现过，"
                        + "而工具对它没有任何解释。",
                "No description: this option is named only in the "
                        + "command's usage line, which the tool documents without "
                        + "explanation.");
        put(ZH_TEXT, EN_TEXT, FORM_OPTION_COUNT, "{0} 个参数", "{0} option(s)");
        // 筛选后的「显示 N / 共 M」整条做模板：中英语序不同，逐段拼接会成病句。
        put(ZH_TEXT, EN_TEXT, FORM_OPTION_SHOWN_OF, "显示 {0} / 共 {1}",
                "{0} of {1}");
        put(ZH_TEXT, EN_TEXT, FORM_OPTIONS_TITLE, "选项", "Options");
        put(ZH_TEXT, EN_TEXT, FORM_NOTE_COPYDIR,
                "请先点企鹅图标，在 Linux 侧建一个工作目录；之后这个文件会自动复制过去。",
                "Press the penguin first to make a working directory on the Linux "
                        + "side; this file is then copied across for you.");
        put(ZH_TEXT, EN_TEXT, FORM_COPY_FAILED,
                "无法复制 {0} 个文件；路径保持原样未改。",
                "Could not copy {0} file(s); the path was left as it was.");
        put(ZH_TEXT, EN_TEXT, FORM_COPIED_INTO, "已复制到 {0}", "Copied into {0}");
        put(ZH_TEXT, EN_TEXT, FORM_COPIED_OF, "已复制 {0} / {1} 个",
                "Copied {0} of {1}");
        put(ZH_TEXT, EN_TEXT, FORM_COPY_REST_UNREADABLE, "；其余文件读不出来。",
                "; the rest could not be read.");
        put(ZH_TEXT, EN_TEXT, FORM_NO_WSL_FOR_WORKDIR,
                "当前没有连上 Linux 侧，无法在那边建工作目录。"
                        + "请先连上，或者直接手动填一个路径。",
                "Nothing is connected on the Linux side, so a working directory "
                        + "cannot be made there. Connect first, or type a path directly.");
        put(ZH_TEXT, EN_TEXT, FORM_WORKDIR_FAILED,
                "无法在 Linux 侧创建工作目录。可以手动填一个已有路径，或稍后重试。",
                "Could not create a working directory on the Linux side. Type an "
                        + "existing path by hand, or try again later.");
        put(ZH_TEXT, EN_TEXT, FORM_WORKDIR_SET, "工作目录：{0}",
                "Working directory: {0}");
        put(ZH_TEXT, EN_TEXT, FORM_DESC_ARG, "参数：", "argument:");
        put(ZH_TEXT, EN_TEXT, FORM_DESC_DEFAULT, "默认值：", "default:");
        put(ZH_TEXT, EN_TEXT, FORM_DESC_RANGE, "取值范围：", "range:");
        put(ZH_TEXT, EN_TEXT, FORM_DESC_ERR, "错误：", "error:");
        put(ZH_TEXT, EN_TEXT, FORM_REMOVED_FLAG, "已删除 {0}。", "Removed {0}.");
        put(ZH_TEXT, EN_TEXT, FORM_STATUS_LOADING, "加载中", "loading");
        put(ZH_TEXT, EN_TEXT, FORM_STATUS_NO_OPTIONS, "帮助里没有描述任何参数",
                "the help describes no options");
        put(ZH_TEXT, EN_TEXT, FORM_STATUS_UNREADABLE, "读不出参数",
                "no option could be read");
        put(ZH_TEXT, EN_TEXT, FORM_STATUS_OPTIONS, "{0} 个参数", "{0} option(s)");
        put(ZH_TEXT, EN_TEXT, FORM_STATUS_SUBCOMMAND, "（子命令：{0}）",
                "  (subcommand: {0})");
        put(ZH_TEXT, EN_TEXT, FORM_STATUS_AT, " @ ", " @ ");
        put(ZH_TEXT, EN_TEXT, FORM_STATUS_SEP, "   \u2014   ", "   \u2014   ");
        put(ZH_TEXT, EN_TEXT, FORM_UNKNOWN, "未知", "unknown");
        put(ZH_TEXT, EN_TEXT, FORM_UNSTRUCTURED,
                "这个命令的帮助里没有列出任何参数，因此无法据此生成表单。"
                        + "它的参数在下方输入。有些程序不用命令行选项，而是读控制文件，"
                        + "并且完全不输出帮助。",
                "This command's help lists no options, so there is nothing to build a "
                        + "form from. Its parameters are entered below. Some programs "
                        + "take a control file rather than command-line options, and "
                        + "print no help at all.");
        put(ZH_TEXT, EN_TEXT, FORM_STRUCTURED_UNREADABLE,
                "这个命令的帮助有可识别的结构（{0}），但从中读不出任何选项。"
                        + "请在下方输入它的参数；「这是怎么解析出来的？」会显示读取到的原文。",
                "This command's help has a recognisable structure ({0}) but no option "
                        + "could be read from it. Enter its arguments below; "
                        + "\u201cHow was this built?\u201d shows the text that was read.");
        put(ZH_TEXT, EN_TEXT, FORM_DESC_SUBCOMMANDS,
                "这个命令把功能分成若干子命令。请在下面选一个，查看它的参数。",
                "This command groups its functionality into subcommands. "
                        + "Choose one below to see its options.");
        put(ZH_TEXT, EN_TEXT, FORM_DESC_OPTIONS,
                "下面的参数是从命令自己的帮助输出里读出来的。"
                        + "可以用右侧的下拉框调整任意参数的类型。",
                "Options below were read from the command's own help output. "
                        + "Adjust any control type with the dropdown on the right.");
    }

    /**
     * 同时往两份表里放一条，保证中英键集合永远一致。
     *
     * <p>为什么不写两次 {@code put}：那样漏掉一行就会得到一个只有中文的键，
     * 而英文用户在界面上会看到中文——这种漏译只有人工比对才能发现。
     * 收在一个调用里，漏译在结构上就不可能发生。
     */
    private static void put(Map<String, String> zh, Map<String, String> en,
                            String key, String zhText, String enText) {
        zh.put(key, zhText);
        en.put(key, enText);
    }

    /**
     * 取某个语言下的文案。
     *
     * @param lang 语言，null 按 {@link Lang#ZH} 处理
     * @param key  键名
     * @return 文案；键不存在时返回 null（由 {@link I18n#t(String)} 负责回退）
     */
    static String raw(Lang lang, String key) {
        Map<String, String> table = (lang == Lang.EN) ? EN_TEXT : ZH_TEXT;
        return table.get(key);
    }

    /** 已登记的键数量（中英相同），供自检与文档使用。 */
    public static int size() {
        return ZH_TEXT.size();
    }

    /**
     * 中英两份表键集合不一致时返回差异描述，一致时返回 null。
     *
     * <p>存在的意义：这份表是手工维护的，而「中文加了、英文忘了」是
     * 最常见的疏漏。{@link #put} 已经从结构上避免了一次性漏写，
     * 但将来若有人分两次补键，这个方法就是最后一道网。自检类会调用它，
     * 因此不一致会在测试阶段就暴露，而不是等英文用户来报告。
     */
    public static String missingKeys() {
        StringBuilder sb = new StringBuilder();
        for (String k : ZH_TEXT.keySet()) {
            if (!EN_TEXT.containsKey(k)) {
                append(sb, "EN 缺少键: " + k);
            }
        }
        for (String k : EN_TEXT.keySet()) {
            if (!ZH_TEXT.containsKey(k)) {
                append(sb, "ZH 缺少键: " + k);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void append(StringBuilder sb, String line) {
        if (sb.length() > 0) {
            sb.append("; ");
        }
        sb.append(line);
    }
}
