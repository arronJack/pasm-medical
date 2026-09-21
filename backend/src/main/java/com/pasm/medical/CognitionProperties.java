package com.pasm.medical;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认知服务连接配置（{@code application.yml} 里的 {@code pasm.cognition.*}）。
 *
 * <p>{@code token} 是<b>管理令牌</b>：认知接口能写记忆、改人格、触发巩固，
 * 全部落在管理作用域。它<b>不能下发到浏览器</b>，也不要写进前端构建产物。
 * 生产环境请从环境变量 / 配置中心注入（见 {@code application.yml} 的注释）。
 */
@ConfigurationProperties(prefix = "pasm.cognition")
public class CognitionProperties {

    /** 认知服务地址，例如 http://127.0.0.1:8090 */
    private String baseUrl = "http://127.0.0.1:8090";

    /** 管理令牌。留空表示开发模式（认知服务未配 token 时两边都不校验）。 */
    private String token = "";

    /** 读超时（秒）。认知检索涉及向量计算，给足但也要有上限，避免拖死业务线程。 */
    private int timeoutSeconds = 20;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
