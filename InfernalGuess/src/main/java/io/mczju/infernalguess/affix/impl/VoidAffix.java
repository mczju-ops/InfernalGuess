package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 虚无（{@code void}）—— **初始抗性提升 V，受到 10 次实体伤害后直接死亡**。
 *
 * <p>照抄 Maggoteers 的 `void` + `void_frail` 两段配置（2026-10-29 设计案）：
 * <pre>
 *   void:       生成时 ADD_POTION resistance amp 4（无限）+ 龙息环 600 tick
 *   void_frail: counter { count: damage_taken, amount: 10 }
 *                → CLEAR_POTIONS [resistance] → SOUND → DAMAGE 10000
 * </pre>
 *
 * <h2>★ 为什么是 PASSIVE（而不是 WARD）</h2>
 * <ul>
 *   <li>Maggoteers 的计数器吃的是 `EntityDamageByEntityEvent` ——
 *       **抗性把伤害吃成 0 也照样计 1 次受击**，环境伤害（毒雾的凋零、摔落、火焰）不计数；</li>
 *   <li>本项目的 `AffixRegistry.dispatchDamage` 只在
 *       `BattleListener.onDamage`（`EntityDamageByEntityEvent`、同阵营免伤之后）派发 PASSIVE，
 *       语义与「被实体打中」完全一致；</li>
 *   <li>★ **不能用 WARD**：`dispatchIncomingDamage` 对 WARD 是「一个返回 true 就 break」——
 *       同时带 `1up` 时，`1up` 可能在虚无之前把第 10 次伤害拦下并回满，
 *       虚无就**永远数不到第 10 次**。PASSIVE 不拦截伤害，不会被别的 WARD 截断。</li>
 * </ul>
 *
 * <h2>★ 为什么用 setHealth(0) 而不是 damage(10000)</h2>
 * Maggoteers 先清抗性再打 10000 伤害；本项目直接用 `setHealth(0)`：
 * <ul>
 *   <li>不受抗性影响（不需要先清也能保证必死）；</li>
 *   <li>不会产生一个新的伤害事件把它自己再计一次；</li>
 *   <li>死亡仍会走 {@code EntityDeathEvent}（清场/亡语逻辑照常）。</li>
 * </ul>
 * 清抗性仍然保留：观感上「护盾破碎」，也避免同一 tick 内其它伤害继续被减免。
 */
public final class VoidAffix implements Affix {

    /** 触发死亡所需的受击次数（用户需求：10 次）。 */
    public static final int HITS_TO_DIE = 10;

    /** 抗性提升 V（药水等级 = amplifier + 1）。 */
    static final int RESISTANCE_AMPLIFIER = 4;

    /** 龙息环持续时间（Maggoteers 用的是 600 tick；虚无正常活不过 30 秒）。 */
    static final int RING_TICKS = 600;

    private static final String HIT_COUNTER = "void_hits";
    private static final String RING_UNTIL = "void_ring_until";
    private static final String DEATH_ONCE = "void_death_once";

    @Override
    public String id() {
        return "void";
    }

    @Override
    public AffixType type() {
        return AffixType.PASSIVE;
    }

    /**
     * 装配：永久抗性提升 V + 30 秒龙息环。
     *
     * <p>★ 依赖 {@code AffixRegistry.equip} 对**所有类型**都调用 `onEquip`
     * （接口注释本来就写着「其余类型可在这里做初始化」，旧实现只对 STAT 调，已修正）。
     */
    @Override
    public void onEquip(AffixContext ctx) {
        LivingEntity entity = ctx.entity();
        if (entity == null || !entity.isValid()) {
            return;
        }
        entity.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,
                PotionEffect.INFINITE_DURATION, RESISTANCE_AMPLIFIER, false, true));
        ctx.runtime().setBuff(RING_UNTIL, ctx.currentTick() + RING_TICKS);
    }

    /** 龙息环（600 tick 内每 5 tick 画一圈；与 Maggoteers 的 PARTICLE_RING 同观感）。 */
    @Override
    public void onTick(AffixContext ctx) {
        LivingEntity entity = ctx.entity();
        if (entity == null || !entity.isValid() || entity.isDead()) {
            return;
        }
        if (ctx.runtime().hasBuff(RING_UNTIL, ctx.currentTick())) {
            AffixFx.ring(entity.getLocation().add(0, 0.3, 0),
                    Particle.DRAGON_BREATH, 40, 1.5);
        }
    }

    /**
     * 被实体打中时计数（由 {@code AffixRegistry.dispatchDamage} 的 PASSIVE 通道派发）。
     *
     * <p>这个入口只承载 `EntityDamageByEntityEvent`（同阵营免伤之后），
     * 所以不需要再判事件类型；毒雾/摔落/火焰不会走到这里。
     */
    @Override
    public void onTrigger(AffixContext ctx) {
        LivingEntity entity = ctx.entity();
        if (entity == null || !entity.isValid() || entity.isDead()) {
            return;
        }
        long hits = ctx.runtime().addCounter(HIT_COUNTER, 1);
        if (!isLethalHit(hits)) {
            return;
        }
        // 第 10 次：先清抗性（观感 + 避免后续伤害继续减免），再直接死亡。
        // 用一次性标记保证同一 tick 内重复派发也只死一次。
        if (!ctx.runtime().useOnceIfAvailable(DEATH_ONCE)) {
            return;
        }
        entity.removePotionEffect(PotionEffectType.RESISTANCE);
        AffixFx.sound(entity.getLocation(), "ENTITY_ENDERMAN_HURT", 1.2f, 0.6f);
        entity.setHealth(0);
    }

    @Override
    public void onUnequip(AffixContext ctx) {
        LivingEntity entity = ctx.entity();
        if (entity == null || !entity.isValid()) {
            return;
        }
        // 实体即将被清场；仍然清掉自己挂的抗性，避免「先 unequip 再复用实体」时残留。
        entity.removePotionEffect(PotionEffectType.RESISTANCE);
    }

    /** 纯函数：第 {@code hits} 次受击是否达到死亡线（供离线单测）。 */
    public static boolean isLethalHit(long hits) {
        return hits >= HITS_TO_DIE;
    }

    /** 当前已计数的受击次数（调试/测试用）。 */
    public static long hitsOf(AffixContext ctx) {
        return ctx == null || ctx.runtime() == null ? 0L : ctx.runtime().counter(HIT_COUNTER);
    }
}
