package io.mczju.infernalguess.battle;

import com.destroystokyo.paper.entity.ai.GoalType;
import io.mczju.infernalguess.InfernalGuessPlugin;
import io.mczju.infernalguess.config.ArenaConfig;
import io.mczju.infernalguess.config.DisplayConfig;
import io.mczju.infernalguess.config.GameConfig;
import io.mczju.infernalguess.config.MobGroup;
import io.mczju.infernalguess.affix.AffixRegistry;
import io.mczju.infernalguess.run.RoundPlan;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 一个回合的**战斗运行时**。
 *
 * <p>职责（对应 [docs/04](../../../../../../docs/04-技术方案.md) §6 的回合阶段）：
 * <ol>
 *   <li>{@link #prepare} —— 按 {@link RoundPlan} 生成双方生物、打阵营标记、锁 AI</li>
 *   <li>{@link #beginFight} —— 归还 AI、挂 {@link EnemyTargetGoal}</li>
 *   <li>{@link #tick} —— 5 tick 扫描兜底 + 胜负判定</li>
 *   <li>{@link #playVictoryEffect} —— 存活怪物处烟花 + 发光</li>
 *   <li>{@link #cleanup} —— 延迟清场（炒鸡怪走 {@code removeEntity}）</li>
 * </ol>
 *
 * <p>所有随机都由传进来的 {@link RoundPlan} 决定，本类**不再掷任何随机数**。
 */
public final class BattleService implements EnemyTargetGoal.EnemyLocator {

    /** 实体 UUID → 所属战斗，供事件监听器 O(1) 反查。 */
    private static final Map<UUID, BattleService> BY_ENTITY = new HashMap<>();

    /** 当前进行中的战斗（同一时间只允许一场：本插件是单房间小游戏）。 */
    private static volatile BattleService current;

    public static Optional<BattleService> current() {
        return Optional.ofNullable(current);
    }

    public static Optional<BattleService> forEntity(UUID uuid) {
        return Optional.ofNullable(BY_ENTITY.get(uuid));
    }

    private final InfernalGuessPlugin plugin;
    private final UUID battleId = UUID.randomUUID();
    private final BattleTracker tracker = new BattleTracker();
    private final Map<BattleSide, Team> teams = new HashMap<>();
    private final Map<BattleSide, Location> sideAnchors = new HashMap<>();

    private World world;
    private ArenaConfig.Arena arena;
    private int round = 1;
    private volatile BattleSide winner;
    private volatile boolean fighting;
    private volatile boolean cleaned;
    private BukkitTask heartbeat;
    /** 分出胜负时的回调（由回合状态机设置）。 */
    private volatile java.util.function.Consumer<BattleSide> winnerListener;
    /**
     * 我们正在主动生成生物。
     *
     * <p>{@link org.bukkit.event.entity.CreatureSpawnEvent} 在 {@code world.spawn()} **返回之前**就触发，
     * 那时 {@link #tagSide} 还没跑、追踪表里也还没有它。
     * 所以这段窗口必须屏蔽「收养衍生单位」逻辑，否则会把我们自己的兵误判成衍生单位。
     */
    private volatile boolean spawning;

    public BattleService(InfernalGuessPlugin plugin) {
        this.plugin = plugin;
    }

    // ================= 准备 =================

    /**
     * 生成双方生物并锁定 AI。
     *
     * @throws IllegalStateException 竞技场缺少必需点位 / 世界未加载
     */
    public void prepare(RoundPlan plan, ArenaConfig.Arena arena, World world) {
        if (current != null) {
            // 真·并发冲突（同一时间只允许一场战斗）。★ 「上一场没清干净」这种残留
            // 在更上游就处理掉了：`InfernalGuessGame.startSession()` 开头会先清掉
            // `current` 里残留的那一场（那时 `activeRound` 还是空的，判得准）。
            throw new IllegalStateException("已有一场战斗在进行（同一时间只允许一场）");
        }
        this.arena = arena;
        this.world = world;
        this.round = plan.round();

        // ★ 开局前先扫掉竞技场里的**残留实体**（用户 2026-10-13 报「前一局留下清不掉的实体」）。
        //   为什么必须有这一步：我们的单位都是 persistent（`setRemoveWhenFarAway(false)` +
        //   `setPersistent(true)`），正常靠 `cleanup()` 按 UUID 删除；但若上一局是**关服 /
        //   异常退出**结束的（`skipCleanupWhileDisabling` 刻意跳过清场，见那边的注释），
        //   那些单位就永远留在世界里 —— 它们属于**已经死掉的战斗实例**，新战斗的追踪表里
        //   没有它们的 UUID，于是**谁都清不掉**。这里用**同一条 `CleanupPolicy` 规则**
        //   （竞技场中心 64 格内、只管投射物/烟花/未接管怪物；绝不碰玩家、掉落物与其它插件的实体）
        //   在开局前兜一次底。
        purgeArenaLeftovers();

        GameConfig cfg = plugin.gameConfig();
        spawning = true;
        try {
            spawnSide(plan.left(), BattleSide.LEFT, cfg);
            spawnSide(plan.right(), BattleSide.RIGHT, cfg);
        } finally {
            spawning = false;
        }

        if (tracker.isEmpty()) {
            throw new IllegalStateException("双方一只生物都没生成出来（检查 mob-groups.yml 与点位绑定）");
        }

        current = this;

        // 5 tick 心跳：兜底收尸 + 判胜负
        heartbeat = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5L, 5L);
    }

    private void spawnSide(RoundPlan.SidePlan sidePlan, BattleSide side, GameConfig cfg) {
        for (RoundPlan.RosterEntry entry : sidePlan.entries()) {
            Location base = spawnLocation(entry.point(), side, cfg);
            if (base == null) {
                plugin.getLogger().warning("第 " + round + " 回合 " + side.id()
                        + " 侧点位 " + entry.point() + " 未绑定，跳过该组。");
                continue;
            }
            for (int i = 0; i < entry.amount(); i++) {
                // 同一点位的多只怪轻微错开，避免叠在一起
                Location loc = base.clone().add((i % 3 - 1) * 1.5, 0, (i / 3) * 1.5);
                spawnOne(entry, side, loc, cfg);
            }
        }
    }

    private void spawnOne(RoundPlan.RosterEntry entry, BattleSide side, Location loc, GameConfig cfg) {
        MobGroup group = entry.group();
        List<String> affixIds = new ArrayList<>();
        for (MobGroup.Affix a : entry.affixes()) {
            affixIds.add(a.id());
        }

        // 取世界与点位所在阵营的「基准点」，用于非计数单位的就近归属
        sideAnchors.putIfAbsent(side, loc);

        // ★ 骑乘组合：一次 spawnOne 生成「一只载具 + 全部乘客」
        if (group.isCombo()) {
            spawnCombo(group, group.combo(), side, loc, affixIds, cfg);
            return;
        }

        // 2026-10-11：已移除炒鸡 API 依赖 → 一律生成**普通原版怪**。
        // 「词条」这一概念保留在数据层（MobGroup.Affix / PDC），等自研词条系统接管。
        LivingEntity mob = spawnVanilla(group.entityType(), loc);
        if (mob == null) {
            plugin.getLogger().warning("生成失败：" + group.id() + " @ " + loc);
            return;
        }

        applyMobSetup(mob, group, side, affixIds, cfg);
        tracker.track(mob.getUniqueId(), side, true);
        BY_ENTITY.put(mob.getUniqueId(), this);
        // ★ 词条装配（读 PDC 里记的词条 id）—— STAT 类在这一步生效
        plugin.affixRegistry().equip(mob);
        diag(() -> "登记 " + mob.getType()
                + " tracked=" + tracker.isTracked(mob.getUniqueId())
                + " 追踪表=" + tracker.all().size()
                + " findEnemy=" + java.util.Optional.ofNullable(findEnemy(mob, side, cfg.followRange()))
                .map(t -> t.getType().name()).orElse("无"));
    }

    /**
     * ★ 生成一个**骑乘组合**：先造载具，再按顺序把乘客放上去。
     *
     * <h2>★ 归属规则（用户裁定）</h2>
     * **载具与全部乘客都 {@code counted = true}** ——
     * 「一个组合 = N 条命，全杀完才算淘汰」。理由（用户原话）：
     * 即使一场战斗只有坐骑活下来，它也会因为孱弱的血量**迅速死于 30 秒后的毒雾**。
     *
     * <p>⚠️ 因此必须走 {@link #tagSide}（**原子**地「写 PDC + 进追踪表」），
     * **不能**用 {@code adoptDerived} —— 后者内部把 {@code counted} 固定成 {@code false}
     * （那是给「唤魔者的恼鬼」这类衍生物用的）。若「计入了却没进追踪表」，
     * 毒雾覆盖不到它、存活判定也算不到它 → **对局可能真的卡死**。
     *
     * <h2>上座失败怎么办</h2>
     * {@code addPassenger} 返回 false 时**不能沉默**：那样会变成「多出来一只怪在地上乱跑」。
     * 这里会记警告 —— 但该乘客**仍是我方的计数单位**（已 tagSide + track），
     * 所以即使没坐上座，它在规则上依然是合法的参战单位，只是观感不对。
     */
    private LivingEntity spawnCombo(MobGroup group, MobGroup.Combo combo, BattleSide side,
                                    Location loc, List<String> affixIds, GameConfig cfg) {
        sideAnchors.putIfAbsent(side, loc);
        String displayName = group.displayName() == null ? group.id() : group.displayName();

        // 1) 载具
        LivingEntity mount = spawnVanilla(combo.mount().entityType(), loc);
        if (mount == null) {
            plugin.getLogger().warning("骑乘组合的载具生成失败：" + group.id()
                    + " mount=" + combo.mount().entityType());
            return null;
        }
        setupComboPart(mount, combo.mount(), side, displayName, affixIds, cfg);

        // 2) 乘客按顺序上座
        int seated = 0;
        for (MobGroup.Part rider : combo.riders()) {
            LivingEntity r = spawnVanilla(rider.entityType(), loc);
            if (r == null) {
                plugin.getLogger().warning("骑乘组合的乘客生成失败：" + group.id()
                        + " rider=" + rider.entityType());
                continue;
            }
            setupComboPart(r, rider, side, displayName, affixIds, cfg);
            if (mount.addPassenger(r)) {
                seated++;
            } else {
                plugin.getLogger().warning("乘客上座失败（载具装不下？）：" + group.id()
                        + " " + rider.entityType() + " → " + combo.mount().entityType()
                        + "（该乘客仍在场上作为我方计数单位）");
            }
        }
        plugin.getLogger().info("[IG] 骑乘组合 " + group.id() + "："
                + combo.mount().entityType() + " + " + seated + "/" + combo.riders().size()
                + " 名乘客（合计 " + combo.unitCount() + " 条命）");
        return mount;
    }

    /**
     * 骑乘组合里**一个部分**的统一配置。
     *
     * <p>对标 {@link #applyMobSetup}，差别只有两处：
     * 装备/附魔用**该部分自己的**，以及幼年标记（鸡骑士的骑手是幼年僵尸）。
     */
    private void setupComboPart(LivingEntity mob, MobGroup.Part part, BattleSide side,
                                String displayName, List<String> affixIds, GameConfig cfg) {
        if (cfg.clearRandomEquipment()) {
            applyEquipment(mob, part.equipment(), part.enchantments());
        }
        if (part.baby() && mob instanceof org.bukkit.entity.Ageable ageable) {
            ageable.setBaby();
        }
        if (cfg.preventItemPickup() && mob instanceof Mob m) {
            m.setCanPickupItems(false);
        }
        mob.setRemoveWhenFarAway(false);
        mob.setPersistent(true);
        AttributeInstance follow = mob.getAttribute(Attribute.FOLLOW_RANGE);
        if (follow != null) {
            follow.setBaseValue(cfg.followRange());
        }
        // ★ 载具与乘客**都是计数单位**（原子：写 PDC + 进追踪表）
        tagSide(mob, side, true, affixIds);
        applySideName(mob, displayName, side);
        preventZombification(mob);
        lockAi(mob);
        tracker.track(mob.getUniqueId(), side, true);
        BY_ENTITY.put(mob.getUniqueId(), this);
        plugin.affixRegistry().equip(mob);
    }

    /**
     * ★ **调试用定点注入**：在指定位置注入一只指定怪组的怪，并可**覆盖词条**。
     *
     * <h2>为什么必须有它</h2>
     * 在它之前，验证一条词条只能靠「改 mob-groups.yml + 换种子等剧本抽中」——
     * 而剧本由种子与波次池共同决定，实测中连续多局都没抽中想验的组，
     * **验证效率是全项目最大的瓶颈**（见 dev-log 035 §1、036 §5）。
     *
     * <p>这个方法让「指定怪 + 指定词条」变成一次命令的事，
     * 于是「每加一条词条就顺手验一条」终于可行。
     *
     * <p>与 {@link #spawnOne} 的差别只有一处：词条不是从剧本里取，而是**由调用方给定**
     * （不传时等于「不带词条」）。其余（原版生成 → 装备/名牌/PDC → 入追踪表 →
     * 装配词条）与正常生成**完全一致** —— 这点很重要：
     * 若这里走一条特殊路径，验证结论就不能代表真实开局。
     *
     * @param affixOverride 要装的词条 id；{@code null} 或空 = 不带词条
     * @return 注入成功的实体；失败返回 {@code null}
     */
    public LivingEntity summonGroup(MobGroup group, List<String> affixOverride,
                                    BattleSide side, Location loc) {
        if (cleaned || group == null || side == null || loc == null || loc.getWorld() == null) {
            return null;
        }
        List<String> ids = affixOverride == null ? List.of() : List.copyOf(affixOverride);
        // ★ 组合也要走这里 —— 否则调试入口会绕过组合分支，
        //   表现成「只生了一只载具、乘客不见踪影」，让人误以为组合功能坏了（实测踩过）。
        //   原则：**调试入口必须与正常生成走同一条装配链**，否则验证结论不代表真实开局。
        if (group.isCombo()) {
            return spawnCombo(group, group.combo(), side, loc, ids, plugin.gameConfig());
        }
        sideAnchors.putIfAbsent(side, loc);
        LivingEntity mob = spawnVanilla(group.entityType(), loc);
        if (mob == null) {
            return null;
        }
        // ★ 与正常生成走同一条装配链，保证验证结论有效
        applyMobSetup(mob, group, side, ids, plugin.gameConfig());
        tracker.track(mob.getUniqueId(), side, true);
        BY_ENTITY.put(mob.getUniqueId(), this);
        plugin.affixRegistry().equip(mob);
        // ★ **中途注入必须立刻解锁 AI**：装配链最后一步是 lockAi（准备阶段的正常做法），
        //   但若这一局**已经在打**，锁着的怪会**冻在原地一步不动** ——
        //   2026-10-13 实测：战斗中途注入的苦力怕 20 秒没挪窝，根本炸不到人。
        //   与 adoptDerived 的处理保持一致（那边也有同一行）。
        if (fighting && mob instanceof Mob midFightMob) {
            unlockAi(midFightMob, side);
        }
        plugin.getLogger().info("[IG] 定点注入 " + group.id() + "（" + side.id() + "）"
                + (ids.isEmpty() ? " 无词条" : " 词条=" + ids) + (fighting ? "（战斗中，AI 已解锁）" : ""));
        return mob;
    }

    /**
     * 按实体类型做**纯原版生成**（不涉及任何外部插件）。
     *
     * <p>返回 {@code null} 表示该 EntityType 无法解析成 LivingEntity，或生成被外部取消。
     */
    private static LivingEntity spawnVanilla(EntityType type, Location loc) {
        Class<? extends Entity> cls = type.getEntityClass();
        if (cls == null) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Class<? extends LivingEntity> living = (Class<? extends LivingEntity>) cls;
            return loc.getWorld().spawn(loc, living);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 生成后的统一配置：清装备 → 穿装备 → 属性 → 阵营标记 → 名牌 → 锁 AI。 */
    private void applyMobSetup(LivingEntity mob, MobGroup group, BattleSide side,
                               List<String> affixIds, GameConfig cfg) {
        // 1) 强制清空原版随机装备（用户第 7 条），再按配置穿戴
        if (cfg.clearRandomEquipment()) {
            applyEquipment(mob, group);
        }
        // 1.5) ★ 变种属性（闪电苦力怕 / 白狐狸 / 美西螈颜色）——
        //      必须在装备之后：变种只改形态，不碰装备。
        //      未识别的键**必须可见**（加载期已拦，这里是第二道保险）。
        if (group.hasVariant()) {
            List<String> failed = io.mczju.infernalguess.config.MobVariant
                    .apply(mob, group.variant());
            if (!failed.isEmpty()) {
                plugin.getLogger().warning("变种属性未能应用：" + group.id() + " → " + failed);
            }
        }
        // 2) 防捡东西 / 防远距离消失
        if (cfg.preventItemPickup() && mob instanceof Mob m) {
            m.setCanPickupItems(false);
        }
        mob.setRemoveWhenFarAway(false);
        mob.setPersistent(true);
        // 2.5) ★ 体型倍率（只为「远处也看得见」）—— 见 applyRenderScale 的注释
        applyRenderScale(mob, cfg);
        // 3) 索敌距离：要覆盖整个竞技场
        AttributeInstance follow = mob.getAttribute(Attribute.FOLLOW_RANGE);
        if (follow != null) {
            follow.setBaseValue(cfg.followRange());
        }
        // 4) 阵营标记（PDC 权威 + 计分板影子队伍）
        tagSide(mob, side, true, affixIds);
        // 5) 红/蓝名牌 —— 让观众一眼看出哪只是哪一方
        applySideName(mob, group, side);
        // 5.5) ★ 猪灵系列 / 疣猪兽：必须在这里就**免疫僵尸化**
        preventZombification(mob);
        // 6) 锁 AI（准备阶段不许动）
        lockAi(mob);
        diag(() -> "穿上 " + mob.getType()
                + " 主手=" + (mob.getEquipment() == null ? "?"
                : String.valueOf(mob.getEquipment().getItemInMainHand().getType()))
                + " sideOf=" + sideOf(mob).map(Enum::name).orElse("无"));
    }

    /** 每种「生物类型 × 倍率」只打一次体型放大日志。 */
    private final java.util.Set<String> scaleLogged = new java.util.HashSet<>();

    /**
     * ★ 按 {@code config.yml} 的倍率放大**包围盒太小**的单位，让它们在竞技场任何角落都看得见。
     *
     * <h2>为什么需要（26.2 反编译实测）</h2>
     * 客户端判「画不画这个实体」用的是
     * {@code Entity#shouldRenderAtSqrDistance} = {@code (包围盒三边均值 × 64 × 客户端实体渲染距离)²}
     * —— 只与**包围盒大小**有关 ⇒ 蠹虫/末影螨（0.4×0.3×0.4）默认只有 **23.5 格**，
     * 48×48 的场地对角约 68 格，它们跑到场那头就从画面上消失了。
     * 服务端的追踪距离（96~128 格）远大于此，**包都发了**，问题不在服务端。
     *
     * <h2>为什么用 {@code Attribute.SCALE}</h2>
     * 它是 26.2 里 {@code setSyncable(true)} 的原版属性（javap + 反编译实测）⇒
     * 客户端会同步到缩放后的包围盒，剔除距离随之变远；同时**不改血量/伤害**
     * （只改模型大小与判定框）。倍率写在 {@code battle.mob-attributes.scale-overrides}。
     *
     * <p>日志把**放大前后的可见距离**都打出来，方便事后对账（例如「狐狸 40.5 → 48.2 格」）。
     */
    private void applyRenderScale(LivingEntity mob, GameConfig cfg) {
        double scale = cfg.scaleFor(mob.getType());
        if (Math.abs(scale - 1.0) < 1e-6) {
            return;   // 没配置（或配成 1.0）就不动它
        }
        AttributeInstance attr = mob.getAttribute(Attribute.SCALE);
        if (attr == null) {
            return;   // 该生物没有 scale 属性（26.2 里所有 LivingEntity 都有，这里是保险）
        }
        // ★ 必须**先**读放大前的尺寸再改属性：改完的同一 tick 内
        //   `getWidth()/getHeight()` 还是旧值（属性→尺寸的刷新不走这条同步路径，
        //   第 10 轮实测把前/后写反过一次：日志出现「7.8 → 23.5」而不是「23.5 → 70.4」）。
        double before = RenderCulling.renderDistance(
                RenderCulling.bboxAverage(mob.getWidth(), mob.getHeight(), mob.getWidth()));
        attr.setBaseValue(scale);
        if (scaleLogged.add(mob.getType() + "|" + scale)) {
            plugin.getLogger().info("[IG] 体型放大 " + mob.getType() + " ×"
                    + String.format(java.util.Locale.ROOT, "%.2f", scale)
                    + "：客户端可见距离 " + trim(before) + " 格 → " + trim(before * scale)
                    + " 格（包围盒太小会在远处被客户端剔除）");
        }
    }

    /** 日志里把距离裁到 1 位，避免 `3.0000000000000004` 这种噪音。 */
    private static String trim(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    /**
     * **按需诊断**：只在 JVM 启动参数 {@code -Digdiag=true} 时输出。
     * <p>为什么做成开关而不是「加日志→跑→删」：这类「配置写了却没生效」的问题
     * 反复出现（见 dev-log 062），留一个**零开销、可随时打开**的探针，
     * 比每次临时改代码再删更可靠 —— 也不会因为忘记删而留下噪音。
     * 平时关闭时只有一个系统属性读取，不影响任何行为。
     */
    private static void diag(java.util.function.Supplier<String> msg) {
        if (Boolean.getBoolean("igdiag")) {
            org.bukkit.Bukkit.getLogger().info("[IG-DIAG] " + msg.get());
        }
    }

    /**
     * 按需诊断：**逐单位打印当前目标**（每 5 tick 心跳一次）。
     *
     * <p>用来定位「远程怪不打某个敌人」这类问题：`getTarget()` 是空、
     * 还是指向别人、还是指向了它却不开火 —— 三种情况对应完全不同的修法。
     */
    private void diagTargets() {
        if (!Boolean.getBoolean("igdiag")) {
            return;
        }
        // ① 追踪表内容（谁在候选表里）——「看不到某个敌人」最常见的根因就是它不在表里
        StringBuilder tsb = new StringBuilder();
        for (BattleTracker.TrackedUnit u : tracker.all()) {
            Entity e = Bukkit.getEntity(u.uuid());
            tsb.append(e == null ? "?" : e.getType().name()).append('[').append(u.side()).append(']')
                    .append(u.counted() ? "*" : "").append(' ');
        }
        plugin.getLogger().info("[IG-DIAG] 追踪表(" + tracker.all().size() + ")：" + tsb);

        // ② 每个单位的「当前目标」与「索敌函数会返回谁」—— 两者不一致就说明 goal 没在跑
        double range = plugin.gameConfig().followRange();
        StringBuilder sb = new StringBuilder();
        for (BattleTracker.TrackedUnit u : tracker.all()) {
            if (!(Bukkit.getEntity(u.uuid()) instanceof Mob m) || m.isDead()) {
                continue;
            }
            sb.append(m.getType()).append('[').append(u.side()).append("]→")
                    .append(m.getTarget() == null ? "无" : m.getTarget().getType().name())
                    .append("/find=")
                    .append(java.util.Optional.ofNullable(findEnemy(m, u.side(), range))
                            .map(t -> t.getType().name()).orElse("无"))
                    .append(' ');
        }
        plugin.getLogger().info("[IG-DIAG] 目标：" + sb);
    }

    /**
     * ★ 让猪灵系列与**疣猪兽**免疫僵尸化 —— 用户反馈「一直颤抖」的根因修复。
     *
     * <h2>为什么不能只在 {@code EntityTransformEvent} 里拦下转化</h2>
     * 原来的做法是取消 {@code TransformReason.PIGLIN_ZOMBIFIED} 的转化事件。但那个事件在
     * **转化流程走完时**才触发，而猪灵的「颤抖」是**转化倒计时期间**的动画
     * （{@code PiglinAbstract#isConverting()} 为真）。拦下终点、倒计时却不会停 ——
     * 于是它们**永远在抖**：抖 → 试图转化 → 被我们取消 → 重新开始抖。
     * （读 26.2 字节码：转化被取消时 `finishConversion` 会把 `timeInOverworld` 归零 ⇒
     * 那是「每 15 秒重试一次」的循环，期间**一直抖**；不是每 tick 重试。）
     *
     * <h2>正确的开关</h2>
     * 【javap 实测 26.2 API】{@code Piglin} / {@code PiglinBrute} 的共同父接口
     * {@code org.bukkit.entity.PiglinAbstract}，以及 {@code org.bukkit.entity.Hoglin}，
     * 都提供 {@code isImmuneToZombification()} / {@code setImmuneToZombification(boolean)}。
     * 这正是原版「下界里的猪灵不会僵尸化」用的同一个标志 —— 置 true 后既不转化，
     * 也不再有转化倒计时与颤抖。
     *
     * <p>★ 疣猪兽（{@code Hoglin}）必须一起处理：26.2 里它读的**同一条**环境属性
     * （{@code EnvironmentAttributes.PIGLINS_ZOMBIFY}，见 {@code Hoglin.isConverting()}），
     * 不设就会在开打 300 tick 后变成僵尸疣猪兽、跟同池的 {@code zoglin} 撞车。
     * 这一支与隔壁 Maggoteers 的 {@code MobFactory#preventOverworldZombification}
     * （2026-10-12 核对源码：{@code PiglinAbstract} + {@code Hoglin} 两支）写法一致。
     *
     * <p>⚠️ 必须**在生成时就设**（而不是等开打）：{@code lockAi} 走的是 {@code setAI(false)}
     * → {@code setNoAi(true)}，而 {@code isConverting()} 会因 {@code isNoAi()} 直接返回 false，
     * 准备阶段倒计时其实是**冻结**的；但 {@code beginFight()} 一开 AI，300 tick（约 15 秒）
     * 后就会转化 —— 长局必炸，所以生成时就得设。
     *
     * <p>本方法只管**本插件生成的**生物。`/summon`、刷怪笼、别的插件刷出来的猪灵/疣猪兽
     * 不经过这里；要在**世界级**上锁死，靠服务端数据包把主世界维度类型的
     * {@code minecraft:gameplay/piglins_zombify} 置 false（见
     * {@code deploy/datapack/infernalguess-no-zombify}）。
     *
     * <p>{@code EntityTransformEvent} 的取消**保留**作为兜底：万一有哪只猪灵
     * 在我们标记之前就已经开始转化。
     */
    private static void preventZombification(LivingEntity mob) {
        if (mob instanceof org.bukkit.entity.PiglinAbstract piglin) {
            piglin.setImmuneToZombification(true);
        } else if (mob instanceof org.bukkit.entity.Hoglin hoglin) {
            hoglin.setImmuneToZombification(true);
        } else {
            return;
        }
        logZombieState("生成时", mob);
    }

    /**
     * ★ 审计探针：把猪灵/疣猪兽的**免疫标与转化状态**打到日志里。
     *
     * <h2>为什么值得留一条常驻 INFO</h2>
     * 「对局里的猪灵一直颤抖」这个症状反复出现（dev-log 062 / 064）：
     * 26.2 的判定式是 {@code isConverting() = !isImmuneToZombification() && !isNoAi()
     * && 维度属性 piglins_zombify}，而**颤抖动画就看这个值**。所以只要看到
     * {@code immune=false} 或 {@code converting=true}，就能一眼区分三种情况：
     *
     * <ul>
     *   <li>生成时 {@code immune=false} → 免疫压根没设上（旧 jar / 没走到这段代码）；</li>
     *   <li>生成时 {@code immune=true}、开打时变 {@code false} → 中间被别的东西清掉了；</li>
     *   <li>两个时刻都 {@code immune=true} 却仍在抖 → 问题在客户端渲染或维度属性，不在实体标。</li>
     * </ul>
     *
     * 只在猪灵/疣猪兽生成与开打两个时刻各打一行，量很小（一局几行）。
     */
    private static void logZombieState(String where, LivingEntity mob) {
        String state;
        if (mob instanceof org.bukkit.entity.PiglinAbstract piglin) {
            state = "immune=" + piglin.isImmuneToZombification()
                    + " converting=" + piglin.isConverting()
                    + (piglin.isConverting() ? " conversionTime=" + piglin.getConversionTime() : "");
        } else if (mob instanceof org.bukkit.entity.Hoglin hoglin) {
            state = "immune=" + hoglin.isImmuneToZombification()
                    + " converting=" + hoglin.isConverting()
                    + (hoglin.isConverting() ? " conversionTime=" + hoglin.getConversionTime() : "");
        } else {
            return;
        }
        org.bukkit.Bukkit.getLogger().info("[IG] 僵尸化状态["
                + where + "] " + mob.getType() + " " + state);
    }

    /**
     * 给生物挂上阵营色的名牌（用户需求）。
     *
     * <p>名字形如 {@code <red>剧毒 迅捷 僵尸} —— **颜色区分阵营**（不加「左/右」字样），
     * 词条在前、生物名在后，**让玩家知道这只怪带了什么词条**。
     *
     * <p>词条来源：我方生成时写入的 PDC（见 {@code AffixRegistry#affixIdsOf}）。
     * 当前词条系统尚未接入，所以实际上只会显示生物名。
     */
    private void applySideName(LivingEntity mob, MobGroup group, BattleSide side) {
        applySideName(mob, group.displayName() == null ? group.id() : group.displayName(), side);
    }

    /** 名牌重载：直接给显示名（骑乘组合的各部分共用组合名）。 */
    private void applySideName(LivingEntity mob, String displayName, BattleSide side) {
        try {
            DisplayConfig display = plugin.displayConfig();
            String path = side == BattleSide.LEFT ? "battle.mob-name-left" : "battle.mob-name-right";
            if (!display.hasMessage(path)) {
                return;
            }
            List<String> affixes = plugin.affixRegistry().affixNamesOf(mob);

            StringBuilder sb = new StringBuilder();
            for (String a : affixes) {
                if (a != null && !a.isBlank()) {
                    sb.append(a.trim()).append(' ');
                }
            }
            // ★★ 在生物名之前**显式恢复阵营色**。
            //
            // 为什么必须：词条显示名各自带颜色（如 vengeance = "<red>复仇"），
            // MiniMessage 的颜色是**持续生效**的 → 最后一个词条的颜色会把它后面
            // 的生物名一起染色，**盖掉阵营色**。
            //
            // 实测症状（用户反馈 + 截图）：右阵营的「狂暴 复仇 僵尸疣猪兽」整条渲染成
            // **红色**，玩家是按颜色判断阵营的 → 把它误认成左方 → 报告
            // 「疣猪兽和女巫互相不攻击」（其实两者同在右侧，本来就不该互殴）。
            // 也就是说：**一个观感 bug 直接造成了错误的战况判断**。
            //
            // 取色方式：直接用模板自己的首个颜色标签（shipped 模板是 "<red>{name}" /
            // "<blue>{name}"），这样用户改模板颜色时这里自动跟随，不写死。
            sb.append(sideColorTag(display.raw(path)));
            sb.append(displayName == null ? "" : displayName);

            mob.customName(display.render(path, Map.of("name", sb.toString())));
            mob.setCustomNameVisible(true);
        } catch (Throwable t) {
            plugin.getLogger().warning("设置名牌失败（不影响对局）：" + t);
        }
    }

    /**
     * 从名牌模板里取出**首个 MiniMessage 颜色标签**（如 {@code <red>}）。
     *
     * <p>用途见 {@link #applySideName}：词条名自带颜色会把它后面的生物名一起染色，
     * 从而**盖掉阵营色**（实测导致玩家把同队的怪误认成对面）。这里把阵营色取出来，
     * 在生物名之前重新应用一次。
     *
     * <p>刻意**从模板里取**而不是写死 "red"/"blue" —— 用户改模板颜色时这里自动跟随。
     * 取不到就返回空串（功能不受影响，只是回到「颜色会泄漏」的旧观感）。
     */
    static String sideColorTag(String template) {
        if (template == null) {
            return "";
        }
        int open = template.indexOf('<');
        if (open < 0) {
            return "";
        }
        int close = template.indexOf('>', open);
        if (close < 0) {
            return "";
        }
        String tag = template.substring(open, close + 1);
        // 只接受形如 <red> / <#ff0000> / <bold> 的简单标签，
        // 避免把 <lang:xxx>、<arg:...> 之类误当颜色
        if (tag.length() > 24 || tag.contains("{") || tag.contains(" ") || tag.contains(":")) {
            return "";
        }
        return tag;
    }

    private Location spawnLocation(int point, BattleSide side, GameConfig cfg) {
        Map<Integer, ArenaConfig.Point> points =
                side == BattleSide.LEFT ? arena.leftPoints() : arena.rightPoints();
        ArenaConfig.Point p = points.get(point);
        return p == null ? null : p.toLocation(world);
    }

    /** 清空六个槽位后按配置穿戴，并把掉落率置 0。 */
    private void applyEquipment(LivingEntity mob, MobGroup group) {
        applyEquipment(mob, group.equipment(), Map.of());
    }

    /**
     * 按「槽位 → 材质名」+「槽位 → 附魔」穿戴。
     *
     * <p>抽成这个重载是为了**骑乘组合的每个部分各有自己的装备** ——
     * 例如骷髅马骑士的骑手要 Iron Helmet（Unbreaking III）+ Iron Sword，
     * 而僵尸马骑士的骑手要 IRON_SPEAR，两者与「组级装备」不是一回事。
     *
     * @param equipment    槽位 → 材质名（如 {@code minecraft:iron_spear}）
     * @param enchantments 槽位 → {@code "附魔key:等级"}（如 {@code head: "unbreaking:3"}）
     */
    private void applyEquipment(LivingEntity mob,
                                Map<org.bukkit.inventory.EquipmentSlot, String> equipment,
                                Map<org.bukkit.inventory.EquipmentSlot, String> enchantments) {
        EntityEquipment eq = mob.getEquipment();
        if (eq == null) {
            return;
        }
        // 清空原版随机装备
        eq.setHelmet(null);
        eq.setChestplate(null);
        eq.setLeggings(null);
        eq.setBoots(null);
        eq.setItemInMainHand(null);
        eq.setItemInOffHand(null);
        eq.setHelmetDropChance(0f);
        eq.setChestplateDropChance(0f);
        eq.setLeggingsDropChance(0f);
        eq.setBootsDropChance(0f);
        eq.setItemInMainHandDropChance(0f);
        eq.setItemInOffHandDropChance(0f);

        for (Map.Entry<org.bukkit.inventory.EquipmentSlot, String> e : equipment.entrySet()) {
            Material mat = Material.matchMaterial(e.getValue());
            if (mat == null) {
                continue;
            }
            ItemStack stack = new ItemStack(mat);
            applyEnchant(stack, enchantments.get(e.getKey()));
            setSlot(eq, e.getKey(), stack);
        }
    }

    /** 把物品放进指定槽位。 */
    private static void setSlot(EntityEquipment eq,
                                org.bukkit.inventory.EquipmentSlot slot, ItemStack stack) {
        switch (slot) {
            case HEAD -> eq.setHelmet(stack);
            case CHEST -> eq.setChestplate(stack);
            case LEGS -> eq.setLeggings(stack);
            case FEET -> eq.setBoots(stack);
            case HAND -> eq.setItemInMainHand(stack);
            case OFF_HAND -> eq.setItemInOffHand(stack);
            default -> { }
        }
    }

    /**
     * 给物品附魔，规格形如 {@code "unbreaking:3"}。
     *
     * <p>⭐ 26.2 里「耐久」的 key 是 <b>{@code unbreaking}</b>，**不是** {@code durability}。
     * 解析或查找失败都**静默跳过**：一条附魔写错不该让整局开不起来
     * （装备本体仍会穿上，只是没有附魔）。
     */
    private static void applyEnchant(ItemStack stack, String spec) {
        if (stack == null || spec == null || spec.isBlank()) {
            return;
        }
        int idx = spec.lastIndexOf(':');
        if (idx <= 0) {
            return;
        }
        String key = spec.substring(0, idx).trim().toLowerCase(java.util.Locale.ROOT);
        int level;
        try {
            level = Integer.parseInt(spec.substring(idx + 1).trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (level <= 0) {
            return;
        }
        try {
            org.bukkit.enchantments.Enchantment ench = org.bukkit.Registry.ENCHANTMENT
                    .get(org.bukkit.NamespacedKey.minecraft(key));
            if (ench == null) {
                return;
            }
            org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
            meta.addEnchant(ench, level, true);
            stack.setItemMeta(meta);
        } catch (Throwable ignored) {
            // 附魔名不合法 / 该物品不支持 → 保留无附魔的装备
        }
    }

    // ================= 阵营标记 =================

    /** 打 PDC 权威阵营 + 加入主计分板影子队伍。 */
    public void tagSide(LivingEntity mob, BattleSide side, boolean counted,
                        List<String> affixIds) {
        var pdc = mob.getPersistentDataContainer();
        pdc.set(BattleTags.side(), PersistentDataType.STRING, side.id());
        pdc.set(BattleTags.counted(), PersistentDataType.STRING, counted ? "1" : "0");
        pdc.set(BattleTags.battleId(), PersistentDataType.STRING, battleId.toString());
        if (!affixIds.isEmpty()) {
            pdc.set(BattleTags.affixes(), PersistentDataType.STRING, String.join(",", affixIds));
        }
        BY_ENTITY.put(mob.getUniqueId(), this);
        tracker.track(mob.getUniqueId(), side, counted);
        addToTeam(mob, side);
    }

    /**
     * 加入**主计分板**的影子队伍。
     *
     * <p>为什么必须用主计分板：原版索敌读的是世界/服务端计分板；
     * 为玩家单独建的计分板只影响该玩家的客户端显示（docs/07 §3）。
     * 队伍的作用是让原版 AI **不把队友当目标**；「打对面」由 {@link EnemyTargetGoal} 负责。
     */
    private void addToTeam(LivingEntity mob, BattleSide side) {
        try {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            Team team = teams.get(side);
            if (team == null) {
                String teamName = "ig_" + side.id() + "_" + battleId.toString().substring(0, 8);
                team = board.getTeam(teamName);
                if (team == null) {
                    team = board.registerNewTeam(teamName);
                }
                teams.put(side, team);
            }
            team.addEntry(mob.getUniqueId().toString());
        } catch (Throwable t) {
            plugin.getLogger().warning("加入计分板队伍失败（不影响索敌主逻辑）：" + t);
        }
    }

    /** 打标记：读取实体阵营。 */
    public static Optional<BattleSide> sideOf(Entity entity) {
        String v = entity.getPersistentDataContainer().get(BattleTags.side(), PersistentDataType.STRING);
        return v == null ? Optional.empty() : Optional.of(BattleSide.byId(v));
    }

    /** 打标记：实体是否为计数单位。 */
    public static boolean isCounted(Entity entity) {
        String v = entity.getPersistentDataContainer().get(BattleTags.counted(), PersistentDataType.STRING);
        return "1".equals(v);
    }

    // ================= 「最后一次击杀」归因（同归于尽判定用） =================

    /**
     * 一次**敌方击杀**的归因快照。
     *
     * @param killerSide 击杀者阵营
     * @param killerType 击杀者类型（同归于尽规则只关心是不是 {@code CREEPER}）
     * @param killerName 击杀者显示名（日志用）
     */
    public record Kill(BattleSide killerSide, org.bukkit.entity.EntityType killerType,
                       String killerName, org.bukkit.entity.EntityType victimType) {
    }

    /**
     * ★ 本回合**最后一次「敌方单位打死我方单位」**的记录（用户 2026-10-13 同归于尽规则）。
     *
     * <p>只记「敌方击杀」：环境伤害（毒雾/凋零/摔落）、自杀、同阵营误伤都**不覆盖**它 ——
     * 因为要回答的问题是「最后是谁把对方打死的」，不是「最后一个死的是谁」。
     */
    private Kill lastKill;

    /** 本回合最后一次敌方击杀（没有就返回 {@code null}）。 */
    public Kill lastKill() {
        return lastKill;
    }

    /**
     * 单位死亡时调用：若它是被**敌方参战单位**打死的，记下这次击杀。
     *
     * <p>为什么要从 {@code getLastDamageCause()} 反查而不是用 {@code Entity#getKiller()}：
     * 后者**只对玩家击杀返回非 null**（见 {@code AffixContext} 的注释），
     * 怪打怪的击杀它一律给 null ⇒ 拿不到「谁打死的」。
     */
    public void recordKill(LivingEntity victim) {
        Entity killer = resolveKiller(victim);
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        Optional<BattleSide> killerSide = sideOf(killer);
        Optional<BattleSide> victimSide = sideOf(victim);
        if (killerSide.isEmpty() || victimSide.isEmpty() || killerSide.get() == victimSide.get()) {
            return;   // 环境 / 自杀 / 同阵营：不是「敌方击杀」
        }
        String name = killer.customName() == null ? killer.getType().name()
                : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(killer.customName());
        lastKill = new Kill(killerSide.get(), killer.getType(), name, victim.getType());
        // ★ 苦力怕的击杀单独打一条日志：它是「同归于尽判胜」的唯一例外，
        //   而苦力怕本身很罕见（一局未必出现），所以这条日志不会刷屏，
        //   却能证明**归因确实抓到了它**（用户 2026-10-13 规则）。
        if (killer.getType() == org.bukkit.entity.EntityType.CREEPER) {
            plugin.getLogger().info("[IG] 苦力怕击杀归因：" + name + "（" + killerSide.get().id()
                    + " 方）打死 " + victim.getType() + " —— 若随后全场同时死亡，将判 "
                    + killerSide.get().id() + " 方胜");
        }
    }

    /**
     * ★ **开局前的残留清理**：把竞技场中心 {@link CleanupPolicy#ARENA_RADIUS} 格内的
     * 「投射物 / 烟花 / 未接管怪物」扫一遍（用户 2026-10-13 报「前一局留下清不掉的实体」）。
     *
     * <p>与 {@link #cleanup()} 里的兜底清理**共用同一份规则**（{@link #sweepArenaUnmanaged}）——
     * 区别只是：这里没有「本回合追踪表」，所以不存在「我们的实体」，`ours` 传空集即可。
     *
     * <p>⚠️ 为什么残留会存在：单位是 persistent，正常靠 `cleanup()` 按 UUID 删；
     * 但**关服 / 异常退出**时 `skipCleanupWhileDisabling` 会跳过清场，那些实体属于一个
     * 已经销毁的战斗实例 ⇒ 新战斗的追踪表里没有它们 ⇒ 谁都不会再删它们。
     */
    private void purgeArenaLeftovers() {
        if (world == null) {
            return;
        }
        // ★ 第一优先：**我们自己的**残留参战单位（按 PDC 的 battleId 识别，跨战斗实例有效）。
        //   为什么必须有这一步：铁律 10 的「未接管怪物」只覆盖 `Enemy`，而我们的单位里有
        //   **大量不是 Enemy 的** —— 骆驼/马/鸡（坐骑）、北极熊、村民系……它们同样
        //   persistent，同样会因为「关服/异常退出跳过清场」而永远留下（用户 2026-10-19 实测：
        //   「出现了上一局残留的坐骑」）。PDC 里的 `side`+`battleId` 是**随实体持久化**的，
        //   所以能精确认出「这是某一场已经结束的战斗的单位」—— 与类型无关。
        int own = sweepStaleOwnUnits();
        if (own > 0) {
            plugin.getLogger().warning("[IG] 开局前清理了 " + own
                    + " 个上一局残留的**参战单位**（含坐骑/乘客/召唤物；按 PDC 阵营+battleId 识别）。");
        }

        Location center = arenaCleanupCenter();
        if (center == null) {
            plugin.getLogger().warning("[IG] 竞技场缺少 player-spawn/spectate-spawn，"
                    + "已跳过开局前的范围清理（本插件不做世界级清理）。");
            return;
        }
        int swept = sweepArenaUnmanaged(center, java.util.Set.of());
        if (swept > 0) {
            plugin.getLogger().warning("[IG] 开局前清理了竞技场里 " + swept
                    + " 个上一局残留的实体（投射物/烟花/未接管怪物，半径 "
                    + (long) CleanupPolicy.ARENA_RADIUS + " 格）。"
                    + "若频繁出现，说明对局是被关服/异常退出的方式结束的。");
        }
    }

    /**
     * ★ 清掉**本插件自己在别的战斗实例里留下的**参战单位（含所有非 `Enemy` 的坐骑/动物）。
     *
     * <p>识别依据：PDC 的 {@link BattleTags#side()} + {@link BattleTags#battleId()} ——
     * 两者都随实体持久化，所以「被 `remove()` 忘了」的单位重启后仍能被认出来。
     * 判定：**带 side 标记**，且 battleId **不是本场**（旧版本没有 battleId 的也算残留）。
     *
     * <p>⚠️ 绝不碰玩家（铁律 10：玩家永不清理）。
     *
     * @return 移除的数量
     */
    private int sweepStaleOwnUnits() {
        int removed = 0;
        String mine = battleId.toString();
        for (Entity e : world.getEntities()) {
            if (e instanceof Player) {
                continue;   // 铁律 10：玩家永不清理
            }
            var pdc = e.getPersistentDataContainer();
            if (pdc.get(BattleTags.side(), PersistentDataType.STRING) == null) {
                continue;   // 没有阵营标记 ⇒ 不是我们的参战单位
            }
            String tag = pdc.get(BattleTags.battleId(), PersistentDataType.STRING);
            if (tag != null && tag.equals(mine)) {
                continue;   // 本场的（理论上 prepare 时不存在，保险起见跳过）
            }
            e.remove();
            removed++;
        }
        return removed;
    }

    /**
     * 按 {@link CleanupPolicy} 扫一遍竞技场范围内的「未接管」实体。
     *
     * @param center 竞技场中心（玩家出生点 / 观战席）
     * @param ours   **本实例接管的**实体 UUID（这些不在这里清 —— 由调用方按 UUID 处理）
     * @return 实际移除的数量
     */
    private int sweepArenaUnmanaged(Location center, java.util.Set<UUID> ours) {
        int swept = 0;
        for (Entity e : world.getEntities()) {
            if (ours.contains(e.getUniqueId())) {
                continue;   // 本插件接管的实体：按 UUID 清（无距离限制，铁律 10 第 1 条）
            }
            Location at = e.getLocation();
            if (!CleanupPolicy.shouldRemoveUnmanaged(
                    e instanceof Player, e instanceof Item,
                    e instanceof org.bukkit.entity.Projectile, e instanceof Firework,
                    e instanceof org.bukkit.entity.Enemy,
                    at.getX() - center.getX(),
                    at.getY() - center.getY(),
                    at.getZ() - center.getZ())) {
                continue;
            }
            // 投射物**不是** LivingEntity，旧实现曾漏掉它们 ——
            // 实测症状：溺尸丢出的三叉戟、骷髅射出的箭插在地上不消失。
            e.remove();
            swept++;
        }
        return swept;
    }

    /**
     * **仅供调试**：把本回合所有「本插件接管的单位」一次性打死（按 UUID，无距离限制 —— 铁律 10 允许）。
     *
     * <p>为什么需要：同归于尽是**运气事件**（毒雾同时带走双方、苦力怕与敌人同归于尽……），
     * 没有这个入口就只能等它自然发生，「真平局 / 苦力怕判胜」两条分支永远无法在实机里核对。
     *
     * @return 被打死的单位数
     */
    public int killAllTrackedForDebug() {
        int killed = 0;
        for (BattleTracker.TrackedUnit unit : tracker.all()) {
            Entity e = Bukkit.getEntity(unit.uuid());
            if (e instanceof LivingEntity le && !le.isDead() && le.isValid()) {
                le.setHealth(0.0);   // 与毒雾致死同一条死亡事件路径（会走 recordKill / handleDeath）
                killed++;
            }
        }
        return killed;
    }

    /** 从「最后一击」反查击杀者实体：直接伤害取 damager，投射物取 shooter。 */
    private static Entity resolveKiller(LivingEntity victim) {
        var cause = victim.getLastDamageCause();
        if (!(cause instanceof org.bukkit.event.entity.EntityDamageByEntityEvent byEntity)) {
            return null;
        }
        Entity damager = byEntity.getDamager();
        if (damager instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Entity shooter) {
            return shooter;   // 箭/火球：算射手的击杀
        }
        return damager;
    }

    // ================= AI 控制 =================

    private void lockAi(LivingEntity entity) {
        entity.setAI(false);
        entity.setCollidable(false);
        if (entity instanceof Mob m) {
            m.setAware(false);
            m.setTarget(null); // setTarget 只在 Mob 上
        }
    }

    /** 归还 AI：开 AI、清原版索敌、挂自定义索敌。 */
    public void beginFight() {
        if (cleaned) {
            return;
        }
        fighting = true;
        for (BattleTracker.TrackedUnit unit : tracker.all()) {
            Entity e = Bukkit.getEntity(unit.uuid());
            if (e instanceof Mob mob && !mob.isDead()) {
                unlockAi(mob, unit.side());
            }
        }
        schedulePoisonFog();
    }

    // ================= 毒雾 =================

    /** 凋零 I 每 40 tick（2 秒）造成 1 点伤害，毒雾按同样节奏推进。 */
    private static final long FOG_INTERVAL_TICKS = 40L;

    private BukkitTask fogTask;
    private boolean fogAnnounced;

    /**
     * 安排毒雾（用户需求：开战后 30 秒给场上所有生物挂永久凋零 I）。
     *
     * <h2>为什么需要它</h2>
     * 远程对射（如骷髅 vs 骷髅）可能长时间拉锯，实测出现过打满 120 秒被超时兜底的情况。
     * 毒雾让双方**同时持续掉血**，于是战斗一定会在 {@code max-fight-seconds} 之前自然分出胜负，
     * 超时强制判定退化为「几乎不会发生」的保险。
     *
     * <h2>为什么是「重复任务」而不是「一次性挂效果」</h2>
     * 战斗中期才被**收养的衍生单位**（如唤魔者的恼鬼）也应当被毒雾覆盖，
     * 重复心跳天然处理这一点。
     *
     * <h2>免疫者按原版规则放行</h2>
     * 原版**只有凋零（Boss）与凋灵骷髅免疫凋零**，而这两者本身伤害极高、
     * 不会造成拉锯战，因此**不做任何兜底伤害** —— 它们免疫毒雾是符合预期的。
     * （早期版本曾对免疫者补等量直接伤害，经用户裁定后移除。）
     */
    private void schedulePoisonFog() {
        GameConfig cfg = plugin.gameConfig();
        if (!cfg.poisonFogEnabled()) {
            return;
        }
        long delay = cfg.poisonFogSeconds() * 20L;
        fogTask = Bukkit.getScheduler().runTaskTimer(plugin, this::poisonFogTick,
                delay, FOG_INTERVAL_TICKS);
        plugin.getLogger().info("[IG] 毒雾将在开战后 " + cfg.poisonFogSeconds()
                + " 秒升起（凋零 " + roman(cfg.poisonFogAmplifier()) + "）");
    }

    /** 毒雾心跳：给所有参战生物补挂永久凋零。 */
    private void poisonFogTick() {
        if (cleaned || !fighting) {
            return;
        }
        GameConfig cfg = plugin.gameConfig();
        int fogged = 0;
        int immune = 0;
        for (BattleTracker.TrackedUnit unit : tracker.all()) {
            Entity e = Bukkit.getEntity(unit.uuid());
            if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) {
                continue;
            }
            try {
                le.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,
                        PotionEffect.INFINITE_DURATION, cfg.poisonFogAmplifier(),
                        false, true, true));
            } catch (Throwable t) {
                // 极少数实体不接受药水效果
            }
            if (le.hasPotionEffect(PotionEffectType.WITHER)) {
                fogged++;
            } else {
                // 原版免疫凋零的只有凋零与凋灵骷髅；它们伤害高、不会拖局，放行即可
                immune++;
            }
        }
        if (!fogAnnounced) {
            fogAnnounced = true;
            String level = roman(cfg.poisonFogAmplifier());
            plugin.getLogger().info("[IG] ☠ 毒雾升起：凋零 " + level + " 已覆盖 " + fogged
                    + " 个生物" + (immune > 0
                    ? "（" + immune + " 个凋零免疫者不受影响：凋零 / 凋灵骷髅）" : ""));
            if (plugin.displayConfig().hasMessage("battle.poison-fog")) {
                broadcast("battle.poison-fog", Map.of("level", level));
            }
        }
    }

    /** 0 → I，1 → II …（仅用于日志美观）。 */
    private static String roman(int amplifier) {
        return switch (amplifier) {
            case 0 -> "I";
            case 1 -> "II";
            case 2 -> "III";
            case 3 -> "IV";
            case 4 -> "V";
            default -> String.valueOf(amplifier + 1);
        };
    }

    /** 解除锁定：开 AI、设索敌距离、清原版 target goal、挂我们的索敌 Goal。 */
    private void unlockAi(Mob mob, BattleSide side) {
        GameConfig cfg = plugin.gameConfig();
        mob.setAI(true);
        mob.setAware(true);
        mob.setCollidable(true);
        // ★ 开打这一刻正好是「转化倒计时能开始走」的时刻（isConverting 要求 !isNoAi()）：
        //   在这里复查一次免疫标，能抓出「生成时设上了、中间被清掉」的情况。
        logZombieState("开打", mob);
        if (mob.getAttribute(Attribute.FOLLOW_RANGE) != null) {
            mob.getAttribute(Attribute.FOLLOW_RANGE).setBaseValue(cfg.followRange());
        }
        try {
            var goals = Bukkit.getMobGoals();
            goals.removeAllGoals(mob, GoalType.TARGET);
            // ★★ 摘掉「分心」goal（用户实测：毒雾升起后被动生物失去攻击欲望；
            //    2026-10-13 追加：**溺尸在干地上会去找水**，于是宁愿找水也不打）
            java.util.List<String> removedGoals = removeDistractionGoals(goals, mob);
            // ★ 山羊：换成**尖叫山羊**（原版冲撞间隔 5~15 秒，远短于普通山羊的 30 秒~5 分钟），
            //    实际冲撞节奏由 tickGoatRams 控制（用户指定 2 秒）。
            if (mob instanceof org.bukkit.entity.Goat goat) {
                goat.setScreaming(true);
            }
            if (!goals.hasGoal(mob, EnemyTargetGoal.key())) {
                goals.addGoal(mob, 1, new EnemyTargetGoal(mob, side, this, cfg.followRange()));
            }
            // ★ 载具（有乘客的怪）的移动归属：
            //   项目早期是「载具一律挂我们的导航 Goal」，但那会**顶掉载具自己的原版近战 goal**
            //   —— 因为同一时刻只能运行一个 MOVE 类 goal。
            //   实测症状（用户反馈）：唤魔者骑士把女巫顶在墙角，却**只有唤魔者在输出**
            //   （劫掠兽不咬人，而唤魔者的魔法打女巫伤害很低）。
            //   现在的规则：
            //     载具**自带**原版近战模组（劫掠兽 / 蜘蛛）→ **不插手**，让它自己追打
            //     载具是**被动**生物（僵尸马 / 骷髅马 / 鸡 / 炽足兽 / 骆驼）→ 才挂导航 Goal
            //   ★ 这条规则自动只命中 spider_jockey 与 evoker_knight 这两个组合。
            // ★★ 判据必须在**挂我们自己的 goal 之前**算：
            //   `isBrainOnly` 的定义是「一个 goal 都没有」，而下一段就会挂上
            //   EnemyTargetGoal —— 先挂再算 ⇒ 永远算不出 brain 型
            //   （2026-10-13 首次实机验证就踩到：疣猪兽被打上 `mount_drive`，
            //     日志里 `MOVE=[mount_drive]` 把它自己招了）。
            String attackKey = nativeAttackGoal(goals, mob);
            boolean hasAttack = attackKey != null;
            boolean brainOnly = isBrainOnly(goals, mob);
            // ★ 走原版 AI 的例外名单（当前为**空** —— 用户已撤销疣猪兽那条例外，
            //   见 VANILLA_AI_TYPES 的注释）。
            boolean vanillaAi = VANILLA_AI_TYPES.contains(mob.getType());
            if (brainOnly) {
                // brain 型：setTarget 对它们多半是空操作（getTarget() 读 brain），
                // 但心跳里还有 forceBrainAnger（猪灵的 ANGRY_AT）与反复重申，
                // 至少别让它们「连兜底都没有」。
                brainMobs.add(mob.getUniqueId());
            }
            if (!goals.hasGoal(mob, EnemyTargetGoal.key())) {
                goals.addGoal(mob, 1, new EnemyTargetGoal(mob, side, this, cfg.followRange()));
            }
            // ★ 载具（有乘客的怪）的移动归属：
            //   项目早期是「载具一律挂我们的导航 Goal」，但那会**顶掉载具自己的原版近战 goal**
            //   —— 因为同一时刻只能运行一个 MOVE 类 goal。
            //   实测症状（用户反馈）：唤魔者骑士把女巫顶在墙角，却**只有唤魔者在输出**
            //   （劫掠兽不咬人，而唤魔者的魔法打女巫伤害很低）。
            //   现在的规则：
            //     载具**自带**原版近战模组（劫掠兽 / 蜘蛛）→ **不插手**，让它自己追打
            //     载具是**被动**生物（僵尸马 / 骷髅马 / 鸡 / 炽足兽 / 骆驼）→ 才挂导航 Goal
            //   ★ 这条规则自动只命中 spider_jockey 与 evoker_knight 这两个组合。
            // ★★ 2026-10-13 放宽：由「只有载具」放宽到「**所有没有自带攻击 goal 的单位**」。
            //    原因（用户反馈）：海龟 / 美西螈这类怪开局后**只会原地站着或闲逛** ——
            //    因为它们身上**没有任何会把它们带向敌人的 MOVE goal**
            //    （我们的 EnemyTargetGoal 是 TARGET 类，只负责 setTarget，不负责走路）。
            //    载具当年就是因为同一个原因才需要它，现在把同一套办法给所有「没有输出模组」的怪。
            //    ⚠️ 但「没有攻击 goal」必须**真的没有**：判据由手写白名单改成扫 goal 清单
            //    （2026-10-13 狐狸事故），否则会把「key 名字没在白名单里」的怪当成没输出，
            //    然后用自己的 MOVE goal 把它的近战 goal 顶掉 —— 越修越坏。
            //    ★ 并且「brain 型」不等于「不驱动」：美西螈（brain 型）就是靠我们的驱动
            //    走向敌人的（dev-log 073 实测）。只有用户点名的生物走原版 AI。
            boolean needsOurDrive = !hasAttack && !vanillaAi;
            if (needsOurDrive && !goals.hasGoal(mob, MountDriveGoal.key())) {
                goals.addGoal(mob, 0, new MountDriveGoal(mob, side, this, cfg.followRange()));
            }
            // ★ 把「AI 装载决策」打出来 —— 否则这几项改动静默、事后无法验证。
            //   每种「阵营+生物类型+决策组合」只打一次，避免每回合刷一屏日志。
            //   ★ 2026-10-13 追加 MOVE goal 清单：它是「这只怪会不会真的去打」的**直接证据**
            //     （溺尸不攻击那次就是因为看不到它当时挂的是哪个 MOVE goal，只能猜）。
            String aiSig = side + "|" + mob.getType() + "|" + needsOurDrive + "|" + hasAttack + "|" + vanillaAi;
            if (aiLogged.add(aiSig)) {
                plugin.getLogger().info("[IG] AI 装载 " + side + " " + mob.getType()
                        + "：索敌✓｜导航" + (needsOurDrive ? "✓(我们驱动)"
                                : vanillaAi ? "✗(原版 AI·点名例外)" : "✗(自带攻击)")
                        + "｜自带输出" + (hasAttack ? "✓(" + attackKey + ")" : "✗")
                        + "｜已摘=" + removedGoals
                        + "｜MOVE=" + describeMoveGoals(goals, mob)
                        + (brainOnly ? "｜brain 型" : "")
                        + (mob instanceof org.bukkit.entity.Goat ? "｜尖叫山羊✓" : ""));
            }
        } catch (Throwable t) {
            // ★ brain 型生物（猪灵 / 疣猪兽 / 监守者 …）没有 goal selector，addGoal 会抛异常。
            //   退化为直接 setTarget，并登记下来，由 5 tick 心跳**反复重申**目标 ——
            //   否则 brain 会很快把目标改回去，表现为「站着不动」。
            brainMobs.add(mob.getUniqueId());
            mob.setTarget(findEnemy(mob, side, cfg.followRange()));
            // ★ 这条日志和上面那条一样重要：否则「brain 型走了兜底」这件事**完全没有痕迹**，
            //   事后只能靠猜（2026-10-13 排查疣猪兽骑士时正是如此）。
            String brainSig = "brain|" + side + "|" + mob.getType();
            if (aiLogged.add(brainSig)) {
                plugin.getLogger().info("[IG] AI 装载 " + side + " " + mob.getType()
                        + "：索敌✓(心跳重申)｜导航✗(brain 型·原版 AI)"
                        + "｜自带输出✗(" + t.getClass().getSimpleName() + ")"
                        + "｜MOVE=" + describeMoveGoals(Bukkit.getMobGoals(), mob));
            }
        }
    }

    /** brain 型生物（无 goal selector）的 UUID；需要靠心跳重申目标。 */
    private final java.util.Set<UUID> brainMobs = new java.util.HashSet<>();

    /**
     * 美西螈「假死复活」状态：UUID → 已经用掉几次复活。
     *
     * <p>★ 必须有上限（{@code axolotl-revive.count}）：不限次数就等于**永不死的单位**，
     * 回合永远打不完、斩杀线也失去意义。用掉上限后它就能正常被打死。
     */
    private final Map<UUID, Integer> axolotlRevives = new HashMap<>();

    /** 正在「假死」的美西螈（假死期间**免疫一切伤害**，否则躺在地上就被补刀，「复活」就不存在）。 */
    private final java.util.Set<UUID> playingDeadAxolotls = new java.util.HashSet<>();

    /** 本回合拦下的传送次数（按实体类型）—— 诊断用，清场时汇总打印一次。 */
    private final Map<org.bukkit.entity.EntityType, Integer> blockedTeleports =
            new java.util.EnumMap<>(org.bukkit.entity.EntityType.class);

    /**
     * 竞技场里**必须摘掉**的「分心」goal。
     *
     * <p>它们全都是 <b>MOVE</b> 类 —— 原版里同一时刻**只能跑一个 MOVE goal**，
     * 一旦被激活就会把「攻击」顶掉，表现出来就是**这只怪站着/乱走，不攻击**。
     *
     * <h2>已经发生过的三起（都是用户实测）</h2>
     * <ol>
     *   <li><b>{@code PANIC} 一族</b>（2026-10-11）：毒雾**持续**造成伤害 →
     *       {@code getLastHurtByMob() != null} 一直成立 → 惊恐 goal 一直抢占行为槽
     *       （优先级高于我们的索敌 goal）→ 它们只会一直逃跑。</li>
     *   <li>★ <b>{@code DROWNED_GO_TO_WATER} 等溺尸专属 goal</b>（2026-10-13）：
     *       原版溺尸**在陆地上**会去找水 / 上岸 / 上浮（{@code DROWNED_GO_TO_WATER}、
     *       {@code DROWNED_GO_TO_BEACH}、{@code DROWNED_SWIM_UP}），
     *       而竞技场是**干地**（`test1` 在 y≈92 的石台上）→ 它宁愿满地图找水也不打敌人。
     *       这就是用户反馈的「**溺尸不攻击**」最可能的机制。</li>
     *   <li><b>{@code FLEE_SUN} / {@code TRY_FIND_WATER}</b>（同族）：僵尸家族的
     *       「躲太阳 / 找水」—— 白天同样会顶掉攻击。</li>
     *   <li>★★ <b>熊猫的「躺平 / 打滚 / 坐下 / 躲怪物」</b>（2026-10-13 用户反馈）：
     *       这一族**不是**抢 MOVE 槽，而是把攻击 goal 自己的 {@code canUse()} 闸门关上
     *       （{@code Panda#canPerformAction()}）—— 机制不同、症状一样（闲逛不打人）。
     *       详见下面列表里的注释。</li>
     * </ol>
     *
     * <p>类型是异构的（{@code GoalKey<Creature>} / {@code GoalKey<Drowned>} / …），
     * 所以先按 {@code getEntityClass().isInstance(mob)} 过滤，再转型调用；
     * 摘不掉就跳过（不是每种生物都有这些 goal）。
     */
    private static final List<com.destroystokyo.paper.entity.ai.GoalKey<?>> DISTRACTION_KEYS = List.of(
            com.destroystokyo.paper.entity.ai.VanillaGoal.PANIC,
            com.destroystokyo.paper.entity.ai.VanillaGoal.TAMABLE_ANIMAL_PANIC,
            com.destroystokyo.paper.entity.ai.VanillaGoal.FOX_PANIC,
            com.destroystokyo.paper.entity.ai.VanillaGoal.RABBIT_PANIC,
            com.destroystokyo.paper.entity.ai.VanillaGoal.PANDA_PANIC,
            com.destroystokyo.paper.entity.ai.VanillaGoal.TURTLE_PANIC,
            // ★ 溺尸专属：找水 / 上岸 / 上浮
            com.destroystokyo.paper.entity.ai.VanillaGoal.DROWNED_GO_TO_WATER,
            com.destroystokyo.paper.entity.ai.VanillaGoal.DROWNED_GO_TO_BEACH,
            com.destroystokyo.paper.entity.ai.VanillaGoal.DROWNED_SWIM_UP,
            // ★ 僵尸家族同族：躲太阳 / 找水
            com.destroystokyo.paper.entity.ai.VanillaGoal.FLEE_SUN,
            com.destroystokyo.paper.entity.ai.VanillaGoal.TRY_FIND_WATER,
            // ★★ 海龟一族（2026-10-13 用户反馈「海龟只会站着或闲逛」）：
            //    原版海龟的 MOVE goal 全是「回老家 / 下水 / 产卵 / 到处游荡」，
            //    没有一个是「走向敌人」的 → 不摘掉它们、又指望我们补的导航 goal 生效，
            //    就会互相抢 MOVE 槽（谁优先级高谁跑）。
            com.destroystokyo.paper.entity.ai.VanillaGoal.TURTLE_GO_TO_WATER,
            com.destroystokyo.paper.entity.ai.VanillaGoal.TURTLE_GO_HOME,
            com.destroystokyo.paper.entity.ai.VanillaGoal.TURTLE_TRAVEL,
            com.destroystokyo.paper.entity.ai.VanillaGoal.TURTLE_LAY_EGG,
            // ★★ 熊猫一族（2026-10-13 用户反馈「熊猫不打人、只会闲逛」）：
            //    熊猫的攻击 goal 是 `Panda$PandaAttackGoal`，而它的 canUse 多了一个
            //    **`panda.canPerformAction()`** 闸门（26.2 反编译）：
            //    `canPerformAction() = !isOnBack && !isScared && !isEating && !isRolling && !isSitting`
            //    ⇒ 只要它「躺平 / 打滚 / 坐着吃」，攻击 goal 就**根本不会启动**，
            //      而它自己的 MOVE goal（打滚 / 坐下 / 躺平）照样跑 —— 看上去就是「闲逛不打人」。
            //    ★ 这三个 flag 由**基因与道具**决定，所以症状是**随机的**：
            //      LAZY 基因会躺平、PLAYFUL 基因会打滚（幼崽也行）、
            //      地上有「熊猫能吃的掉落物」（例如另一只熊猫死后掉的竹子）时会坐下吃。
            //      2026-10-13 实测：同一场里有的熊猫正常咬死了僵尸，有的基因就只会打滚 —— 对上了。
            //    另外把 `PANDA_AVOID` 也摘掉：那是 WORRIED 基因「见到怪物 4 格内就跑」，
            //    在对局里同样表现为「绕着敌人走」。
            //    ⚠️ 剩一个闸门摘不掉：`isScared()` = `isWorried() && 世界在打雷` ——
            //      要靠「比赛期间不许雷暴」才能根除，而那会改世界状态（竞技场就在主世界），
            //      没做；真遇到雷暴+WORRIED 熊猫时它仍会不敢打（已在 dev-log 078 记下）。
            com.destroystokyo.paper.entity.ai.VanillaGoal.PANDA_LIE_ON_BACK,
            com.destroystokyo.paper.entity.ai.VanillaGoal.PANDA_ROLL,
            com.destroystokyo.paper.entity.ai.VanillaGoal.PANDA_SIT,
            com.destroystokyo.paper.entity.ai.VanillaGoal.PANDA_AVOID);

    /**
     * 摘掉该生物身上所有「分心」goal，并返回**真的被摘掉的那些 key 名**。
     *
     * <p>★ 返回值的意义：只有把「摘之前它挂了什么」打进日志，事后才能证明因果关系
     * （2026-10-13 排查「溺尸不攻击」时，缺的正是这条证据 —— 只能猜）。
     */
    @SuppressWarnings("unchecked")
    private static java.util.List<String> removeDistractionGoals(
            com.destroystokyo.paper.entity.ai.MobGoals goals, Mob mob) {
        java.util.List<String> removed = new java.util.ArrayList<>();
        for (var key : DISTRACTION_KEYS) {
            if (!key.getEntityClass().isInstance(mob)) {
                continue;   // 该生物类型不可能有这个 goal（例如 PanicGoal 之于溺尸）
            }
            try {
                var typed = (com.destroystokyo.paper.entity.ai.GoalKey<Mob>) (Object) key;
                if (goals.hasGoal(mob, typed)) {
                    goals.removeGoal(mob, typed);
                    removed.add(key.getNamespacedKey().getKey());
                }
            } catch (Throwable ignored) {
                // 实际不存在 / API 不支持 —— 跳过
            }
        }
        java.util.Collections.sort(removed);
        return removed;
    }

    /**
     * 「分心 goal」的 key 名清单（供单元测试钉住）——
     * 想删掉其中任何一个（比如溺尸的 {@code drowned_go_to_water}），测试会立刻变红。
     */
    static java.util.List<String> distractionKeyNames() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (var key : DISTRACTION_KEYS) {
            out.add(key.getNamespacedKey().getKey());
        }
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * 打印该生物**当前还挂着哪些 MOVE goal**（只打 key 名）——「它会不会真的去打」的直接证据。
     *
     * <p>为什么要它：2026-10-13 的「溺尸不攻击」排查里，日志只有
     * {@code 自带近战✗} 这种**推断**，看不到它当时到底挂着哪个 MOVE goal，
     * 只能靠猜。现在开打时把清单打出来，一眼就能看出「分心 goal 摘干净没有」。
     */
    private static String describeMoveGoals(com.destroystokyo.paper.entity.ai.MobGoals goals, Mob mob) {
        try {
            java.util.List<String> keys = new java.util.ArrayList<>();
            for (var g : goals.getAllGoals(mob, com.destroystokyo.paper.entity.ai.GoalType.MOVE)) {
                keys.add(String.valueOf(g.getKey().getNamespacedKey().getKey()));
            }
            java.util.Collections.sort(keys);
            return keys.toString();
        } catch (Throwable t) {
            return "(读不到)";
        }
    }

    /**
     * 「攻击语义」词根 —— 用来判断一只怪**自带哪个会把它带向敌人并出手的 goal**。
     *
     * <h2>★ 为什么不能靠手写白名单（2026-10-13 狐狸事故的根因）</h2>
     * Paper 的 goal key 是**从 NMS goal 类名推导**出来的
     * （{@code MobGoalHelper.getKey}：取外部类名做前缀、去掉 {@code Goal}/{@code TargetGoal}
     * 后缀、转 snake_case）。于是狐狸的近战 goal
     * {@code Fox$FoxMeleeAttackGoal} ⇒ key = {@code fox_melee_attack}，
     * **不等于** {@code melee_attack}。
     *
     * <p>旧实现维护了一份手写白名单（{@code MELEE_ATTACK/ZOMBIE_ATTACK/…}），
     * 里面没有狐狸 ⇒ 判定「自带输出✗」⇒ 按 dev-log 073 的规则给它补了
     * **优先级 0 的 {@link MountDriveGoal}**；而同一时刻**只能跑一个 MOVE 类 goal**
     * （见 AGENTS.md 坑表）⇒ 狐狸自己的近战 goal 被**永久顶掉**，
     * 症状就是「走到敌人面前却不咬人」。
     *
     * <p>⚠️ 用户当年报的是「狐狸叼着钻石剑不攻击」——**与钻石剑无关**：
     * 用户同时改了配置（给狐狸加剑），于是把因果看成了装备。
     * 真正的变量是 dev-log 073 那次「所有自带输出✗的单位都补导航 goal」的放宽。
     *
     * <p>白名单写法**天生会漏**（每加一个生物都要补一次，漏一次就废掉一个物种），
     * 所以改成**扫这只怪实际挂着的 goal**（{@code getAllGoals}，Paper 为每个 NMS goal
     * 现算 key），按词根判断 —— 狐狸 / 狼 / 铁傀儡 / 劫掠兽这类专属 key 全部自动命中。
     */
    static final java.util.Set<String> ATTACK_KEY_TOKENS = java.util.Set.of(
            "attack", "melee", "ram", "swell", "charge", "spit", "shoot",
            "spear", "trident", "pounce", "bite", "sting");

    /**
     * key 名是否带「攻击语义」词根。
     *
     * <p>按 {@code _} 切词后逐段**全等**比较（不是 {@code contains}）——
     * 否则 {@code program} 这类名字会被 {@code ram} 误命中。
     */
    static boolean isAttackGoalName(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        for (String token : path.split("_")) {
            if (ATTACK_KEY_TOKENS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 该生物**自带哪个攻击 goal** —— 返回命中的 key 名，都没有则返回 {@code null}。
     *
     * <p>返回 key 名而不是 boolean 是刻意的：日志里直接写「✓(fox_melee_attack)」，
     * 排查时不必再猜「它到底靠什么输出」。
     *
     * @return 命中的 goal key 名（如 {@code fox_melee_attack}）；没有则 {@code null}
     */
    private static String nativeAttackGoal(com.destroystokyo.paper.entity.ai.MobGoals goals, Mob mob) {
        try {
            java.util.List<String> hits = new java.util.ArrayList<>();
            for (var goal : goals.getAllGoals(mob)) {
                String path = goal.getKey().getNamespacedKey().getKey();
                if (isAttackGoalName(path)) {
                    hits.add(path);
                }
            }
            if (hits.isEmpty()) {
                return null;
            }
            java.util.Collections.sort(hits);
            return hits.get(0);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 这只怪是不是**纯 brain 型**（一个 goal 都没有 ⇒ 行为全写在 {@code Brain} 里）。
     *
     * <p>实测依据（26.2 反编译）：{@code Piglin} / {@code Hoglin} / {@code Zoglin} /
     * {@code Warden} **都没有 {@code registerGoals()}**，goalSelector 是空的；
     * 且 {@code Hoglin#getTarget()} 的实现是 {@code getTargetFromBrain()}
     * ⇒ {@code setTarget} 对它们**是空操作**。
     *
     * <p>★ 但**判出 brain 型并不等于不驱动它** —— 见 {@link #VANILLA_AI_TYPES}：
     * 实测（dev-log 073 的美西螈、本轮第 3 轮）证明我们的 {@link MountDriveGoal}
     * 对 brain 型**是有效的**（美西螈就是靠它走向敌人的），
     * 所以只有**用户点名的生物**才交给原版 AI。
     */
    private static boolean isBrainOnly(com.destroystokyo.paper.entity.ai.MobGoals goals, Mob mob) {
        try {
            for (var goal : goals.getAllGoals(mob)) {
                var key = goal.getKey();
                // 我们自己挂的两个 goal 不算「原版 goal」——否则先挂上 EnemyTargetGoal
                // 再调本方法就会永远返回 false（2026-10-13 实机踩到，见 unlockAi 的注释）。
                if (key.equals(EnemyTargetGoal.key()) || key.equals(MountDriveGoal.key())) {
                    continue;
                }
                return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ★ 走原版导航 AI、**不要**我们的驱动 goal 的生物种类。
     *
     * <p>⚠️ **当前为空集**（用户 2026-10-13 晚些时候撤销了疣猪兽那条例外）：
     * 用户先说「坐骑换成疣猪兽，ai 改为蜘蛛骑士那样的原版导航 ai」，
     * 在我们实现后又确认「我忘记疣猪兽的特殊性了，**让我们的驱动接手**」。
     *
     * <p>所以现在回到统一规则：**没有自带攻击 goal 的单位一律由 `MountDriveGoal` 驱动**
     * （海龟 / 美西螈 / 疣猪兽 / 僵尸马 / 鸡…都一样）。
     * 保留这个空集合是为了**留下改动痕迹**：下次再有人想「对某一种生物特殊处理」，
     * 先看这里为什么被清空 —— 而且疣猪兽骑士的乘客要靠载具走位才打得到人。
     */
    private static final java.util.Set<EntityType> VANILLA_AI_TYPES =
            java.util.EnumSet.noneOf(EntityType.class);

    /** 供单元测试钉住「点名例外当前为空」。 */
    static java.util.Set<EntityType> vanillaAiTypes() {
        return java.util.Collections.unmodifiableSet(VANILLA_AI_TYPES);
    }

    /**
     * 重新给 brain 型生物指定目标（它们不能被 Goal 驱动）。
     *
     * <p>⚠️ **这里只能「纠正错误目标」，无法真正驱动它们**（见 docs/07 §1 的实测结论：
     * {@code MemoryKey} 没有 {@code ATTACK_TARGET}，brain 型的攻击目标
     * 无法通过 Bukkit API 读写；{@code setTarget} 对它们也基本无效）。
     * 因此本方法的作用是**兜底**：当它们的目标不是敌方单位时（典型是原版 AI 把
     * 误入战场的**玩家**设成了目标），至少把它改回敌方单位。
     *
     * <p>★ 旧版只判断「目标为空或已死」，于是**永远修不了「目标错了」** ——
     * 猪灵一旦锁上玩家（原版规则：对没穿金装备的玩家敌对），
     * 这里就再也不会介入。用户实测症状正是「只打玩家、不打对面」。
     */
    private void retargetBrainMobs() {
        if (brainMobs.isEmpty() || !fighting) {
            return;
        }
        GameConfig cfg = plugin.gameConfig();
        // ⚠️ 2026-10-12 **按用户裁定回调**：本方法曾扩展成「对所有单位强制纠正目标」
        //   （依据是 `-Digdiag` 探针实测：远程怪也会出现 `getTarget()` 为空）。
        //   用户明确要求回调原状 ——「这样的大改造成的 bug 更多了」：
        //   改面越大回归风险越大，而它动的是**所有生物**的索敌。
        //   ⇒ 恢复为**只服务 brain 型**（猪灵/蛮兵/疣猪兽/僵尸疣猪兽），
        //   其余生物一律交给 `EnemyTargetGoal`。
        //   探针结论（`findEnemy` 能找到恶魂、但远程怪 `getTarget()` 恒为空）
        //   保留在 dev-log 062 §十二，属于**已知未修**问题，将来若要修请做窄改。
        for (UUID id : new java.util.ArrayList<>(brainMobs)) {
            BattleTracker.TrackedUnit unit = tracker.get(id).orElse(null);
            if (unit == null) {
                brainMobs.remove(id);
                continue;
            }
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof Mob mob) || mob.isDead() || !mob.isValid()) {
                continue;
            }
            if (!isEnemyUnit(mob.getTarget(), unit.side())) {
                LivingEntity enemy = findEnemy(mob, unit.side(), cfg.followRange());
                forceBrainAnger(mob, enemy);
                mob.setTarget(enemy);
            }
        }
    }

    /**
     * ★ 给 **brain 型猪灵/蛮兵**写它们真正会读的「敌人」记忆（`ANGRY_AT`）。
     *
     * <h2>为什么 {@code setTarget} 对它们无效</h2>
     * 【javap 实测 26.2】{@code AbstractPiglin.getTarget()} 的实现是
     * {@code return this.getTargetFromBrain();} —— 读的是 brain 的 {@code ATTACK_TARGET}；
     * 而 {@code Mob#setTarget} 只写 {@code Mob.target} 这个字段。
     * 两头根本不挨着 ⇒ 我们心跳里的 {@code setTarget} 对猪灵/蛮兵是**空操作**
     * （这也解释了用户观察：蛮兵**挨打会还手**——那是原版 brain 的 HURT_BY → ATTACK_TARGET，
     * 但**不会主动追敌**）。
     *
     * <h2>为什么 {@code ANGRY_AT} 能行、而且不用反射</h2>
     * {@code PiglinAi.findNearestValidAttackTarget(...)} 的**第一优先级**就是
     * {@code ANGRY_AT}：把 UUID 解析成实体后只做
     * {@code Sensor.isEntityAttackableIgnoringLineOfSight}（**忽略视线**）判定，
     * 通过就直接作为攻击目标返回；{@code PiglinBruteAi} 同样读 {@code ANGRY_AT}。
     * 而 {@code MemoryKey.ANGRY_AT}（类型 {@code MemoryKey<UUID>}）**正是 Bukkit 暴露的键**，
     * {@code LivingEntity#setMemory} 就能写 ⇒ 全程走公开 API。
     *
     * <p>⚠️ **疣猪兽走不通**：{@code HoglinAi} 只读 {@code ATTACK_TARGET}、不读 {@code ANGRY_AT}，
     * 而 Bukkit 的 {@code MemoryKey} 里**没有** `ATTACK_TARGET`（只有 `ATTACK_COOLDING_DOWN` 等）
     * ⇒ 想让它主动追怪只能反射写 brain，或维持现状（原版索敌打玩家 + 挨打还手）。
     *
     * <p>⚠️ 另一个原版规则：猪灵身边若有**僵尸猪灵**，{@code findNearestValidAttackTarget}
     * 会先命中 {@code isNearZombified(...)} 直接返回空 —— 即「被同族僵尸吓住」。
     * 本方法不会绕过它（那是原版行为，改了等于改玩法）。
     */
    private static void forceBrainAnger(Mob mob, LivingEntity enemy) {
        if (enemy == null || !(mob instanceof org.bukkit.entity.PiglinAbstract piglin)) {
            return;
        }
        piglin.setMemory(org.bukkit.entity.memory.MemoryKey.ANGRY_AT, enemy.getUniqueId());
    }

    /**
     * 该生物当前的「敌意目标」是否**确实属于对面阵营**。
     *
     * <p>用它替代「非空且未死」的旧判据：玩家、自家队友、乃至任何未标记实体都算「错的目标」。
     * 这样既不会漏掉「锁到玩家」的情形，也不会在多个敌人之间来回抖动
     * （当前目标只要是敌人就维持不动）。
     */
    private static boolean isEnemyUnit(LivingEntity target, BattleSide mySide) {
        if (target == null || target.isDead() || !target.isValid()) {
            return false;
        }
        return sideOf(target).filter(s -> s != mySide).isPresent();
    }

    // ================= 衍生单位（非计数） =================

    /**
     * 「收养」一个战斗中衍生出来的单位（用户第 3 条）。
     *
     * <p>用户原话：
     * 「如果唤魔者的阵营只剩下恼鬼存活依然算作失败（恼鬼会攻击敌人，但不算做阵营存活单位）」
     *
     * <p>因此衍生单位：
     * <ul>
     *   <li>**打上所属阵营**，加入同阵营影子队伍（原版 AI 不会把它当目标）</li>
     *   <li>**打上 {@code counted=0}** → 不计入「一方全灭」判定</li>
     *   <li>**同样挂上索敌 Goal** → 它会去攻击敌方</li>
     *   <li>回合结束一并清场</li>
     * </ul>
     *
     * @param source 来源描述（仅用于日志，如 {@code VEX_OF_EVOKER}）
     * @return 是否成功收养
     */
    public boolean adoptDerived(LivingEntity entity, BattleSide side, String source) {
        if (cleaned || entity == null || entity.isDead()) {
            return false;
        }
        if (tracker.isTracked(entity.getUniqueId())) {
            return false; // 已经是我们自己的兵
        }
        tagSide(entity, side, false, List.of());        entity.setRemoveWhenFarAway(false);
        entity.setPersistent(true);
        // ★ 衍生单位同样要按配置放大体型（否则「分裂出来/生出来」的小怪又看不见了）
        applyRenderScale(entity, plugin.gameConfig());
        // ★ 衍生单位也要免疫僵尸化：`mama` / `mama_plus` 生出来的是**幼体**
        //   （`setBaby()`），而 26.2 里幼体猪灵默认**不免疫**
        //   （`DEFAULT_IMMUNE_TO_ZOMBIFICATION = false`，javap 实测），
        //   不补这一行，主世界里它们 300 tick 后就会变成僵尸猪灵。
        preventZombification(entity);
        if (entity instanceof Mob m) {
            m.setCanPickupItems(false);
        }
        // 已经开始打了就立刻给它装索敌
        if (fighting && entity instanceof Mob m) {
            unlockAi(m, side);
        }
        plugin.getLogger().info("[IG] 收养衍生单位 " + entity.getType()
                + "（阵营 " + side.id() + "，来源 " + source + "，不计入存活判定）");
        return true;
    }

    /** 找离某点最近的**已登记单位**（用于判断衍生单位归属于哪一方）。 */
    public BattleTracker.TrackedUnit nearestUnit(Location loc, double radius) {
        if (loc == null) {
            return null;
        }
        double best = radius * radius;
        BattleTracker.TrackedUnit found = null;
        for (BattleTracker.TrackedUnit unit : tracker.all()) {
            Entity e = Bukkit.getEntity(unit.uuid());
            if (e == null || !e.getWorld().equals(loc.getWorld())) {
                continue;
            }
            double d = e.getLocation().distanceSquared(loc);
            if (d <= best) {
                best = d;
                found = unit;
            }
        }
        return found;
    }

    /** 本场战斗是否正在生成自己的兵（用于屏蔽衍生逻辑）。 */
    public boolean isSpawning() {
        return spawning;
    }

    /** 准备阶段结束后，把双方都锁住（供下注阶段调用）。 */
    public void keepLocked() {
        for (BattleTracker.TrackedUnit unit : tracker.all()) {
            if (Bukkit.getEntity(unit.uuid()) instanceof Mob mob && !mob.isDead()) {
                lockAi(mob);
            }
        }
    }

    // ================= 索敌 =================

    @Override
    public LivingEntity findEnemy(LivingEntity self, BattleSide mySide, double range) {
        double best = range * range;
        LivingEntity found = null;
        for (BattleTracker.TrackedUnit unit : tracker.all()) {
            if (unit.side() == mySide) {
                continue;
            }
            Entity e = Bukkit.getEntity(unit.uuid());
            if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) {
                continue;
            }
            if (!le.getWorld().equals(self.getWorld())) {
                continue;
            }
            double d = le.getLocation().distanceSquared(self.getLocation());
            if (d < best) {
                best = d;
                found = le;
            }
        }
        return found;
    }

    // ================= 山羊冲撞 =================

    /**
     * 山羊冲撞冷却（**用户指定 2 秒**）。
     *
     * <p>心跳每 5 tick 跑一次 → 2 秒 = 40 tick = **8 次心跳**。
     */
    private static final int GOAT_RAM_SECONDS = 2;

    /** 每只山羊剩余的冲撞冷却（单位：心跳次数）。 */
    private final java.util.Map<UUID, Integer> goatRamCooldown = new java.util.HashMap<>();

    /** 每种「阵营+生物类型+决策组合」只打一次 AI 装载日志，避免刷屏。 */
    private final java.util.Set<String> aiLogged = new java.util.HashSet<>();

    /** 每只山羊只记一次「冲撞成功 / 失败」，避免刷屏。 */
    private final java.util.Set<UUID> goatRamNoted = new java.util.HashSet<>();

    /**
     * ★ 让山羊**按我们的节奏**冲撞敌方单位。
     *
     * <h2>为什么必须我们来做</h2>
     * 原版山羊的冲撞是「机会主义」的（见
     * <a href="https://minecraft.wiki/w/Goat">Minecraft Wiki · Goat</a>）：
     * <ul>
     *   <li>间隔 **30 秒 ~ 5 分钟** → 一回合通常一次都不触发</li>
     *   <li>目标必须是**静止不动**的 → 对局里所有怪都在移动，几乎永远筛不出目标</li>
     *   <li>排除 ghast 与**其他山羊** → 同类对局必然零冲撞</li>
     * </ul>
     * 所以「山羊不攻击」不是我们漏配，而是原版行为在对局场景下几乎不生效。
     *
     * <h2>为什么用 {@code Goat#ram(target)}</h2>
     * Paper 暴露了 {@code Goat#ram(LivingEntity)} —— 它走的是**原版那条冲撞行为**，
     * 低头、跺脚、冲锋、命中音效与击退**全部保留**（用户明确要求原版风味）。
     * 同时它**完全绕过原版的目标筛选**，于是「必须静止」的限制自然失效，
     * 频率也由我们决定 —— 三条限制一次解决。
     *
     * <p>另外把 {@code RAM_COOLDOWN_TICKS} 清零，免得原版自己的冷却拦住这次冲撞。
     */
    private void tickGoatRams() {
        if (!fighting || cleaned) {
            return;
        }
        int cooldownHeartbeats = Math.max(1, GOAT_RAM_SECONDS) * 4;
        for (BattleTracker.TrackedUnit unit : tracker.all()) {
            Entity e = Bukkit.getEntity(unit.uuid());
            if (!(e instanceof org.bukkit.entity.Goat goat) || goat.isDead() || !goat.isValid()) {
                continue;
            }
            int left = goatRamCooldown.getOrDefault(unit.uuid(), 0) - 1;
            if (left > 0) {
                goatRamCooldown.put(unit.uuid(), left);
                continue;
            }
            LivingEntity enemy = findEnemy(goat, unit.side(), 16.0);
            if (enemy == null || enemy.isDead() || !enemy.isValid()) {
                goatRamCooldown.put(unit.uuid(), 0);   // 没有敌人 → 下个心跳再看
                continue;
            }
            try {
                goat.setMemory(org.bukkit.entity.memory.MemoryKey.RAM_COOLDOWN_TICKS, 0);
                goat.ram(enemy);
                if (goatRamNoted.add(unit.uuid())) {   // 每只山羊只记一次，避免刷屏
                    plugin.getLogger().info("[IG] 山羊冲撞 → " + enemy.getType()
                            + "（原版冲撞行为，冷却 " + GOAT_RAM_SECONDS + " 秒）");
                }
                goatRamCooldown.put(unit.uuid(), cooldownHeartbeats);
            } catch (Throwable t) {
                if (goatRamNoted.add(unit.uuid())) {
                    plugin.getLogger().warning("山羊冲撞失败（不影响对局）：" + t);
                }
                goatRamCooldown.put(unit.uuid(), cooldownHeartbeats);
            }
        }
    }

    // ================= 心跳 =================

    /**
     * ★ 方块怪（史莱姆 / 岩浆怪）接触伤害的**检查节奏**（单位：心跳次数；1 心跳 = 5 tick）。
     *
     * <p>2 个心跳 = 10 tick —— 正好是原版受伤后的**无敌帧长度**：查得再密也会被无敌帧吃掉，
     * 所以按 10 tick 查一次既够快、也不浪费。
     */
    private static final int CUBE_TOUCH_HEARTBEATS = 2;

    /** 每只方块怪的接触伤害冷却（单位：心跳次数）。 */
    private final java.util.Map<UUID, Integer> cubeTouchCooldown = new java.util.HashMap<>();

    /** 每种「类型 + 体型」只打一次接触伤害日志，避免刷屏。 */
    private final java.util.Set<String> cubeTouchLogged = new java.util.HashSet<>();

    /** 本回合方块怪接触命中次数（清场时打进日志，作为「真的生效了」的证据）。 */
    private int cubeTouchHits;

    /**
     * ★ 让**方块怪（史莱姆 / 岩浆怪）的接触伤害对怪物也生效**。
     *
     * <h2>为什么必须我们接管（26.2 反编译实测）</h2>
     * 原版的伤害入口只有两个，且都**排除了普通怪物**：
     * <ul>
     *   <li>{@code AbstractCubeMob#playerTouch(Player)} —— 只对**玩家**</li>
     *   <li>{@code AbstractCubeMob#push(Entity)} —— 只在撞到**铁傀儡**时（源码里显式
     *       {@code instanceof IronGolem}）</li>
     * </ul>
     * 而看起来像「攻击」的 {@code CubeMobAttackGoal.tick()} 只做
     * {@code lookAt(target)} + 给 {@code CubeMobMoveControl} 设方向 —— **一次伤害都不发**。
     * ⇒ 用户报的「史莱姆 / 岩浆怪打不动别的怪」是**原版行为**，不是配置漏写。
     *
     * <h2>数值与判据全部照抄原版</h2>
     * 伤害 = {@code ATTACK_DAMAGE} 属性（史莱姆 = 体型 1/2/4；岩浆怪 = 体型 + 2）、
     * 能不能打 = {@code isDealsDamage()}（**体型 1 的史莱姆不算输出**，岩浆怪不看体型）、
     * 够不够近 = {@code (bbWidth*2)² + 目标宽度}、还要求有视线 —— 全部见
     * {@link CubeMobTouch}（纯函数，有单测）。
     *
     * <p>⚠️ **只打敌方已登记单位**，绝不碰玩家：玩家那边的原版 {@code playerTouch} 照旧生效，
     * 我们不去改玩家侧的规则。
     */
    private void tickCubeMobTouches() {
        if (!fighting || cleaned) {
            return;
        }
        for (BattleTracker.TrackedUnit unit : tracker.all()) {
            Entity e = Bukkit.getEntity(unit.uuid());
            if (!(e instanceof org.bukkit.entity.AbstractCubeMob cube)
                    || cube.isDead() || !cube.isValid()) {
                continue;
            }
            int left = cubeTouchCooldown.getOrDefault(unit.uuid(), 0) - 1;
            if (left > 0) {
                cubeTouchCooldown.put(unit.uuid(), left);
                continue;
            }
            cubeTouchCooldown.put(unit.uuid(), CUBE_TOUCH_HEARTBEATS);

            boolean magma = cube instanceof org.bukkit.entity.MagmaCube;
            int size = cube.getSize();
            if (!CubeMobTouch.dealsDamage(size, magma)) {
                continue;   // 原版：体型 1 的史莱姆打不动任何东西（连玩家都打不动）
            }
            AttributeInstance attack = cube.getAttribute(Attribute.ATTACK_DAMAGE);
            double damage = CubeMobTouch.damageOf(attack == null ? size : attack.getValue(), magma);

            for (BattleTracker.TrackedUnit other : tracker.all()) {
                if (other.side() == unit.side() || !other.counted()) {
                    continue;
                }
                Entity raw = Bukkit.getEntity(other.uuid());
                if (!(raw instanceof LivingEntity target) || target.isDead() || !target.isValid()) {
                    continue;
                }
                if (!cube.getWorld().equals(target.getWorld())) {
                    continue;
                }
                double distSq = cube.getLocation().distanceSquared(target.getLocation());
                if (distSq > CubeMobTouch.touchRangeSq(cube.getWidth(), target.getWidth())) {
                    continue;
                }
                if (!cube.hasLineOfSight(target)) {
                    continue;
                }
                target.damage(damage, cube);   // 归因给方块怪 ⇒ 击杀播报 / 词条 / 掉落都按它的来
                cubeTouchHits++;
                if (cubeTouchLogged.add(cube.getType() + "|" + size)) {
                    plugin.getLogger().info("[IG] 方块怪接触伤害已接管：" + cube.getType()
                            + "（体型 " + size + "，每次 " + damage + " 点）→ " + target.getType()
                            + "；原版只对玩家与铁傀儡生效");
                }
            }
        }
    }

    /** 每 5 tick 调用：兜底收尸 + 判胜负。 */
    public void tick() {
        if (cleaned) {
            return;
        }
        tickGoatRams();
        tickCubeMobTouches();
        // 兜底：爆炸/被移除没走死亡事件的怪
        for (BattleTracker.TrackedUnit unit : tracker.all()) {
            Entity e = Bukkit.getEntity(unit.uuid());
            if (e == null || e.isDead() || !e.isValid()) {
                handleDeath(unit.uuid());
            }
        }
        if (!fighting) {
            return;
        }
        // brain 型生物靠心跳重申目标
        retargetBrainMobs();
        diagTargets();
        // ★ 词条心跳：RANGE 类派发 + 到期状态回收
        //   （炒鸡的 RANGE **不 fire 任何事件**，只能靠轮询；这里就是那个轮询）
        tickAffixHeartbeat();
        BattleSide w = tracker.decideWinner();
        if (w != null && winner == null) {
            winner = w;
            onRoundDecided(w);
        }
    }

    /**
     * 词条心跳（每 5 tick 一次）：把**存活且仍被追踪**的实体交给词条系统做周期派发。
     *
     * <p>为什么只喂「被追踪的」实体：追踪表就是「本回合的参战单位」，
     * 词条只应作用于参战单位；也给词条系统一个天然的边界。
     */
    private void tickAffixHeartbeat() {
        List<LivingEntity> alive = new ArrayList<>();
        for (BattleTracker.TrackedUnit unit : tracker.all()) {
            if (Bukkit.getEntity(unit.uuid()) instanceof LivingEntity le
                    && !le.isDead() && le.isValid()) {
                alive.add(le);
            }
        }
        if (!alive.isEmpty()) {
            plugin.affixRegistry().tickAffixes(alive);
        }
    }

    /** 由死亡事件监听器调用（**幂等**）。 */
    public void handleDeath(UUID uuid) {
        tracker.untrack(uuid);
        BY_ENTITY.remove(uuid);
    }

    private void onRoundDecided(BattleSide w) {
        plugin.getLogger().info("第 " + round + " 回合结束：胜方 " + w.id()
                + "（左存活计数单位 " + tracker.countedAlive(BattleSide.LEFT)
                + "，右 " + tracker.countedAlive(BattleSide.RIGHT) + "）");
        // 先撤毒雾：胜负已定，不该让获胜方在烟花演出期间继续掉血
        // （实测发现：宣布胜方后到清场之间还有约 3 秒，毒雾仍在跳）
        stopPoisonFog();
        playVictoryEffect(w);
        var listener = winnerListener;
        if (listener != null) {
            listener.accept(w);
        }
    }

    /** 停止毒雾心跳（幂等）。 */
    private void stopPoisonFog() {
        if (fogTask != null) {
            fogTask.cancel();
            fogTask = null;
        }
    }

    /** 设置胜负回调（回合状态机用）。 */
    public void setWinnerListener(java.util.function.Consumer<BattleSide> listener) {
        this.winnerListener = listener;
    }

    // ================= 演出 =================

    /** 在**存活怪物处**召唤烟花并使其发光（用户需求）。 */
    public void playVictoryEffect(BattleSide w) {
        for (BattleTracker.TrackedUnit unit : tracker.all()) {
            if (unit.side() != w) {
                continue;
            }
            Entity e = Bukkit.getEntity(unit.uuid());
            if (!(e instanceof LivingEntity le) || le.isDead()) {
                continue;
            }
            le.setGlowing(true);
            spawnFirework(le.getLocation());
        }
    }

    private void spawnFirework(Location loc) {
        try {
            Firework fw = loc.getWorld().spawn(loc.clone().add(0, 1.0, 0), Firework.class);
            FireworkMeta meta = fw.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .withColor(Color.YELLOW, Color.ORANGE)
                    .with(FireworkEffect.Type.BALL_LARGE)
                    .withTrail()
                    .build());
            meta.setPower(1);
            fw.setFireworkMeta(meta);
        } catch (Throwable t) {
            plugin.getLogger().warning("放烟花失败： " + t);
        }
    }

    // ================= 末影人 / 潜影贝：拦传送 =================

    /**
     * 记录并拦下一次传送（由监听器在 {@code EntityTeleportEvent} 被取消后调用）。
     *
     * <h2>为什么要拦</h2>
     * 末影人（被弹射物命中 / 被看 / 下雨）与潜影贝（受伤时）会**瞬移**，
     * 结果常常是「**飘出竞技场**、双方再也打不到」，一局就废了。
     *
     * <h2>★ 为什么拦传送**不会**破坏「末影人免疫弹射物」</h2>
     * 原版 {@code Enderman.hurt()} 对投射物的处理是「**瞬移 + 直接 return false**」——
     * 「不掉血」这一条与瞬移是否成功**无关**，所以取消传送之后：
     * 箭**依旧打不动它**（免疫保留），只是它**不再飘走**。
     * 这一点用实测确认过（骨架射它，血量全程不掉、位置不移动，见 dev-log 074）。
     *
     * <p>只对本插件接管的单位调用；打印**每个类型仅一次**，避免刷屏；
     * 清场时汇总一次总数（可对账）。
     */
    void recordBlockedTeleport(Entity entity) {
        var type = entity.getType();
        int n = blockedTeleports.merge(type, 1, Integer::sum);
        if (n == 1) {
            plugin.getLogger().info("[IG] 开始拦截 " + type + " 的传送"
                    + "（例：" + fmtLoc(entity.getLocation()) + "）—— 防止它飘出竞技场；"
                    + "同类传送后续不再逐条打印，清场时汇总");
        }
    }

    private static String fmtLoc(Location l) {
        return l == null ? "?" : String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f",
                l.getX(), l.getY(), l.getZ());
    }

    // ================= 美西螈：假死复活 =================

    /**
     * 美西螈「假死复活」的处理入口（由监听器在 {@code EntityDamageEvent} 上调用）。
     *
     * <h2>用户要求（2026-10-13）</h2>
     * 「将美西螈改为驯服的美西螈，能否实现假死复活？如果不能则移除」——
     * 能实现：Paper 的 {@code Axolotl#setPlayingDead(boolean)} 就是原版「假死」姿态
     * （{@code javap} 实测存在），配一次定时任务即可「复活」。
     *
     * <h2>它解决的是什么</h2>
     * 美西螈是**被动**生物、原版**只攻击白名单**（鳕鱼/溺尸/守卫者/鱿鱼/蝌蚪…），
     * 对我们的主流怪根本打不出伤害 → 与其当活靶，不如给它「**扛伤害**」的定位。
     *
     * <h2>规则</h2>
     * <ol>
     *   <li>假死期间 **免疫一切伤害**（否则躺在地上被补刀，「复活」无从谈起）</li>
     *   <li>**致命伤害**（{@code finalDamage >= health}）才触发假死，且只在还有次数时</li>
     *   <li>假死 {@code seconds} 秒后复活，血量 = 最大生命 × {@code health-percent}%</li>
     *   <li>次数用尽后它就能被正常打死 —— **这是保证回合能结束的关键**</li>
     * </ol>
     *
     * @return {@code true} 表示这次伤害已被本机制接管（调用方无需再处理）
     */
    boolean handleAxolotlDamage(LivingEntity victim, org.bukkit.event.entity.EntityDamageEvent event) {
        if (!(victim instanceof org.bukkit.entity.Axolotl axolotl) || sideOf(victim).isEmpty()) {
            return false;
        }
        GameConfig cfg = plugin.gameConfig();
        // ① 假死期间：免疫
        if (playingDeadAxolotls.contains(victim.getUniqueId())) {
            event.setCancelled(true);
            return true;
        }
        int used = axolotlRevives.getOrDefault(victim.getUniqueId(), 0);
        if (!shouldStartPlayDead(cfg.axolotlReviveEnabled(), used, cfg.axolotlReviveCount(),
                victim.getHealth(), event.getFinalDamage())) {
            return false;
        }
        // ② 致命伤害 → 进入假死
        event.setCancelled(true);
        axolotlRevives.put(victim.getUniqueId(), used + 1);
        playingDeadAxolotls.add(victim.getUniqueId());
        int seconds = cfg.axolotlReviveSeconds();
        try {
            axolotl.setPlayingDead(true);
        } catch (Throwable t) {
            plugin.getLogger().warning("[IG] 设置美西螈假死姿态失败：" + t);
        }
        try {
            // 原版假死会自带生命恢复 I —— 保持观感一致
            axolotl.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,
                    seconds * 20, 0, false, true, true));
        } catch (Throwable ignored) {
            // 不接受效果的实体 → 跳过
        }
        plugin.getLogger().info("[IG] 美西螈假死（第 " + (used + 1) + "/"
                + cfg.axolotlReviveCount() + " 次）→ " + seconds + " 秒后复活");
        Bukkit.getScheduler().runTaskLater(plugin, () -> reviveAxolotl(axolotl), seconds * 20L);
        return true;
    }

    /**
     * 纯判定：这次伤害是否该触发「假死复活」（**可离线单测**）。
     *
     * @param enabled     {@code axolotl-revive.enabled}
     * @param used        已经用掉的复活次数
     * @param max         允许的复活次数上限
     * @param health      当前血量
     * @param finalDamage 这次伤害的实际数值
     * @return 是否进入假死
     */
    static boolean shouldStartPlayDead(boolean enabled, int used, int max,
                                       double health, double finalDamage) {
        return enabled && used < max && finalDamage >= health;
    }

    /** 假死结束：恢复姿态 + 带血复活。 */
    private void reviveAxolotl(org.bukkit.entity.Axolotl axolotl) {
        if (!playingDeadAxolotls.remove(axolotl.getUniqueId())) {
            return;   // 已被清场处理过
        }
        if (!axolotl.isValid() || axolotl.isDead()) {
            return;   // 回合已结束 / 实体已被清掉 → 不需要复活
        }
        try {
            axolotl.setPlayingDead(false);
        } catch (Throwable ignored) {
            // 忽略
        }
        double max = 14.0;
        try {
            var attr = axolotl.getAttribute(Attribute.MAX_HEALTH);
            if (attr != null) {
                max = attr.getValue();
            }
        } catch (Throwable ignored) {
            // 用默认值
        }
        double hp = Math.max(1.0, max * plugin.gameConfig().axolotlReviveHealthPercent() / 100.0);
        axolotl.setHealth(Math.min(hp, max));
        plugin.getLogger().info("[IG] 美西螈复活（" + String.format(java.util.Locale.ROOT, "%.1f", hp)
                + " HP）");
    }

    // ================= 清场 =================

    /**
     * 清场 —— ★ 范围受**铁律 10** 严格约束（判定逻辑见 {@link CleanupPolicy}）。
     *
     * <p>要点：
     * <ol>
     *   <li><b>先按追踪表清「本插件接管的实体」</b>（按 UUID，**无距离限制**）
     *       —— 这是唯一允许的"全量"清理</li>
     *   <li>再做<b>竞技场范围内的兜底清理</b>：以竞技场玩家出生点为心、半径
     *       {@link CleanupPolicy#ARENA_RADIUS} 格内，只清投射物 / 烟花 / 未接管的怪物，
     *       用来兜住战斗中衍生的、未登记进追踪表的实体</li>
     *   <li>⚠️ <b>严禁遍历整个世界做无差别清理</b> —— 现场 {@code arenas.yml} 实测竞技场
     *       就在<b>主世界</b>（{@code world: world}），世界级清理连玩家的假人、盔甲架、
     *       动物、村民和其它插件的实体一起删（用户 2026-10-12 裁定）</li>
     *   <li>移除计分板影子队伍</li>
     *   <li>清空追踪表与静态映射（防内存泄漏）</li>
     * </ol>
     */
    public void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        fighting = false;
        if (heartbeat != null) {
            heartbeat.cancel();
            heartbeat = null;
        }
        stopPoisonFog();

        // 1) 先按追踪表逐个移除（**本插件接管的实体，无距离限制** —— 铁律 10 第 1 条）
        //    先留一份 UUID 快照：下面会清空 BY_ENTITY，之后还要用它来区分「自己的」与「别人的」
        java.util.Set<UUID> ours = new java.util.HashSet<>(BY_ENTITY.keySet());
        for (BattleTracker.TrackedUnit unit : tracker.all()) {
            Entity e = Bukkit.getEntity(unit.uuid());
            if (e instanceof LivingEntity le) {
                // ★ 先卸词条再移除：让词条有机会回收持续效果 / 恢复被改过的属性
                plugin.affixRegistry().unequip(le);
                if (!le.isDead()) {
                    le.remove();
                }
            } else if (e != null) {
                // 兜底：接管的实体不是 LivingEntity（当前设计下不会出现）→ 直接移除
                e.remove();
            }
            BY_ENTITY.remove(unit.uuid());
        }
        // ★ 丢弃全部词条运行时状态 —— 否则跨回合残留会让下一局的怪带着上一局的状态
        //   （炒鸡用 MobState 承载，我们在 AffixRuntime 里承载）
        plugin.affixRegistry().clearRuntime();
        // ★ 还原词条改动过的方块（如 webber 的蛛网）—— 与清场同一时机，
        //   不像炒鸡那样靠 runTaskLater（区块卸载/重启后任务丢失 → 方块永久残留）
        int restored = io.mczju.infernalguess.affix.PlacedBlocks.restoreAll();
        if (restored > 0) {
            plugin.getLogger().info("[IG] 已还原 " + restored + " 个被词条改动过的方块。");
        }

        // 2) ★ 竞技场范围内的兜底清理（铁律 10）
        //    中心 = 竞技场「玩家出生点」（缺配置时退回观战席，都没有 → 跳过）
        //    范围 = 半径 CleanupPolicy.ARENA_RADIUS 格
        //    对象 = 投射物 / 烟花 / 未接管的怪物
        //    ⚠️ 绝不遍历整个世界做无差别清理 —— 竞技场就在主世界，那样会删掉玩家的一切布景。
        Location center = arenaCleanupCenter();
        if (world != null && center == null) {
            plugin.getLogger().warning("[IG] 竞技场缺少 player-spawn/spectate-spawn，"
                    + "已跳过范围内的兜底清理（本插件不做世界级清理）。");
        }
        if (world != null && center != null) {
            int swept = sweepArenaUnmanaged(center, ours);
            if (swept > 0) {
                plugin.getLogger().info("[IG] 已清理竞技场半径 "
                        + (long) CleanupPolicy.ARENA_RADIUS + " 格内的 " + swept
                        + " 个残留实体（投射物/烟花/未接管怪物）。");
            }
        }

        // 3) 移除影子队伍
        for (Team team : teams.values()) {
            try {
                team.unregister();
            } catch (Throwable ignored) {
                // 队伍已被移除
            }
        }
        teams.clear();

        tracker.clear();
        brainMobs.clear();
        axolotlRevives.clear();
        playingDeadAxolotls.clear();
        // 末影人 / 潜影贝的传送拦截汇总（每个类型只打过一次「开始拦截」，这里给总数）
        if (!blockedTeleports.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            blockedTeleports.forEach((t, c) -> sb.append(t).append('×').append(c).append(' '));
            plugin.getLogger().info("[IG] 本回合拦下的传送：" + sb.toString().trim());
            blockedTeleports.clear();
        }
        // 方块怪接触伤害汇总：没接管的话史莱姆/岩浆怪对怪物是 0 输出（原版只打玩家与铁傀儡），
        // 所以这个数字是「接管真的生效了」的直接证据。
        if (cubeTouchHits > 0) {
            plugin.getLogger().info("[IG] 本回合方块怪接触命中：" + cubeTouchHits + " 次");
        }
        cubeTouchHits = 0;
        cubeTouchCooldown.clear();
        cubeTouchLogged.clear();
        if (current == this) {
            current = null;
        }
        plugin.getLogger().info("第 " + round + " 回合已清场。");
    }

    /**
     * 兜底清理的中心点：竞技场**玩家出生点**（{@code player-spawn}），
     * 未配置时退回**观战席**（{@code spectate-spawn}）。
     *
     * <p>返回 {@code null} 表示**无法确定范围** → 调用方必须**跳过**兜底清理。
     * 铁律 10：宁可留下残留，也绝不把清理范围放大到整个世界。
     */
    private Location arenaCleanupCenter() {
        if (arena == null || world == null) {
            return null;
        }
        if (arena.playerSpawn() != null) {
            return arena.playerSpawn().toLocation(world);
        }
        if (arena.spectateSpawn() != null) {
            return arena.spectateSpawn().toLocation(world);
        }
        return null;
    }

    // ================= 访问 =================

    public BattleTracker tracker() {
        return tracker;
    }

    public int round() {
        return round;
    }

    public Optional<BattleSide> winner() {
        return Optional.ofNullable(winner);
    }

    public boolean isFighting() {
        return fighting;
    }

    public boolean isCleaned() {
        return cleaned;
    }

    public UUID battleId() {
        return battleId;
    }

    public ArenaConfig.Arena arena() {
        return arena;
    }

    /**
     * 给**本局参与者**发一条来自 display.yml 的消息。
     *
     * <h2>★ 为什么不再用 {@code Bukkit.getOnlinePlayers()}（2026-10-13 实测缺陷）</h2>
     * 用户反馈：「毒雾播报会播报给游戏外的玩家」。旧实现遍历**全服在线玩家**，
     * 于是一条只跟本局有关的播报发给了在大厅、在别的玩法里的所有人。
     * 现在收件人由 {@link #audience()} 界定；**没有收件人就只进控制台**，
     * 绝不退回全服播报（与铁律 10 对「范围」的要求同一口径）。
     */
    public void broadcast(String messagePath, Map<String, String> placeholders) {
        DisplayConfig display = plugin.displayConfig();
        Component c = display.render(messagePath, placeholders);
        int sent = 0;
        for (Player p : audience()) {
            p.sendMessage(c);
            sent++;
        }
        Bukkit.getConsoleSender().sendMessage(c);
        if (sent == 0) {
            plugin.getLogger().info("[IG] 该播报没有参与者接收（仅记录到控制台）");
        }
    }

    /**
     * 本条播报的**收件人**（★ 安全边界，别改回全服）。
     *
     * <ol>
     *   <li>有正式对局 → 本局参与者：**人机**与已 {@code /mgc leave} **退出**的人排除；
     *       <b>已出局但仍在观战</b>的人要收到（他们还在看这一局）</li>
     *   <li>没有正式对局（例如 {@code /ig debug battle} 的独立战斗）→ 退化成
     *       <b>竞技场半径 {@link CleanupPolicy#ARENA_RADIUS} 格内</b>的玩家 ——
     *       与清场同一个范围口径，**仍然有界**</li>
     * </ol>
     */
    private java.util.List<Player> audience() {
        var round = plugin.activeRound().orElse(null);
        if (round != null) {
            java.util.List<Player> out = new java.util.ArrayList<>();
            for (io.mczju.infernalguess.game.GamePlayer gp : round.players()) {
                if (!shouldReceiveBroadcast(gp.isBot(), gp.isExited())) {
                    continue;
                }
                Player p = Bukkit.getPlayer(gp.uuid());
                if (p != null && p.isOnline()) {
                    out.add(p);
                }
            }
            return out;
        }
        Location center = arenaCleanupCenter();
        if (world == null || center == null) {
            return java.util.List.of();
        }
        double r2 = CleanupPolicy.ARENA_RADIUS * CleanupPolicy.ARENA_RADIUS;
        java.util.List<Player> out = new java.util.ArrayList<>();
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(center) <= r2) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * 某参与者是否应收到本局播报（**纯判定，可离线单测**）。
     *
     * <p>规则：**人机**没有客户端；已 {@code /mgc leave} **退出**的人不该再收到本局消息；
     * 其余（含**已出局但仍在观战**的人）都要收到 —— 他们还在看这一局。
     *
     * @param bot    是否为人机
     * @param exited 是否已退出本局（{@code /mgc leave} 或掉线后的标记）
     */
    static boolean shouldReceiveBroadcast(boolean bot, boolean exited) {
        return !bot && !exited;
    }
}
