package com.hmdp;

import com.hmdp.utils.IDGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    IDGenerator idGenerator;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void generateID() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1000);
        Runnable task = new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 100; i++) {
                    idGenerator.nextID("shop");
                }
                countDownLatch.countDown();
            }
        };
        for (int i = 0; i < 1000; i++) {
            es.submit(task);
        }
        countDownLatch.await();
        System.out.println("finished");
    }

    @Test
    void testThreadPool() throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            final int taskId = i;
            es.submit(() -> {
                System.out.println("Task " + taskId + " is being executed by " + Thread.currentThread().getName());
                // 模拟任务执行
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        System.out.println("all task submitted");
    }

}
