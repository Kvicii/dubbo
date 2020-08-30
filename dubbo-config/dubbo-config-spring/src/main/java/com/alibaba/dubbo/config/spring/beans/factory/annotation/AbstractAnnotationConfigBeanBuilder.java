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

import com.alibaba.dubbo.config.AbstractInterfaceConfig;
import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.ModuleConfig;
import com.alibaba.dubbo.config.MonitorConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.util.Assert;

import java.lang.annotation.Annotation;
import java.util.List;

import static com.alibaba.dubbo.config.spring.util.BeanFactoryUtils.getBeans;
import static com.alibaba.dubbo.config.spring.util.BeanFactoryUtils.getOptionalBean;

/**
 * Abstract Configurable {@link Annotation} Bean Builder
 * <p>
 * 泛型 A 对应 @Reference 注解 泛型 B 对应 ReferenceBean 类
 *
 * @since 2.5.7
 */
abstract class AbstractAnnotationConfigBeanBuilder<A extends Annotation, B extends AbstractInterfaceConfig> {

    protected final Log logger = LogFactory.getLog(getClass());

    /**
     * 注解
     */
    protected final A annotation;

    protected final ApplicationContext applicationContext;

    protected final ClassLoader classLoader;

    /**
     * Bean 对象
     */
    protected Object bean;

    /**
     * 接口
     */
    protected Class<?> interfaceClass;

    protected AbstractAnnotationConfigBeanBuilder(A annotation, ClassLoader classLoader,
                                                  ApplicationContext applicationContext) {
        Assert.notNull(annotation, "The Annotation must not be null!");
        Assert.notNull(classLoader, "The ClassLoader must not be null!");
        Assert.notNull(applicationContext, "The ApplicationContext must not be null!");
        this.annotation = annotation;
        this.applicationContext = applicationContext;
        this.classLoader = classLoader;

    }

    /**
     * Build {@link B}
     *
     * @return non-null
     * @throws Exception
     */
    public final B build() throws Exception {

        checkDependencies();    // 校验依赖
        B bean = doBuild(); // 执行构造 Bean 对象
        configureBean(bean);    // 配置 Bean 对象
        if (logger.isInfoEnabled()) {
            logger.info("The bean[type:" + bean.getClass().getSimpleName() + "] has been built.");
        }
        return bean;
    }

    private void checkDependencies() {

    }

    /**
     * Builds {@link B Bean}
     *
     * @return {@link B Bean}
     */
    protected abstract B doBuild();


    protected void configureBean(B bean) throws Exception {

        preConfigureBean(annotation, bean); //前置配置
        configureRegistryConfigs(bean); // 配置 RegistryConfig 属性
        configureMonitorConfig(bean);   // 配置 MonitorConfig 属性
        configureApplicationConfig(bean);   // 配置 ApplicationConfig 属性
        configureModuleConfig(bean);    // 配置 ModuleConfig 属性
        postConfigureBean(annotation, bean);    // 后置配置
    }

    protected abstract void preConfigureBean(A annotation, B bean) throws Exception;


    private void configureRegistryConfigs(B bean) {

        String[] registryConfigBeanIds = resolveRegistryConfigBeanNames(annotation);

        List<RegistryConfig> registryConfigs = getBeans(applicationContext, registryConfigBeanIds, RegistryConfig.class);

        bean.setRegistries(registryConfigs);

    }

    private void configureMonitorConfig(B bean) {

        String monitorBeanName = resolveMonitorConfigBeanName(annotation);

        MonitorConfig monitorConfig = getOptionalBean(applicationContext, monitorBeanName, MonitorConfig.class);

        bean.setMonitor(monitorConfig);

    }

    private void configureApplicationConfig(B bean) {

        String applicationConfigBeanName = resolveApplicationConfigBeanName(annotation);

        ApplicationConfig applicationConfig =
                getOptionalBean(applicationContext, applicationConfigBeanName, ApplicationConfig.class);

        bean.setApplication(applicationConfig);

    }

    private void configureModuleConfig(B bean) {

        String moduleConfigBeanName = resolveModuleConfigBeanName(annotation);

        ModuleConfig moduleConfig =
                getOptionalBean(applicationContext, moduleConfigBeanName, ModuleConfig.class);

        bean.setModule(moduleConfig);

    }

    /**
     * Resolves the bean name of {@link ModuleConfig}
     *
     * @param annotation {@link A}
     * @return
     */
    protected abstract String resolveModuleConfigBeanName(A annotation);

    /**
     * Resolves the bean name of {@link ApplicationConfig}
     *
     * @param annotation {@link A}
     * @return
     */
    protected abstract String resolveApplicationConfigBeanName(A annotation);


    /**
     * Resolves the bean ids of {@link com.alibaba.dubbo.config.RegistryConfig}
     *
     * @param annotation {@link A}
     * @return non-empty array
     */
    protected abstract String[] resolveRegistryConfigBeanNames(A annotation);

    /**
     * Resolves the bean name of {@link MonitorConfig}
     *
     * @param annotation {@link A}
     * @return
     */
    protected abstract String resolveMonitorConfigBeanName(A annotation);

    /**
     * Configures Bean
     *
     * @param annotation
     * @param bean
     */
    protected abstract void postConfigureBean(A annotation, B bean) throws Exception;


    public <T extends AbstractAnnotationConfigBeanBuilder<A, B>> T bean(Object bean) {
        this.bean = bean;
        return (T) this;
    }

    public <T extends AbstractAnnotationConfigBeanBuilder<A, B>> T interfaceClass(Class<?> interfaceClass) {
        this.interfaceClass = interfaceClass;
        return (T) this;
    }

}
