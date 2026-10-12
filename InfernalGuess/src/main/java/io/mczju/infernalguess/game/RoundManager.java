package io.mczju.infernalguess.game;

import io.mczju.infernalguess.InfernalGuessPlugin;
import io.mczju.infernalguess.battle.BattleService;
import io.mczju.infernalguess.battle.BattleSide;
import io.mczju.infernalguess.battle.BattleTracker;
import io.mczju.infernalguess.betting.Bet;
import io.mczju.infernalguess.betting.SettlementService;
import io.mczju.infernalguess.config.ArenaConfig;
import io.mczju.infernalguess.config.GameConfig;
import io.mczju.infernalguess.config.GameMode;
import io.mczju.infernalguess.run.RoundPlan;
import io.mczju.infernalguess.run.RunPlanner;
import io.mczju.infernalguess.run.SeededRng;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 一局对局的**回合状态机**（10 回合循环）。
 *
 * <pre>
 *   PREPARING  用剧本生成双方生物并锁定 AI
 *      ↓
 *   BETTING    玩家下注（或倒计时结束；人机在此自动决策）
 *      ↓
 *   FIGHTING   归还 AI，开打
 *      ↓
 *   SETTLING   按用户第 3 条结算资金（或终极模式的生命/护盾）
 *      ↓
 *   CLEANUP    清场 → 下一回合
 * </pre>
 *
 * <p>不依赖真实玩家：{@link GamePlayer#isBot()} 的参与者由
 * {@link #botDecide(GamePlayer, int)} 自动下注，因此可以在**控制台**把整局跑完
 * （见 {@code /ig debug round}）。这同时就是阶段 ⑨「人机模式」的核心。
 */
public final class RoundManager {

    private final InfernalGuessPlugin plugin;
    private final List<GamePlayer> players;
    private final ArenaConfig.Arena arena;
    private final World world;
    private final long seed;
    private final SeededRng root;
    private final int bettingSeconds;
    private final int maxRounds;
    /** 本局模式（可被调试指令覆盖，见构造函数）。 */
    private final GameMode mode;

    private int round;
    private RoundPhase phase = RoundPhase.IDLE;
    private BattleService battle;
    private BukkitTask countdown;
    private BukkitTask fightTimeout;
    /** 双灭（同归于尽）检查的心跳任务 —— 见 {@link #endIfEveryoneDead()}。 */
    private BukkitTask mutualDeathCheck;
    /** 双灭是否已裁决（防止连着几个心跳重复裁决）。 */
    private boolean mutualDeathResolved;
    /** 回合间的清场延迟任务 —— 必须记下来，否则 {@link #stop} 之后它还会把对局推进一轮。 */
    private BukkitTask cleanupTask;
    /** 本局开始时真人玩家的数量（0 = 纯调试用的全人机局）。 */
    private final long realPlayerCount;
    /** 本回合的剧本（供下注界面展示阵容与词条）。 */
    private volatile RoundPlan currentPlan;
    private int secondsLeft;
    private volatile boolean stopped;
    /** 「已收尾」标志：保证结束回调只触发一次。 */
    private volatile boolean finished;
    /**
     * 全部回合结束时的回调。
     *
     * <p>⚠️ **必须由 MGC 侧接管去调 {@code endGame()}** —— 早期版本这里只是打印排名，
     * 从不通知 MGC，导致实测症状：
     * 「胜出后不会结束游戏 / 房间不会取消 / 所有人都死了对局还在跑 / 退出后仍看得到记分板」。
     */
    private volatile Runnable finishListener;

    /** 上一回合的胜方（供界面显示）。 */
    private BattleSide lastWinner;

    public RoundManager(InfernalGuessPlugin plugin, List<GamePlayer> players,
                        ArenaConfig.Arena arena, World world, long seed, int bettingSeconds) {
        this(plugin, players, arena, world, seed, bettingSeconds, 0, null);
    }

    /**
     * @param roundsLimit 回合数上限；{@code <= 0} 表示用 {@code config.yml} 的 {@code rounds}
     *                    （调试时可缩短，避免测试跑满 10 回合）
     */
    public RoundManager(InfernalGuessPlugin plugin, List<GamePlayer> players,
                        ArenaConfig.Arena arena, World world, long seed, int bettingSeconds,
                        int roundsLimit) {
        this(plugin, players, arena, world, seed, bettingSeconds, roundsLimit, null);
    }

    /**
     * @param modeOverride 模式覆盖；{@code null} 表示用 {@code config.yml} 的 {@code mode}
     *                     （供调试指令在不改配置的情况下实测终极模式）
     */
    public RoundManager(InfernalGuessPlugin plugin, List<GamePlayer> players,
                        ArenaConfig.Arena arena, World world, long seed, int bettingSeconds,
                        int roundsLimit, GameMode modeOverride) {
        this.plugin = plugin;
        this.players = List.copyOf(players);
        this.arena = arena;
        this.world = world;
        this.seed = seed;
        this.root = new SeededRng(seed);
        this.bettingSeconds = Math.max(1, bettingSeconds);
        this.maxRounds = roundsLimit > 0 ? roundsLimit : plugin.gameConfig().rounds();
        this.mode = modeOverride != null ? modeOverride : plugin.gameConfig().mode();
        this.realPlayerCount = players.stream().filter(p -> !p.isBot()).count();
    }

    // ================= 生命周期 =================

    public void start() {
        if (phase != RoundPhase.IDLE) {
            throw new IllegalStateException("该对局已经启动过了");
        }
        round = 1;
        plugin.getLogger().info("[IG] 对局开始：seed=" + seed + " ｜ 参与者 " + players.size()
                + " 人（人机 " + players.stream().filter(GamePlayer::isBot).count() + "）"
                + " ｜ 竞技场 " + arena.name());
        nextRound();
    }

    /** 中止整局。 */
    public void stop(String reason) {
        stopped = true;
        cancelTasks();
        if (battle != null && !battle.isCleaned()) {
            battle.cleanup();
        }
        phase = RoundPhase.ENDED;
        // 收掉下注器 + 摘掉记分板
        for (GamePlayer gp : players) {
            if (gp.isBot()) {
                continue;
            }
            var p = plugin.getServer().getPlayer(gp.uuid());
            if (p != null && p.isOnline()) {
                plugin.menuItemService().take(p);
                plugin.scoreboardService().clear(p);
            }
        }
        plugin.getLogger().info("[IG] 对局中止（" + reason + "）");
    }

    private void cancelTasks() {
        if (countdown != null) {
            countdown.cancel();
            countdown = null;
        }
        if (fightTimeout != null) {
            fightTimeout.cancel();
            fightTimeout = null;
        }
        if (mutualDeathCheck != null) {
            mutualDeathCheck.cancel();
            mutualDeathCheck = null;
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    // ================= 回合推进 =================

    private void nextRound() {
        if (stopped) {
            return;
        }
        GameConfig cfg = plugin.gameConfig();
        long aliveCount = players.stream().filter(GamePlayer::isAlive).count();
        // 真人玩家全没了（都出局/都退出了）→ 必须收尾，否则人机/场地会一直被占着
        // （用户反馈 6：只剩人机时不结束，占着场地）
        long aliveReal = players.stream().filter(p -> !p.isBot() && p.isAlive()).count();

        if (round > maxRounds) {
            // ★ 终极模式加时（Q48）：不接受并列，一直打到唯一胜者或全灭
            if (mode == GameMode.ULTIMATE && cfg.ultimateOvertime() && aliveCount >= 2) {
                plugin.getLogger().info("[IG] 终极模式加时 → 第 " + round
                        + " 回合（存活 " + aliveCount + " 人）");
            } else {
                finish();
                return;
            }
        } else if (aliveCount == 0) {
            // 资金模式也可能全员出局（一起 ALL IN 全输）—— 必须收尾，不能空转
            plugin.getLogger().info("[IG] 已无任何存活参与者，收尾。");
            finish();
            return;
        } else if (realPlayerCount > 0 && aliveReal == 0) {
            plugin.getLogger().info("[IG] 真人玩家已全部出局或离开，收尾（不再让人机独自占用场地）。");
            finish();
            return;
        } else if (aliveCount <= 1 && players.size() > 1) {
            plugin.getLogger().info("[IG] 仅剩 " + aliveCount + " 人存活，提前结束。");
            finish();
            return;
        }

        // 难度表只覆盖 1..rounds；加时回合沿用最后一档难度，但用不同的 salt 让剧本仍有变化
        int effectiveRound = Math.min(round, maxRounds);
        SeededRng roundRoot = (round <= maxRounds)
                ? root
                : root.derive(round * 1_000_003L);

        phase = RoundPhase.PREPARING;
        RoundPlan plan = RunPlanner.planRound(roundRoot, effectiveRound, cfg,
                plugin.mobGroupConfig(), plugin.mobGroupConfig().ids());
        currentPlan = plan;   // 供下注界面展示阵容与词条

        battle = new BattleService(plugin);
        battle.setWinnerListener(this::onWinnerDecided);
        battle.prepare(plan, arena, world);

        double target = cfg.weightForRound(effectiveRound);
        plugin.getLogger().info("[IG] 第 " + round + "/" + maxRounds + " 回合已生成"
                + (round > maxRounds ? "（加时，沿用第 " + effectiveRound + " 回合难度）" : "")
                + " ｜ 目标权重 " + fmt(target)
                + " ｜ 左：" + plan.describeSide(plan.left())
                + "（" + fmt(plan.left().exactWeight()) + "，偏差 "
                + pctOf(plan.left().exactWeight(), target) + "）"
                + " ｜ 右：" + plan.describeSide(plan.right())
                + "（" + fmt(plan.right().exactWeight()) + "，偏差 "
                + pctOf(plan.right().exactWeight(), target) + "）");

        startBetting();
    }

    private void startBetting() {
        phase = RoundPhase.BETTING;
        for (GamePlayer p : players) {
            p.clearBet();
        }
        // 人机随机决策（阶段 ⑨ 的核心）
        int botIndex = 0;
        for (GamePlayer p : players) {
            if (p.isBot() && p.isAlive()) {
                botDecide(p, botIndex++);
            }
        }
        checkAllBet();

        secondsLeft = bettingSeconds;
        countdown = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> guard(
                "下注倒计时", () -> {
                    if (phase != RoundPhase.BETTING || stopped) {
                        return;
                    }
                    secondsLeft--;
                    if (secondsLeft <= 0) {
                        beginFight();
                    }
                }), 20L, 20L);

        plugin.getLogger().info("[IG] 下注阶段开始，倒计时 " + bettingSeconds + " 秒。");
        refreshBoards();
    }

    /**
     * ★ **定时回调的安全外壳**：任何异常都不许让对局永远卡住。
     *
     * <h2>为什么必须有它（「/mgc join 提示没有可用的房间」的根因）</h2>
     * 已用字节码核实 MGC 1.1.2 的两处实现：
     * <ul>
     *   <li>{@code GameRoomManager.getRandomLeisureGameRoom()} 判定「空闲」的条件只有
     *       {@code room.getState() == READY}；为空则返回 {@code null}
     *       → {@code createGame()} 返回 null → {@code /mgc join} 报「没有可用的房间」</li>
     *   <li>{@code DefaultGameManager.createGame()} 在**创建对局的瞬间**就把房间置为
     *       {@code IN_GAME}；只有 cancel / abort / end / forceAbort 会经
     *       {@code solveGameEnd()} 置回 {@code READY}</li>
     * </ul>
     * 而房间 state **不落盘** → 一旦被占住，**只能重启服务器**才能恢复。
     *
     * <p>我们这边的**释放路径只有一条**：状态机走完 → {@link #finish()} →
     * {@code finishListener} → MGC 的 {@code endGame()}。
     * 所以只要状态机"停住不动"，房间就永久被占。
     *
     * <p>⚠️ 三个定时回调原先**都没有 try/catch**，其中
     * **战斗超时判定是「一次性任务」**（{@code runTaskLater}）——
     * 它一抛异常就**不会被重试**，回合永远走不完，房间再也回不到 READY。
     * 这正是「房间里明明没人对局，/mgc join 却说没有可用房间」的最可能来源。
     *
     * <p>本外壳的做法：捕获后用 {@link #finish()} **主动收尾**，
     * 从而触发 {@code finishListener} → MGC {@code endGame()} → 房间回 READY。
     * 宁可让这一局提前结束，也不要把房间永久占死。
     */
    private void guard(String what, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            plugin.getLogger().severe("[IG] " + what + " 出错（" + t
                    + "）→ 强制结束整局，避免房间被永久占用（那会导致 /mgc join 一直说没有可用房间）。");
            try {
                finish();
            } catch (Throwable t2) {
                plugin.getLogger().severe("[IG] 强制收尾也失败了：" + t2);
            }
        }
    }

    /** 刷新侧边栏（记分板只对真人玩家生效，人机跳过）。 */
    private void refreshBoards() {
        try {
            plugin.scoreboardService().update(this);
        } catch (Throwable t) {
            plugin.getLogger().warning("刷新记分板失败：" + t);
        }
    }

    /**
     * 人机决策：随机选边 + 随机比例，小概率观望。
     *
     * <p>用本回合的派生随机源，因此**同 seed 可复现**。
     */
    private void botDecide(GamePlayer bot, int botIndex) {
        SeededRng rng = root.derive(RunPlanner.saltForRound(round) + 90_000L + botIndex);
        GameConfig cfg = plugin.gameConfig();
        double[] ratios = cfg.betRatios();

        if (rng.chance(0.15) && mode.allowsWatch()) {
            bot.setBet(Bet.watching());
            bot.lockBet();
            return;
        }
        BattleSide side = rng.chance(0.5) ? BattleSide.LEFT : BattleSide.RIGHT;
        // ★ 终极模式**没有金额概念**（亏损恒为 1 点）—— 人机若还按 25%/50% 下注，
        //   计分板上会出现毫无意义的百分比（用户反馈 5.2）。所以只选边。
        double ratio = mode.needsStakeChoice() ? ratios[rng.nextInt(ratios.length)] : 1.0;

        // 人机同样受斩杀线约束（否则会出现「人机押了玩家不能押的比例」）
        if (mode.usesKillLine()) {
            long killLine = cfg.killLineForRound(round);
            if (!SettlementService.isBetAllowed(bot.funds(), Bet.of(side, ratio), killLine)) {
                ratio = 1.0; // 低于斩杀线 → 改为 ALL IN
            }
        }
        bot.setBet(Bet.of(side, ratio));
        bot.lockBet();
    }

    /** 玩家下注（真人玩家用）。 */
    public boolean placeBet(UUID uuid, Bet bet) {
        if (phase != RoundPhase.BETTING) {
            return false;
        }
        Optional<GamePlayer> p = players.stream().filter(x -> x.uuid().equals(uuid)).findFirst();
        if (p.isEmpty() || !p.get().isAlive()) {
            return false;
        }
        // ★ 斩杀线约束（用户需求第 1 条）：资金低于斩杀线时只能观望或 ALL IN
        long killLine = plugin.gameConfig().killLineForRound(round);
        if (!SettlementService.isBetAllowed(p.get().funds(), bet, killLine)) {
            plugin.getLogger().info("[IG] " + p.get().name() + " 的下注被拒绝："
                    + SettlementService.disallowedReason(p.get().funds(), killLine));
            return false;
        }
        // ★ 终极模式禁止观望（用户第二轮反馈）：观望不承担风险、不掉血，
        //   于是「一直观望」是严格最优解，模式会失去玩法。
        if (bet.watch() && !mode.allowsWatch()) {
            plugin.getLogger().info("[IG] " + p.get().name() + " 尝试在终极模式观望，已拒绝。");
            return false;
        }
        p.get().setBet(bet);
        p.get().lockBet();
        boolean all = checkAllBet();
        if (all) {
            beginFight();
        }
        return true;
    }

    /** 检查是否所有存活者都已下注；是则立即开打。 */
    private boolean checkAllBet() {
        for (GamePlayer p : players) {
            if (p.isAlive() && !p.isBetLocked()) {
                return false;
            }
        }
        return true;
    }

    private void beginFight() {
        if (phase != RoundPhase.BETTING || stopped) {
            return;
        }
        cancelTasks();
        phase = RoundPhase.FIGHTING;
        // 未下注的按观望处理（Q1）；但终极模式不允许观望 → 随机给一边
        for (GamePlayer p : players) {
            if (p.isAlive() && !p.isBetLocked()) {
                if (mode.allowsWatch()) {
                    p.setBet(Bet.watching());
                } else {
                    // 终极模式：超时未下注的人不能白拿「不掉血」，随机分一边
                    boolean leftSide = root.derive(RunPlanner.saltForRound(round) + 555_000L
                            + p.name().hashCode()).chance(0.5);
                    p.setBet(Bet.of(leftSide ? BattleSide.LEFT : BattleSide.RIGHT, 1.0));
                    plugin.getLogger().info("[IG] " + p.name()
                            + " 未在下注阶段操作，终极模式随机分配为 "
                            + (leftSide ? "左" : "右"));
                }
                p.lockBet();
            }
        }
        battle.beginFight();
        refreshBoards();

        GameConfig cfg = plugin.gameConfig();
        plugin.getLogger().info("[IG] 第 " + round + " 回合开打。下注："
                + players.stream().filter(GamePlayer::isAlive)
                .map(p -> p.name() + "=" + (p.bet().watch() ? "观望"
                        : p.bet().side().id() + p.bet().percentLabel()))
                .reduce((a, b) -> a + ", " + b).orElse("（无）"));

        // 兜底：战斗卡住时强制判定（判据链见 BattleTracker#judgeOnTimeout）
        fightTimeout = plugin.getServer().getScheduler().runTaskLater(plugin, () -> guard(
                "战斗超时判定", () -> {
                    if (phase == RoundPhase.FIGHTING && !stopped) {
                        BattleSide w = battle.tracker().decideWinner();
                        if (w == null) {
                            int lc = battle.tracker().countedAlive(BattleSide.LEFT);
                            int rc = battle.tracker().countedAlive(BattleSide.RIGHT);
                            double lh = battle.tracker().countedHealth(BattleSide.LEFT, this::healthOf);
                            double rh = battle.tracker().countedHealth(BattleSide.RIGHT, this::healthOf);
                            // 真平局时的裁决由本局种子派生 —— 不偏袒任何一方，且可复现
                            boolean coin = root.derive(RunPlanner.saltForRound(round) + 424_242L).chance(0.5);
                            w = BattleTracker.judgeOnTimeout(lc, rc, lh, rh, coin);
                            plugin.getLogger().warning("[IG] 战斗超时（" + cfg.maxFightSeconds()
                                    + " 秒）→ 强制判定 " + w.id()
                                    + " ｜ 计数存活 左 " + lc + " / 右 " + rc
                                    + " ｜ 剩余总血量 左 " + String.format(Locale.ROOT, "%.1f", lh)
                                    + " / 右 " + String.format(Locale.ROOT, "%.1f", rh));
                        }
                        onWinnerDecided(w);
                    }
                }), 20L * cfg.maxFightSeconds());

        // ★★ 双灭（同归于尽）检查 —— 每 5 tick 一次
        //    为什么必须有：BattleTracker.decideWinner() 在**双方计数存活都为 0** 时
        //    **明确返回 null**（注释写着「由调用方处理」），而调用方一直没处理 →
        //    回合会一直挂到 maxFightSeconds（用户实测卡满 120 秒）。
        //    实测案例：蜜蜂×4 vs 狐狸×4，8 只全部被「凋零 + 毒雾」在极短时间内带走。
        mutualDeathResolved = false;
        mutualDeathCheck = plugin.getServer().getScheduler().runTaskTimer(plugin,
                () -> guard("双灭检查", this::endIfEveryoneDead), 5L, 5L);
    }

    /**
     * ★ 双方**同时**归零（同归于尽）时**立刻**结束回合。
     *
     * <h2>修的是什么</h2>
     * 平时的「一方全灭」判定是**即时**的（{@code BattleService} 心跳里
     * {@code decideWinner()} 只要有一方为 0、另一方 > 0 就会立刻决出胜负）。
     * 但**两边同时归零**时 {@code decideWinner()} 返回 {@code null} ——
     * 那一分支原本无人处理，于是回合只能干等到 {@code maxFightSeconds} 兜底超时。
     *
     * <h2>判据</h2>
     * 复用超时那套 {@code BattleTracker.judgeOnTimeout(...)}（同一份被测过的逻辑），
     * 保证「同归于尽」与「超时」两种结局的裁决口径**完全一致**；
     * 真平局时同样用**本局种子派生**的硬币决定，可复现、不偏袒任何一方。
     */
    private void endIfEveryoneDead() {
        if (mutualDeathResolved || phase != RoundPhase.FIGHTING || stopped
                || battle == null || battle.isCleaned()) {
            return;
        }
        int lc = battle.tracker().countedAlive(BattleSide.LEFT);
        int rc = battle.tracker().countedAlive(BattleSide.RIGHT);
        if (lc > 0 || rc > 0) {
            return;   // 还有活口 → 交给平时的「一方全灭」判定（它是即时的）
        }
        double lh = battle.tracker().countedHealth(BattleSide.LEFT, this::healthOf);
        double rh = battle.tracker().countedHealth(BattleSide.RIGHT, this::healthOf);
        boolean coin = root.derive(RunPlanner.saltForRound(round) + 424_242L).chance(0.5);
        BattleSide w = BattleTracker.judgeOnTimeout(lc, rc, lh, rh, coin);
        mutualDeathResolved = true;
        plugin.getLogger().warning("[IG] 双方同归于尽（同时归零）→ 立刻裁决 " + w.id()
                + " ｜ 剩余总血量 左 " + String.format(Locale.ROOT, "%.1f", lh)
                + " / 右 " + String.format(Locale.ROOT, "%.1f", rh)
                + "（不再干等到超时）");
        onWinnerDecided(w);
    }

    /** 实体 UUID → 当前血量（已卸载按 0 计）。 */
    private double healthOf(java.util.UUID uuid) {
        var e = plugin.getServer().getEntity(uuid);
        return e instanceof org.bukkit.entity.LivingEntity le ? le.getHealth() : 0.0;
    }

    // ================= 结算 =================

    private void onWinnerDecided(BattleSide winner) {
        if (phase == RoundPhase.SETTLING || phase == RoundPhase.ENDED || stopped) {
            return;
        }
        cancelTasks();
        phase = RoundPhase.SETTLING;
        lastWinner = winner;

        GameConfig cfg = plugin.gameConfig();
        boolean ultimate = mode == GameMode.ULTIMATE;
        boolean shieldActive = !cfg.shieldGoneAtRound(round);
        long killLine = cfg.killLineForRound(round);

        for (GamePlayer p : players) {
            if (!p.isAlive()) {
                continue;
            }
            boolean wasAlive = p.isAlive();
            if (ultimate) {
                var r = p.settleUltimate(winner, shieldActive, round);
                plugin.getLogger().info("  " + p.name() + " 押 "
                        + label(p.bet()) + " → " + (r.correct() ? "对" : "错"
                        + (r.lostKind() == null ? "" : "（-" + r.lostKind() + "）"))
                        + " ｜ 生命 " + p.life() + " 护盾 " + p.shield()
                        + (r.eliminated() ? " 【出局】" : ""));
            } else {
                var r = p.settleFunds(winner, killLine, cfg.rounding(), round);
                plugin.getLogger().info("  " + p.name() + " 押 " + label(p.bet())
                        + " → " + (r.won() ? "赢" : "输")
                        + " ｜ 本金 " + r.stake() + " 返还 " + r.payout()
                        + (r.killLineBonus() > 0 ? " 斩杀奖励 " + r.killLineBonus() : "")
                        + " ｜ 资金 " + r.after()
                        + (r.eliminated() ? " 【出局】" : ""));
            }
            // ★ 刚出局的玩家，左下角立刻给一条提示（用户需求 8）
            if (wasAlive && !p.isAlive()) {
                actionBar(p, "end.action-eliminated");
            }
        }

        phase = RoundPhase.EFFECT;
        refreshBoards();
        // 让烟花/发光播完再清场（延迟 = 配置的清场延迟 + 演出时间）
        int delay = cfg.cleanupDelayTicks() + 60;
        cleanupTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> guard(
                "回合清场与推进", () -> {
                    cleanupTask = null;
                    if (stopped) {
                        return;
                    }
                    phase = RoundPhase.CLEANUP;
                    if (battle != null) {
                        battle.cleanup();
                    }
                    round++;
                    nextRound();
                }), delay);
    }

    /** 向某个参与者发一条动作栏提示（左下角）。 */
    private void actionBar(GamePlayer gp, String displayPath) {
        if (gp.isBot()) {
            return;
        }
        try {
            var p = plugin.getServer().getPlayer(gp.uuid());
            if (p != null && p.isOnline() && plugin.displayConfig().hasMessage(displayPath)) {
                p.sendActionBar(plugin.displayConfig().render(displayPath));
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("发送动作栏提示失败：" + t);
        }
    }

    /** 本回合的剧本（下注界面用来展示阵容与词条）。 */
    public java.util.Optional<RoundPlan> currentPlan() {
        return java.util.Optional.ofNullable(currentPlan);
    }

    private static String label(Bet bet) {
        if (bet.watch()) {
            return "观望";
        }
        return bet.side().id() + "/" + bet.percentLabel();
    }

    // ================= 结束 =================

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        phase = RoundPhase.ENDED;
        cancelTasks();
        // 回合计数器在 nextRound() 里已自增到 maxRounds+1，这里夹回末回合，避免显示「第 4/3 回合」
        round = Math.min(round, maxRounds);
        GameConfig cfg = plugin.gameConfig();

        List<GamePlayer> ranked = new ArrayList<>(players);
        if (mode == GameMode.ULTIMATE) {
            // 终极模式排序：**存活者优先**（生命 > 护盾），
            // 已出局者按「活得久」排（出局回合越晚越靠前）——
            // 否则全员出局时大家生命都是 0，排名会退化成随机顺序。
            ranked.sort(Comparator
                    .comparing((GamePlayer p) -> p.isAlive() ? 1 : 0).reversed()
                    .thenComparing(Comparator.comparingInt(GamePlayer::life).reversed())
                    .thenComparing(Comparator.comparingInt(GamePlayer::shield).reversed())
                    .thenComparing(Comparator.comparingInt(GamePlayer::eliminatedAtRound).reversed()));
        } else {
            ranked.sort(Comparator.comparingLong(GamePlayer::funds).reversed());
        }

        plugin.getLogger().info("[IG] ══════ 对局结束（seed " + seed + " ｜ 模式 " + mode + "）══════");
        for (int i = 0; i < ranked.size(); i++) {
            GamePlayer p = ranked.get(i);
            plugin.getLogger().info(String.format(Locale.ROOT, "  #%d %-16s %s%s",
                    i + 1, p.name(),
                    mode == GameMode.ULTIMATE
                            ? ("生命 " + p.life() + " 护盾 " + p.shield())
                            : ("资金 " + p.funds()),
                    p.isAlive() ? "" : "（第 " + p.eliminatedAtRound() + " 回合出局）"));
        }

        // 胜负判定
        List<GamePlayer> winners = (mode == GameMode.ULTIMATE)
                ? finishUltimate(ranked)
                : finishFunds(ranked);

        // ★ 左下角提示（用户需求 8）：赢家 / 出局
        for (GamePlayer p : players) {
            actionBar(p, winners.contains(p) ? "end.action-winner" : "end.action-eliminated");
        }

        // ★ 通知上层收尾（MGC 侧会 endGame → 房间置回 READY → 回收道具与记分板）
        Runnable listener = finishListener;
        if (listener != null) {
            try {
                listener.run();
            } catch (Throwable t) {
                plugin.getLogger().warning("[IG] 对局收尾回调失败：" + t);
            }
        }
    }

    /** 设置「全部回合结束」回调（由 MGC 侧的对局类传入 {@code endGame}）。 */
    public void setFinishListener(Runnable listener) {
        this.finishListener = listener;
    }

    /** 终极模式收尾：**不接受并列**（Q48）。返回胜者列表（可能为空 = 全员出局）。 */
    private List<GamePlayer> finishUltimate(List<GamePlayer> ranked) {
        List<GamePlayer> survivors = ranked.stream().filter(GamePlayer::isAlive).toList();
        if (survivors.isEmpty()) {
            plugin.getLogger().info("[IG] 全员出局 → 本局无胜者。");
            return List.of();
        }
        if (survivors.size() == 1) {
            plugin.getLogger().info("[IG] 唯一胜者：" + survivors.get(0).name()
                    + "（生命 " + survivors.get(0).life()
                    + " 护盾 " + survivors.get(0).shield() + "）");
            return survivors;
        }
        plugin.getLogger().warning("[IG] 仍有 " + survivors.size()
                + " 人存活但回合已耗尽 —— 终极模式不接受并列，"
                + "请开启 config.yml 的 ultimate.overtime（加时）后重开本局。");
        return survivors;
    }

    /** 资金模式收尾：资金最高者胜，**允许并列**（用户第 2 条）。返回胜者列表。 */
    private List<GamePlayer> finishFunds(List<GamePlayer> ranked) {
        long top = ranked.isEmpty() ? 0 : ranked.get(0).funds();
        List<GamePlayer> ties = ranked.stream().filter(p -> p.funds() == top).toList();
        if (ties.size() > 1) {
            plugin.getLogger().info("[IG] 并列胜：" + String.join(", ", ties.stream()
                    .map(GamePlayer::name).toList()) + "（资金 " + top + "）");
        } else if (!ties.isEmpty()) {
            plugin.getLogger().info("[IG] 唯一胜者：" + ties.get(0).name() + "（资金 " + top + "）");
        }
        return ties;
    }

    // ================= 访问 =================

    public int round() {
        return round;
    }

    /** 本局模式（构造时确定；调试指令可通过构造参数覆盖）。 */
    public GameMode mode() {
        return mode;
    }

    /** 本局竞技场（供死亡策略把复活的玩家送回观战席）。 */
    public ArenaConfig.Arena arena() {
        return arena;
    }

    /** 全部回合是否已正常打完。 */
    public boolean isFinished() {
        return finished;
    }

    /** 是否已被中止。 */
    public boolean isStopped() {
        return stopped;
    }
    public RoundPhase phase() {
        return phase;
    }

    public List<GamePlayer> players() {
        return players;
    }

    public long seed() {
        return seed;
    }

    public int secondsLeft() {
        return secondsLeft;
    }

    public Optional<BattleSide> lastWinner() {
        return Optional.ofNullable(lastWinner);
    }

    public Optional<BattleService> battle() {
        return Optional.ofNullable(battle);
    }

    public boolean isEnded() {
        return phase == RoundPhase.ENDED;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /**
     * 该方总权重相对本回合目标的偏差百分比。
     *
     * <p>重写后难度**只**由回合权重表决定，所以「实际总权重 vs 目标」是判断
     * 配置是否匹配的**唯一**现场指标 —— 把它写进每回合的日志，
     * 出问题时不必再靠猜（配置层的乐观探测见插件启动日志）。
     */
    private static String pctOf(double actual, double target) {
        if (target <= 0) {
            return "—";
        }
        return String.format(Locale.ROOT, "%+.1f%%", (actual - target) / target * 100.0);
    }

    /** 供调试命令打印当前状态。 */
    public Map<String, String> statusMap() {
        return Map.of(
                "round", round + "/" + maxRounds,
                "phase", phase.name(),
                "countdown", String.valueOf(Math.max(0, secondsLeft)),
                "lastWinner", lastWinner == null ? "-" : lastWinner.id()
        );
    }
}
