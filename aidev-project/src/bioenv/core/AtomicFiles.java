package bioenv.core;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 配置文件的安全落盘：先写 {@code <名字>.tmp}，再整体替换，失败时清掉临时文件。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>原来的每个 store 都用 {@code new FileOutputStream(file)} 直接写目标文件——
 * 那个构造器在<b>打开时</b>就把旧文件截断了。于是磁盘满、杀软锁住、进程被杀
 * 这几种情况里，用户唯一的笔记/参数修改只剩半截；更糟的是它被
 * {@code catch (Exception ignored)} 收尾，界面还会说"已保存"。
 *
 * <p>这里做两件事：<b>旧文件在新内容完整落盘之前不被碰</b>（写临时文件再
 * {@link StandardCopyOption#ATOMIC_MOVE} 替换），以及<b>失败必须让人知道</b>
 * （交给 {@link ConfigWarnings} 报一次，不再是静默）。所有方法都不抛异常：
 * 写盘失败是降级，不是崩溃。
 *
 * <p>另外提供 {@link #backup(File)} 与 {@link #read(File, Charset)}：前者给
 * "配置坏了"的现场留一份 {@code .bak}，后者让调用方能把"文件不存在"和
 * "文件在但读不出来"分开处理——这两件事的后果完全不同。
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /**
     * 原子地写入文本：父目录不存在就建，先写 {@code <名字>.tmp}，再整体替换。
     *
     * <p>失败时删除临时文件、通过 {@link ConfigWarnings#writeFailed(File, Throwable)}
     * 报一次，返回 false；绝不抛异常，旧文件保持原样。
     *
     * @param file    目标文件；为 null 时什么都不做
     * @param text    完整内容；为 null 时什么都不做
     * @param charset 编码；为 null 时按 UTF-8
     * @return true 表示新内容已经完整地替换了目标文件
     */
    public static boolean write(File file, String text, Charset charset) {
        if (file == null || text == null) {
            return false;
        }
        Charset cs = charset == null ? StandardCharsets.UTF_8 : charset;
        File tmp = sibling(file, ".tmp");
        try {
            File dir = file.getParentFile();
            if (dir != null) {
                Files.createDirectories(dir.toPath());
            }
            Files.write(tmp.toPath(), text.getBytes(cs));
            try {
                Files.move(tmp.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicUnsupported) {
                // 文件系统不支持原子替换（网络盘、部分 FAT/exFAT）时退化成普通替换：
                // 仍然比直接截断目标文件安全，只是断电那几毫秒不再是全有或全无。
                Files.move(tmp.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            deleteQuietly(tmp);
            ConfigWarnings.writeFailed(file, e);
            return false;
        }
    }

    /**
     * 把现有文件复制一份成 {@code <名字>.bak}，给"配置坏了"的现场留证。
     *
     * <p>刻意只做复制，不删除也不改写原文件：备份本身绝不能再制造一次数据丢失。
     *
     * @return 备份文件；文件不存在或复制失败时返回 null
     */
    public static File backup(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            File bak = sibling(file, ".bak");
            Files.copy(file.toPath(), bak.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            return bak;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读整个文件为文本。
     *
     * <p>返回 null 有两种含义，调用方需要用 {@code file.isFile()} 区分：
     * 文件不存在，或者文件在但读不出来（权限、锁定、I/O 错误）。
     * 后者必须和"配置为空"分开处理，否则一次读失败会把用户的数据写成空。
     *
     * @return 文件内容；不存在或读不出来时返回 null（空文件返回 ""）
     */
    public static String read(File file, Charset charset) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            return new String(bytes, charset == null ? StandardCharsets.UTF_8 : charset);
        } catch (Exception e) {
            return null;
        }
    }

    /** 与目标同目录、同名字加后缀的兄弟文件（{@code .tmp} / {@code .bak}）。 */
    private static File sibling(File file, String suffix) {
        return new File(file.getParentFile(), file.getName() + suffix);
    }

    /** 删除临时文件；删不掉也只能算了，绝不能因此再抛一次。 */
    private static void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            Path p = file.toPath();
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
            // 临时文件删不掉不影响正确性，下次写入会把它截断重写。
        }
    }
}
