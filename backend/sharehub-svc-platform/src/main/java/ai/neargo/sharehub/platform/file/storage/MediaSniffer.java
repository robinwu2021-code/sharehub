package ai.neargo.sharehub.platform.file.storage;

import ai.neargo.sharehub.platform.file.MediaKind;

/**
 * 按文件头判定类型。扩展名与客户端声明的 Content-Type 都只作参考 ——
 * 一个改名成 .pdf 的脚本，扩展名说它是 PDF，文件头不会。
 * 只认这四种；SVG / HTML 这类能带脚本的格式天然不在其内（预览即 XSS）。
 */
public final class MediaSniffer {

    private MediaSniffer() {
    }

    public static MediaKind sniff(byte[] head, int n) {
        if (n >= 5 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F' && head[4] == '-') return MediaKind.PDF;
        if (n >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) return MediaKind.JPEG;
        if (n >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A) return MediaKind.PNG;
        if (n >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') return MediaKind.WEBP;
        return null;
    }

    /** 扩展名与嗅探结果是否相容（无扩展名视为相容；jpg / jpeg 同义）。 */
    public static boolean extCompatible(String originalName, MediaKind kind) {
        if (originalName == null) return true;
        int dot = originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) return true;
        String ext = originalName.substring(dot + 1).toLowerCase();
        return switch (kind) {
            case JPEG -> ext.equals("jpg") || ext.equals("jpeg");
            default -> ext.equals(kind.ext);
        };
    }
}
