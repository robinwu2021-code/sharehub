package ai.neargo.opskit.svc.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 引导配置文件的启动前检查（21/07 O4-4）。{@code spring.config.import} 对「路径是个目录」不报错、静默跳过 ——
 * 容器挂载时宿主机上文件不存在（或 colima 这类只共享 $HOME 的运行时挂不进去），挂进来的就是空目录，
 * 服务照样 healthy，但账户 / IdP / 角色一个都没有，谁也登录不了。这里把它变成启动失败。
 *
 * <ul>
 *   <li>路径是目录 → 一律拒绝启动；</li>
 *   <li>文件不存在 → required 时拒绝启动，否则记 WARN（多租户 SaaS 形态本就不用引导）；</li>
 *   <li>文件在但 opskit.bootstrap.enabled 不是 true → required 时拒绝启动（文件写了却没生效）。</li>
 * </ul>
 */
public final class BootstrapFileGuard {

    private static final Logger log = LoggerFactory.getLogger(BootstrapFileGuard.class);

    private BootstrapFileGuard() {
    }

    public static void check(Path file, boolean required, boolean enabled) {
        if (Files.isDirectory(file)) {
            throw new IllegalStateException("引导配置 " + file + " 是个目录而不是文件 —— 多半是容器挂载时宿主机上的文件不存在；"
                    + "检查挂载源路径（colima / Docker Desktop 只共享部分目录）");
        }
        if (!Files.isRegularFile(file)) {
            if (required) {
                throw new IllegalStateException("引导配置 " + file + " 不存在，而 OPSKIT_BOOTSTRAP_REQUIRED=true");
            }
            log.warn("未找到引导配置 {}，本次不引导（单宿主部署须提供该文件，否则无账户 / IdP 可登录）", file);
            return;
        }
        if (required && !enabled) {
            throw new IllegalStateException("引导配置 " + file + " 已加载但 opskit.bootstrap.enabled 不是 true");
        }
    }
}
