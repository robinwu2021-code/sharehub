package ai.neargo.sharehub.user.mapper;

import ai.neargo.sharehub.user.entity.UsrIdentity;
import ai.neargo.sharehub.user.entity.UsrUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** user 域 Mapper（嵌套接口，随 MybatisPlusConfig 的 markerInterface 扫描）。 */
public final class UserMappers {

    private UserMappers() {
    }

    public interface UsrUserMapper extends BaseMapper<UsrUser> {
    }

    public interface UsrIdentityMapper extends BaseMapper<UsrIdentity> {
    }
}
