/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.context.annotation;

import java.lang.annotation.Annotation;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionCustomizer;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.core.env.Environment;
import org.springframework.core.env.EnvironmentCapable;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Convenient adapter for programmatic registration of bean classes.
 *
 * <p>This is an alternative to {@link ClassPathBeanDefinitionScanner}, applying
 * the same resolution of annotations but for explicitly registered classes only.
 *
 * @author Juergen Hoeller
 * @author Chris Beams
 * @author Sam Brannen
 * @author Phillip Webb
 * @since 3.0
 * @see AnnotationConfigApplicationContext#register
 *
 * AnnotatedBeanDefinitionReade读取器用来加载class类型的配置，在它初始化的时候，会预先注册一些BeanPostProcessor和BeanFactoryPostProcessor，
 * 这些处理器会在接下来的spring初始化流程中被调用
 */
public class AnnotatedBeanDefinitionReader {

	private final BeanDefinitionRegistry registry;

	private BeanNameGenerator beanNameGenerator = AnnotationBeanNameGenerator.INSTANCE;

	private ScopeMetadataResolver scopeMetadataResolver = new AnnotationScopeMetadataResolver();

	private ConditionEvaluator conditionEvaluator;


	/**
	 * Create a new {@code AnnotatedBeanDefinitionReader} for the given registry.
	 * <p>If the registry is {@link EnvironmentCapable}, e.g. is an {@code ApplicationContext},
	 * the {@link Environment} will be inherited, otherwise a new
	 * {@link StandardEnvironment} will be created and used.
	 * @param registry the {@code BeanFactory} to load bean definitions into,
	 * in the form of a {@code BeanDefinitionRegistry}
	 * @see #AnnotatedBeanDefinitionReader(BeanDefinitionRegistry, Environment)
	 * @see #setEnvironment(Environment)
	 */
	public AnnotatedBeanDefinitionReader(BeanDefinitionRegistry registry) {
		this(registry, getOrCreateEnvironment(registry));
	}

	/**
	 * Create a new {@code AnnotatedBeanDefinitionReader} for the given registry,
	 * using the given {@link Environment}.
	 * @param registry the {@code BeanFactory} to load bean definitions into,
	 * in the form of a {@code BeanDefinitionRegistry}
	 * @param environment the {@code Environment} to use when evaluating bean definition
	 * profiles.
	 * @since 3.1
	 */
	public AnnotatedBeanDefinitionReader(BeanDefinitionRegistry registry, Environment environment) {
		Assert.notNull(registry, "BeanDefinitionRegistry must not be null");
		Assert.notNull(environment, "Environment must not be null");
		this.registry = registry;
		//ConditionEvaluator完成条件注解的判断，在后面的Spring Boot中有大量的应用。如@Condition等等
		this.conditionEvaluator = new ConditionEvaluator(registry, environment, null);
		//把一些自动注解处理器加入到AnnotationConfigApplicationContext下的BeanFactory的BeanDefinitions中  具体见下面
		AnnotationConfigUtils.registerAnnotationConfigProcessors(this.registry);
	}


	/**
	 * Get the BeanDefinitionRegistry that this reader operates on.
	 */
	public final BeanDefinitionRegistry getRegistry() {
		return this.registry;
	}

	/**
	 * Set the {@code Environment} to use when evaluating whether
	 * {@link Conditional @Conditional}-annotated component classes should be registered.
	 * <p>The default is a {@link StandardEnvironment}.
	 * @see #registerBean(Class, String, Class...)
	 */
	public void setEnvironment(Environment environment) {
		this.conditionEvaluator = new ConditionEvaluator(this.registry, environment, null);
	}

	/**
	 * Set the {@code BeanNameGenerator} to use for detected bean classes.
	 * <p>The default is a {@link AnnotationBeanNameGenerator}.
	 */
	public void setBeanNameGenerator(@Nullable BeanNameGenerator beanNameGenerator) {
		this.beanNameGenerator =
				(beanNameGenerator != null ? beanNameGenerator : AnnotationBeanNameGenerator.INSTANCE);
	}

	/**
	 * Set the {@code ScopeMetadataResolver} to use for registered component classes.
	 * <p>The default is an {@link AnnotationScopeMetadataResolver}.
	 */
	public void setScopeMetadataResolver(@Nullable ScopeMetadataResolver scopeMetadataResolver) {
		this.scopeMetadataResolver =
				(scopeMetadataResolver != null ? scopeMetadataResolver : new AnnotationScopeMetadataResolver());
	}


	/**
	 * Register one or more component classes to be processed.
	 * <p>Calls to {@code register} are idempotent; adding the same
	 * component class more than once has no additional effect.
	 * @param componentClasses one or more component classes,
	 * e.g. {@link Configuration @Configuration} classes
	 *
	 * 加载Bean定义信息
	 */
	public void register(Class<?>... componentClasses) {
		for (Class<?> componentClass : componentClasses) {
			registerBean(componentClass);
		}
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations.
	 * @param beanClass the class of the bean
	 */
	public void registerBean(Class<?> beanClass) {
		doRegisterBean(beanClass, null, null, null, null);
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations.
	 * @param beanClass the class of the bean
	 * @param name an explicit name for the bean
	 * (or {@code null} for generating a default bean name)
	 * @since 5.2
	 */
	public void registerBean(Class<?> beanClass, @Nullable String name) {
		doRegisterBean(beanClass, name, null, null, null);
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations.
	 * @param beanClass the class of the bean
	 * @param qualifiers specific qualifier annotations to consider,
	 * in addition to qualifiers at the bean class level
	 */
	@SuppressWarnings("unchecked")
	public void registerBean(Class<?> beanClass, Class<? extends Annotation>... qualifiers) {
		doRegisterBean(beanClass, null, qualifiers, null, null);
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations.
	 * @param beanClass the class of the bean
	 * @param name an explicit name for the bean
	 * (or {@code null} for generating a default bean name)
	 * @param qualifiers specific qualifier annotations to consider,
	 * in addition to qualifiers at the bean class level
	 */
	@SuppressWarnings("unchecked")
	public void registerBean(Class<?> beanClass, @Nullable String name,
			Class<? extends Annotation>... qualifiers) {

		doRegisterBean(beanClass, name, qualifiers, null, null);
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations, using the given supplier for obtaining a new
	 * instance (possibly declared as a lambda expression or method reference).
	 * @param beanClass the class of the bean
	 * @param supplier a callback for creating an instance of the bean
	 * (may be {@code null})
	 * @since 5.0
	 */
	public <T> void registerBean(Class<T> beanClass, @Nullable Supplier<T> supplier) {
		doRegisterBean(beanClass, null, null, supplier, null);
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations, using the given supplier for obtaining a new
	 * instance (possibly declared as a lambda expression or method reference).
	 * @param beanClass the class of the bean
	 * @param name an explicit name for the bean
	 * (or {@code null} for generating a default bean name)
	 * @param supplier a callback for creating an instance of the bean
	 * (may be {@code null})
	 * @since 5.0
	 */
	public <T> void registerBean(Class<T> beanClass, @Nullable String name, @Nullable Supplier<T> supplier) {
		doRegisterBean(beanClass, name, null, supplier, null);
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations.
	 * @param beanClass the class of the bean
	 * @param name an explicit name for the bean
	 * (or {@code null} for generating a default bean name)
	 * @param supplier a callback for creating an instance of the bean
	 * (may be {@code null})
	 * @param customizers one or more callbacks for customizing the factory's
	 * {@link BeanDefinition}, e.g. setting a lazy-init or primary flag
	 * @since 5.2
	 */
	public <T> void registerBean(Class<T> beanClass, @Nullable String name, @Nullable Supplier<T> supplier,
			BeanDefinitionCustomizer... customizers) {

		doRegisterBean(beanClass, name, null, supplier, customizers);
	}

	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations.
	 * @param beanClass the class of the bean
	 * @param name an explicit name for the bean
	 * @param qualifiers specific qualifier annotations to consider, if any,
	 * in addition to qualifiers at the bean class level
	 * @param supplier a callback for creating an instance of the bean
	 * (may be {@code null})
	 * @param customizers one or more callbacks for customizing the factory's
	 * {@link BeanDefinition}, e.g. setting a lazy-init or primary flag
	 * @since 5.0
	 */
	private <T> void doRegisterBean(Class<T> beanClass, @Nullable String name,
			@Nullable Class<? extends Annotation>[] qualifiers, @Nullable Supplier<T> supplier,
			@Nullable BeanDefinitionCustomizer[] customizers) {

		// 先把此实体类型转换为一个BeanDefinition
		AnnotatedGenericBeanDefinition abd = new AnnotatedGenericBeanDefinition(beanClass);
		//abd.getMetadata() 元数据包括注解信息、是否内部类、类Class基本信息等等
		// 此处由conditionEvaluator#shouldSkip去过滤，此Class是否是配置类。
		// 大体逻辑为：必须有@Configuration修饰。然后解析一些Condition注解，看是否排除~
		if (this.conditionEvaluator.shouldSkip(abd.getMetadata())) {
			return;
		}

		abd.setInstanceSupplier(supplier);
		// 解析Scope
		ScopeMetadata scopeMetadata = this.scopeMetadataResolver.resolveScopeMetadata(abd);
		abd.setScope(scopeMetadata.getScopeName());
		// 得到Bean的名称 一般为首字母小写（此处为AnnotationBeanNameGenerator）
		String beanName = (name != null ? name : this.beanNameGenerator.generateBeanName(abd, this.registry));

		// 设定一些注解默认值，如lazy、Primary等等
		AnnotationConfigUtils.processCommonDefinitionAnnotations(abd);
		// 解析qualifiers，若有此注解  则primary都成为true了
		if (qualifiers != null) {
			for (Class<? extends Annotation> qualifier : qualifiers) {
				if (Primary.class == qualifier) {
					abd.setPrimary(true);
				}
				else if (Lazy.class == qualifier) {
					abd.setLazyInit(true);
				}
				else {
					abd.addQualifier(new AutowireCandidateQualifier(qualifier));
				}
			}
		}
		// 自定义定制信息(一般都不需要)
		if (customizers != null) {
			for (BeanDefinitionCustomizer customizer : customizers) {
				customizer.customize(abd);
			}
		}

		// 下面位解析Scope是否需要代理，最后把这个Bean注册进去
		BeanDefinitionHolder definitionHolder = new BeanDefinitionHolder(abd, beanName);
		definitionHolder = AnnotationConfigUtils.applyScopedProxyMode(scopeMetadata, definitionHolder, this.registry);
		BeanDefinitionReaderUtils.registerBeanDefinition(definitionHolder, this.registry);
	}


	/**
	 * Get the Environment from the given registry if possible, otherwise return a new
	 * StandardEnvironment.
	 */
	private static Environment getOrCreateEnvironment(BeanDefinitionRegistry registry) {
		Assert.notNull(registry, "BeanDefinitionRegistry must not be null");
		if (registry instanceof EnvironmentCapable) {
			return ((EnvironmentCapable) registry).getEnvironment();
		}
		return new StandardEnvironment();
	}

}
