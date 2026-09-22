"use client";

// 运营管理 › 应用版本（app-versions）—— F1 占位，见 docs/technical/TDD-运营管理菜单-前端.md
import { PagePending } from "@/components/operation/page-pending";

export default function Page() {
  return (
    <PagePending
      label="应用版本"
      purpose="对标简电「应用版本」：C 端 App 各平台的版本发布、强制更新和灰度。"
      reason="本页在 F2 阶段开发，后端接口已就绪。"
    />
  );
}
