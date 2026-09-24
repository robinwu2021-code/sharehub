"use client";

// 业务件区块（三层里的第三层）。判据见 components/README.md：**依赖方向**——
// 这些组件 import 了业务类型（OrderStatus / WorkOrderStatus）或表达了业务约定
// （「运营端怎么说权限不足」「归档怎么呈现」），所以它们不在 components/ui/ 下。
import * as React from "react";
import { Section, Row, Cell, Hint } from "./kit";
import {
  OrderStatusBadge, WoStatusBadge, CabinetStatusBadge, OnlineBadge, EnabledBadge,
} from "@/components/status";
import { ShowArchivedToggle, ArchivedAt, ArchiveActions } from "@/components/archive";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import type { OrderStatus } from "@/lib/types/order";
import type { WorkOrderStatus } from "@/lib/types/workorder";
import type { CabinetStatus, OnlineStatus } from "@/lib/types/device";

const ORDER_STATES: OrderStatus[] = [
  "CREATED", "DISPENSING", "IN_USE", "RETURNED", "SETTLED", "EXCEPTION", "CLOSED",
];
const WO_STATES: WorkOrderStatus[] = ["CREATED", "DISPATCHED", "PROCESSING", "DONE", "CLOSED"];
const CAB_STATES: CabinetStatus[] = ["IN_STOCK", "DEPLOYED", "FAULT", "RETIRED"];
const ONLINE_STATES: OnlineStatus[] = ["ONLINE", "OFFLINE"];

export function BusinessSections() {
  const [showArchived, setShowArchived] = React.useState(false);

  return (
    <>
      <Section
        id="status-badges"
        name="StatusBadges"
        layer="业务件"
        file="components/status.tsx"
        purpose="各业务状态机的徽章。状态→文案→色调的映射是业务语义，故不在 ui/ 层。"
      >
        <Row label="订单状态（OrderStatusBadge）">
          {ORDER_STATES.map((s) => (
            <Cell key={s} label={s}>
              <OrderStatusBadge s={s} />
            </Cell>
          ))}
        </Row>
        <Row label="工单状态（WoStatusBadge）">
          {WO_STATES.map((s) => (
            <Cell key={s} label={s}>
              <WoStatusBadge s={s} />
            </Cell>
          ))}
        </Row>
        <Row label="机柜状态 / 在线态">
          {CAB_STATES.map((s) => (
            <Cell key={s} label={s}>
              <CabinetStatusBadge s={s} />
            </Cell>
          ))}
          {ONLINE_STATES.map((s) => (
            <Cell key={s} label={s}>
              <OnlineBadge s={s} />
            </Cell>
          ))}
        </Row>
        <Row label="启用/停用（EnabledBadge）">
          <Cell label="on">
            <EnabledBadge on />
          </Cell>
          <Cell label="off">
            <EnabledBadge on={false} />
          </Cell>
          <Cell label="自定义反义词">
            <EnabledBadge on={false} offLabel="暂停" />
          </Cell>
        </Row>
        <Hint>
          反义词不总是「停用」——点位是「暂停」，所以 EnabledBadge 收 boolean 而不是各自的枚举值。
        </Hint>
      </Section>

      <Section
        id="archive"
        name="Archive"
        layer="业务件"
        file="components/archive.tsx"
        purpose="软删除的统一表达。全站零 delete：归档而非删除，可恢复，历史数据保留。"
      >
        <Row label="ShowArchivedToggle">
          
            <ShowArchivedToggle checked={showArchived} onChange={setShowArchived} />
          
        </Row>
        <Row label="ArchivedAt">
          <Cell label="已归档">
            <ArchivedAt at="2026-06-01T10:00:00Z" />
          </Cell>
          <Cell label="未归档（应为空）">
            <ArchivedAt at={null} />
          </Cell>
        </Row>
        <Row label="ArchiveActions">
          <Cell label="未归档 → 出「归档」">
            
              <ArchiveActions
                archived={false}
                canWrite
                onArchive={() => {}}
                onUnarchive={() => {}}
              />
            
          </Cell>
          <Cell label="已归档 → 出「恢复」">
            
              <ArchiveActions
                archived
                canWrite
                onArchive={() => {}}
                onUnarchive={() => {}}
              />
            
          </Cell>
          <Cell label="无权限 → 什么都不出">
            
              <ArchiveActions
                archived={false}
                canWrite={false}
                onArchive={() => {}}
                onUnarchive={() => {}}
              />
            
          </Cell>
        </Row>
      </Section>

      <Section
        id="read-only-notice"
        name="ReadOnlyNotice"
        layer="业务件"
        file="components/read-only-notice.tsx"
        purpose="权限降级提示。原先 30 处各写各的句式，此处统一：主句恒定，差异落到 note。"
      >
        <Row label="单权限码">
          
            <ReadOnlyNotice what="预约取消" perm="order:reservation:cancel" />
          
        </Row>
        <Row label="多权限码（缺任一即降级）">
          
            <ReadOnlyNotice
              what="押金处置 / 欠费催缴"
              perm={["order:deposit:manage", "order:arrears:dun"]}
            />
          
        </Row>
        <Row label="带 note">
          
            <ReadOnlyNotice
              what="告警配置"
              perm="workorder:alarm:config"
              note="不能新增、编辑或归档"
            />
          
        </Row>
        <Hint>
          不静默隐藏操作：说清缺什么权限码，运营才能拿着码去找管理员开权限。
        </Hint>
      </Section>
    </>
  );
}
