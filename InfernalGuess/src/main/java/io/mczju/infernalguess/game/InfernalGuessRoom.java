package io.mczju.infernalguess.game;

import com.github.mczjuops.mczjugamecore.game.room.JsonGameRoom;

/**
 * 本游戏的房间类。
 *
 * <p>MGC 要求每个游戏必须提供房间类（{@code registerGame} 的第二个参数）。
 * 房间是 MGC 的调度壳：{@code /mgc join <gameId>} 会挑一个 {@code READY} 房间来开局，
 * 局末置回 {@code READY} 供复用。
 *
 * <p>序列化、{@code getFilePath()}、{@code deleteRoom()} 均由 {@link JsonGameRoom} 提供。
 * 字段必须是 {@code public}（MGC 通过反射读写）。
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
}
