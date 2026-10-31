package io.mczju.infernalguess.codex;

import io.mczju.infernalguess.InfernalGuessPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 图鉴的**内容配置**（{@code codex.yml}）：词条图鉴的图标/玩法/风味，
 * 以及怪物图鉴可选的 flavor 覆盖。
 *
 * <h2>为什么数值不写在这里</h2>
 * 权重、装备、组合、可抽词条数、分级倍率全部从 {@code mob-groups.yml} /
 * {@code AffixCatalog} **推导**（Maggoteers 的「定义唯一处」原则）。
 * 在这里再抄一份，调平衡时必然漏改。
 *
 * <p>本文件是**可选内容**：缺失的条目会用自动推导的文案兜底，
 * 所以新增词条/怪组时不会因为忘了写 codex.yml 而从图鉴里消失。
 */
public final class CodexConfig {

    public static final String FILE = "codex.yml";

    /** 词条图鉴的一条文案。 */
    public record AffixText(String id, String name, String icon, String gameplay, String flavor) {
    }

    /** 怪物图鉴的一条可选文案；{@code gameplay} 会放在自动生成的数值行之前。 */
    public record MonsterText(String id, String gameplay, String flavor) {
    }

    private static volatile CodexConfig INSTANCE = new CodexConfig(Map.of(), Map.of());

    private final Map<String, AffixText> affixes;
    private final Map<String, MonsterText> monsters;

    CodexConfig(Map<String, AffixText> affixes, Map<String, MonsterText> monsters) {
        this.affixes = Collections.unmodifiableMap(new LinkedHashMap<>(
                affixes == null ? Map.of() : affixes));
        this.monsters = Collections.unmodifiableMap(new LinkedHashMap<>(
                monsters == null ? Map.of() : monsters));
    }

    public static CodexConfig get() {
        return INSTANCE;
    }

    /** 空的配置视图（测试/兜底用）。 */
    public static CodexConfig empty() {
        return new CodexConfig(Map.of(), Map.of());
    }

    public AffixText affix(String id) {
        return id == null ? null : affixes.get(id.toLowerCase(Locale.ROOT));
    }

    public MonsterText monster(String id) {
        return id == null ? null : monsters.get(id.toLowerCase(Locale.ROOT));
    }

    public Map<String, AffixText> affixes() {
        return affixes;
    }

    public Map<String, MonsterText> monsters() {
        return monsters;
    }

    // ================================================================ 加载

    public static void load(InfernalGuessPlugin plugin) {
        if (plugin == null) {
            INSTANCE = empty();
            return;
        }
        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.isFile()) {
            try {
                plugin.saveResource(FILE, false);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("写出出厂 codex.yml 失败（将使用自动推导文案）："
                        + ex.getMessage());
            }
        }
        INSTANCE = parse(YamlConfiguration.loadConfiguration(file));
        plugin.getLogger().info("图鉴内容已加载：" + INSTANCE.affixes.size() + " 条词条文案 / "
                + INSTANCE.monsters.size() + " 条怪物文案");
        warnUnknownAffixes(plugin, INSTANCE);
    }

    /** 纯解析（单测直接喂 YamlConfiguration，不碰文件）。 */
    public static CodexConfig parse(YamlConfiguration yaml) {
        if (yaml == null) {
            return empty();
        }
        Map<String, AffixText> affixes = new LinkedHashMap<>();
        ConfigurationSection a = yaml.getConfigurationSection("affixes");
        if (a != null) {
            for (String rawId : a.getKeys(false)) {
                String id = rawId == null ? "" : rawId.trim().toLowerCase(Locale.ROOT);
                if (id.isEmpty()) {
                    continue;
                }
                ConfigurationSection s = a.getConfigurationSection(rawId);
                if (s == null) {
                    continue;
                }
                affixes.put(id, new AffixText(id,
                        blankToNull(s.getString("name")),
                        blankToNull(s.getString("icon")),
                        s.getString("gameplay", ""),
                        s.getString("flavor", "")));
            }
        }
        Map<String, MonsterText> monsters = new LinkedHashMap<>();
        ConfigurationSection m = yaml.getConfigurationSection("monsters");
        if (m != null) {
            for (String rawId : m.getKeys(false)) {
                String id = rawId == null ? "" : rawId.trim().toLowerCase(Locale.ROOT);
                if (id.isEmpty()) {
                    continue;
                }
                ConfigurationSection s = m.getConfigurationSection(rawId);
                if (s == null) {
                    continue;
                }
                monsters.put(id, new MonsterText(id,
                        s.getString("gameplay", ""),
                        s.getString("flavor", "")));
            }
        }
        return new CodexConfig(affixes, monsters);
    }

    /** 白名单里写了不存在的词条 id → 告警（不静默；图鉴仍按真实池渲染）。 */
    private static void warnUnknownAffixes(InfernalGuessPlugin plugin, CodexConfig cfg) {
        if (plugin == null || plugin.affixRegistry() == null) {
            return;
        }
        List<String> unknown = new ArrayList<>();
        for (String id : cfg.affixes.keySet()) {
            if (!plugin.affixRegistry().isKnown(id)) {
                unknown.add(id);
            }
        }
        if (!unknown.isEmpty()) {
            plugin.getLogger().warning("codex.yml: affixes 里这些 id 不是已实现词条（不会出现在图鉴里）："
                    + String.join(", ", unknown));
        }
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 图标材质名 → Material；非法/空返回 {@code null}（调用方回落 PAPER）。
     *
     * <p>先走 {@link Material#valueOf}（纯枚举查询，离线单测可用），
     * 再回落 {@link Material#matchMaterial}（能吃旧版别名）。
     */
    public static Material materialQuiet(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String name = raw.trim().toUpperCase(Locale.ROOT);
        try {
            Material m = Material.valueOf(name);
            if (m.isItem()) {
                return m;
            }
        } catch (IllegalArgumentException | LinkageError ignored) {
            // 不是枚举名 → 试 matchMaterial
        }
        try {
            Material m = Material.matchMaterial(name);
            if (m != null && m.isItem()) {
                return m;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 无服务端注册表 / 未知名字
        }
        return null;
    }
}
