// 国家区号（全球版，MENA 优先 + 常见）。picker 展示 国旗+国家+区号。
import type { Country } from "@/types";

export const COUNTRIES: Country[] = [
  { iso: "AE", dial: "+971", flag: "🇦🇪", name: "United Arab Emirates" },
  { iso: "SA", dial: "+966", flag: "🇸🇦", name: "Saudi Arabia" },
  { iso: "QA", dial: "+974", flag: "🇶🇦", name: "Qatar" },
  { iso: "KW", dial: "+965", flag: "🇰🇼", name: "Kuwait" },
  { iso: "BH", dial: "+973", flag: "🇧🇭", name: "Bahrain" },
  { iso: "OM", dial: "+968", flag: "🇴🇲", name: "Oman" },
  { iso: "EG", dial: "+20", flag: "🇪🇬", name: "Egypt" },
  { iso: "CN", dial: "+86", flag: "🇨🇳", name: "China" },
  { iso: "GB", dial: "+44", flag: "🇬🇧", name: "United Kingdom" },
  { iso: "US", dial: "+1", flag: "🇺🇸", name: "United States" },
  { iso: "IN", dial: "+91", flag: "🇮🇳", name: "India" },
  { iso: "PK", dial: "+92", flag: "🇵🇰", name: "Pakistan" },
];

export const DEFAULT_COUNTRY: Country = COUNTRIES[0]; // AE (+971)
