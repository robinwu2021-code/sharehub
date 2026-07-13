package ai.neargo.powerbank.portal.ops;

import ai.neargo.powerbank.common.Kw;
import ai.neargo.common.core.PageResult;
import ai.neargo.powerbank.common.Pages;
import ai.neargo.powerbank.dto.Dto.*;
import ai.neargo.powerbank.seed.SeedData;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * user 域运营端端点（对齐 docs/api §六 与 ops-web {@code http.ts}）：
 * C 端用户 / 营销券 / 风控拉黑（内部）。
 */
@RestController
public class UserController {

    private final SeedData db;

    public UserController(SeedData db) {
        this.db = db;
    }

    @GetMapping("/api/user/users")
    public PageResult<CUser> users(@RequestParam(required = false) Integer page,
                                 @RequestParam(required = false) Integer size,
                                 @RequestParam(required = false) String keyword) {
        List<CUser> rows = db.cUsers().stream()
                .filter(u -> Kw.hit(keyword, u.nickname(), u.phone(), u.cUserNo())).toList();
        return Pages.of(rows, page, size);
    }

    @GetMapping("/api/user/coupons")
    public PageResult<Coupon> coupons(@RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size,
                                    @RequestParam(required = false) String keyword) {
        List<Coupon> rows = db.coupons().stream().filter(c -> Kw.hit(keyword, c.name())).toList();
        return Pages.of(rows, page, size);
    }

    @PostMapping("/internal/user/credit/blacklist")
    @PreAuthorize("@perm.can('user:risk:update')")
    public OkResult blacklist(@RequestBody Map<String, Object> body) {
        String cUserNo = String.valueOf(body.get("cUserNo"));
        boolean blacklisted = Boolean.TRUE.equals(body.get("blacklisted"));
        db.replaceFirst(db.cUsers(), u -> u.cUserNo().equals(cUserNo), db.cUsers().stream()
                .filter(u -> u.cUserNo().equals(cUserNo)).findFirst()
                .map(u -> new CUser(u.cUserNo(), u.nickname(), u.phone(), u.creditScore(), blacklisted,
                        u.orders(), u.registeredAt()))
                .orElse(null));
        return new OkResult(true);
    }
}
