package ai.neargo.sharehub.user.consumer;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.PermVersion;
import ai.neargo.sharehub.auth.Realm;
import ai.neargo.sharehub.auth.TokenStore;
import ai.neargo.sharehub.user.entity.UsrIdentity;
import ai.neargo.sharehub.user.entity.UsrUser;
import ai.neargo.sharehub.user.mapper.UserMappers.UsrIdentityMapper;
import ai.neargo.sharehub.user.mapper.UserMappers.UsrUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * C 端统一登录服务：grantType 分发策略 → 规范身份 → upsert 建户/归并 → 签发**统一 Bearer**（realm=CONSUMER）。
 * 三端（App/小程序/H5）登录后完全一致（TDD-认证鉴权-实现细节 §B4）。
 */
@Service
public class ConsumerAuthService {

    private final Map<String, ConsumerLoginStrategy> strategies;
    private final UsrUserMapper userMapper;
    private final UsrIdentityMapper identityMapper;
    private final TokenStore tokenStore;
    private final PermVersion permVersion;

    public ConsumerAuthService(List<ConsumerLoginStrategy> strategyList,
                               UsrUserMapper userMapper, UsrIdentityMapper identityMapper,
                               TokenStore tokenStore, PermVersion permVersion) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(ConsumerLoginStrategy::grantType, Function.identity()));
        this.userMapper = userMapper;
        this.identityMapper = identityMapper;
        this.tokenStore = tokenStore;
        this.permVersion = permVersion;
    }

    @Transactional
    public ConsumerLoginVO login(ConsumerLoginReq req) {
        ConsumerLoginStrategy strategy = strategies.get(req.grantType());
        if (strategy == null) {
            throw BizException.badRequest("error.auth.grant_type_unsupported", req.grantType());
        }
        ResolvedIdentity id = strategy.authenticate(req);              // ← 端差异全在此
        String tenant = (req.tenantNo() == null || req.tenantNo().isBlank()) ? "MAIN" : req.tenantNo();
        Upsert r = upsertByIdentity(tenant, id);
        UsrUser u = r.user();
        String display = (u.getNickname() == null || u.getNickname().isBlank()) ? u.getCUserNo() : u.getNickname();
        LoginUser principal = new LoginUser(Realm.CONSUMER, u.getCUserNo(), display, "CONSUMER",
                List.of(), tenant, "", DataScopeSpec.of("SELF", java.util.Set.of(u.getCUserNo())));
        String token = tokenStore.issue(new TokenStore.SessionData(principal, List.of(), permVersion.get()));
        return new ConsumerLoginVO(token, u.getCUserNo(), r.isNew(), tenant);
    }

    private record Upsert(UsrUser user, boolean isNew) {
    }

    /** 建户/归并：同渠道同 uid → 命中；否则 union_key 归并；否则新建。均绑定一条 usr_identity。 */
    private Upsert upsertByIdentity(String tenant, ResolvedIdentity id) {
        return DataScopeContext.executeWithoutScope(() -> {
            UsrIdentity existing = identityMapper.selectOne(new LambdaQueryWrapper<UsrIdentity>()
                    .eq(UsrIdentity::getTenantId, tenant)
                    .eq(UsrIdentity::getProvider, id.provider().name())
                    .eq(UsrIdentity::getProviderUid, id.providerUid()).last("limit 1"));
            if (existing != null) {
                return new Upsert(byNo(existing.getCUserNo()), false);   // 1) 同渠道同 uid
            }
            UsrIdentity merged = identityMapper.selectOne(new LambdaQueryWrapper<UsrIdentity>()
                    .eq(UsrIdentity::getTenantId, tenant)
                    .eq(UsrIdentity::getUnionKey, id.unionKey()).last("limit 1"));
            String cUserNo;
            boolean isNew;
            if (merged != null) {
                cUserNo = merged.getCUserNo();                          // 2) unionid 归并
                isNew = false;
            } else {
                cUserNo = createUser(tenant, id);                      // 3) 新建
                isNew = true;
            }
            UsrIdentity bind = new UsrIdentity();
            bind.setCUserNo(cUserNo);
            bind.setTenantId(tenant);
            bind.setProvider(id.provider().name());
            bind.setProviderUid(id.providerUid());
            bind.setUnionKey(id.unionKey());
            identityMapper.insert(bind);
            return new Upsert(byNo(cUserNo), isNew);
        });
    }

    private String createUser(String tenant, ResolvedIdentity id) {
        UsrUser u = new UsrUser();
        u.setCUserNo("CU" + UUID.randomUUID().toString().replace("-", "").substring(0, 14));
        u.setTenantId(tenant);
        boolean wechat = id.provider() == Provider.WECHAT_MP || id.provider() == Provider.WECHAT_OA;
        u.setOpenid(wechat ? id.providerUid() : null);
        u.setUnionid(id.unionKey());
        u.setNickname(id.nickname());
        u.setAvatar(id.avatar());
        u.setCreditScore(600);
        u.setStatus("ACTIVE");
        userMapper.insert(u);
        return u.getCUserNo();
    }

    private UsrUser byNo(String cUserNo) {
        return userMapper.selectOne(new LambdaQueryWrapper<UsrUser>().eq(UsrUser::getCUserNo, cUserNo).last("limit 1"));
    }
}
