package ai.neargo.sharehub.agent.ext.mapper;

import ai.neargo.sharehub.agent.ext.entity.AgtPrincipal;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 代理端自然人。查找一律按 {@code phone_hash} / {@code email_hash}，**绝不按掩码列**。 */
@Mapper
public interface AgtPrincipalMapper extends BaseMapper<AgtPrincipal> {
}
