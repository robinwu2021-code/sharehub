package ai.neargo.sharehub.platform.file.job;

import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobHandler;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobResult;
import ai.neargo.sharehub.platform.file.service.FileService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 清理：上传后 24h 未绑定的临时文件、业务移除超 90 天的文件（方案-文件上传与COS存储 §六）。 */
@Component
public class FileTempPurgeJob implements JobHandler {

    private final FileService files;

    public FileTempPurgeJob(FileService files) {
        this.files = files;
    }

    @Override
    public String name() {
        return "file-temp-purge";
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int n = files.purgeExpired(LocalDateTime.now());
        return n == 0 ? JobResult.skipped("无到期文件") : JobResult.success("purged=" + n);
    }

    @Configuration
    static class Declarations {
        @Bean
        JobDeclaration fileTempPurgeDeclaration() {
            return JobDeclaration.of("file-temp-purge", "临时 / 已移除文件清理", "0 40 4 * * *")
                    .ownerModule("platform.file").timeoutSec(300).lockAtMostSec(600).build();
        }
    }
}
