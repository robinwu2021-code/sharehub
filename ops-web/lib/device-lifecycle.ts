// 设备生命周期环节（展示态）—— docs/requirements/设备生命周期管理-运营端设计.md §三.1。
//
// 存储态少而严（机柜 5 个状态，状态机强制）；运营看的是「这台设备在生命周期的哪一步、下一步谁做」，
// 由存储态 + 出库池 / 点位 / 质检派生。后端出 `assetStatus` 之前前端按同一口径算，之后改读它。
import type { Cabinet } from "./types";

export type LifecycleStage =
  | "PENDING_QC"     // 在仓 · 待质检
  | "IN_STOCK"       // 在仓 · 可出库
  | "IN_TRANSIT"     // 在途
  | "DISPATCHED"     // 已发货 · 待签收（V195：有接收人、还没签收；不能绑定）
  | "ISSUED"         // 已出库 · 待绑定（在商家出库池）
  | "BOUND"          // 已绑定 · 待上线（装机、试借还、过门禁）
  | "LIVE"           // 已上线
  | "FAULT"          // 故障
  | "RETIRED";       // 报废

/** 生命周期七环节（设计 §二）里设备当前所处的那一环，供进度条高亮。 */
export type LifecyclePhase = "INBOUND" | "WAREHOUSE" | "DEPLOY" | "INSTALL" | "LIVE" | "MAINTAIN";

export const LIFECYCLE_PHASES: { key: LifecyclePhase; label: string }[] = [
  { key: "INBOUND", label: "入库" },
  { key: "WAREHOUSE", label: "仓储与出库" },
  { key: "DEPLOY", label: "绑定" },
  { key: "INSTALL", label: "安装上线" },
  { key: "LIVE", label: "运行" },
  { key: "MAINTAIN", label: "维护与退出" },
];

export const STAGE_LABEL: Record<LifecycleStage, string> = {
  PENDING_QC: "在仓 · 待质检",
  IN_STOCK: "在仓 · 可出库",
  IN_TRANSIT: "在途",
  DISPATCHED: "已发货 · 待签收",
  ISSUED: "已出库 · 待绑定",
  BOUND: "已绑定 · 待上线",
  LIVE: "已上线",
  FAULT: "故障",
  RETIRED: "报废",
};

export const STAGE_TONE: Record<LifecycleStage, "muted" | "warning" | "info" | "success" | "danger" | "outline"> = {
  PENDING_QC: "warning", IN_STOCK: "muted", IN_TRANSIT: "info", DISPATCHED: "info", ISSUED: "warning",
  BOUND: "info", LIVE: "success", FAULT: "danger", RETIRED: "outline",
};

type CabLike = Pick<Cabinet, "status" | "locationNo" | "warehouseNo" | "qcStatus" | "issuedAgentNo" | "issueClaimedAt">;

/** 机柜当前的生命周期环节。 */
export function lifecycleStage(c: CabLike): LifecycleStage {
  switch (c.status) {
    case "RETIRED": return "RETIRED";
    case "FAULT": return "FAULT";
    case "DEPLOYED": return "LIVE";
    case "IN_TRANSIT": return "IN_TRANSIT";
    default:
      if (c.locationNo) return "BOUND";
      // 字段缺省（V195 之前的数据形状）按已签收算
      if (c.issuedAgentNo) return c.issueClaimedAt === null ? "DISPATCHED" : "ISSUED";
      return c.qcStatus === "PENDING" || c.qcStatus === "FAILED" ? "PENDING_QC" : "IN_STOCK";
  }
}

export function lifecyclePhase(stage: LifecycleStage): LifecyclePhase {
  switch (stage) {
    case "PENDING_QC": return "INBOUND";
    case "IN_STOCK": case "IN_TRANSIT": case "DISPATCHED": return "WAREHOUSE";
    case "ISSUED": return "DEPLOY";
    case "BOUND": return "INSTALL";
    case "LIVE": return "LIVE";
    default: return "MAINTAIN";
  }
}

/** 「卡在哪 / 下一步谁做」—— 设备详情进度条下的一句话，带去处。 */
export function nextStep(c: CabLike): { text: string; who: string; href?: string } {
  switch (lifecycleStage(c)) {
    case "PENDING_QC": return { text: c.qcStatus === "FAILED" ? "质检未通过：返修或报废" : "待入库质检：质检放行后才能出库", who: "仓管" };
    case "IN_STOCK": return { text: "在仓可用：出库给商家后进入它的出库池", who: "仓管 / 运营", href: "/inventory?tab=issued" };
    case "IN_TRANSIT": return { text: "调拨在途：签收后回到在库", who: "仓管", href: "/inventory?tab=transfers" };
    case "DISPATCHED": return { text: "已发货待签收：接收人签收后才能绑定", who: "接收人", href: "/inventory?tab=issued" };
    case "ISSUED": return { text: "在商家出库池：绑定到它的站点点位（默认站点默认点位）", who: "运营", href: "/inventory?tab=issued" };
    case "BOUND": return { text: "已绑定点位：装机完工、试借还、过上线门禁后上线", who: "运维", href: "/devices?tab=pending-live" };
    case "LIVE": return { text: "运行中：在线与可借看实时监控；移位走撤机工单", who: "运维", href: "/devices?tab=monitor" };
    case "FAULT": return { text: "故障：派维修工单，修复后恢复运行；修不好撤机回仓", who: "运维", href: "/devices?tab=repairs" };
    case "RETIRED": return { text: "已报废：不能再出库、绑定或上线", who: "—" };
  }
}
