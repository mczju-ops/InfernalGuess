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
 * 塑料（{@code plastic}）—— **开打后获得 20 秒抗性提升 V**，并带一圈**金色环状粒子**。
 *
 * <p>★ 这是**用户自己新增的词条，不是炒鸡来源**（2026-10-12）：
 * 「生成时获得 20s 抗性提升 V，并带有金色环状粒子效果」。
 *
 * <p>★ **2026-10-13 修正计时起点**：用户实测发现「抗性倒计时在下注前就开始计算了……
 * 等开打就会变成白板」—— 因为词条是在**生成时**装配的，而下注/准备阶段怪没有 AI、挨不到打。
 * 现在改为「**开打后的第一次心跳**才开始 20 秒计时」（用户要求：开打后 20 秒才消失），
 * 实现见 {@link #onEquip} / {@link #onTick}。
 *
 * <h2>抗性 V 到底是多少（26.2 字节码实测，别凭印象）</h2>
 * {@code LivingEntity#lambda$handleEntityDamage$5} 里 {@code f = (amplifier + 1) * 5}，
 * 再经 {@code CombatRules.getDamageAfterMagicAbsorb} 做 {@code clamp(f, 0, 20)}、
 * 伤害 {@code *= (1 - f/25)}：
 *
 * <pre>
 *   抗性 I  = 20%   II = 40%   III = 60%（bullwark 的等级）   IV = 80%   V = 80%（封顶，与 IV 相同）
 * </pre>
 *
 * 也就是说本词条的效果是「**前 20 秒只吃 20% 伤害**」。
 *
 * <h2>与 {@code bullwark} 的关系：原版已会自动恢复，我们再加一层显式兜底</h2>
 * 【javap 实测 26.2】药水同类型相遇时走 {@code MobEffectInstance.update(other)}：
 *
 * <pre>
 *   other.amplifier &gt; this.amplifier  → 替换（等级与时长都取新的）
 *   else 若新的更久：同级 → 只延长时长；等级不同 → 把新的塞进 {@code hiddenEffect}
 *   主效果 duration 归零时 → {@code downgradeToHiddenEffect()} 让 hiddenEffect **自动接管**
 * </pre>
 *
 * ⇒ 所以「`plastic` 的 V 把 bullwark 的永久 III 顶掉、20 秒后就没抗性了」这种担心**不成立**：
 * 两种配置顺序下，III（无限）都会作为 hiddenEffect 存着，等 V 到期自动回来。
 *
 * <p>尽管如此，本类仍在窗口结束时**显式补回** bullwark 的永久 III（用户 2026-10-12 要求「补回那五行」）：
 * <ul>
 *   <li>把终态**写死**，不依赖原版的 hiddenEffect 链（将来版本改动 / 被别的插件清过药水都不怕）；</li>
 *   <li>顺序无关：**先 {@code removePotionEffect} 清掉自己这管 V，再挂回 III** ——
 *       若不清，遇到「场上正好还是 V」的时序时补 III 是无效的（等级更低）。</li>
 * </ul>
 * 每只怪只补一次（{@code AffixRuntime.useOnceIfAvailable}），且只有确实装配了 bullwark 才补。
 *
 * <h2>视觉</h2>
 * 窗口内（**开打后** 20 秒）由 {@code onTick} 每 5 tick 画一圈金色水平环
 * （{@code Particle.WAX_ON} —— 用户指定；原版金色火花粒子，不需要资源包）。
 * 窗口用 {@code AffixRuntime} 的 buff 记录（绝对 tick），而不是"看它有没有抗性"——
 * 后者会被 {@code bullwark} 的永久抗性误判成"一直在窗口内"。
 */
public final class PlasticAffix implements Affix {

    /** 20 秒（用户指定）。 */
    private static final int DURATION_TICKS = 400;
    /** 抗性提升 V（药水等级 = amplifier + 1）。 */
    private static final int AMPLIFIER = 4;

    /** 运行时状态键：本词条的效果窗口（绝对 tick）。 */
    private static final String WINDOW_KEY = "plastic_until";
    /** 运行时状态键：是否已经补回过 bullwark（每只怪只补一次）。 */
    private static final String RESTORED_KEY = "plastic_restored";

    /** bullwark 的抗性等级（2 = 抗性提升 III）—— 与 {@code BullwarkAffix.AMPLIFIER} 保持一致。 */
    private static final int BULLWARK_AMPLIFIER = 2;

    /** 金色环用的粒子（用户 2026-10-12 指定 {@code WAX_ON}：金色火花，无需数据参数）。 */
    private static final Particle RING_PARTICLE = Particle.WAX_ON;
    /** 环的采样点数与半径。 */
    private static final int RING_POINTS = 20;
    private static final double RING_RADIUS = 0.85;

    /** 运行时状态键：是否已经开打（**开打那一刻才开始计时**）。 */
    private static final String STARTED_KEY = "plastic_started";

    @Override
    public String id() {
        return "plastic";
    }

    @Override
    public AffixType type() {
        return AffixType.STAT;
    }

    /**
     * ★ **不在生成时就给抗性**（2026-10-13 用户反馈 + 修复）。
     *
     * <p>原来这里立刻挂 20 秒抗性提升 V，而词条是在**生成时**装配的 ——
     * 那会儿还在下注/准备阶段，怪**没有 AI、也挨不到打**，20 秒窗口就这么白白流走了，
     * 等真正开打时这个怪已经变成**白板**（用户原话：「在下注前就开始计算了……等开打就会变成白板」）。
     *
     * <p>现在改成「**开打后的第一次心跳**才开始计时」，见 {@link #onTick}：
     * 词条心跳只在对局真正开打后派发（{@code BattleService.tick()} 里
     * {@code if (!fighting) return;} 之后才调 {@code tickAffixHeartbeat()}），
     * 所以这里什么都不做 = 等开打。用户要求的效果：**开打后 20 秒才消失**。
     */
    @Override
    public void onEquip(AffixContext ctx) {
        // 刻意留空：抗性在 onTick 里、开打那一刻才挂上（见方法注释）。
    }

    @Override
    public void onTick(AffixContext ctx) {
        LivingEntity entity = ctx.entity();
        if (entity == null || !entity.isValid() || entity.isDead()) {
            return;
        }
        long now = ctx.currentTick();
        // ★ 开打后的第一次心跳：**现在**才开始 20 秒计时（每只怪只开一次窗口）
        if (ctx.runtime().useOnceIfAvailable(STARTED_KEY)) {
            entity.addPotionEffect(new PotionEffect(
                    PotionEffectType.RESISTANCE, DURATION_TICKS, AMPLIFIER, false, true, true));
            // 记下窗口：视觉只在这个区间内画环（不能用「有没有抗性」判断 —— bullwark 是永久的）
            ctx.runtime().setBuff(WINDOW_KEY, now + DURATION_TICKS);
        }
        if (ctx.runtime().hasBuff(WINDOW_KEY, now)) {
            AffixFx.ring(entity.getLocation().add(0, 0.3, 0), RING_PARTICLE, RING_POINTS, RING_RADIUS);
            return;
        }
        restoreBullwark(ctx, entity);
    }

    /**
     * 窗口结束后把被顶掉的 {@code bullwark} **永久抗性 III** 补回来（每只怪只补一次）。
     *
     * <p>★ 「先清后挂」是刻意的：若配置把本词条写在 bullwark **前面**，
     * 原版会把 V 的时长延长成无限（保留更高等级）⇒ 场上会出现**永久抗性 V**；
     * 直接补 III 是无效的（等级更低、时长相同，原版什么都不做）。
     * 先 {@code removePotionEffect} 清掉那管 V，再挂 III，两种配置顺序都能回到「bullwark 的原样」。
     */
    private static void restoreBullwark(AffixContext ctx, LivingEntity entity) {
        if (!ctx.plugin().affixRegistry().has(entity, "bullwark")) {
            return;   // 没装 bullwark ⇒ 没有需要补的东西
        }
        if (!ctx.runtime().useOnceIfAvailable(RESTORED_KEY)) {
            return;   // 已经补过了
        }
        entity.removePotionEffect(PotionEffectType.RESISTANCE);
        entity.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,
                PotionEffect.INFINITE_DURATION, BULLWARK_AMPLIFIER, false, true));
    }
}
