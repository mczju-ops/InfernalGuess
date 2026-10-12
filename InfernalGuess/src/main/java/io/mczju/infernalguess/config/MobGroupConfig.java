package io.mczju.infernalguess.config;

import io.mczju.infernalguess.InfernalGuessPlugin;
import io.mczju.infernalguess.run.WeightAllocator;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EquipmentSlot;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code mob-groups.yml} 的加载与**全量校验**。
 *
 * <p>与 Maggoteers 的实测缺陷相反，这里在**加载期**就把三类错误挡住：
 * <ol>
 *   <li>实体 id 不是合法原版实体 → 报错并列出该键</li>
 *   <li>词条 id 不在**我方已实现的词条白名单**里 → 报错（2026-10-11 起改为自有注册表，
 *       不再依赖炒鸡 API；白名单为空时禁止配置任何词条）</li>
 *   <li>装备材质名非法 → 用 {@link Material#matchMaterial} 校验并报错</li>
 * </ol>
 */
public final class MobGroupConfig {

    public static final String FILE = "mob-groups.yml";

    /** YAML 里的槽位名 → Bukkit 装备槽。 */
    private static final Map<String, EquipmentSlot> SLOT_ALIASES = Map.ofEntries(
            Map.entry("helmet", EquipmentSlot.HEAD),
            Map.entry("head", EquipmentSlot.HEAD),
            Map.entry("chestplate", EquipmentSlot.CHEST),
            Map.entry("chest", EquipmentSlot.CHEST),
            Map.entry("leggings", EquipmentSlot.LEGS),
            Map.entry("legs", EquipmentSlot.LEGS),
            Map.entry("boots", EquipmentSlot.FEET),
            Map.entry("feet", EquipmentSlot.FEET),
            Map.entry("mainhand", EquipmentSlot.HAND),
            Map.entry("main_hand", EquipmentSlot.HAND),
            Map.entry("hand", EquipmentSlot.HAND),
            Map.entry("offhand", EquipmentSlot.OFF_HAND),
            Map.entry("off_hand", EquipmentSlot.OFF_HAND)
    );

    private final Map<String, MobGroup> groups;

    private MobGroupConfig(Map<String, MobGroup> groups) {
        // ★ 必须是**保序**的不可变视图：`Map.copyOf` 的迭代顺序按 JVM 运行随机加盐，
        //   而剧本生成器直接按这个顺序构造「大池」—— 顺序一漂，同一个 seed
        //   在不同次启动里就会抽出不同的阵容，「同种子 → 同剧本」当场失效。
        //   实测依据：Map.copyOf/JDK 不可变 Map 使用 SALT 打散桶序。
        this.groups = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(groups));
    }

    /**
     * 直接构造（**供单元测试**跳过文件 IO 与 plugin、跳过原版实体注册表校验）。
     */
    public static MobGroupConfig of(Map<String, MobGroup> groups) {
        return new MobGroupConfig(groups);
    }

    /**
     * 按用户需求「默认只有原版所有能攻击的生物」：
     * 首次运行释放内置的 {@code mob-groups.yml}，再读取服务器上的实际文件。
     */
    public static MobGroupConfig load(InfernalGuessPlugin plugin) {
        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.isFile()) {
            plugin.saveResource(FILE, false);
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection root = cfg.getConfigurationSection("mob_groups");
        if (root == null) {
            throw new ConfigError(FILE, "mob_groups", "缺失或不是配置段");
        }

        Map<String, MobGroup> out = new LinkedHashMap<>();
        List<String> ignoredMin = new ArrayList<>();
        List<String> legacyAffixKeys = new ArrayList<>();
        // ★ 加载期自检：**全局词条池**必须与**已注册实现**严格一致。
        //   池里有一条没实现的 → 抽到后装配阶段静默忽略，但权重照样算上它的加成，
        //   正是我们在炒鸡上吃过的亏（31 条静默无效却参与加成）。
        List<String> poolProblems = io.mczju.infernalguess.affix.AffixCatalog
                .validateAgainst(plugin.affixRegistry().knownIds());
        if (!poolProblems.isEmpty()) {
            throw new ConfigError("affix-catalog", "pool",
                    "词条池与已实现词条不一致（会导致「配了但无效却抬权重」）：" + poolProblems);
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                throw new ConfigError(FILE, "mob_groups." + id, "应为配置段");
            }
            out.put(id.toLowerCase(Locale.ROOT),
                    parseGroup(id, s, ignoredMin, legacyAffixKeys));
        }
        if (out.isEmpty()) {
            throw new ConfigError(FILE, "mob_groups", "没有任何怪物组，无法生成阵容");
        }
        if (!ignoredMin.isEmpty()) {
            // 只**报告**，不擅自改用户的文件：配置是他的
            plugin.getLogger().warning("mob-groups.yml 里有 " + ignoredMin.size()
                    + " 个组仍写着 amount.min —— 该键**已废弃**（数量现在由「回合目标权重 ÷ "
                    + "单只权重」算出），配置里的值被忽略。");
            plugin.getLogger().warning("  建议删掉这些 min 键（连带整个 amount 段也可以删，"
                    + "只有骑乘组合需要保留 amount.max 来压制实体数）。");
        }
        if (!legacyAffixKeys.isEmpty()) {
            // ★ 2026-10-12：词条改为全局池后，逐组清单已废弃。
            //   现场/老存档里这些键会一直留着 —— 必须让它**在日志里可见**，
            //   否则用户会以为「词条还是按我写的那样发的」（本项目反复踩过的坑）。
            plugin.getLogger().warning("mob-groups.yml 里有 " + legacyAffixKeys.size()
                    + " 个组仍写着 infernal_affixes —— 该键**已废弃**：词条现在从"
                    + " config.yml 之外的全局池统一抽取（见 AffixCatalog），"
                    + "这里写的清单被**完全忽略**。");
            plugin.getLogger().warning("  建议删掉这些键。受影响的组：" + legacyAffixKeys);
        }
        return new MobGroupConfig(out);
    }

    /**
     * 解析变种组显式声明的 {@code type:}。
     *
     * <h2>为什么需要它（实机踩到的缺陷）</h2>
     * 组键的约定是「= 原版实体 id」，而 {@code variant:}（闪电苦力怕 / 白狐狸）
     * 恰恰是**同一个实体 id 的另一种形态** —— 它没有自己的实体 id，
     * 于是组键必须是自定义名字。而原来只有 {@code combo:} 能解锁自定义组键，
     * 所以 `lightning_creeper`（只有 variant）会让**整个插件加载失败**。
     *
     * <p>修法：允许用 {@code type:} 显式写基础实体。这条路径**只**在
     * 「组键不是合法实体 id」时启用，因此不会破坏既有的「键 = 实体 id」写法。
     *
     * <p>★ 并且**强制要求**：写了 {@code variant:} 就必须写 {@code type:} ——
     * 否则「变种是给哪个实体加的」无从判断，属于必然的错误配置，当场报错。
     *
     * @return 声明的实体类型；没写 {@code type:} 时返回 {@code null}
     */
    private static EntityType parseDeclaredType(String path, ConfigurationSection s) {
        String raw = s.getString("type");
        boolean hasVariant = s.isSet("variant");
        if (raw == null || raw.isBlank()) {
            if (hasVariant) {
                throw new ConfigError(FILE, path + ".type",
                        "写了 `variant:` 就必须同时写 `type: <基础实体>` —— "
                                + "否则无从知道这个形态是加在哪个实体上"
                                + "（例：type: CREEPER + variant: { charged: true }）");
            }
            return null;
        }
        EntityType t = Registry.ENTITY_TYPE.get(
                NamespacedKey.minecraft(raw.trim().toLowerCase(Locale.ROOT)));
        if (t == null) {
            throw new ConfigError(FILE, path + ".type",
                    "不是合法的原版实体 id：" + raw + "（写法应如 `CREEPER`、`FOX`）");
        }
        return t;
    }

    /**
     * 推导一个怪组**合法可抽**的词条 id。
     *
     * <h2>单只怪</h2>
     * 直接用它的实体类型查 {@code AffixCatalog} —— 谓词看的是**实体能力**
     * （能不能近战 / 有没有装备槽 / 是不是蜘蛛），而不是组名。
     *
     * <h2>★ 骑乘组合：取各部分的**并集**</h2>
     * 组合是「载具 + 乘客」，词条在生成时**整组共享**（见 {@code BattleService.spawnCombo}
     * 把同一份 {@code affixIds} 发给每个部分）。因此只要**任一部件**能用某条词条，
     * 本条就对该组合法 —— 反之取交集会让「载具是蜘蛛、乘客是骷髅」这种组合
     * 白白丢掉双方各自的专长词条。
     *
     * <p>没有实体上下文（{@code type == null}，理论上不该发生）时退回全池。
     */
    private static List<String> legalAffixIdsOf(MobGroup.Combo combo, EntityType type) {
        if (combo == null) {
            return io.mczju.infernalguess.affix.AffixCatalog.legalIdsForEntityType(type);
        }
        List<EntityType> parts = new ArrayList<>(1 + combo.riders().size());
        parts.add(combo.mount().entityType());
        for (MobGroup.Part rider : combo.riders()) {
            parts.add(rider.entityType());
        }
        return io.mczju.infernalguess.affix.AffixCatalog.legalIdsForUnion(parts);
    }

    private static MobGroup parseGroup(String id, ConfigurationSection s,
                                       List<String> ignoredMin, List<String> legacyAffixKeys) {
        String path = "mob_groups." + id;

        // ---- 实体类型 ----
        // 单只怪：**组键本身就是原版实体 id**（如 `zombie`）—— 这条约定让配置一眼可读。
        // ★ 骑乘组合：组键是自定义名字（如 `chicken_jockey`），类型取 `combo.mount.type`。
        //   组合里每个部分（载具 + 各乘客）的类型由 parsePart 各自校验。
        // ★ 单只怪**变种**：组键也可以自取名字（如 `lightning_creeper`），
        //   此时**必须**用 `type:` 显式写出基础实体 —— 见下面的分支。
        MobGroup.Combo combo = parseCombo(path, s);
        EntityType type;
        if (combo != null) {
            type = combo.mount().entityType();
        } else {
            type = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(id.toLowerCase(Locale.ROOT)));
            if (type == null) {
                // ★ 2026-10-12（实机踩到）：`variant:` 是在「组键 = 实体 id」的约定之外
                //   引入的第二条自定义组键路径。原来这里只认 combo，于是
                //   `lightning_creeper`（只有 variant、没有 combo）直接让加载失败、
                //   **整个插件被禁用**。现在允许用 `type:` 显式声明基础实体。
                EntityType declared = parseDeclaredType(path, s);
                if (declared == null) {
                    throw new ConfigError(FILE, path,
                            "不是合法的原版实体 id（写法应如 `zombie`、`wither_skeleton`）。"
                                    + "★ 若这是一个**骑乘组合**，请补上 `combo:` 段；"
                                    + "★ 若这是**同一实体的另一种形态**（如闪电苦力怕 / 白狐狸），"
                                    + "请补上 `type: <基础实体>`（可配 `variant:` 改形态）"
                                    + " —— 有了其中之一，组键就可以用自定义名字");
                }
                type = declared;
            }
            if (!type.isAlive() || !type.isSpawnable()) {
                throw new ConfigError(FILE, path, "该实体不能作为生物生成：" + type);
            }
            Class<?> cls = type.getEntityClass();
            if (cls == null || !org.bukkit.entity.LivingEntity.class.isAssignableFrom(cls)) {
                throw new ConfigError(FILE, path, "该实体不是 LivingEntity：" + type);
            }
        }

        // ---- 显示名（可选）----
        String displayName = s.getString("name");

        // ---- 单个权重（允许小数）----
        Object rawWeight = s.get("weight");
        if (!(rawWeight instanceof Number wn)) {
            throw new ConfigError(FILE, path + ".weight", "缺失或不是数字（允许小数，如 1.5）");
        }
        double weight = wn.doubleValue();
        if (weight <= 0.0) {
            throw new ConfigError(FILE, path + ".weight", "必须为正数，实际 " + weight);
        }

        // ---- 数量安全上限（★ 重写后的语义，见 config.yml / mob-groups.yml 的说明）----
        //   旧版这里是「数量随机区间 {min,max}」，新版数量由「回合目标 ÷ 单只权重」算出，
        //   所以 min 已废弃；max 降级为**可选的安全上限**。
        //   ⚠️ 这里刻意复用 WeightAllocator 的两个常量（而不是各写一份字面量）：
        //      配置层允许的上限与分配器真正认的上限必须是同一个数字。
        int safetyCap = WeightAllocator.DEFAULT_MAX_UNITS_PER_GROUP;
        ConfigurationSection amount = s.getConfigurationSection("amount");
        if (amount != null) {
            if (amount.isSet("min")) {
                // 不报错（老存档里到处都是），但必须让它在启动日志里**可见** ——
                // 「配置里写了却完全不起作用」是本项目反复踩过的坑。
                ignoredMin.add(path);
            }
            Object rawMax = amount.get("max");
            if (rawMax != null) {
                // ⚠️ 空 amount 段（迁移把废弃键删干净后会剩下它）**不算错**，按缺省处理：
                //    否则老存档一升级就「拒绝启动」，比配置冗余糟糕得多。
                if (!(rawMax instanceof Number maxNum)) {
                    throw new ConfigError(FILE, path + ".amount.max", "不是数字");
                }
                safetyCap = maxNum.intValue();
                if (safetyCap < 1) {
                    throw new ConfigError(FILE, path + ".amount.max", "至少为 1，实际 " + safetyCap);
                }
                if (safetyCap > WeightAllocator.ABSOLUTE_MAX_UNITS_PER_GROUP) {
                    throw new ConfigError(FILE, path + ".amount.max",
                            "不能超过 " + WeightAllocator.ABSOLUTE_MAX_UNITS_PER_GROUP
                                    + "（防爆天花板），实际 " + safetyCap);
                }
            }
        }

        // ---- 词条：★ 2026-10-12 起**不再逐组配置** ----
        //   旧设计让每组自己列 `infernal_affixes: [{id, weight-bonus, chance}]`，
        //   结果「只有少数生物能抽到词条」。现在改为**全局池 + 少量限制**：
        //   本组合法可抽的 id 由 AffixCatalog 按实体能力推导（见 legalAffixIdsOf）。
        //   ⚠️ 现场/老存档里仍会留着 infernal_affixes —— 那是**已废弃**的键，
        //      这里只**报告**、不擅自删除用户的文件（与 amount.min 同一处理原则）。
        if (s.isSet("infernal_affixes")) {
            legacyAffixKeys.add(path);
        }
        List<String> legalAffixIds = legalAffixIdsOf(combo, type);

        // ---- 装备（缺省置空）----
        Map<EquipmentSlot, String> equipment =
                parseEquipment(path + ".equipment", s.getConfigurationSection("equipment"));

        // ---- 变种属性（★ 2026-10-12：原版实体类型表达不了的形态）----
        Map<String, String> variant = parseVariant(path + ".variant",
                s.getConfigurationSection("variant"));
        // ★ 加载期就校验变种声明是否合法、是否与该实体类型匹配 ——
        //   「变种没生效」在游戏里只表现为「这只苦力怕怎么没闪电」，极难归因，
        //   所以宁可拒绝启动并指出键名。
        List<String> variantProblems = MobVariant.validate(path + ".variant", variant,
                type == null ? null : type.getEntityClass());
        if (!variantProblems.isEmpty()) {
            throw new ConfigError(FILE, path + ".variant",
                    "变种声明有问题：" + String.join("；", variantProblems));
        }

        // 注：combo 已在上面解析（类型需要它），这里直接用。

        return new MobGroup(id.toLowerCase(Locale.ROOT), type, displayName,
                weight, safetyCap, legalAffixIds, equipment, variant, combo);
    }

    /**
     * 解析 {@code variant:} 段 → 字符串到字符串的形态表。
     *
     * <p>★ 为什么**不在这里**校验键名/取值：那会让「加一个新变种」必须改两处
     * （解析器 + 应用器）。这里只把它当**传话**，真正的白名单在
     * {@code MobVariant.apply} 里 —— **未识别的键会被明确告警**（不静默忽略），
     * 这样既保持 schema 稳定，又不会出现「配了变种却没生效」的死配置。
     */
    private static Map<String, String> parseVariant(String path, ConfigurationSection sec) {
        Map<String, String> out = new LinkedHashMap<>();
        if (sec == null) {
            return out;
        }
        for (String key : sec.getKeys(false)) {
            Object raw = sec.get(key);
            if (raw == null) {
                throw new ConfigError(FILE, path + "." + key, "取值为空");
            }
            if (raw instanceof ConfigurationSection || raw instanceof java.util.List) {
                throw new ConfigError(FILE, path + "." + key,
                        "变种属性只接受标量（字符串/数字/布尔），实际是 "
                                + raw.getClass().getSimpleName());
            }
            out.put(key, String.valueOf(raw));
        }
        return out;
    }

    /** 解析「槽位 → 材质名」。{@code sec} 为 {@code null} 时返回空 Map。 */
    private static Map<EquipmentSlot, String> parseEquipment(String path, ConfigurationSection sec) {
        Map<EquipmentSlot, String> equipment = new EnumMap<>(EquipmentSlot.class);
        if (sec == null) {
            return equipment;
        }
        for (String slotKey : sec.getKeys(false)) {
            EquipmentSlot slot = SLOT_ALIASES.get(slotKey.toLowerCase(Locale.ROOT));
            if (slot == null) {
                throw new ConfigError(FILE, path + "." + slotKey,
                        "非法槽位名。合法取值：helmet / chestplate / leggings / boots / mainhand / offhand");
            }
            Object rawMat = sec.get(slotKey);
            if (rawMat == null) {
                continue; // null / 空 = 该部位不穿戴
            }
            if (!(rawMat instanceof String matName) || matName.isBlank()) {
                throw new ConfigError(FILE, path + "." + slotKey,
                        "应为材质名（如 minecraft:bow），或留空/null 表示不穿戴");
            }
            Material material = Material.matchMaterial(matName);
            if (material == null || !material.isItem()) {
                throw new ConfigError(FILE, path + "." + slotKey,
                        "不是合法物品材质：" + matName);
            }
            equipment.put(slot, matName.toLowerCase(Locale.ROOT));
        }
        return equipment;
    }

    /**
     * 解析 **{@code combo}（骑乘组合）**。
     *
     * <h2>为什么乘客用「映射」而不是「列表」</h2>
     * YAML 里写成
     * <pre>
     *   combo:
     *     mount: { type: CAMEL_HUSK }
     *     riders:
     *       r1: { type: HUSK,    equipment: { mainhand: minecraft:golden_spear } }
     *       r2: { type: PARCHED, equipment: { mainhand: minecraft:bow } }
     * </pre>
     * 用映射的好处是每个乘客都是一个真正的 {@code ConfigurationSection}，
     * 可以直接复用 {@link #parseEquipment} / {@link #parsePart} —— 列表形式拿到的是裸
     * {@code Map}，嵌套的装备还得手工转，容易出错。
     * {@code YamlConfiguration} 的键是保序的（{@code LinkedHashMap}），
     * 所以 {@code r1} 先于 {@code r2} 上座，顺序可控。
     */
    private static MobGroup.Combo parseCombo(String path, ConfigurationSection s) {
        ConfigurationSection combo = s.getConfigurationSection("combo");
        if (combo == null) {
            return null;
        }
        ConfigurationSection mountSec = combo.getConfigurationSection("mount");
        if (mountSec == null) {
            throw new ConfigError(FILE, path + ".combo",
                    "缺少 mount（载具）。骑乘组合必须至少有载具");
        }
        MobGroup.Part mount = parsePart(path + ".combo.mount", mountSec);

        List<MobGroup.Part> riders = new java.util.ArrayList<>();
        ConfigurationSection ridersSec = combo.getConfigurationSection("riders");
        if (ridersSec != null) {
            for (String key : ridersSec.getKeys(false)) {
                ConfigurationSection one = ridersSec.getConfigurationSection(key);
                if (one == null) {
                    throw new ConfigError(FILE, path + ".combo.riders." + key,
                            "应为配置段（含 type，可选 equipment / enchantments / baby）");
                }
                riders.add(parsePart(path + ".combo.riders." + key, one));
            }
        }
        // ★ 2026-10-12：**单载具、无乘客**的组合是**合法的**，不再报错。
        //
        //   为什么放宽（实测踩到）：用户要求「空手溺尸用原版 id，三叉戟溺尸改走 combo
        //   分支，给予装备刚好能顶掉原本的三叉戟」。而原先这里硬性拒绝零乘客，
        //   导致整个 mob-groups.yml 加载失败、插件被禁用。
        //
        //   单载具 combo 到底有什么意义：它是表达**「同一个原版实体 id 的另一种形态」**
        //   的手段 —— 组键可以自取名字（如 `drowned_trident`），
        //   而 combo.mount 能带 equipment / enchantments / baby。
        //   这类形态**没有独立的原版实体 id**，用普通 entry 又受
        //   「组键必须等于实体 id」的限制，所以只能借 combo 表达。
        //
        //   生成侧本来就支持：`BattleService.spawnCombo` 只是「生成载具 + 循环上座」，
        //   乘客为 0 时循环不执行，载具照样走 `setupComboPart`
        //   （它同样会**先清空六个装备槽**再按配置穿戴 —— 这正是「给装备顶掉原版武器」
        //    能成立的原因）。`Combo.unitCount()` 此时返回 1，与普通单只怪等价。
        return new MobGroup.Combo(mount, riders);
    }

    /** 解析组合里的一个部分（载具或乘客）。 */
    private static MobGroup.Part parsePart(String path, ConfigurationSection sec) {
        String typeName = sec.getString("type");
        if (typeName == null || typeName.isBlank()) {
            throw new ConfigError(FILE, path, "缺少 type（实体类型，如 ZOMBIE / CHICKEN）");
        }
        EntityType type;
        try {
            type = EntityType.valueOf(typeName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigError(FILE, path + ".type", "不是合法的实体类型：" + typeName);
        }
        Class<? extends org.bukkit.entity.Entity> cls = type.getEntityClass();
        if (cls == null || !org.bukkit.entity.LivingEntity.class.isAssignableFrom(cls)) {
            // 提前拦住「载具填了船/矿车」这类错误 —— 它们不是生物，装不了乘客
            throw new ConfigError(FILE, path + ".type",
                    "该实体类型不是生物，不能作为载具/乘客：" + typeName);
        }

        Map<EquipmentSlot, String> equipment =
                parseEquipment(path + ".equipment", sec.getConfigurationSection("equipment"));

        Map<EquipmentSlot, String> enchantments = new EnumMap<>(EquipmentSlot.class);
        ConfigurationSection enchSec = sec.getConfigurationSection("enchantments");
        if (enchSec != null) {
            for (String slotKey : enchSec.getKeys(false)) {
                EquipmentSlot slot = SLOT_ALIASES.get(slotKey.toLowerCase(Locale.ROOT));
                if (slot == null) {
                    throw new ConfigError(FILE, path + ".enchantments." + slotKey,
                            "非法槽位名（同 equipment 的取值）");
                }
                Object v = enchSec.get(slotKey);
                if (v instanceof String spec && !spec.isBlank()) {
                    enchantments.put(slot, spec.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return new MobGroup.Part(type, sec.getString("name"),
                sec.getBoolean("baby", false), equipment, enchantments);
    }

    private static double toDouble(Object v, String file, String path) {
        if (!(v instanceof Number n)) {
            throw new ConfigError(file, path, "缺失或不是数字");
        }
        return n.doubleValue();
    }

    // ---------------- 访问 ----------------

    public Map<String, MobGroup> groups() {
        return groups;
    }

    /**
     * ★ **大池**：全部怪物组的 id，顺序 = {@code mob-groups.yml} 的书写顺序。
     *
     * <p>重写后所有怪物组同属一个池（不再有「出生点 / 回合区间」的区隔），
     * 抽取只依赖回合权重表，所以池子就是本方法的返回值。
     * 顺序稳定是**硬要求** —— 抽取用的加权/均匀随机按索引取值。
     */
    public List<String> ids() {
        return List.copyOf(groups.keySet());
    }

    /** 各组「单只权重」（不含词条加成），用于启动期可达性探测。 */
    public double[] unitWeights() {
        double[] out = new double[groups.size()];
        int i = 0;
        for (MobGroup g : groups.values()) {
            out[i++] = g.weight();
        }
        return out;
    }

    public Optional<MobGroup> get(String id) {
        return Optional.ofNullable(groups.get(id.toLowerCase(Locale.ROOT)));
    }

    public int size() {
        return groups.size();
    }
}
