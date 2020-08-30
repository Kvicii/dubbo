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

import com.alibaba.dubbo.common.utils.CollectionUtils;
import com.alibaba.dubbo.config.ConsumerConfig;
import com.alibaba.dubbo.config.MethodConfig;
import com.alibaba.dubbo.config.annotation.Method;
import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.dubbo.config.spring.ReferenceBean;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.context.ApplicationContext;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.DataBinder;

import java.beans.PropertyEditorSupport;
import java.util.List;
import java.util.Map;

import static com.alibaba.dubbo.config.spring.util.BeanFactoryUtils.getOptionalBean;
import static com.alibaba.dubbo.config.spring.util.ObjectUtils.of;
import static org.springframework.util.StringUtils.commaDelimitedListToStringArray;

/**
 * {@link ReferenceBean} Builder
 * <p>
 * 继承 AbstractAnnotationConfigBeanBuilder 抽象类 ReferenceBean 的构建器
 *
 * @since 2.5.7
 */
class ReferenceBeanBuilder extends AbstractAnnotationConfigBeanBuilder<Reference, ReferenceBean> {

    // Ignore those fields
    static final String[] IGNORE_FIELD_NAMES = of("application", "module", "consumer", "monitor", "registry");

    private ReferenceBeanBuilder(Reference annotation, ClassLoader classLoader, ApplicationContext applicationContext) {
        super(annotation, classLoader, applicationContext);
    }

    private void configureInterface(Reference reference, ReferenceBean referenceBean) {

        // 从 @Reference 获得 interfaceName 属性 从而获得 interfaceClass 类
        Class<?> interfaceClass = reference.interfaceClass();
        if (void.class.equals(interfaceClass)) {
            interfaceClass = null;
            String interfaceClassName = reference.interfaceName();
            if (StringUtils.hasText(interfaceClassName)) {
                if (ClassUtils.isPresent(interfaceClassName, classLoader)) {
                    interfaceClass = ClassUtils.resolveClassName(interfaceClassName, classLoader);
                }
            }
        }

        if (interfaceClass == null) {   // 如果获得不到 则使用 interfaceClass 即可
            interfaceClass = this.interfaceClass;
        }

        Assert.isTrue(interfaceClass.isInterface(),
                "The class of field or method that was annotated @Reference is not an interface!");
        referenceBean.setInterface(interfaceClass);
    }


    private void configureConsumerConfig(Reference reference, ReferenceBean<?> referenceBean) {

        String consumerBeanName = reference.consumer(); // 获得 ConsumerConfig 对象
        ConsumerConfig consumerConfig = getOptionalBean(applicationContext, consumerBeanName, ConsumerConfig.class);
        referenceBean.setConsumer(consumerConfig);  // 设置到 referenceBean 中
    }

    void configureMethodConfig(Reference reference, ReferenceBean<?> referenceBean) {
        Method[] methods = reference.methods();
        List<MethodConfig> methodConfigs = MethodConfig.constructMethodConfig(methods);
        if (!methodConfigs.isEmpty()) {
            referenceBean.setMethods(methodConfigs);
        }
    }

    /**
     * 创建 ReferenceBean 对象
     *
     * @return
     */
    @Override
    protected ReferenceBean doBuild() {
        return new ReferenceBean<Object>();
    }

    @Override
    protected void preConfigureBean(Reference reference, ReferenceBean referenceBean) {
        Assert.notNull(interfaceClass, "The interface class must set first!");
        DataBinder dataBinder = new DataBinder(referenceBean);  // 创建 DataBinder 对象
        // Register CustomEditors for special fields
        // 注册指定属性的自定义 Editor
        dataBinder.registerCustomEditor(String.class, "filter", new StringTrimmerEditor(true));
        dataBinder.registerCustomEditor(String.class, "listener", new StringTrimmerEditor(true));
        dataBinder.registerCustomEditor(Map.class, "parameters", new PropertyEditorSupport() {

            public void setAsText(String text) throws java.lang.IllegalArgumentException {
                // Trim all whitespace
                String content = StringUtils.trimAllWhitespace(text);
                if (!StringUtils.hasText(content)) { // No content , ignore directly
                    return;
                }
                // replace "=" to ","
                content = StringUtils.replace(content, "=", ",");
                // replace ":" to ","
                content = StringUtils.replace(content, ":", ",");
                // String[] to Map
                Map<String, String> parameters = CollectionUtils.toStringMap(commaDelimitedListToStringArray(content));
                setValue(parameters);
            }
        });
        // Bind annotation attributes
        // 将注解的属性 设置到 reference 中
        dataBinder.bind(new AnnotationPropertyValuesAdapter(reference, applicationContext.getEnvironment(), IGNORE_FIELD_NAMES));
    }


    @Override
    protected String resolveModuleConfigBeanName(Reference annotation) {
        return annotation.module();
    }

    @Override
    protected String resolveApplicationConfigBeanName(Reference annotation) {
        return annotation.application();
    }

    @Override
    protected String[] resolveRegistryConfigBeanNames(Reference annotation) {
        return annotation.registry();
    }

    @Override
    protected String resolveMonitorConfigBeanName(Reference annotation) {
        return annotation.monitor();
    }

    @Override
    protected void postConfigureBean(Reference annotation, ReferenceBean bean) throws Exception {

        bean.setApplicationContext(applicationContext); // 设置 applicationContext
        configureInterface(annotation, bean);   // 配置 interfaceClass
        configureConsumerConfig(annotation, bean);  // 配置 ConsumerConfig
        configureMethodConfig(annotation, bean);
        bean.afterPropertiesSet();  // 执行 Bean 后置属性初始化
    }

    public static ReferenceBeanBuilder create(Reference annotation, ClassLoader classLoader,
                                              ApplicationContext applicationContext) {
        return new ReferenceBeanBuilder(annotation, classLoader, applicationContext);
    }
}
