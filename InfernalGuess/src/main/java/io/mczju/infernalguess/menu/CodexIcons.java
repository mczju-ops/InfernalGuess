package io.mczju.infernalguess.menu;

import io.mczju.infernalguess.InfernalGuessPlugin;
import io.mczju.infernalguess.codex.CodexConfig;
import io.mczju.infernalguess.codex.CodexEntries;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 图鉴菜单的图标工厂：边框、按钮、怪物、词条。
 *
 * <p>与 Maggoteers 的 `CodexIcons` 同款设计：
 * <ul>
 *   <li>边框 = `GRAY_STAINED_GLASS_PANE`；</li>
 *   <li>未遭遇 = `GRAY_CONCRETE` + 「？？？」；</li>
 *   <li>已遭遇 = 代表物品 + 玩法/风味；</li>
 *   <li>MiniMessage 解析失败/未知标签 → 退回灰字并告警（图鉴不该因为写错一个标签打不开）。</li>
 * </ul>
 */
final class CodexIcons {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer PLAIN =
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
    private static final java.util.regex.Pattern STRIP_TAGS =
            java.util.regex.Pattern.compile("<[^>]*>");
    private static final Logger LOG = Logger.getLogger("InfernalGuess");

    private CodexIcons() {
    }

    // ---------------------------------------------------------------- 通用

    static ItemStack pane() {
        ItemStack s = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = s.getItemMeta();
        if (m != null) {
            m.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
            s.setItemMeta(m);
        }
        return s;
    }

    /** 单行按钮（名称 + 可选 lore 组件）。 */
    static ItemStack button(Material mat, Component name, List<Component> lore) {
        ItemStack s = new ItemStack(mat == null ? Material.PAPER : mat);
        ItemMeta m = s.getItemMeta();
        if (m == null) {
            return s;
        }
        m.displayName(name == null ? Component.empty() : name.decoration(TextDecoration.ITALIC, false));
        if (lore != null && !lore.isEmpty()) {
            List<Component> clean = new ArrayList<>();
            for (Component c : lore) {
                if (c != null) {
                    clean.add(c.decoration(TextDecoration.ITALIC, false));
                }
            }
            m.lore(clean);
        }
        s.setItemMeta(m);
        return s;
    }

    static ItemStack button(Material mat, Component name) {
        return button(mat, name, List.of());
    }

    /**
     * MiniMessage 解析；写坏了就退回灰字。
     *
     * <p>⚠️ MiniMessage **默认不报错**：不认识的标签（如 `<purple>`）会被原样输出成文字。
     * 所以解析完再看一眼纯文本里还有没有 `<xxx>` —— 有就说明配置写错了，打一条告警。
     */
    static Component line(String miniMessage) {
        if (miniMessage == null || miniMessage.isBlank()) {
            return Component.empty();
        }
        try {
            Component parsed = MM.deserialize(miniMessage).decoration(TextDecoration.ITALIC, false);
            String plain = PLAIN.serialize(parsed);
            if (plain.indexOf('<') >= 0 && plain.indexOf('>') > plain.indexOf('<')) {
                LOG.warning("图鉴文案里有 MiniMessage 不认识的标签（会被原样显示）：" + miniMessage);
            }
            return parsed;
        } catch (RuntimeException ex) {
            LOG.warning("图鉴文案解析失败，已按纯文本显示：" + miniMessage + " -> " + ex.getMessage());
            return Component.text(STRIP_TAGS.matcher(miniMessage).replaceAll(""), NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false);
        }
    }

    static Component plain(String text, NamedTextColor color) {
        return Component.text(text == null ? "" : text, color)
                .decoration(TextDecoration.ITALIC, false);
    }

    // ---------------------------------------------------------------- 怪物 / 词条

    static ItemStack monster(CodexEntries.Monster monster, boolean met) {
        InfernalGuessPlugin plugin = InfernalGuessPlugin.getInstance();
        if (!met) {
            String name = plugin == null ? "？？？"
                    : plugin.displayConfig().raw("codex.unseen-name");
            String lore = plugin == null ? "尚未在竞技场遭遇"
                    : plugin.displayConfig().raw("codex.unseen-lore");
            return button(Material.GRAY_CONCRETE, line(name), List.of(line(lore)));
        }
        Material icon = iconOf(monster.iconKey());
        ItemStack s = new ItemStack(icon);
        ItemMeta m = s.getItemMeta();
        if (m == null) {
            return s;
        }
        m.displayName(plain(monster.name(), NamedTextColor.WHITE));
        List<Component> lore = new ArrayList<>();
        for (String line : monster.gameplay()) {
            if (line != null && !line.isBlank()) {
                lore.add(line(line));
            }
        }
        if (monster.flavor() != null && !monster.flavor().isBlank()) {
            lore.add(line(monster.flavor()));
        }
        if (!lore.isEmpty()) {
            m.lore(lore);
        }
        s.setItemMeta(m);
        return s;
    }

    static ItemStack affix(CodexEntries.Affix affix) {
        ItemStack s = new ItemStack(iconOf(affix.iconKey()));
        ItemMeta m = s.getItemMeta();
        if (m == null) {
            return s;
        }
        m.displayName(line(affix.name()));
        List<Component> lore = new ArrayList<>();
        for (String line : affix.gameplay()) {
            if (line != null && !line.isBlank()) {
                lore.add(line(line));
            }
        }
        if (affix.flavor() != null && !affix.flavor().isBlank()) {
            lore.add(line(affix.flavor()));
        }
        if (!lore.isEmpty()) {
            m.lore(lore);
        }
        s.setItemMeta(m);
        return s;
    }

    /** 行标签：权重区间 + 本行数量/页码。 */
    static ItemStack rowLabel(String range, int count, int page, int pages) {
        InfernalGuessPlugin plugin = InfernalGuessPlugin.getInstance();
        String name;
        String lore;
        if (plugin == null) {
            name = "权重 " + range;
            lore = "本行 " + count + " 只 · 第 " + page + "/" + pages + " 页";
        } else {
            name = plugin.displayConfig().raw("codex.row-label").replace("{range}", range);
            lore = plugin.displayConfig().raw("codex.row-lore")
                    .replace("{count}", String.valueOf(count))
                    .replace("{page}", String.valueOf(page))
                    .replace("{pages}", String.valueOf(pages));
        }
        return button(Material.BOOK, line(name), List.of(line(lore)));
    }

    /** 材质名 → Material（解析失败回落 PAPER：图鉴不该因为写错一个图标就打不开）。 */
    static Material iconOf(String iconKey) {
        Material m = CodexConfig.materialQuiet(iconKey);
        return m == null ? Material.PAPER : m;
    }
}
