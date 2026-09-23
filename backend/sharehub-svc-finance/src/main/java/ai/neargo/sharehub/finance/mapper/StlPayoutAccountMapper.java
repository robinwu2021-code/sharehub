package ai.neargo.sharehub.finance.mapper;

import ai.neargo.sharehub.finance.entity.StlPayoutAccount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 收款账户。查找按 {@code payee_type + payee_no}，**不按掩码列**。 */
@Mapper
public interface StlPayoutAccountMapper extends BaseMapper<StlPayoutAccount> {
}
