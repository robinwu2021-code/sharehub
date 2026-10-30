"use client";

// 商家门户「商家资料」（需求方案 §10.3，G23，V175）。
// 名称、位置、行业只读 —— 它们进对账单、转账文件与站点定位，改了要运营核对；联系人商家自己维护。
// 联系人是业务联系方式，**不是登录标识**：改这里不影响怎么登录（登录邮箱 / 手机号在开通时定）。
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PageTitle, Skeleton } from "@/components/ui/misc";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Field } from "@/components/ui/drawer";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { notify } from "@/lib/notify";
import { useCan } from "@/lib/hooks/use-can";
import { VENUE_INDUSTRY_LABEL, type VenueIndustry } from "@/lib/types";

export default function MerchantProfilePage() {
  const qc = useQueryClient();
  const can = useCan();
  const canEdit = can("agent:profile:update");
  const { data: p, isLoading, isError, error } = useQuery({ queryKey: ["my-profile"], queryFn: () => api.getMyProfile() });
  const [form, setForm] = useState({ contactName: "", phone: "", email: "" });

  useEffect(() => {
    if (p) setForm({ contactName: p.contactName ?? "", phone: p.phone ?? "", email: p.email ?? "" });
  }, [p]);

  const save = useMutation({
    mutationFn: () => api.updateMyContact({
      contactName: form.contactName, phone: form.phone || undefined, email: form.email || undefined,
    }),
    onSuccess: (r) => { qc.setQueryData(["my-profile"], r); notify.success("联系人已保存"); },
    onError: (e: Error) => notify.error(e.message),
  });

  const industry = p?.industry ? VENUE_INDUSTRY_LABEL[p.industry as VenueIndustry] ?? p.industry : null;
  const dirty = !!p && (form.contactName !== (p.contactName ?? "") || form.phone !== (p.phone ?? "")
    || form.email !== (p.email ?? ""));

  return (
    <div className="space-y-6">
      <PageTitle title="商家资料" desc="名称、位置、行业由平台维护；联系人可以自己改" />
      {isError ? (
        <p className="txt-body text-[var(--destructive)]">{(error as Error).message}</p>
      ) : isLoading || !p ? (
        <Skeleton className="h-64" />
      ) : (
        <>
          <Card>
            <CardHeader><CardTitle>商家档案</CardTitle></CardHeader>
            <CardContent className="space-y-4">
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <Field label="商家名称"><span className="txt-strong">{p.name}</span></Field>
                <Field label="商家编号"><span className="tabular-nums">{p.agentNo}</span></Field>
                <Field label="行业">{industry ?? <span className="text-muted-foreground">未填写</span>}</Field>
                <Field label="地址">{p.address ?? <span className="text-muted-foreground">未填写</span>}</Field>
                {p.lat !== null && p.lng !== null && (
                  <Field label="位置">
                    <a className="text-primary hover:underline tabular-nums" target="_blank" rel="noreferrer"
                       href={`https://www.google.com/maps?q=${p.lat},${p.lng}`}>
                      {Number(p.lat).toFixed(5)}, {Number(p.lng).toFixed(5)}
                    </a>
                  </Field>
                )}
              </div>
              <p className="txt-caption text-muted-foreground">
                这些信息出现在对账单和转账文件上。需要更正请联系平台运营。
              </p>
            </CardContent>
          </Card>

          <Card>
            <CardHeader><CardTitle>联系人</CardTitle></CardHeader>
            <CardContent>
              {!canEdit && <ReadOnlyNotice what="修改联系人" perm="agent:profile:update" />}
              <form
                className="grid grid-cols-1 gap-4 sm:grid-cols-3"
                onSubmit={(e) => { e.preventDefault(); save.mutate(); }}
              >
                <Field label="姓名">
                  <Input value={form.contactName} disabled={!canEdit} required
                         onChange={(e) => setForm({ ...form, contactName: e.target.value })} />
                </Field>
                <Field label="电话">
                  <Input value={form.phone} disabled={!canEdit} inputMode="tel" placeholder="+971…"
                         onChange={(e) => setForm({ ...form, phone: e.target.value })} />
                </Field>
                <Field label="邮箱">
                  <Input value={form.email} disabled={!canEdit} type="email"
                         onChange={(e) => setForm({ ...form, email: e.target.value })} />
                </Field>
                {canEdit && (
                  <div className="flex items-center gap-3 sm:col-span-3">
                    <Button type="submit" disabled={!dirty || save.isPending}>{save.isPending ? "保存中…" : "保存"}</Button>
                    <span className="txt-caption text-muted-foreground">只改业务联系方式，不改登录邮箱或手机号。</span>
                  </div>
                )}
              </form>
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}
