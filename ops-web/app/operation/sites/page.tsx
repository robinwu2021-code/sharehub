"use client";

// 运营管理 › 站点管理（sites）—— F1 占位，见 docs/technical/TDD-运营管理菜单-前端.md
import { PagePending } from "@/components/operation/page-pending";

export default function Page() {
  return (
    <PagePending
      label="站点管理"
      purpose="对标简电「站场管理」：站点的新增、编辑、暂停营业和单站统计；点位在站点详情里维护。"
      reason="本页在 F3 阶段开发。"
      legacy={{ href: "/locations?tab=sites", label: "站点与点位 › 站点管理" }}
    />
  );
}
