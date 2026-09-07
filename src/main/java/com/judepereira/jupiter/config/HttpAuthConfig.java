package com.judepereira.jupiter.config;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumSet;

@Configuration
@EnableConfigurationProperties(HttpAuthProperties.class)
public class HttpAuthConfig {

    @Bean
    FilterRegistrationBean<HttpBasicAuthFilter> httpBasicAuthFilter(HttpAuthProperties properties) {
        FilterRegistrationBean<HttpBasicAuthFilter> registration = new FilterRegistrationBean<>(new HttpBasicAuthFilter(properties));
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
        registration.setAsyncSupported(true);
        registration.setOrder(Integer.MIN_VALUE);
        return registration;
    }
}
