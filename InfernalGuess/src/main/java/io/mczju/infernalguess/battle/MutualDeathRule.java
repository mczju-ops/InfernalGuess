package io.mczju.infernalguess.battle;

import org.bukkit.entity.EntityType;

import java.util.Optional;

/**
 * ★ **双方同时全灭（同归于尽）时的裁决规则**（用户 2026-10-13 新规则）。
 *
 * <h2>规则原文</h2>
 * <pre>
 * 当全场实体全部同时死亡时：
 *   1、如果最后一次击杀由苦力怕造成（即苦力怕和敌人同归于尽了）
 *      算最后造成击杀的苦力怕的胜利；
 *   2、否则：
 *      ① 资金模式：所有人不扣资金也不加资金，all in 的人不会死亡；
 *      ② 对决模式：所有人都不会掉血
 * </pre>
 *
 * <h2>为什么单独抽一个类</h2>
 * 「最后一击是谁打的」是运行时事实（{@link BattleService} 记录），而「这算谁赢」是纯规则
 * —— 抽出来就能离线单测（{@code MutualDeathRuleTest}），也免得规则散在
 * {@code RoundManager} 的流程里看不出来。
 *
 * <p>⚠️ 注意与**超时**的区别：战斗超时（{@code maxFightSeconds}）仍走
 * {@code BattleTracker.judgeOnTimeout(...)}（先比存活数、再比总血量、最后种子硬币）——
 * 用户这条规则只针对「**同时全灭**」。
 */
public final class MutualDeathRule {

    private MutualDeathRule() {
    }

    /**
     * 同归于尽时谁赢。
     *
     * @param lastKillerSide 最后一次「敌方击杀」的击杀者所属阵营（可能为 {@code null}）
     * @param lastKillerType 最后一次击杀的击杀者类型（可能为 {@code null}）
     * @return 苦力怕造成最后一击 ⇒ 返回它那一方；否则返回 {@link Optional#empty()} 表示**真平局**
     */
    public static Optional<BattleSide> winner(BattleSide lastKillerSide, EntityType lastKillerType) {
        if (lastKillerType == EntityType.CREEPER && lastKillerSide != null) {
            return Optional.of(lastKillerSide);
        }
        return Optional.empty();
    }
}
