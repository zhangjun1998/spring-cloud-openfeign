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

package org.springframework.cloud.openfeign.loadbalancer;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import feign.Client;
import feign.Request;
import feign.Response;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.CompletionContext;
import org.springframework.cloud.client.loadbalancer.DefaultRequest;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerLifecycle;
import org.springframework.cloud.client.loadbalancer.LoadBalancerLifecycleValidator;
import org.springframework.cloud.client.loadbalancer.LoadBalancerProperties;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.client.loadbalancer.ResponseData;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.Assert;

import static org.springframework.cloud.openfeign.loadbalancer.LoadBalancerUtils.buildRequestData;
import static org.springframework.cloud.openfeign.loadbalancer.LoadBalancerUtils.executeWithLoadBalancerLifecycleProcessing;

/**
 * spring-cloud-openfeign 对 Client 的代理，用来支持负载均衡。
 * 在 {@link DefaultFeignLoadBalancerConfiguration} 配置中注入。
 * <p>
 *
 * A {@link Client} implementation that uses {@link LoadBalancerClient} to select a
 * {@link ServiceInstance} to use while resolving the request host.
 *
 * @author Olga Maciaszek-Sharma
 * @since 2.2.0
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public class FeignBlockingLoadBalancerClient implements Client {

	private static final Log LOG = LogFactory.getLog(FeignBlockingLoadBalancerClient.class);

	private final Client delegate;

	private final LoadBalancerClient loadBalancerClient;

	private final LoadBalancerClientFactory loadBalancerClientFactory;

	/**
	 * delegate：HTTP 客户端
	 * loadBalancerClient：负载均衡器，，由负载均衡组件本身负责注入
	 *
	 * @deprecated in favour of
	 * {@link FeignBlockingLoadBalancerClient#FeignBlockingLoadBalancerClient(Client, LoadBalancerClient, LoadBalancerClientFactory)}
	 */
	@Deprecated
	public FeignBlockingLoadBalancerClient(Client delegate, LoadBalancerClient loadBalancerClient,
			LoadBalancerProperties properties, LoadBalancerClientFactory loadBalancerClientFactory) {
		this.delegate = delegate;
		this.loadBalancerClient = loadBalancerClient;
		this.loadBalancerClientFactory = loadBalancerClientFactory;
	}

	public FeignBlockingLoadBalancerClient(Client delegate, LoadBalancerClient loadBalancerClient,
			LoadBalancerClientFactory loadBalancerClientFactory) {
		this.delegate = delegate;
		this.loadBalancerClient = loadBalancerClient;
		this.loadBalancerClientFactory = loadBalancerClientFactory;
	}


	/**
	 * 执行 HTTP 请求
	 *
	 * @param request  Request 对象，是 openfeign 规定的统一规范，用来对 HTTP 请求相关参数进行封装，以适配各种 HTTP 客户端
	 * @param options HTTP 请求相关选项，如连接超时、读写超时等
	 * @return openfeign 规定的统一 HTTP 响应
	 */
	@Override
	public Response execute(Request request, Request.Options options) throws IOException {
		// 提取出 Request 中的 url
		final URI originalUri = URI.create(request.url());
		// 提取出 url 中的 serviceName/Id
		String serviceId = originalUri.getHost();
		Assert.state(serviceId != null, "Request URI does not contain a valid hostname: " + originalUri);

		String hint = getHint(serviceId);
		DefaultRequest<RequestDataContext> lbRequest = new DefaultRequest<>(new RequestDataContext(buildRequestData(request), hint));
		Set<LoadBalancerLifecycle> supportedLifecycleProcessors = LoadBalancerLifecycleValidator
				.getSupportedLifecycleProcessors(
						loadBalancerClientFactory.getInstances(serviceId, LoadBalancerLifecycle.class),
						RequestDataContext.class, ResponseData.class, ServiceInstance.class
				);
		supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onStart(lbRequest));

		// 选择本次请求的服务实例，根据指定的负载均衡策略决定选择哪个实例
		ServiceInstance instance = loadBalancerClient.choose(serviceId, lbRequest);
		org.springframework.cloud.client.loadbalancer.Response<ServiceInstance> lbResponse = new DefaultResponse(instance);
		if (instance == null) {
			String message = "Load balancer does not contain an instance for the service " + serviceId;
			if (LOG.isWarnEnabled()) {
				LOG.warn(message);
			}
			supportedLifecycleProcessors.forEach(lifecycle -> lifecycle
					.onComplete(new CompletionContext<ResponseData, ServiceInstance, RequestDataContext>(
							CompletionContext.Status.DISCARD, lbRequest, lbResponse)));
			return Response.builder().request(request).status(HttpStatus.SERVICE_UNAVAILABLE.value())
					.body(message, StandardCharsets.UTF_8).build();
		}
		// 根据需要请求的实例 + 原始请求地址，生成最终的 url
		String reconstructedUrl = loadBalancerClient.reconstructURI(instance, originalUri).toString();
		// 替换请求url，生成最终的 Request
		Request newRequest = buildRequest(request, reconstructedUrl);
		// 执行请求，返回响应
		LoadBalancerProperties loadBalancerProperties = loadBalancerClientFactory.getProperties(serviceId);
		return executeWithLoadBalancerLifecycleProcessing(delegate, options, newRequest, lbRequest, lbResponse,
				supportedLifecycleProcessors, loadBalancerProperties.isUseRawStatusCodeInResponseData());
	}

	protected Request buildRequest(Request request, String reconstructedUrl) {
		return Request.create(request.httpMethod(), reconstructedUrl, request.headers(), request.body(),
				request.charset(), request.requestTemplate());
	}

	// Visible for Sleuth instrumentation
	public Client getDelegate() {
		return delegate;
	}

	private String getHint(String serviceId) {
		LoadBalancerProperties properties = loadBalancerClientFactory.getProperties(serviceId);
		String defaultHint = properties.getHint().getOrDefault("default", "default");
		String hintPropertyValue = properties.getHint().get(serviceId);
		return hintPropertyValue != null ? hintPropertyValue : defaultHint;
	}

}
