package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C 端报障截图（2026-09-28）。
 *
 * <p>此前端上的图片选择器最多能选 3 张，而 {@code ReportReq} 根本没有附件字段、{@code /mp} 下也没有上传端点 ——
 * 照片选完只留在手机里，运营永远看不到。界面在暗示「我收到了你的照片」，这是**展示在说谎**：
 * 用户以为证据交上去了，于是不再去拍第二次、不再去柜机前复现。
 *
 * <p>本类钉住：C 端能传、传上去归到那张报障单、运营在详情里读得到；
 * 以及两条不能松的边 —— 别人的文件绑不上，传的不是图片直接拒。
 */
class ConsumerReportPhotoTest extends ApiTestSupport {

    /** 最小的合法 PNG（1×1，用真字节 —— 服务端会按魔数验类型，随便几个字节会被当成非图片）。 */
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Test
    @DisplayName("报障带截图：绑到那张单上，运营详情读得到")
    void photosReachTheTicketAndAreVisibleToOps() {
        String token = consumerToken();
        String fileNo = upload(PNG, "evidence.png", token).path("fileNo").asText();
        assertThat(fileNo).as("上传要回 fileNo").isNotBlank();

        Resp rr = post("/mp/user/report", Map.of(
                "issue", "宝弹不出来", "channel", "APP", "fileNos", List.of(fileNo)), token);
        assertThat(rr.code()).as("报障提交失败：%s", rr.msg()).isZero();
        String reportNo = rr.okData().path("reportNo").asText();

        JsonNode mine = get("/mp/user/reports/" + reportNo, token).okData();
        assertThat(photoNos(mine)).as("C 端自己也要看得见交了什么").containsExactly(fileNo);

        JsonNode ops = get("/api/ops/cs/tickets/" + reportNo, login("ADMIN")).okData();
        assertThat(photoNos(ops)).as("运营详情看得到截图 —— 看不到的话传了等于没传").containsExactly(fileNo);
    }

    @Test
    @DisplayName("不带截图照常受理 —— 附件是可选的")
    void reportWithoutPhotosStillWorks() {
        String token = consumerToken();
        String reportNo = post("/mp/user/report", Map.of("issue", "计费有疑问", "channel", "APP"), token)
                .okData().path("reportNo").asText();
        assertThat(get("/mp/user/reports/" + reportNo, token).okData().path("photos"))
                .as("没有附件时是空列表，不是缺字段").isEmpty();
    }

    @Test
    @DisplayName("别人的文件绑不上 —— 整单失败，而不是悄悄少一张图")
    void cannotAttachSomeoneElsesFile() {
        String other = upload(PNG, "his.png", consumerToken()).path("fileNo").asText();

        var resp = post("/mp/user/report", Map.of(
                "issue", "借的时候柜门没开", "channel", "APP", "fileNos", List.of(other)), consumerToken());
        assertThat(resp.code()).as("响应：%s", resp.msg()).isNotZero();
    }

    @Test
    @DisplayName("不是图片直接拒 —— 用途只收 JPEG/PNG")
    void nonImageIsRejected() {
        var resp = uploadResp("%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII), "x.pdf", consumerToken());
        assertThat(resp.code()).as("响应：%s", resp.msg()).isNotZero();
    }

    // —— 夹具 ——

    private static List<String> photoNos(JsonNode vo) {
        return vo.path("photos").findValuesAsText("fileNo");
    }

    private JsonNode upload(byte[] bytes, String name, String token) {
        Resp r = uploadResp(bytes, name, token);
        assertThat(r.code()).as("上传失败：%s", r.msg()).isZero();
        return r.okData();
    }

    private Resp uploadResp(byte[] bytes, String name, String token) {
        String boundary = "----sharehub" + java.util.UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(bytes);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mp/files"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        return sendRequest(req);
    }

    private String consumerToken() {
        String phone = "+9715" + (10000000 + new java.util.Random().nextInt(89999999));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        return post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
    }
}
