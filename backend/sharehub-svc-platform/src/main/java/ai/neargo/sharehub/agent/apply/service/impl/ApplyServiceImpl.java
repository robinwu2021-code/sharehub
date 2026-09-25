package ai.neargo.sharehub.agent.apply.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.agent.apply.dto.ApplyDtos.ApplyResult;
import ai.neargo.sharehub.agent.apply.dto.ApplyDtos.ApplyView;
import ai.neargo.sharehub.agent.apply.dto.ApplyDtos.AuditReq;
import ai.neargo.sharehub.agent.apply.dto.ApplyDtos.MyApplyView;
import ai.neargo.sharehub.agent.apply.dto.ApplyDtos.SubmitReq;
import ai.neargo.sharehub.agent.AgentStatus;
import ai.neargo.sharehub.agent.RegionScopeJson;
import ai.neargo.sharehub.agent.apply.ApplyStatus;
import ai.neargo.sharehub.agent.apply.entity.AgtApply;
import ai.neargo.sharehub.agent.apply.mapper.AgtApplyMapper;
import ai.neargo.sharehub.agent.apply.service.ApplyService;
import ai.neargo.sharehub.agent.entity.AgtAgent;
import ai.neargo.sharehub.agent.ext.AccountStatus;
import ai.neargo.sharehub.agent.ext.entity.AgtAccount;
import ai.neargo.sharehub.agent.ext.entity.AgtPrincipal;
import ai.neargo.sharehub.agent.ext.mapper.AgtAccountMapper;
import ai.neargo.sharehub.agent.ext.mapper.AgtPrincipalMapper;
import ai.neargo.sharehub.agent.mapper.AgentMapper;
import ai.neargo.sharehub.identity.IdentifierHasher;
import ai.neargo.sharehub.identity.IdentifierNormalizer;
import ai.neargo.sharehub.agent.apply.service.OtpGate;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 入驻申请实现。
 *
 * <p><b>本类集中了 ADR-030 §三的全部硬约束</b>，每一条都配了「不这么做会怎样」，
 * 因为它们的共同点是<b>违反了也不报错</b>。
 */
@Service
public class ApplyServiceImpl implements ApplyService {

    /** 在途状态名，供 SQL 用 —— 取值域由 {@link ApplyStatus#IN_FLIGHT} 定，此处只是投影。 */
    private static final List<String> IN_FLIGHT_NAMES =
            ApplyStatus.IN_FLIGHT.stream().map(Enum::name).toList();

    private static final Set<String> OPERATOR_TYPES = Set.of("AGENT", "CITY_PARTNER");

    private final AgtApplyMapper applyMapper;
    private final AgtPrincipalMapper principalMapper;
    private final AgtAccountMapper accountMapper;
    private final AgentMapper agentMapper;
    private final IdentifierNormalizer normalizer;
    private final IdentifierHasher hasher;
    private final OtpGate otpGate;

    public ApplyServiceImpl(AgtApplyMapper applyMapper, AgtPrincipalMapper principalMapper,
                            AgtAccountMapper accountMapper, AgentMapper agentMapper,
                            IdentifierNormalizer normalizer, IdentifierHasher hasher,
                            OtpGate otpGate) {
        this.applyMapper = applyMapper;
        this.principalMapper = principalMapper;
        this.accountMapper = accountMapper;
        this.agentMapper = agentMapper;
        this.normalizer = normalizer;
        this.hasher = hasher;
        this.otpGate = otpGate;
    }

    // ——————————————————————— 发码 ———————————————————————

    @Override
    public String sendOtp(String rawPhone) {
        /*
         * **规范化必须走同一个函数** —— 发码用 trim 后的原串、验码用规范化后的串，
         * 两者就对不上：用户收到了码却怎么也验不过，而且只在带空格 / 连字符 / 国际区号的
         * 输入上出现（"+971 50 123 4567" 与 "971501234567" 是两个 key）。
         * 把发码也收进 service，就是为了让这一步没有第二个实现。
         */
        return otpGate.issue(normalizer.phone(rawPhone));
    }

    // ——————————————————————— 提交 ———————————————————————

    @Override
    @Transactional
    public ApplyResult submit(SubmitReq req, String staffNo) {
        boolean opsCreated = staffNo != null && !staffNo.isBlank();

        /*
         * 必填校验按 operatorType 分，**不按 source 分**（ADR-030 §3.1 第 2 个「同」）。
         * 代建时放宽必填的话，补件日后没人记得 —— Stripe 的两种 onboarding
         * 核验要求相同，就是这个道理。
         */
        requireText(req.operatorName(), "主体名称必填");
        if (!OPERATOR_TYPES.contains(req.operatorType())) {
            throw new IllegalArgumentException("主体类型只能是 AGENT 或 CITY_PARTNER");
        }
        String phone = normalizer.phone(req.phone());
        String email = IdentifierNormalizer.email(req.email());

        /*
         * 自助提交必须验 OTP —— 这是公开端点唯一能证明「申请人持有这个手机号」的手段。
         * 代建由经办员工的令牌背书，不需要（他面对的是线下签好约的商家）。
         */
        if (!opsCreated) {
            requireText(req.otp(), "验证码必填");
            otpGate.verify(phone, req.otp());
        }

        String phoneHash = hasher.hash(phone);
        String emailHash = hasher.hash(email);

        /*
         * 手机号已有自然人 **不是重复注册**，是多主体申请（ADR-030 §3.5）。
         * 这里只做一件事：把已知的 principal 带上，供审核台显示。
         *
         * ⚠️ 邮箱与已有记录不一致时**不静默覆盖** —— 那要么是换了邮箱（该走改邮箱流程并重新验证），
         * 要么是填错了。留给审核人裁决，见 ApplyView.knownEmailMask。
         */
        AgtPrincipal known = findPrincipalByPhone(phoneHash);

        // 同手机号至多一张在途：DB 的 uk_agt_apply_active 是最终防线，
        // 这里先查一次只为给出人话（撞唯一键的报错没法给用户看）
        Long inFlight = applyMapper.selectCount(new QueryWrapper<AgtApply>()
                .eq("phone_hash", phoneHash).in("status", IN_FLIGHT_NAMES).eq("deleted", 0));
        if (inFlight != null && inFlight > 0) {
            throw new IllegalArgumentException("该手机号已有一张在途申请，请先等待审核结果");
        }

        AgtApply e = new AgtApply();
        e.setApplyNo(nextApplyNo());
        e.setSource(opsCreated ? "OPS_CREATED" : "SELF_SERVICE");
        e.setPhoneHash(phoneHash);
        e.setPhoneMask(IdentifierNormalizer.mask(phone));
        e.setEmailHash(emailHash);
        e.setEmailMask(IdentifierNormalizer.maskEmail(email));
        e.setHashVer(hasher.version());
        e.setPrincipalNo(known == null ? null : known.getPrincipalNo());
        e.setOperatorName(req.operatorName().trim());
        e.setOperatorType(req.operatorType());
        // region_scope 是 JSON 列（两张表都是）—— 直接写自然语言会触发 json_valid 约束、返回 500
        e.setRegionScope(RegionScopeJson.toJson(req.regionScope()));
        e.setShareRate(req.shareRate());
        e.setPayload(req.payload());
        e.setStatus(ApplyStatus.SUBMITTED.name());
        e.setSubmittedBy(opsCreated ? staffNo : phoneHash.substring(0, 12));
        e.setSubmittedAt(LocalDateTime.now());
        e.setTenantId("MAIN");
        applyMapper.insert(e);
        return new ApplyResult(e.getApplyNo(), e.getStatus(), null);
    }

    // ——————————————————————— 申请人查进度 ———————————————————————

    @Override
    public MyApplyView mine(String phone, String otp) {
        String normalized = normalizer.phone(phone);
        requireText(otp, "验证码必填");
        otpGate.verify(normalized, otp);

        AgtApply e = applyMapper.selectOne(new QueryWrapper<AgtApply>()
                .eq("phone_hash", hasher.hash(normalized)).eq("deleted", 0)
                .orderByDesc("id").last("limit 1"));
        if (e == null) throw new IllegalArgumentException("没有查到申请记录");
        return new MyApplyView(e.getApplyNo(), e.getStatus(), e.getOperatorName(),
                e.getPhoneMask(), e.getEmailMask(), e.getRejectReason(), e.getSubmittedAt());
    }

    // ——————————————————————— 运营端队列 ———————————————————————

    @Override
    public PageResult<ApplyView> search(String status, String keyword,
                                        String from, String to, Integer page, Integer size) {
        QueryWrapper<AgtApply> q = new QueryWrapper<AgtApply>().eq("deleted", 0);
        if (status != null && !status.isBlank()) {
            q.eq("status", ApplyStatus.of(status).name());   // 非法值在这里就拒，不要带进 SQL
        } else {
            q.in("status", IN_FLIGHT_NAMES);   // 缺省 = 待办队列（DRAFT 不会出现，见 ApplyStatus）
        }
        if (keyword != null && !keyword.isBlank()) {
            // 只按主体名与申请单号搜。**不按掩码搜** —— 掩码是显示值，
            // 按它搜等于前缀模糊匹配，会把不相干的人捞出来（ADR-030 §2.3）
            q.and(w -> w.like("operator_name", keyword).or().like("apply_no", keyword));
        }
        if (from != null && !from.isBlank()) q.ge("submitted_at", from);
        if (to != null && !to.isBlank()) q.le("submitted_at", to);
        q.orderByAsc("submitted_at").orderByAsc("id");

        Page<AgtApply> p = applyMapper.selectPage(
                new Page<>(page == null ? 1 : page, size == null ? 20 : size), q);
        List<ApplyView> list = p.getRecords().stream().map(this::toView).toList();
        return new PageResult<>(list, p.getTotal());
    }

    // ——————————————————————— 受理 ———————————————————————

    @Override
    @Transactional
    public ApplyResult accept(String applyNo, String staffNo) {
        AgtApply e = mustFind(applyNo);
        transition(e, ApplyStatus.SUBMITTED, ApplyStatus.REVIEWING);
        e.setStatus(ApplyStatus.REVIEWING.name());
        e.setReviewedBy(staffNo);
        applyMapper.updateById(e);
        return new ApplyResult(e.getApplyNo(), e.getStatus(), null);
    }

    // ——————————————————————— 审核 ———————————————————————

    @Override
    @Transactional
    public ApplyResult audit(String applyNo, AuditReq req, String staffNo) {
        AgtApply e = mustFind(applyNo);
        if (!ApplyStatus.of(e.getStatus()).isAuditable()) {
            throw new IllegalArgumentException(
                    "只有待审核或审核中的申请可以处理，当前状态: " + e.getStatus());
        }

        if (!req.approve()) {
            requireText(req.rejectReason(), "驳回原因必填");
            e.setStatus(ApplyStatus.REJECTED.name());
            e.setRejectReason(req.rejectReason().trim());
            stamp(e, staffNo);
            applyMapper.updateById(e);
            return new ApplyResult(e.getApplyNo(), e.getStatus(), null);
        }

        String operatorNo = activate(e, req, staffNo);
        e.setStatus(ApplyStatus.APPROVED.name());
        e.setOperatorNo(operatorNo);
        e.setRejectReason(null);
        stamp(e, staffNo);
        applyMapper.updateById(e);
        return new ApplyResult(e.getApplyNo(), e.getStatus(), operatorNo);
    }

    /**
     * 激活派生：通过那一刻在**一个事务**里造出下游对象（ADR-030 §3.4，形状照 ai-shop 的 activate()）。
     *
     * <p>中途失败会留下「有主体没账号」的半成品，所以整块必须在 {@link Transactional} 里
     * —— 调用方 {@link #audit} 已标注。
     */
    private String activate(AgtApply e, AuditReq req, String staffNo) {
        // ① 运营主体
        AgtAgent agent = new AgtAgent();
        agent.setAgentNo(nextNo(agentMapper, AgtAgent.class, "agent_no", "AG"));
        agent.setName(e.getOperatorName());
        agent.setRegionScope(RegionScopeJson.toJson(
                req.regionScope() != null ? req.regionScope() : e.getRegionScope()));
        java.math.BigDecimal rate = req.shareRate() != null ? req.shareRate() : e.getShareRate();
        agent.setShareRate(rate == null ? 0d : rate.doubleValue());
        agent.setStatus(AgentStatus.ENABLED.name());
        agent.setTenantId("MAIN");
        agentMapper.insert(agent);

        /*
         * ② 自然人：按 phone_hash 查，**命中则复用**（这就是「第 2 个主体」的情形）。
         *    复用时绝不动 cred_ref —— 一个人一套密码，改密一处全主体生效。
         */
        AgtPrincipal principal = findPrincipalByPhone(e.getPhoneHash());
        boolean firstOperatorOfThisPerson = principal == null;
        if (principal == null) {
            principal = new AgtPrincipal();
            principal.setPrincipalNo(nextNo(principalMapper, AgtPrincipal.class, "principal_no", "PR"));
            principal.setPhoneHash(e.getPhoneHash());
            principal.setPhoneMask(e.getPhoneMask());
            principal.setEmailHash(e.getEmailHash());
            principal.setEmailMask(e.getEmailMask());
            principal.setHashVer(e.getHashVer());
            principal.setStatus(AccountStatus.ACTIVE.name());
            principal.setTenantId("MAIN");
            principalMapper.insert(principal);
        }

        /*
         * ③ 属主成员关系行。
         *    is_primary 只给这个人的**第一个**主体 —— 同一人至多一个 is_primary=1
         *    （ADR-030 §2.5 约束 2），否则登录进哪个主体不确定。
         */
        AgtAccount acc = new AgtAccount();
        acc.setAccountNo(nextNo(accountMapper, AgtAccount.class, "account_no", "AA"));
        acc.setAgentNo(agent.getAgentNo());
        acc.setAgentName(agent.getName());
        acc.setUsername(principal.getPrincipalNo());   // username 已不是唯一键，留兼容（ADR-030 §七 3）
        acc.setPrincipalNo(principal.getPrincipalNo());
        acc.setIsOwner(1);
        acc.setIsPrimary(firstOperatorOfThisPerson ? 1 : 0);
        acc.setDisplayName(e.getOperatorName());
        acc.setStatus(AccountStatus.ACTIVE.name());
        acc.setTenantId("MAIN");
        accountMapper.insert(acc);

        // ④ 申请单回写由调用方做（它还要改 status，合并成一次 update）

        /*
         * ⑤ 收款账户占位 —— **本批不做**。
         *    stl_payout_account 属于 ADR-029 的 B3 批，今天全仓无定义（已实测）。
         *    这不是遗漏，是批次边界；但它意味着：
         *    **审核通过的代理商此刻还拿不到钱**（ADR-030 §3.6 的「审核通过 ≠ 能拿钱」）。
         *    B3 落地后在这里补一行，并在代理门户显示「你还不能收款」。
         */
        return agent.getAgentNo();
    }

    // ——————————————————————— 内部 ———————————————————————

    private AgtPrincipal findPrincipalByPhone(String phoneHash) {
        return principalMapper.selectOne(new QueryWrapper<AgtPrincipal>()
                .eq("phone_hash", phoneHash).eq("deleted", 0).last("limit 1"));
    }

    private AgtApply mustFind(String applyNo) {
        AgtApply e = applyMapper.selectOne(new QueryWrapper<AgtApply>()
                .eq("apply_no", applyNo).eq("deleted", 0).last("limit 1"));
        if (e == null) throw BizException.notFound(applyNo);
        return e;
    }

    /** 状态机：非法迁移抛错，不静默跳过（本仓既有约定）。 */
    private static void transition(AgtApply e, ApplyStatus expected, ApplyStatus to) {
        if (ApplyStatus.of(e.getStatus()) != expected) {
            throw new IllegalArgumentException(
                    "非法状态迁移: " + e.getStatus() + " → " + to + "（要求当前为 " + expected + "）");
        }
    }

    /** 审核留痕。代建一键通过时 reviewedBy 与 submittedBy 同值，**照写不省**。 */
    private static void stamp(AgtApply e, String staffNo) {
        e.setReviewedBy(staffNo);
        e.setReviewedAt(LocalDateTime.now());
    }

    private ApplyView toView(AgtApply e) {
        AgtPrincipal known = e.getPrincipalNo() == null ? findPrincipalByPhone(e.getPhoneHash()) : null;
        AgtPrincipal p = known != null ? known : null;
        boolean phoneKnown = e.getPrincipalNo() != null || p != null;
        // 邮箱与已有自然人不一致时，把已有的掩码一并给审核台 —— 由人裁决，不静默覆盖
        String knownEmail = p != null && !p.getEmailHash().equals(e.getEmailHash()) ? p.getEmailMask() : null;
        return new ApplyView(e.getApplyNo(), e.getSource(), e.getStatus(),
                e.getOperatorName(), e.getOperatorType(), e.getPhoneMask(), e.getEmailMask(),
                RegionScopeJson.toPlain(e.getRegionScope()), e.getShareRate(), e.getPayload(), e.getPrincipalNo(),
                e.getRejectReason(), e.getSubmittedBy(), e.getSubmittedAt(),
                e.getReviewedBy(), e.getReviewedAt(), e.getOperatorNo(),
                phoneKnown, knownEmail);
    }

    private String nextApplyNo() {
        return nextNo(applyMapper, AgtApply.class, "apply_no", "AP");
    }

    /**
     * 业务键：扫同前缀最大号 + 1（同 {@code AbstractCrudService.nextNo}）。
     *
     * <p><b>数字序取最大，不能用字典序</b> —— 位宽不一致时会取错：
     * 库里同时有 {@code WD9002}(4 位) 与 {@code WD009003}(6 位) 时，
     * 字典序下前者更大，算出的下一个号会撞已存在行。这个坑在提现单上出过。
     *
     * <p>并发下仍可能撞号，兜底是各表的 {@code uk_*_no} 唯一键 —— 撞了会报错，不会静默写脏。
     */
    private static <T> String nextNo(com.baomidou.mybatisplus.core.mapper.BaseMapper<T> mapper,
                                     Class<T> ignored, String column, String prefix) {
        List<Object> top = mapper.selectObjs(new QueryWrapper<T>()
                .select("MAX(CAST(SUBSTRING(" + column + ", " + (prefix.length() + 1) + ") AS UNSIGNED)) AS mx")
                .likeRight(column, prefix));
        long n = 0L;
        if (top != null && !top.isEmpty() && top.get(0) != null) {
            try {
                n = Long.parseLong(String.valueOf(top.get(0)));
            } catch (NumberFormatException ignore) {
                n = 0L;
            }
        }
        return prefix + String.format("%03d", n + 1);
    }

    private static void requireText(String v, String msg) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(msg);
    }
}
