package ai.neargo.sharehub.portal.platform;

import ai.neargo.common.security.rbac.PermChecker;
import ai.neargo.sharehub.api.platform.dto.FileRef;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.platform.file.FileCategory;
import ai.neargo.sharehub.platform.file.service.FileService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 文件（方案-文件上传与COS存储；接口设计 §3.2）。运营端只有 6 个前缀，文件归 {@code /api/platform}。
 *
 * <p>上传不挂固定权限码：码随用途而变（签署件 → 合同编辑码，工单照片 → 工单处理码……），
 * 在方法内用与 {@code @perm.can} <b>同一个</b> {@link PermChecker} 判定 —— 不另写一套通配匹配。
 *
 * <p>浏览器的 {@code <img>} 带不了 Bearer 头，所以看图分两步：先 {@code GET …/url}（带头、鉴权）拿 5 分钟限时地址，
 * 再用地址直接取。个人数据不发地址，只能经 {@code GET /{fileNo}} 带头下载。
 */
@RestController
@RequestMapping("/api/platform/files")
public class FileController {

    private final FileService files;
    private final PermChecker perm;

    public FileController(FileService files, PermChecker perm) {
        this.files = files;
        this.perm = perm;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FileRef upload(@RequestParam("file") MultipartFile file, @RequestParam("category") String category) {
        FileCategory c = FileCategory.of(category);
        if (c.uploadPerm == null || !perm.can(c.uploadPerm)) {
            throw new AccessDeniedException("无权上传该用途的文件: " + c);
        }
        String agentNo = SecurityUtils.currentUser().map(LoginUser::agentNo).orElse(null);
        return files.upload(new FileService.Upload(c, file.getOriginalFilename(), file.getSize(), file::getInputStream,
                SecurityUtils.realm().name(), SecurityUtils.userNo(), agentNo));
    }

    @GetMapping("/{fileNo}/url")
    public FileService.SignedUrl url(@PathVariable String fileNo) {
        return files.signedUrl(fileNo);
    }

    @GetMapping("/{fileNo}")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String fileNo,
                                                          @RequestParam(defaultValue = "false") boolean inline) {
        return stream(files.openStream(fileNo), inline);
    }

    /** 本地存储的限时签名链接（生产走 COS 预签名，不经过这里）。SecurityConfig 放行，签名即授权。 */
    @GetMapping("/raw/{fileNo}")
    public ResponseEntity<StreamingResponseBody> raw(@PathVariable String fileNo, @RequestParam long exp, @RequestParam String sig) {
        return stream(files.openSigned(fileNo, exp, sig), true);
    }

    private static ResponseEntity<StreamingResponseBody> stream(FileService.Stream s, boolean inline) {
        boolean image = s.contentType() != null && s.contentType().startsWith("image/");
        String disposition = (inline && image ? "inline" : "attachment")
                + "; filename*=UTF-8''" + URLEncoder.encode(s.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        StreamingResponseBody body = out -> {
            try (InputStream in = s.in()) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(s.contentType()))
                .contentLength(s.size())
                .body(body);
    }
}
