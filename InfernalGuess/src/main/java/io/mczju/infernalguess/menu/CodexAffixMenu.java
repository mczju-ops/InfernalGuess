package io.mczju.infernalguess.menu;

import com.github.mczjuops.mczjugamecore.menu.Menu;
import com.github.mczjuops.mczjugamecore.menu.MenuFacade;
import io.mczju.infernalguess.InfernalGuessPlugin;
import io.mczju.infernalguess.codex.CodexConfig;
import io.mczju.infernalguess.codex.CodexEntries;
import io.mczju.infernalguess.codex.CodexLayout;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * 词条图鉴（5×9）。版式照 Maggoteers：第 2–4 行第 2–8 格摆词条（21 格），
 * 第 5 行第 2/8 = 上一页/下一页、第 9 = 返回。
 *
 * <p>词条**全部可见**（Maggoteers 的词条图鉴也没有「未解锁」态）；
 * 29 条 → 2 页。
 */
public class CodexAffixMenu extends Menu {

    public CodexAffixMenu(Player player, Object... args) {
        super(player, args);
    }

    @Override
    protected String getTitle() {
        InfernalGuessPlugin plugin = InfernalGuessPlugin.getInstance();
        return plugin == null ? "词条图鉴" : plugin.displayConfig().raw("codex.affix-title");
    }

    @Override
    protected int getRows() {
        return CodexLayout.SUB_ROWS;
    }

    @Override
    protected String getPermission() {
        return "infernalguess.play";
    }

    @Override
    protected void setup() {
        Player p = player.player();
        InfernalGuessPlugin plugin = InfernalGuessPlugin.getInstance();
        for (int slot = 0; slot < CodexLayout.SUB_ROWS * CodexLayout.COLUMNS; slot++) {
            setSlot(slot, CodexIcons.pane());
        }
        if (plugin == null) {
            return;
        }

        List<CodexEntries.Affix> all = CodexEntries.affixes(
                CodexConfig.get(), plugin.mobGroupConfig(),
                id -> plugin.affixRegistry().displayName(id));
        int pages = CodexEntries.pageCount(all.size(), CodexLayout.AFFIX_PAGE_SIZE);
        int page = CodexEntries.clampPage(pageArg(args), pages);
        List<CodexEntries.Affix> entries = CodexEntries.slice(
                all, page, CodexLayout.AFFIX_PAGE_SIZE);

        for (int i = 0; i < entries.size() && i < CodexLayout.AFFIX_SLOTS.length; i++) {
            setSlot(CodexLayout.AFFIX_SLOTS[i], CodexIcons.affix(entries.get(i)));
        }

        if (pages > 1) {
            if (page > 0) {
                setSlot(CodexLayout.PAGE_PREV, pageButton(plugin, "codex.page-prev"),
                        (c, e) -> open(p, page - 1));
            }
            if (page < pages - 1) {
                setSlot(CodexLayout.PAGE_NEXT, pageButton(plugin, "codex.page-next"),
                        (c, e) -> open(p, page + 1));
            }
            setSlot(40, CodexIcons.button(Material.PAPER,
                    plugin.displayConfig().render("codex.page-info",
                            Map.of("page", String.valueOf(page + 1),
                                    "pages", String.valueOf(pages)))));
        }

        setSlot(CodexLayout.SUB_BACK, CodexIcons.button(Material.RED_CONCRETE,
                        plugin.displayConfig().render("codex.back-name", Map.of()),
                        List.of(plugin.displayConfig().render("codex.back-lore", Map.of()))),
                (c, e) -> openMain(p));
    }

    private static org.bukkit.inventory.ItemStack pageButton(InfernalGuessPlugin plugin, String key) {
        return CodexIcons.button(Material.ARROW,
                plugin.displayConfig().render(key, Map.of()));
    }

    private static int pageArg(Object[] args) {
        if (args != null && args.length > 0 && args[0] instanceof Number n) {
            return Math.max(0, n.intValue());
        }
        return 0;
    }

    private static void open(Player p, int page) {
        try {
            MenuFacade.open("infernalguess-codex-affix", p, page);
        } catch (RuntimeException ex) {
            p.sendMessage(Component.text("图鉴翻页失败：" + ex.getMessage(), NamedTextColor.RED));
        }
    }

    private static void openMain(Player p) {
        try {
            MenuFacade.open("infernalguess-codex", p);
        } catch (RuntimeException ex) {
            p.sendMessage(Component.text("返回图鉴失败：" + ex.getMessage(), NamedTextColor.RED));
        }
    }
}
