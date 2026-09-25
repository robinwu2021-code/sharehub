package ai.neargo.sharehub.loc.port;

import ai.neargo.sharehub.api.platform.port.FileAccessChecker;
import ai.neargo.sharehub.loc.entity.LocContract;
import ai.neargo.sharehub.loc.mapper.LocMappers;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

/** 合同附件的下载鉴权：能按数据范围读到这份合同，就能看它的附件。 */
@Component
public class ContractFileAccessChecker implements FileAccessChecker {

    private final LocMappers.ContractMapper contracts;

    public ContractFileAccessChecker(LocMappers.ContractMapper contracts) {
        this.contracts = contracts;
    }

    @Override
    public String bizType() {
        return "CONTRACT";
    }

    @Override
    public boolean canRead(String bizNo) {
        // 带数据范围的普通查询：范围外即查不到 ⇒ 无权（与「不存在」同一结果）
        return contracts.selectCount(new LambdaQueryWrapper<LocContract>().eq(LocContract::getContractNo, bizNo)) > 0;
    }
}
