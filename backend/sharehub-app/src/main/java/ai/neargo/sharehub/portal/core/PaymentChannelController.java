package ai.neargo.sharehub.portal.core;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.trade.pay.dto.PayDtos.PaymentChannelEntry;
import ai.neargo.sharehub.trade.pay.entity.PayChannel;
import ai.neargo.sharehub.trade.pay.service.PaymentChannelService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 支付渠道（[api/README §7.2]，菜单：系统设置 › 支付渠道）。
 *
 * <p><b>唯一的「表子域 ≠ API 前缀」</b>：表是 {@code pay_channel}（trade 子域），
 * 端点却挂 {@code /api/platform}，因为这页归在运营端「系统设置」菜单下。
 * 与 {@link SysDictController}/{@link PlatformController} 同前缀但子路径不重叠。
 *
 * <p><b>密钥只出掩码</b>（ADR-005）：出参只有 {@code apiKeyMasked}/{@code apiSecretMasked}；
 * 入参即使带了明文密钥字段也无处可落 —— 实体压根没有那个字段。明文轮换走 KMS/vault 的独立流程。
 *
 * <p>{@code countries}/{@code currencies}/{@code capabilities} 前端仍是 CSV，
 * 服务端落 {@code pay_channel_scope} 拆表（[db-design §1.7]），这里只做转发。
 */
@RestController
@RequestMapping("/api/platform")
public class PaymentChannelController {

    private final PaymentChannelService channelService;

    public PaymentChannelController(PaymentChannelService channelService) {
        this.channelService = channelService;
    }

    @GetMapping("/payment-channels")
    @PreAuthorize("@perm.can('system:payment_channel:read')")
    public PageResult<PaymentChannelEntry> channels(@RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String mode,
                                                    @RequestParam(required = false) String status) {
        return channelService.page(page, size, keyword,
                Map.of("mode", nz(mode), "status", nz(status)));
    }

    @PostMapping("/payment-channels")
    @PreAuthorize("@perm.can('system:payment_channel:update')")
    public PaymentChannelEntry createChannel(@RequestBody ChannelBody body) {
        PaymentChannelEntry saved = channelService.save(body.toEntity());
        applyScopes(saved.channelCode(), body);
        return channelService.get(saved.channelCode());
    }

    @PostMapping("/payment-channels/{channelCode}")
    @PreAuthorize("@perm.can('system:payment_channel:update')")
    public PaymentChannelEntry updateChannel(@PathVariable String channelCode, @RequestBody ChannelBody body) {
        body.channelCode = channelCode; // 路径为准，忽略 body 里的键，防越权改他渠道
        channelService.save(body.toEntity());
        applyScopes(channelCode, body);
        return channelService.get(channelCode);
    }

    /** 三个 CSV 字段传了才动，没传保持原样（部分更新语义）。 */
    private void applyScopes(String channelCode, ChannelBody body) {
        if (body.countries != null) {
            channelService.replaceScopeCsv(channelCode, PaymentChannelService.SCOPE_COUNTRY, body.countries);
        }
        if (body.currencies != null) {
            channelService.replaceScopeCsv(channelCode, PaymentChannelService.SCOPE_CURRENCY, body.currencies);
        }
        if (body.capabilities != null) {
            channelService.replaceScopeCsv(channelCode, PaymentChannelService.SCOPE_CAPABILITY, body.capabilities);
        }
    }

    /**
     * 入参体：实体字段 + 三个 CSV 多值串。
     *
     * <p>**刻意不含任何明文密钥字段** —— 掩码由 KMS 轮换流程回写，不接受前端提交。
     */
    public static class ChannelBody {
        public String channelCode;
        public String channelName;
        public String channelNameEn;
        public String channelNameAr;
        public String mode;
        public String status;
        public String apiBase;
        public String merchantId;
        /** CSV，null = 不改；空串 = 清空。 */
        public String countries;
        public String currencies;
        public String capabilities;

        PayChannel toEntity() {
            PayChannel e = new PayChannel();
            e.setChannelCode(channelCode);
            e.setChannelName(channelName);
            e.setChannelNameEn(channelNameEn);
            e.setChannelNameAr(channelNameAr);
            e.setMode(mode);
            e.setStatus(status);
            e.setApiBase(apiBase);
            e.setMerchantId(merchantId);
            return e;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
