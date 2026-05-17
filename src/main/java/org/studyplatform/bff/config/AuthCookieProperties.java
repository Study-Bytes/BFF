package org.studyplatform.bff.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bff.auth.cookies")
public class AuthCookieProperties {
    private String accessName = "access_token";
    private String refreshName = "refresh_token";
    private boolean secure = true;
    private String sameSite = "Lax";
    private String path = "/";
    private String domain;
    private Long accessMaxAgeSeconds;
    private Long refreshMaxAgeSeconds;

    public String getAccessName() {
        return accessName;
    }

    public void setAccessName(String accessName) {
        this.accessName = accessName;
    }

    public String getRefreshName() {
        return refreshName;
    }

    public void setRefreshName(String refreshName) {
        this.refreshName = refreshName;
    }

    public boolean isSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public String getSameSite() {
        return sameSite;
    }

    public void setSameSite(String sameSite) {
        this.sameSite = sameSite;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public Long getAccessMaxAgeSeconds() {
        return accessMaxAgeSeconds;
    }

    public void setAccessMaxAgeSeconds(Long accessMaxAgeSeconds) {
        this.accessMaxAgeSeconds = accessMaxAgeSeconds;
    }

    public Long getRefreshMaxAgeSeconds() {
        return refreshMaxAgeSeconds;
    }

    public void setRefreshMaxAgeSeconds(Long refreshMaxAgeSeconds) {
        this.refreshMaxAgeSeconds = refreshMaxAgeSeconds;
    }
}
