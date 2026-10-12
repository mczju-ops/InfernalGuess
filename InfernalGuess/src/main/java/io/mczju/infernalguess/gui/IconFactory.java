package io.mczju.infernalguess.gui;

import io.mczju.infernalguess.config.DisplayConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 由 {@code display.yml} 的 {@code icons} 段构造 {@link ItemStack}。
 *
 * <p>所有 GUI（竞技场调试、下注选边/选金额）都走这里，因此**没有一个硬编码材质或文案**。
 */
public final class IconFactory {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private IconFactory() {
    }

    /**
     * @param path 相对 {@code icons} 的路径，如 {@code "side.left"} / {@code "amount.all-in"}
     * @param ph   占位符（键不带花括号）
     */
    public static ItemStack make(DisplayConfig display, String path, Map<String, String> ph) {
        return make(display, path, ph, List.of());
    }

    /**
     * 同上，但可在配置的 lore **之后追加动态行**。
     *
     * <p>用于把「本回合阵容 + 词条」这类运行时才知道的内容挂到按钮描述里 ——
     * 静态文案仍然全部来自 {@code display.yml}。
     */
    public static ItemStack make(DisplayConfig display, String path, Map<String, String> ph,
                                 List<Component> extraLore) {
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
        if (extraLore != null) {
            lore.addAll(extraLore);
        }
        meta.lore(lore);
        if (spec.glow()) {
            meta.setEnchantmentGlintOverride(true);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /** 纯填充格。 */
    public static ItemStack filler(DisplayConfig display) {
        return make(display, "common.filler", Map.of());
    }

    /**
     * 用**运行时算出来的材质**构造图标。
     *
     * <h2>为什么需要它</h2>
     * {@link #make(DisplayConfig, String, Map)} 的材质来自 {@code display.yml} 的
     * {@code icons} 段（按路径取）。但下注界面要「**每只怪一个图标**」，
     * 材质来自 {@code display.yml} 的 {@code mob-items}（生物 id → 代表物品），
     * 是**按怪物 id 运行时查出来的**，没法用路径表达。
     *
     * <p>注意：这里只接管**材质**；**名称与 lore 仍全部来自调用方传入的
     * display.yml 渲染结果**，所以「没有一个硬编码文案」这条原则不破。
     */
    public static ItemStack makeWithMaterial(org.bukkit.Material material,
                                             Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material == null
                ? org.bukkit.Material.STONE : material);
        ItemMeta meta = stack.getItemMeta();
        if (name != null) {
            meta.displayName(name);
        }
        if (lore != null && !lore.isEmpty()) {
            meta.lore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    static String replace(String raw, Map<String, String> ph) {
        String s = raw;
        for (Map.Entry<String, String> e : ph.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return s;
    }
}
