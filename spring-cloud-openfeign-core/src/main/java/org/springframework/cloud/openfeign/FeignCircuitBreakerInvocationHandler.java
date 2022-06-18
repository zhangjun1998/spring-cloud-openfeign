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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import feign.InvocationHandlerFactory;
import feign.Target;

import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import static feign.Util.checkNotNull;

/**
 * 对接口代理对象的方法调用做代理，实现熔断降级等自定义逻辑
 */
class FeignCircuitBreakerInvocationHandler implements InvocationHandler {

	private final CircuitBreakerFactory factory;

	private final String feignClientName;

	private final Target<?> target;

	private final Map<Method, InvocationHandlerFactory.MethodHandler> dispatch;

	private final FallbackFactory<?> nullableFallbackFactory;

	private final Map<Method, Method> fallbackMethodMap;

	private final boolean circuitBreakerGroupEnabled;

	private final CircuitBreakerNameResolver circuitBreakerNameResolver;

	/**
	 * 这个构造函数会在 {@link InvocationHandlerFactory#create(Target, Map)} 方法调用时执行，
	 * 因为之前在 {@link FeignCircuitBreaker.Builder#build(FallbackFactory)} 中传入的 lambda 表达式代码逻辑就是执行这个构造函数。
	 * 因此该类在这里可以拿到之前填充的一系列资源，如熔断器工厂、需要调用的服务名称、降级工厂等，还可以拿到 openfeign 中的 target 和 dispatch。
	 */
	FeignCircuitBreakerInvocationHandler(CircuitBreakerFactory factory, String feignClientName, Target<?> target,
			Map<Method, InvocationHandlerFactory.MethodHandler> dispatch, FallbackFactory<?> nullableFallbackFactory,
			boolean circuitBreakerGroupEnabled, CircuitBreakerNameResolver circuitBreakerNameResolver) {
		this.factory = factory;
		this.feignClientName = feignClientName;
		this.target = checkNotNull(target, "target");
		this.dispatch = checkNotNull(dispatch, "dispatch");
		this.fallbackMethodMap = toFallbackMethod(dispatch);
		this.nullableFallbackFactory = nullableFallbackFactory;
		this.circuitBreakerGroupEnabled = circuitBreakerGroupEnabled;
		this.circuitBreakerNameResolver = circuitBreakerNameResolver;
	}

	/**
	 * 执行接口代理对象的任意方法都会被转发到这里进行处理
	 */
	@Override
	public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
		// early exit if the invoked method is from java.lang.Object
		// code is the same as ReflectiveFeign.FeignInvocationHandler
		// 先过滤一些特殊的方法，对这些方法不做处理，直接调用
		if ("equals".equals(method.getName())) {
			try {
				Object otherHandler = args.length > 0 && args[0] != null ? Proxy.getInvocationHandler(args[0]) : null;
				return equals(otherHandler);
			}
			catch (IllegalArgumentException e) {
				return false;
			}
		}
		else if ("hashCode".equals(method.getName())) {
			return hashCode();
		}
		else if ("toString".equals(method.getName())) {
			return toString();
		}

		// 将需要调用的方法封装为 Supplier，内部会执行代理方法
		Supplier<Object> supplier = asSupplier(method, args);
		// 使用 CircuitBreakerFactory 创建熔断器，可以使用不同的熔断器，只要符合 spring cloud 对 CircuitBreaker 的规范即可，
		// 比如 spring cloud 对 Hystrix 就封装了 HystrixCircuitBreakerFactory
		String circuitName = circuitBreakerNameResolver.resolveCircuitBreakerName(feignClientName, target, method);
		CircuitBreaker circuitBreaker = circuitBreakerGroupEnabled ? factory.create(circuitName, feignClientName)
				: factory.create(circuitName);
		// 有降级逻辑
		if (this.nullableFallbackFactory != null) {
			// 封装降级逻辑为 Function
			Function<Throwable, Object> fallbackFunction = throwable -> {
				Object fallback = this.nullableFallbackFactory.create(throwable);
				try {
					return this.fallbackMethodMap.get(method).invoke(fallback, args);
				}
				catch (Exception exception) {
					unwrapAndRethrow(exception);
				}
				return null;
			};
			// 使用熔断器执行代理方法调用，调用失败就走降级逻辑
			// 熔断器内具体的逻辑需要根据熔断器的类型具体分析，这里就不深入了，感兴趣可以看 Hystrix 的实现，逻辑在 HystrixCircuitBreaker 类中
			return circuitBreaker.run(supplier, fallbackFunction);
		}
		// 没有降级逻辑
		// 使用熔断器执行代理方法调用，调用失败直接抛异常
		return circuitBreaker.run(supplier);
	}

	private void unwrapAndRethrow(Exception exception) {
		if (exception instanceof InvocationTargetException || exception instanceof NoFallbackAvailableException) {
			Throwable underlyingException = exception.getCause();
			if (underlyingException instanceof RuntimeException) {
				throw (RuntimeException) underlyingException;
			}
			if (underlyingException != null) {
				throw new IllegalStateException(underlyingException);
			}
			throw new IllegalStateException(exception);
		}
	}

	/**
	 * 将代理方法的调用逻辑封装为 Supplier，方便交给熔断器去调用
	 */
	private Supplier<Object> asSupplier(final Method method, final Object[] args) {
		final RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
		return () -> {
			try {
				RequestContextHolder.setRequestAttributes(requestAttributes);
				// 从 dispatch 中获取目标方法的代理方法，执行，返回
				return dispatch.get(method).invoke(args);
			}
			catch (RuntimeException throwable) {
				throw throwable;
			}
			catch (Throwable throwable) {
				throw new RuntimeException(throwable);
			}
		};
	}

	/**
	 * If the method param of InvocationHandler.invoke is not accessible, i.e in a
	 * package-private interface, the fallback call will cause of access restrictions. But
	 * methods in dispatch are copied methods. So setting access to dispatch method
	 * doesn't take effect to the method in InvocationHandler.invoke. Use map to store a
	 * copy of method to invoke the fallback to bypass this and reducing the count of
	 * reflection calls.
	 * @return cached methods map for fallback invoking
	 */
	static Map<Method, Method> toFallbackMethod(Map<Method, InvocationHandlerFactory.MethodHandler> dispatch) {
		Map<Method, Method> result = new LinkedHashMap<Method, Method>();
		for (Method method : dispatch.keySet()) {
			method.setAccessible(true);
			result.put(method, method);
		}
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof FeignCircuitBreakerInvocationHandler) {
			FeignCircuitBreakerInvocationHandler other = (FeignCircuitBreakerInvocationHandler) obj;
			return this.target.equals(other.target);
		}
		return false;
	}

	@Override
	public int hashCode() {
		return this.target.hashCode();
	}

	@Override
	public String toString() {
		return this.target.toString();
	}

}
