package io.mczju.infernalguess.battle;

/** 对战的双方。 */
public enum BattleSide {

    LEFT("left", "<red>左</red>"),
    RIGHT("right", "<blue>右</blue>");

    private final String id;
    private final String display;

    BattleSide(String id, String display) {
        this.id = id;
        this.display = display;
    }

    /** 稳定标识（写入 PDC / 队伍名）。 */
    public String id() {
        return id;
    }

    /** 默认显示名（MiniMessage；实际展示走 display.yml）。 */
    public String display() {
        return display;
    }

    public BattleSide opposite() {
        return this == LEFT ? RIGHT : LEFT;
    }

    public static BattleSide byId(String id) {
        return RIGHT.id.equalsIgnoreCase(id) ? RIGHT : LEFT;
    }
}
