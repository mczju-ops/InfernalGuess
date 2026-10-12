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
 * 母体（{@code mama}）—— 挨打时**生出几只同类幼体**。
 *
 * <p>原实现：炒鸡 {@code PassiveMamaSkill}，按档位生 1~4 只同类幼体，
 * 并给幼体**重新随机一套词条**（显式排除 {@code mama}，所以不会链式繁殖）。
 *
 * <h2>★ 依用户裁定的两处刻意改动</h2>
 * <ol>
 *   <li><b>每只怪仅触发一次</b>（用户明确要求）—— 用 {@code useOnceIfAvailable} 原子保证。
 *       炒鸡靠 {@code cooldown-ticks: 200} 限流，理论上仍可反复生；我们直接封成一次。</li>
 *   <li><b>幼体必须收养</b> —— 炒鸡用 {@code world.spawnEntity} 生出来的实体
 *       {@code SpawnReason} 是 {@code CUSTOM}，而我们的 {@code DERIVED_REASONS}
 *       里没有它 → **不会被收养** → 无阵营、打自己人、跨回合残留。
 *       这里显式走 {@link BattleService#adoptDerived}，与「唤魔者的恼鬼」完全一致
 *       （{@code counted = false}：**参战但不计入存活判定**）。</li>
 * </ol>
 *
 * <p>幼体**不继承词条**（与炒鸡一致）—— 少一层不可控的随机性，也天然杜绝递归。
 */
public final class MamaAffix implements Affix {

    private static final int SPAWN_COUNT = 2;
    private static final long COOLDOWN_TICKS = 200L;   // 与炒鸡一致（配合「只触发一次」双保险）

    @Override
    public String id() {
        return "mama";
    }

    @Override
    public AffixType type() {
        return AffixType.PASSIVE;
    }

    @Override
    public long cooldownTicks() {
        return COOLDOWN_TICKS;
    }

    /** ★ 原版 skills.Mama  .chance 的值，照抄。 */
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
            Location at = base.clone().add((i % 2 == 0 ? 0.8 : -0.8), 0, (i < 2 ? 0.8 : -0.8));
            LivingEntity child;
            try {
                child = base.getWorld().spawn(at, parent.getType().getEntityClass().asSubclass(
                        LivingEntity.class));
            } catch (Throwable t) {
                continue;   // 该类型不可直接生成 → 跳过这一只
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
            // ★ 收养（非计数单位）—— 补上 SpawnReason.CUSTOM 那条盲区
            if (battle != null && side != null) {
                battle.adoptDerived(child, side, "MAMA");
            }
        }
                AffixFx.sound(base, "ENTITY_ZOMBIE_INFECT", 0.8f, 1.2f);
    }
}
