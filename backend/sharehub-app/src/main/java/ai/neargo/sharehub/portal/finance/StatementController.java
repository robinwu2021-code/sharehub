package ai.neargo.sharehub.portal.finance;

import ai.neargo.sharehub.finance.statement.StatementService;
import ai.neargo.sharehub.finance.statement.StatementService.Statement;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 场地方对账单（运营核心流程 G3，裁决 #2）：结算单导出对账单线下发给场地方，首版不做场地方门户。
 */
@RestController
public class StatementController {

    private final StatementService statements;

    public StatementController(StatementService statements) {
        this.statements = statements;
    }

    @GetMapping("/api/trade/settlements/{settleNo}/statement")
    @PreAuthorize("@perm.can('finance:settlement:read')")
    public Statement statement(@PathVariable String settleNo) {
        return statements.statement(settleNo);
    }

    /**
     * 可打印对账单：浏览器打开后「打印 → 存为 PDF」。直接写响应（与文件下载同一做法）——
     * 统一响应信封只适合 JSON，套在一张页面上会变成 {code, data:"<html>…"}。
     */
    @GetMapping("/api/trade/settlements/{settleNo}/statement.html")
    @PreAuthorize("@perm.can('finance:settlement:read')")
    public void printable(@PathVariable String settleNo, @RequestParam(required = false) String lang,
                          jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        String html = statements.html(settleNo, lang);   // 先生成：查不到结算单时抛业务异常走统一错误处理，而不是写半张页面
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write(html);
    }
}
