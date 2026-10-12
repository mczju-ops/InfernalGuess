package io.mczju.infernalguess.gui;

import io.mczju.infernalguess.InfernalGuessPlugin;
import io.mczju.infernalguess.config.ArenaConfig;
import io.mczju.infernalguess.config.DisplayConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 竞技场图形化调试 UI。
 *
 * <p>用户第 6 条要求「修改名称的调试 UI，以及绑定竞技场内的玩家传送点位和生物召唤点位」。
 *
 * <h2>交互设计（关键：GUI 打开时玩家不在点位上）</h2>
 * <ul>
 *   <li>打开 GUI 时**记录玩家当时的位置**（{@link #openLocation()}）</li>
 *   <li><b>左键</b>某个点位格 → 用「打开时的位置」**绑定**该点位</li>
 *   <li><b>右键</b>某个点位格 → **传送**过去（便于查看/调整）</li>
 * </ul>
 * 这样玩家可以「站在目标位置 → 打开 GUI → 左键绑定」，不需要在 GUI 里输入坐标。
 *
 * <p>用原版 {@link Inventory} 实现而非 MGC 的 {@code Menu}：
 * 本 UI 是纯管理工具，与对局状态无关，避免为此耦合 MGC 的菜单体系。
 */
public final class ArenaEditMenu implements InventoryHolder {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static final int SIZE = 27;

    // 第一行
    public static final int SLOT_PLAYER_SPAWN = 0;
    public static final int SLOT_SPECTATE_SPAWN = 1;
    public static final int SLOT_INFO = 4;
    // 第二行：左 1/2/3 + 右 1/2/3
    public static final int SLOT_LEFT_1 = 9;
    public static final int SLOT_LEFT_2 = 10;
    public static final int SLOT_LEFT_3 = 11;
    public static final int SLOT_RIGHT_1 = 15;
    public static final int SLOT_RIGHT_2 = 16;
    public static final int SLOT_RIGHT_3 = 17;
    // 第三行
    public static final int SLOT_DELETE = 18;
    public static final int SLOT_SAVE = 22;
    public static final int SLOT_CLOSE = 26;

    private final InfernalGuessPlugin plugin;
    private final Player player;
    private final String arenaName;
    private final Location openLocation;
    private final Inventory inventory;

    public ArenaEditMenu(InfernalGuessPlugin plugin, Player player, String arenaName) {
        this.plugin = plugin;
        this.player = player;
        this.arenaName = arenaName;
        this.openLocation = player.getLocation().clone();
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("竞技场编辑 · " + arenaName));
        render();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Player player() {
        return player;
    }

    public String arenaName() {
        return arenaName;
    }

    /** 打开 GUI 时玩家所在的位置 —— 绑定操作使用它。 */
    public Location openLocation() {
        return openLocation.clone();
    }

    public InfernalGuessPlugin plugin() {
        return plugin;
    }

    /** 重新渲染（点完一个按钮后刷新显示）。 */
    public void render() {
        DisplayConfig display = plugin.displayConfig();
        ArenaConfig arenas = plugin.arenaConfig();
        ArenaConfig.Arena arena = arenas.get(arenaName).orElse(null);

        ItemStack filler = icon(display, "common.filler", Map.of());
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, filler);
        }

        // 玩家传送点 / 观战席
        inventory.setItem(SLOT_PLAYER_SPAWN, icon(display, "debug.set-player-spawn",
                Map.of("loc", describe(arena == null ? null : arena.playerSpawn()))));
        inventory.setItem(SLOT_SPECTATE_SPAWN, icon(display, "debug.set-spectate-spawn",
                Map.of("loc", describe(arena == null ? null : arena.spectateSpawn()))));

        // 六个刷怪点
        setPointSlot(SLOT_LEFT_1, display, arena, true, 1);
        setPointSlot(SLOT_LEFT_2, display, arena, true, 2);
        setPointSlot(SLOT_LEFT_3, display, arena, true, 3);
        setPointSlot(SLOT_RIGHT_1, display, arena, false, 1);
        setPointSlot(SLOT_RIGHT_2, display, arena, false, 2);
        setPointSlot(SLOT_RIGHT_3, display, arena, false, 3);

        // 信息
        inventory.setItem(SLOT_INFO, icon(display, "common.info", Map.of(
                "left", String.valueOf(arena == null ? 0 : arena.leftPoints().size()),
                "right", String.valueOf(arena == null ? 0 : arena.rightPoints().size()),
                "left_weight", arena == null ? "-" : arena.incompleteness().isEmpty() ? "完整" : arena.incompleteness(),
                "right_weight", "世界: " + (arena == null ? "-" : arena.world()))));

        // 删除 / 保存 / 关闭
        inventory.setItem(SLOT_DELETE, icon(display, "debug.delete-arena", Map.of()));
        inventory.setItem(SLOT_SAVE, icon(display, "debug.save", Map.of()));
        inventory.setItem(SLOT_CLOSE, icon(display, "debug.cancel", Map.of()));
    }

    private void setPointSlot(int slot, DisplayConfig display, ArenaConfig.Arena arena,
                              boolean left, int index) {
        ArenaConfig.Point p = null;
        if (arena != null) {
            p = (left ? arena.leftPoints() : arena.rightPoints()).get(index);
        }
        String key = left ? "debug.bind-left-point" : "debug.bind-right-point";
        inventory.setItem(slot, icon(display, key,
                Map.of("n", String.valueOf(index), "loc", describe(p))));
    }

    private static String describe(Object point) {
        if (point == null) {
            return "未绑定";
        }
        if (point instanceof ArenaConfig.Point p) {
            return String.format("%.1f, %.1f, %.1f", p.x(), p.y(), p.z());
        }
        if (point instanceof ArenaConfig.Spawn s) {
            return String.format("%.1f, %.1f, %.1f", s.x(), s.y(), s.z());
        }
        return String.valueOf(point);
    }

    private static ItemStack icon(DisplayConfig display, String path, Map<String, String> ph) {
        DisplayConfig.IconSpec spec = display.icon(path);
        ItemStack stack = new ItemStack(spec.material());
        ItemMeta meta = stack.getItemMeta();
        if (!spec.name().isEmpty()) {
            meta.displayName(MM.deserialize(replace(spec.name(), ph)));
        }
        List<Component> lore = new ArrayList<>();
        for (String line : spec.lore()) {
            lore.add(MM.deserialize(replace(line, ph)));
        }
        meta.lore(lore);
        if (spec.glow()) {
            meta.setEnchantmentGlintOverride(true);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private static String replace(String raw, Map<String, String> ph) {
        String s = raw;
        for (Map.Entry<String, String> e : ph.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return s;
    }

    /** 供监听器使用：打开某个竞技场的编辑界面。 */
    public static void open(InfernalGuessPlugin plugin, Player player, String arenaName) {
        player.openInventory(new ArenaEditMenu(plugin, player, arenaName).getInventory());
    }

    /** 点位格 → {leftSide(1/0), index} 的解析；不是点位格返回 null。 */
    public static int[] pointAt(int slot) {
        return switch (slot) {
            case SLOT_LEFT_1 -> new int[]{1, 1};
            case SLOT_LEFT_2 -> new int[]{1, 2};
            case SLOT_LEFT_3 -> new int[]{1, 3};
            case SLOT_RIGHT_1 -> new int[]{0, 1};
            case SLOT_RIGHT_2 -> new int[]{0, 2};
            case SLOT_RIGHT_3 -> new int[]{0, 3};
            default -> null;
        };
    }
}
