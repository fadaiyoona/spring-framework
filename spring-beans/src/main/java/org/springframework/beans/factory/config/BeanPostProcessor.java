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

package org.springframework.beans.factory.config;

import org.springframework.beans.BeansException;
import org.springframework.lang.Nullable;

/**
 * Factory hook that allows for custom modification of new bean instances &mdash;
 * for example, checking for marker interfaces or wrapping beans with proxies.
 *
 * <p>Typically, post-processors that populate beans via marker interfaces
 * or the like will implement {@link #postProcessBeforeInitialization},
 * while post-processors that wrap beans with proxies will normally
 * implement {@link #postProcessAfterInitialization}.
 *
 * <h3>Registration</h3>
 * <p>An {@code ApplicationContext} can autodetect {@code BeanPostProcessor} beans
 * in its bean definitions and apply those post-processors to any beans subsequently
 * created. A plain {@code BeanFactory} allows for programmatic registration of
 * post-processors, applying them to all beans created through the bean factory.
 *
 * <h3>Ordering</h3>
 * <p>{@code BeanPostProcessor} beans that are autodetected in an
 * {@code ApplicationContext} will be ordered according to
 * {@link org.springframework.core.PriorityOrdered} and
 * {@link org.springframework.core.Ordered} semantics. In contrast,
 * {@code BeanPostProcessor} beans that are registered programmatically with a
 * {@code BeanFactory} will be applied in the order of registration; any ordering
 * semantics expressed through implementing the
 * {@code PriorityOrdered} or {@code Ordered} interface will be ignored for
 * programmatically registered post-processors. Furthermore, the
 * {@link org.springframework.core.annotation.Order @Order} annotation is not
 * taken into account for {@code BeanPostProcessor} beans.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 10.10.2003
 * @see InstantiationAwareBeanPostProcessor
 * @see DestructionAwareBeanPostProcessor
 * @see ConfigurableBeanFactory#addBeanPostProcessor
 * @see BeanFactoryPostProcessor
 *
 * 实例化：实例化的过程是一个创建Bean的过程，即调用Bean的构造函数，单例的Bean放入单例池中
 * 初始化：初始化的过程是一个赋值的过程，即调用Bean的setter，设置Bean的属性
 *
 * Bean初始化前后执行在Bean实例化/依赖注入完毕以及自定义的初始化方法前后调用
 */
public interface BeanPostProcessor {

	/**
	 * Apply this {@code BeanPostProcessor} to the given new bean instance <i>before</i> any bean
	 * initialization callbacks (like InitializingBean's {@code afterPropertiesSet}
	 * or a custom init-method). The bean will already be populated with property values.
	 * The returned bean instance may be a wrapper around the original.
	 * <p>The default implementation returns the given {@code bean} as-is.
	 * @param bean the new bean instance
	 * @param beanName the name of the bean
	 * @return the bean instance to use, either the original or a wrapped one;
	 * if {@code null}, no subsequent BeanPostProcessors will be invoked
	 * @throws org.springframework.beans.BeansException in case of errors
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet
	 *
	 * 在Bean实例化/依赖注入完毕以及自定义的初始化方法之前调用。
	 * 什么叫自定义初始化方法：比如init-method、比如@PostConstruct标、比如实现InitailztingBean接口的方法等等
	 * @param bean 这个Bean实例
	 * @param beanName bean名称
	 */
	@Nullable
	default Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

	/**
	 * Apply this {@code BeanPostProcessor} to the given new bean instance <i>after</i> any bean
	 * initialization callbacks (like InitializingBean's {@code afterPropertiesSet}
	 * or a custom init-method). The bean will already be populated with property values.
	 * The returned bean instance may be a wrapper around the original.
	 * <p>In case of a FactoryBean, this callback will be invoked for both the FactoryBean
	 * instance and the objects created by the FactoryBean (as of Spring 2.0). The
	 * post-processor can decide whether to apply to either the FactoryBean or created
	 * objects or both through corresponding {@code bean instanceof FactoryBean} checks.
	 * <p>This callback will also be invoked after a short-circuiting triggered by a
	 * {@link InstantiationAwareBeanPostProcessor#postProcessBeforeInstantiation} method,
	 * in contrast to all other {@code BeanPostProcessor} callbacks.
	 * <p>The default implementation returns the given {@code bean} as-is.
	 * @param bean the new bean instance
	 * @param beanName the name of the bean
	 * @return the bean instance to use, either the original or a wrapped one;
	 * if {@code null}, no subsequent BeanPostProcessors will be invoked
	 * @throws org.springframework.beans.BeansException in case of errors
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet
	 * @see org.springframework.beans.factory.FactoryBean
	 *
	 * 在上面基础上，初始化方法之后调用
	 * @see org.springframework.context.support.ApplicationListenerDetector#postProcessAfterInitialization(java.lang.Object, java.lang.String)
	 * ApplicationListenerDetector重点实现了postProcessAfterInitialization方法：
	 * 测那些实现了接口ApplicationListener的bean，在它们创建时初始化之后，将它们添加到应用上下文的事件多播器上。
	 * 并在这些ApplicationListener bean销毁之前，将它们从应用上下文的事件多播器上移除。
	 */
	@Nullable
	default Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

}
