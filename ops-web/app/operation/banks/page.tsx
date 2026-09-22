"use client";

// 运营管理 › 银行管理（banks）—— F1 占位，见 docs/technical/TDD-运营管理菜单-前端.md
import { PagePending } from "@/components/operation/page-pending";

export default function Page() {
  return (
    <PagePending
      label="银行管理"
      purpose="对标简电「银行管理」：提现收款用的银行字典，按国家和币种维护。"
      reason="本页在 F2 阶段开发，后端接口已就绪。"
    />
  );
}
