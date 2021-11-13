/*
 * Copyright 2002-2017 the original author or authors.
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

package org.springframework.web.servlet;

import java.util.Locale;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.lang.Nullable;

/**
 * Interface for web-based locale resolution strategies that allows for
 * both locale resolution via the request and locale modification via
 * request and response.
 *
 * <p>This interface allows for implementations based on request, session,
 * cookies, etc. The default implementation is
 * {@link org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver},
 * simply using the request's locale provided by the respective HTTP header.
 *
 * <p>Use {@link org.springframework.web.servlet.support.RequestContext#getLocale()}
 * to retrieve the current locale in controllers or views, independent
 * of the actual resolution strategy.
 *
 * <p>Note: As of Spring 4.0, there is an extended strategy interface
 * called {@link LocaleContextResolver}, allowing for resolution of
 * a {@link org.springframework.context.i18n.LocaleContext} object,
 * potentially including associated time zone information. Spring's
 * provided resolver implementations implement the extended
 * {@link LocaleContextResolver} interface wherever appropriate.
 *
 * @author Juergen Hoeller
 * @since 27.02.2003
 * @see LocaleContextResolver
 * @see org.springframework.context.i18n.LocaleContextHolder
 * @see org.springframework.web.servlet.support.RequestContext#getLocale
 * @see org.springframework.web.servlet.support.RequestContextUtils#getLocale
 * 其主要作用在于根据不同的用户区域展示不同的视图，而用户的区域也称为Locale，该信息是可以由前端直接获取的。
 * 通过这种方式，可以实现一种国际化的目的，比如针对美国用户可以提供一个视图，而针对中国用户则可以提供另一个视图。
 *
 * 在Spring的国际化配置中一共有3种使用方式。
 * 1、基于URL参数的初始化。 --- AcceptHeaderLocaleResolver
 * 	通过URL参数来公职国际化，比如你在页面上加一句<a href="?locale=zh_CN">简体中文</a>来控制项目中使用的国际化参数。
 * 	而提供这个功能的就是AcceptHeaderLocaleResolver，默认的参数名为locale，注意大小写。里面放的就是你的提交参数，比如en_US、zh_CN之类的。
 * 2、基于session的配置 --- SessionLocaleResolver
 * 	它通过检验用户会话中预支的属性来解析区域。最常用的是根据用户本次会话过程中的语言设定决定语言种类（例如，用户登录时选择语言种类，则此次登录周期内统一使用此语言设定）
 * 	如果该会话属性不存在，它会根据accept-language HTTP头部确定默认区域
 * 3、基于cookie的国际化配置 --- CookieLocaleResolver
 *  CookieLocaleResolver用于通过浏览器的cookie设置取得Locale对象。这种策略在应用程序不支持会话或者状态必须保存在客户端时有用。
 *
 */
public interface LocaleResolver {

	/**
	 * Resolve the current locale via the given request.
	 * Can return a default locale as fallback in any case.
	 * @param request the request to resolve the locale for
	 * @return the current locale (never {@code null})
	 *
	 * 根据request对象根据指定的方式获取一个Locale，如果没有获取到，则使用用户指定的默认的Locale
	 */
	Locale resolveLocale(HttpServletRequest request);

	/**
	 * Set the current locale to the given one.
	 * @param request the request to be used for locale modification
	 * @param response the response to be used for locale modification
	 * @param locale the new locale, or {@code null} to clear the locale
	 * @throws UnsupportedOperationException if the LocaleResolver
	 * implementation does not support dynamic changing of the locale
	 * 用于实现Locale的切换。比如SessionLocaleResolver获取Locale的方式是从session中读取，但如果
	//户想要切换其展示的样式(由英文切换为中文)，那么这里的setLocale()方法就提供了这样一种可能
	 */
	void setLocale(HttpServletRequest request, @Nullable HttpServletResponse response, @Nullable Locale locale);

}
