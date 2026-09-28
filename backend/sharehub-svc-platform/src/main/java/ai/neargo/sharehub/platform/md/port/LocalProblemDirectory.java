package ai.neargo.sharehub.platform.md.port;

import ai.neargo.sharehub.api.platform.port.ProblemDirectoryPort;
import ai.neargo.sharehub.platform.md.ProblemStatus;
import ai.neargo.sharehub.platform.md.entity.MdProblem;
import ai.neargo.sharehub.platform.md.mapper.MdProblemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

/** {@link ProblemDirectoryPort} 的本地实现：读 {@code md_problem}。 */
@Component
public class LocalProblemDirectory implements ProblemDirectoryPort {

    private final MdProblemMapper problems;

    public LocalProblemDirectory(MdProblemMapper problems) {
        this.problems = problems;
    }

    @Override
    public String suggestedActionOf(String problemNo) {
        if (problemNo == null || problemNo.isBlank()) {
            return null;
        }
        MdProblem p = problems.selectOne(new LambdaQueryWrapper<MdProblem>()
                .eq(MdProblem::getProblemNo, problemNo)
                .isNull(MdProblem::getArchivedAt)
                .last("limit 1"));
        return p != null && ProblemStatus.ENABLED.name().equals(p.getStatus()) ? p.getSuggestedAction() : null;
    }
}
