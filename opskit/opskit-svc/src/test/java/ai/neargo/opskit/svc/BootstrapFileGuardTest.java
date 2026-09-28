package ai.neargo.opskit.svc;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.svc.bootstrap.BootstrapFileGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 21/07 O4-4：容器挂载把引导文件变成空目录时，服务不能「健康却谁也登录不了」。 */
class BootstrapFileGuardTest {

    @Test
    void aDirectoryInPlaceOfTheFileAlwaysStopsTheStartup(@TempDir Path dir) {
        assertThatThrownBy(() -> BootstrapFileGuard.check(dir, false, false)).hasMessageContaining("是个目录");
    }

    @Test
    void aMissingFileOnlyStopsTheStartupWhenRequired(@TempDir Path dir) {
        Path missing = dir.resolve("bootstrap.yml");
        assertThatCode(() -> BootstrapFileGuard.check(missing, false, false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> BootstrapFileGuard.check(missing, true, false)).hasMessageContaining("不存在");
    }

    @Test
    void aRequiredFileMustActuallyEnableTheBootstrap(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("bootstrap.yml"), "opskit: {}");
        assertThatThrownBy(() -> BootstrapFileGuard.check(f, true, false)).hasMessageContaining("enabled");
        assertThatCode(() -> BootstrapFileGuard.check(f, true, true)).doesNotThrowAnyException();
    }
}
