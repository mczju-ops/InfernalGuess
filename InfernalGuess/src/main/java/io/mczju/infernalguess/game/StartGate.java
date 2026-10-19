package io.mczju.infernalguess.game;

/**
 * ★ **开局人数门槛**（用户 1.0.1 规则，2026-10-13 追加第 2 条）——
 * 抽成纯函数，让「等待策略」与「开局兜底」共用**同一条**规则，并可离线单测。
 *
 * <h2>规则</h2>
 * 至少满足一条：
 * <ol>
 *   <li>有 {@value #MIN_HUMANS} 名**真人**；</li>
 *   <li>开着**电脑玩家**（{@code bot.enabled: true} <b>且</b> {@code bot.bots > 0} ——
 *       只开开关、数量为 0 时其实一个机器人都没有）。</li>
 * </ol>
 *
 * <h2>为什么不能交给 MGC 的 {@code min-players}</h2>
 * MGC 只数**真人**（电脑玩家是本插件在 {@link SessionPlanner} 里补的 {@code GamePlayer}，
 * 对 MGC 不可见）⇒ 把它的 {@code min-players} 改成 2 会连「1 真人 + N 人机」一起拦掉
 * （见 dev-log 075）。
 *
 * <h2>★ 为什么必须「只警告、不清房间」</h2>
 * MGC 的 {@code DefaultGameWaitStrategy.tryStart()} 对人数不足本来就是
 * {@code game.sender().warn(...)} 之后 **return**（既不 {@code startGame()} 也不
 * {@code cancelGame()}）⇒ 我们只把它的**判据**换成本类，玩家就会被**留在等待房间**里，
 * 而不是被 {@code abortGame()} 赶回大厅（用户 2026-10-13 原话：
 * 「玩家单人无人机开局时也只会弹警告，不会清空等待房间」）。
 */
public final class StartGate {

    /** 没有电脑玩家时，单局至少需要多少**真人**。 */
    public static final int MIN_HUMANS = 2;

    private StartGate() {
    }

    /** 现在能不能开局。 */
    public static boolean canStart(int humans, boolean botsEnabled, int botCount) {
        return humans >= MIN_HUMANS || botsReallyOn(botsEnabled, botCount);
    }

    /** 电脑玩家是否**真的**有人（开关开着但数量为 0 ⇒ 一个都没有）。 */
    public static boolean botsReallyOn(boolean botsEnabled, int botCount) {
        return botsEnabled && botCount > 0;
    }

    /** 给玩家的提示（`sender().warn(...)` 用；语气与 MGC 自己的「人数不足」提示保持一致）。 */
    public static String blockedMessage(int humans, boolean botsEnabled, int botCount) {
        String botState;
        if (!botsEnabled) {
            botState = "电脑玩家已关闭";
        } else if (botCount <= 0) {
            botState = "电脑玩家数量为 0";
        } else {
            botState = "电脑玩家 " + botCount + " 个";
        }
        return "人数不足，无法开始：" + MIN_HUMANS + " 名玩家或开启电脑玩家才能开局"
                + "（当前 " + humans + " 名玩家，" + botState + "）。"
                + "可用 /ig mode bots <数量> 临时开启电脑玩家，或等待其他玩家加入（房间保留）。";
    }
}
