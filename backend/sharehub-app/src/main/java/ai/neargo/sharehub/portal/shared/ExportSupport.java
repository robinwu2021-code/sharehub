package ai.neargo.sharehub.portal.shared;

import ai.neargo.sharehub.common.export.ExportFile;
import ai.neargo.sharehub.common.export.ExportFormat;
import ai.neargo.sharehub.common.export.ExportTable;
import ai.neargo.sharehub.common.export.PdfTableWriter;
import ai.neargo.sharehub.common.export.XlsxWriter;
import ai.neargo.sharehub.common.time.BizClock;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * 后端导出的公共装配（运营方原始需求 K8：报表与发票导出 Excel + PDF，2026-10-29）。
 *
 * <p>导出走后端而不是前端拼当前页：前端只拿得到当前页，「导出」出来的是 10 行而不是全部 —— 原文 RQ-RPT-03 / AC-09。
 * 表头随请求语言（{@code Accept-Language}）取 {@code export.*} 文案；导出时间按业务日历（迪拜）印。
 */
@Component
public class ExportSupport {

    /** 单次导出行数上限：再多就该缩小筛选，而不是让一个请求拖住应用几分钟。 */
    public static final int MAX_ROWS = 20_000;
    private static final int PAGE = 200;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MessageSource messages;
    private final PdfTableWriter pdf;
    private final BizClock clock;

    public ExportSupport(MessageSource messages, PdfTableWriter pdf, BizClock clock) {
        this.messages = messages;
        this.pdf = pdf;
        this.clock = clock;
    }

    public String msg(String key, Object... args) {
        return messages.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }

    /** 逐页拉全量（到 {@link #MAX_ROWS} 为止）。{@code fetch(page, size)} 返回这一页的行。 */
    public static <T> Fetched<T> fetchAll(BiFunction<Integer, Integer, List<T>> fetch) {
        List<T> all = new ArrayList<>();
        for (int page = 1; all.size() < MAX_ROWS; page++) {
            List<T> rows = fetch.apply(page, PAGE);
            if (rows == null || rows.isEmpty()) break;
            all.addAll(rows);
            if (rows.size() < PAGE) break;
        }
        boolean truncated = all.size() >= MAX_ROWS;
        return new Fetched<>(truncated ? all.subList(0, MAX_ROWS) : all, truncated);
    }

    public record Fetched<T>(List<T> rows, boolean truncated) {
    }

    /**
     * @param titleKey   表名文案键
     * @param filter     人读的筛选说明（空 = 不印这一行）
     * @param headerKeys 列头文案键
     * @param fileBase   文件名主干（不含扩展名），会追加业务日期
     */
    public ExportFile render(ExportFormat fmt, String titleKey, String filter, List<String> headerKeys,
                             List<List<Object>> rows, boolean truncated, String fileBase) {
        List<String> meta = new ArrayList<>();
        meta.add(msg("export.meta.exported_at", clock.now().format(TS)));
        if (filter != null && !filter.isBlank()) meta.add(msg("export.meta.filter", filter));
        List<String> footer = new ArrayList<>();
        footer.add(msg("export.meta.rows", rows.size()));
        if (truncated) footer.add(msg("export.meta.truncated", MAX_ROWS));
        ExportTable t = new ExportTable(msg(titleKey), meta, headerKeys.stream().map(this::msg).toList(), rows, footer);
        String name = fileBase + "-" + clock.today() + "." + fmt.ext();
        return fmt == ExportFormat.PDF
                ? new ExportFile(name, ExportFile.PDF, pdf.write(t))
                : new ExportFile(name, ExportFile.XLSX, XlsxWriter.write(t));
    }
}
