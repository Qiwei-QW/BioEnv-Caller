package bioenv.core;

import java.io.File;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 把一个文件的权限收窄到"只有所有者能读写"。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>{@code ai-models.properties} 里存的是明文的 API key（格式取舍见
 * {@link bioenv.ai.AiModelStore} 的说明）。在共用同一台 Windows 机器的场景里，
 * 用户目录下的文件默认继承父目录的 ACL，本机其它账户可能读得到它。这不是
 * 加密问题，是"本来只需自己看得见的东西被别的账户看得见"。
 *
 * <h2>做不到的时候怎么办</h2>
 *
 * <p>权限是增强，不是保存的前提：FAT、网络盘、非 Windows ACL 的系统上可能
 * 根本没法设。那种情况下保持文件原样、返回 false，并且<b>整个进程只提示一次</b>
 * （{@link ConfigWarnings}），绝不让保存失败或抛异常。
 *
 * <p>设置之后会<b>回读验证</b>：有些系统会把继承来的条目重新加回去，那种"看起来
 * 设了其实没生效"不算成功。
 */
public final class FilePerms {

    /** 本进程是否已经提示过。权限设不了不是每次保存都要刷屏的事。 */
    private static boolean reported;

    private FilePerms() {
    }

    /**
     * 尽量把文件限制为只有所有者可读写。
     *
     * <p>POSIX 文件系统写成 {@code rw-------}；Windows 上用 ACL 只留所有者一条、
     * 去掉继承和其它条目。设置后回读一遍，只有确认结果只剩所有者才算成功。
     *
     * @param file 目标文件；不存在或不是普通文件时什么都不做
     * @return true 表示确认文件权限已经只剩所有者；false 表示系统做不到或未生效
     */
    public static boolean restrictToOwner(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        Path path = file.toPath();
        try {
            FileStore store = Files.getFileStore(path);
            if (store.supportsFileAttributeView("posix")) {
                return restrictPosix(path);
            }
            if (store.supportsFileAttributeView("acl")) {
                return restrictAcl(path);
            }
            reportOnce(path, "这个文件系统既不支持 POSIX 权限也不支持 ACL");
            return false;
        } catch (Exception e) {
            reportOnce(path, e.getMessage());
            return false;
        }
    }

    private static boolean restrictPosix(Path path) throws Exception {
        Set<PosixFilePermission> want = PosixFilePermissions.fromString("rw-------");
        Files.setPosixFilePermissions(path, want);
        if (want.equals(Files.getPosixFilePermissions(path))) {
            return true;
        }
        reportOnce(path, "文件系统没有接受 rw-------");
        return false;
    }

    private static boolean restrictAcl(Path path) throws Exception {
        AclFileAttributeView view = Files.getFileAttributeView(
                path, AclFileAttributeView.class);
        if (view == null) {
            reportOnce(path, "无法取得文件的 ACL");
            return false;
        }
        UserPrincipal owner = view.getOwner();
        if (owner == null) {
            reportOnce(path, "无法确定文件的所有者");
            return false;
        }
        AclEntry allowOwner = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        view.setAcl(Collections.singletonList(allowOwner));
        // 回读验证：继承来的条目在某些系统上会被重新合并进来，那就不算成功。
        List<AclEntry> after = view.getAcl();
        if (after.isEmpty()) {
            reportOnce(path, "设置后 ACL 为空");
            return false;
        }
        for (int i = 0; i < after.size(); i++) {
            if (!owner.equals(after.get(i).principal())) {
                reportOnce(path, "继承的权限无法移除");
                return false;
            }
        }
        return true;
    }

    /**
     * 一次进程只说一次。具体是哪个文件、什么原因都写在消息里，用户才可能自己判断。
     *
     * <p>用本类的标记而不是只靠 {@code ConfigWarnings.reportOnce} 的去重：不同文件
     * 的消息不同，靠消息去重会在多个文件上各说一次。
     */
    private static synchronized void reportOnce(Path path, String why) {
        if (reported) {
            return;
        }
        reported = true;
        ConfigWarnings.reportOnce("无法把 " + path + " 的权限限制为仅本人可读写"
                + (why == null || why.trim().isEmpty() ? "" : "：" + why)
                + "（文件保持原权限，保存不受影响）");
    }

    /** 仅供自检：清掉"已经报过"的标记。生产代码不应调用。 */
    public static void resetForTest() {
        reported = false;
    }
}
