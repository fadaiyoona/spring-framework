/*
 * Copyright 2002-2016 the original author or authors.
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

package org.aopalliance.aop;

/**
 * Tag interface for Advice. Implementations can be any type
 * of advice, such as Interceptors.
 *
 * @author Rod Johnson
 * @version $Id: Advice.java,v 1.1 2004/03/19 17:02:16 johnsonr Exp $
 *
 * AbstractAutoProxyCreator完成解析封装bean之后的具体增强器(具体增强策略Advice)
 * 对应有多种不同的增强策略实现，@After、@Before等等
 *
 * 最终在代理执行的时候，还会通过
 * @see org.springframework.aop.framework.AdvisedSupport#getInterceptorsAndDynamicInterceptionAdvice(java.lang.reflect.Method, java.lang.Class)
 * 转换成MethodInterceptor
 */
public interface Advice {

}
