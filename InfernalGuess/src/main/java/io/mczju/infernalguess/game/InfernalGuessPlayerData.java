package io.mczju.infernalguess.game;

import com.github.mczjuops.mczjugamecore.player.data.JsonPlayerData;
import io.mczju.infernalguess.config.GameMode;

import java.util.ArrayList;
import java.util.List;

/**
 * 本游戏的玩家持久化档案（由 MGC 统一托管）。
 *
 * <p>落盘位置由 {@link JsonPlayerData#getFilePath()} 决定
 * （实测：{@code plugins/MCZJUGameCore/player_data/infernalguess/<uuid>.json}），
 * 我们只负责声明要持久化的**公开字段** —— MGC 通过反射读写它们。
 *
 * <p>⚠️ **改动后必须 {@code setModified(true)}**，否则不会落盘
 * （Maggoteers 的 CLAUDE.md 把这列为已知坑）。本类的写入统一走
 * {@link #recordMatch}，它一定会置位。
 *
 * <h2>★ 2026-10-13（1.0.1）：字段按**模式**拆开，供两个排行榜读取</h2>
 * 用户要的两个榜是分开的：
 * <ul>
 *   <li><b>对决模式战绩榜单</b>（{@code ultimateRuns/ultimateWins/ultimateBestStreak}）
 *       —— 按 {@code ultimateWins} 排名，行里显示胜场 / 总场次 / 最高连胜</li>
 *   <li><b>富豪榜</b>（{@code basicBestFunds}）—— 资金模式里**胜利时**达到过的最高资金</li>
 * </ul>
 * 所以不再保留旧版那套「不分模式」的 {@code runs/wins/bestFunds/totalFundsEarned}
 * —— 那四个字段此前**从未被写入过**（全仓库零调用点，dev-log 075 有实测记录），
 * 且现场 {@code player_data/infernalguess/} 目录并不存在，没有历史数据要迁移。
 */
public final class InfernalGuessPlayerData extends JsonPlayerData {

    // ---------------- 对决模式（ULTIMATE）战绩榜 ----------------

    /** 对决模式：参与过的局数（榜单里的「总场次」）。 */
    public int ultimateRuns;

    /** 对决模式：获胜次数（**榜单按这个字段排名**）。 */
    public int ultimateWins;

    /**
     * 对决模式：**当前**连胜。
     *
     * <p>它是 {@link #ultimateBestStreak} 的**状态**：每赢一场 +1、每输一场清零。
     * 必须持久化，否则每次重启都会把连胜打断。
     * 榜面上**不显示**它（用户 2026-10-13 选的是「历史最高连胜」）。
     */
    public int ultimateStreak;

    /** 对决模式：**历史最高连胜**（只涨不落）—— 榜单显示的就是它。 */
    public int ultimateBestStreak;

    // ---------------- 资金模式（BASIC）富豪榜 ----------------

    /** 资金模式：参与过的局数。 */
    public int basicRuns;

    /** 资金模式：获胜次数。 */
    public int basicWins;

    /**
     * 资金模式：**胜利时**达到过的最高资金（只统计赢下来的局）。
     *
     * <p>为什么不记「所有局的最高资金」：用户的原话是
     * 「记录玩家所达到过的**胜利时**最高资金数量」—— 输掉的局里那点残钱不算数。
     */
    public long basicBestFunds;

    // ---------------- 1.1 成就 / 图鉴 ----------------

    /**
     * ★ 成就「老实赚钱，很枯燥吧？」的**连续 ALL IN 输光次数**。
     *
     * <p>口径见 `docs/17` §1.3.2：
     * <ul>
     *   <li>资金模式 ALL IN 输光 → +1；</li>
     *   <li>资金模式 ALL IN 获胜 → 清零（这是「连续」的断点）；</li>
     *   <li>非 ALL IN 的下注不改变它。</li>
     * </ul>
     *
     * <p>必须**跨局持久化** —— 一局里 ALL IN 输一次就出局了，
     * 不可能在同一局内累计 10 次。
     */
    public int basicAllInBustStreak;

    /**
     * ★ 图鉴：**遭遇过**的怪组 id（账号级）。
     *
     * <p>记账点只有一处：每回合生成剧本后，把双方阵容里的全部怪组 id
     * 写给本局所有真人（见 `CodexProgress.markPlanForAll`）。
     * 幂等、跨局持久化；图鉴据此决定「已遭遇 / ？？？」。
     */
    public List<String> metMobs = new ArrayList<>();

    /** 已解锁的额外内容 id（预留，当前无用途）。 */
    public List<String> unlocks = new ArrayList<>();

    /**
     * 记一次资金模式 ALL IN 的结算结果（成就「老实赚钱」用）。
     *
     * @param won 本次 ALL IN 是否押中
     * @return 结算后的连续输光次数（ALL IN 获胜时为 0）
     */
    public int recordBasicAllInResult(boolean won) {
        if (won) {
            if (basicAllInBustStreak != 0) {
                basicAllInBustStreak = 0;
                setModified(true);
            }
            return 0;
        }
        basicAllInBustStreak++;
        setModified(true);
        return basicAllInBustStreak;
    }

    /**
     * 记一批「遭遇过」的怪组 id（幂等）。
     *
     * @return 本次新增的条数（0 = 全都见过，不落盘）
     */
    public int markMetMobs(java.util.Collection<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return 0;
        }
        if (metMobs == null) {   // 老档案/反序列化可能给 null
            metMobs = new ArrayList<>();
        }
        int added = 0;
        for (String id : groupIds) {
            if (id == null || id.isBlank() || metMobs.contains(id)) {
                continue;
            }
            metMobs.add(id);
            added++;
        }
        if (added > 0) {
            setModified(true);
        }
        return added;
    }

    /** 图鉴已收录的怪组数（防御 null/重复）。 */
    public int metMobCount() {
        return metMobs == null ? 0 : new java.util.LinkedHashSet<>(metMobs).size();
    }

    // ---------------- 写入封装（全部强制 setModified）----------------

    /**
     * 记一局的结果 —— **唯一的写入口**。
     *
     * <p>纯数据操作（不碰 Bukkit），因此可以离线单测：
     * 见 {@code InfernalGuessPlayerDataTest}。
     *
     * <p>⚠️ 只有**真人**才该调它：人机的 UUID 是每局现算的
     * （{@code SessionPlanner} 用 名字+竞技场 派生），记了只会污染榜单。
     *
     * @param mode        本局模式（{@link GameMode#ULTIMATE} 对决 / 其它 = 资金模式）
     * @param won         本局是否获胜
     * @param finalFunds  本局结束时的资金（只有资金模式且获胜时才会被记录）
     */
    public void recordMatch(GameMode mode, boolean won, long finalFunds) {
        if (mode == GameMode.ULTIMATE) {
            ultimateRuns++;
            ultimateStreak = won ? ultimateStreak + 1 : 0;
            if (won) {
                ultimateWins++;
                if (ultimateStreak > ultimateBestStreak) {
                    ultimateBestStreak = ultimateStreak;
                }
            }
        } else {
            basicRuns++;
            if (won) {
                basicWins++;
                if (finalFunds > basicBestFunds) {
                    basicBestFunds = finalFunds;
                }
            }
        }
        setModified(true);
    }
}
