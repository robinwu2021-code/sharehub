package ai.neargo.sharehub.portal.finance;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.common.export.ExportFile;
import ai.neargo.sharehub.common.export.ExportFormat;
import ai.neargo.sharehub.common.export.ExportTable;
import ai.neargo.sharehub.common.export.PdfTableWriter;
import ai.neargo.sharehub.portal.shared.ExportSupport;
import ai.neargo.sharehub.trade.order.entity.OrdTaxInvoice;
import ai.neargo.sharehub.trade.order.service.TaxInvoiceService;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 租赁税务票据的文件形态（K8 / RQ-INV-06~08，2026-10-29）：单张 PDF、登记表 Excel/PDF、批量 PDF 打包 zip。
 *
 * <p><b>单张票面固定英文</b>，不随界面语言变：这是给税务看的法律文件（UAE FTA 要求票面写明 "Tax Invoice"、
 * 供方名称地址与 TRN、税率与税额）。登记表是内部管理用，表头随请求语言。
 */
@Component
public class TaxInvoiceDocuments {

    /** 批量打包上限：PDF 一张约几 KB，500 张一个请求内能完成。 */
    public static final int MAX_ZIP = 500;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final PdfTableWriter pdf;
    private final ExportSupport exports;

    public TaxInvoiceDocuments(PdfTableWriter pdf, ExportSupport exports) {
        this.pdf = pdf;
        this.exports = exports;
    }

    /** 单张票 PDF。BLOCKED 的票没有开出，不能给出一份看起来像发票的文件。 */
    public ExportFile pdf(OrdTaxInvoice t) {
        if (!TaxInvoiceService.ISSUED.equals(t.getStatus())) throw BizException.conflict("error.invoice.not_issued", t.getInvoiceNo());
        return new ExportFile(t.getInvoiceNo() + ".pdf", ExportFile.PDF, pdf.write(sheet(t)));
    }

    static ExportTable sheet(OrdTaxInvoice t) {
        boolean credit = "CREDIT_NOTE".equals(t.getKind());
        List<String> meta = new ArrayList<>();
        meta.add((credit ? "Credit note no.: " : "Invoice no.: ") + t.getInvoiceNo()
                + "    Date of issue: " + (t.getIssuedAt() == null ? "-" : t.getIssuedAt().format(DAY)));
        if (credit) meta.add("Credits invoice: " + nz(t.getRefInvoiceNo()));
        meta.add("Supplier: " + nz(t.getIssuerLegalName()));
        meta.add("Address: " + nz(t.getIssuerAddress()));
        meta.add("TRN: " + nz(t.getIssuerTrn()));
        meta.add("Order no.: " + t.getOrderNo() + (t.getPayNo() == null ? "" : "    Payment ref.: " + t.getPayNo())
                + (t.getRefundNo() == null ? "" : "    Refund ref.: " + t.getRefundNo()));
        String cur = t.getCurrency();
        List<List<Object>> rows = List.of(Arrays.asList(nz(t.getLineDesc()), t.getNetAmount(), t.getTaxRatePercent(),
                t.getTaxAmount(), t.getGrossAmount()));
        List<String> footer = List.of(
                "Total excl. VAT: " + money(t.getNetAmount(), cur),
                "VAT " + t.getTaxRatePercent().stripTrailingZeros().toPlainString() + "%: " + money(t.getTaxAmount(), cur),
                "Total incl. VAT: " + money(t.getGrossAmount(), cur),
                Integer.valueOf(0).equals(t.getTaxIncluded()) ? "VAT charged on top of the listed price." : "Prices include VAT.");
        return new ExportTable(credit ? "TAX CREDIT NOTE" : "TAX INVOICE", meta,
                List.of("Description", "Net (" + cur + ")", "VAT %", "VAT (" + cur + ")", "Total (" + cur + ")"), rows, footer);
    }

    /** 登记表（RQ-INV-08）：一行一张票，含 BLOCKED（登记表要看得到哪些没开出来）。 */
    public ExportFile register(ExportFormat fmt, List<OrdTaxInvoice> list, boolean truncated, String filter) {
        List<List<Object>> rows = list.stream().map(t -> Arrays.<Object>asList(t.getInvoiceNo(), t.getKind(),
                t.getRefInvoiceNo(), t.getOrderNo(), t.getIssuerLegalName(), t.getIssuerTrn(), t.getTaxRatePercent(),
                t.getNetAmount(), t.getTaxAmount(), t.getGrossAmount(), t.getCurrency(), t.getStatus(),
                t.getIssuedAt() == null ? t.getFailReason() : t.getIssuedAt().toString())).toList();
        return exports.render(fmt, "export.tax_invoice.register", filter, List.of("export.col.invoice_no", "export.col.kind",
                        "export.col.ref_invoice", "export.col.order_no", "export.col.issuer", "export.col.trn",
                        "export.col.rate", "export.col.net_amount", "export.col.tax_amount", "export.col.gross_amount",
                        "export.col.currency", "export.col.status", "export.col.issued_at"),
                rows, truncated, "tax-invoice-register");
    }

    /** 批量下载（RQ-INV-07）：只打包已开出的票，一张一个 PDF。 */
    public ExportFile zip(List<OrdTaxInvoice> list, String fileBase) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (OrdTaxInvoice t : list) {
                if (!TaxInvoiceService.ISSUED.equals(t.getStatus())) continue;
                zip.putNextEntry(new ZipEntry(t.getInvoiceNo() + ".pdf"));
                zip.write(pdf.write(sheet(t)));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ExportFile(fileBase + ".zip", ExportFile.ZIP, bytes.toByteArray());
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String money(BigDecimal v, String cur) {
        return (v == null ? "0.00" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()) + " " + cur;
    }
}
