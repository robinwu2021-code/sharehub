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
    @DisplayName("图能取回来；别人的取不到；列表带张数")
    void ownerCanFetchTheImageAndNobodyElseCan() {
        String token = consumerToken();
        String fileNo = upload(PNG, "evidence.png", token).path("fileNo").asText();
        String reportNo = post("/mp/user/report", Map.of(
                "issue", "柜门卡住", "channel", "APP", "fileNos", List.of(fileNo)), token)
                .okData().path("reportNo").asText();

        assertThat(rawGet("/mp/files/" + fileNo, token))
                .as("自己交的图要能取回来 —— 取不回来的话「已附 1 张」又是一句空话").isEqualTo(200);
        assertThat(rawGet("/mp/files/" + fileNo, consumerToken()))
                .as("别人的图取不到（与不存在同一结果）").isNotEqualTo(200);
        assertThat(rawGet("/mp/files/" + fileNo, null)).as("不带令牌取不到").isNotEqualTo(200);

        assertThat(rawGet("/api/platform/files/" + fileNo, login("ADMIN")))
                .as("运营也要取得到 —— 详情里列出文件名却下载不了，等于还是看不见").isEqualTo(200);

        JsonNode row = firstRow(get("/mp/user/reports", token).okData(), reportNo);
        assertThat(row.path("photoCount").asInt())
                .as("列表要带张数 —— 列表不查图本身（N+1），但张数得有，否则界面无从显示").isEqualTo(1);
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

    private static JsonNode firstRow(JsonNode page, String ticketNo) {
        for (JsonNode n : page.path("list")) {
            if (ticketNo.equals(n.path("ticketNo").asText())) return n;
        }
        throw new AssertionError("列表里没有这张单：" + ticketNo);
    }

    /**
     * 直接看 HTTP 状态：这条路返回的是图片字节，不是 Result 信封。
     *
     * <p>两处讲究：①把字节读完（ofByteArray）而不是 discarding；②**单开一个 HttpClient**。
     * 这条响应是异步流式的，Tomcat 之后会关掉连接，而共享的 HttpClient 会把那条已关的连接
     * 复用给下一个请求 —— POST 不会自动重试，于是下一句 `POST /mp/auth/otp` 报
     * 「header parser received no bytes」。症状落在**下一条用例**上，跟这里对不上因果。
     * （服务端本身没问题：curl 同一连接连发「取图 / 取图 / 普通接口」三次全 200。）
     */
    private int rawGet(String path, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        try (java.net.http.HttpClient one = java.net.http.HttpClient.newHttpClient()) {
            var resp = one.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200) {
                assertThat(resp.body()).as("200 就得真有图片字节").isNotEmpty();
            }
            return resp.statusCode();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

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
