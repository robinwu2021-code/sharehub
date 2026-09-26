package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字典项的「分组」读出来叫 {@code group}、写进去要 {@code groupCode} —— 读写不同名。
 *
 * <p>读出参 {@code DictEntry} 把 {@code group_code} 改名成 {@code group}
 * （实体字段注释原话：「前端 VO 里叫 group」），而写入面此前直接收实体 {@code DictItem}，
 * 要的是 {@code groupCode}。运营在「分组」那一格填什么都存不进去：
 * 新建的字典项 {@code group_code} 为 null，列表里那一列空着，<b>全程 200</b>。
 *
 * <p>这是 `check-form-fields` 给 system 页十张表单挂上端点后当场露出来的三条之一 ——
 * 靠人眼比对 `DICT_FIELDS` 与 `DictItem` 很难发现，因为两边各自都自洽。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DictGroupRoundTripTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    final List<String> dictNos = new ArrayList<>();

    @AfterAll
    void cleanup() {
        for (String n : dictNos) jdbc.update("DELETE FROM dict_item WHERE dict_no=?", n);
    }

    @Test
    @DisplayName("新建字典项：分组存得进去，读回来还是它")
    void groupSurvivesCreate() {
        String token = login("ADMIN");
        String group = "grp_" + rnd();

        JsonNode created = post("/api/platform/dict-entries",
                Map.of("group", group, "code", "PAID", "label", "已支付", "sort", 3, "enabled", true),
                token).okData();
        String dictNo = created.path("dictNo").asText();
        dictNos.add(dictNo);

        assertThat(created.path("group").asText())
                .as("出参就该带回分组 —— 此前这里是空的，而 HTTP 仍然 200")
                .isEqualTo(group);
        assertThat(jdbc.queryForObject("SELECT group_code FROM dict_item WHERE dict_no=?", String.class, dictNo))
                .as("落库的列是 group_code；读写不同名时最容易在这一步丢")
                .isEqualTo(group);
    }

    @Test
    @DisplayName("编辑字典项：改分组真的改到了；路径上的单号为准")
    void groupSurvivesUpdate() {
        String token = login("ADMIN");
        String dictNo = post("/api/platform/dict-entries",
                Map.of("group", "grp_old_" + rnd(), "code", "NEW", "label", "新建", "sort", 1, "enabled", true),
                token).okData().path("dictNo").asText();
        dictNos.add(dictNo);

        String moved = "grp_new_" + rnd();
        // 故意在 body 里塞一个别的单号：写入面必须以路径为准，否则能改到别人那行
        JsonNode updated = post("/api/platform/dict-entries/" + dictNo,
                Map.of("dictNo", "DICT-SOMEONE-ELSE", "group", moved, "code", "NEW", "label", "新建",
                        "sort", 1, "enabled", true),
                token).okData();

        assertThat(updated.path("group").asText()).isEqualTo(moved);
        assertThat(jdbc.queryForObject("SELECT group_code FROM dict_item WHERE dict_no=?", String.class, dictNo))
                .isEqualTo(moved);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dict_item WHERE dict_no=?", Integer.class,
                "DICT-SOMEONE-ELSE")).as("body 里的单号不该被当真").isZero();
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 6).toLowerCase();
    }
}
