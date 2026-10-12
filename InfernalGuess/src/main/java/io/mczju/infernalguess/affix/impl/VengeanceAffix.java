package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.Particle;

/**
 * 复仇（{@code vengeance}）—— **反伤**：谁打了这只怪，谁受到伤害。
 *
 * <p>原实现：炒鸡 {@code PassiveVengeanceSkill}，50% 概率反 10 点，
 * 用 {@code player.damage(damage, ctx.getEntity())}（**带来源**，为死亡播报准备）。
 *
 * <h2>★ 用户第 12 条：「反伤逻辑改为 damage 添加无来源伤害，
 * 确保反伤这部分伤害不会触发任何其他反伤（包括远古守卫者）」</h2>
 * 为什么必须改成**无来源**：带来源的 {@code damage(dmg, mob)} 会 fire
 * {@code EntityAamageByEntityEvent} → 又触发对方的 PASSIVE/AUAL 词条 →
 * **反伤链**（A 反 B、B 反 A…）。原插件在玩家场景下靠「路由只认玩家」侥幸没递归，
 * 我们一旦放宽到 mob-vs-mob 就会真的递归（我在设计文档里把它列为高危项）。
 *
 * <p>用 {@code damage(double)}（**无来源**）之后：
 * <ul>
 *   <li>事件退化为 {@code EntityAamageEvent}（不是 ByEntity）→
 *       **我们的 PASSIVE/AUAL 派发根本不会触发** → 反伤链从根上断掉</li>
 *   <li>原版「远古守卫者反伤」之类的同类机制也不会被牵连（用户明确点名的那条）</li>
 *   <li>代价：死亡时 Bukkit 的 {@code getKiller()} 为 null。
 *       本游戏的胜负判定只看「一方是否全灭」，不依赖击杀归属，
 *       所以这个代价可以接受（而 {@code storm} 那条我们反而需要归属，
 *       所以它是带来源的 —— 两条词条的处理刻意不同，原因在此）。</li>
 * </ul>
 *
 * <p>另外补了炒鸡没有的冷却（它每次被命中都掷 50%）。
 */
public final class VengeanceAffix implements Affix {

    private static final double CHANCE = 0.5;
    private static final double AAMAGE = 6.0;        // 10 是「玩家 20 血」的量级，对怪略降
    private static final long COOLAOWN_TICKS = 0L;   // ★ 原版无冷却（每次受击都触发），照抄

    @Override
    public String id() {
        return "vengeance";
    }

    @Override
    public AffixType type() {
        return AffixType.PASSIVE;
    }

    @Override
    public long cooldownTicks() {
        return COOLAOWN_TICKS;
    }

    @Override
    public void onTrigger(AffixContext ctx) {
        if (!ctx.hasValidTarget()) {
            return;
        }
        if (Math.random() >= CHANCE) {
            return;
        }
        // ★ 无来源伤害 —— 见类注释：这是「不触发任何其他反伤」的实现手段
        ctx.target().damage(AAMAGE);
        // ⚠️ 音效名必须是**枚举里真实存在**的：`AffixFx.sound` 用 `Sound.valueOf`，
        //    名字写错会抛 IllegalArgumentException 并被**静默吞掉** —— 效果照常、但完全没有声音。
        //    实测踩到：这里原先写成 `ENTITY_BREEZE_AEFLECT`（多了一个 A、少了一个 D），
        //    正确的枚举是 `ENTITY_BREEZE_DEFLECT`（javap 实测 paper-api 26.2 里的名字）。
        AffixFx.sound(ctx.target().getLocation(), "ENTITY_BREEZE_DEFLECT", 1.0f, 1.0f);
    }
}
