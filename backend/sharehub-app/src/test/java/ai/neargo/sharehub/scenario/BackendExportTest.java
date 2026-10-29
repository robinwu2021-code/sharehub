package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.common.export.ExportTable;
import ai.neargo.sharehub.common.export.PdfTableWriter;
import ai.neargo.sharehub.common.export.XlsxWriter;
import ai.neargo.sharehub.support.ApiTestSupport;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 后端导出 Excel / PDF（运营方原始需求 K8 / RQ-RPT-03 / RQ-INV-06~08 / AC-09，第 4 批）。
 *
 * <p>导出是文件下载，不走统一响应信封，这里自己发请求读字节。
 */
class BackendExportTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<byte[]> download(String path, String token) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** xlsx 里 sheet1.xml 的原文 —— 断言落在表格内容上，而不是「是个 zip」。 */
    private static String sheetXml(byte[] xlsx) throws Exception {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.getName().equals("xl/worksheets/sheet1.xml")) return new String(z.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("xlsx 里没有 sheet1");
    }

    private static String pdfText(byte[] pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(d);
        }
    }

    @Test
    @DisplayName("xlsx：表头粗体、数字写成数值格（能求和）、XML 特殊字符转义、列号过 Z 进位")
    void xlsx_writer() throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(Arrays.asList("A&B <站点>", new BigDecimal("12.50"), 3));
        String xml = sheetXml(XlsxWriter.write(new ExportTable("报表", List.of("说明"), List.of("名称", "金额", "单数"), rows, null)));
        assertThat(xml).contains("A&amp;B &lt;站点&gt;").contains("<v>12.50</v>").contains("<v>3</v>").contains(" s=\"1\"");
        assertThat(XlsxWriter.col(0)).isEqualTo("A");
        assertThat(XlsxWriter.col(26)).isEqualTo("AA");
    }

    @Test
    @DisplayName("PDF：没配 Unicode 字体时中文退成 ?，不抛异常；多页表格每页都有表头")
    void pdf_writer_falls_back_without_font() throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < 120; i++) rows.add(Arrays.asList("Site " + i + " 迪拜", new BigDecimal(i), i));
        byte[] pdf = new PdfTableWriter("").write(new ExportTable("Report 报表", List.of("Filter: x"),
                List.of("Name", "Amount", "Orders"), rows, List.of("120 rows")));
        try (PDDocument d = Loader.loadPDF(pdf)) {
            assertThat(d.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(d);
            assertThat(text).contains("Report ??").contains("Site 119").contains("120 rows");
            PDFTextStripper last = new PDFTextStripper();
            last.setStartPage(d.getNumberOfPages());
            assertThat(last.getText(d)).as("翻页后重复表头").contains("Amount");
        }
    }

    @Test
    @DisplayName("★ 报表导出：全量、xlsx / pdf 两种；格式不认识 400；OPS 导不出财务报表 403")
    void report_export() throws Exception {
        String admin = login("ADMIN");
        HttpResponse<byte[]> x = download("/api/ops/reports/finance/export?format=xlsx", admin);
        assertThat(x.statusCode()).isEqualTo(200);
        assertThat(x.headers().firstValue("Content-Type").orElse("")).contains("spreadsheetml");
        assertThat(x.headers().firstValue("Content-Disposition").orElse("")).contains("finance-report-");
        assertThat(sheetXml(x.body())).contains("GMV");

        HttpResponse<byte[]> p = download("/api/ops/reports/device/export?format=pdf", admin);
        assertThat(p.statusCode()).isEqualTo(200);
        assertThat(new String(p.body(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        assertThat(download("/api/ops/reports/device/export?format=docx", admin).statusCode()).isEqualTo(400);
        assertThat(download("/api/ops/reports/finance/export", login("OPS")).statusCode()).isEqualTo(403);
        assertThat(download("/api/ops/reports/device/export", login("OPS")).statusCode()).isEqualTo(200);
    }

    private String seedInvoice(String no, String status, String orderNo) {
        jdbc.update("INSERT INTO ord_tax_invoice (invoice_no, tenant_id, kind, source_key, order_no, issuer_country, "
                        + "issuer_legal_name, issuer_address, issuer_trn, tax_rate_percent, tax_included, net_amount, tax_amount, "
                        + "gross_amount, currency, line_desc, status, fail_reason, issued_at) "
                        + "VALUES (?, 'MAIN', 'INVOICE', ?, ?, 'AE', 'Junverse Technology LLC', 'Office 1, Dubai', '100000000000003', "
                        + "5.00, 1, 65.71, 3.29, 69.00, 'AED', 'Power bank rental', ?, ?, ?)",
                no, "TEST:" + no, orderNo, status, "BLOCKED".equals(status) ? "ISSUER_MISSING:trn" : null,
                "BLOCKED".equals(status) ? null : java.time.LocalDateTime.now());
        return no;
    }

    @Test
    @DisplayName("★★ 税务发票：单张 PDF 印主体/TRN/税额；BLOCKED 不给 PDF；登记表含 BLOCKED；批量 zip 只装已开出的")
    void tax_invoice_documents() throws Exception {
        String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
        String order = "ORDEXP" + tag;
        String issued = seedInvoice("INV-EXP-" + tag + "-1", "ISSUED", order);
        String blocked = seedInvoice("INV-EXP-" + tag + "-2", "BLOCKED", order);
        String admin = login("ADMIN");

        HttpResponse<byte[]> one = download("/api/trade/tax-invoices/" + issued + "/pdf", admin);
        assertThat(one.statusCode()).isEqualTo(200);
        assertThat(pdfText(one.body())).contains("TAX INVOICE").contains(issued).contains("Junverse Technology LLC")
                .contains("100000000000003").contains("3.29").contains("69.00");
        assertThat(download("/api/trade/tax-invoices/" + blocked + "/pdf", admin).statusCode()).isEqualTo(409);

        String reg = sheetXml(download("/api/trade/tax-invoices/export?format=xlsx&orderNo=" + order, admin).body());
        assertThat(reg).contains(issued).contains(blocked).contains("ISSUER_MISSING:trn");

        HttpResponse<byte[]> zip = download("/api/trade/tax-invoices/export?format=zip&orderNo=" + order, admin);
        assertThat(zip.statusCode()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip.body()))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) names.add(e.getName());
        }
        assertThat(names).containsExactly(issued + ".pdf");

        assertThat(download("/api/trade/tax-invoices/export?format=xlsx&from=2026-13-01", admin).statusCode()).isEqualTo(400);
    }
}
