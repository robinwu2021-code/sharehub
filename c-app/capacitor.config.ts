import type { CapacitorConfig } from '@capacitor/cli';

/*
 * ⚠️ 这是**开发预览用的 WebView 壳，不是要发出去的那个包**。
 *
 * 壳里装的是 H5 产物，`uni.scanCode` 这类原生插件在 WebView 里不生效 ——
 * 扫码按钮点不动，而扫码是 C 端唯一的借出入口。定位与推送同理。
 * 真包走 DCloud uni 离线打包，见 docs/technical/C端App离线打包-操作手册.md。
 *
 * appId 特意带 `.preview` 后缀：与真包**不同包名**。
 * ai-shop 那边两者同名，后果是互相顶掉，而且**交付时拿错包不会有任何报错** ——
 * 界面几乎一样，只是原生能力少一半，等到验扫码/定位才发现。
 */
const config: CapacitorConfig = {
  appId: 'ai.neargo.powerbank.preview',
  appName: '搜电(预览)',
  webDir: 'dist/build/h5'
};

export default config;
