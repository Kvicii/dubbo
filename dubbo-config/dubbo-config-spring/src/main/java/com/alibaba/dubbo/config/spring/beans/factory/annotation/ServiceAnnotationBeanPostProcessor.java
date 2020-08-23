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

import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.config.MethodConfig;
import com.alibaba.dubbo.config.annotation.Method;
import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.dubbo.config.spring.ServiceBean;
import com.alibaba.dubbo.config.spring.context.annotation.DubboClassPathBeanDefinitionScanner;
import org.springframework.beans.BeansException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.alibaba.dubbo.config.spring.util.ObjectUtils.of;
import static org.springframework.beans.factory.support.BeanDefinitionBuilder.rootBeanDefinition;
import static org.springframework.context.annotation.AnnotationConfigUtils.CONFIGURATION_BEAN_NAME_GENERATOR;
import static org.springframework.core.annotation.AnnotationUtils.findAnnotation;
import static org.springframework.util.ClassUtils.resolveClassName;

/**
 * {@link Service} Annotation
 * {@link BeanDefinitionRegistryPostProcessor Bean Definition Registry Post Processor}
 *
 * @since 2.5.8
 */
public class ServiceAnnotationBeanPostProcessor implements BeanDefinitionRegistryPostProcessor, EnvironmentAware,
        ResourceLoaderAware, BeanClassLoaderAware {


    private final Logger logger = LoggerFactory.getLogger(getClass());

    // 要扫描的包的集合
    private final Set<String> packagesToScan;

    private Environment environment;

    private ResourceLoader resourceLoader;

    private ClassLoader classLoader;

    public ServiceAnnotationBeanPostProcessor(String... packagesToScan) {
        this(Arrays.asList(packagesToScan));
    }

    public ServiceAnnotationBeanPostProcessor(Collection<String> packagesToScan) {
        this(new LinkedHashSet<String>(packagesToScan));
    }

    public ServiceAnnotationBeanPostProcessor(Set<String> packagesToScan) {
        this.packagesToScan = packagesToScan;
    }

    /**
     * 扫描 @Service 注解的类 创建对应的 Spring BeanDefinition 对象 从而创建 Dubbo Service Bean 对象
     *
     * @param registry
     * @throws BeansException
     */
    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        // 解析 packagesToScan 集合 因为可能存在占位符
        Set<String> resolvedPackagesToScan = resolvePackagesToScan(packagesToScan);
        // 扫描 packagesToScan 包 创建对应的 Spring BeanDefinition 对象 从而创建 Dubbo Service Bean 对象
        if (!CollectionUtils.isEmpty(resolvedPackagesToScan)) {
            registerServiceBeans(resolvedPackagesToScan, registry);
        } else {
            if (logger.isWarnEnabled()) {
                logger.warn("packagesToScan is empty , ServiceBean registry will be ignored!");
            }
        }

    }


    /**
     * Registers Beans whose classes was annotated {@link Service}
     * 扫描 packagesToScan 包
     * 创建对应的 Spring BeanDefinition 对象 从而创建 Dubbo Service Bean 对象
     *
     * @param packagesToScan The base packages to scan
     * @param registry       {@link BeanDefinitionRegistry}
     */
    private void registerServiceBeans(Set<String> packagesToScan, BeanDefinitionRegistry registry) {

        // 创建 DubboClassPathBeanDefinitionScanner 对象
        // DubboClassPathBeanDefinitionScanner 用于扫描指定包下符合条件的类 用于将每个符合条件的类 创建对应的 BeanDefinition 对象 从而创建 Bean
        DubboClassPathBeanDefinitionScanner scanner =
                new DubboClassPathBeanDefinitionScanner(registry, environment, resourceLoader);
        // 获得 BeanNameGenerator 对象 并设置 beanNameGenerator 到 scanner 中
        BeanNameGenerator beanNameGenerator = resolveBeanNameGenerator(registry);
        scanner.setBeanNameGenerator(beanNameGenerator);
        // 设置过滤获得带有 @Service 注解的类
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));

        for (String packageToScan : packagesToScan) {   // 遍历 packagesToScan 数组

            // Registers @Service Bean first
            // 执行扫描
            scanner.scan(packageToScan);

            // Finds all BeanDefinitionHolders of @Service whether @ComponentScan scans or not.
            // 创建每个在 packageToScan 扫描到的类 对应的 BeanDefinitionHolder 对象 返回 BeanDefinitionHolder 集合
            Set<BeanDefinitionHolder> beanDefinitionHolders =
                    findServiceBeanDefinitionHolders(scanner, packageToScan, registry, beanNameGenerator);
            if (!CollectionUtils.isEmpty(beanDefinitionHolders)) {
                for (BeanDefinitionHolder beanDefinitionHolder : beanDefinitionHolders) {
                    registerServiceBean(beanDefinitionHolder, registry, scanner);   // 注册到 registry 中
                }

                if (logger.isInfoEnabled()) {
                    logger.info(beanDefinitionHolders.size() + " annotated Dubbo's @Service Components { " +
                            beanDefinitionHolders +
                            " } were scanned under package[" + packageToScan + "]");
                }
            } else {
                if (logger.isWarnEnabled()) {
                    logger.warn("No Spring Bean annotating Dubbo's @Service was found under package["
                            + packageToScan + "]");
                }
            }
        }
    }

    /**
     * It'd better to use BeanNameGenerator instance that should reference
     * {@link ConfigurationClassPostProcessor#componentScanBeanNameGenerator},
     * thus it maybe a potential problem on bean name generation.
     *
     * @param registry {@link BeanDefinitionRegistry}
     * @return {@link BeanNameGenerator} instance
     * @see SingletonBeanRegistry
     * @see AnnotationConfigUtils#CONFIGURATION_BEAN_NAME_GENERATOR
     * @see ConfigurationClassPostProcessor#processConfigBeanDefinitions
     * @since 2.5.8
     */
    private BeanNameGenerator resolveBeanNameGenerator(BeanDefinitionRegistry registry) {

        BeanNameGenerator beanNameGenerator = null;

        // 如果是 SingletonBeanRegistry 类型 从中获得对应的 BeanNameGenerator Bean 对象
        if (registry instanceof SingletonBeanRegistry) {
            SingletonBeanRegistry singletonBeanRegistry = SingletonBeanRegistry.class.cast(registry);
            beanNameGenerator = (BeanNameGenerator) singletonBeanRegistry.getSingleton(CONFIGURATION_BEAN_NAME_GENERATOR);
        }

        if (beanNameGenerator == null) {    // 如果不存在 则创建 AnnotationBeanNameGenerator 对象
            if (logger.isInfoEnabled()) {
                logger.info("BeanNameGenerator bean can't be found in BeanFactory with name ["
                        + CONFIGURATION_BEAN_NAME_GENERATOR + "]");
                logger.info("BeanNameGenerator will be a instance of " +
                        AnnotationBeanNameGenerator.class.getName() +
                        " , it maybe a potential problem on bean name generation.");
            }
            beanNameGenerator = new AnnotationBeanNameGenerator();
        }

        return beanNameGenerator;
    }

    /**
     * Finds a {@link Set} of {@link BeanDefinitionHolder BeanDefinitionHolders} whose bean type annotated
     * {@link Service} Annotation.
     * <p>
     * 创建每个在 packageToScan 扫描到的类 对应的 BeanDefinitionHolder 对象 返回 BeanDefinitionHolder 集合
     *
     * @param scanner       {@link ClassPathBeanDefinitionScanner}
     * @param packageToScan pachage to scan
     * @param registry      {@link BeanDefinitionRegistry}
     * @return non-null
     * @since 2.5.8
     */
    private Set<BeanDefinitionHolder> findServiceBeanDefinitionHolders(
            ClassPathBeanDefinitionScanner scanner, String packageToScan, BeanDefinitionRegistry registry,
            BeanNameGenerator beanNameGenerator) {
        // 获得 packageToScan 包下符合条件的 BeanDefinition 集合
        Set<BeanDefinition> beanDefinitions = scanner.findCandidateComponents(packageToScan);
        // 创建 BeanDefinitionHolder 集合
        Set<BeanDefinitionHolder> beanDefinitionHolders = new LinkedHashSet<BeanDefinitionHolder>(beanDefinitions.size());

        for (BeanDefinition beanDefinition : beanDefinitions) { // 遍历 beanDefinitions 数组

            String beanName = beanNameGenerator.generateBeanName(beanDefinition, registry); // 获得 Bean 的名字
            BeanDefinitionHolder beanDefinitionHolder = new BeanDefinitionHolder(beanDefinition, beanName); // 创建 BeanDefinitionHolder 对象
            beanDefinitionHolders.add(beanDefinitionHolder);    // 添加到 beanDefinitions 中

        }
        return beanDefinitionHolders;
    }

    /**
     * Registers {@link ServiceBean} from new annotated {@link Service} {@link BeanDefinition}
     * <p>
     * 将BeanDefinitionHolder注册到 registry 中
     *
     * @param beanDefinitionHolder
     * @param registry
     * @param scanner
     * @see ServiceBean
     * @see BeanDefinition
     */
    private void registerServiceBean(BeanDefinitionHolder beanDefinitionHolder, BeanDefinitionRegistry registry,
                                     DubboClassPathBeanDefinitionScanner scanner) {
        // 解析 Bean 的类
        Class<?> beanClass = resolveClass(beanDefinitionHolder);
        // 获得 @Service 注解
        Service service = findAnnotation(beanClass, Service.class);
        // 获得带有 @Service 的接口
        Class<?> interfaceClass = resolveServiceInterfaceClass(beanClass, service);
        // 获得 Bean 的名字
        String annotatedServiceBeanName = beanDefinitionHolder.getBeanName();
        // 创建 AbstractBeanDefinition 对象
        AbstractBeanDefinition serviceBeanDefinition =
                buildServiceBeanDefinition(service, interfaceClass, annotatedServiceBeanName);

        // ServiceBean Bean name
        // 重新生成 Bean 的名字
        String beanName = generateServiceBeanName(service, interfaceClass, annotatedServiceBeanName);
        // 校验在 scanner 中 是否已经存在 beanName
        // 若不存在则进行注册
        if (scanner.checkCandidate(beanName, serviceBeanDefinition)) { // check duplicated candidate bean
            registry.registerBeanDefinition(beanName, serviceBeanDefinition);

            if (logger.isInfoEnabled()) {
                logger.info("The BeanDefinition[" + serviceBeanDefinition +
                        "] of ServiceBean has been registered with name : " + beanName);
            }

        } else {

            if (logger.isWarnEnabled()) {
                logger.warn("The Duplicated BeanDefinition[" + serviceBeanDefinition +
                        "] of ServiceBean[ bean name : " + beanName +
                        "] was be found , Did @DubboComponentScan scan to same package in many times?");
            }

        }

    }

    /**
     * Generates the bean name of {@link ServiceBean}
     * <p>
     * 重新生成 Bean 的名字
     *
     * @param service
     * @param interfaceClass           the class of interface annotated {@link Service}
     * @param annotatedServiceBeanName the bean name of annotated {@link Service}
     * @return ServiceBean@interfaceClassName#annotatedServiceBeanName
     * @since 2.5.9
     */
    private String generateServiceBeanName(Service service, Class<?> interfaceClass, String annotatedServiceBeanName) {

        ServiceBeanNameBuilder builder = ServiceBeanNameBuilder.create(service, interfaceClass, environment);
        return builder.build();
    }

    /**
     * 获得带有 @Service 的接口
     *
     * @param annotatedServiceBeanClass
     * @param service
     * @return
     */
    private Class<?> resolveServiceInterfaceClass(Class<?> annotatedServiceBeanClass, Service service) {

        // 首先 从注解本身上获得
        Class<?> interfaceClass = service.interfaceClass();
        if (void.class.equals(interfaceClass)) {    // 一般是满足的

            interfaceClass = null;
            // 获得 @Service 注解的 interfaceName 属性
            String interfaceClassName = service.interfaceName();

            if (StringUtils.hasText(interfaceClassName)) {  // 如果存在 获得其对应的类
                if (ClassUtils.isPresent(interfaceClassName, classLoader)) {
                    interfaceClass = resolveClassName(interfaceClassName, classLoader);
                }
            }
        }

        if (interfaceClass == null) {   // <X> [一般情况下 使用这个] 获得不到 则从被注解的类上获得其实现的首个接口
            // Find all interfaces from the annotated class
            // To resolve an issue : https://github.com/apache/incubator-dubbo/issues/3251
            Class<?>[] allInterfaces = ClassUtils.getAllInterfacesForClass(annotatedServiceBeanClass);

            if (allInterfaces.length > 0) {
                interfaceClass = allInterfaces[0];
            }
        }

        Assert.notNull(interfaceClass,
                "@Service interfaceClass() or interfaceName() or interface class must be present!");
        Assert.isTrue(interfaceClass.isInterface(),
                "The type that was annotated @Service is not an interface!");
        return interfaceClass;
    }

    /**
     * 解析返回 Bean 的类
     *
     * @param beanDefinitionHolder
     * @return
     */
    private Class<?> resolveClass(BeanDefinitionHolder beanDefinitionHolder) {

        BeanDefinition beanDefinition = beanDefinitionHolder.getBeanDefinition();
        return resolveClass(beanDefinition);
    }

    private Class<?> resolveClass(BeanDefinition beanDefinition) {

        String beanClassName = beanDefinition.getBeanClassName();
        return resolveClassName(beanClassName, classLoader);
    }

    /**
     * 扫描 packagesToScan 包 创建对应的 Spring BeanDefinition 对象 从而创建 Dubbo Service Bean 对象
     *
     * @param packagesToScan
     * @return
     */
    private Set<String> resolvePackagesToScan(Set<String> packagesToScan) {
        Set<String> resolvedPackagesToScan = new LinkedHashSet<String>(packagesToScan.size());
        for (String packageToScan : packagesToScan) {   // 遍历 packagesToScan 数组
            if (StringUtils.hasText(packageToScan)) {
                // 解析可能存在的占位符
                String resolvedPackageToScan = environment.resolvePlaceholders(packageToScan.trim());
                // 添加到 resolvedPackagesToScan 中
                resolvedPackagesToScan.add(resolvedPackageToScan);
            }
        }
        return resolvedPackagesToScan;
    }

    /**
     * 创建 AbstractBeanDefinition 对象
     *
     * @param service
     * @param interfaceClass
     * @param annotatedServiceBeanName
     * @return
     */
    private AbstractBeanDefinition buildServiceBeanDefinition(Service service, Class<?> interfaceClass,
                                                              String annotatedServiceBeanName) {
        // 创建 BeanDefinitionBuilder 对象
        BeanDefinitionBuilder builder = rootBeanDefinition(ServiceBean.class);
        // 获得 AbstractBeanDefinition 对象
        AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();
        // 获得 MutablePropertyValues 属性
        // 后续 通过向它添加属性 设置到 BeanDefinition 中(即 Service Bean 中)
        MutablePropertyValues propertyValues = beanDefinition.getPropertyValues();
        // <X> 创建 AnnotationPropertyValuesAdapter 对象 添加到 propertyValues 中
        // 此处是将注解上的属性 设置到 propertyValues 中
        // 也就是说 注解上的属性 自然的能够设置到后续创建的 Service Bean 的对象中
        // 举个例子 如果说 @Service(version="1.0.0") 那么这个版本号(version) 就可以设置到 Dubbo Service Bean 中去了
        String[] ignoreAttributeNames = of("provider", "monitor", "application", "module", "registry", "protocol",
                "interface", "interfaceName");
        propertyValues.addPropertyValues(new AnnotationPropertyValuesAdapter(service, environment, ignoreAttributeNames));

        // References "ref" property to annotated-@Service Bean
        // 设置 ref 属性指向的 Service Bean 名字
        addPropertyReference(builder, "ref", annotatedServiceBeanName);
        // Set interface
        // Set interface 设置 Service 接口类全类名
        builder.addPropertyValue("interface", interfaceClass.getName());

        /**
         * Add {@link com.alibaba.dubbo.config.ProviderConfig} Bean reference
         *
         * 添加 provider 属性对应的 ProviderConfig Bean 对象
         */
        String providerConfigBeanName = service.provider();
        if (StringUtils.hasText(providerConfigBeanName)) {
            addPropertyReference(builder, "provider", providerConfigBeanName);
        }

        /**
         * Add {@link com.alibaba.dubbo.config.MonitorConfig} Bean reference
         *
         * 添加 monitor 属性对应的 MonitorConfig Bean 对象
         */
        String monitorConfigBeanName = service.monitor();
        if (StringUtils.hasText(monitorConfigBeanName)) {
            addPropertyReference(builder, "monitor", monitorConfigBeanName);
        }

        /**
         * Add {@link com.alibaba.dubbo.config.ApplicationConfig} Bean reference
         *
         * 添加 application 属性对应的 ApplicationConfig Bean 对象
         */
        String applicationConfigBeanName = service.application();
        if (StringUtils.hasText(applicationConfigBeanName)) {
            addPropertyReference(builder, "application", applicationConfigBeanName);
        }

        /**
         * Add {@link com.alibaba.dubbo.config.ModuleConfig} Bean reference
         *
         * 添加 module 属性对应的 ModuleConfig Bean 对象
         */
        String moduleConfigBeanName = service.module();
        if (StringUtils.hasText(moduleConfigBeanName)) {
            addPropertyReference(builder, "module", moduleConfigBeanName);
        }


        /**
         * Add {@link com.alibaba.dubbo.config.RegistryConfig} Bean reference
         *
         * 添加 registries 属性对应的 RegistryConfig Bean 数组(一个或多个)
         */
        String[] registryConfigBeanNames = service.registry();
        List<RuntimeBeanReference> registryRuntimeBeanReferences = toRuntimeBeanReferences(registryConfigBeanNames);
        if (!registryRuntimeBeanReferences.isEmpty()) {
            builder.addPropertyValue("registries", registryRuntimeBeanReferences);
        }

        /**
         * Add {@link com.alibaba.dubbo.config.ProtocolConfig} Bean reference
         *
         * 添加 protocols 属性对应的 ProtocolConfig Bean 数组(一个或多个)
         */
        String[] protocolConfigBeanNames = service.protocol();
        List<RuntimeBeanReference> protocolRuntimeBeanReferences = toRuntimeBeanReferences(protocolConfigBeanNames);
        if (!protocolRuntimeBeanReferences.isEmpty()) {
            builder.addPropertyValue("protocols", protocolRuntimeBeanReferences);
        }

        Method[] methods = service.methods();
        List<MethodConfig> methodConfigs = MethodConfig.constructMethodConfig(methods);
        if (!methodConfigs.isEmpty()) {
            builder.addPropertyValue("methods", methodConfigs);
        }

        return builder.getBeanDefinition();
    }

    /**
     * RuntimeBeanReference 在解析到依赖的Bean的时侯 解析器会依据依赖bean的name创建一个RuntimeBeanReference对像
     * 将这个对像放入BeanDefinition的MutablePropertyValues中
     * 此处和上面不太一样的原因 因为是多个
     *
     * @param beanNames
     * @return
     */
    private ManagedList<RuntimeBeanReference> toRuntimeBeanReferences(String... beanNames) {

        ManagedList<RuntimeBeanReference> runtimeBeanReferences = new ManagedList<RuntimeBeanReference>();
        if (!ObjectUtils.isEmpty(beanNames)) {
            for (String beanName : beanNames) { // 解析真正的 Bean 名字 如果有占位符的话
                String resolvedBeanName = environment.resolvePlaceholders(beanName);
                runtimeBeanReferences.add(new RuntimeBeanReference(resolvedBeanName));
            }
        }
        return runtimeBeanReferences;
    }

    /**
     * 添加属性值是引用类型
     *
     * @param builder
     * @param propertyName
     * @param beanName
     */
    private void addPropertyReference(BeanDefinitionBuilder builder, String propertyName, String beanName) {
        String resolvedBeanName = environment.resolvePlaceholders(beanName);
        builder.addPropertyReference(propertyName, resolvedBeanName);
    }


    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {

    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

}
