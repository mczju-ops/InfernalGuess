package io.mczju.infernalguess.game;

import io.mczju.infernalguess.battle.BattleSide;
import io.mczju.infernalguess.betting.Bet;
import io.mczju.infernalguess.betting.SettlementService;
import io.mczju.infernalguess.config.GameMode;
import io.mczju.infernalguess.config.Rounding;

import java.util.UUID;

/**
 * 一名参与者在本局中的状态。
 *
 * <p>两种模式：
 * <ul>
 *   <li>{@link GameMode#BASIC} —— 有资金，按资金排名，允许并列胜</li>
 *   <li>{@link GameMode#ULTIMATE} —— **无资金概念**（Q48），只有生命与护盾，不接受并列</li>
 * </ul>
 */
public final class GamePlayer {

    private final UUID uuid;
    private final String name;
    private final boolean bot;
    private final GameMode mode;

    private long funds;
    private int life;
    private int shield;
    private boolean alive = true;
    private boolean exited = false;

    private Bet bet = Bet.watching();
    private boolean betLocked = false;
    /** 出局发生在第几回合（0 = 未出局）。终极模式用来给「都出局」的人排序。 */
    private int eliminatedAtRound;

    /**
     * ★ **本局是否只选择过「观望」**（1.1 成就「！？观者？！」用）。
     *
     * <p>语义：整局从未选过任何一边。超时未下注按观望处理、不改变本标志；
     * 只要 `setBet` 收到过一次非观望的下注，就永久置 false（改回观望也不会恢复）。
     *
     * <p>它是**本局状态**，不是账号状态 —— 所以放在 {@link GamePlayer} 里，
     * 由每局新构造的实例天然重置。
     */
    private boolean onlyWatched = true;

    public GamePlayer(UUID uuid, String name, boolean bot, GameMode mode,
                      long initialFunds, int initialLife, int initialShield) {
        this.uuid = uuid;
        this.name = name;
        this.bot = bot;
        this.mode = mode;
        this.funds = initialFunds;
        this.life = initialLife;
        this.shield = initialShield;
    }

    // ---------------- 访问 ----------------

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public boolean isBot() {
        return bot;
    }

    public GameMode mode() {
        return mode;
    }

    public long funds() {
        return funds;
    }

    public int life() {
        return life;
    }

    public int shield() {
        return shield;
    }

    public boolean isAlive() {
        return alive && !exited;
    }

    /** 是否已出局（但仍可观战）。 */
    public boolean isEliminated() {
        return !alive;
    }

    /** 是否已离开（退出对局）。 */
    public boolean isExited() {
        return exited;
    }

    public Bet bet() {
        return bet;
    }

    public boolean isBetLocked() {
        return betLocked;
    }

    /** 本局是否**只选择过观望**（从未下过任何一边）。 */
    public boolean onlyWatched() {
        return onlyWatched;
    }

    public SettlementService.Stack stack() {
        return new SettlementService.Stack(life, shield);
    }

    // ---------------- 下注 ----------------

    public void setBet(Bet bet) {
        if (betLocked) {
            return;
        }
        this.bet = bet;
        // ★ 成就「！？观者？！」：只要选过一次边，本局就不再是「只观望」。
        //   改回观望、或下一回合重新下注，都不会把标志恢复成 true。
        if (bet != null && !bet.watch()) {
            this.onlyWatched = false;
        }
    }

    public void lockBet() {
        this.betLocked = true;
    }

    public void clearBet() {
        this.bet = Bet.watching();
        this.betLocked = false;
    }

    // ---------------- 结算 ----------------

    /** 资金模式结算。 */
    public SettlementService.Result settleFunds(BattleSide winner, long killLine, Rounding rounding) {
        return settleFunds(winner, killLine, rounding, 0);
    }

    /** 资金模式结算（带回合号，用于记录出局时间）。 */
    public SettlementService.Result settleFunds(BattleSide winner, long killLine, Rounding rounding,
                                                int round) {
        var result = SettlementService.settle(funds, bet, winner, killLine, rounding);
        this.funds = result.after();
        if (result.eliminated() && this.alive) {
            this.alive = false;
            this.eliminatedAtRound = round;
        }
        return result;
    }

    /** 终极模式结算。 */
    public SettlementService.UltimateResult settleUltimate(BattleSide winner, boolean shieldActive) {
        return settleUltimate(winner, shieldActive, 0);
    }

    /**
     * 终极模式结算。
     *
     * @param round 当前回合（用于记录出局时间，供排名使用）
     */
    public SettlementService.UltimateResult settleUltimate(BattleSide winner, boolean shieldActive,
                                                           int round) {
        var result = SettlementService.settleUltimate(stack(), bet, winner, shieldActive);
        this.life = result.after().life();
        this.shield = result.after().shield();
        if (result.eliminated() && this.alive) {
            this.alive = false;
            this.eliminatedAtRound = round;
        }
        return result;
    }

    /**
     * ★ **平局（双方同归于尽）的资金结算：不扣资金也不加资金，ALL IN 也不出局**
     * （用户 2026-10-13 规则）。规则本体在 {@link SettlementService#settleDraw}。
     */
    public SettlementService.Result settleFundsDraw(Rounding rounding, int round) {
        var result = SettlementService.settleDraw(funds, bet, rounding);
        this.funds = result.after();
        // 平局不会把人打没（after == funds）⇒ 只有「结算前就已经 0 资金」才保持出局
        if (result.eliminated() && this.alive) {
            this.alive = false;
            this.eliminatedAtRound = round;
        }
        return result;
    }

    /**
     * ★ **平局（双方同归于尽）的终极模式结算：谁都不掉血**（用户 2026-10-13 规则）。
     * 规则本体在 {@link SettlementService#settleUltimateDraw}。
     */
    public SettlementService.UltimateResult settleUltimateDraw(int round) {
        var result = SettlementService.settleUltimateDraw(stack(), bet);
        this.life = result.after().life();
        this.shield = result.after().shield();
        if (result.eliminated() && this.alive) {
            this.alive = false;
            this.eliminatedAtRound = round;
        }
        return result;
    }

    /** 出局发生在第几回合（0 = 未出局）。 */
    public int eliminatedAtRound() {
        return eliminatedAtRound;
    }

    /** 标记为已离开对局（用户第 4 条：死亡后可用道具退出）。 */
    public void markExited() {
        this.exited = true;
    }

    /** 调试/人机用：直接设置资金。 */
    public void setFunds(long funds) {
        this.funds = Math.max(0, funds);
        if (this.funds <= 0) {
            this.alive = false;
        }
    }

    /** 调试用：设置护盾。 */
    public void setShield(int shield) {
        this.shield = Math.max(0, shield);
    }

    /** 调试用：设置生命。 */
    public void setLife(int life) {
        this.life = Math.max(0, life);
        if (this.life <= 0) {
            this.alive = false;
        }
    }

    @Override
    public String toString() {
        return mode == GameMode.ULTIMATE
                ? name + "(♥" + life + " ✚" + shield + ")"
                : name + "(" + funds + ")";
    }
}
