"use client";

// 运营管理 › 公告管理（notices）—— F1 占位，见 docs/technical/TDD-运营管理菜单-前端.md
import { PagePending } from "@/components/operation/page-pending";

export default function Page() {
  return (
    <PagePending
      label="公告管理"
      purpose="对标简电「公告管理」：C 端首页公告条的内容，支持三语、生效期和置顶。"
      reason="本页在 F2 阶段开发，后端接口已就绪。"
      legacy={{ href: "/marketing?tab=notices", label: "营销管理 › 公告管理" }}
    />
  );
}
