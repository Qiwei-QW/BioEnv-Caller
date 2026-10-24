package bioenv.core;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置写盘失败的去处。
 *
 * <h2>为什么需要这个类</h2>
 *
 * <p>所有 store 的 {@code save()} 原来都用 {@code catch (Exception ignored)} 收尾，
 * 理由写得很合理（"存不下不该让界面报错"），但结果是<b>用户永远不知道没存上</b>：
 * 笔记编辑框关掉了、状态栏写着"已重命名 3 个参数"，重启后全没了，没有任何线索。
 *
 * <p>这里做两件事：<b>同一个问题只说一次</b>（每 700 毫秒重试一次的写盘失败了不该刷屏），
 * 以及<b>把话递到界面上</b>——界面这一侧由 {@code MainPanel} 注册 {@link Sink}，
 * 输出面板里会出现一行；注册之前消息只去 {@code stderr}。
 *
 * <p>刻意不抛异常：写盘失败是"降级"，不是"崩溃"。store 的调用点遍布构造、事件处理、
 * 后台线程，抛出去只会在更远的地方变成别的症状。
 */
public final class ConfigWarnings {

    /** 界面侧的接收器。可能在任意线程被调用，实现方自己决定要不要转到 EDT。 */
    public interface Sink {
        void warn(String message);
    }

    private static volatile Sink sink;

    /** 已经说过的消息。并发安全，因为写盘失败往往发生在多个线程上。 */
    private static final Set<String> said = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    private ConfigWarnings() {
    }

    /** 注册界面接收器；传 {@code null} 取消。 */
    public static void setSink(Sink s) {
        sink = s;
    }

    /** 记一次失败；同样的消息一次进程只报一次。 */
    public static void reportOnce(String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        if (!said.add(message)) {
            return;
        }
        Sink s = sink;
        if (s != null) {
            try {
                s.warn(message);
            } catch (Exception ignored) {
                // 界面自己坏了也不能再抛出去。
            }
        }
        System.err.println("[BioEnv Caller] " + message);
    }

    /** 写盘失败的统一措辞：说清楚是哪个文件、为什么，用户才可能自己修。 */
    public static void writeFailed(java.io.File file, Throwable why) {
        String path = file == null ? "(unknown path)" : file.getAbsolutePath();
        String reason = why == null ? "" : String.valueOf(why.getMessage());
        // 不说"改动不会保留"：三个合并式 store 失败后会把待写内容留着、下次重试，
        // 那句话对它们不成立；能确定的事实只有"这一次没写进去"。
        reportOnce("无法写入配置 " + path + (reason.isEmpty() ? "" : "：" + reason)
                + "（这次没有写进去，稍后会再试）");
    }

    /** 文件存在但读不出来时的统一措辞：已经备份成 .bak，让用户知道原文件还在。 */
    public static void readFailed(java.io.File file, java.io.File backup) {
        String path = file == null ? "(unknown path)" : file.getAbsolutePath();
        String bak = backup == null ? "" : "，原文件已备份为 " + backup.getName();
        reportOnce("配置读不出来，已按空配置继续：" + path + bak);
    }

    /** 测试用：清空"已经说过"的记录，免得前一个用例把后一个的提示吞掉。 */
    public static void resetForTest() {
        said.clear();
        sink = null;
    }
}
