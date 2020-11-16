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
import com.alibaba.dubbo.common.utils.ClassHelper;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.dubbo.config.invoker.DelegateProviderMetaDataInvoker;
import com.alibaba.dubbo.config.model.ApplicationModel;
import com.alibaba.dubbo.config.model.ProviderModel;
import com.alibaba.dubbo.config.support.Parameter;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.StaticContext;
import com.alibaba.dubbo.rpc.cluster.ConfiguratorFactory;
import com.alibaba.dubbo.rpc.service.GenericService;
import com.alibaba.dubbo.rpc.support.ProtocolUtils;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.alibaba.dubbo.common.utils.NetUtils.LOCALHOST;
import static com.alibaba.dubbo.common.utils.NetUtils.getAvailablePort;
import static com.alibaba.dubbo.common.utils.NetUtils.getLocalHost;
import static com.alibaba.dubbo.common.utils.NetUtils.isInvalidLocalHost;
import static com.alibaba.dubbo.common.utils.NetUtils.isInvalidPort;

/**
 * ServiceConfig
 * 服务提供者暴露服务配置类
 *
 * @export
 */
public class ServiceConfig<T> extends AbstractServiceConfig {

	private static final long serialVersionUID = 3033787999037024738L;

	/**
	 * 选择协议 将Invoker对象转换为Exporter对象
	 */
	private static final Protocol protocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

	/**
	 * 通过代理工厂的getInvoker方法获取Invoker对象 代理的具体实现可以是JdkProxyFactory也可以是JavassistProxyFactory
	 */
	private static final ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

	private static final Map<String, Integer> RANDOM_PORT_MAP = new HashMap<String, Integer>();

	private static final ScheduledExecutorService delayExportExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DubboServiceDelayExporter", true));
	private final List<URL> urls = new ArrayList<URL>();
	private final List<Exporter<?>> exporters = new ArrayList<Exporter<?>>();
	// interface type
	private String interfaceName;
	private Class<?> interfaceClass;
	// reference to interface impl
	// 业务逻辑层的具体实现类的引用
	private T ref;
	// service name
	private String path;
	// method configuration
	private List<MethodConfig> methods;
	private ProviderConfig provider;
	private transient volatile boolean exported;

	private transient volatile boolean unexported;

	private volatile String generic;

	public ServiceConfig() {
	}

	public ServiceConfig(Service service) {
		appendAnnotation(Service.class, service);
		setMethods(MethodConfig.constructMethodConfig(service.methods()));
	}

	@Deprecated
	private static List<ProtocolConfig> convertProviderToProtocol(List<ProviderConfig> providers) {
		if (providers == null || providers.isEmpty()) {
			return null;
		}
		List<ProtocolConfig> protocols = new ArrayList<>(providers.size());
		for (ProviderConfig provider : providers) {
			protocols.add(convertProviderToProtocol(provider));
		}
		return protocols;
	}

	@Deprecated
	private static List<ProviderConfig> convertProtocolToProvider(List<ProtocolConfig> protocols) {
		if (protocols == null || protocols.isEmpty()) {
			return null;
		}
		List<ProviderConfig> providers = new ArrayList<>(protocols.size());
		for (ProtocolConfig provider : protocols) {
			providers.add(convertProtocolToProvider(provider));
		}
		return providers;
	}

	@Deprecated
	private static ProtocolConfig convertProviderToProtocol(ProviderConfig provider) {
		ProtocolConfig protocol = new ProtocolConfig();
		protocol.setName(provider.getProtocol().getName());
		protocol.setServer(provider.getServer());
		protocol.setClient(provider.getClient());
		protocol.setCodec(provider.getCodec());
		protocol.setHost(provider.getHost());
		protocol.setPort(provider.getPort());
		protocol.setPath(provider.getPath());
		protocol.setPayload(provider.getPayload());
		protocol.setThreads(provider.getThreads());
		protocol.setParameters(provider.getParameters());
		return protocol;
	}

	@Deprecated
	private static ProviderConfig convertProtocolToProvider(ProtocolConfig protocol) {
		ProviderConfig provider = new ProviderConfig();
		provider.setProtocol(protocol);
		provider.setServer(protocol.getServer());
		provider.setClient(protocol.getClient());
		provider.setCodec(protocol.getCodec());
		provider.setHost(protocol.getHost());
		provider.setPort(protocol.getPort());
		provider.setPath(protocol.getPath());
		provider.setPayload(protocol.getPayload());
		provider.setThreads(protocol.getThreads());
		provider.setParameters(protocol.getParameters());
		return provider;
	}

	private static Integer getRandomPort(String protocol) {
		protocol = protocol.toLowerCase();
		if (RANDOM_PORT_MAP.containsKey(protocol)) {
			return RANDOM_PORT_MAP.get(protocol);
		}
		return Integer.MIN_VALUE;
	}

	private static void putRandomPort(String protocol, Integer port) {
		protocol = protocol.toLowerCase();
		if (!RANDOM_PORT_MAP.containsKey(protocol)) {
			RANDOM_PORT_MAP.put(protocol, port);
		}
	}

	public URL toUrl() {
		return urls.isEmpty() ? null : urls.iterator().next();
	}

	public List<URL> toUrls() {
		return urls;
	}

	@Parameter(excluded = true)
	public boolean isExported() {
		return exported;
	}

	@Parameter(excluded = true)
	public boolean isUnexported() {
		return unexported;
	}

	/**
	 * ServiceConfig初始化的最后一步
	 * 提供暴露服务的功能
	 */
	public synchronized void export() {
		if (provider != null) { // 当 export 或者 delay 为 true 从 ProviderConfig 对象读取
			if (export == null) {
				export = provider.getExport();
			}
			if (delay == null) {
				delay = provider.getDelay();
			}
		}
		if (export != null && !export) {    // 如果不暴露服务( export = false ) 则不进行暴露服务逻辑直接返回
			return;
		}

		if (delay != null && delay > 0) {   // 当配置延迟暴露( delay > 0 )时 使用 delayExportExecutor 延迟调度 调用 #doExport() 方法
			delayExportExecutor.schedule(() -> doExport(), delay, TimeUnit.MILLISECONDS);
		} else {    // 立即暴露
			doExport();
		}
	}

	/**
	 * 执行服务暴露的逻辑
	 */
	protected synchronized void doExport() {
		if (unexported) {   // 检查是否可以暴露 若可以标记已经暴露
			throw new IllegalStateException("Already unexported!");
		}
		if (exported) {
			return;
		}
		exported = true;
		if (interfaceName == null || interfaceName.length() == 0) { // 校验接口名非空
			throw new IllegalStateException("<dubbo:service interface=\"\" /> interface not allow null!");
		}
		checkDefault(); // 读取环境变量和properties配置用于初始化ProtocolConfig
		// 拼接属性配置(环境变量 + properties 属性)到 ProviderConfig 对象
		if (provider != null) { // 从 ProviderConfig 对象中 读取 application | module | registries | monitor | protocols 配置对象
			if (application == null) {
				application = provider.getApplication();
			}
			if (module == null) {
				module = provider.getModule();
			}
			if (registries == null) {
				registries = provider.getRegistries();
			}
			if (monitor == null) {
				monitor = provider.getMonitor();
			}
			if (protocols == null) {
				protocols = provider.getProtocols();
			}
		}
		if (module != null) {   // 从 ModuleConfig 对象中 读取 registries | monitor 配置对象
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
		if (ref instanceof GenericService) {    // 泛化接口的实现
			interfaceClass = GenericService.class;
			if (StringUtils.isEmpty(generic)) {
				generic = Boolean.TRUE.toString();
			}
		} else {    // 普通接口的实现
			try {
				interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
						.getContextClassLoader());
			} catch (ClassNotFoundException e) {
				throw new IllegalStateException(e.getMessage(), e);
			}
			checkInterfaceAndMethods(interfaceClass, methods);  // 校验接口和方法
			checkRef(); // 校验指向的 service 对象
			generic = Boolean.FALSE.toString();
		}
		if (local != null) {    // 处理服务接口客户端本地代理( `local` )相关 实际目前已经废弃 此处主要用于兼容 使用 `stub` 属性 见 `AbstractInterfaceConfig#setLocal` 方法
			if ("true".equals(local)) { // 设为 true 表示使用缺省代理类名 即: 接口名 + Local 后缀
				local = interfaceName + "Local";
			}
			Class<?> localClass;
			try {
				localClass = ClassHelper.forNameWithThreadContextClassLoader(local);
			} catch (ClassNotFoundException e) {
				throw new IllegalStateException(e.getMessage(), e);
			}
			if (!interfaceClass.isAssignableFrom(localClass)) {
				throw new IllegalStateException("The local implementation class " + localClass.getName() + " not implement interface " + interfaceName);
			}
		}
		if (stub != null) { // 处理服务接口客户端本地代理( `stub` )相关
			if ("true".equals(stub)) {  // 设为 true 表示使用缺省代理类名 即: 接口名 + Stub 后缀
				stub = interfaceName + "Stub";
			}
			Class<?> stubClass;
			try {
				stubClass = ClassHelper.forNameWithThreadContextClassLoader(stub);
			} catch (ClassNotFoundException e) {
				throw new IllegalStateException(e.getMessage(), e);
			}
			if (!interfaceClass.isAssignableFrom(stubClass)) {
				throw new IllegalStateException("The stub implementation class " + stubClass.getName() + " not implement interface " + interfaceName);
			}
		}
		checkApplication(); // 校验 ApplicationConfig 配置
		checkRegistry();    // 校验 RegistryConfig 配置
		checkProtocol();    // 校验 ProtocolConfig 配置数组
		appendProperties(this); // 读取环境变量和 properties 配置到 ServiceConfig 对象
		checkStub(interfaceClass);  // 校验 Stub 相关的配置
		checkMock(interfaceClass);  // 校验 Mock 相关的配置
		if (path == null || path.length() == 0) {   // 服务路径 缺省为接口名
			path = interfaceName;
		}
		doExportUrls();
		ProviderModel providerModel = new ProviderModel(getUniqueServiceName(), this, ref);
		ApplicationModel.initProviderModel(getUniqueServiceName(), providerModel);
	}

	private void checkRef() {
		// reference should not be null, and is the implementation of the given interface
		if (ref == null) {
			throw new IllegalStateException("ref not allow null!");
		}
		if (!interfaceClass.isInstance(ref)) {
			throw new IllegalStateException("The class "
					+ ref.getClass().getName() + " unimplemented interface "
					+ interfaceClass + "!");
		}
	}

	public synchronized void unexport() {
		if (!exported) {
			return;
		}
		if (unexported) {
			return;
		}
		if (!exporters.isEmpty()) {
			for (Exporter<?> exporter : exporters) {
				try {
					exporter.unexport();
				} catch (Throwable t) {
					logger.warn("unexpected err when unexport" + exporter, t);
				}
			}
			exporters.clear();
		}
		unexported = true;
	}

	/**
	 * 暴露 Dubbo URL
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private void doExportUrls() {
		List<URL> registryURLs = loadRegistries(true);
		for (ProtocolConfig protocolConfig : protocols) {
			doExportUrlsFor1Protocol(protocolConfig, registryURLs);
		}
	}

	/**
	 * 使用 ServiceConfig 对象 生成 Dubbo URL 对象数组
	 *
	 * @param protocolConfig 协议配置对象
	 * @param registryURLs   注册中心链接对象数组
	 */
	private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs) {
		String name = protocolConfig.getName(); // 协议名
		if (name == null || name.length() == 0) {   // 协议名空时 缺省 "dubbo"
			name = "dubbo";
		}

		// 将 `side` | `dubbo` | `timestamp` | `pid` 参数添加到 `map` 集合中
		Map<String, String> map = new HashMap<>();
		map.put(Constants.SIDE_KEY, Constants.PROVIDER_SIDE);
		map.put(Constants.DUBBO_VERSION_KEY, Version.getProtocolVersion());
		map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
		if (ConfigUtils.getPid() > 0) {
			map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
		}
		//  将各种配置对象 添加到 `map` 集合中
		appendParameters(map, application);
		appendParameters(map, module);
		appendParameters(map, provider, Constants.DEFAULT_KEY); // // ProviderConfig  为 ServiceConfig 的默认属性 因此添加 `default` 属性前缀
		appendParameters(map, protocolConfig);
		appendParameters(map, this);
		if (methods != null && !methods.isEmpty()) {
			for (MethodConfig method : methods) {
				appendParameters(map, method, method.getName());    // 将 MethodConfig 对象 添加到 `map` 集合中
				// 当 配置了 `MethodConfig.retry = false` 时 强制禁用重试
				String retryKey = method.getName() + ".retry";
				if (map.containsKey(retryKey)) {
					String retryValue = map.remove(retryKey);
					if ("false".equals(retryValue)) {
						map.put(method.getName() + ".retries", "0");
					}
				}
				List<ArgumentConfig> arguments = method.getArguments();
				if (arguments != null && !arguments.isEmpty()) {
					for (ArgumentConfig argument : arguments) { // 将 ArgumentConfig 对象数组 添加到 `map` 集合中
						// convert argument type
						if (argument.getType() != null && argument.getType().length() > 0) {    // 指定了类型
							Method[] methods = interfaceClass.getMethods();
							// visit all methods
							if (methods != null && methods.length > 0) {
								for (int i = 0; i < methods.length; i++) {
									String methodName = methods[i].getName();
									// target the method, and get its signature
									if (methodName.equals(method.getName())) {  // 找到指定方法
										Class<?>[] argtypes = methods[i].getParameterTypes();
										// one callback in the method
										if (argument.getIndex() != -1) {
											if (argtypes[argument.getIndex()].getName().equals(argument.getType())) {    // 指定单个参数的位置 + 类型
												appendParameters(map, argument, method.getName() + "." + argument.getIndex());  // 将 ArgumentConfig 对象 添加到 `map` 集合中 // `${methodName}.${index}`
											} else {
												throw new IllegalArgumentException("argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
											}
										} else {
											// multiple callbacks in the method
											for (int j = 0; j < argtypes.length; j++) {
												Class<?> argclazz = argtypes[j];
												if (argclazz.getName().equals(argument.getType())) {
													appendParameters(map, argument, method.getName() + "." + j);    // 将 ArgumentConfig 对象 添加到 `map` 集合中    // `${methodName}.${index}`
													if (argument.getIndex() != -1 && argument.getIndex() != j) {    // 多余的判断 因为 `argument.getIndex() == -1`
														throw new IllegalArgumentException("argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
													}
												}
											}
										}
									}
								}
							}
						} else if (argument.getIndex() != -1) { // 指定单个参数的位置
							appendParameters(map, argument, method.getName() + "." + argument.getIndex());  // 将 ArgumentConfig 对象 添加到 `map` 集合中 // `${methodName}.${index}`
						} else {
							throw new IllegalArgumentException("argument config must set index or type attribute.eg: <dubbo:argument index='0' .../> or <dubbo:argument type=xxx .../>");
						}

					}
				}
			} // end of methods for
		}

		if (ProtocolUtils.isGeneric(generic)) {
			map.put(Constants.GENERIC_KEY, generic);
			map.put(Constants.METHODS_KEY, Constants.ANY_VALUE);
		} else {
			String revision = Version.getVersion(interfaceClass, version);
			if (revision != null && revision.length() > 0) {
				map.put("revision", revision);
			}

			String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
			if (methods.length == 0) {
				logger.warn("NO method found in service interface " + interfaceClass.getName());
				map.put(Constants.METHODS_KEY, Constants.ANY_VALUE);
			} else {
				map.put(Constants.METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
			}
		}
		if (!ConfigUtils.isEmpty(token)) {  // token 见<令牌校验> https://dubbo.gitbooks.io/dubbo-user-book/demos/token-authorization.html
			if (ConfigUtils.isDefault(token)) {
				map.put(Constants.TOKEN_KEY, UUID.randomUUID().toString());
			} else {
				map.put(Constants.TOKEN_KEY, token);
			}
		}
		if (Constants.LOCAL_PROTOCOL.equals(protocolConfig.getName())) {    // 协议为 injvm 时 不注册 不通知
			protocolConfig.setRegister(false);
			map.put("notify", "false");
		}
		// export service
		// 获得 contextPath 基础路径 即java web应用中常说的context path
		String contextPath = protocolConfig.getContextpath();
		if ((contextPath == null || contextPath.length() == 0) && provider != null) {
			contextPath = provider.getContextpath();
		}

		// 调用 #findConfigedHosts(protocolConfig, registryURLs, map) 方法 获得注册到注册中心的服务提供者 Host
		String host = this.findConfigedHosts(protocolConfig, registryURLs, map);
		Integer port = this.findConfigedPorts(protocolConfig, name, map);
		URL url = new URL(name, host, port, (contextPath == null || contextPath.length() == 0 ? "" : contextPath + "/") + path, map);   // 创建 Dubbo URL 对象

		if (ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class).hasExtension(url.getProtocol())) { // 路由规则配置 见<配置规则> https://dubbo.gitbooks.io/dubbo-user-book/demos/config-rule.html
			url = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
					.getExtension(url.getProtocol()).getConfigurator(url).configure(url);
		}
		// ==========================================>服务暴露逻辑start
		String scope = url.getParameter(Constants.SCOPE_KEY);
		// don't export when none is configured
		if (!Constants.SCOPE_NONE.equalsIgnoreCase(scope)) {

			// export to local if the config is not remote (export to remote only when config is remote)
			if (!Constants.SCOPE_REMOTE.equalsIgnoreCase(scope)) {
				exportLocal(url);
			}
			// export to remote if the config is not local (export to local only when config is local)
			if (!Constants.SCOPE_LOCAL.equalsIgnoreCase(scope)) {
				if (logger.isInfoEnabled()) {
					logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
				}
				if (registryURLs != null && !registryURLs.isEmpty()) {
					for (URL registryURL : registryURLs) {
						url = url.addParameterIfAbsent(Constants.DYNAMIC_KEY, registryURL.getParameter(Constants.DYNAMIC_KEY));
						URL monitorUrl = loadMonitor(registryURL);
						if (monitorUrl != null) {
							url = url.addParameterAndEncoded(Constants.MONITOR_KEY, monitorUrl.toFullString());
						}
						if (logger.isInfoEnabled()) {
							logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to registry " + registryURL);
						}

						// For providers, this is used to enable custom proxy to generate invoker
						String proxy = url.getParameter(Constants.PROXY_KEY);
						if (StringUtils.isNotEmpty(proxy)) {
							registryURL = registryURL.addParameter(Constants.PROXY_KEY, proxy);
						}
						// 1.调用getInvoker方法生成Invoker并包装为DelegateProviderMetaDataInvoker对象 使用了Dubbo SPI最终交给了JavassistProxyFactory处理
						Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, registryURL.addParameterAndEncoded(Constants.EXPORT_KEY, url.toFullString()));
						DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

						// 2.通过protocol将Invoker对象转换为Exporter
						Exporter<?> exporter = protocol.export(wrapperInvoker);
						exporters.add(exporter);
					}
				} else {
					Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, url);
					DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

					Exporter<?> exporter = protocol.export(wrapperInvoker);
					exporters.add(exporter);
				}
			}
		}
		// ==========================================>服务暴露逻辑end
		this.urls.add(url);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void exportLocal(URL url) {
		if (!Constants.LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
			URL local = URL.valueOf(url.toFullString())
					.setProtocol(Constants.LOCAL_PROTOCOL)
					.setHost(LOCALHOST)
					.setPort(0);
			StaticContext.getContext(Constants.SERVICE_IMPL_CLASS).put(url.getServiceKey(), getServiceClass(ref));
			Exporter<?> exporter = protocol.export(
					proxyFactory.getInvoker(ref, (Class) interfaceClass, local));
			exporters.add(exporter);
			logger.info("Export dubbo service " + interfaceClass.getName() + " to local registry");
		}
	}

	protected Class getServiceClass(T ref) {
		return ref.getClass();
	}

	/**
	 * Register & bind IP address for service provider, can be configured separately.
	 * Configuration priority: environment variables -> java system properties -> host property in config file ->
	 * /etc/hosts -> default network address -> first available network address
	 *
	 * @param protocolConfig
	 * @param registryURLs
	 * @param map
	 * @return
	 */
	private String findConfigedHosts(ProtocolConfig protocolConfig, List<URL> registryURLs, Map<String, String> map) {
		boolean anyhost = false;

		String hostToBind = getValueFromConfig(protocolConfig, Constants.DUBBO_IP_TO_BIND);
		if (hostToBind != null && hostToBind.length() > 0 && isInvalidLocalHost(hostToBind)) {
			throw new IllegalArgumentException("Specified invalid bind ip from property:" + Constants.DUBBO_IP_TO_BIND + ", value:" + hostToBind);
		}

		// if bind ip is not found in environment, keep looking up
		if (hostToBind == null || hostToBind.length() == 0) {
			hostToBind = protocolConfig.getHost();
			if (provider != null && (hostToBind == null || hostToBind.length() == 0)) {
				hostToBind = provider.getHost();
			}
			if (isInvalidLocalHost(hostToBind)) {
				anyhost = true;
				try {
					hostToBind = InetAddress.getLocalHost().getHostAddress();
				} catch (UnknownHostException e) {
					logger.warn(e.getMessage(), e);
				}
				if (isInvalidLocalHost(hostToBind)) {
					if (registryURLs != null && !registryURLs.isEmpty()) {
						for (URL registryURL : registryURLs) {
							if (Constants.MULTICAST.equalsIgnoreCase(registryURL.getParameter("registry"))) {
								// skip multicast registry since we cannot connect to it via Socket
								continue;
							}
							try {
								Socket socket = new Socket();
								try {
									SocketAddress addr = new InetSocketAddress(registryURL.getHost(), registryURL.getPort());
									socket.connect(addr, 1000);
									hostToBind = socket.getLocalAddress().getHostAddress();
									break;
								} finally {
									try {
										socket.close();
									} catch (Throwable e) {
									}
								}
							} catch (Exception e) {
								logger.warn(e.getMessage(), e);
							}
						}
					}
					if (isInvalidLocalHost(hostToBind)) {
						hostToBind = getLocalHost();
					}
				}
			}
		}

		map.put(Constants.BIND_IP_KEY, hostToBind);

		// registry ip is not used for bind ip by default
		String hostToRegistry = getValueFromConfig(protocolConfig, Constants.DUBBO_IP_TO_REGISTRY);
		if (hostToRegistry != null && hostToRegistry.length() > 0 && isInvalidLocalHost(hostToRegistry)) {
			throw new IllegalArgumentException("Specified invalid registry ip from property:" + Constants.DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
		} else if (hostToRegistry == null || hostToRegistry.length() == 0) {
			// bind ip is used as registry ip by default
			hostToRegistry = hostToBind;
		}

		map.put(Constants.ANYHOST_KEY, String.valueOf(anyhost));

		return hostToRegistry;
	}

	/**
	 * Register port and bind port for the provider, can be configured separately
	 * Configuration priority: environment variable -> java system properties -> port property in protocol config file
	 * -> protocol default port
	 *
	 * @param protocolConfig
	 * @param name
	 * @return
	 */
	private Integer findConfigedPorts(ProtocolConfig protocolConfig, String name, Map<String, String> map) {
		Integer portToBind = null;

		// parse bind port from environment
		String port = getValueFromConfig(protocolConfig, Constants.DUBBO_PORT_TO_BIND);
		portToBind = parsePort(port);

		// if there's no bind port found from environment, keep looking up.
		if (portToBind == null) {
			portToBind = protocolConfig.getPort();
			if (provider != null && (portToBind == null || portToBind == 0)) {
				portToBind = provider.getPort();
			}
			final int defaultPort = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(name).getDefaultPort();
			if (portToBind == null || portToBind == 0) {
				portToBind = defaultPort;
			}
			if (portToBind == null || portToBind <= 0) {
				portToBind = getRandomPort(name);
				if (portToBind == null || portToBind < 0) {
					portToBind = getAvailablePort(defaultPort);
					putRandomPort(name, portToBind);
				}
				logger.warn("Use random available port(" + portToBind + ") for protocol " + name);
			}
		}

		// save bind port, used as url's key later
		map.put(Constants.BIND_PORT_KEY, String.valueOf(portToBind));

		// registry port, not used as bind port by default
		String portToRegistryStr = getValueFromConfig(protocolConfig, Constants.DUBBO_PORT_TO_REGISTRY);
		Integer portToRegistry = parsePort(portToRegistryStr);
		if (portToRegistry == null) {
			portToRegistry = portToBind;
		}

		return portToRegistry;
	}

	private Integer parsePort(String configPort) {
		Integer port = null;
		if (configPort != null && configPort.length() > 0) {
			try {
				Integer intPort = Integer.parseInt(configPort);
				if (isInvalidPort(intPort)) {
					throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
				}
				port = intPort;
			} catch (Exception e) {
				throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
			}
		}
		return port;
	}

	private String getValueFromConfig(ProtocolConfig protocolConfig, String key) {
		String protocolPrefix = protocolConfig.getName().toUpperCase() + "_";
		String port = ConfigUtils.getSystemProperty(protocolPrefix + key);
		if (port == null || port.length() == 0) {
			port = ConfigUtils.getSystemProperty(key);
		}
		return port;
	}

	private void checkDefault() {
		if (provider == null) {
			provider = new ProviderConfig();
		}
		appendProperties(provider);
	}

	private void checkProtocol() {
		if ((protocols == null || protocols.isEmpty())
				&& provider != null) {
			setProtocols(provider.getProtocols());
		}
		// backward compatibility
		if (protocols == null || protocols.isEmpty()) {
			setProtocol(new ProtocolConfig());
		}
		for (ProtocolConfig protocolConfig : protocols) {
			if (StringUtils.isEmpty(protocolConfig.getName())) {
				protocolConfig.setName(Constants.DUBBO_VERSION_KEY);
			}
			appendProperties(protocolConfig);
		}
	}

	public Class<?> getInterfaceClass() {
		if (interfaceClass != null) {
			return interfaceClass;
		}
		if (ref instanceof GenericService) {
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
	public void setInterfaceClass(Class<?> interfaceClass) {
		setInterface(interfaceClass);
	}

	public String getInterface() {
		return interfaceName;
	}

	public void setInterface(String interfaceName) {
		this.interfaceName = interfaceName;
		if (id == null || id.length() == 0) {
			id = interfaceName;
		}
	}

	public void setInterface(Class<?> interfaceClass) {
		if (interfaceClass != null && !interfaceClass.isInterface()) {
			throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
		}
		this.interfaceClass = interfaceClass;
		setInterface(interfaceClass == null ? null : interfaceClass.getName());
	}

	public T getRef() {
		return ref;
	}

	public void setRef(T ref) {
		this.ref = ref;
	}

	@Parameter(excluded = true)
	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		checkPathName(Constants.PATH_KEY, path);
		this.path = path;
	}

	public List<MethodConfig> getMethods() {
		return methods;
	}

	// ======== Deprecated ========

	@SuppressWarnings("unchecked")
	public void setMethods(List<? extends MethodConfig> methods) {
		this.methods = (List<MethodConfig>) methods;
	}

	public ProviderConfig getProvider() {
		return provider;
	}

	public void setProvider(ProviderConfig provider) {
		this.provider = provider;
	}

	public String getGeneric() {
		return generic;
	}

	public void setGeneric(String generic) {
		if (StringUtils.isEmpty(generic)) {
			return;
		}
		if (ProtocolUtils.isGeneric(generic)) {
			this.generic = generic;
		} else {
			throw new IllegalArgumentException("Unsupported generic type " + generic);
		}
	}

	@Override
	public void setMock(Boolean mock) {
		throw new IllegalArgumentException("mock doesn't support on provider side");
	}

	@Override
	public void setMock(String mock) {
		throw new IllegalArgumentException("mock doesn't support on provider side");
	}

	public List<URL> getExportedUrls() {
		return urls;
	}

	/**
	 * @deprecated Replace to getProtocols()
	 */
	@Deprecated
	public List<ProviderConfig> getProviders() {
		return convertProtocolToProvider(protocols);
	}

	/**
	 * @deprecated Replace to setProtocols()
	 */
	@Deprecated
	public void setProviders(List<ProviderConfig> providers) {
		this.protocols = convertProviderToProtocol(providers);
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
