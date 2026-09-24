package ai.neargo.sharehub.portal.platform;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.agent.apply.dto.ApplyDtos.ApplyResult;
import ai.neargo.sharehub.agent.apply.dto.ApplyDtos.ApplyView;
import ai.neargo.sharehub.agent.apply.dto.ApplyDtos.AuditReq;
import ai.neargo.sharehub.agent.apply.dto.ApplyDtos.MyApplyView;
import ai.neargo.sharehub.agent.apply.dto.ApplyDtos.SubmitReq;
import ai.neargo.sharehub.agent.apply.service.ApplyService;
import ai.neargo.sharehub.auth.DevMode;
import ai.neargo.sharehub.auth.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 入驻申请：**两条入口、一个端点**（ADR-030 §三）。
 *
 * <p>用户 2026-09-23 定：「商家可以注册，运营商也可以代建，条件相同，商家注册，也需要运营商审核通过。」
 *
 * <p><b>不新增路径前缀</b>：全部挂在既有的 {@code /api/agent} 下。那个前缀的意思是
 * 「代理商这个<b>业务域</b>」，不是「代理商这个<b>端</b>」（[接口清单 §3.1]）——
 * 现有 {@code /api/agent/**} 17 个端点本来就是运营人员在用。
 *
 * <p><b>两个免鉴权端点</b>（{@code /apply} 与 {@code /apply/mine}）在
 * {@link ai.neargo.sharehub.config.SecurityConfig} 的白名单里，防刷靠手机号 OTP。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentApplyController {

    private final ApplyService applyService;
    private final DevMode devMode;

    public AgentApplyController(ApplyService applyService, DevMode devMode) {
        this.applyService = applyService;
        this.devMode = devMode;
    }

    /**
     * 提交入驻申请 —— **自助与代建共用同一个端点**。
     *
     * <p><b>{@code source} 由这里判定，不从请求体取</b>：带 STAFF 令牌即代建，否则自助。
     * 让客户端传的话，自助申请可以自称代建，绕开 OTP 与限流（ADR-030 §3.2）。
     */
    /**
     * **自助注册发码**（免鉴权）。
     *
     * <p>这是自助入口唯一能证明「申请人确实持有这个手机号」的手段 —— 没有它，
     * 任何人都能拿别人的号提交申请，而那个人只会在审核电话打来时才知道。
     *
     * <p>生产只回 {@code {"sent": true}}，码由短信通道送达；dev-mode 下回传便于联调
     * （与 C 端 {@code /mp/auth/otp} 同一个取舍，也共用同一套 OtpService 的限流与有效期 ——
     * 各写一套的结果一定是两套配置慢慢分叉，而分叉出来的那一套通常没人测）。
     */
    @PostMapping("/apply/otp")
    public Map<String, Object> applyOtp(@RequestBody Map<String, String> body) {
        String code = applyService.sendOtp(body.get("phone"));
        return devMode.isEnabled() ? Map.of("sent", true, "devCode", code) : Map.of("sent", true);
    }

    /**
     * **自助注册**：商家自己提交（免鉴权 + 手机号 OTP）。
     *
     * 这个端点要接待「还没有账号的陌生人」，所以整体放行；防刷靠三道闸：
     * OTP · 单 IP 限流（{@code AnonymousRateLimitFilter}）· 同手机号至多一张在途（生成列 {@code active_key}）。
     *
     * <p>{@code source} 由**路由**决定（恒为 {@code SELF_SERVICE}），不从请求体取、
     * 也不靠「有没有带令牌」去猜 —— 后者是隐式分支，读代码的人看不出这里有两种行为。
     */
    @PostMapping("/apply")
    public ApplyResult selfServiceApply(@RequestBody SubmitReq req) {
        return applyService.submit(req, null);
    }

    /**
     * **运营代建**：替线下签约的商家录入（{@code source = OPS_CREATED}）。
     *
     * <p>与自助落**同一张表、同一个状态机、同一套必填校验**（ADR-030 §3.1 的三个「同」），
     * 差别只有 {@code source} 与 {@code submittedBy}。
     *
     * <p><b>为什么拆成两个端点而不是一个</b>：代建是受控动作（替商家做主、且跳过 OTP），
     * 必须判 {@code agent:apply:create}；而自助端点整体免鉴权，挂 {@code @PreAuthorize}
     * 会把陌生人一起拦掉。把判权写进方法体倒是能跑，但**契约工具扫不到**，
     * 于是权限对账里它是一个「前端在用、后端不存在」的幽灵码 —— 而前端按钮正是按它显示的，
     * 「界面上没有按钮」会被误当成「后端拦得住」。ai-shop 也是两个入口同落一个 createApply。
     *
     * <p>录入码与放行码 {@code agent:apply:approve} **分开** —— 合成一个的话
     * 「能录入」就等于「能放行」，四眼原则以后没有落脚点。
     */
    @PostMapping("/applies")
    @PreAuthorize("@perm.can('agent:apply:create')")
    public ApplyResult opsCreateApply(@RequestBody SubmitReq req) {
        return applyService.submit(req, SecurityUtils.userNo());
    }

    /** 申请人查进度与驳回原因（免鉴权，凭手机号 + OTP）。驳回原因**原样回显**，不是只给运营看。 */
    @GetMapping("/apply/mine")
    public MyApplyView mine(@RequestParam String phone, @RequestParam String otp) {
        return applyService.mine(phone, otp);
    }

    /** 运营端待办队列与历史检索。{@code status} 为空 = 在途（SUBMITTED + REVIEWING）。 */
    @GetMapping("/applies")
    @PreAuthorize("@perm.can('agent:apply:read')")
    public PageResult<ApplyView> applies(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return applyService.search(status, keyword, from, to, page, size);
    }

    /** 受理：SUBMITTED → REVIEWING。 */
    @PostMapping("/applies/{applyNo}/accept")
    @PreAuthorize("@perm.can('agent:apply:approve')")
    public ApplyResult accept(@PathVariable String applyNo) {
        return applyService.accept(applyNo, SecurityUtils.userNo());
    }

    /**
     * 审核：通过 / 驳回。
     *
     * <p>放行码 {@code agent:apply:approve} 与录入码 {@code agent:apply:create} <b>分开发</b> ——
     * 合成一个的话「能录入」就等于「能放行」，四眼原则以后没有落脚点（ADR-030 §3.3）。
     */
    @PostMapping("/applies/{applyNo}/audit")
    @PreAuthorize("@perm.can('agent:apply:approve')")
    public ApplyResult audit(@PathVariable String applyNo, @RequestBody AuditReq req) {
        return applyService.audit(applyNo, req, SecurityUtils.userNo());
    }
}
