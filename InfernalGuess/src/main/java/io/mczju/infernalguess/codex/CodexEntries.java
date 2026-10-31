package io.mczju.infernalguess.codex;

import io.mczju.infernalguess.affix.AffixCatalog;
import io.mczju.infernalguess.config.MobGroup;
import io.mczju.infernalguess.config.MobGroupConfig;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * 图鉴的**数据层**：把 {@code mob-groups.yml} / {@code AffixCatalog} / {@code codex.yml}
 * 折叠成菜单直接能画的条目，并把分页抽成纯函数。
 *
 * <h2>为什么数值不抄第二份</h2>
 * 权重、装备、组合、可抽词条数、分级倍率全部从配置/目录**推导** ——
 * 改平衡时只改一处（Maggoteers 的「定义唯一处」原则）。
 *
 * <p>本类**不碰 Material / 世界 / 玩家**：只产出材质名（{@code iconKey}），
 * 解析在菜单层做。这样离线单测不需要服务端注册表。
 */
public final class CodexEntries {

    private CodexEntries() {
    }

    /** Boss（怪物）图鉴的一条。{@code iconKey} 是材质名，菜单层解析。 */
    public record Monster(String id, String name, String iconKey, double weight,
                          String typeId, List<String> gameplay, String flavor) {
        public Monster {
            gameplay = List.copyOf(gameplay == null ? List.of() : gameplay);
        }
    }

    /** 词条图鉴的一条。 */
    public record Affix(String id, String name, String iconKey, double multiplier, int tier,
                        int legalGroups, List<String> gameplay, String flavor) {
        public Affix {
            gameplay = List.copyOf(gameplay == null ? List.of() : gameplay);
        }
    }

    // ================================================================ 怪物

    /**
     * 全部怪组 → 图鉴条目（**动态**，不写白名单）。
     *
     * @param iconKeys 怪组 id → 代表物品材质名（来自 {@code display.yml} 的 mob-items）；
     *                 缺失时回落 {@code PAPER}
     */
    public static List<Monster> monsters(MobGroupConfig groups, CodexConfig cfg,
                                         Map<String, String> iconKeys) {
        if (groups == null) {
            return List.of();
        }
        CodexConfig config = cfg == null ? CodexConfig.empty() : cfg;
        Map<String, String> icons = iconKeys == null ? Map.of() : iconKeys;
        List<Monster> out = new ArrayList<>();
        for (MobGroup g : groups.groups().values()) {
            if (g == null || g.id() == null) {
                continue;
            }
            String id = g.id().toLowerCase(Locale.ROOT);
            CodexConfig.MonsterText text = config.monster(id);
            List<String> gameplay = new ArrayList<>();
            if (text != null && !blank(text.gameplay())) {
                gameplay.add(text.gameplay());
            }
            gameplay.add("权重 " + weightText(g.weight()) + " · " + weightTier(g.weight()));
            gameplay.add("实体：" + typeId(g));
            String equipment = equipmentText(g);
            if (!equipment.isEmpty()) {
                gameplay.add("装备：" + equipment);
            }
            String combo = comboText(g);
            if (!combo.isEmpty()) {
                gameplay.add("组合：" + combo);
            }
            String variant = variantText(g);
            if (!variant.isEmpty()) {
                gameplay.add("变种：" + variant);
            }
            gameplay.add("可抽词条：" + g.legalAffixIds().size() + " 条");
            out.add(new Monster(id, g.nameOrId(),
                    icons.getOrDefault(id, "PAPER"), g.weight(), typeId(g),
                    gameplay, text == null ? "" : text.flavor()));
        }
        // 权重从小到大；同权重按名字，保证每次打开顺序一致（不要依赖 Map 的迭代）
        out.sort(Comparator.comparingDouble(Monster::weight).thenComparing(Monster::name));
        return out;
    }

    /** 实体类型 id（组合取载具）。 */
    static String typeId(MobGroup g) {
        EntityType type = g.combo() != null ? g.combo().mount().entityType() : g.entityType();
        return entityId(type);
    }

    static String entityId(EntityType type) {
        if (type == null) {
            return "?";
        }
        try {
            return type.getKey().getKey();
        } catch (RuntimeException | LinkageError ex) {
            return type.name().toLowerCase(Locale.ROOT);
        }
    }

    /** 装备：{@code hand=minecraft:iron_sword, head=...}（槽位排序，避免 Map 顺序漂移）。 */
    static String equipmentText(MobGroup g) {
        if (g == null || g.equipment() == null || g.equipment().isEmpty()) {
            return "";
        }
        return g.equipment().entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().name()))
                .map(e -> e.getKey().name().toLowerCase(Locale.ROOT) + "=" + e.getValue())
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    /** 组合：载具 + 各乘客的类型（乘客可带装备时补上）。 */
    static String comboText(MobGroup g) {
        if (g == null || g.combo() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(entityId(g.combo().mount().entityType()));
        for (MobGroup.Part rider : g.combo().riders()) {
            sb.append(" + ").append(entityId(rider.entityType()));
            if (rider.equipment() != null && !rider.equipment().isEmpty()) {
                sb.append("(").append(rider.equipment().values().stream()
                        .sorted().reduce((a, b) -> a + "/" + b).orElse("")).append(")");
            }
        }
        return sb.toString();
    }

    /** 变种：{@code charged=true}（键排序）。 */
    static String variantText(MobGroup g) {
        if (g == null || g.variant() == null || g.variant().isEmpty()) {
            return "";
        }
        return g.variant().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    // ================================================================ 词条

    /**
     * 全部词条 → 图鉴条目（按 {@link AffixCatalog} 的分级顺序）。
     *
     * @param displayNames 词条 id → display.yml 里的显示名（codex.yml 没写 name 时用）
     */
    public static List<Affix> affixes(CodexConfig cfg, MobGroupConfig groups,
                                      Function<String, String> displayNames) {
        CodexConfig config = cfg == null ? CodexConfig.empty() : cfg;
        Function<String, String> names = displayNames == null ? id -> id : displayNames;
        List<Affix> out = new ArrayList<>();
        for (AffixCatalog.Def def : AffixCatalog.all()) {
            String id = def.id();
            CodexConfig.AffixText text = config.affix(id);
            String name = text != null && !blank(text.name()) ? text.name() : names.apply(id);
            String icon = text != null && !blank(text.icon()) ? text.icon() : "PAPER";
            List<String> gameplay = new ArrayList<>();
            if (text != null && !blank(text.gameplay())) {
                gameplay.add(text.gameplay());
            }
            gameplay.add("权重 ×" + String.format(Locale.ROOT, "%.2f", def.multiplier())
                    + " · " + tierOf(def.multiplier()) + " 级");
            gameplay.add("可抽怪物：" + legalGroupCount(groups, id) + " 组");
            out.add(new Affix(id, name, icon, def.multiplier(), tierOf(def.multiplier()),
                    legalGroupCount(groups, id), gameplay, text == null ? "" : text.flavor()));
        }
        return out;
    }

    /** 有多少个怪组能抽到该词条（组合取并集后的白名单）。 */
    public static int legalGroupCount(MobGroupConfig groups, String affixId) {
        if (groups == null || affixId == null) {
            return 0;
        }
        int n = 0;
        for (MobGroup g : groups.groups().values()) {
            if (g != null && g.legalAffixIds() != null
                    && g.legalAffixIds().contains(affixId.toLowerCase(Locale.ROOT))) {
                n++;
            }
        }
        return n;
    }

    static int tierOf(double multiplier) {
        if (multiplier >= AffixCatalog.TIER_3_MULTIPLIER - 1.0e-9) {
            return 3;
        }
        if (multiplier >= AffixCatalog.TIER_2_MULTIPLIER - 1.0e-9) {
            return 2;
        }
        return 1;
    }

    // ================================================================ 分页 / 展示

    public static int pageCount(int total, int perPage) {
        if (total <= 0 || perPage <= 0) {
            return 0;
        }
        return (total + perPage - 1) / perPage;
    }

    public static int clampPage(int page, int pages) {
        if (pages <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(page, pages - 1));
    }

    /** 取第 {@code page} 页（0 基）；越界自动夹到合法范围。 */
    public static <T> List<T> slice(List<T> all, int page, int perPage) {
        if (all == null || all.isEmpty() || perPage <= 0) {
            return List.of();
        }
        int pages = pageCount(all.size(), perPage);
        int p = clampPage(page, pages);
        int from = p * perPage;
        int to = Math.min(all.size(), from + perPage);
        return List.copyOf(all.subList(from, to));
    }

    /** 权重显示：整数保留一位小数（1.0），小数最多两位并去掉尾零。 */
    public static String weightText(double w) {
        if (Math.abs(w - Math.rint(w)) < 1.0e-9) {
            return String.format(Locale.ROOT, "%.1f", w);
        }
        String s = String.format(Locale.ROOT, "%.2f", w);
        while (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 战力评级（按单只权重分段；只用于图鉴展示，不参与任何判定）。 */
    public static String weightTier(double w) {
        if (w < 1.0) {
            return "极弱";
        }
        if (w < 2.0) {
            return "杂兵";
        }
        if (w < 3.0) {
            return "强化";
        }
        if (w < 5.0) {
            return "精锐";
        }
        if (w < 10.0) {
            return "强敌";
        }
        return "传说";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
