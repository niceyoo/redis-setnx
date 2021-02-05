### 前言

平时的工作中，由于生产环境中的项目是需要部署在多台服务器中的，所以经常会面临解决分布式场景下数据一致性的问题，那么就需要引入分布式锁来解决这一问题。

针对分布式锁的实现，目前比较常用的就如下几种方案：

1. 基于数据库实现分布式锁 
2. 基于Redis实现分布式锁    【本文】
3. 基于Zookeeper实现分布式锁

接下来这个系列文章会跟大家一块探讨这三种方案，本篇为Redis实现分布式锁篇。

Redis分布式环境搭建推荐：[基于Docker的Redis集群搭建](https://www.cnblogs.com/niceyoo/p/13011626.html)

### Redis分布式锁一览

说到 redis 锁，能搜到的，或者说常用的无非就下面这两个：

- setNX  + Lua脚本            【本文】
- redisson + RLock可重入锁

接下来我们一一探索这两个的实现，本文为 setNX +  Lua脚本 实现篇。

### 1、setNX

完整语法：SET key value [EX seconds|PX milliseconds] [NX|XX] [KEEPTTL]

必选参数说明：

- SET：命令
- key：待设置的key
- value：设置的key的value，最好为随机字符串

可选参数说明：

- NX：表示key不存在时才设置，如果存在则返回 null
- XX：表示key存在时才设置，如果不存在则返回NULL

- PX millseconds：设置过期时间，过期时间精确为毫秒
- EX seconds：设置过期时间，过期时间精确为秒

> 注意：其实我们常说的通过 Redis 的 setnx 命令来实现分布式锁，并不是直接使用 Redis 的 setnx 命令，因为在老版本之前 setnx 命令语法为「setnx key value」，并不支持同时设置过期时间的操作，那么就需要再执行 expire 过期时间的命令，这样的话加锁就成了两个命令，原子性就得不到保障，所以通常需要配合 Lua 脚本使用，而从 Redis 2.6.12 版本后，set 命令开始整合了 setex 的功能，并且 set 本身就已经包含了设置过期时间，因此常说的 setnx 命令实则只用 set 命令就可以实现了，只是参数上加上了 NX 等参数。

大致说一下用 setnx 命令实现分布式锁的流程：

在 Redis 2.6.12 版本之后，Redis 支持原子命令加锁，我们可以通过向 Redis 发送 ``「set key value NX 过期时间」`` 命令，实现原子的加锁操作。比如某个客户端想要获取一个 key 为 niceyoo 的锁，此时需要执行 ``「set niceyoo random_value NX PX 30000」`` ，在这我们设置了 30 秒的锁自动过期时间，超过 30 秒自动释放。

如果 setnx 命令返回 ok，说明拿到了锁，此时我们就可以做一些业务逻辑处理，业务处理完之后，需要释放锁，释放锁一般就是执行 Redis 的 del 删除指令，「del niceyoo」

如果 setnx 命令返回 nil，说明拿锁失败，被其他线程占用，如下是模拟截图：

![image-20200920003133502](https://gitee.com/niceyoo/blog/raw/master/img/image-20200920003133502.png)

注意，这里在设置值的时候，value 应该是随机字符串，比如 UUID，而不是随便用一个固定的字符串进去，为什么这样做呢？

value 的值设置为随机数主要是为了更安全的释放锁，释放锁的时候需要检查 key 是否存在，且 key 对应的 value 值是否和指定的值一样，是一样的才能释放锁。

感觉这样说还是不清晰，举个例子：例如进程 A，通过 setnx 指令获取锁成功（命令中设置了加锁自动过期时间30 秒），既然拿到锁了就开始执行业务吧，但是进程 A 在接下来的执行业务逻辑期间，程序响应时间竟然超过30秒了，锁自动释放了，而此时进程 B 进来了，由于进程 A 设置的过期时间一到，让进程 B 拿到锁了，然后进程 B 又开始执行业务逻辑，但是呢，这时候进程 A 突然又回来了，然后把进程 B 的锁得释放了，然后进程 C 又拿到锁，然后开始执行业务逻辑，此时进程 B 又回来了，释放了进程 C 的锁，套娃开始了.....

总之，有了随机数的 value 后，可以通过判断 key 对应的 value 值是否和指定的值一样，是一样的才能释放锁。

接下来我们把 setnx 命令落地到项目实例中：

代码环境：``SpringBoot2.2.2.RELEASE`` + ``spring-boot-starter-data-redis`` + ``StringRedisTemplate``

StringRedisTemplate 或者 RedisTemplate 下对应的 setnx 指令的 API 方法如下：

```
/**
 * Set {@code key} to hold the string {@code value} if {@code key} is absent.
 *
 * @param key must not be {@literal null}.
 * @param value
 * @see <a href="http://redis.io/commands/setnx">Redis Documentation: SETNX</a>
 */
Boolean setIfAbsent(K key, V value);
```

> 这个地方再补充一下，使用 jedis 跟使用 StringRedisTemplate 对应的 senx 命令的写法是有区别的，jedis 下就是 set 方法，而 StringRedisTemplate 下使用的是 setIfAbsent 方法 。

##### 1）Maven 依赖，pom.xml

```
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>2.2.2.RELEASE</version>
        <relativePath/> 
    </parent>
    <groupId>com.example</groupId>
    <artifactId>demo-redis</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>demo-redis</name>
    <description>Demo project for Spring Boot</description>

    <properties>
        <java.version>1.8</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Redis-->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>1.18.10</version>
        </dependency>

        <!-- Gson -->
        <dependency>
            <groupId>com.google.code.gson</groupId>
            <artifactId>gson</artifactId>
            <version>2.8.6</version>
        </dependency>

    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>

</project>
```

这里引入了 gson、redis 依赖。

##### 2）application.yml 配置文件

```
server:
  port: 6666
  servlet:
    context-path: /

spring:
  redis:
    host: 127.0.0.1
    password:
    # 数据库索引 默认0
    database: 0
    port: 6379
    # 超时时间 Duration类型 3秒
    timeout: 3S

# 日志
logging:
  # 输出级别
  level:
    root: info
  file:
    # 指定路径
    path: redis-logs
    # 最大保存天数
    max-history: 7
    # 每个文件最大大小
    max-size: 5MB
```

这里设置的服务端口为 6666，大家可以根据自己环境修改。

##### 3）测试的 Controller

```
@Slf4j
@RestController
@RequestMapping("/test")
public class TestController {

    @Resource
    private RedisTemplate<String,Object> redisTemplate;

    @PostMapping(value = "/addUser")
    public String createOrder(User user) {

        String key = user.getUsername();
        // 如下为使用UUID、固定字符串，固定字符串容易出现线程不安全
        String value = UUID.randomUUID().toString().replace("-","");
        // String value = "123";
        /*
         * setIfAbsent <=> SET key value [NX] [XX] [EX <seconds>] [PX [millseconds]]
         * set expire time 5 mins
         */
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, value, 20000, TimeUnit.MILLISECONDS);
        if (flag) {
            log.info("{} 锁定成功，开始处理业务", key);
            try {
                // 模拟处理业务逻辑
                Thread.sleep(1000 * 30);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            // 判断是否是key对应的value
            String lockValue = (String) redisTemplate.opsForValue();
            if (lockValue != null && lockValue.equals(value)) {
            	redisTemplate.delete(key);
            	log.info("{} 解锁成功，结束处理业务", key);
            }
            return "SUCCESS";
        } else {
            log.info("{} 获取锁失败", key);
            return "请稍后再试...";
        }
    }

}
```

大致流程就是，通过 RedisTemplate 的 setIfAbsent() 方法获取原子锁，并设置了锁自动过期时间为 20秒，setIfAbsent() 方法返回 true，表示加锁成功，加锁成功后模拟了一段业务逻辑处理，耗时30秒，执行完逻辑之后调用 delete() 方法释放锁。

问题来了，由于锁自动过期时间为 20秒，而业务逻辑耗时为 30秒，在不使用 random_value（随机字符串）下，如果有多进程操作的话就会出现前面提到的套娃骚操作......

所以在删除锁之前，我们先再次通过 get 命令获取加锁 key 的 value 值，然后判断 value 跟加锁时设置的 value 是否一致，这就看出 UUID 的重要性了，如果一致，就执行 delete() 方法释放锁，否则不执行。

如下是使用「固定字符串」模拟的问题截图：

![image-20200920011841816](https://gitee.com/niceyoo/blog/raw/master/img/image-20200920011841816.png)

两次加锁成功的时间间隔为11秒，不足20秒，显然不是一个进程的用户。

![image-20200920012013118](https://gitee.com/niceyoo/blog/raw/master/img/image-20200920012013118.png)

而在 value 使用 UUID 随机字符串时没有出现上述问题。

但随机字符串就真的安全了吗？

不安全...

因为还是无法保证 ``redisTemplate.delete(key);`` 的原子操作，在多进程下还是会有进程安全问题。

就有小伙伴可能钻牛角尖，怎么就不能原子性操作了，你在删除之前不都已经判断了吗？

再举个例子，比如进程 A 执行完业务逻辑，在 ``redisTemplate.opsForValue().get(key);`` 获得 key 这一步执行没问题，同时也进入了 if 判断中，但是恰好这时候进程 A 的锁自动过期时间到了（别问为啥，就是这么巧），而另一个进程 B 获得锁成功，然后还没来得及执行，进程 A 就执行了 delete(key) ，释放了进程 B 的锁.... 

我操？那你上边巴拉巴拉那么多，说啥呢？

咳咳，解锁正确删除锁的方式之一：为了保障原子性，我们需要用 Lua 脚本进行完美解锁。

### Lua脚本

可能有小伙伴不熟悉 Lua，先简单介绍一下 Lua 脚本：

Lua 是一种轻量小巧的脚本语言，用标准 C 语言编写并以源代码形式开放， 其设计目的是为了嵌入应用程序中，从而为应用程序提供灵活的扩展和定制功能。

Lua 提供了交互式编程模式。我们可以在命令行中输入程序并立即查看效果。

lua脚本优点：

- 减少网络开销：原先多次请求的逻辑放在 redis 服务器上完成。使用脚本，减少了网络往返时延
- 原子操作：Redis会将整个脚本作为一个整体执行，中间不会被其他命令插入（想象为事务）
- 复用：客户端发送的脚本会永久存储在Redis中，意味着其他客户端可以复用这一脚本而不需要使用代码完成同样的逻辑

先大致了解一下，后面我会单独写一篇 Lua 从入门到放弃的文章。。

如下是Lua脚本，通过 Redis 的 eval/evalsha 命令来运行：

```
-- lua删除锁：
-- KEYS和ARGV分别是以集合方式传入的参数，对应上文的Test和uuid。
-- 如果对应的value等于传入的uuid。
if redis.call('get', KEYS[1]) == ARGV[1] 
    then 
	-- 执行删除操作
        return redis.call('del', KEYS[1]) 
    else 
	-- 不成功，返回0
        return 0 
end
```

好了，看到 Lua 脚本了，然后代码中如何使用？

##### 为了让大家更清楚，我们在 SpringBoot 中使用这个 Lua 脚本

##### 1）在 resources 文件下创建 niceyoo.lua 文件

![image-20200920123914568](https://gitee.com/niceyoo/blog/raw/master/img/image-20200920123914568.png)

文件内容如下：

```
if redis.call('get', KEYS[1]) == ARGV[1]
    then
        return redis.call('del', KEYS[1])
    else
        return 0
end
```

##### 2）修改 TestController

在 SpringBoot中，是使用 DefaultRedisScript 类来加载脚本的，并设置相应的数据类型来接收 Lua 脚本返回的数据，这个泛型类在使用时设置泛型是什么类型，脚本返回的结果就是用什么类型接收。

```
@Slf4j
@RestController
@RequestMapping("/test")
public class TestController {

    @Resource
    private RedisTemplate<String,Object> redisTemplate;

    private DefaultRedisScript<Long> script;

    @PostConstruct
    public void init(){
        script = new DefaultRedisScript<Long>();
        script.setResultType(Long.class);
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("niceyoo.lua")));
    }

    @PostMapping(value = "/addUser")
    public String createOrder(User user) {

        String key = user.getUsername();
        String value = UUID.randomUUID().toString().replace("-","");

        /*
         * setIfAbsent <=> SET key value [NX] [XX] [EX <seconds>] [PX [millseconds]]
         * set expire time 5 mins
         */
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, value, 20000, TimeUnit.MILLISECONDS);
        if (flag) {
            log.info("{} 锁定成功，开始处理业务", key);
            try {
                // 模拟处理业务逻辑
                Thread.sleep(1000 * 10);

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
            return "SUCCESS";
        } else {
            log.info("{} 获取锁失败", key);
            return "请稍后再试...";
        }
    }

}
```

##### 3）测试结果

Lua 脚本替换 RedisTemplate 执行 delete() 方法，测试结果如下：

![image-20200920124635965](https://gitee.com/niceyoo/blog/raw/master/img/image-20200920124635965.png)

### 最后总结

1、所谓的 setnx 命令来实现分布式锁，其实不是直接使用 Redis 的 setnx 命令，因为 setnx 不支持设置自动释放锁的时间（至于为什么要设置自动释放锁，是因为防止被某个进程不释放锁而造成死锁的情况），不支持设置过期时间，就得分两步命令进行操作，一步是 ``setnx key value``，一步是设置过期时间，这种情况的弊端很显然，无原子性操作。

2、 Redis 2.6.12 版本后，set 命令开始整合了 setex 的功能，并且 set 本身就已经包含了设置过期时间，因此常说的 setnx 命令实则只用 set 命令就可以实现了，只是参数上加上了 NX 等参数。

3、经过分析，在使用 ``set key value nx px xxx`` 命令时，value 最好是随机字符串，这样可以防止业务代码执行时间超过设置的锁自动过期时间，而导致再次释放锁时出现释放其他进程锁的情况（套娃）

4、尽管使用随机字符串的 value，但是在释放锁时（delete方法），还是无法做到原子操作，比如进程 A 执行完业务逻辑，在准备释放锁时，恰好这时候进程 A 的锁自动过期时间到了，而另一个进程 B 获得锁成功，然后 B 还没来得及执行，进程 A 就执行了 delete(key) ，释放了进程 B 的锁.... ，因此需要配合 Lua 脚本释放锁，文章也给出了 SpringBoot 的使用示例。

至此，带大家一块查看了 setnx 命令如何实现分布式锁，但是下面还是要泼一下冷水...

经过测试，在单机 Redis 模式下，这种分布式锁，简直是无敌（求生欲：纯个人看法），咳咳，没错，你没看错，单机下的 Redis 无敌...

所以在那些主从模式、哨兵模式、或者是 cluster 模式下，可能会出现问题，出现什么问题呢？

##### setNX 的缺陷

setnx 琐最大的缺点就是它加锁时只作用在一个 Redis 节点上，即使 Redis 通过 Sentinel(哨岗、哨兵) 保证高可用，如果这个 master 节点由于某些原因发生了主从切换，那么就会出现锁丢失的情况，下面是个例子：

1. 在 Redis 的 master 节点上拿到了锁；
2. 但是这个加锁的 key 还没有同步到 slave 节点；
3. master 故障，发生故障转移，slave 节点升级为 master节点；
4. 上边 master 节点上的锁丢失。

有的时候甚至不单单是锁丢失这么简单，新选出来的 master 节点可以重新获取同样的锁，出现一把锁被拿两次的场景。

锁被拿两次，也就不能满足安全性了...

尽管单机 Redis 下并不会出现如上问题，但毕竟我们在生产环境中，一般都是采用的集群模式，所以这本身也是 Redis 分布式锁的诟病。

缺陷看完了，怎么解决嘛~

然后 Redis 的作者就提出了著名远洋的 RedLock 算法... 

下节讲。

---

在写这篇文章过程中，本来计划将 Redis 里的 setnx、redisson、redLock 一块写出来发一篇文章；

但由于文章中贴了一些代码片段，会让文章整体的节奏偏长，不适用于后面自己的复习，所以拆分成两篇文章，

下一篇我们一块探索 Redisson + RedLock 的分布式锁的实现。

### 2、Redisson + RedLock

跳转链接：[https://www.cnblogs.com/niceyoo/p/13736140.html](https://www.cnblogs.com/niceyoo/p/13736140.html)

> 博客园持续更新，订阅关注，未来，我们一起成长。

