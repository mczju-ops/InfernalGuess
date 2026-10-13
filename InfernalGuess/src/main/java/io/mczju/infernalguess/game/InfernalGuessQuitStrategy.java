package io.mczju.infernalguess.game;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.player.strategy.AbstractPlayerQuitStrategy;
import com.github.mczjuops.mczjugamecore.player.strategy.PlayerQuitReason;
import io.mczju.infernalguess.InfernalGuessPlugin;
import org.bukkit.entity.Player;

/**
 * 玩家退出策略。
 *
 * <p>用户第 4 条：「玩家死亡后依然能继续观战或使用菜单道具退出」——
 * 因此**单人退出不终止整局**，只把该玩家从「还在参与」里摘出去。
 *
 * <p>⚠️ 实测反馈：玩家用 {@code /mgc leave} 退出后**右侧记分板还在**。
 * 原因是退出回调只打了日志，没有回收该玩家的道具与记分板。
 * 现在这里做三件事：
 * <ol>
 *   <li>回收下注器</li>
 *   <li>摘掉他的侧边栏记分板（恢复主计分板）</li>
 *   <li>把他标记为**已退出** —— 否则回合状态机会一直等他的下注、并把他算作存活</li>
 * </ol>
 */
public final class InfernalGuessQuitStrategy extends AbstractPlayerQuitStrategy {

    public InfernalGuessQuitStrategy(AbstractGame game) {
        super(game);
    }

    @Override
    public void onPlayerQuit(PlayerExt player, PlayerQuitReason reason) {
        InfernalGuessPlugin plug = InfernalGuessPlugin.getInstance();
        // ★ 关服流程里的安全护栏（与 InfernalGuessGame.skipCleanupWhileDisabling 同一目的）。
        //   实测调用链：MCZJUGameCore.onDisable → forceAbortGame → solveGameEnd
        //   → PlayerManager.removeAllPlayer(game) → **本回调**；
        //   而此时**我们的插件早已 disabled**，任何 getScheduler() 调用都会抛
        //   IllegalPluginAccessException: Plugin attempted to register task while disabled。
        //   关服时玩家反正要掉线，回收道具/记分板毫无意义 —— 直接跳过。
        if (plug == null || !plug.isEnabled()) {
            return;
        }
        plug.getLogger().info("[IG] 玩家退出：" + player.getName() + "（原因 " + reason + "）");

        Player p = player.player();
        if (p != null) {
            // 1) 回收局内道具与记分板
            try {
                plug.menuItemService().take(p);
                plug.scoreboardService().clear(p);
            } catch (Throwable t) {
                plug.getLogger().warning("[IG] 回收 " + player.getName() + " 的道具/记分板失败：" + t);
            }
            // 2) ★ 单人退出也要**送回房间配的回程点** —— 否则玩家会留在竞技场世界，
            //    一旦该世界被卸载就会被打回主世界 spawn（Maggoteers 踩过的同一个坑）。
            //    回退链：房间 return → 房间 wait → 入局前位置 → 主世界 spawn。
            try {
                org.bukkit.Location fallback = game instanceof InfernalGuessGame ig
                        ? ig.joinLocationOf(player.getUniqueId()) : null;
                InfernalGuessRoom.teleportReturn(game, p, fallback);
            } catch (Throwable t) {
                plug.getLogger().warning("[IG] 传送 " + player.getName() + " 回程失败：" + t);
            }
        }

        // 让回合状态机知道这个人不再参与
        try {
            plug.activeRound().ifPresent(r -> r.players().stream()
                    .filter(gp -> gp.uuid().equals(player.getUniqueId()))
                    .findFirst()
                    .ifPresent(gp -> {
                        gp.markExited();
                        plug.getLogger().info("[IG] " + player.getName()
                                + " 已标记为退出本局（不再计入存活、也不再阻塞下注）");
                    }));
        } catch (Throwable t) {
            plug.getLogger().warning("[IG] 标记退出状态失败：" + t);
        }

        // 3) ★★ 最后一名**真人**也走了 → 立即中止整局
        //    实测证据（E:\MCpaper\logs\latest.log）：
        //      08:06:02 /mgc leave → [IG] 玩家退出（COMMAND_QUIT）
        //      08:06:09 [IG] 第 1 回合开打。下注：（无）   ← 零参与者却继续开打
        //      08:06:24 [IG] 已无任何存活参与者，收尾      ← 白空转 22 秒
        //    这期间房间一直是 IN_GAME → 玩家再 /mgc join 会被拒
        //    （用户原话：对局不结束空转、玩家无法使用 mgc 大厅工具）。
        //    MGC 自己只在 WAITING 阶段处理「人跑光了」，RUNNING 阶段什么都不做。
        try {
            if (game instanceof InfernalGuessGame ig && shouldAbortGame(ig.getPlayers().size())) {
                ig.abortIfNoPlayersLeft("最后一名玩家离开了对局");
            }
        } catch (Throwable t) {
            plug.getLogger().warning("[IG] 中止空对局失败：" + t);
        }
    }

    /**
     * 玩家离开后是否应当**立即中止整局**。
     *
     * <p>规则同时守住两条用户要求：
     * <ol>
     *   <li><b>用户第 4 条</b>：单人退出**不终止整局** —— 只要还有真人留着就继续打，
     *       走掉的那个人只被摘掉自己（{@link GamePlayer#markExited()}）</li>
     *   <li><b>本次裁定（2026-10-13）</b>：一个真人都不剩 → **立即中止**，
     *       绝不让场地与房间空转</li>
     * </ol>
     *
     * <p>抽成静态纯函数是为了能离线单测（{@code QuitAbortTest}）：若有人把它
     * 改成 {@code <= 1}（「只剩一人就结束」），测试会立刻变红。
     *
     * @param remainingRealPlayers 仍留在对局内的**真人**数量
     *                             （人机不算 —— 人机不是 MGC 玩家，见 {@code RoundManager}）
     */
    static boolean shouldAbortGame(int remainingRealPlayers) {
        return remainingRealPlayers <= 0;
    }
}
