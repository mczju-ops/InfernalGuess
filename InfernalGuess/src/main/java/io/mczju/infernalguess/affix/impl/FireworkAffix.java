package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixRegistry;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.meta.FireworkMeta;

/**
 * 烟花（{@code firework}）—— 在目标位置**放一朵烟花并引爆**。
 *
 * <p>原实现：炒鸡 {@code ActiveFireworkSkill}（类名带 Active，但实际 {@code SkillType.DUAL}），
 * 在目标位置生成红色球状烟花、1 tick 后引爆，靠爆炸伤害。
 *
 * <h2>改造要点</h2>
 * <ol>
 *   <li>目标 {@code Player} → {@code LivingEntity}，只需它的坐标</li>
 *   <li>★ **必须打来源标记**：烟花爆炸的 {@code damager} 是**烟花实体本身**
 *       （既不是 {@code LivingEntity}，也不是有 shooter 的投射物），
 *       不打标记的话会：① 同阵营免伤拦不住 ② 炸到队友 ③ 死亡无法归因。
 *       标记后由 {@code resolveAttacker} 回溯出施法者。</li>
 * </ol>
 */
public final class FireworkAffix implements Affix {

    /** 冷却 20 tick，照抄原版。 */
    private static final long COOLDOWN_TICKS = 20L;
    private static final int POWER = 1;

    @Override
    public String id() {
        return "firework";
    }

    @Override
    public AffixType type() {
        return AffixType.DUAL;
    }

    @Override
    public long cooldownTicks() {
        return COOLDOWN_TICKS;
    }

    /** ★ 原版 {@code skills.firework.chance} 的值，照抄。 */
    @Override
    public double chance() {
        return 0.5;
    }

    @Override
    public void onTrigger(AffixContext ctx) {
        LivingEntity holder = ctx.entity();
        if (holder == null || !holder.isValid() || !ctx.hasValidTarget()) {
            return;
        }
        Location at = ctx.target().getLocation();
        Firework fw = at.getWorld().spawn(at, Firework.class);
        FireworkMeta meta = fw.getFireworkMeta();
        meta.setPower(POWER);
        meta.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL)
                .withColor(Color.RED)
                .withTrail()
                .build());
        fw.setFireworkMeta(meta);
        // ★ 爆炸前打标记 —— 爆炸伤害的 damager 是烟花实体本身
        AffixRegistry.markSource(fw, holder);
        fw.detonate();
        // 音效不另加：烟花自身爆炸就有原版音效，原插件也没有额外 playSound
    }
}
