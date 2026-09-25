package ai.neargo.sharehub.finance.statement;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 对账单的可打印 HTML（G3）。<b>为什么不是直接出 PDF</b>：中文 / 阿拉伯文要进 PDF 必须嵌字体，
 * 仓库里还没有 PDF 库（依赖与字体授权待定）；浏览器打印成 PDF 能原生渲染三种文字、阿语自动右到左。
 * 所有动态值一律转义。
 */
final class StatementHtml {

    private StatementHtml() {
    }

    private static final Map<String, Map<String, String>> L = Map.of(
            "zh", Map.ofEntries(Map.entry("title", "场地方对账单"), Map.entry("payee", "收款方"), Map.entry("period", "账期"),
                    Map.entry("settle", "结算单号"), Map.entry("orders", "订单数"), Map.entry("gross", "订单实收"),
                    Map.entry("share", "分成"), Map.entry("contract", "合同"), Map.entry("rate", "比例"),
                    Map.entry("adjust", "调整项"), Map.entry("kind", "类型"), Map.entry("amount", "金额"), Map.entry("note", "说明"),
                    Map.entry("total", "本期应付"), Map.entry("none", "无")),
            "en", Map.ofEntries(Map.entry("title", "Venue Statement"), Map.entry("payee", "Payee"), Map.entry("period", "Period"),
                    Map.entry("settle", "Settlement No."), Map.entry("orders", "Orders"), Map.entry("gross", "Order revenue"),
                    Map.entry("share", "Revenue share"), Map.entry("contract", "Contract"), Map.entry("rate", "Rate"),
                    Map.entry("adjust", "Adjustments"), Map.entry("kind", "Type"), Map.entry("amount", "Amount"), Map.entry("note", "Note"),
                    Map.entry("total", "Payable this period"), Map.entry("none", "None")),
            "ar", Map.ofEntries(Map.entry("title", "كشف حساب الموقع"), Map.entry("payee", "المستفيد"), Map.entry("period", "الفترة"),
                    Map.entry("settle", "رقم التسوية"), Map.entry("orders", "الطلبات"), Map.entry("gross", "إيرادات الطلبات"),
                    Map.entry("share", "حصة الإيرادات"), Map.entry("contract", "العقد"), Map.entry("rate", "النسبة"),
                    Map.entry("adjust", "التسويات"), Map.entry("kind", "النوع"), Map.entry("amount", "المبلغ"), Map.entry("note", "ملاحظة"),
                    Map.entry("total", "المستحق لهذه الفترة"), Map.entry("none", "لا يوجد")));

    static String render(StatementService.Statement s, String lang) {
        String lg = lang != null && L.containsKey(lang) ? lang : "zh";
        Map<String, String> t = L.get(lg);
        StringBuilder b = new StringBuilder(4096);
        b.append("<!DOCTYPE html><html lang=\"").append(lg).append("\" dir=\"").append("ar".equals(lg) ? "rtl" : "ltr").append("\"><head>")
                .append("<meta charset=\"utf-8\"><title>").append(esc(t.get("title"))).append(' ').append(esc(s.settleNo())).append("</title>")
                .append("<style>body{font-family:system-ui,sans-serif;margin:32px;color:#111}h1{font-size:20px}table{border-collapse:collapse;width:100%;margin:12px 0}")
                .append("th,td{border:1px solid #bbb;padding:6px 8px;text-align:start;font-size:13px}th{background:#f2f2f2}.num{text-align:end}")
                .append(".total{font-size:16px;font-weight:600}@media print{body{margin:12mm}}</style></head><body>");
        b.append("<h1>").append(esc(t.get("title"))).append("</h1><table>")
                .append(row(t.get("payee"), s.payeeName() == null ? s.payeeNo() : s.payeeName() + " (" + s.payeeNo() + ")"))
                .append(row(t.get("period"), s.period())).append(row(t.get("settle"), s.settleNo()))
                .append(row(t.get("orders"), String.valueOf(s.orderCount())))
                .append(row(t.get("gross"), money(s.grossTotal(), s.currency()))).append("</table>");
        b.append("<h2>").append(esc(t.get("share"))).append("</h2><table><tr><th>").append(esc(t.get("contract"))).append("</th><th>")
                .append(esc(t.get("rate"))).append("</th><th>").append(esc(t.get("orders"))).append("</th><th>").append(esc(t.get("gross")))
                .append("</th><th>").append(esc(t.get("amount"))).append("</th></tr>");
        if (s.shares().isEmpty()) b.append("<tr><td colspan=\"5\">").append(esc(t.get("none"))).append("</td></tr>");
        for (var l : s.shares()) {
            b.append("<tr><td>").append(esc(l.contractNo())).append("</td><td class=\"num\">").append(esc(pct(l.rate())))
                    .append("</td><td class=\"num\">").append(l.orders()).append("</td><td class=\"num\">").append(esc(money(l.gross(), s.currency())))
                    .append("</td><td class=\"num\">").append(esc(money(l.amount(), s.currency()))).append("</td></tr>");
        }
        b.append("</table><h2>").append(esc(t.get("adjust"))).append("</h2><table><tr><th>").append(esc(t.get("kind"))).append("</th><th>")
                .append(esc(t.get("contract"))).append("</th><th>").append(esc(t.get("period"))).append("</th><th>").append(esc(t.get("amount")))
                .append("</th><th>").append(esc(t.get("note"))).append("</th></tr>");
        if (s.adjustments().isEmpty()) b.append("<tr><td colspan=\"5\">").append(esc(t.get("none"))).append("</td></tr>");
        for (var a : s.adjustments()) {
            b.append("<tr><td>").append(esc(a.kind())).append("</td><td>").append(esc(a.contractNo())).append("</td><td>").append(esc(a.period()))
                    .append("</td><td class=\"num\">").append(esc(money(a.amount(), s.currency()))).append("</td><td>").append(esc(a.note())).append("</td></tr>");
        }
        b.append("</table><p class=\"total\">").append(esc(t.get("total"))).append(": ").append(esc(money(s.total(), s.currency()))).append("</p>");
        return b.append("</body></html>").toString();
    }

    private static String row(String k, String v) {
        return "<tr><th>" + esc(k) + "</th><td>" + esc(v) + "</td></tr>";
    }

    private static String money(BigDecimal v, String cur) {
        return (v == null ? "0.00" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()) + (cur == null ? "" : " " + cur);
    }

    private static String pct(BigDecimal r) {
        return r == null ? "" : r.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder o = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> o.append("&lt;");
                case '>' -> o.append("&gt;");
                case '&' -> o.append("&amp;");
                case '"' -> o.append("&quot;");
                case '\'' -> o.append("&#39;");
                default -> o.append(c);
            }
        }
        return o.toString();
    }
}
