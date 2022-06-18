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

import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.util.StringUtils;

/**
 * 支持熔断的 Targeter 实现，会进行一些包装来支持熔断、降级等功能
 */
@SuppressWarnings("unchecked")
class FeignCircuitBreakerTargeter implements Targeter {

	private final CircuitBreakerFactory circuitBreakerFactory;

	private final boolean circuitBreakerGroupEnabled;

	private final CircuitBreakerNameResolver circuitBreakerNameResolver;

	FeignCircuitBreakerTargeter(CircuitBreakerFactory circuitBreakerFactory, boolean circuitBreakerGroupEnabled,
			CircuitBreakerNameResolver circuitBreakerNameResolver) {
		this.circuitBreakerFactory = circuitBreakerFactory;
		this.circuitBreakerGroupEnabled = circuitBreakerGroupEnabled;
		this.circuitBreakerNameResolver = circuitBreakerNameResolver;
	}

	@Override
	public <T> T target(FeignClientFactoryBean factory, Feign.Builder feign, FeignContext context,
			Target.HardCodedTarget<T> target) {
		// 校验并转换 feignBuilder 的类型
		if (!(feign instanceof FeignCircuitBreaker.Builder)) {
			return feign.target(target);
		}
		FeignCircuitBreaker.Builder builder = (FeignCircuitBreaker.Builder) feign;

		String name = !StringUtils.hasText(factory.getContextId()) ? factory.getName() : factory.getContextId();
		// 1. 指定了降级类
		Class<?> fallback = factory.getFallback();
		if (fallback != void.class) {
			return targetWithFallback(name, context, target, builder, fallback);
		}
		// 2. 指定了降级的工厂类
		Class<?> fallbackFactory = factory.getFallbackFactory();
		if (fallbackFactory != void.class) {
			return targetWithFallbackFactory(name, context, target, builder, fallbackFactory);
		}
		// 3. 没有指定降级逻辑
		return builder(name, builder).target(target);
	}

	/**
	 * 指定了降级类时接口代理对象的创建逻辑
	 */
	private <T> T targetWithFallback(String feignClientName, FeignContext context, Target.HardCodedTarget<T> target,
			FeignCircuitBreaker.Builder builder, Class<?> fallback) {
		// 从 FeignContext 中获取该 feignClient 对应的 fallback 实例
		T fallbackInstance = getFromContext("fallback", feignClientName, context, fallback, target.type());
		// 调用 builder() 给原 feignBuilder 填充一些属性，然后再调用 builder.target() 方法创建对象
		return builder(feignClientName, builder).target(target, fallbackInstance);
	}

	/**
	 * 指定了降级工厂时接口代理对象的创建逻辑
	 * 这里和上面的 {@link #targetWithFallback} 方法类似，不分析了
	 */
	private <T> T targetWithFallbackFactory(String feignClientName, FeignContext context,
											Target.HardCodedTarget<T> target, FeignCircuitBreaker.Builder builder, Class<?> fallbackFactoryClass) {
		FallbackFactory<? extends T> fallbackFactory = (FallbackFactory<? extends T>) getFromContext("fallbackFactory",
			feignClientName, context, fallbackFactoryClass, FallbackFactory.class);
		return builder(feignClientName, builder).target(target, fallbackFactory);
	}

	private <T> T getFromContext(String fallbackMechanism, String feignClientName, FeignContext context,
			Class<?> beanType, Class<T> targetType) {
		Object fallbackInstance = context.getInstance(feignClientName, beanType);
		if (fallbackInstance == null) {
			throw new IllegalStateException(
					String.format("No " + fallbackMechanism + " instance of type %s found for feign client %s",
							beanType, feignClientName));
		}

		if (!targetType.isAssignableFrom(beanType)) {
			throw new IllegalStateException(String.format("Incompatible " + fallbackMechanism
					+ " instance. Fallback/fallbackFactory of type %s is not assignable to %s for feign client %s",
					beanType, targetType, feignClientName));
		}
		return (T) fallbackInstance;
	}

	/**
	 * 给原 feignBuilder 填充一些熔断降级相关的属性，方便后续创建接口代理对象
	 */
	private FeignCircuitBreaker.Builder builder(String feignClientName, FeignCircuitBreaker.Builder builder) {
		return builder
			// 指定降级工厂
			.circuitBreakerFactory(circuitBreakerFactory)
			// 指定 feignClient 的名称，也就是 @FeignClient 中指定的服务名称
			.feignClientName(feignClientName)
			.circuitBreakerGroupEnabled(circuitBreakerGroupEnabled)
			.circuitBreakerNameResolver(circuitBreakerNameResolver);
	}

}
