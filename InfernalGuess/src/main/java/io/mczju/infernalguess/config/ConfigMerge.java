package io.mczju.infernalguess.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/**
 * 出厂配置 → 磁盘配置的**只增不改**合并。
 *
 * <h2>为什么需要它</h2>
 * 本插件采用严格 schema 校验：缺键即报错并禁用插件。
 * 这带来一个必然后果 —— **版本升级新增的配置键在老存档里不存在**，插件会拒绝启动。
 *
 * <p>实测踩到两次：
 * <ol>
 *   <li>新增 {@code battle.max-fight-seconds} → 旧 config.yml 缺键 → 插件自我禁用</li>
 *   <li>新增 {@code icons.menu.bet-item} 并列入必需图标 → 旧 display.yml 缺键 → 同样会挂</li>
 * </ol>
 *
 * <p>所以严格校验**必须**配套迁移，且迁移要覆盖**全部随包发布的配置文件**。
 *
 * <h2>语义</h2>
 * <ul>
 *   <li>只处理**叶子键**（不碰配置段本身）</li>
 *   <li>**只新增**：磁盘上已存在的键一律不动，玩家的改动与注释以外的自定义都保留</li>
 *   <li>返回被补进去的键名列表（供日志打印）</li>
 * </ul>
 *
 * <p>本类**不依赖 Bukkit 运行时**（只用 {@link ConfigurationSection}，可离线单测）。
 */
public final class ConfigMerge {

    private ConfigMerge() {
    }

    /**
     * 删除「**出厂已经不再有、但磁盘上还留着**」的叶子键（仅用于**内容文件**）。
     *
     * <h2>为什么必须删，而不只是「忽略」</h2>
     * 重写（2026-10-11）把 {@code mob-groups.yml} 里 45 个单只怪的 {@code amount} 段删掉了 ——
     * 数量改由「回合目标权重 ÷ 单只权重」算出。但老存档里还留着
     * {@code amount: { min: 2, max: 5 }}：
     * <ul>
     *   <li>{@code min} 已废弃（被忽略）→ 还算无害；</li>
     *   <li>{@code max} 是**仍在生效**的安全上限 → 老值 {@code 4} 会把僵尸**永久压在 4 只**，
     *       于是「末回合 25 权重」根本凑不出来，难度曲线被悄悄压平
     *       —— 这正是本插件反复踩过的「配置里写了却完全不起作用／悄悄生效」那一类坑。</li>
     * </ul>
     * 只**新增**的合并规则修不好它（出厂没有这个键，就永远不会去覆盖它），
     * 所以内容文件必须允许「删除出厂已废弃的键」。
     *
     * <h2>安全边界</h2>
     * 只在**「所属的顶层对象仍存在于出厂文件」**时才删 —— 也就是
     * {@code mob_groups.<某个出厂组>} 底下的废弃键。
     * 玩家自己新增的**整组内容**（{@code mob_groups.my_mob.*}）所属顶层对象不在出厂文件里，
     * 一律保留。
     *
     * @return 被删掉的键（供日志打印）
     */
    public static List<String> pruneObsolete(ConfigurationSection defaults,
                                             ConfigurationSection disk) {
        List<String> removed = new ArrayList<>();
        if (defaults == null || disk == null) {
            return removed;
        }
        for (String key : new ArrayList<>(disk.getKeys(true))) {
            if (disk.isConfigurationSection(key) || defaults.isSet(key)) {
                continue;
            }
            // 顶层对象 = 前两段路径（如 mob_groups.zombie）
            String[] parts = key.split("\\.");
            if (parts.length < 3) {
                continue;
            }
            String owner = parts[0] + "." + parts[1];
            if (!defaults.isSet(owner)) {
                continue; // 玩家自己的整组内容 → 绝不删
            }
            disk.set(key, null);
            removed.add(key);
        }
        return removed;
    }

    /**
     * 「**出厂值为准**」合并 —— 用于**游戏内容文件**（{@code mob-groups.yml}）。
     *
     * <p>与 {@link #merge} 的区别：**同名键用出厂值覆盖**，而不是保留磁盘值。
     * 磁盘上独有的键（用户自己加的怪物组 / 池子条目）仍然保留。
     *
     * <h2>为什么内容文件必须覆盖</h2>
     * 实测踩到的真实问题：去词条后我重新校准了各组的 {@code amount} 上限，
     * 但「只增不改」策略下 **同名键的出厂值永远到不了老存档** ——
     * 于是测试服仍然是旧区间，目标权重越界、难度曲线被压平，
     * 而本地测试却是绿的。**平衡修复发布不出去，等于没修。**
     *
     * <p>取舍依据：{@code mob-groups.yml} 是**我方拥有的游戏内容**
     * （52 个组的权重是配平的一部分，不该由用户手工维护），
     * 而 {@code config.yml} / {@code display.yml} 是**用户的调参入口**，继续「只增不改」。
     * 调用方会先备份原文件。
     *
     * @return 变更说明（覆盖 / 新增的键；列表额外标注条数）
     */
    public static List<String> overwriteShared(ConfigurationSection defaults,
                                               ConfigurationSection disk) {
        List<String> changed = new ArrayList<>();
        if (defaults == null || disk == null) {
            return changed;
        }
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                continue;
            }
            Object def = defaults.get(key);
            if (!disk.isSet(key)) {
                disk.set(key, def);
                changed.add(key);
                continue;
            }
            Object cur = disk.get(key);
            if (def instanceof List<?> defList && cur instanceof List<?> diskList) {
                List<Object> merged = new ArrayList<>(diskList);
                int before = merged.size();
                for (Object o : defList) {
                    if (!merged.contains(o)) {
                        merged.add(o);
                    }
                }
                if (merged.size() != before) {
                    disk.set(key, merged);
                    changed.add(key + "(+" + (merged.size() - before) + ")");
                }
                continue;
            }
            // 同名标量：出厂值优先（这是与 merge 的唯一区别）
            if (!java.util.Objects.equals(def, cur)) {
                disk.set(key, def);
                changed.add(key);
            }
        }
        return changed;
    }

    /**
     * 把 {@code defaults} 里磁盘上没有的叶子键补进 {@code disk}（就地修改）。
     *
     * <p><b>标量</b>：磁盘上已有就**绝不动**（玩家的值优先）。
     *
     * <p><b>列表</b>：按**并集**合并 —— 保留磁盘上的顺序与自定义条目，
     * 再把出厂列表里多出来的条目**追加**到末尾。
     *
     * <h2>为什么列表必须并集合并</h2>
     * 实测踩到的真实问题：某个列表键（当年是 {@code wave-pools.yml} 的池子键，如
     * {@code point1_wave1-2}）**早就存在**，
     * 「只增不改」的策略下它的值永远不会更新 ——
     * 于是新版加的怪物**虽然补进了 mob-groups.yml，却没有任何池子引用它们**，
     * 表现为「配置更新了但实战还是只有旧怪」。
     *
     * <p>并集既能把新内容带进老存档，又不会删掉玩家自己加的条目。
     *
     * @return 变更说明（补进去的键名；列表额外标注新增条数）
     */
    public static List<String> merge(ConfigurationSection defaults, ConfigurationSection disk) {
        List<String> added = new ArrayList<>();
        if (defaults == null || disk == null) {
            return added;
        }
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                continue; // 只处理叶子键
            }
            Object def = defaults.get(key);
            if (!disk.isSet(key)) {
                disk.set(key, def);
                added.add(key);
                continue;
            }
            // ★ 列表：并集合并
            if (def instanceof List<?> defList && disk.get(key) instanceof List<?> diskList) {
                List<Object> merged = new ArrayList<>(diskList);
                int before = merged.size();
                for (Object o : defList) {
                    if (!merged.contains(o)) {
                        merged.add(o);
                    }
                }
                int n = merged.size() - before;
                if (n > 0) {
                    disk.set(key, merged);
                    added.add(key + "(+" + n + ")");
                }
            }
        }
        return added;
    }

    /** 只探测不修改：返回磁盘上缺失的叶子键。 */
    public static List<String> missingKeys(ConfigurationSection defaults, ConfigurationSection disk) {
        List<String> missing = new ArrayList<>();
        if (defaults == null || disk == null) {
            return missing;
        }
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                continue;
            }
            if (!disk.isSet(key)) {
                missing.add(key);
            }
        }
        return missing;
    }
}
