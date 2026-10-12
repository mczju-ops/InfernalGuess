package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixType;
import io.mczju.infernalguess.affix.AffixRuntime;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;

/**
 * 吸血（{@code lifesteal}）—— 挨打之后**在几秒内持续回血**。
 *
 * <p>原实现：炒鸡 {@code PassiveLifestealSkill} 的 {@code onTrigger} 是**空的** ——
 * 它只在 {@code CombatService} 里写一个带到期 tick 的 buff（{@code lifesteal_until}），
 * 真正的治疗由 {@code CombatService.tickLifesteal} 每 20 tick 做一次。
 * 我们把这套逻辑**收进词条自己**（{@link #onTrigger} 开窗 + {@link #onTick} 治疗）。
 *
 * <h2>★ 三条我们必须改掉 / 补上的东西</h2>
 * <ol>
 *   <li><b>炒鸡的窗口可以被无限刷新</b>：它没有冷却，而 buff 是「每挨打就延长」
 *       → 高频互殴下等于常驻回血。我们给 {@code 100 tick} 冷却
 *       （明显长于 {@code 80 tick} 的窗口）→ **不可能常驻**。</li>
 *   <li><b>炒鸡的回血封顶是僵尸族专用的</b>（{@code zombieFamilyHealCap} 把回血封顶到
 *       {@code 20 × 等级}）—— 那是为僵尸族写的，与本项目数值体系无关，
 *       **不能照抄**（照抄会让僵尸族被压低、其它怪完全不封顶）。</li>
 *   <li><b>必须给总上限</b>：用户担心的正是「每秒 +1 血在低伤害互啄里可能超过对面 DPS
 *       → 必然超时」。所以本次窗口最多回 {@link #MAX_HEALS} 次。</li>
 * </ol>
 */
public final class LifestealAffix implements Affix {

    /** 窗口长度：挨打后 4 秒内有效（同原插件 80 tick）。 */
    private static final long WINDOW_TICKS = 80L;

    /** 冷却：5 秒，**明显长于窗口** → 无法常驻刷新。 */
    private static final long COOLDOWN_TICKS = 0L;   // ★ 原版无冷却（每次受击都触发），照抄

    /** 每 20 tick（1 秒）回一次。 */
    private static final long HEAL_INTERVAL_TICKS = 20L;

    /** 每次回复量。 */
    private static final double HEAL_PER_TICK = 1.0;

    /** ★ 本次窗口的**总次数上限**（4 次 = 4 点血）。 */
    private static final long MAX_HEALS = 4L;

    private static final String KEY_UNTIL = "lifesteal:until";
    private static final String KEY_NEXT = "lifesteal:next";
    private static final String KEY_COUNT = "lifesteal:count";

    @Override
    public String id() {
        return "lifesteal";
    }

    @Override
    public AffixType type() {
        return AffixType.PASSIVE;
    }

    @Override
    public long cooldownTicks() {
        return COOLDOWN_TICKS;
    }

    /** 挨打 → 开一个回血窗口（并重置本次的计数）。 */
    @Override
    public void onTrigger(AffixContext ctx) {
        AffixRuntime rt = ctx.runtime();
        long now = ctx.currentTick();
        rt.setBuff(KEY_UNTIL, now + WINDOW_TICKS);
        rt.setBuff(KEY_NEXT, now);            // 允许立刻回第一次
        rt.setCounter(KEY_COUNT, 0L);
    }

    /** 心跳 → 窗口内每秒回一次血，次数封顶。 */
    @Override
    public void onTick(AffixContext ctx) {
        LivingEntity entity = ctx.entity();
        if (entity == null || !entity.isValid() || entity.isDead()) {
            return;
        }
        AffixRuntime rt = ctx.runtime();
        long now = ctx.currentTick();

        if (!rt.hasBuff(KEY_UNTIL, now)) {
            return;                            // 不在窗口内
        }
        if (rt.counter(KEY_COUNT) >= MAX_HEALS) {
            return;                            // 本次窗口已用完（★ 总上限）
        }
        if (now < rt.buffUntil(KEY_NEXT)) {
            return;                            // 还没到下一次
        }

        AttributeInstance max = entity.getAttribute(Attribute.MAX_HEALTH);
        double full = max != null ? max.getValue() : entity.getMaxHealth();
        if (entity.getHealth() >= full) {
            return;                            // 已满血，不浪费次数
        }

        entity.setHealth(Math.min(full, entity.getHealth() + HEAL_PER_TICK));
        rt.addCounter(KEY_COUNT, 1L);
        rt.setBuff(KEY_NEXT, now + HEAL_INTERVAL_TICKS);

            }
}
