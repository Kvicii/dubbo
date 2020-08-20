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
package com.alibaba.dubbo.config.spring.context.properties;

import com.alibaba.dubbo.config.AbstractConfig;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.validation.DataBinder;

import java.util.Map;

import static com.alibaba.dubbo.config.spring.util.PropertySourcesUtils.getSubProperties;

/**
 * Default {@link DubboConfigBinder} implementation based on Spring {@link DataBinder}
 * 继承 AbstractDubboConfigBinder 抽象类 使用 Spring DataBinder，将配置属性设置到 Dubbo Config 对象中
 */
public class DefaultDubboConfigBinder extends AbstractDubboConfigBinder {

    @Override
    public <C extends AbstractConfig> void bind(String prefix, C dubboConfig) {
        DataBinder dataBinder = new DataBinder(dubboConfig);    // 将 dubboConfig 包装成 DataBinder 对象
        // Set ignored*
        // 设置响应的 ignored* 属性
        dataBinder.setIgnoreInvalidFields(isIgnoreInvalidFields());
        dataBinder.setIgnoreUnknownFields(isIgnoreUnknownFields());
        // Get properties under specified prefix from PropertySources
        Map<String, Object> properties = getSubProperties(getPropertySources(), prefix);    // 获得 prefix 开头的配置属性
        // Convert Map to MutablePropertyValues
        MutablePropertyValues propertyValues = new MutablePropertyValues(properties);   // 创建 MutablePropertyValues 对象
        // Bind
        dataBinder.bind(propertyValues);    // 绑定配置属性到 dubboConfig 中
    }
}
