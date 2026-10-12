package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixType;
import io.mczju.infernalguess.battle.BattleService;
import io.mczju.infernalguess.battle.BattleSide;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Vex;

/**
 * 唤魔（{@code vexsummoner}）—— 挨打时**召唤恼鬼**。
 *
 * <p>原实现：炒鸡 {@code PassiveVexSummonerSkill}，{@code chance 0.25}、
 * 每次 2 只、附近上限 4 只、冷却 60 tick。
 *
 * <h2>★ 依用户裁定：与「唤魔者召唤的恼鬼」**处理方法完全一致**</h2>
 * 用户原话：「生成的恼鬼与唤魔者生成的恼鬼处理方法一致」。
 * 我方 {@code BattleListener.onSpawn} 收养恼鬼有**两条路**：
 * <ol>
 *   <li>按 {@code Vex#getSummoner()} 精确找到召唤者 → 继承阵营（**最可靠**）</li>
 *   <li>按 {@code SpawnReason} + 就近 16 格兜底</li>
 * </ol>
 * 炒鸡用 {@code world.spawnEntity} 生恼鬼，**两条都不满足**（既没 setSummoner、
 * 生成原因也是 {@code CUSTOM}）→ 恼鬼变成无阵营的乱打单位。
 * 我们**两条都补上**：先 {@code setSummoner}（命中第 1 条），
 * 再显式 {@code adoptDerived} 兜底（保证一定被收养）。
 *
 * <h2>★ 另外补了炒鸡没有的总量上限</h2>
 * 炒鸡的「附近最多 4 只」是**以怪为中心 32³ 立方体内、含敌我与自然生成的恼鬼的瞬时数量**
 * —— 恼鬼是飞行单位会飞出这个范围，于是计数回落、可以继续补，
 * 源码里**没有任何总量上限**。我们用「一次性的召唤次数」+ 本方阵营计数来封顶。
 */
public final class VexSummonerAffix implements Affix {

    private static final int SUMMON_COUNT = 2;
    /** 本局**每只怪**最多召唤几批（每批 2 只）→ 合计 4 只，与炒鸡的「附近上限 4」量级一致。 */
    private static final long MAX_BATCHES = 2L;
    private static final long COOLDOWN_TICKS = 60L;

    private static final String KEY_BATCHES = "vexsummoner:batches";

    @Override
    public String id() {
        return "vexsummoner";
    }

    @Override
    public AffixType type() {
        return AffixType.PASSIVE;
    }

    @Override
    public long cooldownTicks() {
        return COOLDOWN_TICKS;
    }

    /** ★ 原版 skills.VexSummoner  .chance 的值，照抄。 */
    @Override
    public double chance() {
        return 0.25;
    }


    @Override
    public void onTrigger(AffixContext ctx) {
        LivingEntity holder = ctx.entity();
        if (holder == null || !holder.isValid() || holder.isDead()) {
            return;
        }
        // ★ 总量上限（炒鸡没有这道闸）
        if (ctx.runtime().counter(KEY_BATCHES) >= MAX_BATCHES) {
            return;
        }
        ctx.runtime().addCounter(KEY_BATCHES, 1L);

        BattleService battle = BattleService.forEntity(holder.getUniqueId()).orElse(null);
        BattleSide side = BattleService.sideOf(holder).orElse(null);

        Location at = holder.getLocation();
        for (int i = 0; i < SUMMON_COUNT; i++) {
            Vex vex = (Vex) at.getWorld().spawnEntity(at, EntityType.VEX);
            // ① 命中我方收养路径的第 1 条（按 getSummoner 精确归属）
            if (holder instanceof Mob m) {
                try {
                    vex.setSummoner(m);
                } catch (Throwable ignored) {
                    // 个别实现可能不支持 → 交给下面的显式收养兜底
                }
            }
            // ② 显式收养兜底（与唤魔者恼鬼的处理完全一致：非计数单位）
            if (battle != null && side != null) {
                battle.adoptDerived(vex, side, "VEXSUMMONER");
            }
            LivingEntity enemy = ctx.target();
            if (enemy != null && !enemy.isDead()) {
                vex.setTarget(enemy);
            }
        }
                AffixFx.sound(at, "ENTITY_EVOKER_PREPARE_SUMMON", 0.8f, 0.8f);
    }
}
