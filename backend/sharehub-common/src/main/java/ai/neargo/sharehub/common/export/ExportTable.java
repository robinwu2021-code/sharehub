package ai.neargo.sharehub.common.export;

import java.util.List;

/**
 * 一张待导出的表（运营方原始需求 K8：报表与发票都要 Excel + PDF，2026-10-29）。
 *
 * @param title      表名，也是 Excel 工作表名与 PDF 标题
 * @param meta       标题下的说明行（筛选条件、导出时间、开票主体等），可空
 * @param headers    列头
 * @param rows       行；单元格是 {@link Number} 时 Excel 写成数值格，其余按文本
 * @param footer     表后的说明行（合计、备注），可空
 */
public record ExportTable(String title, List<String> meta, List<String> headers, List<List<Object>> rows,
                          List<String> footer) {

    public ExportTable {
        meta = meta == null ? List.of() : meta;
        footer = footer == null ? List.of() : footer;
        rows = rows == null ? List.of() : rows;
    }
}
