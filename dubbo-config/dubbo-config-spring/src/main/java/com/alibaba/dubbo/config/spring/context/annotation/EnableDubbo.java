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
package com.alibaba.dubbo.config.spring.context.annotation;

import com.alibaba.dubbo.config.AbstractConfig;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables Dubbo components as Spring Beans, equals
 * {@link DubboComponentScan} and {@link EnableDubboConfig} combination.
 * <p>
 * Note : {@link EnableDubbo} must base on Spring Framework 4.2 and above
 * 通过 @EnableDubbo 可以在指定的包名下(通过 scanBasePackages 属性) 或者指定的类中(通过 scanBasePackageClasses 属性)
 * 扫描 Dubbo 的服务提供者(即 @Service 注解)以及 Dubbo 的服务消费者(以 @Reference 注解)
 * <p>
 * 扫描到 Dubbo 的服务提供方和消费者之后 对其做相应的组装并初始化 并最终完成服务暴露或者引用的工作
 *
 * @see DubboComponentScan
 * @see EnableDubboConfig
 * @since 2.5.8
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@EnableDubboConfig  // 开启 Dubbo Config
@DubboComponentScan // 扫描 Dubbo @Service 和 @Reference Bean
public @interface EnableDubbo {

    /**
     * Base packages to scan for annotated @Service classes.
     * <p>
     * 配置 @DubboComponentScan 注解 即要扫描的包
     * <p>
     * Use {@link #scanBasePackageClasses()} for a type-safe alternative to String-based
     * package names.
     *
     * @return the base packages to scan
     * @see DubboComponentScan#basePackages()
     */
    @AliasFor(annotation = DubboComponentScan.class, attribute = "basePackages")
    String[] scanBasePackages() default {};

    /**
     * Type-safe alternative to {@link #scanBasePackages()} for specifying the packages to
     * scan for annotated @Service classes. The package of each class specified will be
     * scanned.
     * <p>
     * 配置 @DubboComponentScan 注解 即要扫描的类
     *
     * @return classes from the base packages to scan
     * @see DubboComponentScan#basePackageClasses
     */
    @AliasFor(annotation = DubboComponentScan.class, attribute = "basePackageClasses")
    Class<?>[] scanBasePackageClasses() default {};


    /**
     * It indicates whether {@link AbstractConfig} binding to multiple Spring Beans.
     * <p>
     * 配置 @EnableDubboConfig 注解 配置是否绑定到多个 Spring Bean 上
     *
     * @return the default value is <code>false</code>
     * @see EnableDubboConfig#multiple()
     */
    @AliasFor(annotation = EnableDubboConfig.class, attribute = "multiple")
    boolean multipleConfig() default true;

}
