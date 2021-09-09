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

package org.springframework.aop.framework;

import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.AopInvocationException;
import org.springframework.aop.RawTargetAccess;
import org.springframework.aop.TargetSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.DecoratingProxy;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * JDK-based {@link AopProxy} implementation for the Spring AOP framework,
 * based on JDK {@link java.lang.reflect.Proxy dynamic proxies}.
 *
 * <p>Creates a dynamic proxy, implementing the interfaces exposed by
 * the AopProxy. Dynamic proxies <i>cannot</i> be used to proxy methods
 * defined in classes, rather than interfaces.
 *
 * <p>Objects of this type should be obtained through proxy factories,
 * configured by an {@link AdvisedSupport} class. This class is internal
 * to Spring's AOP framework and need not be used directly by client code.
 *
 * <p>Proxies created using this class will be thread-safe if the
 * underlying (target) class is thread-safe.
 *
 * <p>Proxies are serializable so long as all Advisors (including Advices
 * and Pointcuts) and the TargetSource are serializable.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Dave Syer
 * @see java.lang.reflect.Proxy
 * @see AdvisedSupport
 * @see ProxyFactory
 *
 * // 我们发现它自己就实现了了InvocationHandler，所以处理器就是它自己。会实现invoke方法
 * // 它还是个final类  默认是包的访问权限
 */
final class JdkDynamicAopProxy implements AopProxy, InvocationHandler, Serializable {

	/** use serialVersionUID from Spring 1.2 for interoperability. */
	private static final long serialVersionUID = 5531744639992436476L;


	/*
	 * NOTE: We could avoid the code duplication between this class and the CGLIB
	 * proxies by refactoring "invoke" into a template method. However, this approach
	 * adds at least 10% performance overhead versus a copy-paste solution, so we sacrifice
	 * elegance for performance. (We have a good test suite to ensure that the different
	 * proxies behave the same :-)
	 * This way, we can also more easily take advantage of minor optimizations in each class.
	 */

	/** We use a static Log to avoid serialization issues. */
	private static final Log logger = LogFactory.getLog(JdkDynamicAopProxy.class);

	/** Config used to configure this proxy. */
	/** 这里就保存这个AOP代理所有的配置信息  包括所有的增强器等等 */
	private final AdvisedSupport advised;

	/**
	 * Is the {@link #equals} method defined on the proxied interfaces?
	 */
	// 标记equals方法和hashCode方法是否定义在了接口上=====
	private boolean equalsDefined;

	/**
	 * Is the {@link #hashCode} method defined on the proxied interfaces?
	 */
	private boolean hashCodeDefined;


	/**
	 * Construct a new JdkDynamicAopProxy for the given AOP configuration.
	 * @param config the AOP configuration as AdvisedSupport object
	 * @throws AopConfigException if the config is invalid. We try to throw an informative
	 * exception in this case, rather than let a mysterious failure happen later.
	 */
	public JdkDynamicAopProxy(AdvisedSupport config) throws AopConfigException {
		Assert.notNull(config, "AdvisedSupport must not be null");
		// 内部再校验一次：必须有至少一个增强器  和  目标实例才行
		if (config.getAdvisors().length == 0 && config.getTargetSource() == AdvisedSupport.EMPTY_TARGET_SOURCE) {
			throw new AopConfigException("No advisors and no TargetSource specified");
		}
		this.advised = config;
	}


	@Override
	public Object getProxy() {
		return getProxy(ClassUtils.getDefaultClassLoader());
	}

	// 真正创建JDK动态代理实例的地方
	@Override
	public Object getProxy(@Nullable ClassLoader classLoader) {
		if (logger.isTraceEnabled()) {
			logger.trace("Creating JDK dynamic proxy: " + this.advised.getTargetSource());
		}
		// 这部很重要，就是去找接口 我们看到最终代理的接口就是这里返回的所有接口们（除了我们自己的接口，还有Spring默认的一些接口）  大致过程如下：
		//1、获取目标对象自己实现的接口们(最终肯定都会被代理的)
		//2、是否添加`SpringProxy`这个接口：目标对象实现对就不添加了，没实现过就添加true
		//3、是否新增`Adviced`接口，注意不是Advice通知接口。 实现过就不实现了，没实现过并且advised.isOpaque()=false就添加（默认是会添加的）
		//4、是否新增DecoratingProxy接口。传入的参数decoratingProxy为true，并且没实现过就添加（显然这里，首次进来是会添加的）
		//5、代理类的接口一共是目标对象的接口+上面三个接口SpringProxy、Advised、DecoratingProxy（SpringProxy是个标记接口而已，其余的接口都有对应的方法的）
		//DecoratingProxy 这个接口Spring4.3后才提供
		Class<?>[] proxiedInterfaces = AopProxyUtils.completeProxiedInterfaces(this.advised, true);
		findDefinedEqualsAndHashCodeMethods(proxiedInterfaces);
		// 第三个参数传的this，处理器就是自己嘛   到此一个代理对象就此new出来啦
		return Proxy.newProxyInstance(classLoader, proxiedInterfaces, this);
	}

	/**
	 * Finds any {@link #equals} or {@link #hashCode} method that may be defined
	 * on the supplied set of interfaces.
	 * @param proxiedInterfaces the interfaces to introspect
	 */
	// 找找看看接口里有没有自己定义equals方法和hashCode方法，这个很重要  然后标记一下
	// 注意此处用的是getDeclaredMethods，只会找自己的
	private void findDefinedEqualsAndHashCodeMethods(Class<?>[] proxiedInterfaces) {
		for (Class<?> proxiedInterface : proxiedInterfaces) {
			Method[] methods = proxiedInterface.getDeclaredMethods();
			for (Method method : methods) {
				if (AopUtils.isEqualsMethod(method)) {
					this.equalsDefined = true;
				}
				if (AopUtils.isHashCodeMethod(method)) {
					this.hashCodeDefined = true;
				}
				// 小技巧：两个都找到了 就没必要继续循环勒
				if (this.equalsDefined && this.hashCodeDefined) {
					return;
				}
			}
		}
	}


	/**
	 * Implementation of {@code InvocationHandler.invoke}.
	 * <p>Callers will see exactly the exception thrown by the target,
	 * unless a hook method throws an exception.
	 */
	// 对于这部分代码和采用CGLIB的大部分逻辑都是一样的，Spring对此的解释很有意思：
	// 本来是可以抽取出来的，使得代码看起来更优雅。但是因为此会带来10%得性能损耗，所以Spring最终采用了粘贴复制的方式各用一份
	// Spring说它提供了基础的套件，来保证两个的执行行为是一致的。
	//proxy:指的是我们所代理的那个真实的对象；method:指的是我们所代理的那个真实对象的某个方法的Method对象args:指的是调用那个真实对象方法的参数。

	// 此处重点分析一下此方法，这样在CGLIB的时候，就可以一带而过了~~~因为大致逻辑是一样的
	@Override
	@Nullable
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		Object oldProxy = null;
		boolean setProxyContext = false;

		// 进入invoke方法后，最终操作的是targetSource对象
		// 因为InvocationHandler持久的就是targetSource，最终通过getTarget拿到目标对象
		TargetSource targetSource = this.advised.targetSource;
		Object target = null;

		try {
			//“通常情况”Spring AOP不会对equals、hashCode方法进行拦截增强,所以此处做了处理
			// equalsDefined为false（表示自己没有定义过eequals方法）  那就交给代理去比较
			// hashCode同理，只要你自己没有实现过此方法，那就交给代理吧
			// 需要注意的是：这里统一指的是，如果接口上有此方法，但是你自己并没有实现equals和hashCode方法，那就走AOP这里的实现
			// 如国接口上没有定义此方法，只是实现类里自己@Override了HashCode，那是无效的，就是普通执行吧
			if (!this.equalsDefined && AopUtils.isEqualsMethod(method)) {
				// The target does not implement the equals(Object) method itself.
				return equals(args[0]);
			}
			else if (!this.hashCodeDefined && AopUtils.isHashCodeMethod(method)) {
				// The target does not implement the hashCode() method itself.
				return hashCode();
			}
			// 下面两段做了很有意思的处理：DecoratingProxy的方法和Advised接口的方法  都是是最终调用了config，也就是this.advised去执行的~~~~
			else if (method.getDeclaringClass() == DecoratingProxy.class) {
				// There is only getDecoratedClass() declared -> dispatch to proxy config.
				return AopProxyUtils.ultimateTargetClass(this.advised);
			}
			else if (!this.advised.opaque && method.getDeclaringClass().isInterface() &&
					method.getDeclaringClass().isAssignableFrom(Advised.class)) {
				// Service invocations on ProxyConfig with the proxy config...
				return AopUtils.invokeJoinpointUsingReflection(this.advised, method, args);
			}

			// 这个是最终该方法的返回值~~~~
			Object retVal;

			//是否暴露代理对象，默认false可配置为true，如果暴露就意味着允许在线程内共享代理对象，
			//注意这是在线程内，也就是说同一线程的任意地方都能通过AopContext获取该代理对象，这应该算是比较高级一点的用法了。
			// 这里缓存一份代理对象在oldProxy里~~~后面有用
			if (this.advised.exposeProxy) {
				// Make invocation available if necessary.
				oldProxy = AopContext.setCurrentProxy(proxy);
				setProxyContext = true;
			}

			// Get as late as possible to minimize the time we "own" the target,
			// in case it comes from a pool.
			//通过目标源获取目标对象 (此处Spring建议获取目标对象靠后获取  而不是放在上面)
			target = targetSource.getTarget();
			Class<?> targetClass = (target != null ? target.getClass() : null);

			// Get the interception chain for this method.
			// 获取作用在这个方法上的所有拦截器链~~~  参见DefaultAdvisorChainFactory#getInterceptorsAndDynamicInterceptionAdvice方法
			// 会根据切点表达式去匹配这个方法。因此其实每个方法都会进入这里，只是有很多方法得chain事Empty而已
			List<Object> chain = this.advised.getInterceptorsAndDynamicInterceptionAdvice(method, targetClass);

			// Check whether we have any advice. If we don't, we can fallback on direct
			// reflective invocation of the target, and avoid creating a MethodInvocation.
			if (chain.isEmpty()) {
				// We can skip creating a MethodInvocation: just invoke the target directly
				// Note that the final invoker must be an InvokerInterceptor so we know it does
				// nothing but a reflective operation on the target, and no hot swapping or fancy proxying.
				// 若拦截器为空，那就直接调用目标方法了
				// 对参数进行适配：主要处理一些数组类型的参数，看是表示一个参数  还是表示多个参数（可变参数最终到此都是数组类型，所以最好是需要一次适配）
				Object[] argsToUse = AopProxyUtils.adaptArgumentsIfNecessary(method, args);
				// 这句代码的意思是直接调用目标方法~~~
				retVal = AopUtils.invokeJoinpointUsingReflection(target, method, argsToUse);
			}
			else {
				// We need to create a method invocation...
				// 创建一个invocation ，此处为ReflectiveMethodInvocation  最终是通过它，去执行前置加强、后置加强等等逻辑
				MethodInvocation invocation =
						new ReflectiveMethodInvocation(proxy, target, method, args, targetClass, chain);
				// Proceed to the joinpoint through the interceptor chain.
				// 此处会执行所有的拦截器链  交给AOP联盟的MethodInvocation去处理。当然实现还是我们Spring得ReflectiveMethodInvocation
				retVal = invocation.proceed();
			}

			// Massage return value if necessary.
			// 获取返回值的类型
			Class<?> returnType = method.getReturnType();
			if (retVal != null && retVal == target &&
					returnType != Object.class && returnType.isInstance(proxy) &&
					!RawTargetAccess.class.isAssignableFrom(method.getDeclaringClass())) {
				// Special case: it returned "this" and the return type of the method
				// is type-compatible. Note that we can't help if the target sets
				// a reference to itself in another returned object.
				// 一些列的判断条件，如果返回值不为空，且为目标对象的话，就直接将目标对象赋值给retVal
				retVal = proxy;
			}
			// 返回null，并且还不是Void类型。。。抛错
			else if (retVal == null && returnType != Void.TYPE && returnType.isPrimitive()) {
				throw new AopInvocationException(
						"Null return value from advice does not match primitive return type for: " + method);
			}
			return retVal;
		}
		finally {
			if (target != null && !targetSource.isStatic()) {
				// Must have come from TargetSource.
				targetSource.releaseTarget(target);
			}
			// 把老的代理对象重新set进去~~~
			if (setProxyContext) {
				// Restore old proxy.
				AopContext.setCurrentProxy(oldProxy);
			}
		}
	}


	/**
	 * Equality means interfaces, advisors and TargetSource are equal.
	 * <p>The compared object may be a JdkDynamicAopProxy instance itself
	 * or a dynamic proxy wrapping a JdkDynamicAopProxy instance.
	 */
	@Override
	public boolean equals(@Nullable Object other) {
		if (other == this) {
			return true;
		}
		if (other == null) {
			return false;
		}

		JdkDynamicAopProxy otherProxy;
		if (other instanceof JdkDynamicAopProxy) {
			otherProxy = (JdkDynamicAopProxy) other;
		}
		else if (Proxy.isProxyClass(other.getClass())) {
			InvocationHandler ih = Proxy.getInvocationHandler(other);
			if (!(ih instanceof JdkDynamicAopProxy)) {
				return false;
			}
			otherProxy = (JdkDynamicAopProxy) ih;
		}
		else {
			// Not a valid comparison...
			return false;
		}

		// If we get here, otherProxy is the other AopProxy.
		return AopProxyUtils.equalsInProxy(this.advised, otherProxy.advised);
	}

	/**
	 * Proxy uses the hash code of the TargetSource.
	 */
	@Override
	public int hashCode() {
		return JdkDynamicAopProxy.class.hashCode() * 13 + this.advised.getTargetSource().hashCode();
	}

}
