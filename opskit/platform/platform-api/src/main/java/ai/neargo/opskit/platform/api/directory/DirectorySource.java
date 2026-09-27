package ai.neargo.opskit.platform.api.directory;

/**
 * 目录数据从哪来。三种来源互不覆盖：令牌声明只改 CLAIM 的关系，同步 API 只改 SYNC 的，控制台只改 CONSOLE 的
 * —— 否则每次登录都会把运营在控制台里配好的组员冲掉。
 */
public enum DirectorySource {
    JIT, CLAIM, SYNC, CONSOLE
}
