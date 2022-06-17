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

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import feign.Request;

import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.beans.factory.config.BeanExpressionResolver;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * 用于注册被 {@link FeignClient} 注解修饰的接口到容器中
 * <p>
 * 继承 ImportBeanDefinitionRegistrar 接口：向 Spring 容器中注入 Bean。
 * 继承 ResourceLoaderAware 接口：加载外部资源。
 * 继承 EnvironmentAware 接口：获取配置文件属性。
 *
 *
 * @author Spencer Gibb
 * @author Jakub Narloch
 * @author Venil Noronha
 * @author Gang Li
 * @author Michal Domagala
 * @author Marcin Grzejszczak
 * @author Olga Maciaszek-Sharma
 * @author Jasbir Singh
 */
class FeignClientsRegistrar implements ImportBeanDefinitionRegistrar, ResourceLoaderAware, EnvironmentAware {

	// patterned after Spring Integration IntegrationComponentScanRegistrar
	// and RibbonClientsConfigurationRegistgrar

	// 资源加载器
	private ResourceLoader resourceLoader;

	// 配置文件属性
	private Environment environment;

	FeignClientsRegistrar() {
	}

	static void validateFallback(final Class clazz) {
		Assert.isTrue(!clazz.isInterface(), "Fallback class must implement the interface annotated by @FeignClient");
	}

	static void validateFallbackFactory(final Class clazz) {
		Assert.isTrue(!clazz.isInterface(), "Fallback factory must produce instances "
				+ "of fallback classes that implement the interface annotated by @FeignClient");
	}

	static String getName(String name) {
		if (!StringUtils.hasText(name)) {
			return "";
		}

		String host = null;
		try {
			String url;
			if (!name.startsWith("http://") && !name.startsWith("https://")) {
				url = "http://" + name;
			}
			else {
				url = name;
			}
			host = new URI(url).getHost();

		}
		catch (URISyntaxException e) {
		}
		Assert.state(host != null, "Service id not legal hostname (" + name + ")");
		return name;
	}

	static String getUrl(String url) {
		if (StringUtils.hasText(url) && !(url.startsWith("#{") && url.contains("}"))) {
			if (!url.contains("://")) {
				url = "http://" + url;
			}
			try {
				new URL(url);
			}
			catch (MalformedURLException e) {
				throw new IllegalArgumentException(url + " is malformed", e);
			}
		}
		return url;
	}

	static String getPath(String path) {
		if (StringUtils.hasText(path)) {
			path = path.trim();
			if (!path.startsWith("/")) {
				path = "/" + path;
			}
			if (path.endsWith("/")) {
				path = path.substring(0, path.length() - 1);
			}
		}
		return path;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	/**
	 * 注册 FeignClient 的入口，
	 * 继承自 ImportBeanDefinitionRegistrar，启动时会自动执行其 registerBeanDefinitions() 方法。
	 */
	@Override
	public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
		// 1. 注入 @EnableFeignClients 注解中指定的属性
		// defaultConfiguration 作为所有 FeignClient 的兜底默认属性，可以被 @FeignClient 的 configuration 属性覆盖
		// 暂且不关注这里
		registerDefaultConfiguration(metadata, registry);
		// 2. 注入 FeignClient
		registerFeignClients(metadata, registry);
	}

	/**
	 * 注入{@link EnableFeignClients} 注解中指定的 defaultConfiguration 属性
	 */
	private void registerDefaultConfiguration(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
		Map<String, Object> defaultAttrs = metadata.getAnnotationAttributes(EnableFeignClients.class.getName(), true);

		if (defaultAttrs != null && defaultAttrs.containsKey("defaultConfiguration")) {
			String name;
			if (metadata.hasEnclosingClass()) {
				name = "default." + metadata.getEnclosingClassName();
			}
			else {
				name = "default." + metadata.getClassName();
			}
			// 注入 FeignClient 的默认属性 FeignClientSpecification 到容器
			registerClientConfiguration(registry, name, defaultAttrs.get("defaultConfiguration"));
		}
	}

	/**
	 * 扫描所有 FeignClient 并注入
	 */
	public void registerFeignClients(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {

		LinkedHashSet<BeanDefinition> candidateComponents = new LinkedHashSet<>();
		// 1. 获取 @EnableFeignClients 注解中指定的元数据
		Map<String, Object> attrs = metadata.getAnnotationAttributes(EnableFeignClients.class.getName());
		final Class<?>[] clients = attrs == null ? null : (Class<?>[]) attrs.get("clients");

		// 2. 没有指定 clients 属性，扫描注解中 basePackages 属性指定的包，找到包下所有被 @FeignClient 注解修饰的接口
		if (clients == null || clients.length == 0) {
			// 创建扫描器
			ClassPathScanningCandidateComponentProvider scanner = getScanner();
			scanner.setResourceLoader(this.resourceLoader);
			scanner.addIncludeFilter(new AnnotationTypeFilter(FeignClient.class));
			// 获取注解中指定的扫描路径
			Set<String> basePackages = getBasePackages(metadata);
			// 遍历路径进行扫描，将扫描到的被 @FeignClient 注解修饰的接口加入 candidateComponents
			for (String basePackage : basePackages) {
				candidateComponents.addAll(scanner.findCandidateComponents(basePackage));
			}
		}

		// 3. 指定了 clients 属性，不走包扫描，注入注解中指定的 FeignClient
		else {
			for (Class<?> clazz : clients) {
				candidateComponents.add(new AnnotatedGenericBeanDefinition(clazz));
			}
		}

		// 4. 为所有被 @FeignClient 注解修饰的接口生成对应代理类，并将接口对应代理类注入容器
		for (BeanDefinition candidateComponent : candidateComponents) {
			if (candidateComponent instanceof AnnotatedBeanDefinition) {
				// 校验：注解必须加在接口上
				AnnotatedBeanDefinition beanDefinition = (AnnotatedBeanDefinition) candidateComponent;
				AnnotationMetadata annotationMetadata = beanDefinition.getMetadata();
				Assert.isTrue(annotationMetadata.isInterface(), "@FeignClient can only be specified on an interface");

				// @FeignClient 注解中指定的属性集合
				Map<String, Object> attributes = annotationMetadata.getAnnotationAttributes(FeignClient.class.getCanonicalName());

				// 获取注解中指定的服务名称标识(name/value/serviceId/contextId等)
				String name = getClientName(attributes);
				// 将 @FeignClient 注解中指定的配置类注入到容器，方便后续使用
				registerClientConfiguration(registry, name, attributes.get("configuration"));

				// 注入 @FeignClient 注解修饰的接口
				registerFeignClient(registry, annotationMetadata, attributes);
			}
		}
	}

	/**
	 * 注入 @FeignClient 注解修饰的接口
	 */
	private void registerFeignClient(BeanDefinitionRegistry registry, AnnotationMetadata annotationMetadata, Map<String, Object> attributes) {

		// 1. 根据 @FeignClient 注解的元数据获取一系列属性，用于后续创建接口代理类
		// 接口全限定类名
		String className = annotationMetadata.getClassName();
		// 接口的 Class 对象
		Class clazz = ClassUtils.resolveClassName(className, null);
		// 根据 registry 获取 beanFactory
		ConfigurableBeanFactory beanFactory = registry instanceof ConfigurableBeanFactory ? (ConfigurableBeanFactory) registry : null;

		String contextId = getContextId(beanFactory, attributes);
		String name = getName(attributes);

		// 2. 创建 FeignClientFactoryBean 代理工厂，并填充上述属性，用于生成接口的代理类
		FeignClientFactoryBean factoryBean = new FeignClientFactoryBean();
		factoryBean.setBeanFactory(beanFactory);
		factoryBean.setName(name);
		factoryBean.setContextId(contextId);
		factoryBean.setType(clazz);
		factoryBean.setRefreshableClient(isClientRefreshEnabled());

		// 3. 创建 definitionBuilder，用于生成接口的 BeanDefinition。在注入比较复杂的对象时经常用这种方式来做
		// 使用 BeanDefinitionBuilder 来构造，可以指定实例化对象的方法，后面传入的 lambda 函数就是
		// 这里使用 factoryBean.getObject() 方法来生成接口的代理对象
		BeanDefinitionBuilder definitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(clazz, () -> {
			// 完善 factoryBean 的属性，如 url、path、fallback、fallbackFactory
			factoryBean.setUrl(getUrl(beanFactory, attributes));
			factoryBean.setPath(getPath(beanFactory, attributes));
			factoryBean.setDecode404(Boolean.parseBoolean(String.valueOf(attributes.get("decode404"))));
			Object fallback = attributes.get("fallback");
			if (fallback != null) {
				factoryBean.setFallback(fallback instanceof Class ? (Class<?>) fallback
						: ClassUtils.resolveClassName(fallback.toString(), null));
			}
			Object fallbackFactory = attributes.get("fallbackFactory");
			if (fallbackFactory != null) {
				factoryBean.setFallbackFactory(fallbackFactory instanceof Class ? (Class<?>) fallbackFactory
						: ClassUtils.resolveClassName(fallbackFactory.toString(), null));
			}
			// 最终生成接口代理对象的方法，实际是通过 factoryBean 生成接口的代理对象
			return factoryBean.getObject();
		});
		definitionBuilder.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);
		definitionBuilder.setLazyInit(true);
		validate(attributes);

		// 4. 根据 definitionBuilder 创建 BeanDefinition
		// 这里的 BeanDefinition 中持有了代理对象的生成方法即上面的 lambda 函数
		AbstractBeanDefinition beanDefinition = definitionBuilder.getBeanDefinition();
		beanDefinition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, className);
		beanDefinition.setAttribute("feignClientsRegistrarFactoryBean", factoryBean);

		// has a default, won't be null
		boolean primary = (Boolean) attributes.get("primary");
		beanDefinition.setPrimary(primary);

		String[] qualifiers = getQualifiers(attributes);
		if (ObjectUtils.isEmpty(qualifiers)) {
			qualifiers = new String[] { contextId + "FeignClient" };
		}

		// 5. 注册 beanDefinition 到容器，到这里接口的代理对象就注入到容器了，业务中就可以通过注解进行依赖注入了
		// holder 主要作用是将 beanDefinition 和它的别名都封装进去，在将 beanDefinition 注入容器的时候还会为beanName关联别名
		BeanDefinitionHolder holder = new BeanDefinitionHolder(beanDefinition, className, qualifiers);
		BeanDefinitionReaderUtils.registerBeanDefinition(holder, registry);

		// 如果配置了Feign可刷新，还有些额外操作，暂不关注
		registerOptionsBeanDefinition(registry, contextId);
	}

	private void validate(Map<String, Object> attributes) {
		AnnotationAttributes annotation = AnnotationAttributes.fromMap(attributes);
		// This blows up if an aliased property is overspecified
		// FIXME annotation.getAliasedString("name", FeignClient.class, null);
		validateFallback(annotation.getClass("fallback"));
		validateFallbackFactory(annotation.getClass("fallbackFactory"));
	}

	/* for testing */ String getName(Map<String, Object> attributes) {
		return getName(null, attributes);
	}

	String getName(ConfigurableBeanFactory beanFactory, Map<String, Object> attributes) {
		String name = (String) attributes.get("serviceId");
		if (!StringUtils.hasText(name)) {
			name = (String) attributes.get("name");
		}
		if (!StringUtils.hasText(name)) {
			name = (String) attributes.get("value");
		}
		name = resolve(beanFactory, name);
		return getName(name);
	}

	private String getContextId(ConfigurableBeanFactory beanFactory, Map<String, Object> attributes) {
		String contextId = (String) attributes.get("contextId");
		if (!StringUtils.hasText(contextId)) {
			return getName(attributes);
		}

		contextId = resolve(beanFactory, contextId);
		return getName(contextId);
	}

	private String resolve(ConfigurableBeanFactory beanFactory, String value) {
		if (StringUtils.hasText(value)) {
			if (beanFactory == null) {
				return this.environment.resolvePlaceholders(value);
			}
			BeanExpressionResolver resolver = beanFactory.getBeanExpressionResolver();
			String resolved = beanFactory.resolveEmbeddedValue(value);
			if (resolver == null) {
				return resolved;
			}
			Object evaluateValue = resolver.evaluate(resolved, new BeanExpressionContext(beanFactory, null));
			if (evaluateValue != null) {
				return String.valueOf(evaluateValue);
			}
			return null;
		}
		return value;
	}

	private String getUrl(ConfigurableBeanFactory beanFactory, Map<String, Object> attributes) {
		String url = resolve(beanFactory, (String) attributes.get("url"));
		return getUrl(url);
	}

	private String getPath(ConfigurableBeanFactory beanFactory, Map<String, Object> attributes) {
		String path = resolve(beanFactory, (String) attributes.get("path"));
		return getPath(path);
	}

	protected ClassPathScanningCandidateComponentProvider getScanner() {
		return new ClassPathScanningCandidateComponentProvider(false, this.environment) {
			@Override
			protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
				boolean isCandidate = false;
				if (beanDefinition.getMetadata().isIndependent()) {
					if (!beanDefinition.getMetadata().isAnnotation()) {
						isCandidate = true;
					}
				}
				return isCandidate;
			}
		};
	}

	protected Set<String> getBasePackages(AnnotationMetadata importingClassMetadata) {
		Map<String, Object> attributes = importingClassMetadata
				.getAnnotationAttributes(EnableFeignClients.class.getCanonicalName());

		Set<String> basePackages = new HashSet<>();
		for (String pkg : (String[]) attributes.get("value")) {
			if (StringUtils.hasText(pkg)) {
				basePackages.add(pkg);
			}
		}
		for (String pkg : (String[]) attributes.get("basePackages")) {
			if (StringUtils.hasText(pkg)) {
				basePackages.add(pkg);
			}
		}
		for (Class<?> clazz : (Class[]) attributes.get("basePackageClasses")) {
			basePackages.add(ClassUtils.getPackageName(clazz));
		}

		if (basePackages.isEmpty()) {
			basePackages.add(ClassUtils.getPackageName(importingClassMetadata.getClassName()));
		}
		return basePackages;
	}

	private String getQualifier(Map<String, Object> client) {
		if (client == null) {
			return null;
		}
		String qualifier = (String) client.get("qualifier");
		if (StringUtils.hasText(qualifier)) {
			return qualifier;
		}
		return null;
	}

	private String[] getQualifiers(Map<String, Object> client) {
		if (client == null) {
			return null;
		}
		List<String> qualifierList = new ArrayList<>(Arrays.asList((String[]) client.get("qualifiers")));
		qualifierList.removeIf(qualifier -> !StringUtils.hasText(qualifier));
		if (qualifierList.isEmpty() && getQualifier(client) != null) {
			qualifierList = Collections.singletonList(getQualifier(client));
		}
		return !qualifierList.isEmpty() ? qualifierList.toArray(new String[0]) : null;
	}

	private String getClientName(Map<String, Object> client) {
		if (client == null) {
			return null;
		}
		String value = (String) client.get("contextId");
		if (!StringUtils.hasText(value)) {
			value = (String) client.get("value");
		}
		if (!StringUtils.hasText(value)) {
			value = (String) client.get("name");
		}
		if (!StringUtils.hasText(value)) {
			value = (String) client.get("serviceId");
		}
		if (StringUtils.hasText(value)) {
			return value;
		}

		throw new IllegalStateException(
				"Either 'name' or 'value' must be provided in @" + FeignClient.class.getSimpleName());
	}

	private void registerClientConfiguration(BeanDefinitionRegistry registry, Object name, Object configuration) {
		BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(FeignClientSpecification.class);
		builder.addConstructorArgValue(name);
		builder.addConstructorArgValue(configuration);
		registry.registerBeanDefinition(name + "." + FeignClientSpecification.class.getSimpleName(),
				builder.getBeanDefinition());
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	/**
	 * This method is meant to create {@link Request.Options} beans definition with
	 * refreshScope.
	 * @param registry spring bean definition registry
	 * @param contextId name of feign client
	 */
	private void registerOptionsBeanDefinition(BeanDefinitionRegistry registry, String contextId) {
		if (isClientRefreshEnabled()) {
			String beanName = Request.Options.class.getCanonicalName() + "-" + contextId;
			BeanDefinitionBuilder definitionBuilder = BeanDefinitionBuilder
					.genericBeanDefinition(OptionsFactoryBean.class);
			definitionBuilder.setScope("refresh");
			definitionBuilder.addPropertyValue("contextId", contextId);
			BeanDefinitionHolder definitionHolder = new BeanDefinitionHolder(definitionBuilder.getBeanDefinition(),
					beanName);
			definitionHolder = ScopedProxyUtils.createScopedProxy(definitionHolder, registry, true);
			BeanDefinitionReaderUtils.registerBeanDefinition(definitionHolder, registry);
		}
	}

	private boolean isClientRefreshEnabled() {
		return environment.getProperty("feign.client.refresh-enabled", Boolean.class, false);
	}

}
