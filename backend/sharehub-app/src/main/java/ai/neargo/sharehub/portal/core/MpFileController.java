package ai.neargo.sharehub.portal.core;

import ai.neargo.sharehub.api.platform.dto.FileRef;
import ai.neargo.sharehub.auth.ConsumerContext;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.platform.file.FileCategory;
import ai.neargo.sharehub.platform.file.service.FileService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;

/**
 * C 端上传（{@code /mp/files}）—— 目前只有报障截图一种用途。
 *
 * <p>{@link FileCategory} 早就留了 {@code CS_EVIDENCE}（PII 桶、5MB、只收 JPEG/PNG、存前抹掉 EXIF），
 * 注释里也写着「C 端截图走 /mp」，但这个端点一直没建：于是报障页的图片选择器选完只留在手机上，
 * 传不出去，运营也永远看不到 —— 界面在暗示「我收到了你的照片」（2026-09-28 真机实测）。
 *
 * <p><b>不做通用上传</b>：category 在服务端钉死，不从请求里取。C 端能传什么由这里说了算，
 * 否则「传什么、多大、放哪个桶」就由调用方决定了，而调用方是公网上的 App。
 *
 * <p>上传只是把文件收下（状态 TEMP）；真正归属某张报障单是提交报障时 {@code fileNos} 带上去绑定
 * （见 {@code CsTicketServiceImpl#report}）。没绑上的临时文件由 {@code file-temp-purge} 清掉。
 */
@RestController
@RequestMapping("/mp/files")
public class MpFileController {

    private final FileService files;

    public MpFileController(FileService files) {
        this.files = files;
    }

    /**
     * 取回一张图（自己那张单上的）。
     *
     * <p>**不发限时地址**：报障截图是 PII 桶，{@code signedUrl} 对它一律拒绝 ——
     * 一个能直接打开的链接一旦被转发/进日志就等于泄露。这里每次都要带 token，
     * 归属由 {@code CsTicketFileAccessChecker} 判（只认自己那张单）。
     *
     * <p>端上用 {@code uni.downloadFile}（它能带 header）落成临时文件再显示，
     * 因为 {@code <image src>} 发不了 Authorization 头。
     */
    @GetMapping("/{fileNo}")
    public ResponseEntity<StreamingResponseBody> get(@PathVariable String fileNo) {
        ConsumerContext.require();
        FileService.Stream s = files.openStream(fileNo);
        StreamingResponseBody body = out -> {
            try (InputStream in = s.in()) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(s.contentType()))
                .contentLength(s.size())
                .body(body);
    }

    /** 报障截图。返回 fileNo，提交报障时带回来。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FileRef uploadEvidence(@RequestParam("file") MultipartFile file) {
        LoginUser me = ConsumerContext.require();
        return files.upload(new FileService.Upload(FileCategory.CS_EVIDENCE,
                file.getOriginalFilename(), file.getSize(), file::getInputStream,
                me.realm().name(), me.userNo(), null));
    }
}
