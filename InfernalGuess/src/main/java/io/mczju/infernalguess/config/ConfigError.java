package io.mczju.infernalguess.config;

/**
 * 配置加载/校验失败。
 *
 * <p>项目铁律：**加载期校验**，缺失或类型错误的配置项必须立即报错并指出
 * 「文件 + 键路径」，而不是静默取默认值。
 * （教训来自 Maggoteers：其 {@code points.yml} 的 {@code getDouble} 对缺失键返回 {@code 0.0}，
 * 键名写错只会在世界原点刷怪，排查成本极高。）
 */
public final class ConfigError extends RuntimeException {

    private final String file;
    private final String path;

    public ConfigError(String file, String path, String reason) {
        super("[" + file + "] 配置项 `" + path + "` " + reason);
        this.file = file;
        this.path = path;
    }

    public ConfigError(String file, String path, String reason, Throwable cause) {
        super("[" + file + "] 配置项 `" + path + "` " + reason, cause);
        this.file = file;
        this.path = path;
    }

    public String file() {
        return file;
    }

    public String path() {
        return path;
    }
}
