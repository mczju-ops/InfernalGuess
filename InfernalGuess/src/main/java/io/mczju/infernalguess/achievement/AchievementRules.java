package io.mczju.infernalguess.achievement;

import io.mczju.infernalguess.battle.BattleSide;
import io.mczju.infernalguess.config.GameMode;

import java.util.List;

/**
 * 成就判定的**唯一求值处** —— 全是纯函数，不碰 Bukkit，因此能离线单测。
 *
 * <p>阈值由 {@link AchievementCatalog} 从当前 {@code config.yml} 现算后传进来；
 * 本类不认识任何具体成就 id，只回答「这个条件成立吗」。
 */
public final class AchievementRules {

    private AchievementRules() {
    }

    // ================= 土块：资金模式理论上限 =================

    /**
     * 资金模式**理论上限**：每回合都 ALL IN 且全对。
     *
     * <p>结算公式（{@code SettlementService}）：
     * <pre>结算后 = 2 × 结算前资金 + 本回合斩杀线</pre>
     *
     * <p>用饱和运算：即便配置把数值写得极大也不会溢出成负数
     * （溢出会表现为「上限变成很小的数」→ 成就误解锁）。
     *
     * @param initialFunds 初始资金
     * @param rounds       回合数
     * @param killLine     回合号（1 基）→ 本回合斩杀线
     */
    public static long maxBasicFunds(long initialFunds, int rounds,
                                     java.util.function.IntFunction<Long> killLine) {
        long funds = Math.max(0L, initialFunds);
        int n = Math.max(0, rounds);
        for (int r = 1; r <= n; r++) {
            long bonus = killLine == null ? 0L : Math.max(0L, killLine.apply(r));
            funds = doubledPlus(funds, bonus);
        }
        return funds;
    }

    /** 饱和的 {@code 2f + b}。 */
    static long doubledPlus(long f, long b) {
        if (f < 0 || b < 0) {
            return Long.MAX_VALUE;
        }
        long room = Long.MAX_VALUE - b;
        if (f > room / 2L) {
            return Long.MAX_VALUE;
        }
        return f * 2L + b;
    }

    /** 资金是否达到「理论上限」（含边界）。 */
    public static boolean reachedMaxFunds(long funds, long maxFunds) {
        return maxFunds > 0 && funds >= maxFunds;
    }

    // ================= 老实赚钱：连续 ALL IN 输光 =================

    /**
     * 一次资金模式 ALL IN 结算后的连续输光次数。
     *
     * <ul>
     *   <li>非 ALL IN：不变；</li>
     *   <li>ALL IN 赢：清零（连续断点）；</li>
     *   <li>ALL IN 输：+1。</li>
     * </ul>
     */
    public static int bustStreakAfter(int current, boolean allIn, boolean won) {
        int base = Math.max(0, current);
        if (!allIn) {
            return base;
        }
        return won ? 0 : base + 1;
    }

    /** 连续输光是否达到阈值（含边界）。 */
    public static boolean bustStreakReached(int streak, int need) {
        return need > 0 && streak >= need;
    }

    // ================= 观者：资金模式获胜且整局只观望 =================

    public static boolean watchOnlyWin(boolean basicMode, boolean winner, boolean onlyWatched) {
        return basicMode && winner && onlyWatched;
    }

    // ================= 高端局：对决模式存活至第 11 轮 =================

    public static boolean survivedToRound11(GameMode mode, int round, boolean alive) {
        return mode == GameMode.ULTIMATE && round >= 11 && alive;
    }

    // ================= 豪情在天：与其他所有人选择相反并获胜 =================

    /**
     * 自己押的边获胜，且**其他所有存活参与者**都押了另一边。
     *
     * @param own    自己的选择（观望为 {@code null}）
     * @param winner 本轮胜方
     * @param others 其他存活参与者的选择（观望为 {@code null}）
     */
    public static boolean contrarianWin(BattleSide own, BattleSide winner, List<BattleSide> others) {
        if (own == null || winner == null || own != winner) {
            return false;
        }
        if (others == null || others.isEmpty()) {
            return false;   // 没有「其他人」就谈不上「与其他所有人相反」
        }
        for (BattleSide other : others) {
            if (other == null || other == winner) {
                return false;
            }
        }
        return true;
    }

    // ================= 赌神：胜出 100 局 =================

    public static boolean wonAtLeast(int wins, int need) {
        return need > 0 && wins >= need;
    }
}
