package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 披甲（{@code armoured}）—— **给怪物穿一整套盔甲**；不能穿盔甲的改发抗性提升 II。
 *
 * <p>原实现：炒鸡 {@code StatArmouredSkill}，默认钻石套（Lv11+ 升下界合金），
 * 掉落率全部置 0；`mob.getEquipment() == null` 时改发 `RESISTANCE` II。
 *
 * <h2>★ 用户第 4 条要求：「尽量靠后结算，绕开我们删去怪物自带盔甲的过程」</h2>
 * 背景：我方 {@code BattleService.applyMobSetup} 里的 {@code applyEquipment}
 * 会**先清空原版随机装备、再按 mob-groups.yml 穿戴** —— 如果词条在它之前生效，
 * 刚穿好的盔甲会被立刻清掉。
 *
 * <p>而我们的装配时机天然满足这个要求：{@code BattleService.spawnOne} 的顺序是
 * <pre>applyMobSetup(...)  →  tracker.track(...)  →  affixRegistry.equip(mob)</pre>
 * 即 {@code onEquip} **一定在装备流程之后**，所以这里直接覆盖四件套是安全的。
 * 这条注释把**依赖关系**写下来，避免将来有人调换 spawnOne 里的顺序而悄悄弄坏它。
 *
 * <p>数值上：不引入炒鸡的「等级」概念（本项目没有等级体系），固定钻石套。
 * 抗性只取最高等级，所以同时抽到 {@code bullwark}（抗性 III）时以它为准。
 */
public final class ArmouredAffix implements Affix {

    private static final int RESISTANCE_AMPLIFIER = 1;   // 抗性 II

    @Override
    public String id() {
        return "armoured";
    }

    @Override
    public AffixType type() {
        return AffixType.STAT;
    }

    @Override
    public void onEquip(AffixContext ctx) {
        LivingEntity entity = ctx.entity();
        if (entity == null || !entity.isValid()) {
            return;
        }
        if (entity instanceof Mob mob && mob.getEquipment() != null) {
            EntityEquipment eq = mob.getEquipment();
            eq.setHelmet(new ItemStack(Material.DIAMOND_HELMET));
            eq.setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
            eq.setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS));
            eq.setBoots(new ItemStack(Material.DIAMOND_BOOTS));
            // 掉落率 0：清场是靠 remove()，但万一被别的途径杀死也不该掉一地装备
            eq.setHelmetDropChance(0);
            eq.setChestplateDropChance(0);
            eq.setLeggingsDropChance(0);
            eq.setBootsDropChance(0);
        } else {
            entity.addPotionEffect(new PotionEffect(
                    PotionEffectType.RESISTANCE,
                    PotionEffect.INFINITE_DURATION,
                    RESISTANCE_AMPLIFIER, false, true));
        }
    }
}
