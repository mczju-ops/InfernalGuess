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
            try {
                plug.menuItemService().take(p);
                plug.scoreboardService().clear(p);
            } catch (Throwable t) {
                plug.getLogger().warning("[IG] 回收 " + player.getName() + " 的道具/记分板失败：" + t);
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
    }
}
