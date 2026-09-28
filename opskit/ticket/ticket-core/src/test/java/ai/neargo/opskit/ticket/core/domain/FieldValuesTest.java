package ai.neargo.opskit.ticket.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.ticket.api.definition.AssignmentDef;
import ai.neargo.opskit.ticket.api.definition.FieldDef;
import ai.neargo.opskit.ticket.api.definition.FieldType;
import ai.neargo.opskit.ticket.api.definition.OptionDef;
import ai.neargo.opskit.ticket.api.definition.Options;
import ai.neargo.opskit.ticket.api.definition.TicketTypeDef;
import ai.neargo.opskit.ticket.core.domain.FieldValues.IndexValue;
import ai.neargo.opskit.ticket.core.domain.FieldValues.Mode;
import ai.neargo.opskit.ticket.core.domain.FieldValues.Result;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O3-2：11 种字段类型各一条合法 / 非法 · 必填 / 建单可填 · 选项停用不追溯 · 索引行 · 范围标签 · 去重键。 */
class FieldValuesTest {

    static LocalizedText t(String s) {
        return LocalizedText.of("zh", s);
    }

    static FieldDef f(String code, FieldType type) {
        return new FieldDef(code, t(code), type, false, true, true, type.hasOptions() ? Options.of(OPTS) : null, false, null);
    }

    static final List<OptionDef> OPTS = List.of(new OptionDef("A", t("甲"), true), new OptionDef("B", t("乙"), true), new OptionDef("OLD", t("旧"), false));

    static TicketTypeDef type(List<FieldDef> fields) {
        return new TicketTypeDef("repair", t("报修"), "desk", fields, null, null, null, AssignmentDef.MANUAL, List.of(),
                List.of(new TicketTypeDef.ScopeFrom("site", "site")), new TicketTypeDef.Dedupe(List.of("device")), null);
    }

    static Result ok(FieldDef field, Object value) {
        List<FieldError> errors = new ArrayList<>();
        Result r = FieldValues.normalize(type(List.of(field)), x -> OPTS, Map.of(field.code(), value), Mode.CREATE, Map.of(), errors);
        assertThat(errors).as("合法值不应报错").isEmpty();
        return r;
    }

    static List<FieldError> bad(FieldDef field, Object value) {
        List<FieldError> errors = new ArrayList<>();
        Map<String, Object> input = new HashMap<>();
        input.put(field.code(), value);
        FieldValues.normalize(type(List.of(field)), x -> OPTS, input, Mode.CREATE, Map.of(), errors);
        return errors;
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of(FieldType.TEXT, "屏幕碎了", 42),
                Arguments.of(FieldType.LONG_TEXT, "详细描述", List.of("x")),
                Arguments.of(FieldType.NUMBER, 12.5, "12.5"),
                Arguments.of(FieldType.MONEY, Map.of("amount", 1999, "currency", "CNY"), Map.of("amount", 19.99, "currency", "CNY")),
                Arguments.of(FieldType.DATETIME, 1790557031000L, "2026-09-28"),
                Arguments.of(FieldType.SELECT, "A", "Z"),
                Arguments.of(FieldType.MULTI_SELECT, List.of("A", "B"), List.of("A", "Z")),
                Arguments.of(FieldType.MEMBER, "mb_1", "group:x"),
                Arguments.of(FieldType.GROUP, "ops", "group:ops"),
                Arguments.of(FieldType.ATTACHMENT, List.of(Map.of("id", "f1", "name", "照片.jpg")), List.of(Map.of("name", "缺 id"))),
                Arguments.of(FieldType.REF, Map.of("type", "device", "ref", "D-1"), Map.of("type", "device")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void eachTypeAcceptsItsShapeAndRejectsOthers(FieldType type, Object good, Object wrong) {
        FieldDef field = f("x", type);
        assertThat(ok(field, good).values()).containsKey("x");
        assertThat(bad(field, wrong)).extracting(FieldError::path).containsExactly("fields.x");
    }

    @Test
    void normalizedShapes() {
        assertThat(ok(f("m", FieldType.MEMBER), "mb_1").values()).containsEntry("m", "member:mb_1");
        assertThat(ok(f("n", FieldType.NUMBER), 3).values().get("n")).isEqualTo(new BigDecimal("3"));
        assertThat(ok(f("tx", FieldType.TEXT), "a".repeat(255)).values()).containsKey("tx");
        assertThat(bad(f("tx", FieldType.TEXT), "a".repeat(256))).extracting(FieldError::error).containsExactly("opskit.validation.too_long");
        assertThat(bad(f("s", FieldType.SELECT), "OLD")).as("新选停用的选项").extracting(FieldError::error).containsExactly("opskit.ticket.option_disabled");
    }

    @Test
    void requiredOnCreateAndUnknownFields() {
        FieldDef required = new FieldDef("device", t("设备"), FieldType.TEXT, true, true, true, null, false, null);
        FieldDef later = new FieldDef("rootCause", t("根因"), FieldType.TEXT, false, false, false, null, false, null);
        TicketTypeDef ty = type(List.of(required, later));
        List<FieldError> e = new ArrayList<>();
        FieldValues.normalize(ty, x -> OPTS, Map.of("rootCause", "x", "nope", 1), Mode.CREATE, Map.of(), e);
        assertThat(e).extracting(FieldError::path).containsExactlyInAnyOrder("fields.nope", "fields.device", "fields.rootCause");

        List<FieldError> e2 = new ArrayList<>();
        Map<String, Object> clear = new HashMap<>();
        clear.put("device", null);
        FieldValues.normalize(ty, x -> OPTS, clear, Mode.UPDATE, Map.of("device", "D-1"), e2);
        assertThat(e2).as("必填字段不能在编辑时清空").extracting(FieldError::error).containsExactly("opskit.validation.required");
    }

    @Test
    void aDisabledOptionAlreadyChosenSurvivesAnUnrelatedEdit() {
        FieldDef s = f("s", FieldType.SELECT);
        List<FieldError> e = new ArrayList<>();
        Result r = FieldValues.normalize(type(List.of(s)), x -> OPTS, Map.of("s", "OLD"), Mode.UPDATE, Map.of("s", "OLD"), e);
        assertThat(e).isEmpty();
        assertThat(r.values()).containsEntry("s", "OLD");
    }

    @Test
    void indexScopesAndDedupeKey() {
        FieldDef site = f("site", FieldType.SELECT);
        FieldDef device = new FieldDef("device", t("设备"), FieldType.REF, true, true, true, null, false, "device");
        FieldDef tags = f("tags", FieldType.MULTI_SELECT);
        FieldDef amount = f("amount", FieldType.MONEY);
        List<FieldError> e = new ArrayList<>();
        Result r = FieldValues.normalize(type(List.of(site, device, tags, amount)), x -> OPTS,
                Map.of("site", "A", "device", Map.of("type", "device", "ref", "D-9"), "tags", List.of("A", "B"),
                        "amount", Map.of("amount", 500, "currency", "USD")), Mode.CREATE, Map.of(), e);
        assertThat(e).isEmpty();
        assertThat(r.index()).containsExactlyInAnyOrder(new IndexValue("site", "A", null, null), new IndexValue("device", "device:D-9", null, null),
                new IndexValue("tags", "A", null, null), new IndexValue("tags", "B", null, null),
                new IndexValue("amount", "USD", new BigDecimal("500"), null));
        assertThat(r.scopes()).containsExactly("site:A");
        assertThat(r.dedupeKey()).isEqualTo("repair|device:D-9");

        List<FieldError> e2 = new ArrayList<>();
        assertThat(FieldValues.normalize(type(List.of(site, device)), x -> OPTS, Map.of("site", "A", "device", Map.of("type", "other", "ref", "X")),
                Mode.CREATE, Map.of(), e2)).isNull();
        assertThat(e2).extracting(FieldError::error).containsExactly("opskit.ticket.ref_type_mismatch");
    }
}
