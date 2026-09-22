"use client";

// 运营管理 › 站点概览（overview）—— F1 占位，见 docs/technical/TDD-运营管理菜单-前端.md
import { PagePending } from "@/components/operation/page-pending";

export default function Page() {
  return (
    <PagePending
      label="站点概览"
      purpose="对标简电「站场概览」：站点与设备的铺设规模、站点排行，并自动列出需要关注的站点。"
      reason="本页在 F3 阶段开发；它需要的聚合接口后端尚未实现。"
      legacy={{ href: "/", label: "经营看板" }}
    />
  );
}
