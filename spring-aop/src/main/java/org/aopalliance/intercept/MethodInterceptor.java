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

package org.aopalliance.intercept;

/**
 * Intercepts calls on an interface on its way to the target. These
 * are nested "on top" of the target.
 *
 * <p>The user should implement the {@link #invoke(MethodInvocation)}
 * method to modify the original behavior. E.g. the following class
 * implements a tracing interceptor (traces all the calls on the
 * intercepted method(s)):
 *
 * <pre class=code>
 * class TracingInterceptor implements MethodInterceptor {
 *   Object invoke(MethodInvocation i) throws Throwable {
 *     System.out.println("method "+i.getMethod()+" is called on "+
 *                        i.getThis()+" with args "+i.getArguments());
 *     Object ret=i.proceed();
 *     System.out.println("method "+i.getMethod()+" returns "+ret);
 *     return ret;
 *   }
 * }
 * </pre>
 *
 * @author Rod Johnson
 *
 * AOP中，方法最终可执行的增强器，拦截器，因为通过责任链的设计模式执行。
 *
 * 在AbstractAutoProxyCreator完成解析封装bean之后的具体增强器(具体增强策略Advice)，可能有两种略不相同的增强器：
 * 可能是：1、如：AspectJMethodBeforeAdvice、AspectJAfterReturningAdvice，它需要转换成MethodBeforeAdviceInterceptor执行，持有这个advice对象。
 * 也可能是：2、如：AspectJAfterAdvice、AspectJAfterThrowingAdvice、AspectJAroundAdvice，它本身就是MethodInterceptor，不需要转换，不持有这个advice对象，直接可以执行。
 *
 * 一些advice，最终在代理执行的时候，通过
 * @see org.springframework.aop.framework.AdvisedSupport#getInterceptorsAndDynamicInterceptionAdvice(java.lang.reflect.Method, java.lang.Class)
 * 转换成MethodInterceptor
 */
@FunctionalInterface
public interface MethodInterceptor extends Interceptor {

	/**
	 * Implement this method to perform extra treatments before and
	 * after the invocation. Polite implementations would certainly
	 * like to invoke {@link Joinpoint#proceed()}.
	 * @param invocation the method invocation joinpoint
	 * @return the result of the call to {@link Joinpoint#proceed()};
	 * might be intercepted by the interceptor
	 * @throws Throwable if the interceptors or the target object
	 * throws an exception
	 */
	Object invoke(MethodInvocation invocation) throws Throwable;

}
