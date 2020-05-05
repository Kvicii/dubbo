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
package com.alibaba.dubbo.rpc.cluster.loadbalance;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ConsistentHashLoadBalance
 */
public class ConsistentHashLoadBalance extends AbstractLoadBalance {

    private final ConcurrentMap<String, ConsistentHashSelector<?>> selectors = new ConcurrentHashMap<String, ConsistentHashSelector<?>>();

    @SuppressWarnings("unchecked")
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        String methodName = RpcUtils.getMethodName(invocation); // 获取调用的方法名
        String key = invokers.get(0).getUrl().getServiceKey() + "." + methodName;
        int identityHashCode = System.identityHashCode(invokers);   // 生成Invoker的hashcode
        ConsistentHashSelector<T> selector = (ConsistentHashSelector<T>) selectors.get(key);    // 选择一致性哈希选择器
        if (selector == null || selector.identityHashCode != identityHashCode) {    // 不存在创建新的一致性哈希选择器
            selectors.put(key, new ConsistentHashSelector<T>(invokers, methodName, identityHashCode));
            selector = (ConsistentHashSelector<T>) selectors.get(key);
        }
        return selector.select(invocation); // 开始进行负载均衡选举
    }

    private static final class ConsistentHashSelector<T> {

        // 存储一致性哈希 Hash-Invoker 映射
        private final TreeMap<Long, Invoker<T>> virtualInvokers;

        // 副本数
        private final int replicaNumber;

        // hashcode
        private final int identityHashCode;

        // 参数索引数组
        private final int[] argumentIndex;

        ConsistentHashSelector(List<Invoker<T>> invokers, String methodName, int identityHashCode) {
            this.virtualInvokers = new TreeMap<Long, Invoker<T>>(); // 创建TreeMap用于保存虚拟节点
            this.identityHashCode = identityHashCode;   // 调用节点的hashcode
            URL url = invokers.get(0).getUrl(); // 获取URL
            this.replicaNumber = url.getMethodParameter(methodName, "hash.nodes", 160); // 一致性哈希每个Invoker需要创建的节点数 没有配置按照默认的160
            String[] index = Constants.COMMA_SPLIT_PATTERN.split(url.getMethodParameter(methodName, "hash.arguments", "0"));    // 获取需要进行hash的参数索引数组 没有配置按照第一个参数进行hash
            argumentIndex = new int[index.length];
            for (int i = 0; i < index.length; i++) {
                argumentIndex[i] = Integer.parseInt(index[i]);
            }
            for (Invoker<T> invoker : invokers) {   // 对每个Invoker生成创建虚拟节点放到TreeMap
                String address = invoker.getUrl().getAddress();
                for (int i = 0; i < replicaNumber / 4; i++) {
                    byte[] digest = md5(address + i);   // 根据md5算法为每4个结点生成一个消息摘要(摘要长为16字节128位)
                    // 随后将128位分为4部分 0-31/32-63/64-95/95-128并生成4个32位数 存于long中 long的高32位都为0 并作为虚拟结点的key
                    for (int h = 0; h < 4; h++) {
                        long m = hash(digest, h);
                        virtualInvokers.put(m, invoker);
                    }
                }
            }
        }

        public Invoker<T> select(Invocation invocation) {
            String key = toKey(invocation.getArguments());  // 根据调用参数来生成Key
            byte[] digest = md5(key);   // 根据这个参数生成消息摘要
            // 调用hash(digest, 0)将消息摘要转换为hashCode 这里仅取0-31位来生成HashCode
            return selectForKey(hash(digest, 0));   // 调用sekectForKey方法选择结点
        }

        private String toKey(Object[] args) {
            StringBuilder buf = new StringBuilder();
            for (int i : argumentIndex) {   // 按照hash.arguments的配置 取方法的参数作为key
                if (i >= 0 && i < args.length) {
                    buf.append(args[i]);
                }
            }
            return buf.toString();
        }

        private Invoker<T> selectForKey(long hash) {
            Map.Entry<Long, Invoker<T>> entry = virtualInvokers.tailMap(hash, true).firstEntry();   // 找到一个最小上届的key所对应的结点
            if (entry == null) {    //  若不存在(hash圆环中最后一个节点之后的位置) 那么选择treeMap中第一个结点
                entry = virtualInvokers.firstEntry();
            }
            return entry.getValue();    // 若存在则返回
        }

        private long hash(byte[] digest, int number) {
            return (((long) (digest[3 + number * 4] & 0xFF) << 24)
                    | ((long) (digest[2 + number * 4] & 0xFF) << 16)
                    | ((long) (digest[1 + number * 4] & 0xFF) << 8)
                    | (digest[number * 4] & 0xFF))
                    & 0xFFFFFFFFL;
        }

        private byte[] md5(String value) {
            MessageDigest md5;
            try {
                md5 = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            md5.reset();
            byte[] bytes;
            try {
                bytes = value.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            md5.update(bytes);
            return md5.digest();
        }

    }

}
