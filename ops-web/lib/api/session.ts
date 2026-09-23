// 会话收口：登出与 401 失效的**唯一去处**。
//
// 为什么单独成文件而不是塞进 lib/auth.ts：
// http-client 需要 import auth（读 token 拼头），auth 若反过来 import api 就成了循环依赖。
// 把"要发请求的那一半"放在这里，依赖方向保持 session → api → http-client → auth 单向。
import { api } from ".";
import { useAuth } from "../auth";

/** 登录页路径 —— 401 跳转与循环判定都以它为准。 */
export const LOGIN_PATH = "/login";

/**
 * 主动登出：**先让后端吊销 token，再清本地**。
 *
 * 顺序不能反：先清本地就没有 Authorization 头可发，吊销请求会被当成匿名调用，
 * 服务端那份会话原样留着 —— 这正是本次修复前的实际行为（后端 `POST /api/auth/logout`
 * 早就实现了吊销，前端从来没调过）。
 *
 * 后端调用失败**不阻止本地清理**：用户点了退出就必须退出。网络不通时令牌会留到自然过期，
 * 这比"点了没反应、人还在登录态"要好。
 */
export async function signOut(): Promise<void> {
  try {
    await api.logout();
  } catch {
    // 吞掉：吊销失败也要清本地，见上
  }
  useAuth.getState().logout();
}

/**
 * 会话已失效（401）：清本地并回登录页。
 *
 * **不调后端** —— 令牌已经无效了，再发一次吊销只会再吃一个 401。
 *
 * 返回是否真的发生了跳转，便于测试断言。
 */
export function sessionExpired(): boolean {
  useAuth.getState().logout();
  if (typeof window === "undefined") return false;
  // 已经在登录页就不跳：否则 401 → 跳登录 → 页面请求又 401 → 再跳，成死循环
  if (window.location.pathname === LOGIN_PATH) return false;
  window.location.replace(LOGIN_PATH);
  return true;
}

/**
 * 这个端点的 401 该不该触发"会话失效"。
 *
 * **登录接口除外**：密码输错时后端也返回 401，若按会话失效处理，用户看到的是
 * 页面刷新回登录页，而不是"用户名或密码错误" —— 错误信息被自己的跳转吃掉了。
 * 登出接口同理：本来就在退出，再跳一次没有意义。
 */
export function isSessionSensitive(path: string): boolean {
  return !path.startsWith("/api/auth/login") && !path.startsWith("/api/auth/logout");
}
