/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.aop.framework;

import java.io.Closeable;

import org.springframework.beans.factory.Aware;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.Ordered;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;

/**
 * Base class with common functionality for proxy processors, in particular
 * ClassLoader management and the {@link #evaluateProxyInterfaces} algorithm.
 *
 * @author Juergen Hoeller
 * @since 4.1
 * @see AbstractAdvisingBeanPostProcessor
 * @see org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator
 */
@SuppressWarnings("serial")
public class ProxyProcessorSupport extends ProxyConfig implements Ordered, BeanClassLoaderAware, AopInfrastructureBean {

	/**
	 * This should run after all other processors, so that it can just add
	 * an advisor to existing proxies rather than double-proxy.
	 */
	private int order = Ordered.LOWEST_PRECEDENCE;

	@Nullable
	private ClassLoader proxyClassLoader = ClassUtils.getDefaultClassLoader();

	private boolean classLoaderConfigured = false;


	/**
	 * Set the ordering which will apply to this processor's implementation
	 * of {@link Ordered}, used when applying multiple processors.
	 * <p>The default value is {@code Ordered.LOWEST_PRECEDENCE}, meaning non-ordered.
	 * @param order the ordering value
	 */
	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	/**
	 * Set the ClassLoader to generate the proxy class in.
	 * <p>Default is the bean ClassLoader, i.e. the ClassLoader used by the containing
	 * {@link org.springframework.beans.factory.BeanFactory} for loading all bean classes.
	 * This can be overridden here for specific proxies.
	 */
	public void setProxyClassLoader(@Nullable ClassLoader classLoader) {
		this.proxyClassLoader = classLoader;
		this.classLoaderConfigured = (classLoader != null);
	}

	/**
	 * Return the configured proxy ClassLoader for this processor.
	 */
	@Nullable
	protected ClassLoader getProxyClassLoader() {
		return this.proxyClassLoader;
	}

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		if (!this.classLoaderConfigured) {
			this.proxyClassLoader = classLoader;
		}
	}


	/**
	 * Check the interfaces on the given bean class and apply them to the {@link ProxyFactory},
	 * if appropriate.
	 * <p>Calls {@link #isConfigurationCallbackInterface} and {@link #isInternalLanguageInterface}
	 * to filter for reasonable proxy interfaces, falling back to a target-class proxy otherwise.
	 * @param beanClass the class of the bean
	 * @param proxyFactory the ProxyFactory for the bean
	 *
	 * 	这是它最重要的一个方法：就是把该bean所有的实现的接口 都作用在ProxyFactory 上 当然是有过滤得
	 */
	protected void evaluateProxyInterfaces(Class<?> beanClass, ProxyFactory proxyFactory) {
		// 拿到该类所有实现的接口们~~~~
		Class<?>[] targetInterfaces = ClassUtils.getAllInterfacesForClass(beanClass, getProxyClassLoader());
		// 标记：是否存在“有问题的”代理接口  默认是false
		boolean hasReasonableProxyInterface = false;
		for (Class<?> ifc : targetInterfaces) {
			//判断这些接口是否是“有问题的”：既我们需要处理的
			if (!isConfigurationCallbackInterface(ifc) && !isInternalLanguageInterface(ifc) &&
					ifc.getMethods().length > 0) {
				hasReasonableProxyInterface = true;
				break;
			}
		}
		// 说明除开哪些接口外，但凡有一个有用的接口，就add进去（这样就会采用JDK的动态代理了）
		if (hasReasonableProxyInterface) {
			// Must allow for introductions; can't just set interfaces to the target's interfaces only.
			for (Class<?> ifc : targetInterfaces) {
				proxyFactory.addInterface(ifc);
			}
		}
		else {
			// 否则直接采用CGLIB
			proxyFactory.setProxyTargetClass(true);
		}
	}

	/**
	 * Determine whether the given interface is just a container callback and
	 * therefore not to be considered as a reasonable proxy interface.
	 * <p>If no reasonable proxy interface is found for a given bean, it will get
	 * proxied with its full target class, assuming that as the user's intention.
	 * @param ifc the interface to check
	 * @return whether the given interface is just a container callback
	 *
	 * InitializingBean...Aware接口的子接口等等这些回调性质的接口
	 */
	protected boolean isConfigurationCallbackInterface(Class<?> ifc) {
		return (InitializingBean.class == ifc || DisposableBean.class == ifc || Closeable.class == ifc ||
				AutoCloseable.class == ifc || ObjectUtils.containsElement(ifc.getInterfaces(), Aware.class));
	}

	/**
	 * Determine whether the given interface is a well-known internal language interface
	 * and therefore not to be considered as a reasonable proxy interface.
	 * <p>If no reasonable proxy interface is found for a given bean, it will get
	 * proxied with its full target class, assuming that as the user's intention.
	 * @param ifc the interface to check
	 * @return whether the given interface is an internal language interface
	 *
	 * 接口名称为这些的  也就是spring aop自己的东西
	 */
	protected boolean isInternalLanguageInterface(Class<?> ifc) {
		return (ifc.getName().equals("groovy.lang.GroovyObject") ||
				ifc.getName().endsWith(".cglib.proxy.Factory") ||
				ifc.getName().endsWith(".bytebuddy.MockAccess"));
	}

}
