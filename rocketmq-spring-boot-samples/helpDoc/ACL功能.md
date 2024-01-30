Producer 端要想使用 ACL 功能，需要多配置两个配置项:

```properties
## application.properties
rocketmq.name-server=127.0.0.1:9876
rocketmq.producer.group=my-group

rocketmq.producer.access-key=AK
rocketmq.producer.secret-key=SK
```
Consumer 端 ACL 功能需要在 `@RocketMQMessageListener` 中进行配置

```
@Service
@RocketMQMessageListener(
    topic = "test-topic-1", 
    consumerGroup = "my-consumer_test-topic-1",
    accessKey = "AK",
    secretKey = "SK"
)
public class MyConsumer implements RocketMQListener<String> {
    ...
}
```

> 注意:
> 
> 可以不用为每个 `@RocketMQMessageListener` 注解配置 AK/SK，在配置文件中配置 `rocketmq.consumer.access-key` 和 `rocketmq.consumer.secret-key` 配置项，这两个配置项的值就是默认值