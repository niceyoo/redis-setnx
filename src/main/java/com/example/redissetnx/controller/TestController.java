package com.example.redissetnx.controller;

import com.example.redissetnx.bean.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/test")
public class TestController {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    private DefaultRedisScript<Long> script;

    @PostConstruct
    public void init() {
        script = new DefaultRedisScript<Long>();
        script.setResultType(Long.class);
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("niceyoo.lua")));
    }

    @PostMapping(value = "/addUser")
    public String createOrder(User user) {

        String key = user.getUsername();
        String value = UUID.randomUUID().toString().replace("-", "");

        /*
         * setIfAbsent <=> SET key value [NX] [XX] [EX <seconds>] [PX [millseconds]]
         * set expire time 20 s
         */
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, value, 10000, TimeUnit.MILLISECONDS);
        if (flag != null && flag) {
            log.info("{} 锁定成功，开始处理业务", key);
            try {
                // 模拟处理业务逻辑，10秒
                Thread.sleep(1000 * 15);

            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            // 业务逻辑处理完毕，释放锁
            String lockValue = (String) redisTemplate.opsForValue().get(key);
            if (lockValue != null && lockValue.equals(value)) {
                System.out.println("lockValue========：" + lockValue);
                List<String> keys = new ArrayList<>();
                keys.add(key);
                Long execute = redisTemplate.execute(script, keys, lockValue);
                System.out.println("execute执行结果，1表示执行del，0表示未执行 ===== " + execute);
                log.info("{} 解锁成功，结束处理业务", key);
            }
            return "SUCCESS";
        } else {
            log.info("{} 获取锁失败", key);
            return "请稍后再试...";
        }

    }

    /**
     * 定时生成综合考评报表
     */
//    @Scheduled(cron = "0/60 * * * * ?")
    @Scheduled(cron = "0 47 23 * * ?")
    public void test(){
        //System.out.println(LocalDateTime.now());

        String key = "test";
        String value = UUID.randomUUID().toString().replace("-", "");

        /*
         * setIfAbsent <=> SET key value [NX] [XX] [EX <seconds>] [PX [millseconds]]
         * set expire time 5 mins
         */
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, value, 10, TimeUnit.MINUTES);
        if (flag != null && flag) {
            log.info("{} 锁定成功，开始处理业务", key);
            try {
                // 模拟处理业务逻辑
                Thread.sleep(1000 * 30);

            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            // 业务逻辑处理完毕，释放锁
            String lockValue = redisTemplate.opsForValue().get(key).toString();
            if (lockValue != null && lockValue.equals(value)) {
                System.out.println("lockValue========：" + lockValue);
                List<String> keys = new ArrayList<>();
                keys.add(key);
                Long execute = redisTemplate.execute(script, keys, lockValue);
                System.out.println("execute执行结果，1表示执行del，0表示未执行 ===== " + execute);
                log.info("{} 解锁成功，结束处理业务", key);
            }

        } else {
            log.info("{} 获取锁失败", key);
        }

    }

}
