package io.mczju.infernalguess.battle;

/**
 * 清场范围策略 —— ★ 项目**铁律 10**（见 {@code AGENTS.md}）的**可执行形式**。
 *
 * <h2>铁律 10（用户 2026-10-12 裁定，原文）</h2>
 * <blockquote>
 * 「"清 living entity" 这个行为非常危险，需要十分严格的限制：本游戏的清场只能清除
 * 所有本插件接管的实体；至于弹射物 + 烟花等实体，以及未接管但出现在竞技场的怪物，
 * 请以竞技场的玩家出生点为中心，仅清除 64 格内的相关实体。**严禁以整个 lobby 为范围
 * 清除任何实体**。」
 * </blockquote>
 *
 * <h2>为什么必须钉死（实测依据，不是推测）</h2>
 * <p>旧实现是：
 * <pre>
 * for (Entity e : world.getEntities()) {
 *     if (e instanceof Player || e instanceof Item) continue;
 *     if (e instanceof LivingEntity le) le.remove();      // ← 整个世界的一切「生物」全删
 * }
 * </pre>
 * 而现场 {@code arenas.yml}（2026-10-12 实测）里竞技场 {@code test1} 的
 * {@code world: world} —— 竞技场**就在主世界**，各点位距 {@code player-spawn}
 * 只有 8.25~11.49 格。也就是说这一行等于**清空主世界里的一切生物实体**：
 * 玩家放的假人（{@code Mannequin extends LivingEntity}）、盔甲架、动物、村民，
 * 以及其它插件的实体，全部会被误删。
 *
 * <h2>本类为什么能离线单测</h2>
 * <p>只做**纯判定**（半径 + 类型布尔），不引用任何 Bukkit 实体类型 ——
 * 于是清场规则可以被 {@code CleanupPolicyTest} 钉住，改范围必须先改测试。
 */
public final class CleanupPolicy {

    /**
     * 竞技场兜底清理的半径（格）。
     *
     * <p>★ 写死，**不做成配置项** —— 铁律 10 的核心就是「不允许被放大」。
     * 现场竞技场实测最大点位距离仅 11.49 格，64 格已有 5 倍余量。
     */
    public static final double ARENA_RADIUS = 64.0;

    private CleanupPolicy() {
    }

    /**
     * 3D 距离是否落在竞技场清理半径内（**含边界**）。
     *
     * @param dx 目标与竞技场中心的 X 差
     * @param dy 目标与竞技场中心的 Y 差
     * @param dz 目标与竞技场中心的 Z 差
     */
    public static boolean inArenaRange(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz <= ARENA_RADIUS * ARENA_RADIUS;
    }

    /**
     * 「**未接管**实体」是否属于竞技场兜底清理目标。
     *
     * <p>规则（铁律 10 的第 2 条，逐条对应）：
     * <ol>
     *   <li>玩家 / 掉落物 → <b>永不清理</b>（即使就在中心点上也一样）</li>
     *   <li>只清理 <b>投射物 / 烟花 / 怪物</b>（{@code Enemy}：含 Monster、Hoglin、
     *       Slime、Ghast…）—— 假人、盔甲架、展示实体、动物、村民**都不在其中**</li>
     *   <li>且必须在 {@link #ARENA_RADIUS} 格内 —— **超出半径的一律不动**</li>
     * </ol>
     *
     * <p>⚠️ 「本插件接管的实体」不走本方法：它们由调用方按 UUID 从追踪表清理，
     * **不受半径限制**（铁律 10 第 1 条）。
     *
     * @param player     目标是玩家
     * @param item       目标是掉落物
     * @param projectile 目标是投射物
     * @param firework   目标是烟花
     * @param enemy      目标是怪物（{@code org.bukkit.entity.Enemy}）
     * @param dx         与竞技场中心的 X 差
     * @param dy         与竞技场中心的 Y 差
     * @param dz         与竞技场中心的 Z 差
     */
    public static boolean shouldRemoveUnmanaged(boolean player, boolean item,
                                                boolean projectile, boolean firework, boolean enemy,
                                                double dx, double dy, double dz) {
        if (player || item) {
            return false;                       // ① 玩家与掉落物永不清理
        }
        if (!projectile && !firework && !enemy) {
            return false;                       // ② 只管投射物 / 烟花 / 怪物
        }
        return inArenaRange(dx, dy, dz);         // ③ 且必须在半径内
    }
}
