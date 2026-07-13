// 用户 store：登录态（C 池 Bearer）+ 资料。token 持久化到 uni storage（http-client 读同一键）。
import { defineStore } from "pinia";
import { api } from "@/api";
import type { LoginParams } from "@/api";
import type { UserProfile } from "@/types";
import { STORAGE } from "@/shared/constants";

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
    logout() {
      this.token = "";
      this.profile = null;
      uni.removeStorageSync(STORAGE.token);
      uni.removeStorageSync(STORAGE.user);
    },
  },
});
