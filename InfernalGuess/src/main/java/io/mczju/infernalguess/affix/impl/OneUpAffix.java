package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * 复生（{@code 1up}）—— 濒死时**回满血，每只怪一生只有一次**。
 *
 * <p>原实现：炒鸡 {@code Stat1upSkill}，但**判定逻辑整段写在
 * {@code CombatService.onMobDamaged}（269-298）里**，技能类只剩一个 {@code trigger()}。
 * 这是我们刻意不学的那种「按 skillId 硬编码在 service」的坏设计 ——
 * 本类把判定与效果**都收在自己身上**。
 *
 * <h2>★ 必须保留原插件的三条语义</h2>
 * <ol>
 *   <li><b>不看伤害来源</b> —— 近战/投射物/摔落/火焰/凋零都算
 *       （所以挂在 {@code EntityDamageEvent} 上）</li>
 *   <li><b>★「致命一击不拦」</b>：{@code healthAfter <= 0} 时**放行**。
 *       这是保证对局**一定能结束**的关键 —— 否则会出现永远打不死的怪。</li>
 *   <li><b>只触发一次</b> —— 由 {@code AffixRuntime.useOnceIfAvailable} 保证原子性。
 *       若不实现次数上限，每 tick 回满血会**无限拖局**。</li>
 * </ol>
 *
 * <p>阈值方面：炒鸡默认 `hp-threshold: 8`，并且**只在「伤害来源是穿了魔法王胸甲的玩家」时
 * 才把阈值减半**（{@code CombatService:281-285}）→ 怪打怪下恒为完整阈值。
 * 我们沿用 8，并把「最多回满到最大生命」写死（炒鸡还额外套了个僵尸族回血上限
 * {@code zombieFamilyHealCap}，那是为僵尸族把回血封顶到 {@code 20 × 等级} 写的，
 * 与本项目的数值体系无关，**不照抄**）。
 */
public final class OneUpAffix implements Affix {

    /** 触发阈值：这一击之后血量会掉到 ≤ 该值时触发。 */
    private static final double HP_THRESHOLD = 8.0;

    @Override
    public String id() {
        return "1up";
    }

    @Override
    public AffixType type() {
        return AffixType.WARD;
    }

    @Override
    public boolean onIncomingDamage(AffixContext ctx, EntityDamageEvent event) {
        LivingEntity entity = ctx.entity();
        if (entity == null || !entity.isValid() || entity.isDead()) {
            return false;
        }
        boolean dbg = false;
        try {
            dbg = ctx.plugin() != null && ctx.plugin().gameConfig() != null
                    && ctx.plugin().gameConfig().debugEnabled();
        } catch (Throwable ignored) {
            // 配置未就绪 → 不记日志
        }
        // ① 阈值判定：伤害生效后还在阈值之上 → 不触发
        double healthAfter = entity.getHealth() - event.getFinalDamage();
        if (healthAfter > HP_THRESHOLD) {
            if (dbg) {
                log(entity, "未到阈值（healthAfter=" + fmt(healthAfter)
                        + " > " + HP_THRESHOLD + "）→ 放行");
            }
            return false;
        }
        // ② ★ 致命一击不拦 —— 放行，让它死。这是「对局一定能结束」的保证。
        if (healthAfter <= 0) {
            if (dbg) {
                log(entity, "★ 致命一击不拦（healthAfter=" + fmt(healthAfter)
                        + "）→ 放行，这是「对局一定能结束」的保证");
            }
            return false;
        }
        // ③ 一次性（原子占用；失败说明这辈子已经用过了）
        if (!ctx.runtime().useOnceIfAvailable(id())) {
            if (dbg) {
                log(entity, "已用过一次（本局不再触发）→ 放行");
            }
            return false;
        }

        // ④ 取消这次伤害并回满
        event.setCancelled(true);
        AttributeInstance max = entity.getAttribute(Attribute.MAX_HEALTH);
        double full = max != null ? max.getValue() : entity.getMaxHealth();
        entity.setHealth(Math.min(full, entity.getMaxHealth()));

        // ⑤ 视觉：心形螺旋 + 酿造台音效（复现原插件）
        AffixFx.spiral(entity.getLocation(), Particle.HEART, 24, 0.6, 2.0);
        AffixFx.sound(entity.getLocation(), "BLOCK_BREWING_STAND_BREW", 1.0f, 1.0f);
        return true;
    }

    /** 调试日志（受 {@code debug.enabled} 控制）—— 让 WARD 路径可观测。 */
    private void log(LivingEntity entity, String msg) {
        try {
            entity.getServer().getLogger().info("[词条] " + entity.getType()
                    + " 1up：" + msg);
        } catch (Throwable ignored) {
            // 记日志失败不该影响效果
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
