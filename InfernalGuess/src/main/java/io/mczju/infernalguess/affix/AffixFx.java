package io.mczju.infernalguess.affix;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.Locale;

/**
 * 词条的**视觉与听觉效果**工具（用户第 16 条：尽量复现原插件的效果）。
 *
 * <p>炒鸡有一整套 {@code particle/ParticleEffect} + {@code ParticleSource} 抽象
 * （螺旋、射线、球面……）。我们不需要那么重，只保留实际用到的几种形状：
 *
 * <table border="1">
 *   <caption>原插件用到、我们需要复现的</caption>
 *   <tr><th>形状</th><th>原用途</th></tr>
 *   <tr><td>{@link #spiral 螺旋}</td><td>{@code 1up} 的心形螺旋</td></tr>
 *   <tr><td>{@link #line 射线}</td><td>{@code wardenwrath} 的 SONIC_BOOM 射线、{@code thief} 的偷窃连线</td></tr>
 *   <tr><td>{@link #ring 环}</td><td>{@code dye} 的 portal 光环</td></tr>
 * </table>
 *
 * <p>所有方法都**只读入参、不做世界查询**，因此可以在任意时刻安全调用；
 * 内部对 {@code null} 世界做了保护（实体可能刚被卸载）。
 */
public final class AffixFx {

    private AffixFx() {
    }

    // ---------------- 形状 ----------------

    /**
     * 螺旋上升的粒子（{@code 1up} 的复活特效）。
     *
     * @param points 采样点数（越多越密）
     */
    public static void spiral(Location center, Particle particle, int points,
                              double radius, double height) {
        if (!valid(center)) {
            return;
        }
        World world = center.getWorld();
        for (int i = 0; i < points; i++) {
            double t = (double) i / points;
            double angle = t * Math.PI * 4.0;          // 两圈
            double r = radius * (1.0 - t * 0.4);       // 越往上越收
            double x = Math.cos(angle) * r;
            double z = Math.sin(angle) * r;
            world.spawnParticle(particle, center.clone().add(x, t * height, z),
                    1, 0.02, 0.02, 0.02, 0.0);
        }
    }

    /**
     * 两点之间的粒子射线（{@code wardenwrath} 的声波、{@code thief} 的连线）。
     *
     * @param density 采样点数；建议按距离给（如每格 2 点）
     */
    public static void line(Location from, Location to, Particle particle, int density) {
        if (!valid(from) || !valid(to) || !from.getWorld().equals(to.getWorld()) || density < 2) {
            return;
        }
        World world = from.getWorld();
        Vector delta = to.toVector().subtract(from.toVector());
        for (int i = 0; i <= density; i++) {
            Location at = from.clone().add(delta.clone().multiply((double) i / density));
            world.spawnParticle(particle, at, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** 水平圆环（{@code dye} 的光环、{@code vexsummoner} 的召唤阵）。 */
    public static void ring(Location center, Particle particle, int points, double radius) {
        if (!valid(center)) {
            return;
        }
        World world = center.getWorld();
        for (int i = 0; i < points; i++) {
            double angle = 2.0 * Math.PI * i / points;
            Location at = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            world.spawnParticle(particle, at, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /**
     * 需要 {@code BlockData} 的粒子（如 {@code Particle.FALLING_DUST} / {@code Particle.BLOCK}）。
     *
     * <p>★ 实测踩过的坑：{@code FALLING_DUST} 不带 BlockData 会直接抛
     * {@code IllegalArgumentException: missing required data interface BlockData}。
     * 有些粒子的数据参数是**必需**的，不是可选的 —— 所以单列一个方法，别混进 {@link #ring}。
     *
     * @param material 用哪种方块的外观（如 {@code Material.SAND} 做「流沙」）
     */
    public static void ringBlock(Location center, Particle particle,
                                 org.bukkit.Material material, int points, double radius) {
        if (!valid(center) || material == null || material.isAir()) {
            return;
        }
        World world = center.getWorld();
        org.bukkit.block.data.BlockData data;
        try {
            data = material.createBlockData();
        } catch (Throwable t) {
            return;   // 该材质不支持 BlockData → 静默跳过（不值得为特效刷日志）
        }
        for (int i = 0; i < points; i++) {
            double angle = 2.0 * Math.PI * i / points;
            Location at = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            world.spawnParticle(particle, at, 1, 0.0, 0.0, 0.0, 0.0, data);
        }
    }

    // ---------------- 音效 ----------------

    /**
     * 播音效。
     *
     * <p>炒鸡的音效 key 混用两种写法：枚举名（{@code ENTITY_BREEZE_JUMP}）
     * 与小写带点（{@code entity.breeze.jump}）。这里**两种都接受**，
     * 统一规范成枚举名再解析；解析失败就静默跳过（原插件也是 try/catch 忽略）。
     */
    public static void sound(Location at, String soundKey, float volume, float pitch) {
        if (!valid(at) || soundKey == null || soundKey.isBlank()) {
            return;
        }
        try {
            Sound sound = Sound.valueOf(soundKey.trim().toUpperCase(Locale.ROOT).replace('.', '_'));
            at.getWorld().playSound(at, sound, volume, pitch);
        } catch (IllegalArgumentException ignored) {
            // 音效名不合法 → 静默（与炒鸡一致，不值得为它刷日志）
        }
    }

    private static boolean valid(Location loc) {
        return loc != null && loc.getWorld() != null;
    }
}
