package io.mczju.infernalguess.game;

import com.github.mczjuops.mczjugamecore.score.leaderboard.AbstractLeaderboard;
import com.github.mczjuops.mczjugamecore.score.leaderboard.AutoRefresh;
import com.github.mczjuops.mczjugamecore.score.leaderboard.LeaderboardEntry;
import com.github.mczjuops.mczjugamecore.score.leaderboard.SortOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * **对决模式战绩榜单**（用户 2026-10-13 需求 1）。
 *
 * <p>记录每位玩家在对决模式（{@code ULTIMATE}）里的胜场、总场次、最高连胜，
 * **按胜场数排名**，最多显示 15 名。
 *
 * <h2>展示与刷新</h2>
 * 实体显示由 MGC 的 {@code LeaderboardManager} 负责 —— 用
 * {@code /mgcop leaderboard create infernalguess_ranked <展示实体ID>} 建记录、
 * 再用 {@code /mgcop leaderboard edit ...} 选位置，之后每次
 * {@link io.mczju.infernalguess.game.MatchStatsRecorder} 写完战绩都会
 * **立刻**调用 {@code refresh}，所以玩家破纪录后榜面马上变（不必等 MGC 的定时刷新）。
 *
 * <p>{@link AutoRefresh} 只是**第二道保险**：万一某次 refresh 失败，
 * MGC 的轮询（默认 10 分钟）还会兜一次。
 */
@AutoRefresh
public final class InfernalGuessRankedLeaderboard extends AbstractLeaderboard {

    /** 排行榜 id（{@code /mgcop leaderboard ...} 与注册时都用它）。 */
    public static final String ID = "infernalguess_ranked";

    /** 排序与单测钉住的档案字段名。 */
    public static final String SORT_FIELD = "ultimateWins";

    /** ★ 用户要求：最多显示 15 名玩家。 */
    public static final int DISPLAY_COUNT = 15;

    /** {@link #fetchEntries()} 产出的快照，供 {@link #renderLine} 回查另外两个数字。 */
    private Map<String, InfernalGuessPlayerData> snapshot = Map.of();

    @Override
    public String getTitle() {
        return "<gold><bold>对决模式战绩榜单";
    }

    @Override
    public String getSubtitle() {
        return "<gray>胜场 / 总场次 / 最高连胜";
    }

    @Override
    public int getDisplayCount() {
        return DISPLAY_COUNT;
    }

    @Override
    public SortOrder getSortOrder() {
        return SortOrder.DESCENDING;   // 胜场多的排前面
    }

    @Override
    public List<LeaderboardEntry> fetchEntries() {
        snapshot = LeaderboardSupport.snapshot();
        List<LeaderboardEntry> out = new ArrayList<>();
        for (Map.Entry<String, InfernalGuessPlayerData> e : snapshot.entrySet()) {
            int wins = e.getValue().ultimateWins;
            if (wins > 0) {
                // 一场没赢过的玩家不进榜（否则榜尾全是 0 胜，看不出谁强）
                out.add(new LeaderboardEntry(e.getKey(), wins));
            }
        }
        return out;
    }

    @Override
    public String renderLine(int rank, String playerName, double value) {
        InfernalGuessPlayerData data = snapshot.get(playerName);
        return formatLine(rank, playerName,
                (int) value,
                data == null ? 0 : data.ultimateRuns,
                data == null ? 0 : data.ultimateBestStreak);
    }

    @Override
    public String renderEmpty() {
        return "<gray>暂无战绩 —— 打完一局对决模式就会上榜";
    }

    /**
     * 一行的排版（**纯函数**，由 {@code LeaderboardSpecTest} 钉住）。
     *
     * @param rank       名次（1 起）
     * @param playerName 已经带颜色的显示名
     * @param wins       胜场
     * @param runs       总场次
     * @param bestStreak 历史最高连胜
     */
    static String formatLine(int rank, String playerName, int wins, int runs, int bestStreak) {
        return ("%s %s <dark_gray>▏</dark_gray> <white>胜 %d</white> <gray>场 %d</gray> "
                + "<dark_gray>▏</dark_gray> <aqua>连胜 %d</aqua>")
                .formatted(LeaderboardSupport.rank(rank), playerName, wins, runs, bestStreak);
    }
}
