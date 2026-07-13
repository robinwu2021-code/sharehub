// C 端会话（pinia）。持久化到 uni storage key `c-auth`，供 http-client 读取受信头。
import { defineStore } from "pinia";
import { api } from "../api";
import type { LoginReq, Profile } from "../api/types";
import { LOGIN_CHANNEL } from "../config";

const KEY = "c-auth";

interface AuthState {
  token: string;
  cUserNo: string;
  profile: Profile | null;
}

function load(): AuthState {
  try {
    const s = uni.getStorageSync(KEY) as AuthState;
    if (s && s.token) return s;
  } catch {
    /* ignore */
  }
  return { token: "", cUserNo: "", profile: null };
}

export const useAuthStore = defineStore("auth", {
  state: (): AuthState => load(),
  getters: {
    isLoggedIn: (s) => !!s.token,
  },
  actions: {
    async login(partial: Partial<LoginReq> = {}) {
      const req: LoginReq = { channel: LOGIN_CHANNEL as "APP" | "MP", ...partial };
      const resp = await api.login(req);
      this.token = resp.token;
      this.cUserNo = resp.cUserNo;
      this.profile = resp.profile;
      uni.setStorageSync(KEY, { token: this.token, cUserNo: this.cUserNo, profile: this.profile });
    },
    async refreshProfile() {
      this.profile = await api.getProfile();
      uni.setStorageSync(KEY, { token: this.token, cUserNo: this.cUserNo, profile: this.profile });
    },
    logout() {
      this.token = "";
      this.cUserNo = "";
      this.profile = null;
      uni.removeStorageSync(KEY);
    },
  },
});
