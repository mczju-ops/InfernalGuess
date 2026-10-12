package io.mczju.infernalguess.config;

import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EquipmentSlot;

import java.util.List;
import java.util.Map;

/**
 * 一个「怪物组」：**键名 = 原版实体 id**（如 {@code zombie}），不带任何花名。
 *
 * <p>字段语义来自用户需求：
 * <ul>
 *   <li>{@code 原版id} → {@link #id} / {@link #entityType}</li>
 *   <li>{@code 单个权重} → {@link #weight}（**允许小数**，最终只需在最后一步四舍五入）</li>
 *   <li>★ {@code 安全上限} → {@link #safetyCap}（**不再是「数量区间」**：数量由权重算出来，
 *       这个值只在「不许太多实体」时才需要显式写，见 {@code amount.max}）</li>
 *   <li>★ {@code 合法词条} → {@link #legalAffixIds}（**由 {@code AffixCatalog} 推导**，
 *       不再是每组自己列的清单 —— 2026-10-12 用户裁定「每种生物都能抽到绝大多数词条」）</li>
 *   <li>{@code 装备} → {@link #equipment}（缺省置空；生成时会先清空原版随机装备再按此穿戴）</li>
 *   <li>★ {@code 骑乘组合} → {@link #combo}（缺省 {@code null} = 单只怪）</li>
 * </ul>
 */
public record MobGroup(
        String id,
        EntityType entityType,
        /** 显示名；{@code null} 表示由 display.yml 的默认名兜底。 */
        String displayName,
        /** 单怪权重，**允许小数**。 */
        double weight,
        /**
         * ★ **数量安全上限**（重写后的语义，取代旧的 {@code amount.min/max} 随机区间）。
         *
         * <p>数量现在由「回合目标权重 ÷ 单只权重」算出来，配置**不需要**再指定区间。
         * 这个值只在需要**压制实体数量**时才写出来 —— 典型是骑乘组合
         * （一只 = 载具 + 若干乘客，出 32 只就是上百个实体）。
         */
        int safetyCap,
        /**
         * ★ 本组**合法可抽**的词条 id（来自 {@link io.mczju.infernalguess.affix.AffixCatalog}）。
         *
         * <p>语义已变：旧字段 {@code affixes} 是「本组自带、按 chance 逐条掷」的清单；
         * 现在是「从全局池里**可以**抽到哪些」的白名单（池的其余部分对本组封闭）。
         * 绝大多数组就是全池 28 条，只有命中了限制谓词的组才更窄。
         */
        List<String> legalAffixIds,
        /** 装备槽 → 材质名（如 {@code minecraft:bow}）。空 Map = 裸装。 */
        Map<EquipmentSlot, String> equipment,
        /**
         * ★ **变种属性**（2026-10-12 新增，可空 Map = 原版形态）。
         *
         * <p>用途：原版实体类型**无法表达**的形态差异 —— 例如
         * 「闪电苦力怕」（{@code Creeper#setPowered(true)}）、
         * 「白狐狸」（{@code Fox#setFoxType(SNOW)}）。
         * 它们没有自己的实体 id，只能靠生成后再改属性。
         *
         * <p>取值是**字符串到字符串**（如 {@code charged: "true"}、
         * {@code fox_type: "SNOW"}），由 {@code MobVariant.apply} 解析 ——
         * 这样配置与代码之间只靠这一张约定表耦合，加新变种不必改 schema。
         */
        Map<String, String> variant,
        /**
         * ★ **骑乘组合**（可空）。
         *
         * <p>非空时本组生成的**不是单只怪**，而是「一只载具 + 若干乘客」；
         * 此时 {@link #entityType} 只作为**载具类型**的兜底展示用，
         * 真正的类型在 {@link Combo#mount()} / {@link Combo#riders()} 里。
         */
        Combo combo
) {

    public MobGroup {
        legalAffixIds = List.copyOf(legalAffixIds);
        equipment = Map.copyOf(equipment);
        variant = Map.copyOf(variant);
    }

    /**
     * **不使用组合、也不带变种**的构造（保留旧调用点）。
     *
     * <p>刻意留这个重载而不是到处传 {@code null} —— 加载期与测试里绝大多数组
     * 都是单只怪、原版形态，让它们的代码不必知道「组合」「变种」这两个概念存在。
     */
    public MobGroup(String id, EntityType entityType, String displayName, double weight,
                    int safetyCap, List<String> legalAffixIds,
                    Map<EquipmentSlot, String> equipment) {
        this(id, entityType, displayName, weight, safetyCap, legalAffixIds, equipment,
                Map.of(), null);
    }

    /** 是否带**变种属性**（原版实体类型表达不了的形态，如闪电苦力怕 / 白狐狸）。 */
    public boolean hasVariant() {
        return !variant.isEmpty();
    }

    /** 是否有骑乘组合。 */
    public boolean isCombo() {
        return combo != null;
    }

    /**
     * **一条已抽中的词条快照**（只存在于剧本里，**不再来自配置文件**）。
     *
     * <p>语义变更（2026-10-12）：旧版这是「配置里的一条词条候选」，
     * 带 {@code chance} 抽取概率；现在抽取由 {@code RunPlanner} 从
     * {@link io.mczju.infernalguess.affix.AffixCatalog} 的全局池里完成，
     * 本条只负责把「抽中了哪条 + 它的权重加成是多少」带进剧本，
     * 因此 {@code chance} 已删除（抽取概率不再存在 —— 抽中即生效）。
     */
    public record Affix(
            /** 词条 id，来自 {@code AffixCatalog} 的全局池。 */
            String id,
            /** 权重加成，如 {@code 0.10} 表示 ×1.10（由分级表决定，不再逐组配置）。 */
            double weightBonus
    ) {
    }

    /**
     * **骑乘组合的一个组成部分**（载具或乘客各算一个）。
     *
     * <p>每个部分都有自己的类型、装备与附魔 —— 因为原版骑乘组合里
     * 「骑手穿什么、拿什么」正是它战力的主要来源
     * （如骷髅马骑士的 Iron Helmet + Unbreaking III + Iron Sword）。
     */
    public record Part(
            EntityType entityType,
            /** 显示名；{@code null} 时用实体默认名。 */
            String displayName,
            /** 是否幼年（鸡骑士的骑手是幼年僵尸）。 */
            boolean baby,
            /** 装备槽 → 材质名。 */
            Map<EquipmentSlot, String> equipment,
            /**
             * 附魔：槽位 → {@code "附魔key:等级"}，如 {@code head: "unbreaking:3"}。
             *
             * <p>⭐ 注意 26.2 里「耐久」的枚举名是 <b>{@code unbreaking}</b>，
             * **不是** {@code durability}（后者已不存在）。
             */
            Map<EquipmentSlot, String> enchantments
    ) {
        public Part {
            equipment = Map.copyOf(equipment);
            enchantments = Map.copyOf(enchantments);
        }

        /** 简化的乘客构造（无附魔）。 */
        public Part(EntityType entityType, String displayName, boolean baby,
                    Map<EquipmentSlot, String> equipment) {
            this(entityType, displayName, baby, equipment, Map.of());
        }
    }

    /**
     * ★ **骑乘组合**：一只载具 + 按顺序上座的乘客。
     *
     * <h2>为什么是通用结构而不是 7 个特例</h2>
     * 用户说「复用性不高的写法也可以」，但通用 schema 反而**代码更少**，
     * 而且天然支持「一只载具载两名乘客」（沙漠骑兵小队：骆驼尸壳 +
     * 持金矛的尸壳 + 持弓的焦骸）—— 写成特例反而要额外处理 N 个乘客。
     *
     * <h2>★ 归属规则（用户裁定）</h2>
     * **载具与全部乘客一律 {@code counted = true}** —— 即「一个组合 = N 条命，
     * 全杀完才算淘汰」。用户理由：即使只剩孱弱的坐骑，它也会在 30 秒后被毒雾收掉，
     * 所以不需要为「落单坐骑」单独设计规则。
     */
    public record Combo(Part mount, List<Part> riders) {
        public Combo {
            riders = List.copyOf(riders);
        }

        /** 组合一共几个实体（= 几条命）。 */
        public int unitCount() {
            return 1 + riders.size();
        }
    }

    /**
     * 显示名（未配置时退回原版 id）。
     *
     * <p>注：本类**不提供**掷数量的方法——随机一律由 {@code run} 包的 {@code SeededRng} 负责，
     * 避免 {@code config} 包反向依赖 {@code run} 包，也避免误用非确定性随机源。
     */
    public String nameOrId() {
        return displayName == null || displayName.isBlank() ? id : displayName;
    }
}
