package io.mczju.infernalguess.betting;

import io.mczju.infernalguess.battle.BattleSide;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * {@link BetMenu} 的点击处理。
 *
 * <p>全部点击都 `setCancelled(true)`，玩家无法把图标拿走或拖入物品。
 */
public final class BetMenuListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof BetMenu menu)) {
            return;
        }
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player) || menu.player() != player) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= BetMenu.SIZE) {
            return; // 点到玩家背包：已取消
        }

        if (menu.step() == BetMenu.Step.SIDE) {
            switch (slot) {
                case BetMenu.SLOT_LEFT -> menu.chooseSide(BattleSide.LEFT);
                case BetMenu.SLOT_RIGHT -> menu.chooseSide(BattleSide.RIGHT);
                case BetMenu.SLOT_WATCH -> menu.watch();
                default -> { /* 填充格 / 信息格 */ }
            }
        } else {
            switch (slot) {
                case BetMenu.SLOT_25 -> menu.chooseAmount(0.25);
                case BetMenu.SLOT_50 -> menu.chooseAmount(0.50);
                case BetMenu.SLOT_ALL_IN -> menu.chooseAmount(1.0);
                case BetMenu.SLOT_BACK -> menu.back();
                default -> { }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof BetMenu) {
            event.setCancelled(true);
        }
    }
}
