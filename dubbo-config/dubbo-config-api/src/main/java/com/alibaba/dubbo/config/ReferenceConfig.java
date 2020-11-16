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
package com.alibaba.dubbo.config;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.bytecode.Wrapper;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.dubbo.config.model.ApplicationModel;
import com.alibaba.dubbo.config.model.ConsumerModel;
import com.alibaba.dubbo.config.support.Parameter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.StaticContext;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.directory.StaticDirectory;
import com.alibaba.dubbo.rpc.cluster.support.AvailableCluster;
import com.alibaba.dubbo.rpc.cluster.support.ClusterUtils;
import com.alibaba.dubbo.rpc.protocol.injvm.InjvmProtocol;
import com.alibaba.dubbo.rpc.service.GenericService;
import com.alibaba.dubbo.rpc.support.ProtocolUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.alibaba.dubbo.common.utils.NetUtils.isInvalidLocalHost;

/**
 * ReferenceConfig
 * 服务消费者引用服务配置类
 *
 * @export
 */
public class ReferenceConfig<T> extends AbstractReferenceConfig {

	private static final long serialVersionUID = -5864351140409987595L;

	/**
	 * 选择协议 将远端服务转换为Invoker对象
	 */
	private static final Protocol refprotocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

	/**
	 * 用于集群容错的对象
	 */
	private static final Cluster cluster = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

	/**
	 * 通过代理工厂的getProxy方法获取接口对象 代理的具体实现可以是JdkProxyFactory也可以是JavassistProxyFactory
	 */
	private static final ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();
	private final List<URL> urls = new ArrayList<>();
	// interface name
	private String interfaceName;
	private Class<?> interfaceClass;
	// client type
	private String client;
	// url for peer-to-peer invocation
	private String url;
	// method configs
	private List<MethodConfig> methods;
	// default config
	private ConsumerConfig consumer;
	private String protocol;
	// interface proxy reference
	// 业务逻辑层的接口
	private transient volatile T ref;
	private transient volatile Invoker<?> invoker;
	private transient volatile boolean initialized;
	private transient volatile boolean destroyed;
	@SuppressWarnings("unused")
	private final Object finalizerGuardian = new Object() {
		@Override
		protected void finalize() throws Throwable {
			super.finalize();

			if (!ReferenceConfig.this.destroyed) {
				logger.warn("ReferenceConfig(" + url + ") is not DESTROYED when FINALIZE");

                /* don't destroy for now
                try {
                    ReferenceConfig.this.destroy();
                } catch (Throwable t) {
                        logger.warn("Unexpected err when destroy invoker of ReferenceConfig(" + url + ") in finalize method!", t);
                }
                */
			}
		}
	};

	public ReferenceConfig() {
	}

	public ReferenceConfig(Reference reference) {
		appendAnnotation(Reference.class, reference);
		setMethods(MethodConfig.constructMethodConfig(reference.methods()));
	}

	/**
	 * 检查属性集合中的事件通知方法是否正确
	 * 因为 此时方法配置的是字符串 需要通过反射获得 Method ，并添加到 attributes 其中 键为 {@link StaticContext#getKey(Map, String, String)} 方法获取
	 *
	 * @param method     方法配置对象
	 * @param map        参数集合
	 * @param attributes 属性集合
	 */
	private static void checkAndConvertImplicitConfig(MethodConfig method, Map<String, String> map, Map<Object, Object> attributes) {
		//check config conflict
		if (Boolean.FALSE.equals(method.isReturn()) && (method.getOnreturn() != null || method.getOnthrow() != null)) {
			throw new IllegalStateException("method config error : return attribute must be set true when onreturn or onthrow has been setted.");
		}
		// convert on return methodName to Method
		// onreturn: 将方法名字符串转换成方法
		String onReturnMethodKey = StaticContext.getKey(map, method.getName(), Constants.ON_RETURN_METHOD_KEY);
		Object onReturnMethod = attributes.get(onReturnMethodKey);
		if (onReturnMethod instanceof String) {
			attributes.put(onReturnMethodKey, getMethodByName(method.getOnreturn().getClass(), onReturnMethod.toString()));
		}
		// convert on throw methodName to Method
		//  onthrow: 将方法名字符串转换成方法
		String onThrowMethodKey = StaticContext.getKey(map, method.getName(), Constants.ON_THROW_METHOD_KEY);
		Object onThrowMethod = attributes.get(onThrowMethodKey);
		if (onThrowMethod instanceof String) {
			attributes.put(onThrowMethodKey, getMethodByName(method.getOnthrow().getClass(), onThrowMethod.toString()));
		}
		// convert on invoke methodName to Method
		// oninvoke: 将方法名字符串转换成方法
		String onInvokeMethodKey = StaticContext.getKey(map, method.getName(), Constants.ON_INVOKE_METHOD_KEY);
		Object onInvokeMethod = attributes.get(onInvokeMethodKey);
		if (onInvokeMethod instanceof String) {
			attributes.put(onInvokeMethodKey, getMethodByName(method.getOninvoke().getClass(), onInvokeMethod.toString()));
		}
	}

	private static Method getMethodByName(Class<?> clazz, String methodName) {
		try {
			return ReflectUtils.findMethodByMethodName(clazz, methodName);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	public URL toUrl() {
		return urls.isEmpty() ? null : urls.iterator().next();
	}

	public List<URL> toUrls() {
		return urls;
	}

	/**
	 * 获取引用服务
	 *
	 * @return
	 */
	public synchronized T get() {
		if (destroyed) {    // 若已经销毁( destroyed = true ) 抛出异常
			throw new IllegalStateException("Already destroyed!");
		}
		if (ref == null) {  // 初始化ReferenceConfig
			init();
		}
		return ref; // 返回引用服务
	}

	public synchronized void destroy() {
		if (ref == null) {
			return;
		}
		if (destroyed) {
			return;
		}
		destroyed = true;
		try {
			invoker.destroy();
		} catch (Throwable t) {
			logger.warn("Unexpected err when destroy invoker of ReferenceConfig(" + url + ").", t);
		}
		invoker = null;
		ref = null;
	}

	private void init() {
		if (initialized) {  // 若已经初始化( initialized = true ) 时 直接返回 否则标记已经初始化
			return;
		}
		initialized = true;
		if (interfaceName == null || interfaceName.length() == 0) { // 校验接口名非空
			throw new IllegalStateException("<dubbo:reference interface=\"\" /> interface not allow null!");
		}
		// get consumer's global configuration
		// 拼接属性配置(环境变量 + properties 属性)到 ConsumerConfig 对象
		checkDefault(); // 获取消费者全局配置
		// 拼接属性配置(环境变量 + properties 属性)到 ReferenceConfig 对象
		appendProperties(this); // 从jvm运行变量中取相应的值写到consumer
		if (getGeneric() == null && getConsumer() != null) {    // 若未设置 `generic` 属性 使用 `ConsumerConfig.generic` 属性
			setGeneric(getConsumer().getGeneric());
		}
		if (ProtocolUtils.isGeneric(getGeneric())) {    // 泛化接口的实现
			interfaceClass = GenericService.class;
		} else {    // 普通接口的实现
			try {
				// 根据 interfaceName 获得对应的接口类 并赋值给 interfaceClass
				interfaceClass = Class.forName(interfaceName, true, Thread.currentThread().getContextClassLoader());
			} catch (ClassNotFoundException e) {
				throw new IllegalStateException(e.getMessage(), e);
			}
			checkInterfaceAndMethods(interfaceClass, methods);  // 校验接口和方法
		}
		// 直连提供者 见文档<直连提供者> http://dubbo.apache.org/zh-cn/docs/user/demos/explicit-target.html
		// [直连提供者]第一优先级 通过 -D 参数指定 例如 java -Dcom.alibaba.xxx.XxxService=dubbo://localhost:20890
		String resolve = System.getProperty(interfaceName);
		String resolveFile = null;
		if (resolve == null || resolve.length() == 0) {
			// [直连提供者]第二优先级 通过文件映射 例如 com.alibaba.xxx.XxxService=dubbo://localhost:20890
			// 默认先加载 `${user.home}/dubbo-resolve.properties` 文件 无需配置
			resolveFile = System.getProperty("dubbo.resolve.file");
			if (resolveFile == null || resolveFile.length() == 0) {
				File userResolveFile = new File(new File(System.getProperty("user.home")), "dubbo-resolve.properties");
				if (userResolveFile.exists()) {
					resolveFile = userResolveFile.getAbsolutePath();
				}
			}
			if (resolveFile != null && resolveFile.length() > 0) {  // 存在 resolveFile 则进行文件读取加载
				Properties properties = new Properties();
				FileInputStream fis = null;
				try {
					fis = new FileInputStream(new File(resolveFile));
					properties.load(fis);
				} catch (IOException e) {
					throw new IllegalStateException("Unload " + resolveFile + ", cause: " + e.getMessage(), e);
				} finally {
					try {
						if (null != fis) fis.close();
					} catch (IOException e) {
						logger.warn(e.getMessage(), e);
					}
				}
				resolve = properties.getProperty(interfaceName);
			}
		}
		if (resolve != null && resolve.length() > 0) {  // 设置直连提供者的 url
			url = resolve;
			if (logger.isWarnEnabled()) {
				if (resolveFile != null) {
					logger.warn("Using default dubbo resolve file " + resolveFile + " replace " + interfaceName + "" + resolve + " to p2p invoke remote service.");
				} else {
					logger.warn("Using -D" + interfaceName + "=" + resolve + " to p2p invoke remote service.");
				}
			}
		}
		// 从 ConsumerConfig 对象中 读取 applicationConfig | moduleConfig | RegistryConfig | MonitorConfig 对象赋值给ReferenceConfig自身
		if (consumer != null) {
			if (application == null) {
				application = consumer.getApplication();
			}
			if (module == null) {
				module = consumer.getModule();
			}
			if (registries == null) {
				registries = consumer.getRegistries();
			}
			if (monitor == null) {
				monitor = consumer.getMonitor();
			}
		}
		if (module != null) {   // 从 ModuleConfig 对象中 读取 registries monitor 配置对象
			if (registries == null) {
				registries = module.getRegistries();
			}
			if (monitor == null) {
				monitor = module.getMonitor();
			}
		}
		if (application != null) {  // 从 ApplicationConfig 对象中 读取 registries | monitor 配置对象
			if (registries == null) {
				registries = application.getRegistries();
			}
			if (monitor == null) {
				monitor = application.getMonitor();
			}
		}
		checkApplication(); // 校验 ApplicationConfig 配置
		checkStub(interfaceClass);  // 校验 Stub 相关的配置
		checkMock(interfaceClass);  // 校验 Mock 相关的配置
		// 将 `side` | `dubbo` | `timestamp` | `pid` 参数 添加到 `map` 集合中
		Map<String, String> map = new HashMap<>();
		Map<Object, Object> attributes = new HashMap<>();
		map.put(Constants.SIDE_KEY, Constants.CONSUMER_SIDE);
		map.put(Constants.DUBBO_VERSION_KEY, Version.getProtocolVersion());
		map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
		if (ConfigUtils.getPid() > 0) {
			map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
		}
		// 将 `interface` | `methods` | `revision` 到 map 中
		if (!isGeneric()) {
			String revision = Version.getVersion(interfaceClass, version);
			if (revision != null && revision.length() > 0) {
				map.put("revision", revision);
			}

			String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames(); // 获得方法数组
			if (methods.length == 0) {
				logger.warn("NO method found in service interface " + interfaceClass.getName());
				map.put("methods", Constants.ANY_VALUE);
			} else {
				map.put("methods", StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
			}
		}
		map.put(Constants.INTERFACE_KEY, interfaceName);
		//  将各种配置对象 添加到 `map` 集合中
		appendParameters(map, application);
		appendParameters(map, module);
		appendParameters(map, consumer, Constants.DEFAULT_KEY);
		appendParameters(map, this);
		// 获得服务键 作为前缀
		String prefix = StringUtils.getServiceKey(map);
		if (methods != null && !methods.isEmpty()) {
			for (MethodConfig method : methods) {   //  将 MethodConfig 对象 添加到 `map` 集合中
				appendParameters(map, method, method.getName());
				String retryKey = method.getName() + ".retry";
				if (map.containsKey(retryKey)) {    // 当 配置了 `MethodConfig.retry = false` 时 强制禁用重试
					String retryValue = map.remove(retryKey);
					if ("false".equals(retryValue)) {
						map.put(method.getName() + ".retries", "0");
					}
				}
				// 将带有 @Parameter(attribute = true) 配置对象的属性 添加到参数集合 见<事件通知> http://dubbo.apache.org/zh-cn/docs/user/demos/events-notify.html
				appendAttributes(attributes, method, prefix + "." + method.getName());
				// 检查属性集合中的事件通知方法是否正确 若正确进行转换
				checkAndConvertImplicitConfig(method, map, attributes);
			}
		}

		// 以系统环境变量( DUBBO_IP_TO_REGISTRY ) 作为服务注册地址 见 https://github.com/dubbo/dubbo-docker-sample 项目
		String hostToRegistry = ConfigUtils.getSystemProperty(Constants.DUBBO_IP_TO_REGISTRY);
		if (hostToRegistry == null || hostToRegistry.length() == 0) {
			hostToRegistry = NetUtils.getLocalHost();
		} else if (isInvalidLocalHost(hostToRegistry)) {
			throw new IllegalArgumentException("Specified invalid registry ip from property:" + Constants.DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
		}
		map.put(Constants.REGISTER_IP_KEY, hostToRegistry);

		//attributes are stored by system context.
		// 添加到 StaticContext 进行缓存
		StaticContext.getSystemContext().putAll(attributes);
		ref = createProxy(map); // 创建代理对象
		ConsumerModel consumerModel = new ConsumerModel(getUniqueServiceName(), this, ref, interfaceClass.getMethods());
		ApplicationModel.initConsumerModel(getUniqueServiceName(), consumerModel);
	}

	@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
	private T createProxy(Map<String, String> map) {
		URL tmpUrl = new URL("temp", "localhost", 0, map);
		final boolean isJvmRefer;
		if (isInjvm() == null) {
			if (url != null && url.length() > 0) { // if a url is specified, don't do local reference
				isJvmRefer = false;
			} else if (InjvmProtocol.getInjvmProtocol().isInjvmRefer(tmpUrl)) {
				// by default, reference local service if there is
				isJvmRefer = true;
			} else {
				isJvmRefer = false;
			}
		} else {
			isJvmRefer = isInjvm().booleanValue();
		}

		if (isJvmRefer) {
			URL url = new URL(Constants.LOCAL_PROTOCOL, NetUtils.LOCALHOST, 0, interfaceClass.getName()).addParameters(map);
			invoker = refprotocol.refer(interfaceClass, url);
			if (logger.isInfoEnabled()) {
				logger.info("Using injvm service " + interfaceClass.getName());
			}
		} else {
			if (url != null && url.length() > 0) { // user specified URL, could be peer-to-peer address, or register center's address.
				String[] us = Constants.SEMICOLON_SPLIT_PATTERN.split(url);
				if (us != null && us.length > 0) {
					for (String u : us) {
						URL url = URL.valueOf(u);
						if (url.getPath() == null || url.getPath().length() == 0) {
							url = url.setPath(interfaceName);
						}
						if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
							urls.add(url.addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map)));
						} else {
							urls.add(ClusterUtils.mergeUrl(url, map));
						}
					}
				}
			} else { // assemble URL from register center's configuration
				List<URL> us = loadRegistries(false);
				if (us != null && !us.isEmpty()) {
					for (URL u : us) {
						URL monitorUrl = loadMonitor(u);
						if (monitorUrl != null) {
							map.put(Constants.MONITOR_KEY, URL.encode(monitorUrl.toFullString()));
						}
						urls.add(u.addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map)));
					}
				}
				if (urls.isEmpty()) {
					throw new IllegalStateException("No such any registry to reference " + interfaceName + " on the consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() + ", please config <dubbo:registry address=\"...\" /> to your spring config.");
				}
			}

			if (urls.size() == 1) {
				invoker = refprotocol.refer(interfaceClass, urls.get(0));   // 通过refer方法获取invoker对象
			} else {
				List<Invoker<?>> invokers = new ArrayList<>();
				URL registryURL = null;
				for (URL url : urls) {
					invokers.add(refprotocol.refer(interfaceClass, url));
					if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
						registryURL = url; // use last registry url
					}
				}
				if (registryURL != null) { // registry url is available
					// use AvailableCluster only when register's cluster is available
					URL u = registryURL.addParameterIfAbsent(Constants.CLUSTER_KEY, AvailableCluster.NAME);
					invoker = cluster.join(new StaticDirectory(u, invokers));
				} else { // not a registry url
					invoker = cluster.join(new StaticDirectory(invokers));
				}
			}
		}

		Boolean c = check;
		if (c == null && consumer != null) {
			c = consumer.isCheck();
		}
		if (c == null) {
			c = true; // default true
		}
		if (c && !invoker.isAvailable()) {
			// make it possible for consumer to retry later if provider is temporarily unavailable
			initialized = false;
			throw new IllegalStateException("Failed to check the status of the service " + interfaceName + ". No provider available for the service " + (group == null ? "" : group + "/") + interfaceName + (version == null ? "" : ":" + version) + " from the url " + invoker.getUrl() + " to the consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion());
		}
		if (logger.isInfoEnabled()) {
			logger.info("Refer dubbo service " + interfaceClass.getName() + " from url " + invoker.getUrl());
		}
		// create service proxy
		return (T) proxyFactory.getProxy(invoker);  // 返回接口的代理对象
	}

	/**
	 * 校验 ConsumerConfig 配置
	 * 实际上 会拼接属性配置(环境变量 + properties 属性)到 ConsumerConfig 对象
	 */
	private void checkDefault() {
		if (consumer == null) {
			consumer = new ConsumerConfig();
		}
		appendProperties(consumer);
	}

	public Class<?> getInterfaceClass() {
		if (interfaceClass != null) {
			return interfaceClass;
		}
		if (isGeneric()
				|| (getConsumer() != null && getConsumer().isGeneric())) {
			return GenericService.class;
		}
		try {
			if (interfaceName != null && interfaceName.length() > 0) {
				this.interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
						.getContextClassLoader());
			}
		} catch (ClassNotFoundException t) {
			throw new IllegalStateException(t.getMessage(), t);
		}
		return interfaceClass;
	}

	/**
	 * @param interfaceClass
	 * @see #setInterface(Class)
	 * @deprecated
	 */
	@Deprecated
	public void setInterfaceClass(Class<?> interfaceClass) {
		setInterface(interfaceClass);
	}

	public String getInterface() {
		return interfaceName;
	}

	public void setInterface(Class<?> interfaceClass) {
		if (interfaceClass != null && !interfaceClass.isInterface()) {
			throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
		}
		this.interfaceClass = interfaceClass;
		setInterface(interfaceClass == null ? null : interfaceClass.getName());
	}

	public void setInterface(String interfaceName) {
		this.interfaceName = interfaceName;
		if (id == null || id.length() == 0) {
			id = interfaceName;
		}
	}

	public String getClient() {
		return client;
	}

	public void setClient(String client) {
		checkName("client", client);
		this.client = client;
	}

	@Parameter(excluded = true)
	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public List<MethodConfig> getMethods() {
		return methods;
	}

	@SuppressWarnings("unchecked")
	public void setMethods(List<? extends MethodConfig> methods) {
		this.methods = (List<MethodConfig>) methods;
	}

	public ConsumerConfig getConsumer() {
		return consumer;
	}

	public void setConsumer(ConsumerConfig consumer) {
		this.consumer = consumer;
	}

	public String getProtocol() {
		return protocol;
	}

	public void setProtocol(String protocol) {
		this.protocol = protocol;
	}

	// just for test
	Invoker<?> getInvoker() {
		return invoker;
	}

	@Parameter(excluded = true)
	public String getUniqueServiceName() {
		StringBuilder buf = new StringBuilder();
		if (group != null && group.length() > 0) {
			buf.append(group).append("/");
		}
		buf.append(interfaceName);
		if (version != null && version.length() > 0) {
			buf.append(":").append(version);
		}
		return buf.toString();
	}

}
