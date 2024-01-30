/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.spring.autoconfigure;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.rocketmq.client.AccessChannel;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQReplyListener;
import org.apache.rocketmq.spring.support.DefaultRocketMQListenerContainer;
import org.apache.rocketmq.spring.support.RocketMQMessageConverter;
import org.apache.rocketmq.spring.support.RocketMQUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.support.BeanDefinitionValidationException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

@Configuration
public class ListenerContainerConfiguration implements ApplicationContextAware {
    private final static Logger log = LoggerFactory.getLogger(ListenerContainerConfiguration.class);

    private ConfigurableApplicationContext applicationContext;

    private AtomicLong counter = new AtomicLong(0);

    private ConfigurableEnvironment environment;

    private RocketMQProperties rocketMQProperties;

    private RocketMQMessageConverter rocketMQMessageConverter;

    public ListenerContainerConfiguration(RocketMQMessageConverter rocketMQMessageConverter,
        ConfigurableEnvironment environment, RocketMQProperties rocketMQProperties) {
        this.rocketMQMessageConverter = rocketMQMessageConverter;
        this.environment = environment;
        this.rocketMQProperties = rocketMQProperties;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = (ConfigurableApplicationContext) applicationContext;
    }

    public void  registerContainer(String beanName, Object bean, RocketMQMessageListener annotation) {
        // 获取被代理的目标类
        Class<?> clazz = AopProxyUtils.ultimateTargetClass(bean);

        /**
         * 判断当前bean对象是否实现RocketMQListener接口 或者 RocketMQReplyListener接口
         * 不能同时实现两个 且必须选择一个
         *  class.isAssignableFrom：确定此class对象表示的类或接口是否与指定的class参数表示的类或接口相同，或者是否是该类或接口的超类或超接口。
         */
        if (RocketMQListener.class.isAssignableFrom(bean.getClass()) && RocketMQReplyListener.class.isAssignableFrom(bean.getClass())) {
            throw new IllegalStateException(clazz + " cannot be both instance of " + RocketMQListener.class.getName() + " and " + RocketMQReplyListener.class.getName());
        }

        if (!RocketMQListener.class.isAssignableFrom(bean.getClass()) && !RocketMQReplyListener.class.isAssignableFrom(bean.getClass())) {
            throw new IllegalStateException(clazz + " is not instance of " + RocketMQListener.class.getName() + " or " + RocketMQReplyListener.class.getName());
        }

        /**
         *  获取当前注解上参数
         *  environment.resolvePlaceholder，解析给定文本中的 ${...} 占位符，将它们替换为 {@link #getProperty} 解析的相应属性值。无法解析且没有默认值的占位符将被忽略并保持不变。
         *  @Value("${dataSource.url}") 注入级别顺序比较低，获取配置数据时，只能获取到初始化数据
         */
        String consumerGroup = this.environment.resolvePlaceholders(annotation.consumerGroup());
        String topic = this.environment.resolvePlaceholders(annotation.topic());

        /**
         * 获取rocketmq配置文件中是否配置哪些 consumer-topic 需要开关监听
         * 默认开启
         */
        boolean listenerEnabled =
            (boolean) rocketMQProperties.getConsumer().getListeners().getOrDefault(consumerGroup, Collections.EMPTY_MAP)
                .getOrDefault(topic, true);

        if (!listenerEnabled) {
            log.debug(
                "Consumer Listener (group:{},topic:{}) is not enabled by configuration, will ignore initialization.",
                consumerGroup, topic);
            return;
        }
        // 验证注解配置
        validate(annotation);

        // 容器命名
        String containerBeanName = String.format("%s_%s", DefaultRocketMQListenerContainer.class.getName(),
            counter.incrementAndGet());
        GenericApplicationContext genericApplicationContext = (GenericApplicationContext) applicationContext;

        // 注册监听容器bean
        genericApplicationContext.registerBean(containerBeanName, DefaultRocketMQListenerContainer.class,
            () -> createRocketMQListenerContainer(containerBeanName, bean, annotation));

        DefaultRocketMQListenerContainer container = genericApplicationContext.getBean(containerBeanName,
            DefaultRocketMQListenerContainer.class);

        if (!container.isRunning()) {
            try {
                container.start();
            } catch (Exception e) {
                log.error("Started container failed. {}", container, e);
                throw new RuntimeException(e);
            }
        }

        log.info("Register the listener to container, listenerBeanName:{}, containerBeanName:{}", beanName, containerBeanName);
    }

    private DefaultRocketMQListenerContainer createRocketMQListenerContainer(String name, Object bean,
        RocketMQMessageListener annotation) {
        DefaultRocketMQListenerContainer container = new DefaultRocketMQListenerContainer();

        container.setRocketMQMessageListener(annotation);

        String nameServer = environment.resolvePlaceholders(annotation.nameServer());
        nameServer = StringUtils.hasLength(nameServer) ? nameServer : rocketMQProperties.getNameServer();
        String accessChannel = environment.resolvePlaceholders(annotation.accessChannel());
        container.setNameServer(nameServer);
        if (StringUtils.hasLength(accessChannel)) {
            container.setAccessChannel(AccessChannel.valueOf(accessChannel));
        }
        container.setTopic(environment.resolvePlaceholders(annotation.topic()));
        String tags = environment.resolvePlaceholders(annotation.selectorExpression());
        if (StringUtils.hasLength(tags)) {
            container.setSelectorExpression(tags);
        }
        container.setConsumerGroup(environment.resolvePlaceholders(annotation.consumerGroup()));
        container.setTlsEnable(environment.resolvePlaceholders(annotation.tlsEnable()));
        if (RocketMQListener.class.isAssignableFrom(bean.getClass())) {
            container.setRocketMQListener((RocketMQListener) bean);
        } else if (RocketMQReplyListener.class.isAssignableFrom(bean.getClass())) {
            container.setRocketMQReplyListener((RocketMQReplyListener) bean);
        }
        container.setMessageConverter(rocketMQMessageConverter.getMessageConverter());
        container.setName(name);

        String namespace = environment.resolvePlaceholders(annotation.namespace());
        container.setNamespace(RocketMQUtil.getNamespace(namespace,
            rocketMQProperties.getConsumer().getNamespace()));
        return container;
    }

    private void validate(RocketMQMessageListener annotation) {
        if (annotation.consumeMode() == ConsumeMode.ORDERLY &&
            annotation.messageModel() == MessageModel.BROADCASTING) {
            throw new BeanDefinitionValidationException(
                "Bad annotation definition in @RocketMQMessageListener, messageModel BROADCASTING does not support ORDERLY message!");
        }
    }
}
