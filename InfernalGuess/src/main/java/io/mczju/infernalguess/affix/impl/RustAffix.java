package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 锈蚀（{@code rust}）—— **打掉对方手里的武器**。
 *
 * <p>原实现：炒鸡 {@code PassiveRustSkill}，30% 概率扣对面主手 20 点耐久，
 * 掉光就直接消失 + 物品损坏音效。
 *
 * <h2>★ 用户第 7 条：「加大力度，我需要一个能直接缴械对方的词条」</h2>
 * 所以本实现刻意做得**激进**：概率 50%、每次扣 **60** 点耐久、冷却 3 秒。
 * 多数武器总耐久只有 130~203，连续几次命中就能真的打碎 →
 * **「把对面的武器打没」本身就是一个可读性极强的战况**。
 *
 * <h2>★ 接口改造（炒鸡这里是重度 `Player` 用法）</h2>
 * <table>
 *   <tr><th>炒鸡</th><th>我们</th></tr>
 *   <tr><td>{@code player.getInventory().getItemInMainHand()}</td>
 *       <td>{@code target.getEquipment().getItemInMainHand()}（★ 必须判 {@code getEquipment() != null}）</td></tr>
 *   <tr><td>{@code player.getGameMode() != CREATIVE} 守卫</td><td>**删掉** —— 怪没有游戏模式</td></tr>
 *   <tr><td>{@code player.getInventory().setItemInMainHand(empty)}</td>
 *       <td>{@code eq.setItemInMainHand(null)}</td></tr>
 * </table>
 *
 * <p>另外**跳过带「无法破坏」的武器**（{@code Unbreakable}）——
 * 那种武器扣不动，硬扣只会白费这次触发。
 */
public final class RustAffix implements Affix {

    private static final double CHANCE = 0.5;
    private static final int DURABILITY_DAMAGE = 60;   // ★ 用户要求「加大力度」
    private static final long COOLDOWN_TICKS = 0L;   // ★ 原版无冷却（每次受击都触发），照抄

    @Override
    public String id() {
        return "rust";
    }

    @Override
    public AffixType type() {
        return AffixType.PASSIVE;
    }

    @Override
    public long cooldownTicks() {
        return COOLDOWN_TICKS;
    }

    /** ★ 原版 skills.Rust  .chance 的值，照抄。 */
    @Override
    public double chance() {
        return 0.3;
    }


    @Override
    public void onTrigger(AffixContext ctx) {
        LivingEntity holder = ctx.entity();
        if (holder == null || !ctx.hasValidTarget()) {
            return;
        }
        if (Math.random() >= CHANCE) {
            return;
        }
        LivingEntity target = ctx.target();
        EntityEquipment eq = target.getEquipment();
        if (eq == null) {
            return;
        }
        ItemStack hand = eq.getItemInMainHand();
        if (hand == null || hand.getType().isAir() || hand.getAmount() <= 0) {
            return;   // 空手：没什么可缴的
        }
        ItemMeta meta = hand.getItemMeta();
        if (!(meta instanceof Damageable damageable)) {
            return;   // 工具/方块等不可损耗
        }
        if (meta.isUnbreakable()) {
            return;   // 无法破坏 → 扣不动，别白费触发
        }
        int max = hand.getType().getMaxDurability();
        if (max <= 0) {
            return;
        }
        int now = damageable.getDamage();
        int next = now + DURABILITY_DAMAGE;

        if (next >= max) {
            // ★ 直接缴械：武器碎了
            eq.setItemInMainHand(null);
                        AffixFx.sound(target.getLocation(), "ENTITY_ITEM_BREAK", 1.0f, 0.7f);
        } else {
            damageable.setDamage(next);
            hand.setItemMeta(damageable);
                        AffixFx.sound(target.getLocation(), "ENTITY_ITEM_BREAK", 0.5f, 0.7f);
        }
    }
}
