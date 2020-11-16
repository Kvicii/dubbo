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
package com.alibaba.dubbo.rpc.cluster.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * When invoke fails, log the initial error and retry other invokers (retry n times, which means at most n different invokers will be invoked)
 * Note that retry causes latency.
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Failover">Failover</a>
 */
public class FailoverClusterInvoker<T> extends AbstractClusterInvoker<T> {

	private static final Logger logger = LoggerFactory.getLogger(FailoverClusterInvoker.class);

	public FailoverClusterInvoker(Directory<T> directory) {
		super(directory);
	}

	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public Result doInvoke(Invocation invocation, final List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
		List<Invoker<T>> copyinvokers = invokers;
		checkInvokers(copyinvokers, invocation);    // 如果没有Invoker抛出异常
		String methodName = RpcUtils.getMethodName(invocation);
		// 获取该方法的最大重试次
		int len = getUrl().getMethodParameter(methodName, Constants.RETRIES_KEY, Constants.DEFAULT_RETRIES) + 1;
		if (len <= 0) {
			len = 1;
		}
		// retry loop.
		RpcException le = null; // last exception.
		List<Invoker<T>> invoked = new ArrayList<>(copyinvokers.size()); // invoked invokers.
		Set<String> providers = new HashSet<>(len);
		for (int i = 0; i < len; i++) { // 通过循环进行调用
			//Reselect before retry to avoid a change of candidate `invokers`.
			//NOTE: if `invokers` changed, then `invoked` also lose accuracy.
			if (i > 0) {
				checkWhetherDestroyed();    // 每一次都检查是否关闭consumer
				copyinvokers = list(invocation);    // 重新获取Invoker列表
				// check again
				checkInvokers(copyinvokers, invocation);    // 再次进行检查
			}
			Invoker<T> invoker = select(loadbalance, invocation, copyinvokers, invoked);    // 交给负载均衡器选择具体的Invoker
			invoked.add(invoker);   // 增加到已经执行过的Invoker列表
			RpcContext.getContext().setInvokers((List) invoked);
			try {
				Result result = invoker.invoke(invocation); // 执行调用操作
				if (le != null && logger.isWarnEnabled()) {
					logger.warn("Although retry the method " + invocation.getMethodName()
							+ " in the service " + getInterface().getName()
							+ " was successful by the provider " + invoker.getUrl().getAddress()
							+ ", but there have been failed providers " + providers
							+ " (" + providers.size() + "/" + copyinvokers.size()
							+ ") from the registry " + directory.getUrl().getAddress()
							+ " on the consumer " + NetUtils.getLocalHost()
							+ " using the dubbo version " + Version.getVersion() + ". Last error is: "
							+ le.getMessage(), le);
				}
				return result;
			} catch (RpcException e) {
				if (e.isBiz()) { // biz exception.  如果是业务异常则直接抛出
					throw e;
				}
				le = e;
			} catch (Throwable e) {
				le = new RpcException(e.getMessage(), e);
			} finally {
				providers.add(invoker.getUrl().getAddress());
			}
		}
		// 如果重试了指定次数后依旧失败 则直接认定为失败
		throw new RpcException(le != null ? le.getCode() : 0, "Failed to invoke the method "
				+ invocation.getMethodName() + " in the service " + getInterface().getName()
				+ ". Tried " + len + " times of the providers " + providers
				+ " (" + providers.size() + "/" + copyinvokers.size()
				+ ") from the registry " + directory.getUrl().getAddress()
				+ " on the consumer " + NetUtils.getLocalHost() + " using the dubbo version "
				+ Version.getVersion() + ". Last error is: "
				+ (le != null ? le.getMessage() : ""), le != null && le.getCause() != null ? le.getCause() : le);
	}

}
