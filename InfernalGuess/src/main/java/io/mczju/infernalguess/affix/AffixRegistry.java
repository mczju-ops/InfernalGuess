package io.mczju.infernalguess.affix;

import io.mczju.infernalguess.InfernalGuessPlugin;
import io.mczju.infernalguess.battle.BattleTags;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 词条注册表 + **派发中心** —— 本插件自有的词条体系入口，不依赖任何外部插件。
 *
 * <h2>它在体系里的位置</h2>
 * <pre>
 *   BattleListener / BattleService.tick()          ← 事件源（已存在，复用）
 *            ↓  dispatchDamage / dispatchDeath / tickAffixes
 *      AffixRegistry                                ← 本类：按 AffixType 派发，不认具体 id
 *            ↓
 *   Affix 实现（PoisonousAffix …）                  ← 每个词条自包含
 * </pre>
 *
 * <h2>★ 与炒鸡的三处关键差别</h2>
 * <ol>
 *   <li><b>目标类型放宽</b>：{@code LivingEntity} 而非 {@code Player}
 *       → {@code ACTIVE/PASSIVE/DUAL} 在 mob-vs-mob 下天然可用</li>
 *   <li><b>不认具体 id</b>：炒鸡在 service 里按 {@code "1up"} / {@code "dye"} /
 *       {@code "ghastly"} 字符串硬编码特例；本类只按 {@link AffixType} 派发</li>
 *   <li><b>防递归</b>：词条自身造成的伤害**不再触发词条**（见 {@link #DISPATCH_DEPTH}）——
 *       炒鸡的 {@code vengeance} 在玩家场景下靠「路由只认玩家」侥幸没递归，
 *       我们一旦放宽类型就会真的递归（同 tick 互反数十次 → 瞬间双杀）</li>
 * </ol>
 *
 * <h2>词条的权威来源是我们自己</h2>
 * 装配时写入 PDC（{@code BattleTags.affixes()}），派发时读回 —— 判断标准唯一。
 */
public final class AffixRegistry {

    /**
     * 派发深度守卫。
     *
     * <p>词条效果里经常会再次造成伤害（反伤、声波、爆炸）→ 会重新进入
     * {@code EntityDamageByEntityEvent} → 又触发对方的词条 → **递归**。
     *
     * <p>规则：**深度 &gt; 0 时不再派发词条**。即「词条产生的伤害不触发词条」。
     * 这同时满足用户对 {@code vengeance} 的明确要求
     * （「确保反伤这部分伤害不会触发任何其他反伤（包括远古守卫者）」）。
     *
     * <p>用 {@code ThreadLocal} 而不是普通字段：伤害事件虽在主线程，
     * 但插件可能被其它插件从异步上下文触发，用 ThreadLocal 不会互相污染。
     */
    private static final ThreadLocal<Integer> DISPATCH_DEPTH = ThreadLocal.withInitial(() -> 0);

    private final InfernalGuessPlugin plugin;

    /** 已实现的词条：id → 实现。 */
    private final Map<String, Affix> impls = new LinkedHashMap<>();

    /** 实体 → 它装配的词条实现（装配时算一次，避免每次伤害都拆 PDC 字符串）。 */
    private final Map<UUID, List<Affix>> equipped = new LinkedHashMap<>();

    /** 实体 → 运行时状态（一次性 / 冷却 / buff）。 */
    private final Map<UUID, AffixRuntime> runtimes = new LinkedHashMap<>();

    /**
     * ★ **调试专用**：忽略 {@link Affix#chance()} 的概率闸门（由 {@code /ig debug summon ... force} 打开）。
     *
     * <h2>为什么需要它（三轮定点注入的经验）</h2>
     * 带概率的词条**无法靠跑一局来可靠验证**：
     * <ul>
     *   <li>原版 `mama+` 的 `chance` 是 **0.15** —— 两次都不过的概率就有 **72%**</li>
     *   <li>再叠加「只触发一次」「必须被打到」「必须在 15 格内」等条件，
     *       连续多局都验不到是常态（见 dev-log 040）</li>
     * </ul>
     *
     * <p>关键：可测试性应该由**测试入口的开关**解决，
     * **不能去改被测对象本身的数值** —— 那会违反「词条数值必须与原版一致」的裁定
     * （用户 2026-10-11 明确要求：受众熟悉原版数值，改了会影响他们的判断）。
     *
     * <p>因此 {@code chance()} 的原值**一个字都不动**，只在调试时临时旁路它。
     * 由清场时的 {@link #clearRuntime()} 自动复位，避免泄漏到正常对局。
     */
    private boolean forceChance = false;

    public AffixRegistry(InfernalGuessPlugin plugin) {
        this.plugin = plugin;
        registerBuiltins();
    }

    /**
     * 注册全部内置词条。
     *
     * <p>★ **加载期白名单就是这里** —— 只有注册过的 id 才允许写进 {@code mob-groups.yml}
     * （由 {@code MobGroupConfig} 用它做校验），从而杜绝「配置了但静默无效、
     * 却照样抬高权重」这类问题（我们在炒鸡上吃过这个亏）。
     */
    private void registerBuiltins() {
        // ---- 阶段 1 已验证 ----
        register(new io.mczju.infernalguess.affix.impl.SprintAffix());
        register(new io.mczju.infernalguess.affix.impl.BullwarkAffix());
        register(new io.mczju.infernalguess.affix.impl.PoisonousAffix());
        // ---- 阶段 2 ----
        register(new io.mczju.infernalguess.affix.impl.WitheringAffix());
        register(new io.mczju.infernalguess.affix.impl.QuicksandAffix());
        register(new io.mczju.infernalguess.affix.impl.OneUpAffix());
        register(new io.mczju.infernalguess.affix.impl.LifestealAffix());
        register(new io.mczju.infernalguess.affix.impl.BerserkAffix());
        register(new io.mczju.infernalguess.affix.impl.ArcherAffix());
        // ---- 阶段 2 第二批（阶段 2 完成 = 16 条）----
        register(new io.mczju.infernalguess.affix.impl.MamaAffix());
        register(new io.mczju.infernalguess.affix.impl.VexSummonerAffix());
        register(new io.mczju.infernalguess.affix.impl.WardenWrathAffix());
        register(new io.mczju.infernalguess.affix.impl.FireworkAffix());
        register(new io.mczju.infernalguess.affix.impl.GhastlyAffix());
        register(new io.mczju.infernalguess.affix.impl.NecromancerAffix());
        register(new io.mczju.infernalguess.affix.impl.TosserAffix());
        // ---- 阶段 3（要改语义/数值的 9 条）----
        register(new io.mczju.infernalguess.affix.impl.ArmouredAffix());
        register(new io.mczju.infernalguess.affix.impl.MoltenAffix());
        register(new io.mczju.infernalguess.affix.impl.WeaknessAffix());
        register(new io.mczju.infernalguess.affix.impl.RustAffix());
        register(new io.mczju.infernalguess.affix.impl.StormAffix());
        register(new io.mczju.infernalguess.affix.impl.WebberAffix());
        register(new io.mczju.infernalguess.affix.impl.EnderAffix());
        register(new io.mczju.infernalguess.affix.impl.VengeanceAffix());
        register(new io.mczju.infernalguess.affix.impl.GravityAffix());
        // ---- 升级形态（用户裁定：原版按等级/档位变强的形态拆成独立词条）----
        register(new io.mczju.infernalguess.affix.impl.ArmouredPlusAffix());
        register(new io.mczju.infernalguess.affix.impl.MamaPlusAffix());
        // ---- 用户自有词条（★ 非炒鸡来源）----
        register(new io.mczju.infernalguess.affix.impl.PlasticAffix());
    }

    /**
     * 是否开启词条调试日志（受 {@code config.yml} 的 {@code debug.enabled} 控制）。
     *
     * <p>★ 为什么必须有：上一轮我只验到「词条进了剧本 + 头顶显示 + 权重加成」，
     * 但**「到底触发了没」只能从掉血间接推断** —— 我不愿拿「没报错」当「跑对了」。
     * 有了这行日志，每次触发都能在控制台看到。
     */
    private boolean debug() {
        try {
            return plugin.gameConfig() != null && plugin.gameConfig().debugEnabled();
        } catch (Throwable t) {
            return false;   // 配置尚未加载时静默
        }
    }

    /** 注册一个词条实现。 */
    public void register(Affix affix) {
        if (affix == null || affix.id() == null || affix.id().isBlank()) {
            throw new IllegalArgumentException("词条 id 不能为空");
        }
        impls.put(affix.id().toLowerCase(Locale.ROOT), affix);
    }

    // ================= 查询 =================

    public int size() {
        return impls.size();
    }

    /** 是否已有可用词条（空 = 词条系统尚未接入，此时不应生成任何词条）。 */
    public boolean available() {
        return !impls.isEmpty();
    }

    public Set<String> knownIds() {
        return Set.copyOf(impls.keySet());
    }

    public Optional<Affix> get(String affixId) {
        return affixId == null
                ? Optional.empty()
                : Optional.ofNullable(impls.get(affixId.toLowerCase(Locale.ROOT)));
    }

    /** 是否是**已实现**的词条 id（大小写不敏感）。 */
    public boolean isKnown(String affixId) {
        return affixId != null && impls.containsKey(affixId.toLowerCase(Locale.ROOT));
    }

    // ================= 显示名 / PDC =================

    /**
     * 词条显示名。
     *
     * <p>取值顺序：{@code display.yml} 的 {@code affixes.<id>} → 原样返回 id。
     * 放在配置里而不是硬编码，便于改文案与加颜色。
     */
    public String displayName(String affixId) {
        if (affixId == null || affixId.isBlank()) {
            return "";
        }
        String key = "affixes." + affixId.toLowerCase(Locale.ROOT);
        try {
            if (plugin.displayConfig() != null && plugin.displayConfig().hasMessage(key)) {
                return plugin.displayConfig().raw(key);
            }
        } catch (Throwable ignored) {
            // 配置尚未加载时走兜底
        }
        return affixId;
    }

    /**
     * 读一个生物携带的词条 id 列表。
     *
     * <p>来源是**我方在生成时写入的 PDC**（{@code BattleTags.affixes()}，逗号分隔）——
     * 读自己写入的数据，判断标准唯一、不受外部插件变动影响。
     */
    public List<String> affixIdsOf(LivingEntity entity) {
        if (entity == null) {
            return List.of();
        }
        try {
            String raw = entity.getPersistentDataContainer()
                    .get(BattleTags.affixes(), PersistentDataType.STRING);
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (String s : Arrays.asList(raw.split(","))) {
                String t = s.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
            return out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** 某个生物的词条**显示名**列表（名牌与下注界面用）。 */
    public List<String> affixNamesOf(LivingEntity entity) {
        List<String> out = new ArrayList<>();
        for (String id : affixIdsOf(entity)) {
            out.add(displayName(id));
        }
        return out;
    }

    // ================= 装配 / 卸下 =================

    /**
     * 装配：按 PDC 里记的词条 id 找到实现，并调用各自的 {@code onEquip}。
     *
     * <p>由 {@code BattleService.spawnOne} 在生成后调用。
     * 未注册的 id 会被**静默忽略**（加载期白名单本应已经拦住它们；
     * 这里再兜一层，避免运行期因为一条坏配置崩掉整场对局）。
     */
    public void equip(LivingEntity entity) {
        if (entity == null || !entity.isValid()) {
            return;
        }
        List<String> ids = affixIdsOf(entity);
        if (ids.isEmpty()) {
            return;
        }
        List<Affix> list = new ArrayList<>();
        AffixRuntime runtime = runtimeFor(entity.getUniqueId());
        for (String id : ids) {
            Affix affix = impls.get(id);
            if (affix == null) {
                continue;
            }
            list.add(affix);
            runtime.markEquipped(id);
        }
        if (list.isEmpty()) {
            return;
        }
        equipped.put(entity.getUniqueId(), List.copyOf(list));

        long now = Bukkit.getCurrentTick();
        for (Affix affix : list) {
            if (affix.type() != AffixType.STAT) {
                continue;   // STAT 在装配时生效，其余类型等触发
            }
            try {
                affix.onEquip(new AffixContext(plugin, entity, runtime, now));
            } catch (Throwable t) {
                plugin.getLogger().warning("词条 " + affix.id() + " 的 onEquip 出错："
                        + t + "（已跳过该词条，不影响对局）");
            }
        }
    }

    /** 卸下某个实体的全部词条（清场时调用）。 */
    public void unequip(LivingEntity entity) {
        if (entity == null) {
            return;
        }
        List<Affix> list = equipped.remove(entity.getUniqueId());
        AffixRuntime runtime = runtimes.remove(entity.getUniqueId());
        if (list == null || runtime == null) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        for (Affix affix : list) {
            try {
                affix.onUnequip(new AffixContext(plugin, entity, runtime, now));
            } catch (Throwable t) {
                plugin.getLogger().warning("词条 " + affix.id() + " 的 onUnequip 出错：" + t);
            }
        }
    }

    /** 清空全部运行时状态（清场时调用）—— 否则跨回合残留会让下一局带着上一局的状态。 */
    public void clearRuntime() {
        equipped.clear();
        runtimes.clear();
        forceChance = false;   // ★ 调试开关随清场复位，防止泄漏到正常对局
    }

    /** 开关「忽略概率闸门」（调试用，见 {@link #forceChance}）。 */
    public void setForceChance(boolean force) {
        this.forceChance = force;
    }

    public boolean forceChance() {
        return forceChance;
    }

    public AffixRuntime runtimeFor(UUID uuid) {
        return runtimes.computeIfAbsent(uuid, k -> new AffixRuntime());
    }

    public Optional<AffixRuntime> runtime(UUID uuid) {
        return Optional.ofNullable(runtimes.get(uuid));
    }

    /** 该实体是否装配了某类型或某 id 的词条（供其它模块判定，如「是否有吸血」）。 */
    public boolean has(LivingEntity entity, String affixId) {
        if (entity == null) {
            return false;
        }
        List<Affix> list = equipped.get(entity.getUniqueId());
        if (list == null) {
            return false;
        }
        String want = affixId.toLowerCase(Locale.ROOT);
        return list.stream().anyMatch(a -> a.id().equalsIgnoreCase(want));
    }

    // ================= 派发 =================

    /**
     * 伤害事件派发。
     *
     * <p>mob-vs-mob 时 {@code EntityDamageByEntityEvent} **本来就会 fire**，
     * 所以这里不需要「补事件分支」—— 只是从两个角度看同一个事件：
     * <ul>
     *   <li>攻击方：{@code ACTIVE} + {@code DUAL}，目标 = {@code victim}</li>
     *   <li>受击方：{@code PASSIVE} + {@code DUAL}，目标 = {@code attacker}</li>
     * </ul>
     *
     * @param attacker 攻击者（已回溯过投射物的 shooter）
     * @param victim   受害者
     */
    public void dispatchDamage(LivingEntity attacker, LivingEntity victim, Event event) {
        if (attacker == null || victim == null || attacker.equals(victim)) {
            return;
        }
        if (DISPATCH_DEPTH.get() > 0) {
            return;   // 词条产生的伤害 → 不再触发词条（防递归）
        }
        DISPATCH_DEPTH.set(DISPATCH_DEPTH.get() + 1);
        try {
            long now = Bukkit.getCurrentTick();
            fire(attacker, AffixType.ACTIVE, victim, event, now);
            fire(attacker, AffixType.DUAL, victim, event, now);
            fire(victim, AffixType.PASSIVE, attacker, event, now);
            fire(victim, AffixType.DUAL, attacker, event, now);
        } finally {
            DISPATCH_DEPTH.set(DISPATCH_DEPTH.get() - 1);
        }
    }

    /** 死亡派发（{@code DEATH} / 亡语）。目标可能是 null —— 怪物击杀时 Bukkit 没有 killer。 */
    public void dispatchDeath(LivingEntity entity, LivingEntity killer, Event event) {
        if (entity == null) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        fire(entity, AffixType.DEATH, killer, event, now);
    }

    /**
     * 周期派发，由 {@code BattleService} 的 5 tick 心跳驱动。
     *
     * <p>做两件事：
     * <ol>
     *   <li>对**所有**词条调 {@link Affix#onTick} —— 承载持续效果
     *       （如 {@code lifesteal} 的按秒回血，炒鸡也是在 tick 里做的）</li>
     *   <li>对 {@link AffixType#RANGE} 词条调 {@link Affix#onTrigger} ——
     *       ★ 炒鸡的 RANGE **不 fire 任何事件**，只能自己轮询，这里就是那个轮询</li>
     * </ol>
     */
    public void tickAffixes(List<LivingEntity> trackedEntities) {
        if (DISPATCH_DEPTH.get() > 0) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        DISPATCH_DEPTH.set(DISPATCH_DEPTH.get() + 1);
        try {
            for (LivingEntity entity : trackedEntities) {
                if (entity == null || !entity.isValid() || entity.isDead()) {
                    continue;
                }
                runtimeFor(entity.getUniqueId()).prune(now);   // 顺手清到期状态
                List<Affix> list = equipped.get(entity.getUniqueId());
                if (list == null) {
                    continue;
                }
                LivingEntity target = null;
                if (entity instanceof org.bukkit.entity.Mob mob) {
                    target = mob.getTarget();
                }
                for (Affix affix : list) {
                    AffixContext ctx = ctx(entity, target, null, now);
                    // 1) 周期效果（所有类型）
                    try {
                        affix.onTick(ctx);
                    } catch (Throwable t) {
                        plugin.getLogger().warning("词条 " + affix.id() + " 的 onTick 出错：" + t);
                    }
                    // 2) RANGE 触发
                    if (affix.type() == AffixType.RANGE) {
                        trigger(entity, affix, target, null, now);
                    }
                }
            }
        } finally {
            DISPATCH_DEPTH.set(DISPATCH_DEPTH.get() - 1);
        }
    }

    /**
     * **伤害拦截派发**（{@link AffixType#WARD}），在任何伤害生效前调用。
     *
     * <p>由 {@code EntityDamageEvent} 驱动 —— 含环境伤害（摔落/火焰/凋零），
     * 因为 {@code 1up} 的语义就是「不管怎么掉血，濒死时回满一次」。
     *
     * <p>★ **不受 {@code DISPATCH_DEPTH} 限制**：拦截本身不产生伤害，
     * 而词条造成的伤害若把某个单位打到濒死，它理应仍能触发 {@code 1up}。
     */
    public void dispatchIncomingDamage(LivingEntity victim,
                                       org.bukkit.event.entity.EntityDamageEvent event) {
        if (victim == null || event == null) {
            return;
        }
        List<Affix> list = equipped.get(victim.getUniqueId());
        if (list == null || list.isEmpty()) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        for (Affix affix : list) {
            if (affix.type() != AffixType.WARD) {
                continue;
            }
            if (debug()) {
                // ★ 为什么必须记这一行：WARD 是**唯一不产生触发日志**的路径 ——
                //   onIncomingDamage 的结果只有「拦下 / 放行」两种，不发任何事件、
                //   也不经过 trigger()。上一轮 1up 明明进了剧本，日志里却完全查不到
                //   它有没有生效（见 dev-log 036 §3），就是缺了这一行。
                plugin.getLogger().info("[词条] " + victim.getType() + " WARD 派发 → "
                        + affix.id() + "（伤害 " + String.format(Locale.ROOT, "%.2f", event.getFinalDamage())
                        + "，血量 " + String.format(Locale.ROOT, "%.2f", victim.getHealth()) + "）");
            }
            try {
                if (affix.onIncomingDamage(ctx(victim, null, event, now), event)) {
                    if (debug()) {
                        plugin.getLogger().info("[词条] " + victim.getType()
                                + " 已被 " + affix.id() + " 拦下本次伤害");
                    }
                    break;   // 已被拦下，不再派发其它 WARD
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("词条 " + affix.id() + " 的伤害拦截出错：" + t);
            }
        }
    }

    /** 组装一个上下文（统一入口，避免各处参数顺序写错）。 */
    private AffixContext ctx(LivingEntity holder, LivingEntity target,
                             Event event, long now) {
        AffixContext ctx = new AffixContext(plugin, holder, runtimeFor(holder.getUniqueId()), now);
        ctx.setTarget(target);
        ctx.setTriggerEvent(event);
        return ctx;
    }

    /** 对某实体派发指定类型。 */
    private void fire(LivingEntity holder, AffixType type, LivingEntity target,
                      Event event, long now) {
        if (holder == null || !holder.isValid() || holder.isDead()) {
            return;
        }
        List<Affix> list = equipped.get(holder.getUniqueId());
        if (list == null || list.isEmpty()) {
            return;
        }
        for (Affix affix : list) {
            if (affix.type() != type) {
                continue;
            }
            trigger(holder, affix, target, event, now);
        }
    }

    /** 单个词条的触发：冷却判定 → **掷概率** → 执行 → 记冷却。 */
    private void trigger(LivingEntity holder, Affix affix, LivingEntity target,
                         Event event, long now) {
        AffixRuntime runtime = runtimeFor(holder.getUniqueId());
        long cd = affix.cooldownTicks();
        if (cd > 0 && runtime.isOnCooldown(affix.id(), now)) {
            return;
        }
        // ★ 概率判定与原版一致：先扣冷却，再掷概率；**概率没通过也照样消耗冷却**
        //   （原版 triggerActiveSkills 是先 setCooldown 再交给技能，概率在技能里掷）
        if (cd > 0) {
            runtime.setCooldown(affix.id(), now + cd);
        }
        double chance = affix.chance();
        if (!forceChance && chance < 1.0 && Math.random() >= chance) {
            if (debug()) {
                plugin.getLogger().info("[词条] " + holder.getType() + " " + affix.id()
                        + " 概率未通过（chance=" + chance + "）");
            }
            return;
        }
        if (debug()) {
            plugin.getLogger().info("[词条] " + holder.getType() + " 触发 " + affix.id()
                    + (forceChance && chance < 1.0 ? "（force 已忽略 chance=" + chance + "）" : "")
                    + (target == null ? "" : " → " + target.getType()));
        }
        try {
            affix.onTrigger(ctx(holder, target, event, now));
        } catch (Throwable t) {
            plugin.getLogger().warning("词条 " + affix.id() + " 触发出错："
                    + t + "（已忽略，不影响对局）");
        }
    }

    /**
     * 投射物/爆炸实体的**来源标记** metadata 键。
     *
     * <p>价值：{@code EntityDamageByEntityEvent} 的 {@code getDamager()} 在
     * 「箭 / 火球 / 凋灵之首 / 烟花」的情况下**不是攻击者本人**，而是那个投射物实体。
     * 于是两件事都会失效：
     * <ol>
     *   <li>**同阵营免伤** —— {@code BattleListener.onDamage} 第一行就判
     *       {@code instanceof LivingEntity}，投射物会直接 return 掉
     *       → 词条的投射物会**炸到队友**（这是我们在自己代码里查出的真实缺口）</li>
     *   <li>伤害归因 —— 死亡播报与「谁打的」都拿不到</li>
     * </ol>
     * 所以词条生成投射物/爆炸时要调用 {@link #markSource} 打上归属，
     * 再由 {@link #resolveAttacker} 回溯出来。
     *
     * <p>炒鸡用的是同样的思路（metadata {@code infernalmobs_source}），我们沿用。
     */
    public static final String SOURCE_META = "ig_affix_source";

    /**
     * 词条投射物的**伤害数值** metadata 键。
     *
     * <p>为什么不用「火球/凋灵的天然爆炸伤害」：那会**炸方块**（我们的红线是
     * 「不得破坏场地」，而 {@code mobGriefing: false} 只拦 mob 的爆炸、拦不住
     * 插件直接造的带 yield 的火球）。所以我们的做法是：
     * <b>把 yield 设为 0（不爆炸），用这个 metadata 记下我们想要的伤害，
     * 再由 {@code BattleListener.onProjectileHit} 只对**被命中的实体**结算</b>。
     *
     * <p>副作用是好的：伤害完全由我们控制，也便于将来调数值。
     */
    public static final String DAMAGE_META = "ig_affix_damage";

    /** 给词条生成的投射物打上「这是谁放的」。 */
    public static void markSource(org.bukkit.entity.Entity projectile, LivingEntity owner) {
        if (projectile == null || owner == null) {
            return;
        }
        projectile.setMetadata(SOURCE_META, new org.bukkit.metadata.FixedMetadataValue(
                io.mczju.infernalguess.InfernalGuessPlugin.getInstance(), owner.getUniqueId()));
    }

    /** 给词条生成的投射物设好「命中时结算多少伤害」 + 来源（一次调用，避免漏打标记）。 */
    public static void markProjectile(org.bukkit.entity.Entity projectile,
                                      LivingEntity owner, double damage) {
        markSource(projectile, owner);
        if (projectile != null) {
            projectile.setMetadata(DAMAGE_META, new org.bukkit.metadata.FixedMetadataValue(
                    io.mczju.infernalguess.InfernalGuessPlugin.getInstance(), damage));
        }
    }

    /** 读词条投射物的伤害数值；不是我们的投射物时返回 0。 */
    public static double damageOf(org.bukkit.entity.Entity projectile) {
        try {
            if (projectile != null && projectile.hasMetadata(DAMAGE_META)
                    && projectile.getMetadata(DAMAGE_META).get(0).value() instanceof Number n) {
                return n.doubleValue();
            }
        } catch (Throwable ignored) {
            // metadata 不可读 → 当 0
        }
        return 0.0;
    }

    /**
     * 从伤害事件的 {@code damager} 回溯真正的攻击者。
     *
     * <p>顺序：本身就是 {@code LivingEntity} → 投射物回溯 {@code getShooter()}
     * → 我方 {@link #SOURCE_META} 标记（兜住烟花这类 shooter 为空的爆炸物）。
     *
     * @return 攻击者；无法判定时返回 {@code null}
     */
    public static LivingEntity resolveAttacker(org.bukkit.entity.Entity damager) {
        if (damager instanceof LivingEntity living) {
            return living;
        }
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof LivingEntity shooter) {
            return shooter;
        }
        // 兜底：词条自己打的标记（烟花、以及 shooter 为空的爆炸物）
        try {
            if (damager != null && damager.hasMetadata(SOURCE_META)) {
                Object raw = damager.getMetadata(SOURCE_META).get(0).value();
                if (raw instanceof java.util.UUID uuid
                        && Bukkit.getEntity(uuid) instanceof LivingEntity owner) {
                    return owner;
                }
            }
        } catch (Throwable ignored) {
            // metadata 不可读时当作无法判定
        }
        return null;
    }
}
