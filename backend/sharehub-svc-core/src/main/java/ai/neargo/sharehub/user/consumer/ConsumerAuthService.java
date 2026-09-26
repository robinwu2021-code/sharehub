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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ConsumerAuthService.class);

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
            UsrIdentity legacy = byLegacyUid(tenant, id);
            if (legacy != null) {
                // 1b) 旧算法命中 → 就地重键。不新建、不归并：这本来就是同一个人。
                legacy.setProviderUid(id.providerUid());
                legacy.setUnionKey(id.unionKey());
                identityMapper.updateById(legacy);
                log.info("登录标识重键 provider={} cUserNo={}", id.provider(), legacy.getCUserNo());
                return new Upsert(byNo(legacy.getCUserNo()), false);
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

    /**
     * 按**旧算法**的 uid 找那条历史行（惰性重键的唯一入口）。
     *
     * <p>为什么不写迁移脚本：旧 uid 是 {@code hash(原始输入)}，而原始输入没有存下来 ——
     * 库里只有 hash，离线算不出「这一行对应哪个归一后的号码」。只有用户本人再登录一次时
     * 才同时拿到两者，所以重键只能发生在这一刻。
     *
     * <p>{@code legacyUid == null}（其余渠道，算法从未变过）直接跳过。
     */
    private UsrIdentity byLegacyUid(String tenant, ResolvedIdentity id) {
        if (id.legacyUid() == null || id.legacyUid().isBlank()) {
            return null;
        }
        return identityMapper.selectOne(new LambdaQueryWrapper<UsrIdentity>()
                .eq(UsrIdentity::getTenantId, tenant)
                .eq(UsrIdentity::getProvider, id.provider().name())
                .eq(UsrIdentity::getProviderUid, id.legacyUid()).last("limit 1"));
    }

    private String createUser(String tenant, ResolvedIdentity id) {
        UsrUser u = new UsrUser();
        u.setCUserNo("CU" + UUID.randomUUID().toString().replace("-", "").substring(0, 14));
        u.setTenantId(tenant);
        boolean wechat = id.provider() == Provider.WECHAT_MP || id.provider() == Provider.WECHAT_OA;
        u.setOpenid(wechat ? id.providerUid() : null);
        /*
         * unionid 与 openid 同样**只对微信渠道有意义**（V4 的列注释：「唯一性移交 usr_identity；
         * openid/unionid 仅主渠道冗余展示」）。此前无条件写 unionKey，于是手机号渠道往这列塞
         * 'PHONE:<hash>' —— 一个没有任何代码会读的合成值，而 uid 变长到 64 位后
         * 它就是 70 字符、超过 VARCHAR(64)，登录直接 500。归并靠的是 usr_identity.union_key，
         * 不是这列。
         */
        u.setUnionid(wechat ? id.unionKey() : null);
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
