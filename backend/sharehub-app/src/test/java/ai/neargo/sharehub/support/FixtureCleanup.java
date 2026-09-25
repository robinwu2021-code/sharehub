package ai.neargo.sharehub.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;

/**
 * 测试自建设备的清理。共享测试库里机柜数是被精确断言的基线（SmokeTest / OperatorDailyFlowTest = 种子 48 台），
 * 自建的柜子留下来就会让别的用例红 —— 建了就要删。连带删它的保护动作、试借还与在柜宝。
 */
public final class FixtureCleanup {

    private FixtureCleanup() {
    }

    public static void dropCabinets(JdbcTemplate jdbc, Collection<String> cabinetNos) {
        for (String no : cabinetNos) {
            jdbc.update("DELETE FROM dev_protection WHERE cabinet_no = ?", no);
            jdbc.update("DELETE FROM dev_trial_rent WHERE cabinet_no = ?", no);
            jdbc.update("DELETE FROM dev_powerbank WHERE cabinet_no = ?", no);
            // 批次 C：装机 / 撤机工单、质检记录、资产差异、调拨明细都按机柜号挂
            jdbc.update("DELETE FROM wo_handle WHERE wo_no IN (SELECT wo_no FROM wo_order WHERE cabinet_no = ?)", no);
            jdbc.update("DELETE FROM wo_order WHERE cabinet_no = ?", no);
            jdbc.update("DELETE FROM dev_qc_record WHERE item_no = ?", no);
            jdbc.update("DELETE FROM inv_asset_diff WHERE cabinet_no = ? OR item_no = ?", no, no);
            jdbc.update("DELETE FROM inv_transfer WHERE transfer_no IN (SELECT transfer_no FROM inv_transfer_item WHERE cabinet_no = ?)", no);
            jdbc.update("DELETE FROM inv_transfer_item WHERE cabinet_no = ?", no);
            jdbc.update("DELETE FROM dev_cabinet WHERE cabinet_no = ?", no);
        }
    }
}
