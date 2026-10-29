package ai.neargo.sharehub.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 借还类用例的柜子夹具：每条用例开始前把一台柜子摆回「能借」。
 *
 * <p><b>为什么需要</b>：测试库 test_sharehub 是有状态的共享资产（见测试 application.properties 的说明），
 * 而借还用例会把宝借走。用例中途失败、或被别的用例改了站点状态时，宝就留在「已借出」、站点停在「暂停」——
 * 下一次跑同一批用例全部 409（「暂无可借充电宝」「该站点暂停服务」），报的是环境而不是代码。
 * 2026-10-29 实测：CAB1005 的宝被历次运行借空，另一台被挑中的柜子所在站点被别的用例置为暂停。
 *
 * <p>做法：站点置营业中、柜子置在用在线、撤掉停借保护、补四颗满电的夹具宝进 1–4 号仓、按同口径重算可借数。
 * 只动这一台柜子和它的站点，不清别人的数据。
 */
public final class RentFixture {

    /** 种子里归属清楚的一台（LOC205 / ST305 / AG006），借还用例统一用它。 */
    public static final String CABINET = "CAB1005";

    private static final int STOCK = 4;

    private RentFixture() {
    }

    /** 把 {@link #CABINET} 摆成能借，返回柜号。 */
    public static String rentable(JdbcTemplate jdbc) {
        return prepare(jdbc, CABINET);
    }

    public static String prepare(JdbcTemplate jdbc, String cabinetNo) {
        jdbc.update("UPDATE loc_site SET status = 'ACTIVE' WHERE site_no = (SELECT site_no FROM "
                + "(SELECT site_no FROM dev_cabinet WHERE cabinet_no = ?) t)", cabinetNo);
        jdbc.update("UPDATE dev_cabinet SET status = 'DEPLOYED', online_status = 'ONLINE' WHERE cabinet_no = ?", cabinetNo);
        jdbc.update("UPDATE dev_protection SET active = 0 WHERE cabinet_no = ? AND active = 1", cabinetNo);
        for (int slot = 1; slot <= STOCK; slot++) {
            String pb = "PBFIX-" + cabinetNo + "-" + slot;
            jdbc.update("INSERT INTO dev_powerbank (powerbank_no, tenant_id, sn, vendor_code, battery, status, cabinet_no, "
                    + "slot_index, cycles, health) SELECT ?, 'MAIN', ?, 'TEST', 100, 'IN_CABINET', ?, ?, 0, 'OK' FROM DUAL "
                    + "WHERE NOT EXISTS (SELECT 1 FROM dev_powerbank WHERE powerbank_no = ?)", pb, pb, cabinetNo, slot, pb);
            jdbc.update("UPDATE dev_powerbank SET status = 'IN_CABINET', cabinet_no = ?, slot_index = ?, battery = 100, "
                    + "health = 'OK', archived_at = NULL, deleted = 0 WHERE powerbank_no = ?", cabinetNo, slot, pb);
        }
        // 可借数与 PowerbankTracker.recount 同口径：在柜、未归档、电量够、未老化（保护已在上面撤掉）
        jdbc.update("UPDATE dev_cabinet SET available_count = (SELECT n FROM (SELECT COUNT(*) n FROM dev_powerbank "
                + "WHERE cabinet_no = ? AND status = 'IN_CABINET' AND archived_at IS NULL AND (battery IS NULL OR battery >= 60) "
                + "AND (health IS NULL OR health <> 'AGED')) t) WHERE cabinet_no = ?", cabinetNo, cabinetNo);
        return cabinetNo;
    }
}
