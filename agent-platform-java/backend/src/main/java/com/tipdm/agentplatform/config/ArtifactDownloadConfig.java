package com.tipdm.agentplatform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 会话产物下载的专用异步执行器。 */
@Configuration(proxyBeanMethods = false)
public class ArtifactDownloadConfig {

    /**
     * ZIP 打包使用独立线程池，避免占用 Tomcat 请求线程。
     *
     * @return 产物打包执行器
     */
    @Bean(name = "artifactZipExecutor")
    public AsyncTaskExecutor artifactZipExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(16);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("artifact-zip-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
