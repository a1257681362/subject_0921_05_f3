package com.evops;

import org.apache.shiro.spring.boot.autoconfigure.ShiroAnnotationProcessorAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = {
        // 项目只用 Shiro URL 过滤链；该自动配置注册的 DefaultAdvisorAutoProxyCreator
        // 强制 JDK 动态代理，会把 @Transactional 服务代理成接口代理导致无法按具体类注入。
        ShiroAnnotationProcessorAutoConfiguration.class
})
@MapperScan("com.evops.**.mapper")
public class EvopsApplication {
    public static void main(String[] args) {
        SpringApplication.run(EvopsApplication.class, args);
    }
}
