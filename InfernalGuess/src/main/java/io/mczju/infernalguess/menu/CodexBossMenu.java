package io.mczju.infernalguess.menu;

import com.github.mczjuops.mczjugamecore.menu.Menu;
import com.github.mczjuops.mczjugamecore.menu.MenuFacade;
import io.mczju.infernalguess.InfernalGuessPlugin;
import io.mczju.infernalguess.codex.CodexConfig;
import io.mczju.infernalguess.codex.CodexEntries;
import io.mczju.infernalguess.codex.CodexLayout;
import io.mczju.infernalguess.codex.CodexProgress;
import io.mczju.infernalguess.game.InfernalGuessPlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Boss（怪物）图鉴（5×9）。
 *
 * <p>版式照 Maggoteers：第 1–4 行第 1 格是**权重区间**标签，第 2–9 格摆怪物；
 * 第 5 行第 2/8 格 = 上一页/下一页，第 9 格 = 返回。
 *
 * <p>为什么有翻页：Maggoteers 的 Boss 图鉴最多 4 层 × 8 个蛋，天然不超过 32；
 * 本插件一个大池里有 51 个怪组，不翻页就收不全。
 * 用户要求「所有会出现在竞技场的怪物都会被收录」，因此保留 5×9 版式、加页脚。
 */
public class CodexBossMenu extends Menu {

    public CodexBossMenu(Player player, Object... args) {
        super(player, args);
    }

    @Override
    protected String getTitle() {
        InfernalGuessPlugin plugin = InfernalGuessPlugin.getInstance();
        return plugin == null ? "Boss 图鉴" : plugin.displayConfig().raw("codex.boss-title");
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

        Map<String, String> icons = new LinkedHashMap<>();
        plugin.displayConfig().mobItems().forEach((id, mat) -> icons.put(id, mat.name()));
        List<CodexEntries.Monster> all = CodexEntries.monsters(
                plugin.mobGroupConfig(), CodexConfig.get(), icons);

        int pages = CodexEntries.pageCount(all.size(), CodexLayout.BOSS_PAGE_SIZE);
        int page = CodexEntries.clampPage(pageArg(args), pages);
        List<CodexEntries.Monster> entries = CodexEntries.slice(
                all, page, CodexLayout.BOSS_PAGE_SIZE);

        InfernalGuessPlayerData data = CodexProgress.dataOf(p);
        Set<String> met = CodexProgress.metMobs(data);

        for (int r = 0; r < CodexLayout.BOSS_MAX_ROWS; r++) {
            int from = r * CodexLayout.BOSS_SLOTS_PER_ROW;
            if (from >= entries.size()) {
                break;
            }
            int to = Math.min(entries.size(), from + CodexLayout.BOSS_SLOTS_PER_ROW);
            List<CodexEntries.Monster> row = entries.subList(from, to);
            double min = row.stream().mapToDouble(CodexEntries.Monster::weight).min().orElse(0);
            double max = row.stream().mapToDouble(CodexEntries.Monster::weight).max().orElse(0);
            String range = Math.abs(min - max) < 1.0e-9
                    ? CodexEntries.weightText(min)
                    : CodexEntries.weightText(min) + "–" + CodexEntries.weightText(max);
            setSlot(CodexLayout.slot(r, CodexLayout.BOSS_LABEL_OFFSET),
                    CodexIcons.rowLabel(range, row.size(), page + 1, Math.max(1, pages)));
            for (int c = 0; c < row.size(); c++) {
                CodexEntries.Monster monster = row.get(c);
                setSlot(CodexLayout.bossSlot(r, c),
                        CodexIcons.monster(monster, met.contains(monster.id())));
            }
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
            MenuFacade.open("infernalguess-codex-boss", p, page);
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
