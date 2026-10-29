package ai.neargo.sharehub.common.export;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 表格 → PDF（运营方原始需求 K8 / RQ-RPT-03 / RQ-INV-06，2026-10-29）。
 *
 * <p><b>字体是配置</b>（{@code sharehub.export.pdf-font}，一个 TTF 路径）。不配时用 PDF 内置 Helvetica ——
 * 它只覆盖拉丁字符，中文 / 阿拉伯文会印成 {@code ?}。不配也能出文件，但第一次用到时打一条 WARN 说清楚，
 * 不让人对着一页问号猜原因。阿拉伯文即使配了字体也不做连写与右到左排版（PDFBox 不带 shaping），见上线清单。
 *
 * <p>版式：列多于 5 列用横向 A4；列宽按内容自然宽度分配、超宽等比压缩，放不下的单元格截断加 {@code ...}；
 * 数值右对齐；每页重复表头，页脚印页码。
 */
@Component
public class PdfTableWriter {

    private static final Logger log = LoggerFactory.getLogger(PdfTableWriter.class);
    private static final float MARGIN = 36;
    private static final float TITLE_SIZE = 14;
    private static final float META_SIZE = 9;
    private static final float CELL_SIZE = 8;
    private static final float ROW_H = 14;
    private static final float PAD = 3;

    private final String fontPath;
    private final AtomicBoolean warned = new AtomicBoolean();

    public PdfTableWriter(@Value("${sharehub.export.pdf-font:}") String fontPath) {
        this.fontPath = fontPath == null ? "" : fontPath.trim();
    }

    public byte[] write(ExportTable t) {
        try (PDDocument doc = new PDDocument()) {
            Fonts f = fonts(doc);
            boolean landscape = t.headers().size() > 5;
            PDRectangle size = landscape ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()) : PDRectangle.A4;
            float width = size.getWidth() - 2 * MARGIN;

            List<String> headers = t.headers().stream().map(h -> f.safe(h)).toList();
            List<List<String>> cells = new ArrayList<>();
            List<boolean[]> numeric = new ArrayList<>();
            for (List<Object> r : t.rows()) {
                List<String> row = new ArrayList<>();
                boolean[] num = new boolean[headers.size()];
                for (int c = 0; c < headers.size(); c++) {
                    Object v = c < r.size() ? r.get(c) : null;
                    num[c] = v instanceof Number;
                    row.add(v == null ? "" : f.safe(String.valueOf(v)));
                }
                cells.add(row);
                numeric.add(num);
            }
            float[] cols = widths(f.regular, headers, cells, width);
            // 数值列的表头也右对齐：数字贴列右缘、表头贴左缘时，看上去每个数都落在下一列的表头下
            boolean[] numCol = new boolean[headers.size()];
            for (boolean[] n : numeric) for (int c = 0; c < n.length; c++) numCol[c] |= n[c];

            Page p = new Page(doc, size, f);
            p.text(f.bold, TITLE_SIZE, MARGIN, p.y, f.safe(t.title()));
            p.y -= TITLE_SIZE + 6;
            for (String m : t.meta()) {
                p.text(f.regular, META_SIZE, MARGIN, p.y, fit(f.regular, META_SIZE, f.safe(m), width));
                p.y -= META_SIZE + 4;
            }
            p.y -= 6;
            p.header(headers, cols, numCol);
            for (int i = 0; i < cells.size(); i++) {
                if (p.y - ROW_H < MARGIN + 16) {
                    p = p.next();
                    p.header(headers, cols, numCol);
                }
                p.row(cells.get(i), numeric.get(i), cols, f.regular);
            }
            if (!t.footer().isEmpty()) {
                p.y -= 8;
                for (String line : t.footer()) {
                    if (p.y - META_SIZE < MARGIN + 16) p = p.next();
                    p.text(f.regular, META_SIZE, MARGIN, p.y, fit(f.regular, META_SIZE, f.safe(line), width));
                    p.y -= META_SIZE + 4;
                }
            }
            p.close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 自然宽度（表头与前 200 行取最大）→ 总宽超出就等比压缩，但每列至少放得下 4 个字符。 */
    private static float[] widths(PDFont font, List<String> headers, List<List<String>> rows, float total) {
        float[] w = new float[headers.size()];
        for (int c = 0; c < w.length; c++) {
            w[c] = width(font, CELL_SIZE, headers.get(c));
            for (int r = 0; r < Math.min(rows.size(), 200); r++) w[c] = Math.max(w[c], width(font, CELL_SIZE, rows.get(r).get(c)));
            w[c] = Math.min(w[c], 220) + 2 * PAD;
        }
        float sum = 0;
        for (float x : w) sum += x;
        float scale = total / sum;   // 窄了也拉满一行，表格不留半截空白
        for (int c = 0; c < w.length; c++) w[c] = Math.max(w[c] * scale, 4 * CELL_SIZE * 0.5f + 2 * PAD);
        return w;
    }

    private static float width(PDFont font, float size, String s) {
        try {
            return font.getStringWidth(s) / 1000 * size;
        } catch (IOException | IllegalArgumentException e) {
            return s.length() * size * 0.5f;
        }
    }

    private static String fit(PDFont font, float size, String s, float max) {
        if (width(font, size, s) <= max) return s;
        String cut = s;
        while (!cut.isEmpty() && width(font, size, cut + "...") > max) cut = cut.substring(0, cut.length() - 1);
        return cut + "...";
    }

    private Fonts fonts(PDDocument doc) throws IOException {
        if (!fontPath.isEmpty()) {
            File file = new File(fontPath);
            if (file.isFile()) {
                PDFont u = PDType0Font.load(doc, file);
                return new Fonts(u, u, false);
            }
            if (warned.compareAndSet(false, true)) {
                log.warn("PDF 字体文件不存在 sharehub.export.pdf-font={}，退回内置 Helvetica，非拉丁字符将印成 ?；请运维修正路径", fontPath);
            }
        } else if (warned.compareAndSet(false, true)) {
            log.warn("未配置 PDF 字体 sharehub.export.pdf-font，使用内置 Helvetica，中文/阿拉伯文将印成 ?；需要时请运维配一个 Unicode TTF 路径");
        }
        return new Fonts(new PDType1Font(Standard14Fonts.FontName.HELVETICA),
                new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), true);
    }

    /** 内置字体编不了的字符换成 ?（否则 showText 直接抛异常，整份导出失败）。 */
    private static final class Fonts {
        final PDFont regular;
        final PDFont bold;
        final boolean latinOnly;
        final Map<Integer, Boolean> ok = new HashMap<>();

        Fonts(PDFont regular, PDFont bold, boolean latinOnly) {
            this.regular = regular;
            this.bold = bold;
            this.latinOnly = latinOnly;
        }

        String safe(String s) {
            if (s == null) return "";
            String flat = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
            if (!latinOnly) return flat;
            StringBuilder b = new StringBuilder(flat.length());
            flat.codePoints().forEach(cp -> b.append(ok.computeIfAbsent(cp, this::encodable) ? Character.toString(cp) : "?"));
            return b.toString();
        }

        private boolean encodable(int cp) {
            try {
                regular.encode(Character.toString(cp));
                return true;
            } catch (IOException | IllegalArgumentException e) {
                return false;
            }
        }
    }

    /** 一页的绘制状态；{@link #next()} 收尾本页并开下一页。 */
    private static final class Page {
        final PDDocument doc;
        final PDRectangle size;
        final Fonts f;
        final int no;
        final PDPageContentStream cs;
        float y;

        Page(PDDocument doc, PDRectangle size, Fonts f) throws IOException {
            this(doc, size, f, 1);
        }

        private Page(PDDocument doc, PDRectangle size, Fonts f, int no) throws IOException {
            this.doc = doc;
            this.size = size;
            this.f = f;
            this.no = no;
            PDPage page = new PDPage(size);
            doc.addPage(page);
            this.cs = new PDPageContentStream(doc, page);
            this.y = size.getHeight() - MARGIN - TITLE_SIZE;
        }

        Page next() throws IOException {
            close();
            return new Page(doc, size, f, no + 1);
        }

        void text(PDFont font, float sz, float x, float atY, String s) throws IOException {
            cs.beginText();
            cs.setFont(font, sz);
            cs.newLineAtOffset(x, atY);
            cs.showText(s);
            cs.endText();
        }

        void header(List<String> headers, float[] cols, boolean[] numCol) throws IOException {
            float x = MARGIN;
            for (int c = 0; c < headers.size(); c++) {
                String h = fit(f.bold, CELL_SIZE, headers.get(c), cols[c] - 2 * PAD);
                float tx = numCol[c] ? x + cols[c] - PAD - width(f.bold, CELL_SIZE, h) : x + PAD;
                text(f.bold, CELL_SIZE, tx, y - ROW_H + 4, h);
                x += cols[c];
            }
            y -= ROW_H;
            cs.setLineWidth(0.6f);
            cs.moveTo(MARGIN, y);
            cs.lineTo(x, y);
            cs.stroke();
        }

        void row(List<String> cells, boolean[] numeric, float[] cols, PDFont font) throws IOException {
            float x = MARGIN;
            for (int c = 0; c < cells.size(); c++) {
                String s = fit(font, CELL_SIZE, cells.get(c), cols[c] - 2 * PAD);
                float tx = numeric[c] ? x + cols[c] - PAD - width(font, CELL_SIZE, s) : x + PAD;
                if (!s.isEmpty()) text(font, CELL_SIZE, tx, y - ROW_H + 4, s);
                x += cols[c];
            }
            y -= ROW_H;
            cs.setLineWidth(0.2f);
            cs.moveTo(MARGIN, y);
            cs.lineTo(x, y);
            cs.stroke();
        }

        void close() throws IOException {
            text(f.regular, 7, size.getWidth() - MARGIN - 40, MARGIN - 14, "Page " + no);
            cs.close();
        }
    }
}
