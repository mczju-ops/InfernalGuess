package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

/**
 * 披甲+（{@code armoured+}）—— `armoured` 的**升级形态**：穿一整套**下界合金**甲。
 *
 * <h2>★ 为什么单独立成一个词条（用户裁定）</h2>
 * 原版炒鸡的 `armoured` 是按**等级**选甲的：
 * {@code armor-tier 3} 钻石套，**Lv11 以上升级为下界合金套**。
 * 而本项目**没有等级体系**，所以「同一词条按等级变强」无法成立。
 *
 * <p>用户的处理办法很干净：**把升级形态拆成独立词条** ——
 * <ul>
 *   <li>{@code armoured} = 钻石套（原版低档）</li>
 *   <li>{@code armoured+} = 下界合金套（原版高档）</li>
 * </ul>
 * 这样两条各自的强度都是原版的**真实档位**，玩家对「钻甲」和「合金甲」的
 * 威胁判断完全不变；而权重系统可以分别给它们定价。
 * （沿用 {@code 1up} 的经验：**不要发明新的强度刻度，只复用原版已有的档位**。）
 *
 * <p>与 {@code armoured} 相同的两点：掉落率置 0；不能穿甲的实体改发抗性 II。
 */
public final class ArmouredPlusAffix implements Affix {

    private static final int RESISTANCE_AMPLIFIER = 1;   // 抗性 II（与原版无装备时的兜底一致）

    @Override
    public String id() {
        return "armoured+";
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
            eq.setHelmet(new ItemStack(Material.NETHERITE_HELMET));
            eq.setChestplate(new ItemStack(Material.NETHERITE_CHESTPLATE));
            eq.setLeggings(new ItemStack(Material.NETHERITE_LEGGINGS));
            eq.setBoots(new ItemStack(Material.NETHERITE_BOOTS));
            eq.setHelmetDropChance(0);
            eq.setChestplateDropChance(0);
            eq.setLeggingsDropChance(0);
            eq.setBootsDropChance(0);
        } else {
            // 不能穿甲的实体（如史莱姆、烈焰人）→ 改发抗性提升（与原版兜底一致）
            entity.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.RESISTANCE,
                    org.bukkit.potion.PotionEffect.INFINITE_DURATION,
                    RESISTANCE_AMPLIFIER, false, true));
        }
    }
}
