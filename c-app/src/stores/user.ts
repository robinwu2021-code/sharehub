// 用户 store：登录态（C 池 Bearer）+ 资料。token 持久化到 uni storage（http-client 读同一键）。
import { defineStore } from "pinia";
import { api } from "@/api";
import type { LoginParams } from "@/api";
import type { UserProfile } from "@/types";
import { STORAGE } from "@/shared/constants";
import { setUnauthorizedHandler } from "@/api/http-client";

/** 登录页 —— 401 跳转与循环判定都以它为准。 */
const LOGIN_PAGE = "/pages/login/index";

export const useUserStore = defineStore("user", {
  state: () => ({
    token: (uni.getStorageSync(STORAGE.token) as string) || "",
    profile: (uni.getStorageSync(STORAGE.user) as UserProfile | "") || null,
  }),
  getters: {
    isLoggedIn: (s): boolean => !!s.token,
  },
  actions: {
    async login(params: LoginParams) {
      const r = await api.login(params);
      this.token = r.token;
      uni.setStorageSync(STORAGE.token, r.token);
      await this.loadProfile();
      return r;
    },
    async setSession(token: string) {
      this.token = token;
      uni.setStorageSync(STORAGE.token, token);
      await this.loadProfile();
    },
    async loadProfile() {
      const p = await api.getProfile();
      this.profile = p;
      uni.setStorageSync(STORAGE.user, p);
      return p;
    },
    setProfile(p: UserProfile) {
      this.profile = p;
      uni.setStorageSync(STORAGE.user, p);
    },
    /**
     * 登出：**先请后端吊销 token，再清本地**。
     *
     * 顺序不能反 —— 先清本地就没有 Bearer 头可发，吊销请求会被当成匿名调用，
     * 服务端那份会话原样留着。后端失败不阻止本地清理：用户点了退出就必须退出，
     * 网络不通时令牌留到自然过期，也好过"点了没反应、人还在登录态"。
     */
    async logout() {
      try {
        await api.logout();
      } catch {
        // 吞掉：吊销失败也要清本地，见上
      }
      this.clearSession();
    },
    /** 只清本地，不发请求。401 失效时用它（令牌已经无效，再发一次吊销只会再吃一个 401）。 */
    clearSession() {
      this.token = "";
      this.profile = null;
      uni.removeStorageSync(STORAGE.token);
      uni.removeStorageSync(STORAGE.user);
    },
    /**
     * 把「401 怎么办」接到 http-client 上。App onLaunch 调一次。
     *
     * 已经在登录页就不跳：否则 401 → 跳登录 → 页面请求又 401 → 再跳，死循环。
     */
    installSessionGuard() {
      setUnauthorizedHandler(() => {
        this.clearSession();
        const stack = getCurrentPages();
        const cur = stack.length ? `/${stack[stack.length - 1].route ?? ""}` : "";
        if (cur === LOGIN_PAGE) return;
        uni.reLaunch({ url: LOGIN_PAGE });
      });
    },
  },
});
