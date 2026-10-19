package io.mczju.infernalguess.battle;

import io.mczju.infernalguess.InfernalGuessPlugin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Vex;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;

import java.util.EnumSet;
import java.util.Set;

/**
 * 战斗事件监听。
 *
 * <p>四件事：
 * <ol>
 *   <li><b>死亡登记</b>：把死亡单位从追踪表摘掉（{@code handleDeath} 是幂等的，
 *       与 5 tick 扫描兜底共用）</li>
 *   <li><b>清掉落</b>：战斗生物不掉东西（避免地上堆装备）</li>
 *   <li><b>同阵营免伤</b>：原版**不拦**实体间的队伍伤害
 *       （[MC-88511](https://mojira.dev/MC-88511) 被判 WAI：「mobs ignore all team rules」），
 *       所以必须由插件兜底，否则爆炸/范围伤害会误伤队友。</li>
 *   <li><b>★ 收养衍生单位</b>：战斗中新出现的召唤物归入召唤者的阵营，
 *       但**不计入存活判定**（用户第 3 条）</li>
 * </ol>
 */
public final class BattleListener implements Listener {

    /**
     * 会产生「阵营衍生单位」的生成原因。
     *
     * <p>不含 {@code NATURAL} / {@code SPAWNER} 等 —— 那些不属于「某一方召唤出来的」。
     */
    private static final Set<CreatureSpawnEvent.SpawnReason> DERIVED_REASONS =
            EnumSet.of(
                    CreatureSpawnEvent.SpawnReason.SPELL,             // 唤魔者召唤恼鬼
                    CreatureSpawnEvent.SpawnReason.REINFORCEMENTS,    // 僵尸增援
                    CreatureSpawnEvent.SpawnReason.SLIME_SPLIT,       // 史莱姆 / 岩浆怪分裂
                    CreatureSpawnEvent.SpawnReason.SILVERFISH_BLOCK,  // 蠹虫
                    CreatureSpawnEvent.SpawnReason.DUPLICATION,       // 复制类
                    CreatureSpawnEvent.SpawnReason.METAMORPHOSIS,
                    CreatureSpawnEvent.SpawnReason.TRAP              // 骷髅陷阱
            );

    /** 判定归属时搜索附近已登记单位的半径。 */
    private static final double ADOPT_RADIUS = 16.0;

    private final InfernalGuessPlugin plugin;

    public BattleListener(InfernalGuessPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------- 衍生单位 ----------------

    /**
     * 战斗中新增的实体 → 尝试归入某一方（**非计数单位**）。
     *
     * <p>判定优先级：
     * <ol>
     *   <li><b>恼鬼</b>：用 {@link Vex#getSummoner()} **精确**找到召唤者，
     *       召唤者是我们的兵就继承它的阵营（这是最可靠的一条路）</li>
     *   <li>其它：生成原因在 {@link #DERIVED_REASONS} 里，且附近 16 格内有我们的兵 → 就近继承</li>
     * </ol>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        BattleService battle = BattleService.current().orElse(null);
        if (battle == null || battle.isCleaned()) {
            return;
        }
        // ⚠️ world.spawn() 会在返回前触发本事件，那时我们自己的兵还没登记 —— 必须屏蔽
        if (battle.isSpawning()) {
            return;
        }
        LivingEntity entity = event.getEntity();

        // 1) 恼鬼：精确按召唤者归属
        if (entity instanceof Vex vex) {
            Mob summoner = vex.getSummoner();
            if (summoner != null) {
                var unit = battle.tracker().get(summoner.getUniqueId()).orElse(null);
                if (unit != null) {
                    battle.adoptDerived(entity, unit.side(),
                            "VEX_OF_" + summoner.getType().name());
                    return;
                }
            }
        }

        // 2) 其它衍生：按生成原因 + 就近归属
        if (DERIVED_REASONS.contains(event.getSpawnReason())) {
            // ★ 史莱姆 / 岩浆怪分裂：用**父体阵营**精确收养，不再靠「最近的单位」猜。
            if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SLIME_SPLIT
                    && entity instanceof org.bukkit.entity.AbstractCubeMob) {
                var side = consumePendingSplit(entity.getLocation());
                if (side != null) {
                    // 分裂体是**真正在打**的单位 → 计入存活判定（counted=true），
                    // 不能走 adoptDerived（后者把 counted 固定成 false）
                    battle.tagSide(entity, side, true, java.util.List.of());
                }
                // 消费不到待收养记录就**不猜** —— 猜错等于把这只小怪送给对面
                return;
            }
            var owner = battle.nearestUnit(entity.getLocation(), ADOPT_RADIUS);
            if (owner != null) {
                battle.adoptDerived(entity, owner.side(), event.getSpawnReason().name());
            }
        }
    }

    // ---------------- 史莱姆 / 岩浆怪分裂 ----------------

    /** 一条待收养记录：父体位置 + 阵营 + 到期时刻。 */
    private record PendingSplit(org.bukkit.Location at, BattleSide side, long expireAt) {
    }

    private final java.util.List<PendingSplit> pendingSplits = new java.util.ArrayList<>();

    /** 待收养记录的存活时长：分裂体与事件几乎同一 tick 产生，1 秒足够宽松。 */
    private static final long PENDING_SPLIT_TTL_MS = 1000L;

    /** 分裂体与父体位置的最大偏差（分裂体就生成在父体体内）。 */
    private static final double SPLIT_MATCH_RADIUS = 4.0;

    /**
     * ★ 史莱姆 / 岩浆怪死亡分裂 —— **从父体精确取阵营**。
     *
     * <h2>为什么必须有它（用户实测缺陷）</h2>
     * 原来分裂体走的是「生成原因在 {@link #DERIVED_REASONS} 里 + 附近 16 格内最近的
     * 已登记单位」这套**猜**的逻辑，有两个缺陷：
     * <ol>
     *   <li>分裂发生在**父体死亡时** —— 若 16 格内没有我方单位就**不收养**</li>
     *   <li>就算 16 格内有单位，也可能是**敌人** → 会把分裂体**归给对面**</li>
     * </ol>
     * 而 {@code SlimeSplitEvent#getEntity()} 返回的是 {@code AbstractCubeMob}（**父体**），
     * 且**同时覆盖史莱姆与岩浆怪** → 可以精确取阵营。
     *
     * <p>顺序上：原版在 {@code Slime#die()} 里先分裂、之后才抛死亡事件，
     * 所以此刻父体**仍在追踪表里**，查得到阵营。（查不到时宁可不收养，也不猜。）
     *
     * <p>事件不提供分裂体列表，所以两条路一起走：
     * ①立刻收养父体位置附近「还没登记」的方块怪；②记一条待收养，供随后的
     * {@code CreatureSpawnEvent(SLIME_SPLIT)} 消费（两种事件的先后在不同版本可能不同）。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSlimeSplit(org.bukkit.event.entity.SlimeSplitEvent event) {
        BattleService battle = BattleService.current().orElse(null);
        if (battle == null || battle.isCleaned()) {
            return;
        }
        var parent = event.getEntity();
        var unit = battle.tracker().get(parent.getUniqueId()).orElse(null);
        if (unit == null) {
            return;   // 归属不明 → 宁可不收养，也不猜（猜错会把小怪送给对面）
        }
        long now = System.currentTimeMillis();
        pendingSplits.removeIf(p -> p.expireAt() < now);
        pendingSplits.add(new PendingSplit(parent.getLocation(), unit.side(),
                now + PENDING_SPLIT_TTL_MS));
        adoptNeighbouringCubes(battle, parent.getLocation(), unit.side());
    }

    /** 消费一条待收养记录（位置足够近且未过期）。 */
    private BattleSide consumePendingSplit(org.bukkit.Location at) {
        long now = System.currentTimeMillis();
        pendingSplits.removeIf(p -> p.expireAt() < now);
        for (PendingSplit p : pendingSplits) {
            var w1 = p.at().getWorld();
            var w2 = at.getWorld();
            if (w1 != null && w1.equals(w2)
                    && p.at().distanceSquared(at) <= SPLIT_MATCH_RADIUS * SPLIT_MATCH_RADIUS) {
                return p.side();
            }
        }
        return null;
    }

    /**
     * 把父体附近**刚分裂出来**的方块怪（史莱姆 / 岩浆怪）收养进来。
     *
     * <h2>★ 为什么必须加「刚生成」这一条（2026-10-13 安全审查）</h2>
     * 旧实现只看「是方块怪 + 在我 4 格内 + 还没登记」——**归属靠类型与邻近**，
     * 于是**别人的**史莱姆（玩家养的、别的插件刷的）只要恰好在这 4 格里，
     * 就会被收编进本局，并在清场时被当作我方单位**删掉**。
     * 现在多一条时间条件：只有**刚刚生成**（本 tick 前后）的方块怪才算分裂体。
     */
    private void adoptNeighbouringCubes(BattleService battle, org.bukkit.Location at, BattleSide side) {
        if (at.getWorld() == null) {
            return;
        }
        for (org.bukkit.entity.Entity e : at.getWorld().getNearbyEntities(at, SPLIT_MATCH_RADIUS,
                SPLIT_MATCH_RADIUS, SPLIT_MATCH_RADIUS)) {
            if (e instanceof org.bukkit.entity.AbstractCubeMob cube
                    && isFreshSpawn(cube.getTicksLived())
                    && battle.tracker().get(cube.getUniqueId()).isEmpty()) {
                battle.tagSide(cube, side, true, java.util.List.of());
            }
        }
    }

    /**
     * 方块怪是否**刚生成**（分裂体判定，纯函数便于离线单测）。
     *
     * <p>分裂子体与父体死亡在同一 tick 内产生，因此 {@code ticksLived} 极小；
     * 给 2 tick 余量以兼容不同版本/事件先后。玩家或别的插件早就放着的史莱姆
     * （{@code ticksLived} 很大）因此**不会被误收编**。
     *
     * @param ticksLived 实体的 {@code getTicksLived()}
     */
    static boolean isFreshSpawn(int ticksLived) {
        return ticksLived <= 2;
    }

    /**
     * 阻止**参战猪灵在主世界变成僵尸猪灵** —— ⚠️ 这已经是**兜底**，不再是主开关。
     *
     * <h2>为什么主开关换了</h2>
     * 本事件（{@code TransformReason.PIGLIN_ZOMBIFIED}）只在**转化流程走完时**触发，
     * 而用户实测的「猪灵一直颤抖」是**转化倒计时期间**的动画
     * （{@code PiglinAbstract#isConverting()}）：取消终点并不能停下倒计时，
     * 于是「抖 → 转化 → 被取消 → 继续抖」无限循环。
     *
     * <p>现在真正起作用的是 {@code BattleService.preventZombification(...)} ——
     * 生成时调 {@code PiglinAbstract#setImmuneToZombification(true)}（原版自己用的开关）。
     * 本监听器保留，用于覆盖「在我们标记之前就已经开始转化」的漏网情况。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (event.getTransformReason() != EntityTransformEvent.TransformReason.PIGLIN_ZOMBIFIED) {
            return;
        }
        if (BattleService.forEntity(event.getEntity().getUniqueId()).isPresent()) {
            event.setCancelled(true);
        }
    }

    // ---------------- 传送拦截 / 美西螈假死 ----------------

    /**
     * **拦下末影人与潜影贝的传送**（用户要求 2026-10-13）。
     *
     * <h2>为什么要拦</h2>
     * 末影人（被弹射物命中 / 被注视 / 下雨）与潜影贝（受伤时）会瞬移，
     * 结果常常是「**飘出竞技场**，双方再也打不到」—— 一局就废了。
     *
     * <h2>★ 为什么拦传送不会破坏「末影人免疫弹射物」</h2>
     * 原版 {@code Enderman.hurt()} 对投射物的处理是「**瞬移 + 直接 return false**」：
     * 「不掉血」与瞬移是否成功**无关** → 取消传送后箭**照样打不动它**，
     * 只是它**不再飘走**。已实测确认（骨架射它：血量不掉、坐标不动）。
     *
     * <p>只对本插件**接管**的单位生效（{@code BY_ENTITY} 查得到），别人的末影人不动。
     * 本事件的 from/to 与 cause：26.2 的 {@code EntityTeleportEvent} **没有 cause**
     * （{@code javap} 实测），所以这里取消的是该实体**所有** Bukkit 可见的传送。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityTeleport(org.bukkit.event.entity.EntityTeleportEvent event) {
        org.bukkit.entity.Entity e = event.getEntity();
        if (!(e instanceof org.bukkit.entity.Enderman)
                && !(e instanceof org.bukkit.entity.Shulker)) {
            return;
        }
        BattleService battle = BattleService.forEntity(e.getUniqueId()).orElse(null);
        if (battle == null) {
            return;   // 不是我们的兵 → 一律不碰
        }
        event.setCancelled(true);
        battle.recordBlockedTeleport(e);
    }

    /**
     * **美西螈「假死复活」**的拦截点 —— 必须在伤害**生效前**取消，所以用 {@code HIGHEST}
     * （晚于同阵营免伤与词条派发，确保是「最后一道闸」）。
     *
     * <p>判定与状态都在 {@link BattleService#handleAxolotlDamage}，这里只做转发。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAxolotlPlayDead(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        BattleService battle = BattleService.forEntity(victim.getUniqueId()).orElse(null);
        if (battle == null) {
            return;
        }
        battle.handleAxolotlDamage(victim, event);
    }

    // ---------------- 死亡 / 伤害 ----------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        var battle = BattleService.forEntity(event.getEntity().getUniqueId());
        if (battle.isEmpty()) {
            return;
        }
        // ★ 亡语词条要在**摘掉追踪之前**派发 —— 否则 equipped 表里已经没它了
        plugin.affixRegistry().dispatchDeath(event.getEntity(),
                io.mczju.infernalguess.affix.AffixRegistry.resolveAttacker(
                        event.getEntity().getKiller()),
                event);
        // ★ 记「最后一次敌方击杀」——同归于尽时的裁决要用（用户 2026-10-13 规则：
        //   最后一击由苦力怕造成 ⇒ 苦力怕那一方赢）。必须在 handleDeath 之前，
        //   因为那一刻实体还带着 getLastDamageCause()。
        if (event.getEntity() instanceof LivingEntity victim) {
            battle.get().recordKill(victim);
        }
        battle.get().handleDeath(event.getEntity().getUniqueId());
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    /**
     * **伤害拦截**（{@code 1up} 用）—— 必须在伤害生效前介入，所以挂
     * {@link org.bukkit.event.entity.EntityDamageEvent} 而不是 {@code EntityDamageByEntityEvent}。
     *
     * <p>刻意**不区分伤害来源**：近战、投射物、摔落、火焰、凋零都算 ——
     * 这与炒鸡的 {@code onMobDamaged} 一致（它也只收 {@code EntityDamageEvent}），
     * 也正是 {@code 1up} 能在怪打怪下本来就生效的原因。
     *
     * <p>优先级低于 {@code onDamage}：同阵营免伤先把伤害取消掉，这里就不会白跑一遍。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onAnyDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        if (BattleService.sideOf(victim).isEmpty()) {
            return;   // 不是参战单位 → 不可能带词条
        }
        plugin.affixRegistry().dispatchIncomingDamage(victim, event);
    }

    /**
     * 词条投射物的**命中结算**。
     *
     * <p>为什么不让火球/凋灵之首自己爆炸：那会**炸方块**
     * （我们的红线是「不得破坏场地」，而 {@code mobGriefing: false}
     * 只拦 mob 的爆炸，拦不住插件造的带 yield 的投射物）。
     * 所以词条把 yield 设为 0、把伤害写进 metadata，由这里**只对被命中的实体**结算。
     *
     * <p>顺带做**同阵营过滤** —— 与近战那条免伤一致，避免词条的投射物打到队友。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onProjectileHit(org.bukkit.event.entity.ProjectileHitEvent event) {
        org.bukkit.entity.Entity proj = event.getEntity();
        double damage = io.mczju.infernalguess.affix.AffixRegistry.damageOf(proj);
        if (damage <= 0) {
            return;   // 不是我们的投射物（或没设伤害）
        }
        LivingEntity owner = io.mczju.infernalguess.affix.AffixRegistry.resolveAttacker(proj);
        if (event.getHitEntity() instanceof LivingEntity victim && owner != null
                && !owner.equals(victim)) {
            var ownerSide = BattleService.sideOf(owner);
            var victimSide = BattleService.sideOf(victim);
            boolean sameSide = ownerSide.isPresent() && victimSide.isPresent()
                    && ownerSide.get() == victimSide.get();
            if (!sameSide) {
                victim.damage(damage, owner);   // 归因给施法者，死亡播报才认得出
            }
        }
        proj.remove();
    }

    /**
     * 伤害 → ① 同阵营免伤 ② 词条派发。
     *
     * <h2>★ 这里修掉了我们自己的一个真实缺口</h2>
     * 旧实现第一行是 {@code if (!(event.getDamager() instanceof LivingEntity attacker)) return;}
     * —— 而**箭 / 火球 / 凋灵之首 / 烟花都不是 {@code LivingEntity}**，
     * 于是直接返回、**后面那段同阵营免伤根本不执行**。
     * 新词条里 {@code archer} / {@code ghastly} / {@code necromancer} / {@code firework}
     * 全是投射物伤害 → 一上线就会**炸到队友**。
     *
     * <p>现在改为用 {@link io.mczju.infernalguess.affix.AffixRegistry#resolveAttacker}
     * 回溯真正的攻击者（投射物看 {@code getShooter()}，词条生成物看
     * {@link io.mczju.infernalguess.affix.AffixRegistry#SOURCE_META} 标记）。
     *
     * <p>⚠️ 回溯不到来源时**不取消伤害** —— 因为毒雾的凋零、摔落、火焰这些环境伤害
     * 也必须能生效（它们没有攻击者）。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        LivingEntity attacker = io.mczju.infernalguess.affix.AffixRegistry
                .resolveAttacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        var victimSide = BattleService.sideOf(victim);
        var attackerSide = BattleService.sideOf(attacker);
        if (victimSide.isPresent() && attackerSide.isPresent()
                && victimSide.get() == attackerSide.get()) {
            event.setCancelled(true); // 同阵营免伤（原版队伍规则对实体不生效）
            return;
        }
        // 只有至少一方是我们的兵时才派发（未参战实体不带词条，派发也是空转）
        if (victimSide.isPresent() || attackerSide.isPresent()) {
            plugin.affixRegistry().dispatchDamage(attacker, victim, event);
        }
    }
}
