/*
 * Copyright 2002-2021 the original author or authors.
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

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.event.EventListenerFactory;
import org.springframework.core.Conventions;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Utilities for identifying {@link Configuration} classes.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.1
 */
abstract class ConfigurationClassUtils {

	/**
	 * Full模式和Lite模式的唯一区别：Full模式的配置组件会被enhance（加强/代理），而Liter模式不会。其余使用方式都一样，比如@Bean、@Import等等
	 *
	 * 如果类上有@Configuration注解说明是一个完全（Full）的配置类
	 * 如果如果类上面有@Component，@ComponentScan，@Import，@ImportResource这些注解，那么就是一个简化配置类。如果不是上面两种情况，那么有@Bean注解修饰的方法也是简化配置类
	 */
	public static final String CONFIGURATION_CLASS_FULL = "full";

	public static final String CONFIGURATION_CLASS_LITE = "lite";

	public static final String CONFIGURATION_CLASS_ATTRIBUTE =
			Conventions.getQualifiedAttributeName(ConfigurationClassPostProcessor.class, "configurationClass");

	private static final String ORDER_ATTRIBUTE =
			Conventions.getQualifiedAttributeName(ConfigurationClassPostProcessor.class, "order");


	private static final Log logger = LogFactory.getLog(ConfigurationClassUtils.class);

	private static final Set<String> candidateIndicators = new HashSet<>(8);

	static {
		candidateIndicators.add(Component.class.getName());
		candidateIndicators.add(ComponentScan.class.getName());
		candidateIndicators.add(Import.class.getName());
		candidateIndicators.add(ImportResource.class.getName());
	}


	/**
	 * Check whether the given bean definition is a candidate for a configuration class
	 * (or a nested component class declared within a configuration/component class,
	 * to be auto-registered as well), and mark it accordingly.
	 * @param beanDef the bean definition to check
	 * @param metadataReaderFactory the current factory in use by the caller
	 * @return whether the candidate qualifies as (any kind of) configuration class
	 *
	 * 检查当前给定的beanDefinition中是否是一个配置类的候选者，
	 * 判断在嵌套的关系中是否包含配置类或者组件类或者自动注册进去的，并做相应的标记
	 */
	public static boolean checkConfigurationClassCandidate(
			BeanDefinition beanDef, MetadataReaderFactory metadataReaderFactory) {

		// 获取bean定义信息中的class类名
		String className = beanDef.getBeanClassName();
		// 如果className为空，或者bean定义信息中的factoryMethod不等于空，那么直接返回
		if (className == null || beanDef.getFactoryMethodName() != null) {
			return false;
		}

		AnnotationMetadata metadata;
		// 通过注解注入的db都是AnnotatedGenericBeanDefinition，实现了AnnotatedBeanDefinition
		// 此处主要用于判断是否归属于AnnotatedBeanDefinition
		if (beanDef instanceof AnnotatedBeanDefinition &&
				className.equals(((AnnotatedBeanDefinition) beanDef).getMetadata().getClassName())) {
			// Can reuse the pre-parsed metadata from the given BeanDefinition...
			// 从当前bean的定义信息中获取元数据信息
			metadata = ((AnnotatedBeanDefinition) beanDef).getMetadata();
		}
		// 判断是否是spring中默认的BeanDefinition
		// spring内部的bd是RootBeanDefinition，实现了AbstractBeanDefinition
		else if (beanDef instanceof AbstractBeanDefinition && ((AbstractBeanDefinition) beanDef).hasBeanClass()) {
			// Check already loaded Class if present...
			// since we possibly can't even load the class file for this Class.
			// 获取当前bean对象的Class对象
			Class<?> beanClass = ((AbstractBeanDefinition) beanDef).getBeanClass();
			// 如果class实例是下面四种类或接口的子类、父接口等任何一种情况，直接返回
			if (BeanFactoryPostProcessor.class.isAssignableFrom(beanClass) ||
					BeanPostProcessor.class.isAssignableFrom(beanClass) ||
					AopInfrastructureBean.class.isAssignableFrom(beanClass) ||
					EventListenerFactory.class.isAssignableFrom(beanClass)) {
				return false;
			}
			// 为给定类创建新的AnnotationMetadata实例
			metadata = AnnotationMetadata.introspect(beanClass);
		}
		// 如果上述两种情况都不符合
		else {
			try {
				// 获取className的MetadataReader实例
				MetadataReader metadataReader = metadataReaderFactory.getMetadataReader(className);
				// 读取底层类的完整注释元数据，包括带注解方法的元数据
				metadata = metadataReader.getAnnotationMetadata();
			}
			catch (IOException ex) {
				if (logger.isDebugEnabled()) {
					logger.debug("Could not find class file for introspecting configuration annotations: " +
							className, ex);
				}
				return false;
			}
		}

		// 获取bean定义的元数据被@Configuration注解标注的属性字典值
		Map<String, Object> config = metadata.getAnnotationAttributes(Configuration.class.getName());
		// 如果bean被@Configuration注解标注，且属性proxyBeanMethods为false(使用代理模式)，则将bean定义记为full
		if (config != null && !Boolean.FALSE.equals(config.get("proxyBeanMethods"))) {
			// 只要这个类标注了：@Configuration注解就行  哪怕是接口、抽象类都木有问题 都是FULL模式
			beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_FULL);
		}
		// 如果bean被@configuration注解标注，且被注解@Component，@ComponentScan、@Import、@ImportResource或者@Bean标记的方法，则将bean定义标记为lite
		else if (config != null || isConfigurationCandidate(metadata)) {
			// 判断是Lite模式
			beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_LITE);
		}
		else {
			return false;
		}

		// It's a full or lite configuration candidate... Let's determine the order value, if any.
		// bean定义是一个标记为full或lite的候选项，如果设置order则设置order属性值
		Integer order = getOrder(metadata);
		// 如果值不为空的话，那么直接设置值到具体的beanDefinition
		if (order != null) {
			// 设置bean定义的order值
			beanDef.setAttribute(ORDER_ATTRIBUTE, order);
		}

		return true;
	}

	/**
	 * Check the given metadata for a configuration class candidate
	 * (or nested component class declared within a configuration/component class).
	 * @param metadata the metadata of the annotated class
	 * @return {@code true} if the given class is to be registered for
	 * configuration class processing; {@code false} otherwise
	 *
	 * 	// 判断是Lite模式：（首先肯定没有@Configuration注解）
	 * 	// 1、不能是接口
	 * 	// 2、但凡只有标注了一个下面注解，都算lite模式：@Component @ComponentScan @Import @ImportResource
	 * 	// 3、只有存在有一个方法标注了@Bean注解，那就是lite模式
	 */
	public static boolean isConfigurationCandidate(AnnotationMetadata metadata) {
		// Do not consider an interface or an annotation...
		// 不能是接口
		if (metadata.isInterface()) {
			return false;
		}

		// Any of the typical annotations found?
		// 但凡只有标注了一个下面注解，都算lite模式：@Component @ComponentScan @Import @ImportResource
		for (String indicator : candidateIndicators) {
			if (metadata.isAnnotated(indicator)) {
				return true;
			}
		}

		// Finally, let's look for @Bean methods...
		// 只有存在有一个方法标注了@Bean注解，那就是lite模式
		return hasBeanMethods(metadata);
	}

	static boolean hasBeanMethods(AnnotationMetadata metadata) {
		try {
			return metadata.hasAnnotatedMethods(Bean.class.getName());
		}
		catch (Throwable ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("Failed to introspect @Bean methods on class [" + metadata.getClassName() + "]: " + ex);
			}
			return false;
		}
	}

	/**
	 * Determine the order for the given configuration class metadata.
	 * @param metadata the metadata of the annotated class
	 * @return the {@code @Order} annotation value on the configuration class,
	 * or {@code Ordered.LOWEST_PRECEDENCE} if none declared
	 * @since 5.0
	 */
	@Nullable
	public static Integer getOrder(AnnotationMetadata metadata) {
		Map<String, Object> orderAttributes = metadata.getAnnotationAttributes(Order.class.getName());
		return (orderAttributes != null ? ((Integer) orderAttributes.get(AnnotationUtils.VALUE)) : null);
	}

	/**
	 * Determine the order for the given configuration class bean definition,
	 * as set by {@link #checkConfigurationClassCandidate}.
	 * @param beanDef the bean definition to check
	 * @return the {@link Order @Order} annotation value on the configuration class,
	 * or {@link Ordered#LOWEST_PRECEDENCE} if none declared
	 * @since 4.2
	 */
	public static int getOrder(BeanDefinition beanDef) {
		Integer order = (Integer) beanDef.getAttribute(ORDER_ATTRIBUTE);
		return (order != null ? order : Ordered.LOWEST_PRECEDENCE);
	}

}
