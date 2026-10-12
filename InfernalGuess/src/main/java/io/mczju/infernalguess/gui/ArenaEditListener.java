package io.mczju.infernalguess.gui;

import io.mczju.infernalguess.InfernalGuessPlugin;
import io.mczju.infernalguess.config.ArenaConfig;
import io.mczju.infernalguess.config.DisplayConfig;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.io.IOException;
import java.util.Map;

/**
 * 竞技场调试 UI 的点击处理。
 *
 * <p>交互约定（与 {@link ArenaEditMenu} 的文档一致）：
 * <ul>
 *   <li><b>左键</b>点位/落点格 = 用「打开 GUI 时所在位置」**绑定**</li>
 *   <li><b>右键</b>点位/落点格 = **传送**过去查看</li>
 * </ul>
 */
public final class ArenaEditListener implements Listener {

    private final InfernalGuessPlugin plugin;

    public ArenaEditListener(InfernalGuessPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ArenaEditMenu menu)) {
            return;
        }
        event.setCancelled(true); // 任何点击都不许改动这个界面

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= ArenaEditMenu.SIZE) {
            return; // 点到玩家背包区域：已取消，忽略
        }
        boolean right = event.isRightClick();
        String arena = menu.arenaName();

        int[] point = ArenaEditMenu.pointAt(slot);
        if (point != null) {
            boolean leftSide = point[0] == 1;
            int index = point[1];
            if (right) {
                teleportToPoint(player, arena, leftSide, index);
            } else {
                bindPoint(player, menu, arena, leftSide, index);
            }
            return;
        }

        switch (slot) {
            case ArenaEditMenu.SLOT_PLAYER_SPAWN -> {
                if (right) {
                    teleportToSpawn(player, arena, true);
                } else {
                    bindSpawn(player, menu, arena, true);
                }
            }
            case ArenaEditMenu.SLOT_SPECTATE_SPAWN -> {
                if (right) {
                    teleportToSpawn(player, arena, false);
                } else {
                    bindSpawn(player, menu, arena, false);
                }
            }
            case ArenaEditMenu.SLOT_DELETE -> {
                plugin.arenaConfig().delete(arena);
                saveQuietly(player);
                player.closeInventory();
                msg(player, "debug.arena-deleted", Map.of("name", arena));
            }
            case ArenaEditMenu.SLOT_SAVE -> {
                saveQuietly(player);
                menu.render();
            }
            case ArenaEditMenu.SLOT_CLOSE -> player.closeInventory();
            default -> { /* 填充格，什么都不做 */ }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ArenaEditMenu) {
            event.setCancelled(true);
        }
    }

    // ---------------- 动作 ----------------

    private void bindSpawn(Player player, ArenaEditMenu menu, String arena, boolean isPlayerSpawn) {
        Location loc = menu.openLocation();
        ArenaConfig.Spawn spawn = new ArenaConfig.Spawn(
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        boolean ok = isPlayerSpawn
                ? plugin.arenaConfig().setPlayerSpawn(arena, spawn)
                : plugin.arenaConfig().setSpectateSpawn(arena, spawn);
        if (!ok) {
            msg(player, "debug.arena-missing", Map.of("name", arena));
            return;
        }
        saveQuietly(player);
        menu.render();
        msg(player, "debug.spawn-set", Map.of(
                "kind", isPlayerSpawn ? "玩家传送点" : "观战席",
                "loc", String.format("%.1f, %.1f, %.1f", loc.getX(), loc.getY(), loc.getZ())));
    }

    private void bindPoint(Player player, ArenaEditMenu menu, String arena,
                           boolean leftSide, int index) {
        Location loc = menu.openLocation();
        boolean ok = plugin.arenaConfig().setPoint(arena, leftSide, index,
                new ArenaConfig.Point(loc.getX(), loc.getY(), loc.getZ()));
        if (!ok) {
            msg(player, "debug.arena-missing", Map.of("name", arena));
            return;
        }
        saveQuietly(player);
        menu.render();
        msg(player, "debug.point-set", Map.of(
                "side", leftSide ? "左" : "右",
                "point", String.valueOf(index),
                "loc", String.format("%.1f, %.1f, %.1f", loc.getX(), loc.getY(), loc.getZ())));
    }

    private void teleportToSpawn(Player player, String arena, boolean isPlayerSpawn) {
        World world = worldOf(player, arena);
        if (world == null) {
            return;
        }
        ArenaConfig.Arena a = plugin.arenaConfig().get(arena).orElse(null);
        if (a == null) {
            msg(player, "debug.arena-missing", Map.of("name", arena));
            return;
        }
        ArenaConfig.Spawn s = isPlayerSpawn ? a.playerSpawn() : a.spectateSpawn();
        if (s == null) {
            msg(player, "debug.arena-missing", Map.of("name", isPlayerSpawn ? "player-spawn" : "spectate-spawn"));
            return;
        }
        player.teleport(s.toLocation(world));
        player.closeInventory();
    }

    private void teleportToPoint(Player player, String arena, boolean leftSide, int index) {
        World world = worldOf(player, arena);
        if (world == null) {
            return;
        }
        ArenaConfig.Arena a = plugin.arenaConfig().get(arena).orElse(null);
        if (a == null) {
            msg(player, "debug.arena-missing", Map.of("name", arena));
            return;
        }
        ArenaConfig.Point p = (leftSide ? a.leftPoints() : a.rightPoints()).get(index);
        if (p == null) {
            msg(player, "debug.arena-missing", Map.of("name", (leftSide ? "左" : "右") + index + "（未绑定）"));
            return;
        }
        player.teleport(p.toLocation(world));
        player.closeInventory();
    }

    private World worldOf(Player player, String arena) {
        ArenaConfig.Arena a = plugin.arenaConfig().get(arena).orElse(null);
        if (a == null) {
            msg(player, "debug.arena-missing", Map.of("name", arena));
            return null;
        }
        World w = player.getServer().getWorld(a.world());
        if (w == null) {
            msg(player, "debug.arena-missing", Map.of("name", "世界 " + a.world() + "（不存在）"));
            return null;
        }
        return w;
    }

    private void saveQuietly(Player player) {
        try {
            plugin.arenaConfig().save(plugin);
        } catch (IOException e) {
            plugin.getLogger().severe("保存 arenas.yml 失败：" + e.getMessage());
            msg(player, "reload-fail", Map.of());
        }
    }

    private void msg(Player player, String key, Map<String, String> ph) {
        DisplayConfig display = plugin.displayConfig();
        String path = "debug." + key;
        if (!display.hasMessage(path)) {
            path = key;
        }
        if (display.hasMessage(path)) {
            player.sendMessage(display.render(path, ph));
        } else {
            player.sendMessage(net.kyori.adventure.text.Component.text("[IG] " + key + " " + ph));
        }
    }
}
