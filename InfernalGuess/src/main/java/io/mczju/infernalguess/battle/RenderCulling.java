package io.mczju.infernalguess.battle;

/**
 * ★ **客户端渲染剔除**的纯计算部分 —— 用来回答「为什么那只怪离远了就看不见」。
 *
 * <h2>反编译依据（26.2）</h2>
 * <pre>
 * // net.minecraft.world.entity.Entity
 * public boolean shouldRenderAtSqrDistance(double distance) {
 *    double size = this.getBoundingBox().getSize();   // = (xSize + ySize + zSize) / 3
 *    if (Double.isNaN(size)) size = 1.0;
 *    size *= 64.0 * viewScale;                        // viewScale = 客户端的「实体渲染距离」系数
 *    return distance < size * size;
 * }
 * </pre>
 * 注意这是**客户端**的行为：服务端该发的实体包都发了（追踪距离见 {@code clientTrackingRange}
 * 与 spigot 的 {@code entity-tracking-range}，单位是格，通常 96~128），
 * 但客户端到一定距离就**不再绘制**该实体。而这个距离只与**包围盒大小**有关 ⇒
 * 体型极小的怪（蠹虫、末影螨）会在 ~24 格外凭空消失。
 *
 * <h2>本项目怎么用它</h2>
 * 服务端唯一能改变的量就是包围盒 ⇒ 用原版属性 {@code minecraft:scale}
 * （26.2 里注册为 {@code setSyncable(true)}，所以客户端知道缩放、剔除用的包围盒也会跟着变大）
 * 把这类单位放大，让它「可见距离」覆盖整个竞技场。
 * 具体倍率写在 {@code config.yml} 的 {@code battle.mob-attributes.scale-overrides}，
 * 而 {@code ShippedConfigTest} 会按**用户提出的目标距离**逐条钉住（见该类注释）。
 */
public final class RenderCulling {

    private RenderCulling() {
    }

    /**
     * 原版 {@code AABB#getSize()}：包围盒三边长的**平均**。
     *
     * @param xSize 包围盒 x 边长（Bukkit 的 {@code Entity#getWidth()}）
     * @param ySize 包围盒 y 边长（{@code Entity#getHeight()}）
     * @param zSize 包围盒 z 边长（等于 {@code getWidth()}）
     */
    public static double bboxAverage(double xSize, double ySize, double zSize) {
        return (xSize + ySize + zSize) / 3.0;
    }

    /**
     * 客户端在默认「实体渲染距离」（100%）下的**渲染上限**（格）。
     *
     * <p>玩家把这个客户端选项调高（最高 5×）会按比例放大这个值 —— 我们改不了它，
     * 只能改包围盒。
     */
    public static double renderDistance(double bboxAverage) {
        return bboxAverage * 64.0;
    }

    /** 反推：想让尺寸为 {@code bboxAverage} 的单位在 {@code targetDistance} 格外还被渲染，需要多少倍缩放。 */
    public static double neededScale(double bboxAverage, double targetDistance) {
        return targetDistance / renderDistance(bboxAverage);
    }
}
