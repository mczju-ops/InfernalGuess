package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixType;
import io.mczju.infernalguess.battle.BattleService;
import io.mczju.infernalguess.battle.BattleSide;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

/**
 * 母体+（{@code mama+}）—— `mama` 的**升级形态**：一次召唤 **4 只**幼体。
 *
 * <h2>★ 为什么单独立成一个词条（用户裁定）</h2>
 * 原版炒鸡的 `mama` 是按**档位**决定召唤数量的（1~4 只），
 * 而本项目没有等级体系 → 用户把升级形态拆成独立词条：
 * <ul>
 *   <li>{@code mama} = 一次召唤 **2 只**幼体</li>
 *   <li>{@code mama+} = 一次召唤 **4 只**幼体</li>
 * </ul>
 * 两条都保留原版的真实档位语义，权重可分别定价。
 *
 * <h2>与 `mama` 完全一致的三条语义</h2>
 * <ol>
 *   <li>★ **每只怪仅触发一次**（用户明确要求）—— {@code useOnceIfAvailable} 原子保证</li>
 *   <li>★ **幼体必须收养**，且 **{@code counted = false}（不计入存活判定）**
 *       —— 补上炒鸡 {@code SpawnReason.CUSTOM} 那条盲区，
 *       否则幼体会变成无阵营、打自己人、跨回合残留</li>
 *   <li>幼体**不继承词条**，天然杜绝链式繁殖</li>
 * </ol>
 */
public final class MamaPlusAffix implements Affix {

    private static final int SPAWN_COUNT = 4;
    private static final long COOLDOWN_TICKS = 200L;   // 照抄原版（配合「只触发一次」双保险）

    @Override
    public String id() {
        return "mama+";
    }

    @Override
    public AffixType type() {
        return AffixType.PASSIVE;
    }

    @Override
    public long cooldownTicks() {
        return COOLDOWN_TICKS;
    }

    /** ★ 原版 skills.mama.chance 的值，照抄（升级形态沿用同一概率）。 */
    @Override
    public double chance() {
        return 0.15;
    }

    @Override
    public void onTrigger(AffixContext ctx) {
        LivingEntity parent = ctx.entity();
        if (parent == null || !parent.isValid() || parent.isDead()) {
            return;
        }
        // ★ 用户要求：每只怪仅触发一次
        if (!ctx.runtime().useOnceIfAvailable(id())) {
            return;
        }
        BattleService battle = BattleService.forEntity(parent.getUniqueId()).orElse(null);
        BattleSide side = BattleService.sideOf(parent).orElse(null);

        Location base = parent.getLocation();
        for (int i = 0; i < SPAWN_COUNT; i++) {
            // 四个角散开，避免叠在同一格
            double ox = (i % 2 == 0 ? 0.8 : -0.8);
            double oz = (i < 2 ? 0.8 : -0.8);
            LivingEntity child;
            try {
                child = base.getWorld().spawn(base.clone().add(ox, 0, oz),
                        parent.getType().getEntityClass().asSubclass(LivingEntity.class));
            } catch (Throwable t) {
                continue;
            }
            if (child == null) {
                continue;
            }
            if (child instanceof Ageable ageable) {
                ageable.setBaby();
            }
            if (child instanceof Mob mob) {
                mob.setCanPickupItems(false);
            }
            if (battle != null && side != null) {
                battle.adoptDerived(child, side, "MAMA+");
            }
        }
        AffixFx.ring(base.clone().add(0, 0.3, 0), Particle.ITEM_SLIME, 14, 0.8);
        AffixFx.sound(base, "ENTITY_ZOMBIE_INFECT", 0.8f, 1.2f);
    }
}
