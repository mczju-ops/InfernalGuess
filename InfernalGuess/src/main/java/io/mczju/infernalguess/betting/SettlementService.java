package io.mczju.infernalguess.betting;

import io.mczju.infernalguess.battle.BattleSide;
import io.mczju.infernalguess.config.Rounding;

/**
 * 资金结算（**纯函数，无 Bukkit 依赖**，可单元测试）。
 *
 * <h2>规则来源：用户第 3 条修正</h2>
 * <pre>
 *   观望      ：资金不变
 *   常规比例  ：赢 → F + S      输 → F - S
 *   ALL IN    ：赢 → 2F + L     输 → 0（出局）
 *
 *   F = 结算前资金，S = 押注额，L = 本回合斩杀线
 * </pre>
 *
 * <p>用户给出的验算例：{@code F=31250, L=40000, ALL IN 获胜 → 31250×2 + 40000 = 102500}。
 *
 * <p>要点：
 * <ul>
 *   <li>「200% 资金返还」= 押注额从资金扣除，获胜时返还 {@code 2S}</li>
 *   <li>**斩杀线奖励只给 ALL IN**，且**无条件发放**（Q45：不问资金是否低于斩杀线）</li>
 *   <li>观望**在任意回合都可用**，包括资金低于斩杀线时（用户第 3 条）</li>
 * </ul>
 */
public final class SettlementService {

    private SettlementService() {
    }

    /**
     * 判断一个下注在**斩杀线约束**下是否允许（用户原始需求第 1 条）。
     *
     * <p>用户原话：「资金**少于**斩杀线时**只能**选择 all in」。
     *
     * <p>后经用户修正（第 3 条）：**观望在任意回合都可用**，斩杀线不再禁止观望。
     * 因此实际的约束是：
     * <pre>
     *   资金 >= 斩杀线  →  观望 / 25% / 50% / ALL IN 都允许
     *   资金 &lt;  斩杀线  →  **只能观望或 ALL IN**
     * </pre>
     *
     * <p>注意斩杀线为 0 的早期回合（第 1~5 回合）此约束不生效。
     */
    public static boolean isBetAllowed(long funds, Bet bet, long killLine) {
        if (bet.watch() || bet.isAllIn()) {
            return true;
        }
        return funds >= killLine;
    }

    /** 不允许时的原因文案（供界面/指令提示）。 */
    public static String disallowedReason(long funds, long killLine) {
        return "资金 " + funds + " 低于斩杀线 " + killLine + "，本回合只能观望或 ALL IN";
    }

    /**
     * 一次结算的结果。
     *
     * @param before        结算前资金
     * @param stake         押注额
     * @param payout        返还给玩家的金额（含本金）
     * @param after         结算后资金
     * @param won           是否押中
     * @param watched       是否观望
     * @param eliminated    结算后是否出局（资金归零）
     * @param killLineBonus 斩杀线额外奖励（仅 ALL IN 获胜时非 0）
     */
    public record Result(long before, long stake, long payout, long after,
                         boolean won, boolean watched, boolean eliminated, long killLineBonus) {
    }

    /**
     * 结算一个玩家的资金。
     *
     * @param funds    结算前资金
     * @param bet      本回合下注
     * @param winner   本回合胜方
     * @param killLine 本回合斩杀线
     * @param rounding 押注额取整方式
     */
    public static Result settle(long funds, Bet bet, BattleSide winner,
                                long killLine, Rounding rounding) {
        if (funds < 0) {
            throw new IllegalArgumentException("资金不能为负：" + funds);
        }
        if (bet.watch()) {
            return new Result(funds, 0, 0, funds, false, true, funds == 0, 0);
        }
        long stake = bet.stake(funds, rounding);

        if (bet.side() != winner) {
            long after = funds - stake;
            return new Result(funds, stake, 0, after, false, false, after <= 0, 0);
        }

        if (bet.isAllIn()) {
            long bonus = killLine;
            long after = 2L * funds + bonus;
            return new Result(funds, stake, after, after, true, false, false, bonus);
        }

        long after = funds + stake;
        return new Result(funds, stake, 2L * stake, after, true, false, false, 0);
    }

    // ================= 终极模式（无资金概念，Q48） =================

    /** 终极模式的「生命 + 护盾」。 */
    public record Stack(int life, int shield) {
        public boolean dead() {
            return life <= 0;
        }

        public Stack minusOne(boolean shieldActive) {
            if (shieldActive && shield > 0) {
                return new Stack(life, shield - 1);
            }
            return new Stack(Math.max(0, life - 1), shield);
        }
    }

    /**
     * 终极模式的一次结算结果。
     *
     * @param lostKind 失去的是 {@code "护盾"} / {@code "生命"} / {@code null}（未失去）
     */
    public record UltimateResult(Stack before, Stack after, boolean correct,
                                 boolean watched, String lostKind, boolean eliminated) {
    }

    /**
     * 终极模式结算：**猜错扣 1 点，优先扣护盾**（用户需求）。
     *
     * @param shieldActive 护盾是否仍生效（第 7 回合开始后为 {@code false}）
     */
    public static UltimateResult settleUltimate(Stack before, Bet bet, BattleSide winner,
                                                boolean shieldActive) {
        if (bet.watch()) {
            // 观望不参与判定，不扣（🔵 Q18）
            return new UltimateResult(before, before, false, true, null, before.dead());
        }
        boolean correct = bet.side() == winner;
        if (correct) {
            return new UltimateResult(before, before, true, false, null, before.dead());
        }
        Stack after = before.minusOne(shieldActive);
        String kind = (shieldActive && before.shield() > 0) ? "护盾" : "生命";
        return new UltimateResult(before, after, false, false, kind, after.dead());
    }
}
