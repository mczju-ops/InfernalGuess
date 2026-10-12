package io.mczju.infernalguess.game;

import com.github.mczjuops.mczjugamecore.player.data.JsonPlayerData;

import java.util.ArrayList;
import java.util.List;

/**
 * 本游戏的玩家持久化档案（由 MGC 统一托管）。
 *
 * <p>落盘位置由 {@link JsonPlayerData#getFilePath()} 决定（MGC 内部规则），
 * 我们只负责声明要持久化的**公开字段** —— MGC 通过反射读写它们。
 *
 * <p>⚠️ **改动后必须 {@code setModified(true)}**，否则不会落盘
 * （Maggoteers 的 CLAUDE.md 把这列为已知坑）。本类用 setter 封装来强制这一点。
 */
public final class InfernalGuessPlayerData extends JsonPlayerData {

    /** 参与过的局数。 */
    public int runs;

    /** 最终获胜（唯一胜者或并列胜）的次数。 */
    public int wins;

    /** 历史最高结束资金（资金模式）。 */
    public long bestFunds;

    /** 历史累计获得资金（用于排行榜）。 */
    public long totalFundsEarned;

    /** 已解锁的额外内容 id（预留，当前无用途）。 */
    public List<String> unlocks = new ArrayList<>();

    // ---------------- 带 setModified 的写入封装 ----------------

    public void addRun() {
        runs++;
        setModified(true);
    }

    public void addWin() {
        wins++;
        setModified(true);
    }

    public void recordFunds(long finalFunds) {
        if (finalFunds > bestFunds) {
            bestFunds = finalFunds;
        }
        if (finalFunds > 0) {
            totalFundsEarned += finalFunds;
        }
        setModified(true);
    }
}
