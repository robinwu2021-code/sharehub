package ai.neargo.sharehub.common.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 最小 xlsx 写出器：一个工作表、表头加粗、数字写成数值格（Excel 里能直接求和）、文本用 inlineStr。
 *
 * <p>不引 Apache POI：导出只要「表格 → 文件」这一个方向，POI 的体量（十几 MB 依赖）换不来什么。
 * xlsx 本身就是几份 XML 打成 zip，格式在 ECMA-376 里是稳定的。
 */
public final class XlsxWriter {

    private XlsxWriter() {
    }

    public static byte[] write(ExportTable t) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                    </Types>""");
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>""");
            put(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                    </Relationships>""");
            put(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets><sheet name="%s" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>""".formatted(esc(sheetName(t.title()))));
            // 样式 0 = 默认，1 = 粗体（表头）
            put(zip, "xl/styles.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
                    <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
                    <borders count="1"><border/></borders>
                    <cellStyleXfs count="1"><xf/></cellStyleXfs>
                    <cellXfs count="2"><xf fontId="0"/><xf fontId="1" applyFont="1"/></cellXfs>
                    </styleSheet>""");
            put(zip, "xl/worksheets/sheet1.xml", sheet(t));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static String sheet(ExportTable t) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        int r = 0;
        for (String m : t.meta()) row(sb, ++r, List.of(m), false);
        if (!t.meta().isEmpty()) r++;   // 说明与表头之间空一行
        row(sb, ++r, List.copyOf(t.headers()), true);
        for (List<Object> cells : t.rows()) row(sb, ++r, cells, false);
        if (!t.footer().isEmpty()) r++;
        for (String f : t.footer()) row(sb, ++r, List.of(f), false);
        return sb.append("</sheetData></worksheet>").toString();
    }

    private static void row(StringBuilder sb, int r, List<?> cells, boolean bold) {
        sb.append("<row r=\"").append(r).append("\">");
        for (int c = 0; c < cells.size(); c++) {
            Object v = cells.get(c);
            if (v == null) continue;
            String ref = col(c) + r;
            String style = bold ? " s=\"1\"" : "";
            if (v instanceof Number n && !bold) {
                sb.append("<c r=\"").append(ref).append('"').append(style).append("><v>").append(n).append("</v></c>");
            } else {
                sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(style)
                        .append("><is><t xml:space=\"preserve\">").append(esc(String.valueOf(v))).append("</t></is></c>");
            }
        }
        sb.append("</row>");
    }

    /** 0 → A，25 → Z，26 → AA。 */
    public static String col(int i) {
        StringBuilder s = new StringBuilder();
        for (int n = i + 1; n > 0; n = (n - 1) / 26) s.insert(0, (char) ('A' + (n - 1) % 26));
        return s.toString();
    }

    /** 工作表名：Excel 限 31 字符、不许 []:*?/\ 。 */
    private static String sheetName(String title) {
        String s = (title == null || title.isBlank() ? "Sheet1" : title).replaceAll("[\\[\\]:*?/\\\\]", " ");
        return s.length() > 31 ? s.substring(0, 31) : s;
    }

    /** XML 转义，并去掉 XML 1.0 不允许的控制字符（否则 Excel 报文件损坏）。 */
    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                default -> {
                    if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') b.append(ch);
                }
            }
        }
        return b.toString();
    }

    private static void put(ZipOutputStream zip, String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
