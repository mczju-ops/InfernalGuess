package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixType;
import io.mczju.infernalguess.affix.PlacedBlocks;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;

/**
 * 织网（{@code webber}）—— 在目标处铺蛛网；有概率铺一张**半径 6 的巨网**。
 *
 * <p>原实现：炒鸡 {@code DualWebberSkill}，{@code chance 0.15} / 冷却 40；
 * 在目标脚下放蛛网（5 秒后消失）；另有 {@code giant-sphere-chance 0.15} 的概率
 * 以目标为中心铺一个**半径 6、厚度 0.5 的空心蛛网球**。
 *
 * <h2>★ 为什么「半径 6 巨网」是安全的（我一开始判断错了）</h2>
 * 我最初把巨网去掉，理由是「会把双方一起关进网里、走位失效」。**这是错的** ——
 * 原版对蛛网有明确的免疫名单：
 * <blockquote>
 *   All mobs <b>except for spiders, cave spiders, shulkers and vexes</b>
 *   experience slow movement through a cobweb.
 *   —— <a href="https://minecraft.wiki/w/Cobweb">Minecraft Wiki: Cobweb § Effects</a>
 * </blockquote>
 * 而按用户裁定，本词条**只会发给蜘蛛类**（蜘蛛 / 洞穴蜘蛛）——**两者都在免疫名单里**。
 * 所以巨网的真实效果是「**蜘蛛织网捕食**」：猎物被黏住、蜘蛛畅通无阻。
 * 这正是此词条的设计意图，去掉巨网等于把它的核心观感削掉。
 *
 * <p>（附带发现：**恼鬼也免疫蛛网** → {@code vexsummoner} 召出的恼鬼不受巨网影响。）
 *
 * <h2>★ 清理逻辑（用户第 8 条要求）</h2>
 * 所有方块都走 {@link PlacedBlocks} 登记（记的是**原方块数据**），
 * 由 {@code BattleService.cleanup()} 与**回合清场同一时机**还原。
 * 炒鸡用 {@code runTaskLater} 定时清理 —— **区块卸载/服务器重启后任务丢失，
 * 蛛网永久残留在竞技场**，这条我们不再有。
 */
public final class WebberAffix implements Affix {

    /** 冷却 40 tick，照抄原版。 */
    private static final long COOLDOWN_TICKS = 40L;

    /** 巨网球触发概率（原版 {@code giant-sphere-chance}）。 */
    private static final double GIANT_SPHERE_CHANCE = 0.15;
    /** 巨网球半径（原版 {@code radius}）。 */
    private static final double SPHERE_RADIUS = 6.0;
    /** 巨网球壳厚度（原版 {@code thickness}）。 */
    private static final double SPHERE_THICKNESS = 0.5;

    @Override
    public String id() {
        return "webber";
    }

    @Override
    public AffixType type() {
        return AffixType.DUAL;
    }

    @Override
    public long cooldownTicks() {
        return COOLDOWN_TICKS;
    }

    /** ★ 原版 skills.webber.chance 的值，照抄。 */
    @Override
    public double chance() {
        return 0.15;
    }

    @Override
    public void onTrigger(AffixContext ctx) {
        if (!ctx.hasValidTarget()) {
            return;
        }
        Location at = ctx.target().getLocation();

        // 1) 目标脚下铺一格（原版的主体效果）
        int placed = place(at);

        // 2) 15% 概率再铺一张巨网（原版 giant-sphere-chance）
        if (Math.random() < GIANT_SPHERE_CHANCE) {
            placed += giantSphere(at);
        }
        if (placed > 0) {
            AffixFx.sound(at, "BLOCK_COBWEB_PLACE", 0.8f, 1.0f);
        }
    }

    /**
     * 以 {@code center} 为中心铺一层**空心球壳**蛛网。
     *
     * <p>只铺在**空气**上 → 不覆盖场地已有方块，这是它「可还原、不破坏场地」的前提
     * （配合 {@link PlacedBlocks} 的原方块数据登记）。
     */
    private int giantSphere(Location center) {
        int r = (int) Math.ceil(SPHERE_RADIUS);
        double inner = SPHERE_RADIUS - SPHERE_THICKNESS;
        int placed = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist > SPHERE_RADIUS || dist < inner) {
                        continue;   // 只留球壳
                    }
                    placed += place(center.clone().add(dx, dy, dz));
                }
            }
        }
        return placed;
    }

    private int place(Location loc) {
        if (loc.getWorld() == null) {
            return 0;
        }
        Block block = loc.getBlock();
        if (!block.getType().isAir()) {
            return 0;   // 不动已有方块
        }
        return PlacedBlocks.place(loc, Material.COBWEB) ? 1 : 0;
    }
}
