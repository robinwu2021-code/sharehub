// 设备运维：上线门禁 · 试借还 · 保护动作（`/api/ops/devices/**`）。
//
// ⚠️ **为什么单独成文件而不是并进 device.ts**
// 2026-09-25 落这一批时，`lib/types/device.ts` 正被另一个会话改着（`git status` 长时间为 `M`）。
// 按 CLAUDE.md 的并发纪律，有别人在途改动时「改用非侵入方式」——
// 往那个文件里加内容，`git add` 会把对方未完成的改动一起提交（本仓库已因此出过两次事故）。
//
// **这不是永久归宿**：等 `types/device.ts` 空出来，这几个类型应当并回去，本文件删除。
// 在那之前它是一个有意为之的临时落点，不是「设备类型分了两处」的新约定。

/**
 * 保护动作（SSOT，与后端 `ProtectionAction` 同名同值）。
 *
 * <p>四档按**影响面**从大到小：停租整柜 → 禁用仓位 → 锁定仓位 → 降额。
 * 它们不是互斥的状态，而是**可叠加的约束**：同一台设备可能同时挂着
 * 信号触发的停租和人工挂的仓位禁用，所以下面用「引用计数」的方式解除
 * （见 {@link Protection.holderType}）。
 */
export type ProtectionAction = "STOP_RENT" | "SLOT_DISABLE" | "SLOT_LOCK" | "DERATE";

/**
 * 保护的持有方（`dev_protection.holder_type`）。
 *
 * <p>**这是保护能不能被解除的依据**：信号（SIGNAL）与告警（ALARM）挂的保护
 * 由系统在条件恢复时自己撤，人工（MANUAL）挂的只能人工撤。
 * 不分持有方的话，运维手一抖把告警挂的停租解了，设备会在故障未恢复时重新接客。
 */
export type ProtectionHolderType = "SIGNAL" | "ALARM" | "MANUAL";

/**
 * 试借还状态（SSOT，与后端 `TrialRentStatus` 同名同值）。
 *
 * <p>上线门禁里最硬的一关：**新装的柜子必须真借出一个宝、再真还回去**，
 * 才算证明了弹仓与回收都通。只查配置不试一次的话，
 * 第一个真实用户就是试验品，而那时现场已经没人了。
 */
export type TrialRentStatus = "EJECTING" | "WAIT_RETURN" | "PASSED" | "FAILED" | "EXPIRED";

/** 一次试借还（后端 `TrialRent`）。 */
export interface TrialRent {
  trialNo: string;
  cabinetNo: string;
  slotIndex: number | null;
  powerbankNo: string | null;
  status: TrialRentStatus;
  ejectedAt: string | null;
  returnedAt: string | null;
  failReason: string | null;
  operator: string | null;
  createdAt: string;
}

/** 一条保护（后端 `Protection`）。 */
export interface Protection {
  protectionNo: string;
  cabinetNo: string;
  /** 空 = 整柜级；有值 = 只作用于该仓位。 */
  slotIndex: number | null;
  action: ProtectionAction;
  holderType: ProtectionHolderType;
  /** 持有方的业务号：信号码 / 告警号 / 操作人。解除时要核对它。 */
  holderRef: string | null;
  reason: string | null;
  active: boolean;
  createdAt: string;
  releasedAt: string | null;
  releaseReason: string | null;
}

/** 挂保护的入参。 */
export interface ProtectionReq {
  action: ProtectionAction;
  slotIndex?: number | null;
  reason: string;
}

/** 设备信号码字典（后端 `SignalCode`）—— 设备错误码降级后的「信号」。 */
export interface SignalCode {
  code: string;
  name: string;
  nameEn: string | null;
  /** 分类：通信 / 电源 / 仓位 … */
  category: string | null;
  /** 作用范围：整柜还是仓位。 */
  scope: string | null;
  /** 命中后自动挂的保护动作；空 = 只记录不保护。 */
  protectiveAction: ProtectionAction | null;
  /** 哪个信号码能清除它（成对出现的信号，如 离线/恢复）。 */
  clearsCode: string | null;
  /** 它喂给哪些业务告警。信号本身**不是**告警——业务告警才是人要看的那层。 */
  feeds: string | null;
}
