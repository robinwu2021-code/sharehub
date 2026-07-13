import { defineConfig, presetWind } from "unocss";

// preset-wind = Tailwind 兼容原子类心智（跨端统一走地基不走 UI，见 tech-stack-frontend）。
export default defineConfig({
  presets: [presetWind()],
  theme: {
    colors: {
      brand: "#16a34a", // 品牌绿（充电/可用）
    },
  },
  shortcuts: {
    "card": "bg-white rounded-xl p-3 shadow-sm",
    "row-between": "flex items-center justify-between",
  },
});
