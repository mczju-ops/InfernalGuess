package io.mczju.infernalguess.game;

/** 回合阶段。 */
public enum RoundPhase {
    /** 未开始。 */
    IDLE,
    /** 生成剧本与生物（AI 锁定中）。 */
    PREPARING,
    /** 下注中（等待玩家选择或倒计时结束）。 */
    BETTING,
    /** 战斗中。 */
    FIGHTING,
    /** 结算中。 */
    SETTLING,
    /** 演出（烟花/发光，由 BattleService 负责）。 */
    EFFECT,
    /** 清场。 */
    CLEANUP,
    /** 全部结束。 */
    ENDED
}
