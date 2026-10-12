package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

/**
 * 投掷（{@code tosser}）—— 周期性地**把目标拉向自己**。
 *
 * <p>原实现：炒鸡 {@code RangeTosserSkill}（{@code SkillType.RANGE}），
 * {@code range 6} / {@code chance 0.3} / 冷却 100 tick /
 * {@code force 4} / {@code upward 0.2}，前置一大堆玩家专属判定
 * （非创造/旁观、非潜行、位移免疫、快捷栏护符）。
 *
 * <h2>改造要点</h2>
 * <ol>
 *   <li>目标 {@code Player} → {@code LivingEntity}；
 *       语义从「把玩家拽到怪面前」变成「**把敌方怪拽到自己面前**」——
 *       对近战怪是正收益，也符合「投掷」的字面观感</li>
 *   <li>★ 去掉**全部**玩家专属前置（创造/旁观模式、潜行、位移免疫、护符）——
 *       它们对怪没有意义</li>
 *   <li>★ 力度照抄原插件 yml 里的 {@code force 4}（代码兜底常量是 1.2，
 *       但实际配置覆盖成 4）—— 我一度擅自改成 1.2（怕「像瞬移」），
 *       按「数值必须与原版一致」的裁定已回滚为 4</li>
 * </ol>
 *
 * <p>⚠️ 已知隐患（源码层面无法排除）：怪物 AI 的移动控制在**同一个 tick** 里
 * 可能覆盖 {@code setVelocity}，表现为「效果时有时无」。
 * 需要实机观察；若确实无效，退路是短时 {@code setAI(false)} 或用小段传送代替。
 */
public final class TosserAffix implements Affix {

    /** 照抄原版 yml 的 force 4。 */
    private static final double FORCE = 4.0;
    private static final double UPWARD = 0.2;
    private static final long COOLDOWN_TICKS = 100L;

    @Override
    public String id() {
        return "tosser";
    }

    @Override
    public AffixType type() {
        return AffixType.RANGE;
    }

    @Override
    public long cooldownTicks() {
        return COOLDOWN_TICKS;
    }

    /** ★ 原版 {@code skills.tosser.chance} 的值，照抄。 */
    @Override
    public double chance() {
        return 0.3;
    }

    @Override
    public void onTrigger(AffixContext ctx) {
        LivingEntity holder = ctx.entity();
        if (holder == null || !holder.isValid() || !ctx.hasValidTarget()) {
            return;
        }
        LivingEntity target = ctx.target();
        Location holderLoc = holder.getLocation();
        Vector pull = holderLoc.toVector().subtract(target.getLocation().toVector());
        pull.setY(0);
        if (pull.lengthSquared() < 0.01) {
            return;   // 已经贴在一起，没什么可拉的
        }
        pull.normalize();
        target.setVelocity(pull.multiply(FORCE).setY(UPWARD));

        // 只保留原版的音效（我一度自己加了一条 CRIT 连线粒子，已按裁定移除）
        AffixFx.sound(target.getLocation(), "ENTITY_BREEZE_JUMP", 1.0f, 0.8f);
    }
}
