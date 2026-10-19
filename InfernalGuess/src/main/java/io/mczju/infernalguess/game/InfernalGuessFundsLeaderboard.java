package io.mczju.infernalguess.game;

import com.github.mczjuops.mczjugamecore.score.leaderboard.AbstractLeaderboard;
import com.github.mczjuops.mczjugamecore.score.leaderboard.AutoRefresh;
import com.github.mczjuops.mczjugamecore.score.leaderboard.LeaderboardEntry;
import com.github.mczjuops.mczjugamecore.score.leaderboard.SortOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * **富豪榜**（用户 2026-10-13 需求 1）。
 *
 * <p>记录玩家在**资金模式**（{@code BASIC}）里**胜利时**达到过的最高资金，
 * 按金额排名，最多显示 15 名。
 *
 * <p>⚠️ 只统计「赢下来的局」 —— 用户原话是「所达到过的**胜利时**最高资金数量」，
 * 输掉的局里剩多少都不算（字段说明见 {@link InfernalGuessPlayerData#basicBestFunds}）。
 */
@AutoRefresh
public final class InfernalGuessFundsLeaderboard extends AbstractLeaderboard {

    /** 排行榜 id（{@code /mgcop leaderboard ...} 与注册时都用它）。 */
    public static final String ID = "infernalguess_funds";

    /** 排序与单测钉住的档案字段名。 */
    public static final String SORT_FIELD = "basicBestFunds";

    /** ★ 用户要求：最多显示 15 名玩家。 */
    public static final int DISPLAY_COUNT = 15;

    private Map<String, InfernalGuessPlayerData> snapshot = Map.of();

    @Override
    public String getTitle() {
        return "<gold><bold>富豪榜";
    }

    @Override
    public String getSubtitle() {
        return "<gray>资金模式：胜利时达到过的最高资金";
    }

    @Override
    public int getDisplayCount() {
        return DISPLAY_COUNT;
    }

    @Override
    public SortOrder getSortOrder() {
        return SortOrder.DESCENDING;   // 钱多的排前面
    }

    @Override
    public List<LeaderboardEntry> fetchEntries() {
        snapshot = LeaderboardSupport.snapshot();
        List<LeaderboardEntry> out = new ArrayList<>();
        for (Map.Entry<String, InfernalGuessPlayerData> e : snapshot.entrySet()) {
            long best = e.getValue().basicBestFunds;
            if (best > 0) {
                out.add(new LeaderboardEntry(e.getKey(), best));
            }
        }
        return out;
    }

    @Override
    public String renderLine(int rank, String playerName, double value) {
        return formatLine(rank, playerName, (long) value);
    }

    @Override
    public String renderEmpty() {
        return "<gray>暂无记录 —— 资金模式赢一局就会上榜";
    }

    /**
     * 一行的排版（**纯函数**，由 {@code LeaderboardSpecTest} 钉住）。
     *
     * <p>金额带千分位（{@code %,d}）：这个游戏后期是六位数，不带分隔符没法一眼比大小。
     *
     * @param rank       名次（1 起）
     * @param playerName 已经带颜色的显示名
     * @param funds      胜利时最高资金
     */
    static String formatLine(int rank, String playerName, long funds) {
        return ("%s %s <dark_gray>▏</dark_gray> <gold>%,d</gold>")
                .formatted(LeaderboardSupport.rank(rank), playerName, funds);
    }
}
