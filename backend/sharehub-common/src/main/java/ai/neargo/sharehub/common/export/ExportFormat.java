package ai.neargo.sharehub.common.export;

import ai.neargo.sharehub.common.BizException;

/** 导出格式。运营方原文 K8：Excel 与 PDF 都要，不做成配置。 */
public enum ExportFormat {
    XLSX, PDF;

    /** 缺省 XLSX；不认识的值 400，不静默退回某个格式。 */
    public static ExportFormat of(String v) {
        if (v == null || v.isBlank()) return XLSX;
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("error.common.invalid_value", "format");
        }
    }

    public String ext() {
        return name().toLowerCase();
    }
}
