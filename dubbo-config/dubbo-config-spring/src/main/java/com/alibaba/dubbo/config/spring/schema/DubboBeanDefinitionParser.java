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
package com.alibaba.dubbo.config.spring.schema;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.config.ArgumentConfig;
import com.alibaba.dubbo.config.ConsumerConfig;
import com.alibaba.dubbo.config.MethodConfig;
import com.alibaba.dubbo.config.ProtocolConfig;
import com.alibaba.dubbo.config.ProviderConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.config.spring.ReferenceBean;
import com.alibaba.dubbo.config.spring.ServiceBean;
import com.alibaba.dubbo.rpc.Protocol;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.ManagedMap;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AbstractBeanDefinitionParser
 * Dubbo Bean 定义解析器
 *
 * @export
 */
public class DubboBeanDefinitionParser implements BeanDefinitionParser {

    private static final Logger logger = LoggerFactory.getLogger(DubboBeanDefinitionParser.class);
    private static final Pattern GROUP_AND_VERION = Pattern.compile("^[\\-.0-9_a-zA-Z]+(\\:[\\-.0-9_a-zA-Z]+)?$");
    // Bean对象的类
    private final Class<?> beanClass;
    // 是否需要Bean的`id`属性 不存在时自动生成编号 无需被其他应用引用的配置对象无需自动生成编号 例如有 <dubbo:reference />
    private final boolean required;

    public DubboBeanDefinitionParser(Class<?> beanClass, boolean required) {
        this.beanClass = beanClass;
        this.required = required;
    }

    /**
     * 真正解析 XML 元素
     *
     * @param element
     * @param parserContext
     * @param beanClass
     * @param required
     * @return
     */
    @SuppressWarnings("unchecked")
    private static BeanDefinition parse(Element element, ParserContext parserContext, Class<?> beanClass, boolean required) {
        // 创建 RootBeanDefinition
        RootBeanDefinition beanDefinition = new RootBeanDefinition();
        beanDefinition.setBeanClass(beanClass);
        // lazyInit默认为false 即默认延迟初始化 只有引用被注入到其它 Bean 或被 getBean() 获取 才会初始化
        // 如果需要实时加载 即没有引用也立即生成动态代理 可以配置 <dubbo:reference ... init="true" />
        beanDefinition.setLazyInit(false);
        // 解析配置对象的id 若不存在则进行生成
        String id = element.getAttribute("id");
        if ((id == null || id.length() == 0) && required) {
            // 生成id 不同的配置对象会不同 规则为 name > 特殊规则 > className
            String generatedBeanName = element.getAttribute("name");
            if (generatedBeanName == null || generatedBeanName.length() == 0) {
                if (ProtocolConfig.class.equals(beanClass)) {
                    generatedBeanName = "dubbo";
                } else {
                    generatedBeanName = element.getAttribute("interface");
                }
            }
            if (generatedBeanName == null || generatedBeanName.length() == 0) {
                generatedBeanName = beanClass.getName();
            }
            id = generatedBeanName;
            int counter = 2;
            while (parserContext.getRegistry().containsBeanDefinition(id)) {    // 若 id 在 Spring 注册表已经存在 通过添加自增序列作为后缀 避免冲突
                id = generatedBeanName + (counter++);
            }
        }
        if (id != null && id.length() > 0) {
            if (parserContext.getRegistry().containsBeanDefinition(id)) {
                throw new IllegalStateException("Duplicate spring bean id " + id);
            }
            parserContext.getRegistry().registerBeanDefinition(id, beanDefinition); // 添加Bean到 Spring 的注册表
            beanDefinition.getPropertyValues().addPropertyValue("id", id);  // 设置 Bean 的 `id` 属性值
        }
        if (ProtocolConfig.class.equals(beanClass)) {   // 处理 `<dubbo:protocol` /> 的特殊情况
            for (String name : parserContext.getRegistry().getBeanDefinitionNames()) {
                // 需要满足[第 220 至 243 行]
                // 例如: [顺序要这样]
                // <dubbo:service interface="com.alibaba.dubbo.demo.DemoService" protocol="dubbo" ref="demoService"/>
                // <dubbo:protocol id="dubbo" name="dubbo" port="20880"/>
                BeanDefinition definition = parserContext.getRegistry().getBeanDefinition(name);
                PropertyValue property = definition.getPropertyValues().getPropertyValue("protocol");
                if (property != null) {
                    Object value = property.getValue();
                    if (value instanceof ProtocolConfig && id.equals(((ProtocolConfig) value).getName())) {
                        definition.getPropertyValues().addPropertyValue("protocol", new RuntimeBeanReference(id));
                    }
                }
            }
        } else if (ServiceBean.class.equals(beanClass)) {   // 处理 <dubbo:service /> 的 class 属性
            // 当配置 class 属时 会自动创建 Service Bean 对象 而无需再配置 ref 属性指向 Service Bean 对象 如:
            // <bean id="demoDAO" class="com.alibaba.dubbo.demo.provider.DemoDAO" />
            //    <dubbo:service id="sa" interface="com.alibaba.dubbo.demo.DemoService" class="com.alibaba.dubbo.demo.provider.DemoServiceImpl">
            //    <property name="demoDAO" ref="demoDAO" />
            // 通过这种方式 可以使用 <property /> 标签 设置 Service Bean 的属性
            // </dubbo:service>
            // 处理 `class` 属性
            // 例如  <dubbo:service id="sa" interface="com.alibaba.dubbo.demo.DemoService" class="com.alibaba.dubbo.demo.provider.DemoServiceImpl" >
            String className = element.getAttribute("class");
            if (className != null && className.length() > 0) {
                // 创建 Service 的 RootBeanDefinition 对象 相当于内嵌了 <bean class="com.alibaba.dubbo.demo.provider.DemoServiceImpl" />
                RootBeanDefinition classDefinition = new RootBeanDefinition();
                classDefinition.setBeanClass(ReflectUtils.forName(className));
                classDefinition.setLazyInit(false);
                parseProperties(element.getChildNodes(), classDefinition);  // 解析 Service Bean 对象的property属性
                // 设置 `<dubbo:service ref="" />` 属性
                beanDefinition.getPropertyValues().addPropertyValue("ref", new BeanDefinitionHolder(classDefinition, id + "Impl"));
            }
        } else if (ProviderConfig.class.equals(beanClass)) {    // 解析 <dubbo:provider /> 的内嵌子元素 <dubbo:service />
            parseNested(element, parserContext, ServiceBean.class, true, "service", "provider", id, beanDefinition);
        } else if (ConsumerConfig.class.equals(beanClass)) {    // 解析 <dubbo:consumer /> 的内嵌子元素 <dubbo:reference />
            parseNested(element, parserContext, ReferenceBean.class, false, "reference", "consumer", id, beanDefinition);
        }
        Set<String> props = new HashSet<String>();  // 已解析的属性集合 该属性在 将XML元素未遍历到的属性 添加到 parameters 集合中 会使用到
        ManagedMap parameters = null;   // 解析的参数集合 parameters
        for (Method setter : beanClass.getMethods()) {  // 循环 Bean 对象的 set 方法 将属性赋值到 Bean 对象
            String name = setter.getName();
            if (name.length() > 3 && name.startsWith("set")
                    && Modifier.isPublic(setter.getModifiers())
                    && setter.getParameterTypes().length == 1) {    // set && public && 唯一参数 的方法
                Class<?> type = setter.getParameterTypes()[0];
                String propertyName = name.substring(3, 4).toLowerCase() + name.substring(4);
                // 添加属性名到 `props`
                String property = StringUtils.camelToSplitName(propertyName, "-");
                props.add(property);
                Method getter = null;
                try {   // 获取set方法对应的get方法
                    getter = beanClass.getMethod("get" + name.substring(3), new Class<?>[0]);
                } catch (NoSuchMethodException e) {
                    try {
                        getter = beanClass.getMethod("is" + name.substring(3), new Class<?>[0]);
                    } catch (NoSuchMethodException e2) {
                    }
                }
                if (getter == null
                        || !Modifier.isPublic(getter.getModifiers())
                        || !type.equals(getter.getReturnType())) {  // 无get方法 || !public || 属性值类型不同 跳过不处理
                    continue;
                }
                if ("parameters".equals(property)) {    // 解析 `<dubbo:parameters />`
                    parameters = parseParameters(element.getChildNodes(), beanDefinition);
                } else if ("methods".equals(property)) {    // 解析 `<dubbo:method />`
                    parseMethods(id, element.getChildNodes(), beanDefinition, parserContext);
                } else if ("arguments".equals(property)) {  // 解析 `<dubbo:argument />`
                    parseArguments(id, element.getChildNodes(), beanDefinition, parserContext);
                } else {
                    String value = element.getAttribute(property);
                    if (value != null) {
                        value = value.trim();
                        if (value.length() > 0) {
                            if ("registry".equals(property) && RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(value)) {   // 处理不想注册到注册中心的情况 即 `registry=N/A`
                                RegistryConfig registryConfig = new RegistryConfig();
                                registryConfig.setAddress(RegistryConfig.NO_AVAILABLE);
                                beanDefinition.getPropertyValues().addPropertyValue(property, registryConfig);
                            } else if ("registry".equals(property) && value.indexOf(',') != -1) {   // 多注册中心的情况
                                parseMultiRef("registries", value, beanDefinition, parserContext);
                            } else if ("provider".equals(property) && value.indexOf(',') != -1) {   // 多服务提供者的情况
                                parseMultiRef("providers", value, beanDefinition, parserContext);
                            } else if ("protocol".equals(property) && value.indexOf(',') != -1) {   // 多协议的情况
                                parseMultiRef("protocols", value, beanDefinition, parserContext);
                            } else {
                                Object reference;
                                if (isPrimitive(type)) {    // 处理属性类型为基本属性的情况
                                    if ("async".equals(property) && "false".equals(value)
                                            || "timeout".equals(property) && "0".equals(value)
                                            || "delay".equals(property) && "0".equals(value)
                                            || "version".equals(property) && "0.0.0".equals(value)
                                            || "stat".equals(property) && "-1".equals(value)
                                            || "reliable".equals(property) && "false".equals(value)) {  // 兼容性处理
                                        // backward compatibility for the default value in old version's xsd
                                        value = null;
                                    }
                                    reference = value;
                                } else if ("protocol".equals(property)
                                        && ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(value)   // 存在该注册协议的实现
                                        && (!parserContext.getRegistry().containsBeanDefinition(value)  // Spring 注册表中不存在该 `<dubbo:provider />` 的定义
                                        || !ProtocolConfig.class.getName().equals(parserContext.getRegistry().getBeanDefinition(value).getBeanClassName()))) {  // Spring 注册表中存在该编号 但是类型不为 ProtocolConfig
                                    // "protocol" 属性在多个标签中会使用 例如 <dubbo:service /> 或者 <dubbo:provider /> 等
                                    // 根据 <Dubbo 文档 —— schema 配置参考手册> 的说明 "protocol" 代表的是指向的 <dubbo:protocol /> 的编号(id)
                                    // 优先以指向的 <dubbo:protocol /> 的编号(id) 为准 否则认为是协议名 这就出现了如下的问题:
                                    // 实际在解析 Bean 对象时 带有 "protocol" 属性的标签 无法保证一定在 <dubbo:protocol /> 之后解析
                                    // 解决方式:
                                    // 如果带有 "protocol" 属性的标签先解析 先[第 220 至 243 行]直接创建 ProtocolConfig 对象并设置到 "protocol" 属性
                                    // 再[第 121 至 136 行]在 <dubbo:protocol /> 解析后 进行覆盖
                                    // 这样 如果不存在 <dubbo:protocol /> 的情况 最多不进行覆盖
                                    // 如果带有 "protocol" 属性的标签后解析 无需走上述流程 走[第 260 至 268    行]即可
                                    // ---------------------------------------------------------------------------------------------------
                                    // 处理在 `<dubbo:provider />` 或者 `<dubbo:service />` 上定义了 `protocol` 属性的 兼容性
                                    if ("dubbo:provider".equals(element.getTagName())) {    // 目前 `<dubbo:provider protocol="" />` 推荐独立成 `<dubbo:protocol />`
                                        logger.warn("Recommended replace <dubbo:provider protocol=\"" + value + "\" ... /> to <dubbo:protocol name=\"" + value + "\" ... />");
                                    }
                                    // backward compatibility
                                    ProtocolConfig protocol = new ProtocolConfig();
                                    protocol.setName(value);
                                    reference = protocol;
                                } else if ("onreturn".equals(property)) {   // 处理 `onreturn` 属性
                                    int index = value.lastIndexOf("."); // 按照 `.` 拆分
                                    String returnRef = value.substring(0, index);
                                    String returnMethod = value.substring(index + 1);
                                    reference = new RuntimeBeanReference(returnRef);    // 创建 RuntimeBeanReference 指向回调的对象
                                    beanDefinition.getPropertyValues().addPropertyValue("onreturnMethod", returnMethod);    // 设置 `onreturnMethod` 到 BeanDefinition 的属性值
                                } else if ("onthrow".equals(property)) {    // 处理 `onthrow` 属性
                                    int index = value.lastIndexOf("."); // 按照 `.` 拆分
                                    String throwRef = value.substring(0, index);
                                    String throwMethod = value.substring(index + 1);
                                    reference = new RuntimeBeanReference(throwRef); // 创建 RuntimeBeanReference 指向回调的对象
                                    beanDefinition.getPropertyValues().addPropertyValue("onthrowMethod", throwMethod);  // 设置 `onthrowMethod` 到 BeanDefinition 的属性值
                                } else if ("oninvoke".equals(property)) {
                                    int index = value.lastIndexOf(".");
                                    String invokeRef = value.substring(0, index);
                                    String invokeRefMethod = value.substring(index + 1);
                                    reference = new RuntimeBeanReference(invokeRef);
                                    beanDefinition.getPropertyValues().addPropertyValue("oninvokeMethod", invokeRefMethod);
                                } else {    // 通用解析
                                    if ("ref".equals(property) && parserContext.getRegistry().containsBeanDefinition(value)) {
                                        BeanDefinition refBean = parserContext.getRegistry().getBeanDefinition(value);
                                        if (!refBean.isSingleton()) {   // 指向的 Service 的 Bean 对象 必须是单例
                                            throw new IllegalStateException("The exported service ref " + value + " must be singleton! Please set the " + value + " bean scope to singleton, eg: <bean id=\"" + value + "\" scope=\"singleton\" ...>");
                                        }
                                    }
                                    reference = new RuntimeBeanReference(value);    // 创建 RuntimeBeanReference 指向 Service 的 Bean 对象 例如<dubbo:service /> 的 "ref" 属性
                                }
                                beanDefinition.getPropertyValues().addPropertyValue(propertyName, reference);   // 设置 BeanDefinition 的属性值 该属性值来自[第 206 至 265 行]代码的逻辑
                            }
                        }
                    }
                }
            }
        }
        // 将未在上面遍历到的XML元素属性 添加到 `parameters` 集合中
        // 目前测试下来 不存在这样的情况
        NamedNodeMap attributes = element.getAttributes();
        int len = attributes.getLength();
        for (int i = 0; i < len; i++) { // 将未在上面遍历到的XML元素属性 添加到 parameters 集合中
            Node node = attributes.item(i);
            String name = node.getLocalName();
            if (!props.contains(name)) {
                if (parameters == null) {
                    parameters = new ManagedMap();
                }
                String value = node.getNodeValue();
                parameters.put(name, new TypedStringValue(value, String.class));
            }
        }
        if (parameters != null) {   // 设置 Bean 的 parameters
            beanDefinition.getPropertyValues().addPropertyValue("parameters", parameters);
        }
        return beanDefinition;
    }

    /**
     * 判断是否是基本属性类型
     *
     * @param cls
     * @return
     */
    private static boolean isPrimitive(Class<?> cls) {
        return cls.isPrimitive() || cls == Boolean.class || cls == Byte.class
                || cls == Character.class || cls == Short.class || cls == Integer.class
                || cls == Long.class || cls == Float.class || cls == Double.class
                || cls == String.class || cls == Date.class || cls == Class.class;
    }

    /**
     * 解析多指向的情况 例如多注册中心 多服务提供者 多协议等
     *
     * @param property       属性
     * @param value          值
     * @param beanDefinition Bean 定义对象
     * @param parserContext  Spring 解析上下文
     */
    @SuppressWarnings("unchecked")
    private static void parseMultiRef(String property, String value, RootBeanDefinition beanDefinition, ParserContext parserContext) {
        // 以 , 拆分值字符串 创建 RuntimeBeanReference 数组
        String[] values = value.split("\\s*[,]+\\s*");
        ManagedList list = null;
        for (int i = 0; i < values.length; i++) {
            String v = values[i];
            if (v != null && v.length() > 0) {
                if (list == null) {
                    list = new ManagedList();
                }
                list.add(new RuntimeBeanReference(v));
            }
        }
        beanDefinition.getPropertyValues().addPropertyValue(property, list);    // 设置 Bean 对象的指定属性值
    }

    /**
     * 解析内嵌的指向的子XML元素
     *
     * @param element        父 XML 元素
     * @param parserContext  Spring 解析上下文
     * @param beanClass      内嵌解析子元素的 Bean 的类
     * @param required       是否需要 Bean 的 `id` 属性
     * @param tag            标签
     * @param property       父 Bean 对象在子元素中的属性名
     * @param ref            指向
     * @param beanDefinition 父 Bean 定义对象
     */
    private static void parseNested(Element element, ParserContext parserContext, Class<?> beanClass, boolean required, String tag, String property, String ref, BeanDefinition beanDefinition) {
        NodeList nodeList = element.getChildNodes();
        if (nodeList != null && nodeList.getLength() > 0) {
            boolean first = true;
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    if (tag.equals(node.getNodeName())
                            || tag.equals(node.getLocalName())) {   // 只解析指定标签 目前有内嵌的 <dubbo:service /> 和 <dubbo:reference /> 标签
                        if (first) {
                            first = false;
                            String isDefault = element.getAttribute("default");
                            if (isDefault == null || isDefault.length() == 0) {
                                beanDefinition.getPropertyValues().addPropertyValue("default", "false");
                            }
                        }
                        // 解析子元素 创建 BeanDefinition 对象
                        BeanDefinition subDefinition = parse((Element) node, parserContext, beanClass, required);
                        if (subDefinition != null && ref != null && ref.length() > 0) { // 设置子 BeanDefinition 指向父 BeanDefinition
                            subDefinition.getPropertyValues().addPropertyValue(property, new RuntimeBeanReference(ref));
                        }
                    }
                }
            }
        }
    }

    /**
     * 解析 <dubbo:service class="" /> 下 内含的 `<property />` 的赋值
     *
     * @param nodeList       子元素数组
     * @param beanDefinition Bean 定义对象
     */
    private static void parseProperties(NodeList nodeList, RootBeanDefinition beanDefinition) {
        if (nodeList != null && nodeList.getLength() > 0) {
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    if ("property".equals(node.getNodeName())
                            || "property".equals(node.getLocalName())) {    // 只解析 <property /> 标签
                        String name = ((Element) node).getAttribute("name");
                        if (name != null && name.length() > 0) {
                            String value = ((Element) node).getAttribute("value");
                            String ref = ((Element) node).getAttribute("ref");
                            if (value != null && value.length() > 0) {  // 优先使用 "value" 属性
                                beanDefinition.getPropertyValues().addPropertyValue(name, value);
                            } else if (ref != null && ref.length() > 0) {   // 其次使用 "ref" 属性
                                beanDefinition.getPropertyValues().addPropertyValue(name, new RuntimeBeanReference(ref));
                            } else {    // 属性不完整 抛出异常
                                throw new UnsupportedOperationException("Unsupported <property name=\"" + name + "\"> sub tag, Only supported <property name=\"" + name + "\" ref=\"...\" /> or <property name=\"" + name + "\" value=\"...\" />");
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 解析 `<dubbo:parameter />`
     *
     * @param nodeList       子元素节点数组
     * @param beanDefinition Bean 定义对象
     * @return 参数集合
     */
    @SuppressWarnings("unchecked")
    private static ManagedMap parseParameters(NodeList nodeList, RootBeanDefinition beanDefinition) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedMap parameters = null;   // 解析的参数集合
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    if ("parameter".equals(node.getNodeName())
                            || "parameter".equals(node.getLocalName())) {   // 只解析子元素中的 `<dubbo:parameter />`
                        if (parameters == null) {
                            parameters = new ManagedMap();
                        }
                        String key = ((Element) node).getAttribute("key");
                        String value = ((Element) node).getAttribute("value");
                        boolean hide = "true".equals(((Element) node).getAttribute("hide"));
                        if (hide) {
                            key = Constants.HIDE_KEY_PREFIX + key;
                        }
                        parameters.put(key, new TypedStringValue(value, String.class)); // 添加 "key" "value" 到 parameters
                    }
                }
            }
            return parameters;
        }
        return null;
    }

    /**
     * 解析 `<dubbo:method />`
     *
     * @param id             Bean 的 `id` 属性
     * @param nodeList       子元素节点数组
     * @param beanDefinition Bean 定义对象
     * @param parserContext  解析上下文
     */
    @SuppressWarnings("unchecked")
    private static void parseMethods(String id, NodeList nodeList, RootBeanDefinition beanDefinition, ParserContext parserContext) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedList methods = null; // 解析的方法数组
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    Element element = (Element) node;
                    if ("method".equals(node.getNodeName()) || "method".equals(node.getLocalName())) {  // 只解析 `<dubbo:method />` 标签
                        String methodName = element.getAttribute("name");
                        if (methodName == null || methodName.length() == 0) {   // 方法名不能为空
                            throw new IllegalStateException("<dubbo:method> name attribute == null");
                        }
                        if (methods == null) {
                            methods = new ManagedList();
                        }
                        // 解析 `<dubbo:method />` 创建子 BeanDefinition 对象
                        BeanDefinition methodBeanDefinition = parse(((Element) node), parserContext, MethodConfig.class, false);
                        String name = id + "." + methodName;
                        BeanDefinitionHolder methodBeanDefinitionHolder = new BeanDefinitionHolder(
                                methodBeanDefinition, name);
                        methods.add(methodBeanDefinitionHolder);    // 添加子Bean对象到 `methods` 中
                    }
                }
            }
            if (methods != null) {  // 设置 Bean 的 methods
                beanDefinition.getPropertyValues().addPropertyValue("methods", methods);
            }
        }
    }

    /**
     * 解析 `<dubbo:argument />`
     *
     * @param id             Bean 的 `id` 属性
     * @param nodeList       子元素节点数组
     * @param beanDefinition Bean 定义对象
     * @param parserContext  解析上下文
     */
    @SuppressWarnings("unchecked")
    private static void parseArguments(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
                                       ParserContext parserContext) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedList arguments = null;   // 解析的参数数组
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    Element element = (Element) node;
                    if ("argument".equals(node.getNodeName()) || "argument".equals(node.getLocalName())) {  // 判断只解析 `<dubbo:argument />`
                        String argumentIndex = element.getAttribute("index");
                        if (arguments == null) {
                            arguments = new ManagedList();
                        }
                        // 解析 `<dubbo:argument />` 创建 BeanDefinition 对象
                        BeanDefinition argumentBeanDefinition = parse(((Element) node), parserContext, ArgumentConfig.class, false);
                        String name = id + "." + argumentIndex;
                        BeanDefinitionHolder argumentBeanDefinitionHolder = new BeanDefinitionHolder(
                                argumentBeanDefinition, name);
                        arguments.add(argumentBeanDefinitionHolder);    // 添加到 `arguments` 中
                    }
                }
            }
            if (arguments != null) {
                beanDefinition.getPropertyValues().addPropertyValue("arguments", arguments);
            }
        }
    }

    /**
     * 解析 XML 元素
     *
     * @param element
     * @param parserContext
     * @return
     */
    @Override
    public BeanDefinition parse(Element element, ParserContext parserContext) {
        return parse(element, parserContext, beanClass, required);
    }
}
