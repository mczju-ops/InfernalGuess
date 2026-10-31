package io.mczju.infernalguess.achievement;

import com.github.mczjuops.mczjugamecore.MCZJUGameCore;
import com.github.mczjuops.mczjugamecore.achievement.AchievementKey;
import com.github.mczjuops.mczjugamecore.achievement.AchievementManager;
import com.github.mczjuops.mczjugamecore.achievement.AchievementMenu;
import com.github.mczjuops.mczjugamecore.achievement.AchievementUpdateResult;
import io.mczju.infernalguess.InfernalGuessPlugin;
import io.mczju.infernalguess.battle.BattleSide;
import io.mczju.infernalguess.betting.SettlementService;
import io.mczju.infernalguess.config.GameMode;
import io.mczju.infernalguess.game.GamePlayer;
import io.mczju.infernalguess.game.InfernalGuessPlayerData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * 成就系统的门面：**注册**（MGC 1.5.0 的 {@link AchievementManager}）、**解锁**（幂等）
 * 与对局里的几个挂点。
 *
 * <p>判定逻辑在 {@link AchievementRules}（纯函数、可单测）；本类只做
 * 「取当前局面 → 问规则 → 按 UUID 解锁」。
 *
 * <h2>三条硬约束</h2>
 * <ol>
 *   <li>解锁一律按 **uuid** 走 MGC，不遍历全服、不广播（MGC 自己会发 toast）；</li>
 *   <li>**人机不参与**：它们的 UUID 是每局现算的，MGC 的成就存储里没有登录记录；</li>
 *   <li>任何成就失败都只记日志，**绝不影响对局收尾**（收尾里还有 MGC 的 endGame / 房间释放）。</li>
 * </ol>
 */
public final class AchievementService {

    private static final Logger LOG = Logger.getLogger("InfernalGuess");

    private static volatile InfernalGuessPlugin plugin;

    /** 是否已经成功注册（reload 时先注销再注册）。 */
    private static final AtomicBoolean registered = new AtomicBoolean(false);

    /** 同一个成就 id 的失败只喊一次，避免每回合刷屏。 */
    private static final Set<String> reportedFailures = ConcurrentHashMap.newKeySet();

    private AchievementService() {
    }

    // ================================================================ 生命周期

    public static void load(InfernalGuessPlugin p) {
        plugin = p;
        register();
    }

    /**
     * 配置热重载：资金上限写进了「土块」的描述，所以改完 `config.yml` 要重建定义。
     *
     * <p>先注销再注册；MGC 的成就**进度/解锁记录在存储里**，注销定义不会清掉它们。
     */
    public static void reload(InfernalGuessPlugin p) {
        plugin = p;
        unregister();
        register();
    }

    public static void disable() {
        unregister();
        plugin = null;
    }

    private static void unregister() {
        if (!registered.compareAndSet(true, false)) {
            return;
        }
        InfernalGuessPlugin p = plugin;
        try {
            AchievementManager mgr = manager();
            if (mgr != null && p != null) {
                mgr.unregister(p);
            }
        } catch (RuntimeException | LinkageError ex) {
            LOG.warning("注销成就失败：" + ex.getMessage());
        }
    }

    private static void register() {
        InfernalGuessPlugin p = plugin;
        if (p == null) {
            return;
        }
        AchievementManager mgr = manager();
        if (mgr == null) {
            p.getLogger().warning("MGC 没有成就管理器（版本低于 1.5.0？），成就系统已停用。");
            return;
        }
        // MGC 要求「先 registerGame 再注册成就」；onEnable 的调用顺序已经满足，
        // 这里再判一次，避免 MGC 注册失败时抛出一堆难懂的异常。
        if (!gameRegistered()) {
            p.getLogger().warning("游戏尚未注册到 MGC，成就注册延后（本次跳过）。");
            return;
        }
        try {
            mgr.register(p, AchievementCatalog.definitions(p.gameConfig()));
            registered.set(true);
            p.getLogger().info("成就系统：已注册 " + AchievementCatalog.specs(p.gameConfig()).size()
                    + " 个成就（" + AchievementCatalog.GAME_ID + "）");
        } catch (RuntimeException ex) {
            p.getLogger().warning("注册成就失败：" + ex.getMessage());
        }
    }

    private static AchievementManager manager() {
        try {
            return MCZJUGameCore.getAchievementManager();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static boolean gameRegistered() {
        try {
            return MCZJUGameCore.getGameManager().getRegisteredGameIds()
                    .contains(AchievementCatalog.GAME_ID);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    // ================================================================ 玩家入口

    /**
     * 打开 MGC 的成就界面。
     *
     * <p>为什么需要自己给入口：`/mgc achievements` 不带游戏 id，会过滤出空列表
     * （Maggoteers 已实测），玩家会看到「暂无成就」。
     */
    public static boolean openMenu(Player p) {
        if (p == null) {
            return false;
        }
        // ★ 打开界面前把「赌神」的进度对齐一次：1.1 之前的老玩家可能有 ≥100 历史胜场，
        //   但当时还没有这条成就；不主动同步的话要等他们再赢一局才会解锁。
        syncWins(p);
        try {
            new AchievementMenu(p, AchievementCatalog.GAME_ID, false).open();
            return true;
        } catch (RuntimeException | LinkageError ex) {
            LOG.warning("打开成就界面失败：" + ex.getMessage());
            return false;
        }
    }

    // ================================================================ 解锁

    /** 解锁一个成就（幂等；已解锁不会再触发一次 toast）。 */
    public static boolean unlock(Player p, String id) {
        if (p == null || id == null || !p.isOnline()) {
            return false;
        }
        AchievementManager mgr = manager();
        if (mgr == null) {
            return false;
        }
        try {
            AchievementUpdateResult r = mgr.unlock(p.getUniqueId(),
                    new AchievementKey(AchievementCatalog.GAME_ID, id));
            if (r != null && r.newlyUnlocked()) {
                log("成就解锁：" + p.getName() + " -> " + id);
                return true;
            }
        } catch (RuntimeException ex) {
            if (reportedFailures.add(id)) {
                log("解锁成就失败 " + id + "：" + ex.getMessage());
            }
        }
        return false;
    }

    /**
     * 更新「赌神」的计数进度。
     *
     * <p>用 `counter` 而不是 `statistic`：MGC 的 `statistic` 成就只能由
     * `GameStatisticsManager` 自动累计，我们不想为了一个成就去改 MGC 的统计事件；
     * `counter` 的进度由本类显式写入，同样持久化、同样在达到目标时自动解锁。
     */
    private static void updateWins(Player p, int wins) {
        if (p == null || !p.isOnline() || wins < 0) {
            return;
        }
        AchievementManager mgr = manager();
        if (mgr == null) {
            return;
        }
        try {
            AchievementUpdateResult r = mgr.updateProgress(p.getUniqueId(),
                    new AchievementKey(AchievementCatalog.GAME_ID, AchievementCatalog.WINS_100),
                    Math.min(AchievementCatalog.WINS_TARGET, wins));
            if (r != null && r.newlyUnlocked()) {
                log("成就解锁：" + p.getName() + " -> " + AchievementCatalog.WINS_100);
            }
        } catch (RuntimeException ex) {
            if (reportedFailures.add(AchievementCatalog.WINS_100)) {
                log("更新「赌神」进度失败：" + ex.getMessage());
            }
        }
    }

    // ================================================================ 挂点

    /**
     * 资金模式一次结算之后。
     *
     * <p>负责两个成就：
     * <ul>
     *   <li><b>土块！</b>——结算后资金 ≥ 本配置的理论上限；</li>
     *   <li><b>老实赚钱</b>——ALL IN 输光 +1 / ALL IN 获胜清零。</li>
     * </ul>
     */
    public static void onBasicSettled(GamePlayer gp, SettlementService.Result result, long maxFunds) {
        if (gp == null || gp.isBot()) {
            return;
        }
        Player p = onlinePlayer(gp);
        if (p == null) {
            return;
        }
        if (AchievementRules.reachedMaxFunds(gp.funds(), maxFunds)) {
            unlock(p, AchievementCatalog.BASIC_MAX_FUNDS);
        }
        if (gp.bet() == null || !gp.bet().isAllIn()) {
            return;
        }
        boolean won = result != null && result.won();
        InfernalGuessPlayerData data = dataOf(gp.uuid());
        if (data == null) {
            return;
        }
        int before = data.basicAllInBustStreak;
        int after = AchievementRules.bustStreakAfter(before, true, won);
        if (after != before) {
            data.basicAllInBustStreak = after;
            data.setModified(true);
        }
        if (AchievementRules.bustStreakReached(after, 10)) {
            unlock(p, AchievementCatalog.BASIC_ALLIN_BUST_10);
        }
    }

    /**
     * 对决模式一轮的胜负刚决定（结算前调用）。
     *
     * <p>「豪情在天」：自己押的边获胜，且其他**所有存活参与者**（含人机）都押了另一边。
     */
    public static void onUltimateRound(List<GamePlayer> players, BattleSide winner) {
        if (players == null || players.isEmpty() || winner == null) {
            return;
        }
        for (GamePlayer gp : players) {
            if (gp == null || gp.isBot() || !gp.isAlive() || gp.bet() == null || gp.bet().watch()) {
                continue;
            }
            if (gp.bet().side() != winner) {
                continue;
            }
            List<BattleSide> others = new ArrayList<>();
            for (GamePlayer other : players) {
                if (other == null || other.uuid().equals(gp.uuid()) || !other.isAlive()) {
                    continue;
                }
                others.add(other.bet() == null || other.bet().watch()
                        ? null : other.bet().side());
            }
            if (!AchievementRules.contrarianWin(gp.bet().side(), winner, others)) {
                continue;
            }
            Player p = onlinePlayer(gp);
            if (p != null) {
                unlock(p, AchievementCatalog.ULTIMATE_CONTRARIAN_WIN);
            }
        }
    }

    /**
     * 回合推进到第 {@code round} 轮时（对决模式）。
     *
     * <p>「高端局」：第 11 轮开始时仍存活。只在对决模式、且回合号 ≥ 11 时判定；
     * 提前在某轮结束的对局（只剩一人）不会走到第 11 轮，也就不发。
     */
    public static void onUltimateReachedRound(List<GamePlayer> players, int round) {
        if (players == null || players.isEmpty()) {
            return;
        }
        for (GamePlayer gp : players) {
            if (gp == null || gp.isBot() || !gp.isAlive()) {
                continue;
            }
            if (!AchievementRules.survivedToRound11(GameMode.ULTIMATE, round, true)) {
                continue;
            }
            Player p = onlinePlayer(gp);
            if (p != null) {
                unlock(p, AchievementCatalog.ULTIMATE_ROUND_11);
            }
        }
    }

    /**
     * 整局正常结束（`RoundManager.finish()`，此时战绩已写）。
     *
     * <p>负责：
     * <ul>
     *   <li><b>！？观者？！</b>——资金模式胜者且整局只观望；</li>
     *   <li><b>赌神</b>——`basicWins + ultimateWins` 写入 counter 进度；</li>
     *   <li><b>土块！</b>——兜底（正常已在结算时触发）。</li>
     * </ul>
     */
    public static void onMatchFinished(GameMode mode, List<GamePlayer> players,
                                       List<GamePlayer> winners, long maxFunds) {
        if (mode == null || players == null || players.isEmpty()) {
            return;
        }
        Set<UUID> winnerIds = ConcurrentHashMap.newKeySet();
        if (winners != null) {
            for (GamePlayer w : winners) {
                if (w != null) {
                    winnerIds.add(w.uuid());
                }
            }
        }
        boolean basic = mode == GameMode.BASIC;
        for (GamePlayer gp : players) {
            if (gp == null || gp.isBot()) {
                continue;
            }
            Player p = onlinePlayer(gp);
            if (p == null) {
                continue;
            }
            boolean won = winnerIds.contains(gp.uuid());
            if (AchievementRules.watchOnlyWin(basic, won, gp.onlyWatched())) {
                unlock(p, AchievementCatalog.BASIC_WATCH_ONLY_WIN);
            }
            if (basic && AchievementRules.reachedMaxFunds(gp.funds(), maxFunds)) {
                unlock(p, AchievementCatalog.BASIC_MAX_FUNDS);
            }
            if (won) {
                InfernalGuessPlayerData data = dataOf(gp.uuid());
                if (data != null) {
                    updateWins(p, data.basicWins + data.ultimateWins);
                }
            }
        }
    }

    /** 把玩家档案里的历史胜场同步到「赌神」的 counter 进度（幂等）。 */
    public static void syncWins(Player p) {
        InfernalGuessPlayerData data = p == null ? null : dataOf(p.getUniqueId());
        if (data != null) {
            updateWins(p, data.basicWins + data.ultimateWins);
        }
    }

    // ================================================================ 取值 helper

    private static Player onlinePlayer(GamePlayer gp) {
        if (gp == null || gp.isBot()) {
            return null;
        }
        Player p = Bukkit.getPlayer(gp.uuid());
        return p != null && p.isOnline() ? p : null;
    }

    /** 读本插件的玩家档案；读不到时返回 {@code null}（成就失败不影响对局）。 */
    private static InfernalGuessPlayerData dataOf(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        try {
            return MCZJUGameCore.getPlayerDataManager()
                    .getPlayerData(uuid.toString(), InfernalGuessPlayerData.class);
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static void log(String msg) {
        InfernalGuessPlugin p = plugin;
        if (p != null) {
            p.getLogger().info("[IG] " + msg);
        } else {
            LOG.info("[IG] " + msg);
        }
    }
}
