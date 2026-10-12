package io.mczju.infernalguess.item;

import io.mczju.infernalguess.InfernalGuessPlugin;
import io.mczju.infernalguess.betting.BetMenu;
import io.mczju.infernalguess.game.GamePlayer;
import io.mczju.infernalguess.game.RoundManager;
import io.mczju.infernalguess.game.RoundPhase;
import io.mczju.infernalguess.gui.ArenaEditMenu;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;

import java.util.Map;
import java.util.Optional;

/**
 * 下注器的行为监听。
 *
 * <ul>
 *   <li><b>右键</b>（**空气或方块都行**）打开下注界面</li>
 *   <li><b>防丢</b>：对局中禁止丢弃</li>
 *   <li><b>防入容器</b>：对局中直接**取消玩家与方块容器的互动**（含 Shift 快速移入）</li>
 *   <li><b>防副手切换</b>：避免用 F 键把它挪到副手后又被 {@code give()} 补一个</li>
 * </ul>
 */
public final class MenuItemListener implements Listener {

    private final InfernalGuessPlugin plugin;

    public MenuItemListener(InfernalGuessPlugin plugin) {
        this.plugin = plugin;
    }

    // ================= 右键打开 =================

    /**
     * 右键打开下注界面。
     *
     * <p>⚠️ 早期版本用 {@code ignoreCancelled = true} 且依赖 {@code event.getItem()} 判断，
     * 实测结果是**只有右键方块能开、右键空气开不了**。现在：
     * <ul>
     *   <li>**不设 {@code ignoreCancelled}** —— 不被其它插件/原版的取消吃掉</li>
     *   <li>判断依据换成 {@code player.getInventory().getItemInMainHand()}
     *       （右键空气时 {@code event.getItem()} 可能为 null）</li>
     *   <li>只排除副手，避免一次交互触发两次</li>
     * </ul>
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            return; // 副手留给主手那次处理，防止双触发
        }
        Player player = event.getPlayer();
        if (!MenuItemService.isBetItem(player.getInventory().getItemInMainHand())) {
            return;
        }
        event.setCancelled(true); // 顺带阻止对着箱子右键把箱子打开
        openMenu(player);
    }

    /** 打开下注界面：把「谁在主持这一局」交给 plugin 当前的对局。 */
    private void openMenu(Player player) {
        Optional<RoundManager> opt = plugin.activeRound();
        if (opt.isEmpty() || opt.get().phase() != RoundPhase.BETTING) {
            player.sendMessage(plugin.displayConfig().render("menu.betting-closed"));
            return;
        }
        RoundManager manager = opt.get();
        GamePlayer gamePlayer = manager.players().stream()
                .filter(p -> p.uuid().equals(player.getUniqueId()))
                .findFirst().orElse(null);
        if (gamePlayer == null) {
            player.sendMessage(plugin.displayConfig().render("menu.betting-closed"));
            return;
        }
        // ★ 已出局的玩家不再能下注 —— 给明确提示，而不是让他白点一通
        if (!gamePlayer.isAlive()) {
            player.sendMessage(plugin.displayConfig().render("menu.eliminated"));
            return;
        }
        long funds = gamePlayer.funds();
        var mode = manager.mode();
        long line = mode.usesKillLine() ? plugin.gameConfig().killLineForRound(manager.round()) : 0;
        BetMenu.open(plugin, player, (p, bet) -> {
            boolean ok = manager.placeBet(p.getUniqueId(), bet);
            if (ok) {
                if (bet.watch()) {
                    p.sendMessage(plugin.displayConfig().render("bet.watching"));
                } else if (!mode.usesFunds()) {
                    // ★ 终极模式**不提资金**（用户第二轮反馈：这个模式没有资金概念）
                    p.sendMessage(plugin.displayConfig().render("bet.placed-ultimate", Map.of(
                            "side", sideName(bet.side()))));
                } else {
                    p.sendMessage(plugin.displayConfig().render("bet.placed", Map.of(
                            "side", sideName(bet.side()),
                            "percent", bet.percentLabel(),
                            "stake", String.valueOf(bet.stake(funds, plugin.gameConfig().rounding())))));
                }
            }
            return ok;
        }, gamePlayer, line, mode, manager.currentPlan().orElse(null));
    }

    /** 「左 / 右」的中文名。 */
    private static String sideName(io.mczju.infernalguess.battle.BattleSide side) {
        return side == io.mczju.infernalguess.battle.BattleSide.LEFT ? "左" : "右";
    }

    // ================= 防丢 / 防换手 =================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.gameConfig().protectMenuItem()) {
            return;
        }
        if (MenuItemService.isBetItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.displayConfig().render("menu.drop-denied"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!plugin.gameConfig().protectMenuItem()) {
            return;
        }
        if (MenuItemService.isBetItem(event.getMainHandItem())
                || MenuItemService.isBetItem(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    // ================= 对局中禁止与方块容器互动 =================

    /**
     * 对局进行中**直接取消玩家打开任何方块容器**。
     *
     * <p>用户反馈：Shift + 左键可以绕过「防入容器」把下注器快速移进箱子。
     * 与其逐个拦点击，不如按用户建议**整块禁掉**：
     * 对局中玩家本来也不需要开箱子。
     *
     * <p>放行三种情况：自己的背包、本插件自己的界面（下注 / 竞技场调试）、非对局参与者。
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!isParticipant(player)) {
            return;
        }
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof Player) {
            return; // 自己的背包
        }
        if (holder instanceof BetMenu || holder instanceof ArenaEditMenu) {
            return; // 本插件自己的界面
        }
        event.setCancelled(true);
        player.sendMessage(plugin.displayConfig().render("menu.container-denied"));
    }

    /** 兜底：即使界面已经打开，也拦住把下注器挪进容器的点击（含 Shift 快速移动）。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!plugin.gameConfig().protectMenuItem()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        var top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (holder instanceof BetMenu || holder instanceof ArenaEditMenu
                || holder instanceof Player) {
            return; // 这两个界面自己会取消；玩家自己背包不拦
        }
        boolean movingOurItem = MenuItemService.isBetItem(event.getCurrentItem())
                || MenuItemService.isBetItem(event.getCursor());
        if (movingOurItem && event.getRawSlot() < top.getSize()) {
            event.setCancelled(true);
            player.sendMessage(plugin.displayConfig().render("menu.container-denied"));
        }
    }

    /** 该玩家是否属于当前进行中的对局。 */
    private boolean isParticipant(Player player) {
        return plugin.activeRound()
                .map(r -> r.players().stream()
                        .anyMatch(p -> p.uuid().equals(player.getUniqueId())))
                .orElse(false);
    }
}
