package io.mczju.infernalguess.game;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.game.room.JsonGameRoom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * 本游戏的房间类（{@code plugins/MCZJUGameCore/rooms/infernalguess/<房间名>.json}）。
 *
 * <p>MGC 要求每个游戏必须提供房间类（{@code registerGame} 的第二个参数）。
 * 房间是 MGC 的调度壳：{@code /mgc join <gameId>} 会挑一个 {@code READY} 房间来开局，
 * 局末置回 {@code READY} 供复用。
 *
 * <p>序列化、{@code getFilePath()}、{@code deleteRoom()} 均由 {@link JsonGameRoom} 提供。
 *
 * <h2>★ 字段约定（实测 MGC 1.1.2）</h2>
 * 字段必须是 {@code public}（MGC 通过反射读写），且类型只支持
 * {@code Boolean} / {@code String} / {@link Location} / {@code Long} / {@code Float} / {@code Double}
 * —— 依据 {@code RoomParameterEditor.supports()} 的字节码（它内部有
 * {@code ldc class org/bukkit/Location}，且 {@code format()} 会打印
 * {@code getWorld()/getX()/getY()/getZ()}）。
 * 所以这里的 {@code Location} 字段**能直接在 MGC 的房间编辑菜单里改**，
 * 不需要 {@code @FieldDescription}（Maggoteers 实测该注解在编辑菜单里不起作用）。
 *
 * <h2>★ 三个地点各管什么（别混）</h2>
 * <table>
 *   <tr><th>字段</th><th>时机</th><th>未配置时</th></tr>
 *   <tr><td>{@link #waitLocation}</td><td>**加入房间进入等待阶段**</td>
 *       <td>不传送（玩家留在原地）</td></tr>
 *   <tr><td>竞技场的 {@code spectate-spawn}（在 arenas.yml，不在本类）</td>
 *       <td>**开局后**下注阶段观战</td><td>不传送</td></tr>
 *   <tr><td>{@link #returnLocation}</td><td>**对局结束 / 中止 / 退出**</td>
 *       <td>回退到 {@link #waitLocation}</td></tr>
 * </table>
 *
 * <p>运维指令：{@code /mgcop room create infernalguess <名>}、
 * {@code /mgcop room edit infernalguess <名>}。
 */
public final class InfernalGuessRoom extends JsonGameRoom {

    /**
     * 本房间使用的竞技场名（对应 {@code arenas.yml} 里的键）。
     *
     * <p>留空或指向不存在的竞技场时，开局会**自动挑一个可用竞技场**兜底，
     * 因此新服不配也能跑起来；多竞技场时运维可用
     * {@code /mgcop room edit infernalguess default} 指定。
     */
    public String arenaName;

    /**
     * ★ **加入房间时的集合点**：玩家 {@code /mgc join} 进入等待阶段后传送到这里。
     *
     * <p>未设置则不传送（玩家留在自己加入前的位置）。
     */
    public Location waitLocation;

    /**
     * ★ **对局结束时的回程点**：局末 / 中止 / 退出时把玩家送回这里。
     *
     * <p>可跨世界（Bukkit 的 {@code teleport(Location)} 支持跨世界）。
     * 未设置时回退到 {@link #waitLocation}，再回退到玩家**入局前的位置**，
     * 最后兜底主世界 spawn —— 见 {@link #teleportReturn}。
     */
    public Location returnLocation;

    /** 取某局绑定的房间（不是本游戏的房间时返回 {@code null}）。 */
    public static InfernalGuessRoom of(AbstractGame game) {
        if (game == null) {
            return null;
        }
        return game.getGameRoom() instanceof InfernalGuessRoom r ? r : null;
    }

    /** 加入房间 → 传送到集合点（未配置则不传送）。 */
    public static void teleportWait(AbstractGame game, Player player) {
        InfernalGuessRoom room = of(game);
        teleport(player, room == null ? null : room.waitLocation);
    }

    /**
     * ★ 回程：把玩家送回大厅。
     *
     * <h2>为什么必须有回退链（Maggoteers 踩过的坑）</h2>
     * 房间未配 return / wait（例如 {@code default.json} 是空的 {@code {}}）时，
     * 若不加兜底，玩家会**留在竞技场世界**；一旦该世界被卸载/删除，
     * Bukkit 会把玩家自动送回**主世界 spawn** —— 表现为「打完一局被丢到主世界出生点」。
     *
     * <p>回退顺序：{@link #returnLocation} → {@link #waitLocation} →
     * {@code fallback}（玩家入局前的位置）→ 主世界 spawn。
     */
    public static void teleportReturn(AbstractGame game, Player player, Location fallback) {
        Location dest = null;
        InfernalGuessRoom room = of(game);
        if (room != null) {
            if (isUsable(room.returnLocation)) {
                dest = room.returnLocation;
            } else if (isUsable(room.waitLocation)) {
                dest = room.waitLocation;
            }
        }
        if (dest == null && isUsable(fallback)) {
            dest = fallback;
        }
        if (dest == null) {
            var worlds = Bukkit.getWorlds();
            if (!worlds.isEmpty()) {
                dest = worlds.get(0).getSpawnLocation();
            }
        }
        teleport(player, dest);
    }

    /** 世界已加载才算可用 —— Gson 反序列化出的 Location 若世界名无效，{@code getWorld()} 会是 null。 */
    private static boolean isUsable(Location loc) {
        return loc != null && loc.getWorld() != null;
    }

    private static void teleport(Player player, Location loc) {
        if (player == null || !player.isOnline() || loc == null) {
            return;
        }
        if (loc.getWorld() == null) {
            // 跨世界 tp 会静默失败 → 必须留痕，否则表现为「传送没反应」查不出原因
            var plug = io.mczju.infernalguess.InfernalGuessPlugin.getInstance();
            if (plug != null) {
                plug.getLogger().warning("[IG] 房间传送目标的世界未加载，跳过：" + loc);
            }
            return;
        }
        player.teleport(loc);
    }

    /** 记录玩家入局前的位置，供回程兜底用（回退链的第三环）。 */
    public static void rememberJoinLocation(java.util.Map<UUID, Location> into, Player player) {
        if (into != null && player != null) {
            into.put(player.getUniqueId(), player.getLocation());
        }
    }
}
