"use client";

// 运营管理 › 收费方案（fee-plans）—— F1 占位，见 docs/technical/TDD-运营管理菜单-前端.md
import { PagePending } from "@/components/operation/page-pending";

export default function Page() {
  return (
    <PagePending
      label="收费方案"
      purpose="对标简电「收费方案」：计费规则、适用范围和时段倍率，附计费摘要与试算。"
      reason="本页在 F4 阶段开发。"
      legacy={{ href: "/pricing", label: "计费定价 › 计费模板" }}
    />
  );
}
