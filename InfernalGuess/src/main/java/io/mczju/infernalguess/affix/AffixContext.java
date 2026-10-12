package io.mczju.infernalguess.affix;

import org.bukkit.event.Event;

/**
 * 词条执行上下文 —— 对标炒鸡的 {@code SkillContext}，但**目标类型放宽为 {@code LivingEntity}**。
 *
 * <h2>与炒鸡的唯一实质差别</h2>
 * 炒鸡：{@code private Player targetPlayer}
 * （只有 {@code getTargetPlayer()}，**没有 LivingEntity 版本**）→ 怪打怪时目标永远是 null。
 *
 * <p>我们：{@link #target()} 返回 {@link org.bukkit.entity.LivingEntity}。
 * 这样 {@code ACTIVE / PASSIVE / DUAL} 三类**不需要改任何触发逻辑**，
 * 只要把「取目标」从玩家换成实体即可（见 {@code docs/13} §2.3.1 的逐入口核对）。
 *
 * <h2>目标从哪来</h2>
 * <ul>
 *   <li>{@code ACTIVE}：伤害事件的**受害者**</li>
 *   <li>{@code PASSIVE}：伤害事件的**攻击者**（投射物要回溯 {@code getShooter()}）</li>
 *   <li>{@code DEATH}：击杀者（可能为 null —— 怪物击杀时 Bukkit 的 {@code getKiller()} 就是 null）</li>
 *   <li>{@code RANGE}：{@code entity.getTarget()}（★ RANGE 没有事件，只能取当前索敌目标）</li>
 * </ul>
 */
public final class AffixContext {

    private final io.mczju.infernalguess.InfernalGuessPlugin plugin;
    private final org.bukkit.entity.LivingEntity entity;
    private final AffixRuntime runtime;
    private final long currentTick;

    private org.bukkit.entity.LivingEntity target;
    private Event triggerEvent;

    public AffixContext(io.mczju.infernalguess.InfernalGuessPlugin plugin,
                        org.bukkit.entity.LivingEntity entity,
                        AffixRuntime runtime, long currentTick) {
        this.plugin = plugin;
        this.entity = entity;
        this.runtime = runtime;
        this.currentTick = currentTick;
    }

    public io.mczju.infernalguess.InfernalGuessPlugin plugin() {
        return plugin;
    }

    /** **技能的持有者**（身上带词条的那只怪）。 */
    public org.bukkit.entity.LivingEntity entity() {
        return entity;
    }

    /**
     * **技能的目标**（对面阵营的那只怪）。
     *
     * <p>可能为 {@code null}（例如亡语时没有击杀者）—— 每个词条实现**必须自行判空**。
     * 提供 {@link #hasValidTarget()} 做统一判定。
     */
    public org.bukkit.entity.LivingEntity target() {
        return target;
    }

    public void setTarget(org.bukkit.entity.LivingEntity target) {
        this.target = target;
    }

    /** 目标存在、未死亡、且在同一个世界里 —— 可直接使用。 */
    public boolean hasValidTarget() {
        return target != null && target.isValid() && !target.isDead()
                && entity != null && entity.isValid()
                && target.getWorld().equals(entity.getWorld());
    }

    public Event triggerEvent() {
        return triggerEvent;
    }

    public void setTriggerEvent(Event triggerEvent) {
        this.triggerEvent = triggerEvent;
    }

    /** 当前绝对 tick（{@code Bukkit.getCurrentTick()}）。冷却与 buff 都用这个时间轴。 */
    public long currentTick() {
        return currentTick;
    }

    public AffixRuntime runtime() {
        return runtime;
    }
}
