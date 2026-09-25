package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.dev.dto.DevLegacyDtos.CabinetDetail;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Slot;
import ai.neargo.sharehub.dev.service.CabinetService;
import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.FixtureCleanup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 机柜仓位图 —— 此前每一格都是算出来的，不是读出来的。
 *
 * <p>原 {@code slotsOf} 只拿 {@code slotTotal} 与 {@code availableCount} 拼：
 * 前 N 个仓算有宝、宝号 {@code "PB" + cabinetNo.substring(3) + (i+1)} 编一个、
 * 电量 {@code 40 + (i*13)%60} 是个公式、锁状态等于「有没有宝」（完全不看 {@code dev_protection}）。
 * 那个公式产出 40/53/66/79/92/45… <b>看着特别像真的电量</b>，
 * 于是运维照着排障，看到的宝号库里不存在、电量是算的、锁着的仓显示没锁 —— 页面不报错。
 *
 * <p>本类刻意把夹具造成「与那套公式不一致」：宝只放在第 3、5 仓（不是前 N 个），
 * 电量取 77（公式在任何下标都给不出 77），第 2 仓上一条 SLOT_LOCK。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CabinetSlotMapTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CabinetService cabinets;

    final List<String> cabs = new ArrayList<>();
    final List<String> banks = new ArrayList<>();

    @AfterAll
    void cleanup() {
        for (String b : banks) jdbc.update("DELETE FROM dev_powerbank WHERE powerbank_no=?", b);
        FixtureCleanup.dropCabinets(jdbc, cabs);
    }

    @Test
    @DisplayName("仓位图读 dev_powerbank：宝在第几仓就画在第几仓，电量取真值")
    void slotsComeFromRealRows() {
        String cab = cabinet(6);
        String third = bank(cab, 3, 77, "OK");
        String fifth = bank(cab, 5, 12, "AGED");

        List<Slot> slots = slotsOf(cab);
        assertThat(slots).as("6 仓就出 6 格").hasSize(6);

        assertThat(at(slots, 3).powerbankNo()).as("第 3 仓是真实宝号，不是编出来的").isEqualTo(third);
        assertThat(at(slots, 3).battery()).as("电量取真值 —— 原实现给的是 40+(i*13)%%60，永远出不了 77").isEqualTo(77);
        assertThat(at(slots, 5).powerbankNo()).isEqualTo(fifth);
        assertThat(at(slots, 5).health()).as("健康度取真值，不是「只有机柜 FAULT 时最后一仓 FAULT」").isEqualTo("AGED");

        for (int i : new int[]{1, 2, 4, 6}) {
            assertThat(at(slots, i).powerbankNo())
                    .as("第 %d 仓没宝就该是空的（原实现会把前 availableCount 个仓都画成有宝）", i)
                    .isNull();
            assertThat(at(slots, i).battery()).as("空仓没有电量可言，不该是 0").isNull();
        }
    }

    @Test
    @DisplayName("锁状态读 dev_protection：锁的是仓，不是「有没有宝」")
    void lockStatusComesFromProtection() {
        String cab = cabinet(4);
        bank(cab, 1, 90, "OK");
        jdbc.update("INSERT INTO dev_protection (protection_no, tenant_id, cabinet_no, slot_index, action,"
                + " holder_type, holder_ref, reason, active) VALUES (?, 'MAIN', ?, 2, 'SLOT_LOCK',"
                + " 'MANUAL', 'TEST', '仓位图测试锁仓', 1)", "PRTSL" + rnd(), cab);

        List<Slot> slots = slotsOf(cab);
        assertThat(at(slots, 2).lockStatus())
                .as("第 2 仓挂着 SLOT_LOCK，原实现只看有没有宝，会把它显示成 UNLOCKED")
                .isEqualTo("LOCKED");
        assertThat(at(slots, 1).lockStatus())
                .as("有宝待借不等于仓锁着 —— 原实现把这两件事混成一件")
                .isEqualTo("UNLOCKED");
        assertThat(at(slots, 3).lockStatus()).isEqualTo("UNLOCKED");
    }

    // —— 夹具 ——

    private List<Slot> slotsOf(String cabinetNo) {
        CabinetDetail d = cabinets.detail(cabinetNo);
        return d.slots();
    }

    private static Slot at(List<Slot> slots, int slotIndex) {
        return slots.stream().filter(s -> s.slotIndex() == slotIndex).findFirst()
                .orElseThrow(() -> new AssertionError("没有第 " + slotIndex + " 仓"));
    }

    private String cabinet(int slotTotal) {
        String cab = "CBSL" + rnd();
        cabs.add(cab);
        // availableCount 故意填 0：原实现靠它决定「前几个仓有宝」，填 0 时会把每一格都画成空
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, slot_total,"
                + " available_count, status, online_status, last_heartbeat_at)"
                + " VALUES (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, 0, 'DEPLOYED', 'ONLINE', NOW(3))",
                cab, cab, slotTotal);
        return cab;
    }

    private String bank(String cabinetNo, int slotIndex, int battery, String health) {
        String no = "PBSL" + rnd();
        banks.add(no);
        jdbc.update("INSERT INTO dev_powerbank (powerbank_no, tenant_id, sn, vendor_code, battery, health,"
                        + " status, cabinet_no, slot_index) VALUES (?, 'MAIN', ?, 'TEST', ?, ?, 'IN_CABINET', ?, ?)",
                no, no, battery, health, cabinetNo, slotIndex);
        return no;
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
