"use client";

// 运营管理 › 分成方分成（payee-sharing）—— F1 占位，见 docs/technical/TDD-运营管理菜单-前端.md
import { PagePending } from "@/components/operation/page-pending";

export default function Page() {
  return (
    <PagePending
      label="分成方分成"
      purpose="对标简电「商户分成」：按场地方或代理商查看其分成的站点、比例和分成金额。"
      reason="与站点分成一样取决于清单 D2；金额还依赖从订单生成的真实分润明细，目前后端没有这条链路。"
      legacy={{ href: "/finance?tab=rules", label: "财务管理 › 分润规则" }}
    />
  );
}
