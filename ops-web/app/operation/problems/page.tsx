"use client";

// 运营管理 › 问题管理（problems）—— F1 占位，见 docs/technical/TDD-运营管理菜单-前端.md
import { PagePending } from "@/components/operation/page-pending";

export default function Page() {
  return (
    <PagePending
      label="问题管理"
      purpose="对标简电「问题管理」：C 端报障和投诉时可选的问题类型，含标准答复和建议处置。"
      reason="本页在 F2 阶段开发，后端接口已就绪。"
    />
  );
}
