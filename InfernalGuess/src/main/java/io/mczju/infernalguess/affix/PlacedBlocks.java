package io.mczju.infernalguess.affix;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * **对局期间被词条改动过的方块**的登记与还原。
 *
 * <h2>为什么必须自己管（用户第 8 条要求「需要写一个清理逻辑」）</h2>
 * 炒鸡的 {@code webber} 用 {@code block.setType(COBWEB)} 铺网，再用 `runTaskLater`
 * 定时清回空气。这个做法有两个致命问题：
 * <ol>
 *   <li><b>不持久化</b>：区块卸载 / 服务器重启后，定时任务丢失 →
 *       **蛛网永久残留在竞技场里**，无法回收（源码 {@code DualWebberSkill:140-181} 从未处理）</li>
 *   <li><b>不在清场流程里</b>：与「一回合清场」是两套独立机制，容易漏</li>
 * </ol>
 *
 * <p>我们的做法：**所有词条放下的方块都登记在这里**（同时记下**原方块的数据**），
 * 由 {@code BattleService.cleanup()} 统一还原。好处：
 * <ul>
 *   <li>与清场同一时机 → **不会跨回合残留**</li>
 *   <li>记的是**原方块数据**（不只是「置为空气」）→ 即使将来词条放到非空气处也能正确还原</li>
 *   <li>万一服务器重启，至少下一次清场会兜住（而且我们**不做定时任务**，不存在任务丢失）</li>
 * </ul>
 *
 * <p>⚠️ 线程安全：只在主线程使用（与 Bukkit 方块操作一致）。
 */
public final class PlacedBlocks {

    private PlacedBlocks() {
    }

    /** 位置 → 原方块数据（同一位置被多次改动时，以**最早**那次的原数据为准）。 */
    private static final Map<Location, BlockData> ORIGINAL = new LinkedHashMap<>();

    /**
     * 记下原方块，并把该位置改成 {@code material}。
     *
     * @return 是否真的改了（位置无效 / 已经是该材质 → false）
     */
    public static boolean place(Location location, Material material) {
        if (location == null || location.getWorld() == null || material == null) {
            return false;
        }
        Block block = location.getBlock();
        if (block.getType() == material) {
            return false;                      // 已经是目标材质，不必登记
        }
        ORIGINAL.putIfAbsent(location.getBlock().getLocation(), block.getBlockData());
        block.setType(material, false);        // false = 不触发物理更新（避免连带掉落）
        return true;
    }

    /** 还原全部被改动过的方块，并清空登记。由清场调用。 */
    public static int restoreAll() {
        int n = 0;
        for (Map.Entry<Location, BlockData> e : ORIGINAL.entrySet()) {
            try {
                Location loc = e.getKey();
                if (loc.getWorld() == null) {
                    continue;
                }
                loc.getBlock().setBlockData(e.getValue(), false);
                n++;
            } catch (Throwable ignored) {
                // 世界已卸载等 → 跳过这一个
            }
        }
        ORIGINAL.clear();
        return n;
    }

    /** 当前登记了多少个被改动过的方块（调试用）。 */
    public static int tracked() {
        return ORIGINAL.size();
    }
}
