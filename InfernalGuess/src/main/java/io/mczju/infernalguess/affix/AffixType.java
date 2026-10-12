package io.mczju.infernalguess.affix;

/**
 * 词条类型 —— 决定**它由哪个事件源派发**。
 *
 * <p>这套分类沿用炒鸡（InfernalMobs）的语义，因为它与 Bukkit 的事件模型一一对应，
 * 而且我们已经逐个核对过炒鸡的七个触发入口（见 {@code docs/13} §2.3.1）。
 *
 * <h2>与炒鸡的关键差别</h2>
 * 炒鸡的触发路由只认「怪打玩家 / 玩家打怪」，且目标字段是 {@code Player}
 * （{@code SkillContext} 只有 {@code Player targetPlayer}）→ 怪打怪时**一条都不触发**。
 *
 * <p>我们做两件事就够了：
 * <ol>
 *   <li><b>类型放宽</b>：目标改成 {@link org.bukkit.entity.LivingEntity}</li>
 *   <li><b>语义换视角</b>：{@code ACTIVE} = 「我的怪打到敌人时」、
 *       {@code PASSIVE} = 「敌人打到我的怪时」—— **本来就是同一个伤害事件</b>，
 *       只是从攻方/守方两个角度看</li>
 * </ol>
 * 因此 mob-vs-mob 下不需要改事件的选取逻辑，只需要在派发时把「另一方」当目标。
 */
public enum AffixType {

    /**
     * 数值型：装配时（怪物生成后）直接改实体或属性，**与目标无关**。
     *
     * <p>对应炒鸡的 {@code STAT}。派发时机：{@code BattleService.spawnOne} 之后一次。
     */
    STAT,

    /**
     * **伤害拦截**：在任何伤害**生效之前**介入（不分来源：近战/投射物/摔落/火焰都算）。
     *
     * <p>这是为 {@code 1up}（濒死回满血）单独分出来的一类 —— 炒鸡把它的类型标成
     * {@code STAT}，但真正的判定挂在 {@code EntityDamageEvent} 上（不是
     * {@code EntityDamageByEntityEvent}），与「装配时改属性」完全是两回事。
     * 标成 STAT 会误导实现者，所以这里独立成 WARD。
     *
     * <p>★ 实现时必须保留炒鸡的一条语义：**「致命一击不拦」**
     * （{@code healthAfter <= 0} 时放行）—— 这是保证对局**一定能结束**的关键，
     * 否则会出现「永远打不死的怪」。
     */
    WARD,

    /**
     * 主动：**我的怪打到目标**时触发。目标 = 伤害事件的受害者。
     *
     * <p>对应炒鸡的 {@code ACTIVE}（它只认受害者是玩家的情形）。
     */
    ACTIVE,

    /**
     * 被动：**目标打到我的怪**时触发。目标 = 伤害事件的攻击者。
     *
     * <p>对应炒鸡的 {@code PASSIVE}（它只认攻击者是玩家的情形）。
     * 语义上就是「反制打我的那个人」，所以放宽到 {@code LivingEntity} 后天然成立。
     */
    PASSIVE,

    /**
     * 双向：攻守两侧都触发。
     *
     * <p>对应炒鸡的 {@code DUAL}。
     */
    DUAL,

    /** 亡语：怪物死亡时触发。对应炒鸡的 {@code DEATH}。 */
    DEATH,

    /**
     * 周期型：由 {@code BattleService} 的 **5 tick 心跳**驱动，目标取 {@code entity.getTarget()}。
     *
     * <p>对应炒鸡的 {@code RANGE}。★ 注意炒鸡的 RANGE **完全不 fire 任何 Bukkit 事件**
     * （只在轮询里直接调 {@code onTrigger}，且不设 {@code triggerEvent}），
     * 所以外部拦不到它 —— 我们自己实现时就按「心跳 + 自己判冷却/概率」来做。
     */
    RANGE
}
