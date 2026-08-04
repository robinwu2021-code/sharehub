package ai.neargo.sharehub.trade.pay.service.impl;

import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.trade.pay.dto.PayDtos.ChannelScopeEntry;
import ai.neargo.sharehub.trade.pay.dto.PayDtos.PaymentChannelDetail;
import ai.neargo.sharehub.trade.pay.dto.PayDtos.PaymentChannelEntry;
import ai.neargo.sharehub.trade.pay.entity.PayChannel;
import ai.neargo.sharehub.trade.pay.entity.PayChannelScope;
import ai.neargo.sharehub.trade.pay.mapper.PayChannelMapper;
import ai.neargo.sharehub.trade.pay.mapper.PayChannelScopeMapper;
import ai.neargo.sharehub.trade.pay.service.PaymentChannelService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 支付渠道实现。CRUD 走基类；国家/币种/能力的多值拆表手写。
 *
 * <p><b>密钥</b>：本类只搬运掩码列。任何时候都不要在这里读/写/记明文密钥（ADR-005）。
 *
 * <p><b>N+1 说明</b>：{@link #toVO} 会为每行渠道各查一次 scope 表把 CSV 拼回。
 * 渠道总数是十几行量级（「一张表 + 一页 + 配置抽屉」就是产品设计），
 * 换 join 反而把简单的东西搞复杂；若将来渠道上百再改批量查。
 */
@Service
public class PaymentChannelServiceImpl extends AbstractCrudService<PayChannel, PaymentChannelEntry>
        implements PaymentChannelService {

    private final PayChannelScopeMapper scopeMapper;

    public PaymentChannelServiceImpl(PayChannelMapper mapper, PayChannelScopeMapper scopeMapper) {
        super(mapper);
        this.scopeMapper = scopeMapper;
    }

    @Override
    protected String keyColumn() {
        return "channel_code";
    }

    @Override
    protected String keyOf(PayChannel e) {
        return e.getChannelCode();
    }

    @Override
    protected void setKey(PayChannel e, String no) {
        e.setChannelCode(no);
    }

    // keyPrefix() 不覆盖 → null → channel_code 是自然键（STRIPE/TABBY/NEARPAY…），新建必须显式给

    @Override
    protected String[] keywordColumns() {
        return new String[]{"channel_code", "channel_name", "channel_name_en"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"mode", "status"};
    }

    @Override
    protected String orderColumn() {
        return "channel_code";
    }

    @Override
    protected boolean orderDesc() {
        return false;
    }

    @Override
    protected void beforeCreate(PayChannel e) {
        if (e.getMode() == null || e.getMode().isBlank()) e.setMode("DELEGATED");
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus("DISABLED"); // 新渠道默认关，须显式启用
    }

    @Override
    protected void beforeUpdate(PayChannel e, PayChannel current) {
        // 掩码列是「展示态」，只有轮换密钥时由 KMS 流程回写；普通编辑不许清空
        if (e.getApiKeyMasked() == null) e.setApiKeyMasked(current.getApiKeyMasked());
        if (e.getApiSecretMasked() == null) e.setApiSecretMasked(current.getApiSecretMasked());
    }

    @Override
    protected PaymentChannelEntry toVO(PayChannel e) {
        return new PaymentChannelEntry(
                e.getChannelCode(), e.getChannelName(), e.getChannelNameEn(), e.getChannelNameAr(),
                e.getMode(), e.getStatus(),
                csv(scopeValues(e.getChannelCode(), SCOPE_COUNTRY)),
                csv(scopeValues(e.getChannelCode(), SCOPE_CURRENCY)),
                csv(scopeValues(e.getChannelCode(), SCOPE_CAPABILITY)),
                e.getApiBase(), e.getMerchantId(),
                e.getApiKeyMasked(), e.getApiSecretMasked(),
                e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
    }

    // ——————————————————————— §1.7 多值拆表 ———————————————————————

    @Override
    public PaymentChannelDetail detail(String channelCode) {
        PaymentChannelEntry channel = get(channelCode);
        return channel == null ? null : new PaymentChannelDetail(channel, scopesOf(channelCode));
    }

    @Override
    public List<ChannelScopeEntry> scopesOf(String channelCode) {
        if (channelCode == null || channelCode.isBlank()) return List.of();
        return scopeMapper.selectList(new LambdaQueryWrapper<PayChannelScope>()
                        .eq(PayChannelScope::getChannelCode, channelCode)
                        .orderByAsc(PayChannelScope::getId))
                .stream()
                .map(s -> new ChannelScopeEntry(s.getChannelCode(), s.getScopeType(), s.getScopeValue()))
                .toList();
    }

    @Override
    public List<String> scopeValues(String channelCode, String scopeType) {
        if (channelCode == null || channelCode.isBlank() || scopeType == null || scopeType.isBlank()) {
            return List.of();
        }
        return scopeMapper.selectList(new LambdaQueryWrapper<PayChannelScope>()
                        .eq(PayChannelScope::getChannelCode, channelCode)
                        .eq(PayChannelScope::getScopeType, scopeType)
                        .orderByAsc(PayChannelScope::getId))
                .stream()
                .map(PayChannelScope::getScopeValue)
                .toList();
    }

    @Override
    @Transactional
    public void replaceScope(String channelCode, String scopeType, List<String> scopeValues) {
        if (channelCode == null || channelCode.isBlank()) throw new IllegalArgumentException("channelCode 不能为空");
        if (scopeType == null || scopeType.isBlank()) throw new IllegalArgumentException("scopeType 不能为空");

        scopeMapper.delete(new LambdaQueryWrapper<PayChannelScope>()
                .eq(PayChannelScope::getChannelCode, channelCode)
                .eq(PayChannelScope::getScopeType, scopeType));

        if (scopeValues == null) return;
        scopeValues.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .forEach(v -> {
                    PayChannelScope row = new PayChannelScope();
                    row.setTenantId(TENANT_MAIN);
                    row.setChannelCode(channelCode);
                    row.setScopeType(scopeType);
                    row.setScopeValue(v);
                    scopeMapper.insert(row);
                });
    }

    @Override
    public void replaceScopeCsv(String channelCode, String scopeType, String csv) {
        replaceScope(channelCode, scopeType, split(csv));
    }

    @Override
    public List<String> channelsFor(String country, String currency, String capability) {
        List<String> codes = null;
        codes = narrow(codes, SCOPE_COUNTRY, country);
        codes = narrow(codes, SCOPE_CURRENCY, currency);
        codes = narrow(codes, SCOPE_CAPABILITY, capability);
        if (codes == null) return List.of(); // 三个条件全空：不猜意图，让调用方走 page()
        if (codes.isEmpty()) return List.of();

        return mapper.selectList(new LambdaQueryWrapper<PayChannel>()
                        .in(PayChannel::getChannelCode, codes)
                        .eq(PayChannel::getStatus, "ENABLED")
                        .orderByAsc(PayChannel::getChannelCode))
                .stream()
                .map(PayChannel::getChannelCode)
                .toList();
    }

    /** 逐条件求交集；条件为空则不收窄。 */
    private List<String> narrow(List<String> acc, String scopeType, String value) {
        if (value == null || value.isBlank()) return acc;
        List<String> hit = scopeMapper.selectList(new LambdaQueryWrapper<PayChannelScope>()
                        .eq(PayChannelScope::getScopeType, scopeType)
                        .eq(PayChannelScope::getScopeValue, value.trim()))
                .stream()
                .map(PayChannelScope::getChannelCode)
                .distinct()
                .toList();
        if (acc == null) return hit;
        List<String> both = new ArrayList<>(acc);
        both.retainAll(hit);
        return both;
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String csv(List<String> values) {
        return values.isEmpty() ? "" : String.join(",", values);
    }
}
