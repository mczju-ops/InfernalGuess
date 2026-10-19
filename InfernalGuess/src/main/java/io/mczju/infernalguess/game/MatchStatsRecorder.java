package io.mczju.infernalguess.game;

import com.github.mczjuops.mczjugamecore.MCZJUGameCore;
import com.github.mczjuops.mczjugamecore.score.leaderboard.AbstractLeaderboard;
import io.mczju.infernalguess.InfernalGuessPlugin;
import io.mczju.infernalguess.config.GameMode;

import java.util.List;

/**
 * 把一局的战绩写进玩家档案，并**立刻**刷新两个排行榜。
 *
 * <h2>为什么需要「立刻刷新」</h2>
 * MGC 自己的定时刷新是 {@code startAutoRefresh(12000L)} —— 12000 tick ≈ **10 分钟**一轮
 * （实测 MGC 1.1.2 的 {@code onEnable}）。用户的要求是
 * 「玩家破纪录后**立马**就能在榜单上看到自己的排名变动」，
 * 所以每局结束时我们主动调一次 {@code refresh(<榜单类>)}。
 *
 * <h2>调用点</h2>
 * {@code RoundManager.finish()}（胜负判定之后）—— 这是**唯一**能同时拿到
 * 「模式 / 玩家列表 / 每人最终资金 / 谁赢了」的地方。
 * ⚠️ 被中止（{@code /mgc leave} 到无人、`/ig stop`）的对局**不走 finish()**，
 * 因此那类对局不记战绩 —— 这是有意的：没打完的局不该算输。
 */
public final class MatchStatsRecorder {

    private MatchStatsRecorder() {
    }

    /**
     * 记一局的战绩 + 刷新榜单。
     *
     * <p>全程 try/catch：**写档案或刷榜单失败都不许影响对局收尾**
     * （收尾里还有 MGC 的 endGame / 房间释放）。
     */
    public static void record(InfernalGuessPlugin plugin, GameMode mode,
                              List<GamePlayer> players, List<GamePlayer> winners) {
        int recorded = 0;
        for (GamePlayer p : players) {
            if (p.isBot()) {
                continue;   // 人机的 UUID 每局现算，记了只会污染榜单
            }
            boolean won = winners.contains(p);
            try {
                InfernalGuessPlayerData data = MCZJUGameCore.getPlayerDataManager()
                        .getPlayerData(p.uuid().toString(), InfernalGuessPlayerData.class);
                data.recordMatch(mode, won, p.funds());
                recorded++;
                plugin.getLogger().info("[IG] 战绩已记 " + p.name() + "：" + mode
                        + (won ? " 胜" : " 负")
                        + "｜对决 " + data.ultimateWins + " 胜 / " + data.ultimateRuns + " 场"
                        + "，最高连胜 " + data.ultimateBestStreak
                        + "｜资金模式 最佳 " + data.basicBestFunds);
            } catch (Throwable t) {
                plugin.getLogger().warning("[IG] 写玩家档案失败：" + p.name() + " → " + t);
            }
        }
        if (recorded == 0) {
            return;   // 全是人机（调试局）→ 没有榜单会变，不必刷新
        }
        refresh(plugin, InfernalGuessRankedLeaderboard.class);
        refresh(plugin, InfernalGuessFundsLeaderboard.class);
    }

    private static void refresh(InfernalGuessPlugin plugin,
                                Class<? extends AbstractLeaderboard> leaderboard) {
        try {
            MCZJUGameCore.getLeaderboardManager().refresh(leaderboard);
        } catch (Throwable t) {
            // 「排行榜没注册」「还没有展示实体」都会走到这里 —— 不影响对局，只提示
            plugin.getLogger().warning("[IG] 刷新排行榜失败（" + leaderboard.getSimpleName()
                    + "）：" + t);
        }
    }
}
