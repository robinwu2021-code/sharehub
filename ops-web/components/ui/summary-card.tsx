// 状态摘要卡：说明**当前是什么状态**，不表达涨跌。
//
// 与 ui/misc 的 StatCard 同一套表面（圆角、底色、阴影），区别只在副文案：
// StatCard 的副文案是趋势（恒绿 / 恒红），拿来写「构建 1420 · 7 月 5 日发布」这类说明
// 会被读成「利好」。这里副文案一律中性灰。
//
// **选哪一个**：副文案是「比上周 +12%」用 StatCard；是「共 8 台机柜」「7 月 5 日发布」
// 这类说明用它。2026-09 从 components/operation/ 上提 —— 它不认任何业务，属于原语。
export function SummaryCard({ label, value, sub }: { label: string; value: React.ReactNode; sub?: string }) {
  return (
    <div data-surface="stat" className="rounded-card bg-card p-5 shadow-[var(--card-shadow)]">
      <div className="txt-body text-muted-foreground">{label}</div>
      <div className="mt-2 txt-display tabular-nums">{value}</div>
      {sub && <div className="mt-1 txt-caption text-muted-foreground">{sub}</div>}
    </div>
  );
}
