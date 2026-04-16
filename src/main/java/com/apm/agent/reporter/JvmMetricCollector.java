package com.apm.agent.reporter;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Method;
import java.lang.management.OperatingSystemMXBean;
import com.apm.agent.util.Logger;

/**
 * 주기적으로 JVM 상태(CPU, 메모리, 스레드 등)를 수집하여 AgentDataSender를 통해 전송합니다.
 */
public class JvmMetricCollector {

    private final AgentDataSender dataSender;
    private final ScheduledExecutorService scheduler;
    private final OperatingSystemMXBean osBean;

    // CPU 메트릭 조회 메서드를 생성자에서 한 번만 조회하여 캐싱
    private final Method processCpuLoadMethod;
    private final Method systemCpuLoadMethod;

    public JvmMetricCollector(AgentDataSender dataSender, int intervalSeconds) {
        this.dataSender = dataSender;
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
        this.processCpuLoadMethod = resolveCpuMethod("getProcessCpuLoad");
        this.systemCpuLoadMethod = resolveCpuMethod("getSystemCpuLoad", "getCpuLoad");

        // 애플리케이션 수명 주기를 방해하지 않도록 데몬 스레드 사용
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "LxAgent-JvmCollector-Thread");
                t.setDaemon(true);
                return t;
            }
        });

        this.scheduler.scheduleAtFixedRate(this::collectAndSend, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private void collectAndSend() {
        try {
            long timestamp = System.currentTimeMillis();

            // CPU 메트릭 수집 (Reflection 활용하여 Sun JDK 의존성 우회)
            double processCpuLoad = getProcessCpuLoad();
            double systemCpuLoad = getSystemCpuLoad();

            // 메모리 메트릭
            MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
            long heapUsed = heapUsage.getUsed();
            long heapMax = heapUsage.getMax();
            long heapCommitted = heapUsage.getCommitted();
            double heapUsagePercent = heapMax > 0 ? (double) heapUsed / heapMax * 100.0 : 0.0;

            // GC 수집
            List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
            long gcCount = 0;
            long gcTime = 0;
            for (GarbageCollectorMXBean gcBean : gcBeans) {
                long count = gcBean.getCollectionCount();
                if (count != -1)
                    gcCount += count;
                long time = gcBean.getCollectionTime();
                if (time != -1)
                    gcTime += time;
            }

            // 스레드 상태
            ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
            int liveThreads = threadMXBean.getThreadCount();
            long[] deadlockedThreadIds = threadMXBean.findDeadlockedThreads();
            int deadlockedThreads = deadlockedThreadIds == null ? 0 : deadlockedThreadIds.length;

            // 최적화된 JSON 문자열 생성 (객체 생성 최소화)
            StringBuilder sb = new StringBuilder(256);
            sb.append("{")
                    .append("\"type\":\"JVM\",")
                    .append("\"timestamp\":").append(timestamp).append(",")
                    .append("\"processCpuLoad\":").append(formatDouble(processCpuLoad)).append(",")
                    .append("\"systemCpuLoad\":").append(formatDouble(systemCpuLoad)).append(",")
                    .append("\"heapUsed\":").append(heapUsed).append(",")
                    .append("\"heapMax\":").append(heapMax).append(",")
                    .append("\"heapCommitted\":").append(heapCommitted).append(",")
                    .append("\"heapUsagePercent\":").append(formatDouble(heapUsagePercent)).append(",")
                    .append("\"gcCount\":").append(gcCount).append(",")
                    .append("\"gcTime\":").append(gcTime).append(",")
                    .append("\"liveThreads\":").append(liveThreads).append(",")
                    .append("\"deadlockedThreads\":").append(deadlockedThreads)
                    .append("}");

            dataSender.addMetric(sb.toString());

        } catch (Exception e) {
            Logger.warn("JVM 메트릭 수집 중 오류 발생: " + e.getMessage());
        }
    }

    /** 메서드 이름 후보 목록 중 처음으로 찾은 메서드를 반환. 없으면 null. */
    private Method resolveCpuMethod(String... names) {
        Class<?> sunOsBeanClass = null;
        try {
            sunOsBeanClass = Class.forName("com.sun.management.OperatingSystemMXBean");
        } catch (ClassNotFoundException ignored) {
        }
        
        boolean isSunOsBean = sunOsBeanClass != null && sunOsBeanClass.isInstance(osBean);

        for (String name : names) {
            if (isSunOsBean) {
                try {
                    return sunOsBeanClass.getMethod(name);
                } catch (NoSuchMethodException ignored) {
                }
            }
            
            // Fallback (Sun JDK가 아니거나 접근 불가능한 경우)
            try {
                Method m = osBean.getClass().getMethod(name);
                try {
                    m.setAccessible(true);
                } catch (Exception e) {
                    // Java 16+ 강력한 캡슐화로 인한 InaccessibleObjectException 등은 무시
                }
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private double getProcessCpuLoad() {
        return invokeCpuMethod(processCpuLoadMethod);
    }

    private double getSystemCpuLoad() {
        return invokeCpuMethod(systemCpuLoadMethod);
    }

    private double invokeCpuMethod(Method method) {
        if (method == null) return 0.0;
        try {
            double val = (Double) method.invoke(osBean);
            return val < 0 ? 0.0 : val;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String formatDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value))
            return "0.00";
        return String.format(java.util.Locale.US, "%.2f", value);
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }
}
