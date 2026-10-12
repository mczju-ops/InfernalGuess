package io.mczju.infernalguess.affix;

/**
 * 一个词条（= 给怪物加的特殊能力）。
 *
 * <h2>★ 设计约定：**每个词条自包含**</h2>
 * 这是刻意与炒鸡相反的一条。炒鸡把技能逻辑劈成两半 ——
 * `1up` 的判定整段写在 {@code CombatService.onMobDamaged} 里、`dye` 写在
 * {@code CombatService:479}、`ghastly`/`necromancer` 的共享冷却写在 {@code tickRangeSkills} ——
 * 全部靠 <b>按 skillId 字符串硬编码</b>。后果是：**加一个词条要改 service**。
 *
 * <p>我方的约定是：
 * <ul>
 *   <li>触发条件、冷却、数值、粒子、音效 —— **全在这个词条自己的实现里**</li>
 *   <li>{@code AffixRegistry} 只负责「注册 id → 拿实现」与**按类型派发**，
 *       <b>不认任何具体 id</b></li>
 * </ul>
 *
 * <h2>冷却为什么是接口方法</h2>
 * 炒鸡的 PASSIVE 冷却默认值是 **0**，而 `lifesteal` / `rust` / `vengeance` 的配置里
 * 都没写这一项 → **每次被命中都会触发**（`rust` 会高频打飞对面武器）。
 * 我们把它变成**必须显式声明**的 {@link #cooldownTicks()}，从接口层面杜绝这个坑。
 */
public interface Affix {

    /** 词条 id（小写，与 {@code mob-groups.yml} 里写的一致）。 */
    String id();

    /** 派发类型。 */
    AffixType type();

    /**
     * 同一词条两次触发之间的最小间隔（tick）。
     *
     * <p>★ **数值必须与原版炒鸡一致**（见 {@code docs/13} §11 的用户裁定）：
     * 「主要受众是玩原版炒鸡 PvE 的玩家，对这些数值很熟悉，数值变了会影响他们的判断」。
     * 所以这里填的是**原版 {@code config.yml} 里 {@code cooldown-ticks} 的默认值**，
     * 不要「顺手优化」。
     *
     * <p>{@code 0} = 不限流（原版多数 PASSIVE 就是 0，即每次受击都触发）。
     */
    default long cooldownTicks() {
        return 0L;
    }

    /**
     * 每次满足触发条件时的**实际触发概率**。
     *
     * <p>★ 这条是照着原版的 {@code skills.<id>.chance} 补的 ——
     * 我第一版**漏掉了整个概率机制**，等于把所有词条的触发率都擅自改成了 100%，
     * 这是比单条数值更严重的保真缺口（见 {@code docs/13} §11.5）。
     *
     * <p>默认 {@code 1.0}（原版没有配 {@code chance} 的条目即无条件触发）。
     * 派发顺序与原版一致：**先判冷却、再掷概率**；概率没过也照样消耗冷却
     * （原版 {@code triggerActiveSkills} 就是先 {@code setCooldown} 再执行，
     * 概率判定在技能内部）。
     */
    default double chance() {
        return 1.0;
    }

    /**
     * 装配（怪物生成后调用一次）。
     *
     * <p>{@code STAT} 类在这里改实体/属性；其余类型可在这里做初始化。
     * 与目标无关，所以 {@code ctx.target()} 此时为 {@code null}。
     */
    default void onEquip(AffixContext ctx) {
    }

    /** 卸下（清场时调用）。用于回收持续效果、恢复被改过的属性。 */
    default void onUnequip(AffixContext ctx) {
    }

    /**
     * 触发。
     *
     * <p>{@code STAT} / {@code WARD} 类通常不实现它；其余类型按 {@link #type()} 在对应时机被调用。
     * **必须自行判空**：{@code ctx.target()} 可能为 null（如亡语时没有击杀者），
     * 建议直接用 {@link AffixContext#hasValidTarget()}。
     */
    default void onTrigger(AffixContext ctx) {
    }

    /**
     * **伤害拦截**（仅 {@link AffixType#WARD} 会被调用）：在任何伤害生效前介入。
     *
     * <p>与 {@link #onTrigger} 的区别：这个发生在**扣血之前**，所以可以取消伤害。
     * 由 {@code EntityDamageEvent} 派发 —— **含环境伤害**（摔落、火焰、凋零），
     * 这正是 {@code 1up} 需要的（炒鸡也不看伤害来源）。
     *
     * @param event 伤害事件；调用方可 {@code setCancelled(true)} 拦下这次伤害
     * @return true = 本次已处理（调用方会停止继续派发其它 WARD 词条）
     */
    default boolean onIncomingDamage(AffixContext ctx, org.bukkit.event.entity.EntityDamageEvent event) {
        return false;
    }

    /**
     * **周期性心跳**（每 5 tick，对所有类型都会被调用）。
     *
     * <p>用途是「持续效果」，例如 {@code lifesteal} 的**按秒回血** ——
     * 炒鸡的做法完全一样：挨打时只写一个带到期 tick 的 buff，
     * 真正的治疗在 {@code CombatService.tickLifesteal} 里做。
     *
     * <p>不需要周期行为的词条不用实现它（默认空实现）。
     */
    default void onTick(AffixContext ctx) {
    }
}
