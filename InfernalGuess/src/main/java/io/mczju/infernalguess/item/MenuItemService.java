package io.mczju.infernalguess.item;

import io.mczju.infernalguess.InfernalGuessPlugin;
import io.mczju.infernalguess.config.DisplayConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 「下注器」菜单道具。
 *
 * <h2>用户第 5 条</h2>
 * 「道具不依赖 ItemCreator，进服给一个，退服收回去。」
 *
 * <h2>实现要点</h2>
 * <ul>
 *   <li><b>不依赖 MCZJUItemCreator</b>：直接用 {@link ItemStack} + {@link ItemMeta} 构造</li>
 *   <li><b>识别靠 PDC</b>：{@code ig_kind=bet-menu}，因此不怕被改名 / 改描述 / 附魔</li>
 *   <li><b>回收精确</b>：{@link #take(Player)} 只删带 PDC 标记的格，不误删玩家自己的同类物品</li>
 *   <li>材质与文案全部来自 {@code display.yml}（{@code icons.menu.bet-item} / {@code messages.menu.*}）</li>
 * </ul>
 */
public final class MenuItemService {

    /** PDC 键：道具种类。 */
    public static final String KEY_KIND = "ig_kind";
    /** PDC 键：道具 id（预留给将来多种菜单道具）。 */
    public static final String KEY_ID = "ig_id";

    public static final String KIND_BET_MENU = "bet-menu";

    /** 快捷栏投放位置（第 9 格），避免占用第 1 格。 */
    private static final int HOTBAR_SLOT = 8;

    private final InfernalGuessPlugin plugin;

    public MenuItemService(InfernalGuessPlugin plugin) {
        this.plugin = plugin;
    }

    private NamespacedKey kindKey() {
        return new NamespacedKey(plugin, KEY_KIND);
    }

    private NamespacedKey idKey() {
        return new NamespacedKey(plugin, KEY_ID);
    }

    // ---------------- 构造与识别 ----------------

    /** 构造下注器（材质与文案取自 display.yml）。 */
    public ItemStack create() {
        DisplayConfig display = plugin.displayConfig();
        DisplayConfig.IconSpec spec = display.icon("menu.bet-item");
        ItemStack stack = new ItemStack(spec.material());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MiniMessage.miniMessage().deserialize(display.raw("menu.item-name")));
        meta.lore(display.raw("menu.item-lore").lines()
                .map(l -> (Component) MiniMessage.miniMessage().deserialize(l))
                .toList());
        meta.getPersistentDataContainer().set(kindKey(), PersistentDataType.STRING, KIND_BET_MENU);
        meta.getPersistentDataContainer().set(idKey(), PersistentDataType.STRING, KIND_BET_MENU);
        stack.setItemMeta(meta);
        return stack;
    }

    /** 判断一个物品是不是下注器（静态版，供事件监听器使用）。 */
    public static boolean isBetItem(ItemStack stack) {
        InfernalGuessPlugin p = InfernalGuessPlugin.getInstance();
        if (p == null || stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        String kind = meta.getPersistentDataContainer()
                .get(new NamespacedKey(p, KEY_KIND), PersistentDataType.STRING);
        return KIND_BET_MENU.equals(kind);
    }

    // ---------------- 发放与回收 ----------------

    /** 给玩家一个下注器（已持有则跳过）。 */
    public void give(Player player) {
        if (has(player)) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        ItemStack existing = inv.getItem(HOTBAR_SLOT);
        if (existing == null || existing.getType().isAir()) {
            inv.setItem(HOTBAR_SLOT, create());
        } else {
            inv.addItem(create());
        }
    }

    /** 玩家是否已持有下注器。 */
    public boolean has(Player player) {
        for (ItemStack s : player.getInventory().getContents()) {
            if (isBetItem(s)) {
                return true;
            }
        }
        return isBetItem(player.getInventory().getItemInOffHand());
    }

    /**
     * 回收下注器。
     *
     * <p>**只删带 PDC 标记的格**，不误删玩家自己的时钟或同类物品。
     *
     * @return 是否真的删掉了至少一个
     */
    public boolean take(Player player) {
        PlayerInventory inv = player.getInventory();
        boolean removed = false;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isBetItem(contents[i])) {
                inv.setItem(i, null);
                removed = true;
            }
        }
        if (isBetItem(inv.getItemInOffHand())) {
            inv.setItemInOffHand(null);
            removed = true;
        }
        return removed;
    }
}
