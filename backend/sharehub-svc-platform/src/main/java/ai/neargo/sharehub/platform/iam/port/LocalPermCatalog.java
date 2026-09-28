package ai.neargo.sharehub.platform.iam.port;

import ai.neargo.sharehub.api.platform.port.PermCatalogPort;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamPermission;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.PermissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/** {@link PermCatalogPort} 的本地实现：直接读 {@code iam_permission}（全局表，几百行） */
@Component
public class LocalPermCatalog implements PermCatalogPort {

    private final PermissionMapper mapper;

    public LocalPermCatalog(PermissionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Set<String> allCodes() {
        return mapper.selectList(new LambdaQueryWrapper<IamPermission>().select(IamPermission::getCode)).stream()
                .map(IamPermission::getCode).filter(c -> c != null && !c.isBlank()).collect(Collectors.toUnmodifiableSet());
    }
}
