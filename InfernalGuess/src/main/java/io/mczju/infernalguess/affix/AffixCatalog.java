package io.mczju.infernalguess.affix;

import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ★ **词条目录**：全局词条池 + 权重分级 + 抽取限制。
 *
 * <h2>为什么要有这个类（2026-10-12 用户裁定）</h2>
 * 旧设计把词条清单写在 {@code mob-groups.yml} 的**每个怪组**里
 * （{@code infernal_affixes: [{id, weight-bonus, chance}]}）。
 * 后果是「只有少数生物能抽到词条」——僵尸永远只有剧毒+复生，
 * 骷髅永远只有弓手+披甲，其余 24 条词条与它们无关。
 *
 * <p>用户要求改为：**每种生物都能抽到绝大多数词条**，
 * 只有少数词条带附加限制（蜘蛛才会织网、近战才会狂暴…）；
 * 同时把 {@code weight-bonus} 从「每组自己填一个数」改成**统一分级**。
 *
 * <h2>分级（用户原话：「权重变为原先的 N%」）</h2>
 * <ul>
 *   <li><b>1 级 ×1.10</b>：tosser、mama、vengeance、archer、ender、poisonous、
 *       withering、quicksand、rust</li>
 *   <li><b>2 级 ×1.20</b>：armoured、1up、webber、necromancer、ghastly、gravity、
 *       sprint、firework、molten、lifesteal</li>
 *   <li><b>3 级 ×1.50</b>：mama+、armoured+、bullwark、berserk、storm、weakness、
 *       vexsummoner、plastic、<b>wardenwrath</b></li>
 * </ul>
 * 共 28 条 = {@link AffixRegistry#knownIds()} 的全集。
 * {@code wardenwrath} 是用户列表里**漏写**的一条（它已实现且一直在用），
 * 由用户当场裁定归 3 级。
 *
 * <h2>★ 多条命中时怎么算</h2>
 * 沿用既有公式（用户明确选择「乘法叠加」）：
 * {@code 组权重 = 单怪权重 × 数量 × Π(1 + 各条加成)}。
 * 于是 3 条 3 级 = {@code 1.5³ = 3.375}（用户已知并接受这个放大）。
 *
 * <h2>★ 判定口径：一律按 {@link EntityType}，不按实体实例</h2>
 * 加载 {@code mob-groups.yml} 时只有「实体类型」这一个上下文（还没有任何实体），
 * 若限制谓词依赖实体实例，加载期就无法推导合法集合，只能把池放宽到全量
 * ——那样「披甲」会被配给蜘蛛、真正抽到时才发现无效。
 *
 * <p>因此所有限制都写成**类型谓词**。好处不止于能加载期判定：
 * 它是**纯函数**，可以被离线单测完整枚举（{@link #legalIdsForEntityType}）。
 * 代价是「能不能近战」这类需要查询属性的判据不能直接用 {@code getAttribute}
 * —— 我们用等价的原版名单代替，并由测试守住「名单与属性判据一致」。
 *
 * <p>本类**不读配置、不碰 plugin**：分级与限制是**词条本身的属性**，
 * 写在代码里才能保证「注册表 = 池 = 生效范围」三者不会互相漂移
 * （旧设计里三者分别在代码、{@code mob-groups.yml}、运行期，漂移过多次）。
 */
public final class AffixCatalog {

    // ================= 分级 =================

    /** 1 级：权重 ×1.10。 */
    private static final List<String> TIER_1 = List.of(
            "tosser", "mama", "vengeance", "archer", "ender",
            "poisonous", "withering", "quicksand", "rust");

    /** 2 级：权重 ×1.20。 */
    private static final List<String> TIER_2 = List.of(
            "armoured", "1up", "webber", "necromancer", "ghastly",
            "gravity", "sprint", "firework", "molten", "lifesteal");

    /** 3 级：权重 ×1.50。 */
    private static final List<String> TIER_3 = List.of(
            "mama+", "armoured+", "bullwark", "berserk", "storm",
            "weakness", "vexsummoner", "plastic", "wardenwrath");

    /** 各级的**权重倍率**（用户口径：「权重变为原先的 110% / 120% / 150%」）。 */
    public static final double TIER_1_MULTIPLIER = 1.10;
    public static final double TIER_2_MULTIPLIER = 1.20;
    public static final double TIER_3_MULTIPLIER = 1.50;

    // ================= 限制谓词（全部按 EntityType） =================

    /**
     * 一条「该实体类型能否用某词条」的谓词。
     *
     * <p>用自定义函数式接口而不是 {@code Predicate<EntityType>}：调用点写作
     * {@code t -> t == EntityType.SPIDER} 时无需装箱，且语义读起来就是「按类型判定」。
     */
    @FunctionalInterface
    public interface TypeRule {
        boolean test(EntityType type);
    }

    /**
     * 「近战生物」名单 —— **人工维护，会随版本漂移**。
     *
     * <h2>⚠️ 局限（如实说明，不假装它是推导出来的）</h2>
     * 判据本应是原版的 {@code ATTACK_DAMAGE} 属性（有该属性 = 能打出近战伤害），
     * 但那需要**实体实例**，而加载期只有实体类型（见类注释）。
     * 因此这里退化为一份人工枚举的名单：
     * **26.2 新增的近战生物必须在原版确认后手工补进来**，
     * 否则它们拿不到 {@code berserk} / {@code sprint}。
     * 「名单 = 原版属性判据」这件事**离线测试无法验证**，
     * 已写进 {@code docs/12-实机冒烟清单.md} 交由用户在真服务器核对。
     *
     * <p>含义：**能主动打出近战伤害**的生物。远程为主但确实会近战的
     * （骷髅、流浪者、沼骸、焦骸）也算 —— 用户原话是「只能由近战生物抽到」，
     * 手持弓的骷髅被贴近时仍会挥弓近战。
     *
     * <p>刻意**不含**：苦力怕（只自爆，不挥击）、雪傀儡（雪球对绝大多数生物 0 伤害）、
     * 纯远程（恶魂）、纯被动（鱼、鱿鱼、蝌蚪、蝙蝠、鸡、猪、牛、羊）。
     */
    private static final Set<EntityType> MELEE = EnumSet.of(
            // 亡灵系
            EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED,
            EntityType.ZOMBIE_VILLAGER, EntityType.ZOMBIFIED_PIGLIN,
            EntityType.ZOMBIE_NAUTILUS,
            // 骷髅系（被贴近时会近战）
            EntityType.SKELETON, EntityType.STRAY, EntityType.BOGGED,
            EntityType.PARCHED, EntityType.WITHER_SKELETON,
            // 节肢 / 立方体
            EntityType.SPIDER, EntityType.CAVE_SPIDER,
            EntityType.SILVERFISH, EntityType.ENDERMITE,
            EntityType.SLIME, EntityType.MAGMA_CUBE, EntityType.SULFUR_CUBE,
            // 下界
            EntityType.PIGLIN, EntityType.PIGLIN_BRUTE,
            EntityType.HOGLIN, EntityType.ZOGLIN, EntityType.BLAZE,
            // 灾厄 / 精英
            EntityType.VINDICATOR, EntityType.PILLAGER, EntityType.EVOKER,
            EntityType.ILLUSIONER, EntityType.RAVAGER, EntityType.WITCH, EntityType.VEX,
            EntityType.IRON_GOLEM,
            // 中立 / 会主动攻击的动物
            EntityType.POLAR_BEAR, EntityType.PANDA, EntityType.WOLF,
            EntityType.GOAT, EntityType.LLAMA, EntityType.TRADER_LLAMA,
            EntityType.BEE, EntityType.FOX,
            // 其他
            EntityType.ENDERMAN, EntityType.PHANTOM, EntityType.BREEZE,
            EntityType.GUARDIAN, EntityType.ELDER_GUARDIAN,
            EntityType.WARDEN, EntityType.WITHER,
            EntityType.CAMEL, EntityType.CAMEL_HUSK);

    /** 「两种蜘蛛」。 */
    private static final Set<EntityType> SPIDERS =
            EnumSet.of(EntityType.SPIDER, EntityType.CAVE_SPIDER);

    /** 「海龟 / 劫掠兽 / 女巫 / 潜影贝」。 */
    private static final Set<EntityType> VENGEANCE_OK =
            EnumSet.of(EntityType.TURTLE, EntityType.RAVAGER, EntityType.WITCH, EntityType.SHULKER);

    /**
     * 「拥有幼体的生物」——按实体类型枚举。
     *
     * <p>为什么不用 {@code Ageable} 判定：{@code Ageable} 覆盖面太宽，
     * 而用户要的是「有幼体」= 会带小孩出场 / 会被 {@code mama} 的收养逻辑认作亲代。
     */
    private static final Set<EntityType> HAS_BABY = EnumSet.of(
            EntityType.CHICKEN, EntityType.TURTLE, EntityType.FROG,
            EntityType.ZOMBIE, EntityType.ZOMBIE_VILLAGER, EntityType.HUSK,
            EntityType.DROWNED, EntityType.PIGLIN, EntityType.PIGLIN_BRUTE,
            EntityType.ZOMBIFIED_PIGLIN, EntityType.HOGLIN, EntityType.ZOGLIN);

    /**
     * 「盔甲会被看见的生物」——有装备槽、原版会渲染盔甲的实体。
     *
     * <p>判据是「原版会不会给它渲染装备」。蜘蛛、苦力怕、史莱姆、蠹虫、鱼类
     * 没有装备槽，给它们穿盔甲等于石沉大海（而 {@code armoured} 会**当场**
     * 尝试给全套盔甲）。
     */
    private static final Set<EntityType> CAN_WEAR = EnumSet.of(
            EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED,
            EntityType.ZOMBIE_VILLAGER, EntityType.ZOMBIFIED_PIGLIN,
            EntityType.PIGLIN, EntityType.PIGLIN_BRUTE,
            EntityType.SKELETON, EntityType.STRAY, EntityType.BOGGED,
            EntityType.PARCHED, EntityType.WITHER_SKELETON,
            EntityType.VINDICATOR, EntityType.PILLAGER, EntityType.EVOKER,
            EntityType.ILLUSIONER,
            EntityType.ARMOR_STAND, EntityType.MANNEQUIN,
            EntityType.PLAYER, EntityType.VILLAGER, EntityType.WANDERING_TRADER,
            EntityType.ZOMBIE_HORSE, EntityType.SKELETON_HORSE);

    // ================= 池 =================

    /** 一条池内词条：id + 分级倍率 + 类型限制（{@code null} = 对所有生物开放）。 */
    public record Def(String id, double multiplier, TypeRule rule) {

        /** 本条的**权重加成**（{@code 0.10} 表示 +10%）。 */
        public double bonus() {
            return multiplier - 1.0;
        }

        /** 指定实体类型能否抽到本条。 */
        public boolean allows(EntityType type) {
            return rule == null || (type != null && rule.test(type));
        }
    }

    /**
     * 全局词条池：**保序**（LinkedHashMap）。
     *
     * <p>★ 顺序必须稳定 —— 抽取用种子派生的洗牌，但池本身的迭代顺序
     * 一旦跨 JVM 变化，「同种子 → 同剧本」就会失效
     * （本项目已被 {@code Map.copyOf} 的随机加盐坑过一次）。
     */
    private static final Map<String, Def> POOL = buildPool();

    private AffixCatalog() {
    }

    private static Map<String, Def> buildPool() {
        Map<String, Def> m = new LinkedHashMap<>();
        for (String id : TIER_1) {
            m.put(id, new Def(id, TIER_1_MULTIPLIER, ruleOf(id)));
        }
        for (String id : TIER_2) {
            m.put(id, new Def(id, TIER_2_MULTIPLIER, ruleOf(id)));
        }
        for (String id : TIER_3) {
            m.put(id, new Def(id, TIER_3_MULTIPLIER, ruleOf(id)));
        }
        return Collections.unmodifiableMap(m);
    }

    /**
     * 用户列出的 5 条限制；未列出的词条**对所有生物开放**（用户要求）。
     *
     * <p>只有「机制上必然无效」的几条额外加了谓词，见类注释。
     */
    private static TypeRule ruleOf(String id) {
        return switch (id) {
            // 1) 织网：只有两种蜘蛛
            case "webber" -> SPIDERS::contains;
            // 2) 狂暴 / 疾速：只有近战生物
            case "berserk", "sprint" -> MELEE::contains;
            // 3) 复仇：只有海龟 / 劫掠兽 / 女巫 / 潜影贝
            case "vengeance" -> VENGEANCE_OK::contains;
            // 4) 母体：只有拥有幼体的生物
            case "mama", "mama+" -> HAS_BABY::contains;
            // 5) 披甲：只有盔甲会被看见（能穿装备）的生物
            case "armoured", "armoured+" -> CAN_WEAR::contains;
            // 6) 结构性约束：这两条要改实体属性，无对应通道者必静默无效
            case "plastic" -> CAN_WEAR::contains;
            case "bullwark" -> MELEE::contains;
            default -> null;   // 其余全部开放
        };
    }

    // ================= 查询 =================

    /** 池里全部词条（保序）。 */
    public static List<Def> all() {
        return List.copyOf(POOL.values());
    }

    /** 池大小（28）。 */
    public static int size() {
        return POOL.size();
    }

    /** 取一条定义；未注册时返回 {@code null}。 */
    public static Def get(String affixId) {
        return affixId == null ? null : POOL.get(affixId.toLowerCase(Locale.ROOT));
    }

    /** 池里是否包含该 id。 */
    public static boolean contains(String affixId) {
        return get(affixId) != null;
    }

    /** 全部 id（保序）。 */
    public static List<String> ids() {
        return List.copyOf(POOL.keySet());
    }

    /**
     * 某个实体类型**合法可抽**的词条 id（保序）。
     *
     * <p>{@code type == null} 时返回全池 —— 「没有类型上下文」时宁可放宽，
     * 也不要让某个组合因为取不到类型而丢掉全部词条。
     */
    public static List<String> legalIdsForEntityType(EntityType type) {
        if (type == null) {
            return ids();
        }
        List<String> out = new ArrayList<>(POOL.size());
        for (Def d : POOL.values()) {
            if (d.allows(type)) {
                out.add(d.id());
            }
        }
        return out;
    }

    /**
     * 一组实体类型（骑乘组合的各部件）**并集**上合法可抽的词条 id（保序）。
     *
     * <p>组合的词条是**整组共享**的，所以「任一部件能用」即对组合法 ——
     * 取交集会让「载具是蜘蛛、乘客是骷髅」这类组合两边专长全丢。
     */
    public static List<String> legalIdsForUnion(List<EntityType> types) {
        java.util.LinkedHashSet<String> union = new java.util.LinkedHashSet<>();
        if (types == null || types.isEmpty()) {
            return ids();
        }
        for (EntityType t : types) {
            union.addAll(legalIdsForEntityType(t));
        }
        return List.copyOf(union);
    }

    /**
     * 某个类型集合抽到 {@code count} 条时的**期望权重倍率**（一阶近似）。
     *
     * <p>用途仅限启动自检与测试的**量级**参考：真正的权重由
     * {@code RunPlanner} 按实际抽中的条目逐条相乘得到。
     */
    public static double expectedMultiplier(List<String> legalIds, int count) {
        if (count <= 0 || legalIds == null || legalIds.isEmpty()) {
            return 1.0;
        }
        double sum = 0.0;
        int n = 0;
        for (String id : legalIds) {
            Def d = POOL.get(id);
            if (d != null) {
                sum += d.multiplier();
                n++;
            }
        }
        if (n == 0) {
            return 1.0;
        }
        double avg = sum / n;
        double m = 1.0;
        for (int i = 0; i < count; i++) {
            m *= avg;
        }
        return m;
    }

    // ================= 与注册表对账 =================

    /**
     * 校验「池」与「已注册实现」严格一致。
     *
     * <p>★ 这是替代旧设计里「每组自己列清单」的关键守卫：
     * 池里写了一条没实现的词条 → 抽到它以后装配阶段会**静默忽略**，
     * 但权重已经算上了它的加成 —— 正是我们在炒鸡上吃过的亏。
     *
     * @return 人类可读的问题列表（空 = 一致）
     */
    public static List<String> validateAgainst(Set<String> knownIds) {
        List<String> problems = new ArrayList<>();
        for (String id : knownIds) {
            String k = id.toLowerCase(Locale.ROOT);
            if (!POOL.containsKey(k)) {
                problems.add("已注册但**不在分级表里**：" + k + "（无法定价，会白拿权重加成）");
            }
        }
        for (String id : POOL.keySet()) {
            if (!knownIds.contains(id)) {
                problems.add("在分级表里但**没有实现**：" + id + "（抽到后装配阶段会静默忽略）");
            }
        }
        return problems;
    }

    /** 供测试使用的「近战名单」只读视图。 */
    public static Set<EntityType> meleeTypes() {
        return Collections.unmodifiableSet(MELEE);
    }

    /** 供测试使用的「能穿盔甲名单」只读视图。 */
    public static Set<EntityType> armourTypes() {
        return Collections.unmodifiableSet(CAN_WEAR);
    }

    /** 供测试使用的「有幼体名单」只读视图。 */
    public static Set<EntityType> babyTypes() {
        return Collections.unmodifiableSet(HAS_BABY);
    }
}

