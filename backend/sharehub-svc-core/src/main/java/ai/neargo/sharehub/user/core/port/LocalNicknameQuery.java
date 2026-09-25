package ai.neargo.sharehub.user.core.port;

import ai.neargo.sharehub.api.core.port.NicknameQueryPort;
import ai.neargo.sharehub.user.core.service.NicknameLookup;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

/** {@link NicknameQueryPort} 的本地实现：直接委托给既有的 {@link NicknameLookup}，不另起一套查询。 */
@Component
public class LocalNicknameQuery implements NicknameQueryPort {

    private final NicknameLookup lookup;

    public LocalNicknameQuery(NicknameLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public Map<String, String> byUserNos(Collection<String> cUserNos) {
        return lookup.byUserNos(cUserNos);
    }
}
