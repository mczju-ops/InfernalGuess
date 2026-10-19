package io.mczju.infernalguess.battle;

/**
 * ★ **方块怪（史莱姆 / 岩浆怪）接触伤害**的纯计算部分 —— 判据与数值**逐条照抄原版**。
 *
 * <h2>为什么需要它（26.2 服务端反编译实测）</h2>
 * 原版 {@code AbstractCubeMob} 的伤害入口**只有两个**，而且都排除了普通怪物：
 * <table border="1">
 *   <caption>原版入口</caption>
 *   <tr><th>方法</th><th>触发条件</th><th>能打到谁</th></tr>
 *   <tr><td>{@code playerTouch(Player)}</td><td>玩家碰到它</td><td><b>只有玩家</b></td></tr>
 *   <tr><td>{@code push(Entity)}</td><td>它撞到别的实体</td>
 *       <td>只有 {@code IronGolem}（源码里显式 {@code instanceof IronGolem}）</td></tr>
 * </table>
 * 而看起来像「攻击」的 {@code CubeMobAttackGoal.tick()} 只做两件事：
 * {@code lookAt(target)} + 给 {@code CubeMobMoveControl} 设方向 —— **一次伤害都不发**。
 * ⇒ 用户报的「史莱姆 / 岩浆怪打不动别的怪」是**原版行为**，不是我们漏配。
 *
 * <h2>照抄过来的四条</h2>
 * <ol>
 *   <li><b>伤害</b> {@code getAttackDamage()}：属性 {@code ATTACK_DAMAGE} 的值
 *       （{@code setSize} 把它设成体型 1/2/4）；<b>岩浆怪再 +2</b>（{@code MagmaCube} 覆写）。</li>
 *   <li><b>能不能打</b> {@code isDealsDamage()}：史莱姆要求 {@code !isTiny()}（体型 &gt; 1）
 *       —— 所以**体型 1 的史莱姆连玩家都打不动**；岩浆怪覆写成「只看 AI 是否生效」，
 *       体型 1 也能打（3 点）。</li>
 *   <li><b>够不够近</b> {@code Mob#getMeleeAttackRangeSqr()} = {@code (bbWidth*2)² + 目标宽度}。</li>
 *   <li><b>视线</b> {@code hasLineOfSight(target)}（原版 {@code dealDamage} 同款）。</li>
 * </ol>
 *
 * <p>这个类不碰 Bukkit，因此可以被离线单测钉住（{@code CubeMobTouchTest}）。
 */
final class CubeMobTouch {

    private CubeMobTouch() {
    }

    /** 原版 {@code AbstractCubeMob#isTiny()}：{@code getSize() <= 1}。 */
    static boolean isTiny(int size) {
        return size <= 1;
    }

    /**
     * 原版 {@code isDealsDamage()}：
     * 史莱姆 = {@code !isTiny() && isEffectiveAi()}，岩浆怪 = {@code isEffectiveAi()}。
     *
     * @param magma 是不是岩浆怪（它的覆写把体型条件去掉了）
     */
    static boolean dealsDamage(int size, boolean magma) {
        return magma || !isTiny(size);
    }

    /**
     * 原版 {@code getAttackDamage()} 的等价物。
     *
     * @param attackDamageAttribute 实体 {@code ATTACK_DAMAGE} 属性的当前值（= 体型）
     * @param magma                 是不是岩浆怪（{@code MagmaCube} 覆写：+2）
     */
    static double damageOf(double attackDamageAttribute, boolean magma) {
        return magma ? attackDamageAttribute + 2.0 : attackDamageAttribute;
    }

    /**
     * 原版 {@code Mob#getMeleeAttackRangeSqr(LivingEntity)} —— 返回的是**平方**距离阈值。
     *
     * @param selfWidth   方块怪自己的 {@code getWidth()}（体型越大越宽）
     * @param targetWidth 目标的 {@code getWidth()}
     */
    static double touchRangeSq(double selfWidth, double targetWidth) {
        double reach = selfWidth * 2.0;
        return reach * reach + targetWidth;
    }
}
