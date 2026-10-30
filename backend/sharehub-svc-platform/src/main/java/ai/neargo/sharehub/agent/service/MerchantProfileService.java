package ai.neargo.sharehub.agent.service;

import ai.neargo.sharehub.agent.entity.AgtAgent;
import ai.neargo.sharehub.agent.mapper.AgentMapper;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.identity.IdentifierNormalizer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 商家门户「商家资料」（需求方案 §10.3，G23）：商家看自己的档案，只能改联系人。
 *
 * <p><b>商家号只取自会话，不接受入参</b>：{@code agt_agent} 没有数据范围规则，
 * 让调用方传商家号就等于让任何商家读别家的档案。
 *
 * <p>名称、位置、行业只读 —— 它们进对账单、转账文件与站点定位，改了要运营核对。
 * 联系人（姓名、电话、邮箱）是业务联系方式，<b>不是登录标识</b>：改这里不影响怎么登录。
 */
@Service
public class MerchantProfileService {

    public record MerchantProfile(String agentNo, String name, String address, BigDecimal lng, BigDecimal lat,
                          String industry, String contactName, String phone, String email,
                          String defaultSiteNo, String status) {
    }

    public record ContactReq(String contactName, String phone, String email) {
    }

    private final AgentMapper agents;
    private final IdentifierNormalizer normalizer;

    public MerchantProfileService(AgentMapper agents, IdentifierNormalizer normalizer) {
        this.agents = agents;
        this.normalizer = normalizer;
    }

    public MerchantProfile mine() {
        return toVO(require());
    }

    @Transactional
    public MerchantProfile updateContact(ContactReq req) {
        AgtAgent a = require();
        if (req == null || req.contactName() == null || req.contactName().isBlank()) {
            throw BizException.badRequest("error.common.missing_parameter", "contactName");
        }
        a.setContactName(req.contactName().trim());
        // 归一化与入驻同一个函数；格式不对由它抛 400
        a.setPhone(req.phone() == null || req.phone().isBlank() ? null : "+" + normalizer.phone(req.phone()));
        a.setEmail(req.email() == null || req.email().isBlank() ? null : IdentifierNormalizer.email(req.email()));
        agents.updateById(a);
        return toVO(a);
    }

    private AgtAgent require() {
        String agentNo = SecurityUtils.currentUser().map(LoginUser::agentNo).orElse(null);
        if (agentNo == null || agentNo.isBlank()) throw BizException.of(ai.neargo.common.core.ErrorCode.FORBIDDEN, "error.auth.merchant_only");
        AgtAgent a = agents.selectOne(new LambdaQueryWrapper<AgtAgent>().eq(AgtAgent::getAgentNo, agentNo).last("limit 1"));
        if (a == null) throw BizException.notFound(agentNo);
        return a;
    }

    private static MerchantProfile toVO(AgtAgent a) {
        return new MerchantProfile(a.getAgentNo(), a.getName(), a.getAddress(), a.getLng(), a.getLat(), a.getIndustry(),
                a.getContactName(), a.getPhone(), a.getEmail(), a.getDefaultSiteNo(), a.getStatus());
    }
}
