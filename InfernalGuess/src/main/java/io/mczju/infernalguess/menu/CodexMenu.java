package io.mczju.infernalguess.menu;

import com.github.mczjuops.mczjugamecore.menu.Menu;
import com.github.mczjuops.mczjugamecore.menu.MenuFacade;
import io.mczju.infernalguess.InfernalGuessPlugin;
import io.mczju.infernalguess.affix.AffixCatalog;
import io.mczju.infernalguess.codex.CodexLayout;
import io.mczju.infernalguess.codex.CodexProgress;
import io.mczju.infernalguess.game.InfernalGuessPlayerData;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图鉴一级菜单（3×9）。
 *
 * <p>版式照 Maggoteers：两本图鉴放在中间对称的 11 / 15 格，右下角 26 = 退出。
 * 只做 Boss（怪物）与词条两本 —— 用户明确要求「只包括 boss 图鉴和词条图鉴」。
 */
public class CodexMenu extends Menu {

    public CodexMenu(Player player, Object... args) {
        super(player, args);
    }

    @Override
    protected String getTitle() {
        InfernalGuessPlugin plugin = InfernalGuessPlugin.getInstance();
        return plugin == null ? "图鉴" : plugin.displayConfig().raw("codex.title");
    }

    @Override
    protected int getRows() {
        return CodexLayout.MAIN_ROWS;
    }

    @Override
    protected String getPermission() {
        return "infernalguess.play";
    }

    @Override
    protected void setup() {
        Player p = player.player();
        InfernalGuessPlugin plugin = InfernalGuessPlugin.getInstance();
        for (int slot = 0; slot < CodexLayout.MAIN_ROWS * CodexLayout.COLUMNS; slot++) {
            setSlot(slot, CodexIcons.pane());
        }
        if (plugin == null) {
            return;
        }

        InfernalGuessPlayerData data = CodexProgress.dataOf(p);
        Set<String> met = CodexProgress.metMobs(data);
        int totalMobs = plugin.mobGroupConfig().size();
        int metMobs = 0;
        for (String id : plugin.mobGroupConfig().ids()) {
            if (met.contains(id)) {
                metMobs++;
            }
        }
        int totalAffixes = AffixCatalog.size();

        Component bossName = plugin.displayConfig().render("codex.boss-name",
                Map.of("got", String.valueOf(metMobs), "total", String.valueOf(totalMobs)));
        Component bossLore = plugin.displayConfig().render("codex.boss-lore",
                Map.of("got", String.valueOf(metMobs), "total", String.valueOf(totalMobs)));
        setSlot(CodexLayout.MAIN_BOSS, CodexIcons.button(
                Material.WITHER_SKELETON_SKULL, bossName, List.of(bossLore)),
                (c, e) -> open("infernalguess-codex-boss", p, 0));

        Component affixName = plugin.displayConfig().render("codex.affix-name",
                Map.of("total", String.valueOf(totalAffixes)));
        Component affixLore = plugin.displayConfig().render("codex.affix-lore",
                Map.of("total", String.valueOf(totalAffixes)));
        setSlot(CodexLayout.MAIN_AFFIX, CodexIcons.button(
                Material.ENCHANTED_BOOK, affixName, List.of(affixLore)),
                (c, e) -> open("infernalguess-codex-affix", p, 0));

        Component exitName = plugin.displayConfig().render("codex.exit-name", Map.of());
        Component exitLore = plugin.displayConfig().render("codex.exit-lore", Map.of());
        setSlot(CodexLayout.MAIN_EXIT, CodexIcons.button(
                Material.RED_CONCRETE, exitName, List.of(exitLore)),
                (c, e) -> p.closeInventory());
    }

    private static void open(String id, Player p, Object... args) {
        try {
            MenuFacade.open(id, p, args);
        } catch (RuntimeException ex) {
            p.sendMessage(Component.text("图鉴打开失败：" + ex.getMessage(),
                    net.kyori.adventure.text.format.NamedTextColor.RED));
        }
    }
}
