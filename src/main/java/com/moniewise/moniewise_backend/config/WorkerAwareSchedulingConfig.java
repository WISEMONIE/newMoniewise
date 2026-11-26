package com.moniewise.moniewise_backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.Date;
import java.util.concurrent.ScheduledFuture;

@Configuration
public class WorkerAwareSchedulingConfig implements SchedulingConfigurer {

    private final RuntimeRoleService runtimeRoleService;

    @Value("${moniewise.scheduler.pool-size:2}")
    private int schedulerPoolSize;

    public WorkerAwareSchedulingConfig(RuntimeRoleService runtimeRoleService) {
        this.runtimeRoleService = runtimeRoleService;
    }

    @Bean(name = "moniewiseScheduledTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler moniewiseScheduledTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, Math.min(schedulerPoolSize, 8)));
        scheduler.setThreadNamePrefix("moniewise-worker-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setTaskScheduler(new WorkerRoleTaskScheduler(
                moniewiseScheduledTaskScheduler(),
                runtimeRoleService));
    }

    private static final class WorkerRoleTaskScheduler implements TaskScheduler {

        private final TaskScheduler delegate;
        private final RuntimeRoleService runtimeRoleService;

        private WorkerRoleTaskScheduler(TaskScheduler delegate, RuntimeRoleService runtimeRoleService) {
            this.delegate = delegate;
            this.runtimeRoleService = runtimeRoleService;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return delegate.schedule(workerOnly(task), trigger);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Date startTime) {
            return delegate.schedule(workerOnly(task), startTime);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Date startTime, long period) {
            return delegate.scheduleAtFixedRate(workerOnly(task), startTime, period);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long period) {
            return delegate.scheduleAtFixedRate(workerOnly(task), period);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Date startTime, long delay) {
            return delegate.scheduleWithFixedDelay(workerOnly(task), startTime, delay);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long delay) {
            return delegate.scheduleWithFixedDelay(workerOnly(task), delay);
        }

        private Runnable workerOnly(Runnable task) {
            return () -> {
                if (runtimeRoleService.workerJobsEnabled()) {
                    task.run();
                }
            };
        }
    }
}
