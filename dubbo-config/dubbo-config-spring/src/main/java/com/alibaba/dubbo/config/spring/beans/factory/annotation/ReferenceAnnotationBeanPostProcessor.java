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
package com.alibaba.dubbo.config.spring.beans.factory.annotation;

import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.dubbo.config.spring.ReferenceBean;
import com.alibaba.dubbo.config.spring.ServiceBean;
import com.alibaba.dubbo.config.spring.context.event.ServiceBeanExportedEvent;
import com.alibaba.dubbo.config.spring.util.AnnotationUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.InjectionMetadata;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor} implementation
 * that Consumer service {@link Reference} annotated fields
 * <p>
 * 继承 AnnotationInjectedBeanPostProcessor 抽象类 实现 ApplicationContextAware | ApplicationListener 接口
 * 扫描 @Reference 注解的类 创建对应的 Spring BeanDefinition 对象 从而创建 Dubbo Reference Bean 对象
 * 即ReferenceAnnotationBeanPostProcessor 实现的就是 支持 @Reference 注解的属性注入
 *
 * @since 2.5.7
 */
public class ReferenceAnnotationBeanPostProcessor extends AnnotationInjectedBeanPostProcessor<Reference>
        implements ApplicationContextAware, ApplicationListener {

    /**
     * The bean name of {@link ReferenceAnnotationBeanPostProcessor}
     */
    public static final String BEAN_NAME = "referenceAnnotationBeanPostProcessor";

    /**
     * Cache size
     */
    private static final int CACHE_SIZE = Integer.getInteger(BEAN_NAME + ".cache.size", 32);

    /**
     * ReferenceBean 缓存 Map
     * KEY: Reference Bean 的名字
     */
    private final ConcurrentMap<String, ReferenceBean<?>> referenceBeanCache =
            new ConcurrentHashMap<String, ReferenceBean<?>>(CACHE_SIZE);

    /**
     * ReferenceBeanInvocationHandler 缓存 Map
     * KEY: Reference Bean 的名字
     */
    private final ConcurrentHashMap<String, ReferenceBeanInvocationHandler> localReferenceBeanInvocationHandlerCache =
            new ConcurrentHashMap<String, ReferenceBeanInvocationHandler>(CACHE_SIZE);

    /**
     * 使用属性进行注入的 @Reference Bean 的缓存 Map
     * 一般情况下 使用这个
     */
    private final ConcurrentMap<InjectionMetadata.InjectedElement, ReferenceBean<?>> injectedFieldReferenceBeanCache =
            new ConcurrentHashMap<InjectionMetadata.InjectedElement, ReferenceBean<?>>(CACHE_SIZE);

    /**
     * 使用方法进行注入的 @Reference Bean 的缓存 Map
     */
    private final ConcurrentMap<InjectionMetadata.InjectedElement, ReferenceBean<?>> injectedMethodReferenceBeanCache =
            new ConcurrentHashMap<InjectionMetadata.InjectedElement, ReferenceBean<?>>(CACHE_SIZE);

    private ApplicationContext applicationContext;

    /**
     * Gets all beans of {@link ReferenceBean}
     *
     * @return non-null read-only {@link Collection}
     * @since 2.5.9
     */
    public Collection<ReferenceBean<?>> getReferenceBeans() {
        return referenceBeanCache.values();
    }

    /**
     * Get {@link ReferenceBean} {@link Map} in injected field.
     *
     * @return non-null {@link Map}
     * @since 2.5.11
     */
    public Map<InjectionMetadata.InjectedElement, ReferenceBean<?>> getInjectedFieldReferenceBeanMap() {
        return Collections.unmodifiableMap(injectedFieldReferenceBeanCache);
    }

    /**
     * Get {@link ReferenceBean} {@link Map} in injected method.
     *
     * @return non-null {@link Map}
     * @since 2.5.11
     */
    public Map<InjectionMetadata.InjectedElement, ReferenceBean<?>> getInjectedMethodReferenceBeanMap() {
        return Collections.unmodifiableMap(injectedMethodReferenceBeanCache);
    }

    /**
     * 获得要注入的 @Reference Bean
     *
     * @param reference
     * @param bean            Current bean that will be injected
     * @param beanName        Current bean name that will be injected
     * @param injectedType    the type of injected-object
     * @param injectedElement {@link InjectionMetadata.InjectedElement}
     * @return
     * @throws Exception
     */
    @Override
    protected Object doGetInjectedBean(Reference reference, Object bean, String beanName, Class<?> injectedType,
                                       InjectionMetadata.InjectedElement injectedElement) throws Exception {

        // 获得 Reference Bean 的名字
        String referencedBeanName = buildReferencedBeanName(reference, injectedType);
        // 创建 ReferenceBean 对象
        ReferenceBean referenceBean = buildReferenceBeanIfAbsent(referencedBeanName, reference, injectedType, getClassLoader());
        // 缓存到 injectedFieldReferenceBeanCache or injectedMethodReferenceBeanCache 中
        cacheInjectedReferenceBean(referenceBean, injectedElement);
        // 创建 Proxy 代理对象
        Object proxy = buildProxy(referencedBeanName, referenceBean, injectedType);
        return proxy;
    }

    /**
     * 创建 Proxy 代理对象
     *
     * @param referencedBeanName
     * @param referenceBean
     * @param injectedType
     * @return
     */
    private Object buildProxy(String referencedBeanName, ReferenceBean referenceBean, Class<?> injectedType) {
        InvocationHandler handler = buildInvocationHandler(referencedBeanName, referenceBean);
        Object proxy = Proxy.newProxyInstance(getClassLoader(), new Class[]{injectedType}, handler);
        return proxy;
    }

    private InvocationHandler buildInvocationHandler(String referencedBeanName, ReferenceBean referenceBean) {
        // 从 localReferenceBeanInvocationHandlerCache 缓存中 获得 ReferenceBeanInvocationHandler 对象
        ReferenceBeanInvocationHandler handler = localReferenceBeanInvocationHandlerCache.get(referencedBeanName);

        if (handler == null) {  // 如果不存在 则创建 ReferenceBeanInvocationHandler 对象
            handler = new ReferenceBeanInvocationHandler(referenceBean);
        }
        //  <X> 之后根据引用的 Dubbo 服务是远程的还是本地的 做不同的处理
        // [本地]判断如果 applicationContext 中已经初始化 说明是本地的 @Service Bean 则添加到 localReferenceBeanInvocationHandlerCache 缓存中
        // 等到本地的 @Service Bean 暴露后 通过 Spring 事件监听的功能进行实现
        if (applicationContext.containsBean(referencedBeanName)) { // Is local @Service Bean or not ?
            // ReferenceBeanInvocationHandler's initialization has to wait for current local @Service Bean has been exported.
            localReferenceBeanInvocationHandlerCache.put(referencedBeanName, handler);
        } else {    // [远程]判断如果 applicationContext 中未初始化 说明是远程的 @Service Bean 对象 则立即进行初始化
            // Remote Reference Bean should initialize immediately
            handler.init();
        }

        return handler;
    }

    private static class ReferenceBeanInvocationHandler implements InvocationHandler {

        /**
         * ReferenceBean 对象
         */
        private final ReferenceBean referenceBean;

        /**
         * Bean 对象
         */
        private Object bean;

        private ReferenceBeanInvocationHandler(ReferenceBean referenceBean) {
            this.referenceBean = referenceBean;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = null;
            try {
                if (bean == null) { // If the bean is not initialized, invoke init()
                    // issue: https://github.com/apache/incubator-dubbo/issues/3429
                    init();
                }
                // 调用 bean 的对应的方法
                result = method.invoke(bean, args);
            } catch (InvocationTargetException e) {
                // re-throws the actual Exception.
                throw e.getTargetException();
            }
            return result;
        }

        /**
         * 通过初始化方法，可以获得 `ReferenceBean.ref`
         */
        private void init() {
            this.bean = referenceBean.get();
        }
    }

    @Override
    protected String buildInjectedObjectCacheKey(Reference reference, Object bean, String beanName,
                                                 Class<?> injectedType, InjectionMetadata.InjectedElement injectedElement) {

        String key = buildReferencedBeanName(reference, injectedType) +
                "#source=" + (injectedElement.getMember()) +
                "#attributes=" + AnnotationUtils.getAttributes(reference, getEnvironment(), true);

        return key;
    }

    /**
     * 获得 Reference Bean 的名字
     *
     * @param reference
     * @param injectedType
     * @return
     */
    private String buildReferencedBeanName(Reference reference, Class<?> injectedType) {

        // 创建 Service Bean 的名字
        ServiceBeanNameBuilder builder = ServiceBeanNameBuilder.create(reference, injectedType, getEnvironment());
        // 貌似重复解析占位符
        return getEnvironment().resolvePlaceholders(builder.build());
    }

    /**
     * 创建(获得) ReferenceBean 对象
     *
     * @param referencedBeanName
     * @param reference
     * @param referencedType
     * @param classLoader
     * @return
     * @throws Exception
     */
    private ReferenceBean buildReferenceBeanIfAbsent(String referencedBeanName, Reference reference,
                                                     Class<?> referencedType, ClassLoader classLoader)
            throws Exception {
        // 首先 从 referenceBeanCache 缓存中 获得 referencedBeanName 对应的 ReferenceBean 对象
        ReferenceBean<?> referenceBean = referenceBeanCache.get(referencedBeanName);

        if (referenceBean == null) {    // 如果不存在则进行创建 添加到 referenceBeanCache 缓存中
            ReferenceBeanBuilder beanBuilder = ReferenceBeanBuilder
                    .create(reference, classLoader, applicationContext)
                    .interfaceClass(referencedType);
            referenceBean = beanBuilder.build();
            referenceBeanCache.put(referencedBeanName, referenceBean);
        }

        return referenceBean;
    }

    private void cacheInjectedReferenceBean(ReferenceBean referenceBean,
                                            InjectionMetadata.InjectedElement injectedElement) {
        if (injectedElement.getMember() instanceof Field) {
            injectedFieldReferenceBeanCache.put(injectedElement, referenceBean);
        } else if (injectedElement.getMember() instanceof Method) {
            injectedMethodReferenceBeanCache.put(injectedElement, referenceBean);
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ServiceBeanExportedEvent) {
            onServiceBeanExportEvent((ServiceBeanExportedEvent) event);
        } else if (event instanceof ContextRefreshedEvent) {
            onContextRefreshedEvent((ContextRefreshedEvent) event);
        }
    }

    private void onServiceBeanExportEvent(ServiceBeanExportedEvent event) {
        // 获得 ServiceBean 对象
        ServiceBean serviceBean = event.getServiceBean();
        // 初始化对应的 ReferenceBeanInvocationHandler
        initReferenceBeanInvocationHandler(serviceBean);
    }

    private void initReferenceBeanInvocationHandler(ServiceBean serviceBean) {
        String serviceBeanName = serviceBean.getBeanName();
        // Remove ServiceBean when it's exported
        // 从 localReferenceBeanInvocationHandlerCache 缓存中移除
        ReferenceBeanInvocationHandler handler = localReferenceBeanInvocationHandlerCache.remove(serviceBeanName);
        // Initialize
        // 执行初始化
        if (handler != null) {
            handler.init();
        }
    }

    private void onContextRefreshedEvent(ContextRefreshedEvent event) {

    }

    @Override
    public void destroy() throws Exception {
        super.destroy();    // 父类销毁
        // 清空缓存
        this.referenceBeanCache.clear();
        this.localReferenceBeanInvocationHandlerCache.clear();
        this.injectedFieldReferenceBeanCache.clear();
        this.injectedMethodReferenceBeanCache.clear();
    }
}
