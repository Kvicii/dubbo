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
package com.alibaba.dubbo.rpc.cluster.router.condition;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Router;
import com.alibaba.dubbo.rpc.cluster.router.AbstractRouter;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ConditionRouter
 * 条件路由
 */
public class ConditionRouter extends AbstractRouter {

	private static final Logger logger = LoggerFactory.getLogger(ConditionRouter.class);
	private static final int DEFAULT_PRIORITY = 2;
	private static Pattern ROUTE_PATTERN = Pattern.compile("([&!=,]*)\\s*([^&!=,\\s]+)");
	private final boolean force;
	// 是否满足判断条件
	private final Map<String, MatchPair> whenCondition;
	// 当满足判断条件时如何选择Invoker
	private final Map<String, MatchPair> thenCondition;

	public ConditionRouter(URL url) {
		this.url = url;
		this.priority = url.getParameter(Constants.PRIORITY_KEY, DEFAULT_PRIORITY);
		this.force = url.getParameter(Constants.FORCE_KEY, false);
		try {
			String rule = url.getParameterAndDecoded(Constants.RULE_KEY);
			if (rule == null || rule.trim().length() == 0) {    // 必须包含路由规则
				throw new IllegalArgumentException("Illegal route rule!");
			}
			rule = rule.replace("consumer.", "").replace("provider.", "");
			// 根据 => 解析路由规则
			int i = rule.indexOf("=>");
			String whenRule = i < 0 ? null : rule.substring(0, i).trim();
			String thenRule = i < 0 ? rule.trim() : rule.substring(i + 2).trim();
			// 分别生成provider和consumer的rule
			Map<String, MatchPair> when = StringUtils.isBlank(whenRule) || "true".equals(whenRule) ? new HashMap<>() : parseRule(whenRule);
			Map<String, MatchPair> then = StringUtils.isBlank(thenRule) || "false".equals(thenRule) ? null : parseRule(thenRule);
			// NOTE: It should be determined on the business level whether the `When condition` can be empty or not.
			this.whenCondition = when;
			this.thenCondition = then;
		} catch (ParseException e) {
			throw new IllegalStateException(e.getMessage(), e);
		}
	}

	/**
	 * 解析路由规则
	 *
	 * @param rule
	 * @return
	 * @throws ParseException
	 */
	private static Map<String, MatchPair> parseRule(String rule)
			throws ParseException {
		Map<String, MatchPair> condition = new HashMap<>();
		if (StringUtils.isBlank(rule)) {
			return condition;
		}
		// Key-Value pair, stores both match and mismatch conditions
		MatchPair pair = null;  // 当前操作的数据 用于在循环中使用 标识上一次循环所操作的信息
		// Multiple values
		Set<String> values = null;
		// 对每一个条件进行转换 如host = 1.1.1.* & host != 1.1.1.2 & method=say会被分割成以下几部分
		// 1. "" host
		// 2. "=" 1.1.1.x
		// 3. "&" host
		// 4. "!=" 1.1.1.2
		// ...
		final Matcher matcher = ROUTE_PATTERN.matcher(rule);
		while (matcher.find()) { // Try to match one by one
			String separator = matcher.group(1);    // 分割符
			String content = matcher.group(2);  // 内容
			// Start part of the condition expression.
			if (separator == null || separator.length() == 0) { // 如果不存在分隔符 则认为是首个判断
				pair = new MatchPair();
				condition.put(content, pair);   // 直接放入condition
			}
			// The KV part of the condition expression
			else if ("&".equals(separator)) {   // 如果是"&"则代表并且
				if (condition.get(content) == null) {   // 如果当前的when或者then中不包含该判断条件 则添加
					pair = new MatchPair();
					condition.put(content, pair);
				} else {    // 否则当前的condition就需要拿出来
					pair = condition.get(content);
				}
			}
			// The Value in the KV part.
			else if ("=".equals(separator)) {   // 如果是等于的比较 则需要将值放入matches中
				if (pair == null)
					throw new ParseException("Illegal route rule \""
							+ rule + "\", The error char '" + separator
							+ "' at index " + matcher.start() + " before \""
							+ content + "\".", matcher.start());

				values = pair.matches;
				values.add(content);
			}
			// The Value in the KV part.
			else if ("!=".equals(separator)) {  // 如果为不等于 则需要放入到mismatches中
				if (pair == null)
					throw new ParseException("Illegal route rule \""
							+ rule + "\", The error char '" + separator
							+ "' at index " + matcher.start() + " before \""
							+ content + "\".", matcher.start());

				values = pair.mismatches;
				values.add(content);
			}
			// The Value in the KV part, if Value have more than one items.
			else if (",".equals(separator)) { // Should be seperateed by ',' 如果values是多个的话  则分别加入到values列表中
				if (values == null || values.isEmpty())
					throw new ParseException("Illegal route rule \""
							+ rule + "\", The error char '" + separator
							+ "' at index " + matcher.start() + " before \""
							+ content + "\".", matcher.start());
				values.add(content);
			} else {
				throw new ParseException("Illegal route rule \"" + rule
						+ "\", The error char '" + separator + "' at index "
						+ matcher.start() + " before \"" + content + "\".", matcher.start());
			}
		}
		return condition;
	}

	/**
	 * 根据路由规则选择Invoker列表
	 *
	 * @param invokers
	 * @param url        refer url
	 * @param invocation
	 * @param <T>
	 * @return
	 * @throws RpcException
	 */
	@Override
	public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation)
			throws RpcException {
		if (invokers == null || invokers.isEmpty()) {
			return invokers;
		}
		try {
			if (!matchWhen(url, invocation)) {  // 不满足条件直接返回
				return invokers;
			}
			List<Invoker<T>> result = new ArrayList<>();
			if (thenCondition == null) {
				logger.warn("The current consumer in the service blacklist. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey());
				return result;
			}
			for (Invoker<T> invoker : invokers) {
				if (matchThen(invoker.getUrl(), url)) { // 判断每一个Invoker的url是否满足条件
					result.add(invoker);
				}
			}
			if (!result.isEmpty()) {
				return result;
			} else if (force) {
				logger.warn("The route result is empty and force execute. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey() + ", router: " + url.getParameterAndDecoded(Constants.RULE_KEY));
				return result;
			}
		} catch (Throwable t) {
			logger.error("Failed to execute condition router rule: " + getUrl() + ", invokers: " + invokers + ", cause: " + t.getMessage(), t);
		}
		return invokers;
	}

	@Override
	public URL getUrl() {
		return url;
	}

	@Override
	public int compareTo(Router o) {
		if (o == null || o.getClass() != ConditionRouter.class) {
			return 1;
		}
		ConditionRouter c = (ConditionRouter) o;
		return this.priority == c.priority ? url.toFullString().compareTo(c.url.toFullString()) : (this.priority > c.priority ? 1 : -1);
	}

	boolean matchWhen(URL url, Invocation invocation) {
		// 判断条件为空 || 条件匹配认定为匹配
		return whenCondition == null || whenCondition.isEmpty() || matchCondition(whenCondition, url, null, invocation);
	}

	private boolean matchThen(URL url, URL param) {
		// 判断条件不为空 && 条件匹配认定为匹配
		return !(thenCondition == null || thenCondition.isEmpty()) && matchCondition(thenCondition, url, param, null);
	}

	private boolean matchCondition(Map<String, MatchPair> condition, URL url, URL param, Invocation invocation) {
		Map<String, String> sample = url.toMap();
		boolean result = false;
		for (Map.Entry<String, MatchPair> matchPair : condition.entrySet()) {
			String key = matchPair.getKey();
			String sampleValue;
			//get real invoked method name from invocation
			if (invocation != null && (Constants.METHOD_KEY.equals(key) || Constants.METHODS_KEY.equals(key))) {
				sampleValue = invocation.getMethodName();
			} else {
				sampleValue = sample.get(key);
				if (sampleValue == null) {
					sampleValue = sample.get(Constants.DEFAULT_KEY_PREFIX + key);
				}
			}
			if (sampleValue != null) {
				if (!matchPair.getValue().isMatch(sampleValue, param)) {
					return false;
				} else {
					result = true;
				}
			} else {
				//not pass the condition
				if (!matchPair.getValue().matches.isEmpty()) {
					return false;
				} else {
					result = true;
				}
			}
		}
		return result;
	}

	private static final class MatchPair {
		// 满足/匹配的条件
		final Set<String> matches = new HashSet<String>();
		// 不满足/不匹配的条件
		final Set<String> mismatches = new HashSet<String>();

		private boolean isMatch(String value, URL param) {
			if (!matches.isEmpty() && mismatches.isEmpty()) {
				for (String match : matches) {
					if (UrlUtils.isMatchGlobPattern(match, value, param)) {
						return true;
					}
				}
				return false;
			}

			if (!mismatches.isEmpty() && matches.isEmpty()) {
				for (String mismatch : mismatches) {
					if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
						return false;
					}
				}
				return true;
			}

			if (!matches.isEmpty() && !mismatches.isEmpty()) {
				//when both mismatches and matches contain the same value, then using mismatches first
				for (String mismatch : mismatches) {
					if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
						return false;
					}
				}
				for (String match : matches) {
					if (UrlUtils.isMatchGlobPattern(match, value, param)) {
						return true;
					}
				}
				return false;
			}
			return false;
		}
	}
}
