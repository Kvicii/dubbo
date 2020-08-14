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
package com.alibaba.dubbo.config.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;

/**
 * Parameter
 * 用于Dubbo URL的parameter拼接
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Parameter {

    /**
     * 键
     *
     * @return
     */
    String key() default "";

    /**
     * 是否必填
     *
     * @return
     */
    boolean required() default false;

    /**
     * 是否忽略
     *
     * @return
     */
    boolean excluded() default false;

    /**
     * 是否转义
     *
     * @return
     */
    boolean escaped() default false;

    /**
     * 是否为属性
     * 目前用于<事件通知> http://dubbo.apache.org/zh-cn/docs/user/demos/events-notify.html
     *
     * @return
     */
    boolean attribute() default false;

    /**
     * 是否拼接默认属性 见 {@link com.alibaba.dubbo.config.AbstractConfig#appendParameters(Map, Object, String)} 方法
     * <p>
     * `#append() = true` 的属性 有如下四个:
     * + {@link com.alibaba.dubbo.config.AbstractInterfaceConfig#getFilter()}
     * + {@link com.alibaba.dubbo.config.AbstractInterfaceConfig#getListener()}
     * + {@link com.alibaba.dubbo.config.AbstractReferenceConfig#getFilter()}
     * + {@link com.alibaba.dubbo.config.AbstractReferenceConfig#getListener()}
     * + {@link com.alibaba.dubbo.config.AbstractServiceConfig#getFilter()}
     * + {@link com.alibaba.dubbo.config.AbstractServiceConfig#getListener()}
     * 以 AbstractServiceConfig 为例
     * <p>
     * ProviderConfig 和 ServiceConfig 继承 AbstractServiceConfig 类，那么 `filter` 和 `listener` 对应的相同的键
     * 以 `filter` 为例
     * <p>
     * 在 ServiceConfig 中 默认会继承 ProviderConfig 配置的 `filter` 和 `listener`
     * 所以这个属性 就是用于 像 ServiceConfig 的这种情况 从 ProviderConfig 读取父属性
     * <p>
     * 如果 `ProviderConfig.filter=aaaFilter` `ServiceConfig.filter=bbbFilter`  最终暴露到 Dubbo URL 时 参数为 `service.filter=aaaFilter, bbbFilter`
     */
    boolean append() default false;
}