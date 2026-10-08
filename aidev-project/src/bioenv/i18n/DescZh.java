package bioenv.i18n;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 把第三方工具 {@code --help} 里的<b>英文参数说明</b>译成中文。
 *
 * <h2>为什么这是一个独立的东西，而不是 Strings 里多加几个键</h2>
 *
 * <p>{@link Strings} 装的是<b>插件自己的文案</b>：数量有限、由我们撰写、
 * 每一条都能事先确定。而这里的输入是被调用工具自己 help 里的原文
 * （{@code seqkit}、{@code RAxML}、{@code trimAl}……），它们：
 * <ul>
 *   <li><b>是无限开放集</b>——用户装任何一个新工具就会带来一批新描述，
 *       不可能事先穷举；</li>
 *   <li><b>不在我们的控制之下</b>——工具升级、换个版本，措辞就变了。</li>
 * </ul>
 * 所以这里需要的不是「一份完整的对照表」，而是<b>一个能优雅降级的映射层</b>：
 * 收录了的高频短语给中文，没收录的原样显示英文。
 *
 * <h2>核心契约</h2>
 * <ol>
 *   <li><b>EN 模式逐字节返回原文。</b>英文用户看到的必须和加这层之前
 *       一模一样——不能因为词典恰好命中就换成别的写法（哪怕「更规范的英文」
 *       也不行）。这是回归底线，见 {@link #translate(String)}。</li>
 *   <li><b>永不返回 null、永不抛异常、永不返回空串</b>（除非入参就是空）。
 *       这是显示层调用的东西，任何异常都会让参数面板画不出来；
 *       返回空串则会让一个参数看起来没有说明，用户会以为是工具的问题。</li>
 *   <li><b>只做整条描述的精确匹配</b>（经规范化），不做模糊替换。
 *       理由见下面的「为什么不做分词替换」。</li>
 * </ol>
 *
 * <h2>为什么不做分词/模糊替换</h2>
 *
 * <p>看起来「把句子里认识的部分换成中文、其余保留英文」能得到更高的覆盖率，
 * 但实际效果是灾难性的：
 * <pre>
 *   "gap penalty for loop regions"
 *   → 部分替换后可能是「gap 罚分 for loop 区域」
 * </pre>
 * 这种中英夹杂的句子比纯英文更难读，而且会让用户以为插件翻译错了、
 * 从而不信任整个中文界面。<b>整条命中才翻译，否则整条保留原文</b>——
 * 界面要么是自然的中文，要么是原汁原味的英文，不会出现缝合体。
 *
 * <p>同理，词典刻意<b>不收录</b>以下内容，即使它们出现频率很高：
 * 工具专有名词（{@code RAxML}、{@code BAM}、{@code FASTQ}）、
 * 命令行片段（{@code [--auto-prot=ml|bic]}）、
 * 以及含义依赖具体工具的长句（一句话里塞了七八个限定条件的 RAxML 说明）。
 * 硬译这些比不译更糟：译错了会让用户按错误的参数跑实验。
 *
 * <h2>词典的收录标准</h2>
 *
 * <p>只收「在多个工具之间通用、且语义明确」的短语，例如
 * {@code input file}、{@code number of threads}、{@code print version}。
 * 判断标准是：这句话换个工具还成立吗？成立才收。
 *
 * <h2>规范化</h2>
 *
 * <p>比对前对两侧都做：去首尾空白、把连续空白压成一个空格、
 * 统一大小写。这样做是因为 help 文本的排版差异极大——同一句
 * {@code Input  file} 和 {@code input file} 在用户看来完全一样，
 * 却会被字节比对当成两条。规范化让它们命中同一条词典项。
 *
 * <p><b>但返回给调用方的永远是原文（未规范化）</b>，规范化只用于查表。
 */
public final class DescZh {

    private DescZh() {
        // 纯静态工具类。
    }

    /**
     * 英文描述（规范化为小写）→ 中文译文。
     *
     * <p>用 {@link HashMap} 而不是 TreeMap：只做精确查表，不需要有序，
     * 哈希查找是 O(1)；这份表在界面重绘时可能被频繁查询。
     *
     * <p>静态初始化后不再修改，因此天然线程安全（不可变表的并发读无需同步，
     * 见 JLS 12.4.2 的类初始化保证）。
     */
    private static final Map<String, String> DICT;

    static {
        Map<String, String> m = new HashMap<String, String>();

        // ---------------------------------------------------------------
        // 1. 输入 / 输出文件（最高频、最通用）
        // ---------------------------------------------------------------
        put(m, "input file", "输入文件");
        put(m, "input files", "输入文件");
        put(m, "input file(s)", "输入文件");
        put(m, "input files(s)", "输入文件");
        put(m, "input file name", "输入文件名");
        put(m, "input file name(s)", "输入文件名");
        put(m, "input filename", "输入文件名");
        put(m, "input filename(s)", "输入文件名");
        put(m, "input sequence file", "输入序列文件");
        put(m, "input sequence file(s)", "输入序列文件");
        put(m, "input sequence", "输入序列");
        put(m, "input sequences", "输入序列");
        put(m, "input sequences.", "输入序列。");
        put(m, "input sequences file", "输入序列文件");
        put(m, "input directory", "输入目录");
        put(m, "input dir", "输入目录");
        put(m, "input path", "输入路径");
        put(m, "input data", "输入数据");
        put(m, "input alignment", "输入比对");
        put(m, "input alignment file", "输入比对文件");
        put(m, "input alignment file(s)", "输入比对文件");
        put(m, "input msa file", "输入多序列比对文件");
        put(m, "input tree file", "输入树文件");
        put(m, "input tree", "输入树");
        put(m, "input list file", "输入列表文件");
        put(m, "input list", "输入列表");
        put(m, "input counts file (see manual)", "输入计数文件（见手册）");
        put(m, "input site frequency model file", "输入位点频率模型文件");
        put(m, "input fasta file", "输入 FASTA 文件");
        put(m, "input fasta file(s)", "输入 FASTA 文件");
        put(m, "input fastq file", "输入 FASTQ 文件");
        put(m, "input fastq file(s)", "输入 FASTQ 文件");
        put(m, "input bam file", "输入 BAM 文件");
        put(m, "input sam file", "输入 SAM 文件");
        put(m, "input gff file", "输入 GFF 文件");
        put(m, "input vcf file", "输入 VCF 文件");
        put(m, "input file in fasta format", "FASTA 格式的输入文件");
        put(m, "the input file", "输入文件");
        put(m, "the input file(s)", "输入文件");
        put(m, "the input file name", "输入文件名");
        put(m, "the input file(s) name", "输入文件名");
        put(m, "the input alignment", "输入比对");
        put(m, "the input alignment file", "输入比对文件");
        put(m, "the input sequence", "输入序列");
        put(m, "the input sequences", "输入序列");
        put(m, "the input tree", "输入树");
        put(m, "the input directory", "输入目录");
        put(m, "the input data", "输入数据");
        put(m, "file containing a sequence alignment.", "包含序列比对的文件。");
        put(m, "sequence alignment file name", "序列比对文件名");
        put(m, "sequence alignment file", "序列比对文件");
        put(m, "sequence file", "序列文件");
        put(m, "sequence file name", "序列文件名");
        put(m, "sequence filename", "序列文件名");
        put(m, "sequence input file", "序列输入文件");
        put(m, "specify the input file", "指定输入文件");
        put(m, "specify the input sequence alignment", "指定输入序列比对");
        put(m, "specify the input file name", "指定输入文件名");

        put(m, "output file", "输出文件");
        put(m, "output files", "输出文件");
        put(m, "output file(s)", "输出文件");
        put(m, "output file name", "输出文件名");
        put(m, "output file name [stdout]", "输出文件名 [标准输出]");
        put(m, "output filename", "输出文件名");
        put(m, "output directory", "输出目录");
        put(m, "output dir", "输出目录");
        put(m, "output path", "输出路径");
        put(m, "output prefix", "输出前缀");
        put(m, "output format", "输出格式");
        put(m, "output data", "输出数据");
        put(m, "output tree", "输出树");
        put(m, "output tree file", "输出树文件");
        put(m, "output alignment", "输出比对");
        put(m, "output alignment file", "输出比对文件");
        put(m, "the output file", "输出文件");
        put(m, "the output file name", "输出文件名");
        put(m, "the output directory", "输出目录");
        put(m, "the output alignment", "输出比对");
        put(m, "the output alignment file", "输出比对文件");
        put(m, "the output tree", "输出树");
        put(m, "the output data", "输出数据");
        put(m, "specify the output file", "指定输出文件");
        put(m, "specify the output file name", "指定输出文件名");
        put(m, "specify the name of the output file.", "指定输出文件名。");
        put(m, "specify the name of the output file", "指定输出文件名");
        put(m, "output file in clustal format", "CLUSTAL 格式输出文件");
        put(m, "output file in fasta format", "FASTA 格式输出文件");
        put(m, "output file in mega format", "MEGA 格式输出文件");
        put(m, "output file in nbrf/pir format", "NBRF/PIR 格式输出文件");
        put(m, "output file in nexus format", "NEXUS 格式输出文件");
        put(m, "output file in phylip/phylip4 format", "PHYLIP/PHYLIP4 格式输出文件");
        put(m, "output file in phylip3.2 format", "PHYLIP3.2 格式输出文件");
        put(m, "output to standard out", "输出到标准输出");
        put(m, "write to standard output", "写入标准输出");
        put(m, "write to stdout", "写入标准输出");
        put(m, "write to new file", "写入新文件");
        put(m, "write to old file (default)", "写入旧文件（默认）");
        put(m, "write to old file", "写入旧文件");
        put(m, "force output file overwrite", "强制覆盖输出文件");
        put(m, "force overwrite", "强制覆盖");
        put(m, "overwrite existing output files", "覆盖已存在的输出文件");
        put(m, "overwrite files without asking", "覆盖文件且不询问");
        put(m, "overwrite existing signature file.", "覆盖已存在的签名文件。");
        put(m, "overwrite", "覆盖");
        put(m, "force", "强制");

        // ---------------------------------------------------------------
        // 2. 线程 / 并行（最常被用户调整的一组）
        // ---------------------------------------------------------------
        put(m, "number of threads", "线程数");
        put(m, "number of thread", "线程数");
        put(m, "number of threads to use", "使用的线程数");
        put(m, "number of threads.", "线程数。");
        put(m, "number of cpu threads", "CPU 线程数");
        put(m, "number of cpus", "CPU 数");
        put(m, "number of cpu", "CPU 数");
        put(m, "number of cores", "核心数");
        put(m, "number of processors", "处理器数");
        put(m, "number of processes", "进程数");
        put(m, "threads", "线程数");
        put(m, "threads to use", "使用的线程数");
        put(m, "thread number", "线程数");
        put(m, "thread count", "线程数");
        put(m, "number of threads (default: 1)", "线程数（默认：1）");
        put(m, "maximum number of threads", "最大线程数");
        put(m, "max number of threads", "最大线程数");
        put(m, "max threads", "最大线程数");
        put(m, "cpus", "CPU 数");
        put(m, "cores", "核心数");
        put(m, "processes", "进程数");
        put(m, "run 2to3 concurrently", "并发运行 2to3");

        // ---------------------------------------------------------------
        // 3. 帮助 / 版本
        // ---------------------------------------------------------------
        put(m, "print help", "打印帮助");
        put(m, "show help", "显示帮助");
        put(m, "display help", "显示帮助");
        put(m, "display help message", "显示帮助信息");
        put(m, "display this help message", "显示本帮助信息");
        put(m, "display this help message.", "显示本帮助信息。");
        put(m, "display this help text", "显示本帮助文本");
        put(m, "display this help and exit", "显示本帮助并退出");
        put(m, "show this help message and exit", "显示本帮助信息并退出");
        put(m, "show this help message", "显示本帮助信息");
        put(m, "print this message", "打印本信息");
        put(m, "print this help page", "打印本帮助页");
        put(m, "print this help", "打印本帮助");
        put(m, "print help and exit", "打印帮助并退出");
        put(m, "print usage and exit", "打印用法并退出");
        put(m, "print usage", "打印用法");
        put(m, "show usage", "显示用法");
        put(m, "usage", "用法");
        put(m, "produce help message", "生成帮助信息");
        put(m, "print (more) help usages", "打印（更多）帮助用法");
        put(m, "print more help usages", "打印更多帮助用法");
        put(m, "get help for commands", "获取命令的帮助");
        put(m, "give this help", "显示本帮助");
        put(m, "help", "帮助");
        put(m, "prints this help.", "打印本帮助。");
        put(m, "prints this help", "打印本帮助");
        put(m, "output full help content.", "输出完整帮助内容。");
        put(m, "output full help content", "输出完整帮助内容");

        put(m, "print version", "打印版本");
        put(m, "show version", "显示版本");
        put(m, "display version", "显示版本");
        put(m, "display version information", "显示版本信息");
        put(m, "display version number", "显示版本号");
        put(m, "display version and exit", "显示版本并退出");
        put(m, "print version information and exit", "打印版本信息并退出");
        put(m, "print version and exit", "打印版本并退出");
        put(m, "output version information", "输出版本信息");
        put(m, "show version number", "显示版本号");
        put(m, "version", "版本");
        put(m, "display software license", "显示软件许可");
        put(m, "print license", "打印许可");
        put(m, "show license", "显示许可");

        // ---------------------------------------------------------------
        // 4. 日志 / 详细程度 / 静默
        // ---------------------------------------------------------------
        put(m, "verbose", "详细输出");
        put(m, "verbose mode", "详细模式");
        put(m, "verbose operation", "详细输出模式");
        put(m, "verbose output", "详细输出");
        put(m, "be verbose", "输出详细信息");
        put(m, "more verbose logging", "更详细的日志");
        put(m, "enable verbose output", "启用详细输出");
        put(m, "quiet", "静默");
        put(m, "quiet mode", "静默模式");
        put(m, "quiet mode, suppress all warnings", "静默模式，抑制全部警告");
        put(m, "suppress noncritical error messages", "抑制非致命错误信息");
        put(m, "suppress warnings", "抑制警告");
        put(m, "suppress all warnings", "抑制全部警告");
        put(m, "silent", "静默");
        put(m, "silent mode", "静默模式");
        put(m, "reduce console output to minimum", "将控制台输出降到最少");
        put(m, "do not report progress", "不报告进度");
        put(m, "don't print startup message", "不打印启动信息");
        put(m, "treat all warnings as fatal", "把所有警告视为致命错误");
        put(m, "print information on progress", "打印进度信息");
        put(m, "display timing information", "显示耗时信息");
        put(m, "set verbosity level", "设置详细程度级别");
        put(m, "emit debug output", "输出调试信息");
        put(m, "debug", "调试");
        put(m, "debug output", "调试输出");
        put(m, "debugging output", "调试输出");
        put(m, "log file", "日志文件");
        put(m, "log level", "日志级别");
        put(m, "no output", "无输出");
        put(m, "no progress bar", "不显示进度条");
        put(m, "suppress progress indicator", "抑制进度指示");
        put(m, "do not show progress bar", "不显示进度条");

        // ---------------------------------------------------------------
        // 5. 数值 / 阈值 / 长度
        // ---------------------------------------------------------------
        put(m, "number of", "数量");
        put(m, "count", "数量");
        put(m, "counts", "计数");
        put(m, "maximum number", "最大数量");
        put(m, "minimum number", "最小数量");
        put(m, "maximum length", "最大长度");
        put(m, "minimum length", "最小长度");
        put(m, "max length", "最大长度");
        put(m, "min length", "最小长度");
        put(m, "maximum allowed input sequence length", "允许的输入序列最大长度");
        put(m, "maximum size", "最大大小");
        put(m, "minimum size", "最小大小");
        put(m, "max size", "最大大小");
        put(m, "min size", "最小大小");
        put(m, "maximum", "最大值");
        put(m, "minimum", "最小值");
        put(m, "default value", "默认值");
        put(m, "default:", "默认值：");
        put(m, "by default", "默认情况下");
        put(m, "default", "默认");
        put(m, "threshold", "阈值");
        put(m, "cutoff", "阈值");
        put(m, "cut-off", "阈值");
        put(m, "score cutoff", "得分阈值");
        put(m, "e-value cutoff", "E 值阈值");
        put(m, "p-value cutoff", "P 值阈值");
        put(m, "p-value cutoff (default: 0.05)", "P 值阈值（默认：0.05）");
        put(m, "minimum correlation coefficient (default: 0.99)",
                "最小相关系数（默认：0.99）");
        put(m, "seed", "随机种子");
        put(m, "random seed", "随机种子");
        put(m, "random number seed", "随机数种子");
        put(m, "seed number", "种子数");
        put(m, "seed number for bootstraps.", "自举的种子数。");
        put(m, "window size", "窗口大小");
        put(m, "word size", "词长");
        put(m, "k-mer size", "k-mer 长度");
        put(m, "kmer size", "k-mer 长度");
        put(m, "k-mer length", "k-mer 长度");
        put(m, "kmer length", "k-mer 长度");
        put(m, "step size", "步长");
        put(m, "window", "窗口");
        put(m, "length", "长度");
        put(m, "size", "大小");
        put(m, "number", "数量");
        put(m, "frequency", "频率");
        put(m, "ratio", "比例");
        put(m, "percentage", "百分比");
        put(m, "fraction", "比例");
        put(m, "integer", "整数");
        put(m, "float", "浮点数");
        put(m, "string", "字符串");

        // ---------------------------------------------------------------
        // 6. 比对 / 序列处理（生物信息通用）
        // ---------------------------------------------------------------
        put(m, "gap penalty", "空位罚分");
        put(m, "gap opening penalty", "空位开启罚分");
        put(m, "gap extension penalty", "空位延伸罚分");
        put(m, "gap penalty for loop regions", "环区的空位罚分");
        put(m, "gap penalty for structure termini", "结构末端的空位罚分");
        put(m, "gap separation pen. range", "空位间隔罚分范围");
        put(m, "gap separation penalty range", "空位间隔罚分范围");
        put(m, "match score", "匹配得分");
        put(m, "mismatch penalty", "错配罚分");
        put(m, "substitution matrix", "替换矩阵");
        put(m, "substitution model", "替换模型");
        put(m, "score matrix", "得分矩阵");
        put(m, "alignment file", "比对文件");
        put(m, "alignment file(s)", "比对文件");
        put(m, "alignment files", "比对文件");
        put(m, "directory of alignment files", "比对文件所在目录");
        put(m, "multiple alignment", "多序列比对");
        put(m, "multiple sequence alignment", "多序列比对");
        put(m, "do full multiple alignment.", "进行完整的多序列比对。");
        put(m, "do full multiple alignment", "进行完整的多序列比对");
        put(m, "sequence alignment", "序列比对");
        put(m, "alignment", "比对");
        put(m, "aligned", "已比对");
        put(m, "ungapped", "无空位");
        put(m, "ignore positions with gaps.", "忽略含空位的位置。");
        put(m, "ignore positions with gaps", "忽略含空位的位置");
        put(m, "disable sequence weighting", "禁用序列加权");
        put(m, "transitions weighting", "转换加权");
        put(m, "no end gap separation pen.", "不计末端空位间隔罚分。");
        put(m, "hydrophilic gaps off", "关闭亲水空位");
        put(m, "list hydrophilic res.", "列出亲水残基。");
        put(m, "residue-specific gaps off", "关闭残基特异空位");
        put(m, "hydrophilic residues", "亲水残基");

        put(m, "convert fastq to fasta", "FASTQ 转 FASTA");
        put(m, "convert to fasta", "转换为 FASTA");
        put(m, "convert to gff", "转换为 GFF");
        put(m, "convert to table", "转换为表格");
        put(m, "convert fasta to fastq", "FASTA 转 FASTQ");
        put(m, "reformat aligned fasta", "重排比对后的 FASTA");
        put(m, "convert file formats", "转换文件格式");
        put(m, "sequence format", "序列格式");
        put(m, "input format", "输入格式");
        put(m, "output format.", "输出格式。");
        put(m, "format", "格式");
        put(m, "sequences", "序列");
        put(m, "duplicate sequences n times", "将序列重复 N 次");
        put(m, "align sequences", "比对序列");
        put(m, "cluster protein sequences", "聚类蛋白序列");
        put(m, "find common elements", "查找共有元素");
        put(m, "find unique elements", "查找唯一元素");
        put(m, "extract subsequences", "提取子序列");
        put(m, "locate subsequences/motifs, mismatch allowed",
                "定位子序列/模体，允许错配");
        put(m, "remove gaps", "去除空位");
        put(m, "reverse complement", "反向互补");
        put(m, "sequence length", "序列长度");
        put(m, "quality", "质量值");
        put(m, "trim", "修剪");
        put(m, "filter", "过滤");
        put(m, "merge", "合并");
        put(m, "split", "拆分");
        put(m, "sort", "排序");
        put(m, "index", "索引");

        // ---------------------------------------------------------------
        // 7. 行为开关（高频）
        // ---------------------------------------------------------------
        put(m, "recursive", "递归");
        put(m, "recursively", "递归地");
        put(m, "force conversion of binary files", "强制转换二进制文件");
        put(m, "skip binary files (default)", "跳过二进制文件（默认）");
        put(m, "keep output file date", "保留输出文件日期");
        put(m, "keep source file(s) (default)", "保留源文件（默认）");
        put(m, "keep source file(s)", "保留源文件");
        put(m, "remove source file(s)", "删除源文件");
        put(m, "keep byte order mark", "保留字节顺序标记");
        put(m, "keep byte order mark (default)", "保留字节顺序标记（默认）");
        put(m, "remove byte order mark", "移除字节顺序标记");
        put(m, "remove byte order mark (default)", "移除字节顺序标记（默认）");
        put(m, "keep utf-16 encoding", "保留 UTF-16 编码");
        put(m, "add additional newline", "增加额外的换行");
        put(m, "add annotations multiple times", "多次添加注释");
        put(m, "allow file ownership change", "允许更改文件属主");
        put(m, "write back modified files", "回写修改后的文件");
        put(m, "fix up doctests only", "仅修复文档测试");
        put(m, "list available transformations", "列出可用的转换");
        put(m, "conversion mode", "转换模式");
        put(m, "default conversion mode", "默认转换模式");
        put(m, "preserve", "保留");
        put(m, "append", "追加");
        put(m, "no cache", "不使用缓存");
        put(m, "allow overwrite", "允许覆盖");
        put(m, "follow symlinks", "跟随符号链接");
        put(m, "no header", "不含表头");
        put(m, "with header", "含表头");
        put(m, "case insensitive", "不区分大小写");
        put(m, "case sensitive", "区分大小写");
        put(m, "compress", "压缩");
        put(m, "decompress", "解压");
        put(m, "force compression", "强制压缩");
        put(m, "force decompression", "强制解压");
        put(m, "test compressed file integrity", "测试压缩文件完整性");
        put(m, "do not", "不");
        put(m, "enable", "启用");
        put(m, "disable", "禁用");
        put(m, "enable all", "全部启用");
        put(m, "disable all", "全部禁用");
        put(m, "print", "打印");
        put(m, "display", "显示");
        put(m, "write", "写入");
        put(m, "read", "读取");
        put(m, "use", "使用");
        put(m, "set", "设置");
        put(m, "specify", "指定");
        put(m, "select", "选择");
        put(m, "select algorithm:", "选择算法：");
        put(m, "remove", "移除");
        put(m, "create", "创建");
        put(m, "delete", "删除");
        put(m, "list", "列出");
        put(m, "show", "显示");

        // ---------------------------------------------------------------
        // 8. 第二批：从审计的「未收录高频」里筛出的通用短语
        //
        // 只收跨工具通用的。像 [--auto-prot=ml|bic] 或 ...+ASC 这类
        // 命令行语法片段一律不收——它们不是英文句子，译出来只是噪声。
        // ---------------------------------------------------------------
        put(m, "disable pattern compression.", "禁用模式压缩。");
        put(m, "enable outputting internal sequences", "启用内部序列输出");
        put(m, "replicates for bootstrap only", "仅用于自举的重复次数");
        put(m, "replicates for topology test", "用于拓扑检验的重复次数");
        put(m, "transfer bootstrap expectation", "传递自举期望值");
        put(m, "write induced partition subtrees.", "写出诱导的分区子树。");
        put(m, "write presence-absence matrix.", "写出存在-缺失矩阵。");
        put(m, "presence-absence matrix of loci coverage.", "位点覆盖度的存在-缺失矩阵。");
        put(m, "to turn off maximum-likelihood", "关闭最大似然");
        put(m, "to suppress progress indicator", "抑制进度指示");
        put(m, "to suppress reporting information", "抑制报告信息");
        put(m, "to not compute support values", "不计算支持值");
        put(m, "turn off all stopping rules.", "关闭所有停止规则。");
        put(m, "compute tree likelihood without optimisation", "不优化直接计算树的似然值");
        put(m, "initial rearrangement setting for the subsequent application of topological changes phase",
                "拓扑变换阶段后续应用的初始重排设置");
        put(m, "specify a user starting tree file name in newick format",
                "以 Newick 格式指定用户起始树文件名");
        put(m, "specify an integer number (random seed) and turn on bootstrapping",
                "指定一个整数（随机种子）并启用自举");
        put(m, "specify the name of the alignment data file in phylip format",
                "指定 PHYLIP 格式的比对数据文件名");
        put(m, "specifies the name of the output file.", "指定输出文件名。");
        put(m, "report command line options and exit", "报告命令行选项并退出");
        put(m, "keep nas in the tests", "在检验中保留 NA");
        put(m, "exclude outgroup from time tree", "从时间树中排除外群");
        put(m, "collapse near-zero branches into polytomy", "将近零分支折叠成多叉");
        put(m, "fast search to resemble fasttree", "近似 FastTree 的快速搜索");
        put(m, "compute greedy vertex cover", "计算贪心顶点覆盖");
        put(m, "improved version of colinear scan", "ColinearScan 的改进版本");
        put(m, "make database index", "建立数据库索引");
        put(m, "make the specified modules.", "构建指定的模块。");
        put(m, "merge daa files", "合并 DAA 文件");
        put(m, "operate on ncbi taxonomies", "处理 NCBI 分类信息");
        put(m, "connect to mygene interface", "连接 mygene 接口");
        put(m, "explain biological terms", "解释生物学名词");
        put(m, "download genbank/ensembl data", "下载 GenBank/ENSEMBL 数据");
        put(m, "get the complementary alignment.", "取得互补比对。");
        put(m, "perform three tests of symmetry", "执行三项对称性检验");
        put(m, "do --symtest then exist", "先做 --symtest 然后退出");
        put(m, "print summary statistics", "打印汇总统计");
        put(m, "print the trimal version.", "打印 trimAl 版本。");
        put(m, "list the command line parameters", "列出命令行参数");
        put(m, "calculate nj tree.", "计算 NJ 树。");
        put(m, "compute nj tree", "计算 NJ 树");
        put(m, "output tree file", "输出树文件");
        put(m, "convert fastq to fasta", "FASTQ 转 FASTA");
        put(m, "extract event-related genomic alignment", "提取事件相关的基因组比对");
        put(m, "brief listing, removes headers", "简要列表，去除表头");
        put(m, "re(re)index compressed file", "(重新)建立压缩文件索引");
        put(m, "(re)index compressed file", "(重新)建立压缩文件索引");
        put(m, "autogenerate xsubs using c::scan.", "使用 C::Scan 自动生成 XSUB。");
        put(m, "compile with debugging information", "带调试信息编译");
        put(m, "compile without optimization [no_meson]", "不带优化编译 [NO_MESON]");
        put(m, "optimization flags [no_meson]", "优化选项 [NO_MESON]");
        put(m, "force an interactive session", "强制交互式会话");
        put(m, "force baseline quantization tables", "强制使用基准量化表");
        put(m, "force grayscale output", "强制灰度输出");
        put(m, "force rgb output", "强制 RGB 输出");
        put(m, "force rgb565 output", "强制 RGB565 输出");
        put(m, "create monochrome jpeg file", "创建单色 JPEG 文件");
        put(m, "create progressive jpeg file", "创建渐进式 JPEG 文件");
        put(m, "create rgb jpeg file", "创建 RGB JPEG 文件");
        put(m, "execute 'expr' and exit", "执行 'EXPR' 后退出");
        put(m, "don't restore anything", "不恢复任何内容");
        put(m, "don't save it", "不保存");
        put(m, "don't read the site-wide rprofile", "不读取站点级 Rprofile");
        put(m, "do not export symbols.", "不导出符号。");
        put(m, "keep source file(s)", "保留源文件");
        put(m, "remove source file(s)", "删除源文件");
        put(m, "http post data", "HTTP POST 数据");
        put(m, "bundle install path", "bundle 安装路径");
        put(m, "curl install prefix", "curl 安装前缀");
        put(m, "library linking information", "库链接信息");
        put(m, "f77 compiler flags", "F77 编译器选项");
        put(m, "f90 compiler flags", "F90 编译器选项");
        put(m, "preprocessor and compiler flags", "预处理与编译器选项");
        put(m, "compiler", "编译器");
        put(m, "file for new guide tree", "新指导树文件");
        put(m, "file for old guide tree", "旧指导树文件");
        put(m, "replicates for ultrafast bootstrap (>=1000)", "超快自举的重复次数（>=1000）");
        put(m, "replicates for ultrafast jackknife (>=1000)", "超快刀切法的重复次数（>=1000）");
        put(m, "random number seed for --subsample", "--subsample 的随机数种子");
        put(m, "seed number for bootstraps.", "自举的种子数。");
        put(m, "sequence alignment for --scf", "--scf 的序列比对");
        put(m, "window around best diags.", "最佳对角线的窗口范围。");
        put(m, "number of best diags.", "最佳对角线数量。");
        put(m, "% ident. for delay", "延迟的最小一致性百分比");
        put(m, "gap separation pen. range", "空位间隔罚分范围");
        put(m, "no end gap separation pen.", "不计末端空位间隔罚分。");
        put(m, "hydropathy window size", "疏水性窗口大小");
        put(m, "maximum number of hits", "最大命中数");
        put(m, "minimum length of match", "最小匹配长度");
        put(m, "number of mismatches", "错配数");
        put(m, "number of gaps", "空位数");
        put(m, "percent identity", "一致性百分比");
        put(m, "identity cutoff", "一致性阈值");
        put(m, "coverage cutoff", "覆盖度阈值");
        put(m, "number of iterations", "迭代次数");
        put(m, "maximum iterations", "最大迭代次数");
        put(m, "number of bootstraps", "自举次数");
        put(m, "bootstrap replicates", "自举重复次数");
        put(m, "bootstrap", "自举");
        put(m, "bootstrapping", "自举");
        put(m, "outgroup", "外群");
        put(m, "ingroup", "内群");
        put(m, "taxa", "分类单元");
        put(m, "taxon", "分类单元");
        put(m, "tree file", "树文件");
        put(m, "trees", "树");
        put(m, "tree", "树");
        put(m, "rooted", "有根");
        put(m, "unrooted", "无根");
        put(m, "midpoint root", "中点生根");
        put(m, "branch length", "枝长");
        put(m, "branch lengths", "枝长");
        put(m, "consensus tree", "一致树");
        put(m, "majority rule", "多数规则");
        put(m, "newick format", "Newick 格式");
        put(m, "newick", "Newick");
        put(m, "partition file", "分区文件");
        put(m, "partitions", "分区");
        put(m, "partition", "分区");
        put(m, "model", "模型");
        put(m, "substitution", "替换");
        put(m, "rate heterogeneity", "速率异质性");
        put(m, "invariable sites", "不变位点");
        put(m, "gamma", "Gamma");
        put(m, "amino acid", "氨基酸");
        put(m, "nucleotide", "核苷酸");
        put(m, "protein", "蛋白");
        put(m, "dna", "DNA");
        put(m, "rna", "RNA");
        put(m, "morphological", "形态学");
        put(m, "state frequencies", "状态频率");
        put(m, "base frequencies", "碱基频率");
        put(m, "empirical base frequencies", "经验碱基频率");
        put(m, "weight file", "权重文件");
        put(m, "weights", "权重");
        put(m, "weight", "权重");
        put(m, "annotation", "注释");
        put(m, "annotations", "注释");
        put(m, "overlap", "重叠");
        put(m, "header", "表头");
        put(m, "headers", "表头");
        put(m, "standard input", "标准输入");
        put(m, "standard output", "标准输出");
        put(m, "stdin", "标准输入");
        put(m, "stdout", "标准输出");
        put(m, "stderr", "标准错误");
        put(m, "temporary directory", "临时目录");
        put(m, "working directory", "工作目录");
        put(m, "current directory", "当前目录");
        put(m, "home directory", "用户主目录");
        put(m, "absolute path", "绝对路径");
        put(m, "relative path", "相对路径");
        put(m, "path", "路径");
        put(m, "directory", "目录");
        put(m, "folder", "目录");
        put(m, "extension", "扩展名");
        put(m, "separator", "分隔符");
        put(m, "delimiter", "分隔符");
        put(m, "column", "列");
        put(m, "columns", "列");
        put(m, "line", "行");
        put(m, "lines", "行");
        put(m, "row", "行");
        put(m, "rows", "行");
        put(m, "first line", "第一行");
        put(m, "last line", "最后一行");
        put(m, "skip first line", "跳过第一行");
        put(m, "skip header", "跳过表头");
        put(m, "no header line", "不含表头行");
        put(m, "tab separated", "制表符分隔");
        put(m, "comma separated", "逗号分隔");
        put(m, "space separated", "空格分隔");
        put(m, "one per line", "每行一个");
        put(m, "gzip", "gzip");
        put(m, "compressed", "压缩的");
        put(m, "uncompressed", "未压缩的");
        put(m, "binary", "二进制");
        put(m, "text", "文本");
        put(m, "all", "全部");
        put(m, "none", "无");
        put(m, "true", "真");
        put(m, "false", "假");
        put(m, "yes", "是");
        put(m, "no", "否");
        put(m, "both", "两者");
        put(m, "only", "仅");
        put(m, "also", "同时");
        put(m, "instead", "改为");
        put(m, "optional", "可选");
        put(m, "required", "必需");

        DICT = Collections.unmodifiableMap(m);
    }

    /** 静态表构建用的辅助，仅在 static 块内使用。 */
    private static void put(Map<String, String> m, String en, String zh) {
        String k = normalize(en);
        if (k != null && !m.containsKey(k)) {
            m.put(k, zh);
        }
    }

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    /**
     * 把一条英文参数说明翻成当前语言下的文字。
     *
     * <h2>行为（这是本方法最重要的部分）</h2>
     * <ul>
     *   <li><b>英文模式下原样返回入参本身</b>（同一个字符串内容，逐字节相同）。
     *       不做规范化、不做 trim、不做任何「顺手整理」——英文界面必须和
     *       加这层之前完全一致。</li>
     *   <li>中文模式下：词典命中则返回中文；未命中则<b>返回原文</b>
     *       （同样是入参本身，未规范化）。</li>
     *   <li>{@code null} 返回空串。显示层直接 {@code setText} 也不会出问题。</li>
     *   <li>任何内部异常都被吞掉并回退到原文。这是一条显示路径，
     *       翻译失败绝不能让参数面板画不出来。</li>
     * </ul>
     *
     * @param english 工具 help 里的原始描述，可为 null
     * @return 中文模式下的译文或原文；英文模式下的原文；永不为 null
     */
    public static String translate(String english) {
        try {
            if (english == null) {
                return "";
            }
            // 英文模式：什么都不做。这个分支必须在最前面，且不能有任何
            // 副作用——它是「英文界面零变化」这条回归底线的实现处。
            if (I18n.get() != Lang.EN) {
                String key = normalize(english);
                if (key != null) {
                    String zh = DICT.get(key);
                    if (zh != null) {
                        return zh;
                    }
                }
            }
            return english;
        } catch (Throwable t) {
            // 显示路径：宁可显示英文，也不能让异常冒到 Swing 的绘制过程里。
            // 这里连 Error 一起接住是有意的——翻译失败的任何形式都不该
            // 让整个参数面板白掉。
            return english == null ? "" : english;
        }
    }

    /**
     * 指定语言下翻译，与当前语言无关。
     *
     * <p>给「用某语言显示」的场景用，也为自检提供与全局状态无关的入口。
     *
     * @param lang    目标语言；null 按 {@link Lang#ZH} 处理
     * @param english 原始描述
     * @return 译文或原文，永不为 null
     */
    public static String translateIn(Lang lang, String english) {
        try {
            if (english == null) {
                return "";
            }
            if (lang == Lang.EN) {
                return english;
            }
            String key = normalize(english);
            if (key != null) {
                String zh = DICT.get(key);
                if (zh != null) {
                    return zh;
                }
            }
            return english;
        } catch (Throwable t) {
            return english == null ? "" : english;
        }
    }

    /**
     * 规范化：压缩连续空白、去首尾、转小写。
     *
     * <p>只用于<b>查表</b>，绝不用于返回值——见 {@link #translate(String)}。
     * 把空白折叠成单个空格而不是直接删掉，是因为
     * {@code input  file} 和 {@code inputfile} 是两回事，
     * 前者是排版差异、后者是另一个词。
     *
     * @param s 原始字符串，可为 null
     * @return 规范化后的查表键；{@code s} 为 null 或全空白时返回 null
     */
    static String normalize(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean lastWasSpace = true; // 前导空白直接丢弃
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // 把所有空白类字符（含 \t \n \r）统一成单个空格。
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
                if (!lastWasSpace) {
                    sb.append(' ');
                    lastWasSpace = true;
                }
            } else {
                sb.append(Character.toLowerCase(c));
                lastWasSpace = false;
            }
        }
        // 去掉尾部可能多出的那个空格。
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == ' ') {
            sb.setLength(len - 1);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 词典条目数，供自检与文档使用。
     */
    public static int size() {
        return DICT.size();
    }

    /**
     * 该描述在当前语言下是否会被翻译。
     *
     * <p>给审计和自检用：不需要比较字符串就能知道命中与否。
     */
    public static boolean hasTranslation(String english) {
        String key = normalize(english);
        return key != null && DICT.containsKey(key);
    }
}
