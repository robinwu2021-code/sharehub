package ai.neargo.sharehub.api.platform.dto;

import java.time.LocalDateTime;

/**
 * 文件引用（跨模块只传它，不传存储位置）。
 *
 * <p>没有下载地址字段：地址是**限时**的，放进引用里会被缓存、转存，过期后变成一个打不开的链接。
 * 要看文件时按 {@code fileNo} 现取（{@code GET /api/platform/files/{fileNo}/url}）。
 */
public record FileRef(String fileNo, String category, String status, String originalName, String contentType,
                      long sizeBytes, boolean previewable, Integer imageWidth, Integer imageHeight,
                      LocalDateTime uploadedAt) {
}
