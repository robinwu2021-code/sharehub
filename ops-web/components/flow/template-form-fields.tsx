"use client";

// 审批模板的「发起表单」编辑区（OpsKit 38983255）：运营台「发起审批」按这里定义的字段渲染表单。
// 名称按当前界面语言编辑（其余语言保留原值）；下拉选项一行一个「代号 名称」。
import { useState } from "react";
import { Plus, Trash2 } from "lucide-react";
import { useFlowT } from "@/lib/flow/i18n";
import type { I18nText, TemplateFieldType, TemplateFormField } from "@/lib/flow/types";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { CheckboxField } from "@/components/ui/checkbox";

const TYPES: TemplateFieldType[] = ["TEXT", "TEXTAREA", "NUMBER", "DATE", "SELECT"];

export function TemplateFormFields({ fields, onChange, err }: {
  fields: TemplateFormField[];
  onChange: (fn: (fs: TemplateFormField[]) => void) => void;
  err: (path: string) => string | undefined;
}) {
  const { t, locale } = useFlowT("processes");
  // 选项框输入时只存草稿、失焦再解析：边打边解析会把「只敲了代号」立刻补成「代号 代号」
  const [drafts, setDrafts] = useState<Record<number, string>>({});
  const show = (n: I18nText) => n[locale] ?? "";
  const optionsText = (f: TemplateFormField) => (f.options ?? []).map((o) => `${o.code} ${o.name[locale] ?? o.name.en ?? o.name.zh ?? ""}`).join("\n");
  /** 一行一个「代号 名称」；已有选项的其余语言名称按代号保留 */
  const parseOptions = (f: TemplateFormField, text: string) => text.split("\n").map((l) => l.trim()).filter(Boolean).map((l) => {
    const [code, ...rest] = l.split(/\s+/);
    const prev = (f.options ?? []).find((o) => o.code === code);
    return { code, name: { ...(prev?.name ?? {}), [locale]: rest.join(" ") || code } as I18nText };
  });

  return (
    <div data-slot="template-form" className="mt-5">
      <div className="mb-2 flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="txt-strong">{t("form.title")}</h3>
        <span className="txt-caption text-muted-foreground">{t("form.hint")}</span>
      </div>
      <div className="space-y-2">
        {fields.map((f, i) => {
          const at = (k: string) => err(`fields[${i}].${k}`);
          return (
            <div key={i} className="rounded-card bg-secondary/50 p-3">
              <div className="grid gap-2 sm:grid-cols-[9rem_1fr_9rem_auto_auto] sm:items-start">
                <div>
                  <Input value={f.code} placeholder={t("form.code")} aria-label={t("form.code")} aria-invalid={!!at("code")}
                    onChange={(e) => onChange((fs) => { fs[i].code = e.target.value.trim(); })} />
                  {at("code") && <p className="mt-1 txt-caption text-destructive-ink">{at("code")}</p>}
                </div>
                <div>
                  <Input value={show(f.name)} placeholder={t("form.name")} aria-label={t("form.name")} aria-invalid={!!at("name")}
                    onChange={(e) => onChange((fs) => { fs[i].name = { ...fs[i].name, [locale]: e.target.value }; })} />
                  {at("name") && <p className="mt-1 txt-caption text-destructive-ink">{at("name")}</p>}
                </div>
                <Select value={f.type} aria-label={t("form.type")}
                  onChange={(e) => onChange((fs) => {
                    fs[i].type = e.target.value as TemplateFieldType;
                    if (fs[i].type !== "SELECT") delete fs[i].options;
                    else fs[i].options ??= [];
                  })}>
                  {TYPES.map((ty) => <option key={ty} value={ty}>{t(`form.types.${ty}`)}</option>)}
                </Select>
                <CheckboxField label={t("form.required")} checked={f.required}
                  onChange={(v) => onChange((fs) => { fs[i].required = v === true; })} />
                <Button size="icon" variant="ghost" aria-label={t("form.remove")}
                  onClick={() => { setDrafts({}); onChange((fs) => { fs.splice(i, 1); }); }}><Trash2 /></Button>
              </div>
              {f.type === "SELECT" && (
                <div className="mt-2">
                  <Textarea rows={3} value={drafts[i] ?? optionsText(f)} placeholder={t("form.optionsHelp")} aria-label={t("form.options")}
                    onChange={(v) => setDrafts((d) => ({ ...d, [i]: v }))}
                    onBlur={() => {
                      const text = drafts[i];
                      if (text === undefined) return;
                      onChange((fs) => { fs[i].options = parseOptions(fs[i], text); });
                      setDrafts((d) => { const n = { ...d }; delete n[i]; return n; });
                    }} />
                  <p className={at("options") ? "mt-1 txt-caption text-destructive-ink" : "mt-1 txt-caption text-muted-foreground"}>
                    {at("options") ?? t("form.optionsHelp")}
                  </p>
                </div>
              )}
            </div>
          );
        })}
        <Button variant="secondary" onClick={() => onChange((fs) => { fs.push({ code: "", name: {}, type: "TEXT", required: false }); })}>
          <Plus className="size-4" /> {t("form.add")}
        </Button>
      </div>
    </div>
  );
}
