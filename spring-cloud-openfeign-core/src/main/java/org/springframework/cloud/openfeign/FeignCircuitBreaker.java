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

import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;

/**
 * 对 feignClient 提供熔断降级功能
 * <p>
 *
 * Allows Feign interfaces to work with {@link CircuitBreaker}.
 *
 * @author Marcin Grzejszczak
 * @author Andrii Bohutskyi
 * @author Kwangyong Kim
 * @since 3.0.0
 */
public final class FeignCircuitBreaker {

	private FeignCircuitBreaker() {
		throw new IllegalStateException("Don't instantiate a utility class");
	}

	/**
	 * @return builder for Feign CircuitBreaker integration
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * 继承自 Feign.Builder，是 spring cloud 对 openfeign 的扩展，用于实现熔断降级
	 *
	 * <p>
	 * Builder for Feign CircuitBreaker integration.
	 */
	public static final class Builder extends Feign.Builder {

		private CircuitBreakerFactory circuitBreakerFactory;

		private String feignClientName;

		private boolean circuitBreakerGroupEnabled;

		private CircuitBreakerNameResolver circuitBreakerNameResolver;

		Builder circuitBreakerFactory(CircuitBreakerFactory circuitBreakerFactory) {
			this.circuitBreakerFactory = circuitBreakerFactory;
			return this;
		}

		Builder feignClientName(String feignClientName) {
			this.feignClientName = feignClientName;
			return this;
		}

		Builder circuitBreakerGroupEnabled(boolean circuitBreakerGroupEnabled) {
			this.circuitBreakerGroupEnabled = circuitBreakerGroupEnabled;
			return this;
		}

		Builder circuitBreakerNameResolver(CircuitBreakerNameResolver circuitBreakerNameResolver) {
			this.circuitBreakerNameResolver = circuitBreakerNameResolver;
			return this;
		}

		/**
		 * 以下三个方法分别对应 {@link FeignCircuitBreakerTargeter#target} 中的三种情况
		 */

		/**
		 * 生成接口代理对象(feignClient)，且指定了降级接口
		 * 就分析这个方法吧，下面两个差不多的不看了
		 */
		public <T> T target(Target<T> target, T fallback) {
			// 先通过 builder() 方法生成一个 Feign 对象(实际是 ReflectiveFeign 子类)，然后再调用 feign 对象的 newInstance() 方法创建出接口的代理对象(feignClient)
			return build(fallback != null ? new FallbackFactory.Default<T>(fallback) : null).newInstance(target);
		}

		/**
		 * 生成接口代理对象(feignClient)，指定了降级工厂
		 */
		public <T> T target(Target<T> target, FallbackFactory<? extends T> fallbackFactory) {
			return build(fallbackFactory).newInstance(target);
		}

		/**
		 * 生成接口代理对象(feignClient)，但没指定降级接口
		 */
		@Override
		public <T> T target(Target<T> target) {
			return build(null).newInstance(target);
		}

		/**
		 * 上面三个方法都会通过这个方法创建出一个 Feign 对象，只不过参数不一样，所以创建出的对象功能也不一样。
		 * 里面的源码需要追踪到 openfeign 中看了，spring-cloud-openfeign 只是对它的扩展封装，然后最终还是构建出一个 Feign 对象。
		 * 可见 Feign 的可扩展性真的很强。
		 * <p>
		 *
		 * 可以继续去我的 github 看后续 openfeign 的源码，我也想看下它是怎么做的，地址如下：
		 * <a href="https://github.com/zhangjun1998/feign">香蕉大魔王的 openfeign 源码分析</a>
		 */
		public Feign build(final FallbackFactory<?> nullableFallbackFactory) {
			// 调用 Feign.Builder 的方法给 feignBuilder 设置 InvocationHandlerFactory
			// invocationHandlerFactory() 方法传入一个 InvocationHandlerFactory，它是 openfeign 的一个函数式接口，只有一个 create() 方法用于创建 InvocationHandler。
			// 这里传入的 lambda 函数就是它的 create() 方法实现，返回了一个 FeignCircuitBreakerInvocationHandler，其继承自 java.lang.reflect.InvocationHandler 接口，
			// 它会接管代理对象的所有方法调用，进而实现对方法的增强，做熔断降级等操作。
			super.invocationHandlerFactory((target, dispatch) -> new FeignCircuitBreakerInvocationHandler(
					circuitBreakerFactory, feignClientName, target, dispatch, nullableFallbackFactory,
					circuitBreakerGroupEnabled, circuitBreakerNameResolver)
			);
			// 调用 builder.build() 方法创建 Feign 对象(ReflectiveFeign)，这里要看 openfeign 的源码，去我的 GitHub 看吧
			return super.build();
		}

	}

}
