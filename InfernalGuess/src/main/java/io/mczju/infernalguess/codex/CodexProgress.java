package io.mczju.infernalguess.codex;

import com.github.mczjuops.mczjugamecore.MCZJUGameCore;
import io.mczju.infernalguess.InfernalGuessPlugin;
import io.mczju.infernalguess.game.GamePlayer;
import io.mczju.infernalguess.game.InfernalGuessPlayerData;
import io.mczju.infernalguess.run.RoundPlan;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 图鉴进度（跨局持久化）：**遭遇过哪些怪组**。
 *
 * <p>记账点只有一处 —— {@code RoundManager.nextRound()} 生成剧本后，
 * 把双方阵容里的全部怪组 id 写给本局所有真人（{@link #markPlanForAll}）。
 * 幂等、账号级；写在 {@link InfernalGuessPlayerData#metMobs} 里，由 MGC 的 JSON 档案落盘。
 *
 * <p>⚠️ 图鉴的**词条**不设「未解锁」态（与 Maggoteers 一致：词条图鉴全部可见），
 * 所以这里只有一本账。
 */
public final class CodexProgress {

    private CodexProgress() {
    }

    /** 读玩家档案；未装 MGC / 无档案时返回 {@code null}（图鉴仍能开，只是全部显示未遭遇）。 */
    public static InfernalGuessPlayerData dataOf(Player player) {
        if (player == null) {
            return null;
        }
        return dataOf(player.getUniqueId());
    }

    public static InfernalGuessPlayerData dataOf(UUID uuid) {
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

    /** 已遭遇的怪组 id 集合（防御 null/重复）。 */
    public static Set<String> metMobs(InfernalGuessPlayerData data) {
        if (data == null || data.metMobs == null || data.metMobs.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(new LinkedHashSet<>(data.metMobs));
    }

    /**
     * 本回合剧本里的全部怪组 id（双方去重，保持出现顺序）。
     *
     * <p>纯函数，便于单测：不碰 Bukkit，只读 {@link RoundPlan}。
     */
    public static Set<String> groupIdsOf(RoundPlan plan) {
        if (plan == null) {
            return Set.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        collect(plan.left(), out);
        collect(plan.right(), out);
        return out;
    }

    private static void collect(RoundPlan.SidePlan side, Set<String> out) {
        if (side == null) {
            return;
        }
        for (RoundPlan.RosterEntry e : side.entries()) {
            if (e != null && e.group() != null && e.group().id() != null) {
                out.add(e.group().id());
            }
        }
    }

    /**
     * 把本回合剧本里的怪组记给所有真人参与者。
     *
     * @return 本次新增的（玩家 × 怪组）条数；仅用于日志/调试
     */
    public static int markPlanForAll(InfernalGuessPlugin plugin, RoundPlan plan,
                                     List<GamePlayer> players) {
        if (plan == null || players == null || players.isEmpty()) {
            return 0;
        }
        Set<String> ids = groupIdsOf(plan);
        if (ids.isEmpty()) {
            return 0;
        }
        int added = 0;
        for (GamePlayer gp : players) {
            if (gp == null || gp.isBot()) {
                continue;   // 人机的 UUID 是每局现算的，记了只会污染档案
            }
            Player p = Bukkit.getPlayer(gp.uuid());
            if (p == null || !p.isOnline()) {
                continue;
            }
            InfernalGuessPlayerData data = dataOf(p);
            if (data == null) {
                continue;
            }
            added += data.markMetMobs(ids);
        }
        if (plugin != null && added > 0) {
            plugin.getLogger().fine("[IG] 图鉴：本轮为真人新增 " + added + " 条遭遇记录（"
                    + String.join("、", ids) + "）");
        }
        return added;
    }

    /** 清空某人的图鉴进度（`/ig debug codex reset` 用）。 */
    public static void reset(InfernalGuessPlayerData data) {
        if (data == null) {
            return;
        }
        data.metMobs = new ArrayList<>();
        data.setModified(true);
    }

    /** 供测试：把一批 id 记进档案（不碰 Bukkit）。 */
    public static int mark(InfernalGuessPlayerData data, Collection<String> ids) {
        return data == null ? 0 : data.markMetMobs(ids);
    }
}
