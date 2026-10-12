package io.mczju.infernalguess.game;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.player.strategy.AbstractPlayerDeathStrategy;
import io.mczju.infernalguess.InfernalGuessPlugin;
import io.mczju.infernalguess.config.ArenaConfig;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * 玩家死亡策略。
 *
 * <p>本游戏的玩家是**下注者/观战者**，正常情况下不下场战斗，因此死亡只可能是意外
 * （掉出世界、被残留生物打到等）。
 *
 * <h2>★ 实测反馈：玩家死亡后「卡在房间里」</h2>
 * 用户反馈：「似乎玩家在死亡时没有自动退出房间，房间也仍然被占用」。
 *
 * <p>原先这里只做了「清掉落 + 打日志」，注释里还留着
 * 「阶段 6 接入：传送回观战席」的 **TODO 从未实现** ——
 * 结果玩家死亡后就**一直躺在那儿**：既没有复活、也没被送回观战席，
 * 在房主/其他玩家看来就是「这个人还占在房间里不动」。
 *
 * <h2>现在的处理（对齐用户第 4 条「死亡后依然能继续观战」）</h2>
 * <ol>
 *   <li>清空掉落（防止把下注器掉在地上）</li>
 *   <li>★ **立即复活**并传回竞技场的观战席（{@code spectateSpawn}）</li>
 *   <li>★ 顺带把他的**游戏模式设为生存**并**清空负面状态** ——
 *       否则若他之前被切到旁观/创造（或带着凋零、中毒），
 *       复活后会继续掉血、反复死亡，看起来还是「赖在房间里」</li>
 *   <li>**不因此终止对局**（用户第 4 条：死亡后仍可继续观战）</li>
 * </ol>
 *
 * <p>⚠️ 复活必须**延迟一个 tick**：Bukkit 不允许在 {@code PlayerDeathEvent}
 * 的处理中直接 {@code spigot().respawn()}，那样会与正在进行的死亡流程打架。
 */
public final class InfernalGuessDeathStrategy extends AbstractPlayerDeathStrategy {

    public InfernalGuessDeathStrategy(AbstractGame game) {
        super(game);
    }

    @Override
    public void onPlayerDeath(PlayerExt player, PlayerDeathEvent event) {
        InfernalGuessPlugin plug = InfernalGuessPlugin.getInstance();
        plug.getLogger().info("[IG] 玩家死亡：" + player.getName()
                + "（本游戏玩家不应参与战斗）→ 将复活并送回观战席");

        // 不让下注器掉一地
        event.getDrops().clear();
        event.setDroppedExp(0);

        Player p = player.player();
        if (p == null) {
            return;
        }
        // ★ 延迟一 tick 再复活：在 PlayerDeathEvent 里直接 respawn 会与死亡流程冲突
        plug.getServer().getScheduler().runTask(plug, () -> reviveToSpectate(plug, p));
    }

    /**
     * 复活并送回观战席。
     *
     * <p>送到哪儿：优先用**本局正在使用的竞技场**的观战点；
     * 拿不到就退回玩家当前所在世界的出生点（总比躺在原地强）。
     */
    private void reviveToSpectate(InfernalGuessPlugin plug, Player p) {
        if (p == null || !p.isOnline()) {
            return;
        }
        try {
            if (p.isDead()) {
                p.spigot().respawn();
            }
            // 清掉可能让他继续掉血的状态（否则复活后立刻又死，表现为「一直赖在房间」）
            try {
                p.setFireTicks(0);
                p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
            } catch (Throwable ignored) {
                // 清状态失败不影响主流程
            }
            if (p.getGameMode() == GameMode.SPECTATOR) {
                p.setGameMode(GameMode.SURVIVAL);
            }

            // 送回观战席
            var round = plug.activeRound().orElse(null);
            var arena = round == null ? null : round.arena();
            var world = arena == null ? null : ArenaConfig.resolveWorld(arena).orElse(null);
            if (arena != null && arena.spectateSpawn() != null && world != null) {
                p.teleport(arena.spectateSpawn().toLocation(world));
            } else {
                p.teleport(p.getWorld().getSpawnLocation());
            }
            p.sendMessage(plug.displayConfig().render("battle.revived-to-spectate",
                    java.util.Map.of()));
        } catch (Throwable t) {
            plug.getLogger().warning("[IG] 复活/传送 " + p.getName() + " 失败：" + t);
        }
    }
}
