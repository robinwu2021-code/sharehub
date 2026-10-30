package ai.neargo.sharehub.common.export;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** 一个可下载文件。{@link #writeTo} 统一下载响应头（与付款任务导出同一套：attachment + no-store）。 */
public record ExportFile(String filename, String contentType, byte[] content) {

    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String PDF = "application/pdf";
    public static final String ZIP = "application/zip";

    public void writeTo(HttpServletResponse response) throws IOException {
        response.setContentType(contentType);
        // filename* 让带非 ASCII 的文件名（站点名）在浏览器里不乱码；filename 给老客户端兜底
        String ascii = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''"
                + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"));
        response.setHeader("Cache-Control", "no-store");
        response.setContentLength(content.length);
        response.getOutputStream().write(content);
    }
}
