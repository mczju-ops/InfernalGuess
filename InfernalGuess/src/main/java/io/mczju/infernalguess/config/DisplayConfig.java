package io.mczju.infernalguess.config;

import io.mczju.infernalguess.InfernalGuessPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code display.yml} 的加载与校验：**全部玩家可见文案 + GUI 图标 + 生物代表物品**。
 *
 * <p>用户第 1 条要求把这些从 {@code config.yml} 移出，集中到一个专门文件。
 *
 * <h2>三段结构</h2>
 * <ul>
 *   <li>{@code messages} —— MiniMessage 文案，扁平化为点号路径（如 {@code settle.bet-win-allin}）</li>
 *   <li>{@code icons}    —— GUI 按钮/状态图标，叶子节点含 {@code material/name/lore/glow}</li>
 *   <li>{@code mob-items} —— 每个生物的代表物品（默认取头颅 / 标志性掉落物 / 强关联物）</li>
 * </ul>
 *
 * <p>占位符写法为 <code>{name}</code>，在 MiniMessage 解析**之前**做字符串替换。
 * 注意：替换进去的值会被当作 MiniMessage 解析（玩家名只含字母数字下划线，安全）。
 */
public final class DisplayConfig {

    public static final String FILE = "display.yml";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /**
     * 代码会直接取用的文案键。缺任何一个都在**加载期**报错，
     * 避免运行期取到「missing」占位而玩家看到空白。
     */
    private static final List<String> REQUIRED_MESSAGES = List.of(
            "prefix",
            "game.meta-name",
            "game.meta-description",
            "start.arena-ready",
            "start.funds-given",
            "round.begin",
            "round.lineup",
            "round.betting-open",
            "round.fight",
            "bet.side-chosen",
            "bet.placed",
            "bet.placed-ultimate",
            "bet.watching",
            "bet.kill-line-note",
            "start.stack-given",
            "battle.poison-fog",
            "battle.mob-name-left",
            "battle.mob-name-right",
            "battle.roster-entry",
            "battle.roster-affix",
            "battle.bet-left",
            "battle.bet-right",
            "battle.bet-watch",
            "end.action-winner",
            "end.action-eliminated",
            "menu.eliminated",
            "settle.round-result",
            "settle.bet-win",
            "settle.bet-win-allin",
            "settle.bet-lose",
            "settle.bet-lose-allin",
            "settle.bet-watch",
            "settle.eliminated",
            "end.winner",
            "end.tie-winner",
            "menu.item-name",
            "menu.item-lore",
            "menu.betting-title-side",
            "menu.betting-title-amount",
            "scoreboard.title",
            "scoreboard.funds-row",
            "scoreboard.life-row",
            "scoreboard.side-left",
            "scoreboard.side-right",
            "scoreboard.side-watch"
    );

    /** 代码会直接取用的图标键。 */
    private static final List<String> REQUIRED_ICONS = List.of(
            "side.left", "side.right", "side.watch",
            "amount.percent-25", "amount.percent-50", "amount.all-in", "amount.back",
            "common.filler", "common.funds", "common.stack",
            "menu.bet-item"
    );

    /** 代码会直接取用的文案键（供测试断言出厂 display.yml 齐全）。 */
    public static List<String> requiredMessages() {
        return REQUIRED_MESSAGES;
    }

    /** 代码会直接取用的图标键（供测试断言出厂 display.yml 齐全）。 */
    public static List<String> requiredIcons() {
        return REQUIRED_ICONS;
    }

    private final Map<String, String> messages;
    private final Map<String, IconSpec> icons;
    private final Map<String, Material> mobItems;
    private final Material fallbackMobItem;

    /** 一个 GUI 图标规格。 */
    public record IconSpec(Material material, String name, List<String> lore, boolean glow) {
        public IconSpec {
            lore = List.copyOf(lore);
        }
    }

    private DisplayConfig(Map<String, String> messages, Map<String, IconSpec> icons,
                          Map<String, Material> mobItems, Material fallbackMobItem) {
        this.messages = Map.copyOf(messages);
        this.icons = Map.copyOf(icons);
        this.mobItems = Map.copyOf(mobItems);
        this.fallbackMobItem = fallbackMobItem;
    }

    public static DisplayConfig load(InfernalGuessPlugin plugin) {
        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.isFile()) {
            plugin.saveResource(FILE, false);
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        Map<String, String> messages = new LinkedHashMap<>();
        ConfigurationSection msgRoot = cfg.getConfigurationSection("messages");
        if (msgRoot == null) {
            throw new ConfigError(FILE, "messages", "缺失或不是配置段");
        }
        flattenMessages(msgRoot, "", messages);

        Map<String, IconSpec> icons = new LinkedHashMap<>();
        ConfigurationSection iconRoot = cfg.getConfigurationSection("icons");
        if (iconRoot != null) {
            flattenIcons(iconRoot, "", icons, plugin);
        }

        Map<String, Material> mobItems = new LinkedHashMap<>();
        ConfigurationSection mobRoot = cfg.getConfigurationSection("mob-items");
        if (mobRoot != null) {
            for (String key : mobRoot.getKeys(false)) {
                Object v = mobRoot.get(key);
                if (!(v instanceof String raw) || raw.isBlank()) {
                    throw new ConfigError(FILE, "mob-items." + key,
                            "应为物品材质名（如 ZOMBIE_HEAD），或删除该键以使用内置默认");
                }
                Material mat = Material.matchMaterial(raw);
                if (mat == null || !mat.isItem()) {
                    throw new ConfigError(FILE, "mob-items." + key, "不是合法物品材质：" + raw);
                }
                mobItems.put(key.toLowerCase(Locale.ROOT), mat);
            }
        }

        // ---- 必填键校验 ----
        List<String> missing = new ArrayList<>();
        for (String k : REQUIRED_MESSAGES) {
            if (!messages.containsKey(k)) {
                missing.add("messages." + k);
            }
        }
        for (String k : REQUIRED_ICONS) {
            if (!icons.containsKey(k)) {
                missing.add("icons." + k);
            }
        }
        if (!missing.isEmpty()) {
            throw new ConfigError(FILE, String.join(", ", missing),
                    "以下必需键缺失（代码会直接取用）：" + missing);
        }

        Material fallback = Material.matchMaterial("STONE");
        return new DisplayConfig(messages, icons, mobItems, fallback);
    }

    private static void flattenMessages(ConfigurationSection sec, String prefix, Map<String, String> out) {
        for (String key : sec.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object v = sec.get(key);
            if (v instanceof ConfigurationSection child) {
                flattenMessages(child, path, out);
            } else if (v instanceof String s) {
                out.put(path, s);
            } else if (v instanceof List<?> list) {
                // lore 之类的多行文案：以 \n 连接，便于用同一个键渲染
                StringBuilder sb = new StringBuilder();
                for (Object o : list) {
                    if (!(o instanceof String line)) {
                        throw new ConfigError(FILE, "messages." + path, "列表元素应为字符串");
                    }
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(line);
                }
                out.put(path, sb.toString());
            } else if (v != null) {
                throw new ConfigError(FILE, "messages." + path, "应为字符串");
            }
        }
    }

    private static void flattenIcons(ConfigurationSection sec, String prefix,
                                     Map<String, IconSpec> out, InfernalGuessPlugin plugin) {
        for (String key : sec.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object v = sec.get(key);
            if (v instanceof ConfigurationSection child) {
                if (child.isSet("material")) {
                    out.put(path, parseIcon(child, path));
                } else {
                    flattenIcons(child, path, out, plugin);
                }
            } else if (v != null) {
                throw new ConfigError(FILE, "icons." + path, "应为配置段（含 material/name/lore）");
            }
        }
    }

    private static IconSpec parseIcon(ConfigurationSection s, String path) {
        String rawMat = s.getString("material");
        if (rawMat == null || rawMat.isBlank()) {
            throw new ConfigError(FILE, "icons." + path + ".material", "缺失");
        }
        Material mat = Material.matchMaterial(rawMat);
        if (mat == null || !mat.isItem()) {
            throw new ConfigError(FILE, "icons." + path + ".material", "不是合法物品材质：" + rawMat);
        }
        String name = s.getString("name", "");
        List<String> lore = new ArrayList<>();
        Object rawLore = s.get("lore");
        if (rawLore instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof String line)) {
                    throw new ConfigError(FILE, "icons." + path + ".lore", "列表元素应为字符串");
                }
                lore.add(line);
            }
        } else if (rawLore != null) {
            throw new ConfigError(FILE, "icons." + path + ".lore", "应为字符串列表");
        }
        boolean glow = s.getBoolean("glow", false);
        return new IconSpec(mat, name, lore, glow);
    }

    // ---------------- 访问 ----------------

    /** 原始 MiniMessage 文案（不做占位符替换）。 */
    public String raw(String path) {
        String s = messages.get(path);
        if (s == null) {
            throw new ConfigError(FILE, "messages." + path, "缺失（可能是拼错了路径）");
        }
        return s;
    }

    public boolean hasMessage(String path) {
        return messages.containsKey(path);
    }

    /** 渲染文案：先替换 <code>{key}</code> 占位符，再按 MiniMessage 解析。 */
    public Component render(String path, Map<String, String> placeholders) {
        String raw = raw(path);
        if (placeholders != null && !placeholders.isEmpty()) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                raw = raw.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
        }
        return MM.deserialize(raw);
    }

    public Component render(String path) {
        return render(path, Map.of());
    }

    /** 是否含有未替换的占位符（用于自检/测试）。 */
    public boolean hasPlaceholder(String path, String key) {
        return raw(path).contains("{" + key + "}");
    }

    /** 取图标；不存在时抛错（加载期已保证必需键存在）。 */
    public IconSpec icon(String path) {
        IconSpec spec = icons.get(path);
        if (spec == null) {
            throw new ConfigError(FILE, "icons." + path, "缺失（可能是拼错了路径）");
        }
        return spec;
    }

    public boolean hasIcon(String path) {
        return icons.containsKey(path);
    }

    /**
     * 生物的**代表物品**材质。
     *
     * <p>兜底链：{@code display.yml 的 mob-items} → 调用方给的默认 → {@code STONE}。
     */
    public Material mobItem(String mobId, Material builtinDefault) {
        Material m = mobItems.get(mobId.toLowerCase(Locale.ROOT));
        if (m != null) {
            return m;
        }
        return builtinDefault != null ? builtinDefault : fallbackMobItem;
    }

    public Map<String, Material> mobItems() {
        return mobItems;
    }
}
