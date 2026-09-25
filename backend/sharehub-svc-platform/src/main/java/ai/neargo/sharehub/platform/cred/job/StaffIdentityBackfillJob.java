package ai.neargo.sharehub.platform.cred.job;

import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobHandler;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobResult;
import ai.neargo.sharehub.identity.IdentifierHasher;
import ai.neargo.sharehub.identity.IdentifierNormalizer;
import ai.neargo.sharehub.platform.org.entity.IamEmployee;
import ai.neargo.sharehub.platform.org.mapper.IamEmployeeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 把员工档案上的明文邮箱 / 手机算成 hash 回填进去（P3b · B1）。
 *
 * <h2>为什么是一个任务而不是启动时跑一次</h2>
 * 哈希要 `identity-pepper`（运行期配置），SQL 算不出来，所以迁移里做不了。
 * 剩下两个选择是「启动时跑」和「做成任务」：
 * 启动时跑的东西**藏在代码里**，运维不知道它存在、跑没跑过、跑出了什么；
 * 做成任务则在注册表里可见、可手动触发、结果有 detail 可看。
 *
 * <h2>幂等，且只补不改</h2>
 * 只处理「有明文、但 hash 为空」的行。已经有 hash 的一律不碰 ——
 * pepper 轮换是另一件事（要全表重算并切 hash_ver，见 IdentifierHasher 的类注释），
 * 混进这个任务里会让「补齐」和「轮换」用同一个开关，而后者是不可逆的。
 *
 * <h2>⚠️ 没有邮箱也没有手机的员工，这个任务帮不了</h2>
 * 实测：生产 5 个在职员工全都有邮箱；而测试库有 200 个在职、其中 <b>195 个两者皆空</b>
 * （种子造的）。所以本任务的成功判据是「**该补的都补上了**」，
 * 不是「每个在职员工都有 hash」—— 后者在测试库上永远为假，
 * 而那与回填对不对无关。
 *
 * <p>但对生产它是一条真约束：B5 退役共享口令闸之前，
 * 必须确认每个还要登录的人都有可用的标识，否则他会被锁在门外。
 */
@Component
public class StaffIdentityBackfillJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(StaffIdentityBackfillJob.class);

    private final IamEmployeeMapper employees;
    private final IdentifierHasher hasher;
    private final IdentifierNormalizer normalizer;

    public StaffIdentityBackfillJob(IamEmployeeMapper employees,
                                    IdentifierHasher hasher,
                                    IdentifierNormalizer normalizer) {
        this.employees = employees;
        this.hasher = hasher;
        this.normalizer = normalizer;
    }

    @Override
    public String name() {
        return "staff-identity-backfill";
    }

    @Override
    @Transactional
    public JobResult run(JobInvocation invocation) {
        List<IamEmployee> pending = employees.selectList(new LambdaQueryWrapper<IamEmployee>()
                .and(w -> w.isNull(IamEmployee::getEmailHash).or().isNull(IamEmployee::getPhoneHash))
                .and(w -> w.isNotNull(IamEmployee::getEmail).or().isNotNull(IamEmployee::getPhone)));

        int filled = 0;
        int skipped = 0;
        for (IamEmployee e : pending) {
            boolean changed = false;
            if (e.getEmailHash() == null && notBlank(e.getEmail())) {
                e.setEmailHash(hasher.hash(IdentifierNormalizer.email(e.getEmail())));
                changed = true;
            }
            if (e.getPhoneHash() == null && notBlank(e.getPhone())) {
                /*
                 * 手机号规范化可能抛（号码本身不合法）。**单个员工失败不该让整批回滚** ——
                 * 一个脏号能挡住所有人回填，而那时看到的只是任务 FAILED，
                 * 看不出是哪一行、更看不出其余人其实本可以补上。
                 */
                try {
                    e.setPhoneHash(hasher.hash(normalizer.phone(e.getPhone())));
                    changed = true;
                } catch (RuntimeException ex) {
                    log.warn("员工手机号无法规范化，跳过该列 employeeNo={} 原因={}", e.getEmployeeNo(), ex.getMessage());
                    skipped++;
                }
            }
            if (changed) {
                e.setHashVer(hasher.version());
                employees.updateById(e);
                filled++;
            }
        }
        if (filled == 0 && skipped == 0) {
            return JobResult.skipped("没有待回填的员工标识");
        }
        return JobResult.success("filled=" + filled + " skippedPhone=" + skipped);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    @Configuration
    static class Declarations {
        @Bean
        JobDeclaration staffIdentityBackfillDeclaration() {
            /*
             * 每天跑一次而不是只跑一次：新建的员工档案同样需要回填 ——
             * B2 建号时会顺手写 hash，但**通过别的路径建出来的档案**（导入、种子、
             * 直接改库）不会，而那些人到登录时才会发现自己登不进去。
             */
            return JobDeclaration.of("staff-identity-backfill", "员工标识哈希回填", "0 40 4 * * *")
                    .ownerModule("platform.cred")
                    .logEveryRun(true)
                    .timeoutSec(60).lockAtMostSec(120)
                    .build();
        }
    }
}
