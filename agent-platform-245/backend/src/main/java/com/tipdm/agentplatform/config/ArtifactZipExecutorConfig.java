package com.tipdm.agentplatform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 产物打包专用线程池。
 *
 * <p><strong>为什么不共用 Tomcat 请求线程</strong>：打包同时吃 CPU（Deflater）与磁盘 IO
 * （递归扫盘 + 逐文件读）。跑在同步请求线程上会拖住普通接口——而同一时刻
 * 这个线程池还在服务 SSE 与前端 API，用户感知是「整个页面变卡」。</p>
 *
 * <p><strong>为什么不用 {@code spring.mvc.async} 全局超时</strong>：SSE 需要 2 小时
 * （见 {@code agentscope.agui.sse-timeout}），产物下载只需数分钟。全局一刀切会让两者
 * 互相破坏——放宽则下载失去超时保护，收紧则直接搞坏 SSE。因此超时由端点侧的
 * {@code WebAsyncTask} 单独指定，线程池也独立。</p>
 */
@Configuration
public class ArtifactZipExecutorConfig {

    /**
     * 打包线程池 bean 名，端点侧按名注入。
     *
     * <p>返回类型声明为 {@link AsyncTaskExecutor}（而非更宽的 {@code TaskExecutor}）：
     * {@code WebAsyncTask} 的执行器形参要的是前者，声明窄类型才能直接注入。</p>
     *
     * <p>队列容量刻意设小（8）：排队超过这个数量说明并发下载已经压过磁盘吞吐，
     * 继续排队只会让所有请求一起超时，不如让新请求立刻拿到明确错误。</p>
     */
    @Bean("artifactZipExecutor")
    public AsyncTaskExecutor artifactZipExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("artifact-zip-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // 与 spring.lifecycle.timeout-per-shutdown-phase 对齐：
        // 优雅关停时让在途打包写完，避免客户端拿到半截 zip
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
