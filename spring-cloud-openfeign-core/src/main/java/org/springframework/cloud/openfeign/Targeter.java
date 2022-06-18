/*
 * Copyright 2013-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.openfeign;

import feign.Feign;
import feign.Target;

/**
 * 主要就是来控制创建何种 feignClient，是默认还是需要支持熔断，面向接口编程，不同子类有自己的具体实现。
 *
 * @author Spencer Gibb
 */
public interface Targeter {

	/**
	 * 用来生成 接口的代理对象(feignClient)
	 *
	 * @param factory  factoryBean，创建 feignClient 的工厂
	 * @param feign  feignBuilder，创建 feignClient 所需要的一些类/属性/资源都被封装在里面
	 * @param context  feignContext，feign 的上下文，每个 feignClient 有自己独立的 feignContext，单独隔离开
	 * @param target target，封装了 url/path/name 等 HTTP 请求需要的参数，还能够将 RequestTemplate 转换为 Request
	 * @return 接口的代理对象(feignClient)
	 */
	<T> T target(FeignClientFactoryBean factory, Feign.Builder feign, FeignContext context,
			Target.HardCodedTarget<T> target);

}
