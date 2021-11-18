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

package org.springframework.context.support;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeansException;
import org.springframework.beans.CachedIntrospectionResults;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.support.ResourceEditorRegistrar;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.HierarchicalMessageSource;
import org.springframework.context.LifecycleProcessor;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceAware;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.ContextStartedEvent;
import org.springframework.context.event.ContextStoppedEvent;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.context.expression.StandardBeanExpressionResolver;
import org.springframework.context.weaving.LoadTimeWeaverAware;
import org.springframework.context.weaving.LoadTimeWeaverAwareProcessor;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Abstract implementation of the {@link org.springframework.context.ApplicationContext}
 * interface. Doesn't mandate the type of storage used for configuration; simply
 * implements common context functionality. Uses the Template Method design pattern,
 * requiring concrete subclasses to implement abstract methods.
 *
 * <p>In contrast to a plain BeanFactory, an ApplicationContext is supposed
 * to detect special beans defined in its internal bean factory:
 * Therefore, this class automatically registers
 * {@link org.springframework.beans.factory.config.BeanFactoryPostProcessor BeanFactoryPostProcessors},
 * {@link org.springframework.beans.factory.config.BeanPostProcessor BeanPostProcessors},
 * and {@link org.springframework.context.ApplicationListener ApplicationListeners}
 * which are defined as beans in the context.
 *
 * <p>A {@link org.springframework.context.MessageSource} may also be supplied
 * as a bean in the context, with the name "messageSource"; otherwise, message
 * resolution is delegated to the parent context. Furthermore, a multicaster
 * for application events can be supplied as an "applicationEventMulticaster" bean
 * of type {@link org.springframework.context.event.ApplicationEventMulticaster}
 * in the context; otherwise, a default multicaster of type
 * {@link org.springframework.context.event.SimpleApplicationEventMulticaster} will be used.
 *
 * <p>Implements resource loading by extending
 * {@link org.springframework.core.io.DefaultResourceLoader}.
 * Consequently treats non-URL resource paths as class path resources
 * (supporting full class path resource names that include the package path,
 * e.g. "mypackage/myresource.dat"), unless the {@link #getResourceByPath}
 * method is overridden in a subclass.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @author Stephane Nicoll
 * @author Sam Brannen
 * @since January 21, 2001
 * @see #refreshBeanFactory
 * @see #getBeanFactory
 * @see org.springframework.beans.factory.config.BeanFactoryPostProcessor
 * @see org.springframework.beans.factory.config.BeanPostProcessor
 * @see org.springframework.context.event.ApplicationEventMulticaster
 * @see org.springframework.context.ApplicationListener
 * @see org.springframework.context.MessageSource
 */
public abstract class AbstractApplicationContext extends DefaultResourceLoader
		implements ConfigurableApplicationContext {

	/**
	 * Name of the MessageSource bean in the factory.
	 * If none is supplied, message resolution is delegated to the parent.
	 * @see MessageSource
	 */
	public static final String MESSAGE_SOURCE_BEAN_NAME = "messageSource";

	/**
	 * Name of the LifecycleProcessor bean in the factory.
	 * If none is supplied, a DefaultLifecycleProcessor is used.
	 * @see org.springframework.context.LifecycleProcessor
	 * @see org.springframework.context.support.DefaultLifecycleProcessor
	 */
	public static final String LIFECYCLE_PROCESSOR_BEAN_NAME = "lifecycleProcessor";

	/**
	 * Name of the ApplicationEventMulticaster bean in the factory.
	 * If none is supplied, a default SimpleApplicationEventMulticaster is used.
	 * @see org.springframework.context.event.ApplicationEventMulticaster
	 * @see org.springframework.context.event.SimpleApplicationEventMulticaster
	 */
	public static final String APPLICATION_EVENT_MULTICASTER_BEAN_NAME = "applicationEventMulticaster";


	static {
		// Eagerly load the ContextClosedEvent class to avoid weird classloader issues
		// on application shutdown in WebLogic 8.1. (Reported by Dustin Woods.)
		ContextClosedEvent.class.getName();
	}


	/** Logger used by this class. Available to subclasses. */
	protected final Log logger = LogFactory.getLog(getClass());

	/** Unique id for this context, if any. */
	private String id = ObjectUtils.identityToString(this);

	/** Display name. */
	private String displayName = ObjectUtils.identityToString(this);

	/** Parent context. */
	@Nullable
	private ApplicationContext parent;

	/** Environment used by this context. */
	@Nullable
	private ConfigurableEnvironment environment;

	/** BeanFactoryPostProcessors to apply on refresh. */
	private final List<BeanFactoryPostProcessor> beanFactoryPostProcessors = new ArrayList<>();

	/** System time in milliseconds when this context started. */
	private long startupDate;

	/** Flag that indicates whether this context is currently active. */
	private final AtomicBoolean active = new AtomicBoolean();

	/** Flag that indicates whether this context has been closed already. */
	private final AtomicBoolean closed = new AtomicBoolean();

	/** Synchronization monitor for the "refresh" and "destroy". */
	private final Object startupShutdownMonitor = new Object();

	/** Reference to the JVM shutdown hook, if registered. */
	@Nullable
	private Thread shutdownHook;

	/** ResourcePatternResolver used by this context. */
	private ResourcePatternResolver resourcePatternResolver;

	/** LifecycleProcessor for managing the lifecycle of beans within this context. */
	@Nullable
	private LifecycleProcessor lifecycleProcessor;

	/** MessageSource we delegate our implementation of this interface to. */
	@Nullable
	private MessageSource messageSource;

	/** Helper class used in event publishing. */
	@Nullable
	private ApplicationEventMulticaster applicationEventMulticaster;

	/** Statically specified listeners. */
	private final Set<ApplicationListener<?>> applicationListeners = new LinkedHashSet<>();

	/** Local listeners registered before refresh. */
	@Nullable
	private Set<ApplicationListener<?>> earlyApplicationListeners;

	/** ApplicationEvents published before the multicaster setup. */
	@Nullable
	private Set<ApplicationEvent> earlyApplicationEvents;


	/**
	 * Create a new AbstractApplicationContext with no parent.
	 */
	public AbstractApplicationContext() {
		this.resourcePatternResolver = getResourcePatternResolver();
	}

	/**
	 * Create a new AbstractApplicationContext with the given parent context.
	 * @param parent the parent context
	 */
	public AbstractApplicationContext(@Nullable ApplicationContext parent) {
		this();
		setParent(parent);
	}


	//---------------------------------------------------------------------
	// Implementation of ApplicationContext interface
	//---------------------------------------------------------------------

	/**
	 * Set the unique id of this application context.
	 * <p>Default is the object id of the context instance, or the name
	 * of the context bean if the context is itself defined as a bean.
	 * @param id the unique id of the context
	 */
	@Override
	public void setId(String id) {
		this.id = id;
	}

	@Override
	public String getId() {
		return this.id;
	}

	@Override
	public String getApplicationName() {
		return "";
	}

	/**
	 * Set a friendly name for this context.
	 * Typically done during initialization of concrete context implementations.
	 * <p>Default is the object id of the context instance.
	 */
	public void setDisplayName(String displayName) {
		Assert.hasLength(displayName, "Display name must not be empty");
		this.displayName = displayName;
	}

	/**
	 * Return a friendly name for this context.
	 * @return a display name for this context (never {@code null})
	 */
	@Override
	public String getDisplayName() {
		return this.displayName;
	}

	/**
	 * Return the parent context, or {@code null} if there is no parent
	 * (that is, this context is the root of the context hierarchy).
	 */
	@Override
	@Nullable
	public ApplicationContext getParent() {
		return this.parent;
	}

	/**
	 * Set the {@code Environment} for this application context.
	 * <p>Default value is determined by {@link #createEnvironment()}. Replacing the
	 * default with this method is one option but configuration through {@link
	 * #getEnvironment()} should also be considered. In either case, such modifications
	 * should be performed <em>before</em> {@link #refresh()}.
	 * @see org.springframework.context.support.AbstractApplicationContext#createEnvironment
	 */
	@Override
	public void setEnvironment(ConfigurableEnvironment environment) {
		this.environment = environment;
	}

	/**
	 * Return the {@code Environment} for this application context in configurable
	 * form, allowing for further customization.
	 * <p>If none specified, a default environment will be initialized via
	 * {@link #createEnvironment()}.
	 */
	@Override
	public ConfigurableEnvironment getEnvironment() {
		if (this.environment == null) {
			this.environment = createEnvironment();
		}
		return this.environment;
	}

	/**
	 * Create and return a new {@link StandardEnvironment}.
	 * <p>Subclasses may override this method in order to supply
	 * a custom {@link ConfigurableEnvironment} implementation.
	 */
	protected ConfigurableEnvironment createEnvironment() {
		return new StandardEnvironment();
	}

	/**
	 * Return this context's internal bean factory as AutowireCapableBeanFactory,
	 * if already available.
	 * @see #getBeanFactory()
	 */
	@Override
	public AutowireCapableBeanFactory getAutowireCapableBeanFactory() throws IllegalStateException {
		return getBeanFactory();
	}

	/**
	 * Return the timestamp (ms) when this context was first loaded.
	 */
	@Override
	public long getStartupDate() {
		return this.startupDate;
	}

	/**
	 * Publish the given event to all listeners.
	 * <p>Note: Listeners get initialized after the MessageSource, to be able
	 * to access it within listener implementations. Thus, MessageSource
	 * implementations cannot publish events.
	 * @param event the event to publish (may be application-specific or a
	 * standard framework event)
	 */
	@Override
	public void publishEvent(ApplicationEvent event) {
		publishEvent(event, null);
	}

	/**
	 * Publish the given event to all listeners.
	 * <p>Note: Listeners get initialized after the MessageSource, to be able
	 * to access it within listener implementations. Thus, MessageSource
	 * implementations cannot publish events.
	 * @param event the event to publish (may be an {@link ApplicationEvent}
	 * or a payload object to be turned into a {@link PayloadApplicationEvent})
	 */
	@Override
	public void publishEvent(Object event) {
		publishEvent(event, null);
	}

	/**
	 * Publish the given event to all listeners.
	 * @param event the event to publish (may be an {@link ApplicationEvent}
	 * or a payload object to be turned into a {@link PayloadApplicationEvent})
	 * @param eventType the resolved event type, if known
	 * @since 4.2
	 */
	protected void publishEvent(Object event, @Nullable ResolvableType eventType) {
		Assert.notNull(event, "Event must not be null");

		// Decorate event as an ApplicationEvent if necessary
		ApplicationEvent applicationEvent;
		if (event instanceof ApplicationEvent) {
			applicationEvent = (ApplicationEvent) event;
		}
		else {
			applicationEvent = new PayloadApplicationEvent<>(this, event);
			if (eventType == null) {
				eventType = ((PayloadApplicationEvent<?>) applicationEvent).getResolvableType();
			}
		}

		// Multicast right now if possible - or lazily once the multicaster is initialized
		if (this.earlyApplicationEvents != null) {
			this.earlyApplicationEvents.add(applicationEvent);
		}
		else {
			getApplicationEventMulticaster().multicastEvent(applicationEvent, eventType);
		}

		// Publish event via parent context as well...
		if (this.parent != null) {
			if (this.parent instanceof AbstractApplicationContext) {
				((AbstractApplicationContext) this.parent).publishEvent(event, eventType);
			}
			else {
				this.parent.publishEvent(event);
			}
		}
	}

	/**
	 * Return the internal ApplicationEventMulticaster used by the context.
	 * @return the internal ApplicationEventMulticaster (never {@code null})
	 * @throws IllegalStateException if the context has not been initialized yet
	 */
	ApplicationEventMulticaster getApplicationEventMulticaster() throws IllegalStateException {
		if (this.applicationEventMulticaster == null) {
			throw new IllegalStateException("ApplicationEventMulticaster not initialized - " +
					"call 'refresh' before multicasting events via the context: " + this);
		}
		return this.applicationEventMulticaster;
	}

	/**
	 * Return the internal LifecycleProcessor used by the context.
	 * @return the internal LifecycleProcessor (never {@code null})
	 * @throws IllegalStateException if the context has not been initialized yet
	 */
	LifecycleProcessor getLifecycleProcessor() throws IllegalStateException {
		if (this.lifecycleProcessor == null) {
			throw new IllegalStateException("LifecycleProcessor not initialized - " +
					"call 'refresh' before invoking lifecycle methods via the context: " + this);
		}
		return this.lifecycleProcessor;
	}

	/**
	 * Return the ResourcePatternResolver to use for resolving location patterns
	 * into Resource instances. Default is a
	 * {@link org.springframework.core.io.support.PathMatchingResourcePatternResolver},
	 * supporting Ant-style location patterns.
	 * <p>Can be overridden in subclasses, for extended resolution strategies,
	 * for example in a web environment.
	 * <p><b>Do not call this when needing to resolve a location pattern.</b>
	 * Call the context's {@code getResources} method instead, which
	 * will delegate to the ResourcePatternResolver.
	 * @return the ResourcePatternResolver for this context
	 * @see #getResources
	 * @see org.springframework.core.io.support.PathMatchingResourcePatternResolver
	 */
	protected ResourcePatternResolver getResourcePatternResolver() {
		return new PathMatchingResourcePatternResolver(this);
	}


	//---------------------------------------------------------------------
	// Implementation of ConfigurableApplicationContext interface
	//---------------------------------------------------------------------

	/**
	 * Set the parent of this application context.
	 * <p>The parent {@linkplain ApplicationContext#getEnvironment() environment} is
	 * {@linkplain ConfigurableEnvironment#merge(ConfigurableEnvironment) merged} with
	 * this (child) application context environment if the parent is non-{@code null} and
	 * its environment is an instance of {@link ConfigurableEnvironment}.
	 * @see ConfigurableEnvironment#merge(ConfigurableEnvironment)
	 */
	@Override
	public void setParent(@Nullable ApplicationContext parent) {
		this.parent = parent;
		if (parent != null) {
			Environment parentEnvironment = parent.getEnvironment();
			if (parentEnvironment instanceof ConfigurableEnvironment) {
				getEnvironment().merge((ConfigurableEnvironment) parentEnvironment);
			}
		}
	}

	@Override
	public void addBeanFactoryPostProcessor(BeanFactoryPostProcessor postProcessor) {
		Assert.notNull(postProcessor, "BeanFactoryPostProcessor must not be null");
		this.beanFactoryPostProcessors.add(postProcessor);
	}

	/**
	 * Return the list of BeanFactoryPostProcessors that will get applied
	 * to the internal BeanFactory.
	 */
	public List<BeanFactoryPostProcessor> getBeanFactoryPostProcessors() {
		// 它不是返回Spring容器里面的Processors，而是你自己的注册的（你自己手动set的），
		// 也就是说我们自己手动调用set方法添加进去，就能够执行。并不需要自己配置@Bean或者在xml里配置
		return this.beanFactoryPostProcessors;
	}

	@Override
	public void addApplicationListener(ApplicationListener<?> listener) {
		Assert.notNull(listener, "ApplicationListener must not be null");
		if (this.applicationEventMulticaster != null) {
			this.applicationEventMulticaster.addApplicationListener(listener);
		}
		this.applicationListeners.add(listener);
	}

	/**
	 * Return the list of statically specified ApplicationListeners.
	 */
	public Collection<ApplicationListener<?>> getApplicationListeners() {
		return this.applicationListeners;
	}

	/**
	 * 1、prepareRefresh()刷新前的预处理;
	 * 	0）、this.closed.set(false)，this.active.set(true)  设置一些标记位
	 * 	1）、initPropertySources()初始化一些属性设置;(交由子类去实现，比如web容器中的 AbstractRefreshableWebApplicationContext 就去初始化了servlet的一些init参数等等)
	 * 	2）、getEnvironment().validateRequiredProperties();检验属性的合法等
	 * 	3）、earlyApplicationEvents= new LinkedHashSet<ApplicationEvent>();初始化容器，保存一些早期的事件；
	 *
	 * 2、obtainFreshBeanFactory();获取BeanFactory；
	 * 	1）、refreshBeanFactory();抽象方法，子类【AbstractRefreshableApplicationContext】唯一实现的：
	 * 			①、若已经存在beanFactory了，那就做一些清理工作（销毁单例Bean、关闭工厂）
	 * 			②、创建了一个this.beanFactory = new DefaultListableBeanFactory();并且设置id
	 * 			③、把旧的工厂的属性赋值给新创建的工厂：customizeBeanFactory(beanFactory)
	 * 			④、loadBeanDefinitions(beanFactory)：加载Bean定义。抽象方法，由子类去决定从哪儿去把Bean定义加载进来，实现有比如：
	 * 					XmlWebApplicationContext：专为web设计的从xml文件里加载Bean定义（借助XmlBeanDefinitionReader）
	 * 					ClassPathXmlApplicationContext/FileSystemXmlApplicationContext：均由父类AbstractXmlApplicationContext去实现这个方法的，也是借助XmlBeanDefinitionReader
	 * 					AnnotationConfigWebApplicationContext：基于注解驱动的容器。（也是当下最流行、最重要的一个实现，前面一篇博文对此有重点分析），借助了AnnotatedBeanDefinitionReader.register()方法加载Bean定义
	 * 						（这里面需要注意的是：.register()只是把当前这一个Class对象registry.registerBeanDefinition()了，至于内部的@Bean、@ComponentScan扫描到的，都不是在此处注册的）
	 *
	 * 					有必要说一句：AnnotationConfigApplicationContext是在非web环境下的容器。它虽然没有实现实现loadBeanDefinitions()抽象方法，是因为它在new对象的时候，已经调用了.register()完成配置Bean定义信息的注册了
	 * 	2）、getBeanFactory();返回刚才GenericApplicationContext创建的BeanFactory对象；
	 * 	3）、将创建的BeanFactory【DefaultListableBeanFactory】返回；
	 * 	=======到这一步截止，BeanFactory已经创建好了（只不过都还是默认配置而已），配置Bean的定义信息也注册好了=======
	 *
	 * 3、prepareBeanFactory(beanFactory);BeanFactory的预准备工作（对BeanFactory进行一些设置）；
	 * 	1）、设置BeanFactory的类加载器、StandardBeanExpressionResolver、ResourceEditorRegistrar
	 * 	2）、添加感知后置处理器BeanPostProcessor【ApplicationContextAwareProcessor】,并设置一些忽略EnvironmentAware、EmbeddedValueResolverAware、xxxxx（因为这个处理器都一把抓了）
	 * 	3）、注册【可以解析的(表示虽然不在容器里，但还是可以直接 @Auwowired)】自动装配；我们能直接在任何组件中自动注入(@Autowired)：
	 * 			BeanFactory、ResourceLoader、ApplicationEventPublisher、ApplicationContext
	 * 	5）、添加BeanPostProcessor【ApplicationListenerDetector】 检测注入进来的Bean是否是监听器
	 * 	6）、Detect a LoadTimeWeaver and prepare for weaving, if found.添加编译时的AspectJ支持：LoadTimeWeaverAwareProcessor
	 * 		(添加的支持的条件是：beanFactory.containsBean("loadTimeWeaver"))
	 * 	7）、给BeanFactory中注册一些能用的组件；
	 * 		environment-->【ConfigurableEnvironment】、
	 * 		systemProperties-->【Map<String, Object>】、
	 * 		systemEnvironment-->【Map<String, Object>】
	 *
	 * 4、postProcessBeanFactory(beanFactory);BeanFactory准备工作完成后进行的后置处理工作；（由子类完成）
	 * 	一般web容器都会对应的实现此方法，比如 AbstractRefreshableWebApplicationContext：
	 * 		1)、添加感知BeanPostProcessor【ServletContextAwareProcessor】，支持到了ServletContextAware、ServletConfigAware
	 * 		2)、注册scopse：beanFactory.registerScope(WebApplicationContext.SCOPE_REQUEST, new RequestScope());当然还有SCOPE_SESSION、SCOPE_APPLICATION
	 * 		3)、向上线一样，注册【可以解析的】自动注入依赖：ServletRequest/ServletResponse/HttpSession/WebRequest
	 * 			(备注：此处放进容器的都是xxxObjectFactory类型，所以这是为何@Autowired没有线程安全问题的重要一步)
	 * 		4)、registerEnvironmentBeans：注册环境相关的Bean（使用的registerSingleton，是直接以单例Bean放到容器里面了）
	 * 			servletContext-->【ServletContext】
	 * 			servletConfig-->【ServletConfig】
	 * 			contextParameters-->【Map<String, String>】 保存有所有的init初始化参数（getInitParameter）
	 * 			contextAttributes-->【Map<String, Object>】 servletContext的所有属性（ServletContext#getAttribute(String)）
	 *
	 * ========以上是BeanFactory的创建及预准备工作，至此准备工作完成了，那么接下来就得利用工厂干点正事了========
	 *
	 * 5、invokeBeanFactoryPostProcessors(beanFactory);执行BeanFactoryPostProcessor的方法；
	 * 	BeanFactoryPostProcessor：BeanFactory的后置处理器。此处调用，现在就表示在BeanFactory标准初始化之后执行的；
	 * 	两个接口：BeanFactoryPostProcessor、BeanDefinitionRegistryPostProcessor（子接口）
	 * 	1）、执行BeanFactoryPostProcessor们的方法；
	 *
	 * 		===先执行BeanDefinitionRegistryPostProcessor===
	 * 		1）、获取所有的BeanDefinitionRegistryPostProcessor；（当然会最先执行我们手动set进去的Processor，但是这个一般都不会有）
	 * 		2）、先执行实现了PriorityOrdered优先级接口的BeanDefinitionRegistryPostProcessor、
	 * 			postProcessor.postProcessBeanDefinitionRegistry(registry)
	 * 		3）、在执行实现了Ordered顺序接口的BeanDefinitionRegistryPostProcessor
	 * 		4）、最后执行没有实现任何优先级或者是顺序接口的BeanDefinitionRegistryPostProcessors
	 * 		（小细节：都会调用getBean(“name”,BeanDefinitionRegistryPostProcessor.class)方法，所以都会先实例化，才去执行的）
	 *
	 * 		**这里面需要特别的介绍一个处理器：`ConfigurationClassPostProcessor`，它是一个BeanDefinitionRegistryPostProcessor**
	 * 		**它会解析完成所有的@Configuration配置类，然后所有@Bean、@ComponentScan等等Bean定义都会搜集进来了，所以这一步是非常的重要的**
	 *
	 * 		===再执行BeanFactoryPostProcessor的方法（顺序逻辑同上，略）===
	 * 	2)、再次检测一次添加对AspectJ的支持。为何还要检测呢？through an @Bean method registered by ConfigurationClassPostProcessor，这样我们注入了一个切面Bean，就符合条件了嘛
	 *
	 * 6、registerBeanPostProcessors(beanFactory);注册BeanPostProcessor（Bean的后置处理器）【 intercept bean creation】
	 * 		**不同接口类型的BeanPostProcessor；在Bean创建前后的执行时机是不一样的**
	 * 		BeanPostProcessor：BeanPostProcessor是一个工厂钩子，允许Spring框架在新创建Bean实例时对其进行定制化修改，比如填充Bean、创建代理、解析Bean内部的注解等等。。。
	 * 		DestructionAwareBeanPostProcessor：Bean销毁时候
	 * 		InstantiationAwareBeanPostProcessor：Bean初始化的时候
	 * 		SmartInstantiationAwareBeanPostProcessor：初始化增强版本：增加了一个对Bean类型预测的回调（一般是Spring内部使用，调用者还是使用InstantiationAwareBeanPostProcessor就好）
	 * 		MergedBeanDefinitionPostProcessor：合并处理Bean定义的时候的回调【该类型的处理器保存在名为internalPostProcessors的List中】、
	 *
	 * 		1）、获取所有的 BeanPostProcessor;后置处理器都默认可以通过PriorityOrdered、Ordered接口来执行优先级
	 * 		2）、先注册PriorityOrdered优先级接口的BeanPostProcessor；
	 * 			把每一个BeanPostProcessor；添加到BeanFactory中
	 * 			beanFactory.addBeanPostProcessor(postProcessor);
	 * 		3）、再注册Ordered接口的、最后注册没有实现任何优先级接口的、最终注册MergedBeanDefinitionPostProcessor
	 * 			(此处细节：BeanPostProcessor本身也是一个Bean，其注册之前一定先实例化，而且是分批实例化和注册。
	 * 			另外还有一个非常非常重要的一点就是阶段顺序问题：
	 * 			我们可以把BeanPostProcessor的实例化与注册分为四个阶段：
	 * 					第一阶段applicationContext内置阶段、
	 * 					第二阶段priorityOrdered阶段、
	 * 					第三阶段Ordered阶段、
	 * 					第四阶段nonOrdered阶段
	 * 			因为是分批注册，所以我们同阶段是不能拦截到同阶段的BeanPostProcessor的实例化的。举例子：
	 * 			PriorityOrdered的只能被内置阶段的比如：ApplicationContextAwareProcessor(可以注入啦)/ApplicationListenerDetector（可以接受事件啦）这种拦截
	 * 			而：Ordered就可以被	内置的、PriorityOrdered都拦截到了
	 * 			。。。 以此类推。。。
	 *
	 * 			所以我们的BeanPostProcessor是可以@Autowired 比如Service、Dao来做一些事的。单思，但是一定要【注意避免BeanPostProcessor启动时的“误伤”陷阱】，什么意思？大概解释一下如下：
	 * 				可能由于你的Processor依赖于某个@Bean，从而让它提前实例化了，然后就很可能错过了后面一些BeanPostProcessor的处理，造成“误伤”
	 * 				（SpringBoot中使用Shiro、Spring-Cache的时候，使用不当会出现这样的问题）
	 *
	 * 		6）、这一步非常有意思：moving it to the end of the processor chain。它的又注册了一次，作用是把这个探测器移动到处理器的底部，最后一个（显然，最后一个是为了不要放过任何Bean）
	 * 			（小细节：可能有小伙伴疑问，这里也是new出来，这这样容器内不就有两个探测器对象了吗？气其实不然，ApplicationListenerDetector它重写了hashCode方法，且只和应用applicationContext有关）
	 * 			return ObjectUtils.nullSafeHashCode(this.applicationContext);所以对它执行remove的时候，会被当作同一个对象处理，能把老的移除成功添加新的的
	 *
	 * 7、initMessageSource();初始化MessageSource组件（做国际化功能；消息绑定，消息解析）
	 * 		1）、看容器中是否有id为messageSource的，类型是MessageSource的组件
	 * 			如果有赋值给messageSource，如果没有自己创建一个DelegatingMessageSource；
	 * 				MessageSource：取出国际化配置文件中的某个key的值；能按照区域信息获取；
	 * 		2）、把创建好的MessageSource注册在容器中，以后获取国际化配置文件的值的时候，可以自动注入MessageSource
	 * 		beanFactory.registerSingleton(MESSAGE_SOURCE_BEAN_NAME, this.messageSource)
	 *
	 * 8、initApplicationEventMulticaster();初始化事件派发器；
	 * 		1）、从BeanFactory中获取applicationEventMulticaster的ApplicationEventMulticaster；
	 * 		2）、如果上一步没有配置；创建一个SimpleApplicationEventMulticaster,将创建的ApplicationEventMulticaster添加到BeanFactory中
	 *
	 * 9、onRefresh();留给子容器（子类） 容器刷新的时候做些事
	 * 		AbstractRefreshableWebApplicationContext：this.themeSource = UiApplicationContextUtils.initThemeSource(this);
	 *
	 * 10、registerListeners();把容器中将所有项目里面的ApplicationListener注册进来；
	 * 		1、拿到容器里所有的Bean定义的名字，类型为ApplicationListener，然后添加进来
	 * 			getApplicationEventMulticaster().addApplicationListener(listener);
	 * 		2、派发之前步骤产生的事件（早期事件）
	 * 		（细节：此处只是把Bean的名字放进去，Bean还没有实例化哦~~~~）
	 *
	 * 11、finishBeanFactoryInitialization(beanFactory);初始化所有剩下的单实例bean；这应该是最核心的一步了
	 * 	1)、为容器初始化ConversionService(容器若没有就不用初始化了,依然采用getBean()初始化的) 提供转换服务
	 * 	2)、若没有设置值解析器，那就注册一个默认的值解析器（lambda表示的匿名处理）
	 * 	3)、实例化LoadTimeWeaverAware(若存在)
	 * 	4)、清空临时类加载器：beanFactory.setTempClassLoader(null)
	 * 	5)、缓存（快照）下当前所有的Bean定义信息 beanFactory.freezeConfiguration();
	 * 	==== 更精确的是说是根据Bean的定义信息：beanDefinitionNames来实例化、初始化剩余的Bean ====
	 * 	6)、beanFactory.preInstantiateSingletons();初始化后剩下的单实例bean(过程这里就不详说了)
	 *
	 * 12、finishRefresh();完成BeanFactory的初始化创建工作；IOC容器就创建完成
	 * 		0)、clearResourceCaches(); (Spring5.0才有)
	 * 		1）、initLifecycleProcessor();初始化和生命周期有关的后置处理器；从容器中找是否有lifecycleProcessor的组件【LifecycleProcessor】；如果没有new DefaultLifecycleProcessor();
	 * 			关于Lifecycle接口的使用，也专门讲解过，这里不聊了
	 * 		2）、getLifecycleProcessor().onRefresh();  相当于上面刚注册，下面就调用了
	 * 		3）、publishEvent(new ContextRefreshedEvent(this));发布容器刷新完成事件；
	 * 		4）、liveBeansView.registerApplicationContext(this); 和MBean相关，略
	 *
	 * 	======总结===========
	 * 	1）、Spring容器在启动的时候，先会保存所有注册进来的Bean的定义信息（可以有N种方式）；
	 * 		1）、xml注册bean；<bean>
	 * 		2）、注解注册Bean；@Service、@Component、@Bean、xxx
	 * 	2）、Spring容器会合适的时机创建这些Bean
	 * 		1）、用到这个bean的时候；利用getBean创建bean；创建好以后保存在容器中；
	 * 		2）、统一创建剩下所有的bean的时候；finishBeanFactoryInitialization()；
	 * 	3）、后置处理器；BeanPostProcessor
	 * 		1）、每一个bean创建完成，都会使用各种后置处理器进行处理；来增强bean的功能；
	 * 			AutowiredAnnotationBeanPostProcessor:处理自动注入
	 * 			AnnotationAwareAspectJAutoProxyCreator:来做AOP功能；
	 * 			xxx....
	 * 			增强的功能注解：
	 * 			AsyncAnnotationBeanPostProcessor
	 * 			....
	 * 	4）、事件驱动模型；
	 * 		ApplicationListener；事件监听；
	 * 		ApplicationEventMulticaster；事件派发：
	 */
	@Override
	public void refresh() throws BeansException, IllegalStateException {
		synchronized (this.startupShutdownMonitor) {
			// Prepare this context for refreshing.
			// 1、容器刷新前的准备，设置上下文状态，获取属性，验证必要的属性等
			prepareRefresh();

			// Tell the subclass to refresh the internal bean factory.
			// 2、获取新的beanFactory、销毁原有beanFactory
			// 为每个bean生成BeanDefinition等  注意，此处是获取新的，销毁旧的，这就是刷新的意义
			ConfigurableListableBeanFactory beanFactory = obtainFreshBeanFactory();

			// Prepare the bean factory for use in this context.
			// 3、配置标准的beanFactory，设置ClassLoader，设置SpEL表达式解析器等
			prepareBeanFactory(beanFactory);

			try {
				// Allows post-processing of the bean factory in context subclasses.
				// 4、模板方法，允许在子类中对beanFactory进行后置处理。如web环境针对此做特殊处理
				postProcessBeanFactory(beanFactory);

				// Invoke factory processors registered as beans in the context.
				// 5、实例化并调用所有注册的beanFactory后置处理器（实现接口BeanFactoryPostProcessor的bean）。
				// 在beanFactory标准初始化之后执行  例如：PropertyPlaceholderConfigurer(处理占位符)
				// bean定义的注册就是这里处理的
				invokeBeanFactoryPostProcessors(beanFactory);

				// Register bean processors that intercept bean creation.
				// 6、实例化和注册beanFactory中扩展了BeanPostProcessor的bean。
				// 例如：
				// AutowiredAnnotationBeanPostProcessor(处理被@Autowired注解修饰的bean并注入)
				// RequiredAnnotationBeanPostProcessor(处理被@Required注解修饰的方法)
				// CommonAnnotationBeanPostProcessor(处理@PreDestroy、@PostConstruct、@Resource等多个注解的作用)等。
				registerBeanPostProcessors(beanFactory);

				// Initialize message source for this context.
				// 7、初始化i18n国际化工具类MessageSource
				// 初始化消息源。向容器里注册一个一个事件源的单例Bean：MessageSource
				// JDK的java.util包中提供了几个支持本地化的格式化操作工具了：NumberFormat、DateFormat、MessageFormat，
				// 而在Spring中的国际化资源操作也无非是对于这些类的封装操作、具体如何创建MessageSource这个bean、及初始化文件，这些倒是没有深入了解
				/**
				 * 1、定义资源文件
				 * messages.properties(默认：英文），内容如下：
				 * test=test
				 * messages_zh_CN.properties（简体中文），内容如下：
				 * test=测试
				 *
				 * 2、定义bean
				 * <bean id="messageSource", class="org.springframework.context.support.ResourceBundleMessageSource">
				 *    <property name="basenames">
				 *        <list>
				 *            <value>test/messages</value>
				 *        </list>
				 *    </property>
				 * </bean>
				 */
				initMessageSource();

				// Initialize event multicaster for this context.
				// 8、初始化事件多播器
				initApplicationEventMulticaster();

				// Initialize other special beans in specific context subclasses.
				// 9、模板方法，在容器刷新的时候可以自定义逻辑（子类自己去实现逻辑），不同的Spring容器做不同的事情
				// 类似于第四步的postProcessBeanFactory，它也是个模版方法。本环境中的实现为：AbstractRefreshableWebApplicationContext#onRefresh方法：
				onRefresh();

				// Check for listener beans and register them.
				// 10、注册监听器，并且广播early application events,也就是早期的事件
				registerListeners();

				// Instantiate all remaining (non-lazy-init) singletons.
				// 11、非常重要。。。实例化所有剩余的（非懒加载）单例Bean。（也就是我们自己定义的那些Bean们）
				// 比如invokeBeanFactoryPostProcessors方法中根据各种注解解析出来的类，在这个时候都会被初始化  扫描的 @Bean之类的
				// 实例化的过程各种BeanPostProcessor开始执行、起作用~~~~~~~~~~~~~~
				finishBeanFactoryInitialization(beanFactory);

				// Last step: publish corresponding event.
				// 12、refresh做完之后需要做的其他事情
				// 清除上下文资源缓存（如扫描中的ASM元数据）
				// 初始化上下文的生命周期处理器，并刷新（找出Spring容器中实现了Lifecycle接口的bean并执行start()方法）。
				// 发布ContextRefreshedEvent事件告知对应的ApplicationListener进行响应的操作
				finishRefresh();
			}

			catch (BeansException ex) {
				if (logger.isWarnEnabled()) {
					logger.warn("Exception encountered during context initialization - " +
							"cancelling refresh attempt: " + ex);
				}

				// Destroy already created singletons to avoid dangling resources.
				// 如果刷新失败那么就会将已经创建好的单例Bean销毁掉
				destroyBeans();

				// Reset 'active' flag.
				// 重置context的活动状态 告知是失败的
				cancelRefresh(ex);

				// Propagate exception to caller.
				throw ex;
			}

			finally {
				// Reset common introspection caches in Spring's core, since we
				// might not ever need metadata for singleton beans anymore...
				// 失败与否，都会重置Spring内核的缓存。因为可能不再需要metadata给单例Bean了。
				resetCommonCaches();
			}
		}
	}

	/**
	 * Prepare this context for refreshing, setting its startup date and
	 * active flag as well as performing any initialization of property sources.
	 */
	protected void prepareRefresh() {
		// Switch to active.
		//记录容器启动时间，然后设立对应的标志位
		this.startupDate = System.currentTimeMillis();
		this.closed.set(false);
		this.active.set(true);

		if (logger.isDebugEnabled()) {
			if (logger.isTraceEnabled()) {
				logger.trace("Refreshing " + this);
			}
			else {
				logger.debug("Refreshing " + getDisplayName());
			}
		}

		// Initialize any placeholder property sources in the context environment.
		// 这是扩展方法，由子类去实现，可以在验证之前为系统属性设置一些值可以在子类中实现此方法
		// 因为我们这边是AnnotationConfigApplicationContext，可以看到不管父类还是自己，都什么都没做，所以此处先忽略
		initPropertySources();

		// Validate that all properties marked as required are resolvable:
		// see ConfigurablePropertyResolver#setRequiredProperties
		//这里有两步，getEnvironment()，然后是是验证是否系统环境中有RequiredProperties参数值 如下详情
		// 然后管理Environment#validateRequiredProperties 后面在讲到环境的时候再专门讲解吧
		// 这里其实就干了一件事，验证是否存在需要的属性
		getEnvironment().validateRequiredProperties();

		// Store pre-refresh ApplicationListeners...
		// 初始化容器，用于装载早期的一些事件
		if (this.earlyApplicationListeners == null) {
			this.earlyApplicationListeners = new LinkedHashSet<>(this.applicationListeners);
		}
		else {
			// Reset local application listeners to pre-refresh state.
			this.applicationListeners.clear();
			this.applicationListeners.addAll(this.earlyApplicationListeners);
		}

		// Allow for the collection of early ApplicationEvents,
		// to be published once the multicaster is available...
		this.earlyApplicationEvents = new LinkedHashSet<>();
	}

	/**
	 * <p>Replace any stub property sources with actual instances.
	 * @see org.springframework.core.env.PropertySource.StubPropertySource
	 * @see org.springframework.web.context.support.WebApplicationContextUtils#initServletPropertySources
	 */
	protected void initPropertySources() {
		// For subclasses: do nothing by default.
	}

	/**
	 * Tell the subclass to refresh the internal bean factory.
	 * @return the fresh BeanFactory instance
	 * @see #refreshBeanFactory()
	 * @see #getBeanFactory()
	 */
	protected ConfigurableListableBeanFactory obtainFreshBeanFactory() {
		// 销毁原工厂,创建一个bean工厂
		// 刷新BeanFactory
		refreshBeanFactory();
		return getBeanFactory();
	}

	/**
	 * Configure the factory's standard context characteristics,
	 * such as the context's ClassLoader and post-processors.
	 * @param beanFactory the BeanFactory to configure
	 *
	 * 1、增加对SpEL语言的支持
	 * 2、增加对属性编辑器的支持
	 * 3、增加对一些内置类，比如EnvironmentAware、MessageSourceAware的信息注入
	 * 4、设置了依赖功能可忽略的接口
	 * 5、注册一些固定依赖的属性
	 * 6、增加AspectJ的支持
	 * 7、蒋相关环境变量及属性注册以单例模式注册
	 */
	protected void prepareBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		// Tell the internal bean factory to use the context's class loader etc.
		// 设置beanFactory的classLoader为当前context的classLoader
		beanFactory.setBeanClassLoader(getClassLoader());
		// 设置EL表达式解析器（Bean初始化完成后填充属性时会用到）
		// spring3增加了表达式语言的支持，默认可以使用#{bean.xxx}的形式来调用相关属性值，对@Value依赖注入的时候，就可以使用到了。
		beanFactory.setBeanExpressionResolver(new StandardBeanExpressionResolver(beanFactory.getBeanClassLoader()));
		// 设置属性注册解析器PropertyEditor 这个主要是对bean的属性等设置管理的一个工具
		// 1、可以自定义属性编辑器，继承PropertyEditorSupport并注册成bean
		// 2、注册Spring自带的属性编辑器，实现PropertyEditorRegistrar，自己进行注册属性编辑器
		beanFactory.addPropertyEditorRegistrar(new ResourceEditorRegistrar(this, getEnvironment()));

		// Configure the bean factory with context callbacks.
		// 将当前的ApplicationContext对象交给ApplicationContextAwareProcessor类来处理，从而在Aware接口实现类中的注入applicationContext等等
		// 添加了一个处理aware相关接口的beanPostProcessor扩展，主要是使用beanPostProcessor的postProcessBeforeInitialization()前置处理方法实现aware相关接口的功能
		// 类似的还有ResourceLoaderAware、ServletContextAware等等等等
		beanFactory.addBeanPostProcessor(new ApplicationContextAwareProcessor(this));
		// 下面是忽略的自动装配（也就是实现了这些接口的Bean，不要Autowired自动装配了）
		// 默认只有BeanFactoryAware被忽略,所以其它的需要自行设置
		// 因为ApplicationContextAwareProcessor把这5个接口的实现工作做了（具体你可参见源码） 所以这里就直接忽略掉
		beanFactory.ignoreDependencyInterface(EnvironmentAware.class);
		beanFactory.ignoreDependencyInterface(EmbeddedValueResolverAware.class);
		beanFactory.ignoreDependencyInterface(ResourceLoaderAware.class);
		beanFactory.ignoreDependencyInterface(ApplicationEventPublisherAware.class);
		beanFactory.ignoreDependencyInterface(MessageSourceAware.class);
		beanFactory.ignoreDependencyInterface(ApplicationContextAware.class);

		// BeanFactory interface not registered as resolvable type in a plain factory.
		// MessageSource registered (and found for autowiring) as a bean.
		// 设置几个"自动装配"规则======如下：
		// 如果是BeanFactory的类，就注册beanFactory
		//  如果是ResourceLoader、ApplicationEventPublisher、ApplicationContext等等就注入当前对象this(applicationContext对象)

		// 此处registerResolvableDependency()方法注意：它会把他们加入到DefaultListableBeanFactory的resolvableDependencies字段里面缓存这，供后面处理依赖注入的时候使用 DefaultListableBeanFactory#resolveDependency处理依赖关系
		// 这也是为什么我们可以通过依赖注入的方式，直接注入这几个对象比如ApplicationContext可以直接依赖注入
		// 但是需要注意的是：这些Bean，Spring的IOC容器里其实是没有的。beanFactory.getBeanDefinitionNames()和beanFactory.getSingletonNames()都是找不到他们的，所以特别需要理解这一点
		// 至于容器中没有，但是我们还是可以@Autowired直接注入的有哪些，请看下图：
		beanFactory.registerResolvableDependency(BeanFactory.class, beanFactory);
		beanFactory.registerResolvableDependency(ResourceLoader.class, this);
		beanFactory.registerResolvableDependency(ApplicationEventPublisher.class, this);
		beanFactory.registerResolvableDependency(ApplicationContext.class, this);

		// Register early post-processor for detecting inner beans as ApplicationListeners.
		// 注册这个Bean的后置处理器：在Bean初始化后检查是否实现了ApplicationListener接口
		// 是则加入当前的applicationContext的applicationListeners列表 这样后面广播事件也就方便了
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(this));

		// Detect a LoadTimeWeaver and prepare for weaving, if found.
		// 检查容器中是否包含名称为loadTimeWeaver的bean，实际上是增加Aspectj的支持
		// AspectJ采用编译期织入、类加载期织入两种方式进行切面的织入
		// 类加载期织入简称为LTW（Load Time Weaving）,通过特殊的类加载器来代理JVM默认的类加载器实现
		if (beanFactory.containsBean(LOAD_TIME_WEAVER_BEAN_NAME)) {
			// 添加BEAN后置处理器：LoadTimeWeaverAwareProcessor
			// 在BEAN初始化之前检查BEAN是否实现了LoadTimeWeaverAware接口，
			// 如果是，则进行加载时织入，即静态代理。
			beanFactory.addBeanPostProcessor(new LoadTimeWeaverAwareProcessor(beanFactory));
			// Set a temporary ClassLoader for type matching.
			beanFactory.setTempClassLoader(new ContextTypeMatchClassLoader(beanFactory.getBeanClassLoader()));
		}

		// Register default environment beans.
		// 注入一些其它信息的bean，比如environment、systemProperties、SystemEnvironment等
		if (!beanFactory.containsLocalBean(ENVIRONMENT_BEAN_NAME)) {
			beanFactory.registerSingleton(ENVIRONMENT_BEAN_NAME, getEnvironment());
		}
		if (!beanFactory.containsLocalBean(SYSTEM_PROPERTIES_BEAN_NAME)) {
			beanFactory.registerSingleton(SYSTEM_PROPERTIES_BEAN_NAME, getEnvironment().getSystemProperties());
		}
		if (!beanFactory.containsLocalBean(SYSTEM_ENVIRONMENT_BEAN_NAME)) {
			beanFactory.registerSingleton(SYSTEM_ENVIRONMENT_BEAN_NAME, getEnvironment().getSystemEnvironment());
		}
	}

	/**
	 * Modify the application context's internal bean factory after its standard
	 * initialization. All bean definitions will have been loaded, but no beans
	 * will have been instantiated yet. This allows for registering special
	 * BeanPostProcessors etc in certain ApplicationContext implementations.
	 * @param beanFactory the bean factory used by the application context
	 */
	protected void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
	}

	/**
	 * Instantiate and invoke all registered BeanFactoryPostProcessor beans,
	 * respecting explicit order if given.
	 * <p>Must be called before singleton instantiation.
	 */
	protected void invokeBeanFactoryPostProcessors(ConfigurableListableBeanFactory beanFactory) {
		// 执行BeanFactoryPostProcessor
		PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors(beanFactory, getBeanFactoryPostProcessors());

		// Detect a LoadTimeWeaver and prepare for weaving, if found in the meantime
		// (e.g. through an @Bean method registered by ConfigurationClassPostProcessor)
		// 这里就是定制：如果loadTimeWeaver这个Bean存在，那么就会配置上运行时织入的处理器LoadTimeWeaverAwareProcessor
		if (beanFactory.getTempClassLoader() == null && beanFactory.containsBean(LOAD_TIME_WEAVER_BEAN_NAME)) {
			beanFactory.addBeanPostProcessor(new LoadTimeWeaverAwareProcessor(beanFactory));
			beanFactory.setTempClassLoader(new ContextTypeMatchClassLoader(beanFactory.getBeanClassLoader()));
		}
	}

	/**
	 * Instantiate and register all BeanPostProcessor beans,
	 * respecting explicit order if given.
	 * <p>Must be called before any instantiation of application beans.
	 */
	protected void registerBeanPostProcessors(ConfigurableListableBeanFactory beanFactory) {
		// 发现它又是委托给PostProcessorRegistrationDelegate 去做的
		PostProcessorRegistrationDelegate.registerBeanPostProcessors(beanFactory, this);
	}

	/**
	 * Initialize the MessageSource.
	 * Use parent's if none defined in this context.
	 */
	protected void initMessageSource() {
		ConfigurableListableBeanFactory beanFactory = getBeanFactory();
		// 判断是否已经存在名为“messageSource”的Bean、或者bean定义
		if (beanFactory.containsLocalBean(MESSAGE_SOURCE_BEAN_NAME)) {
			// 从容器里拿出这个messageSource、或进行初始化Bean
			this.messageSource = beanFactory.getBean(MESSAGE_SOURCE_BEAN_NAME, MessageSource.class);
			// Make MessageSource aware of parent MessageSource.
			// 设置父属性。。。。。。。。。。。。。
			if (this.parent != null && this.messageSource instanceof HierarchicalMessageSource) {
				HierarchicalMessageSource hms = (HierarchicalMessageSource) this.messageSource;
				if (hms.getParentMessageSource() == null) {
					// Only set parent context as parent MessageSource if no parent MessageSource
					// registered already.
					hms.setParentMessageSource(getInternalParentMessageSource());
				}
			}
			if (logger.isTraceEnabled()) {
				logger.trace("Using MessageSource [" + this.messageSource + "]");
			}
		}
		else {
			// Use empty MessageSource to be able to accept getMessage calls.
			DelegatingMessageSource dms = new DelegatingMessageSource();
			// 其实就是获取到父容器的messageSource字段（否则就是getParent()上下文自己）
			dms.setParentMessageSource(getInternalParentMessageSource());
			// 给当前的messageSource赋值
			this.messageSource = dms;
			// 把messageSource作为一个单例的Bean注册进beanFactory工厂里面
			beanFactory.registerSingleton(MESSAGE_SOURCE_BEAN_NAME, this.messageSource);
			if (logger.isTraceEnabled()) {
				logger.trace("No '" + MESSAGE_SOURCE_BEAN_NAME + "' bean, using [" + this.messageSource + "]");
			}
		}
	}

	/**
	 * Initialize the ApplicationEventMulticaster.
	 * Uses SimpleApplicationEventMulticaster if none defined in the context.
	 * @see org.springframework.context.event.SimpleApplicationEventMulticaster
	 * 若用户自己定义了这个Bean（备注：Bean名称必须是"applicationEventMulticaster"哦），就以用户的为准。
	 * 否则注册一个系统默认的SimpleApplicationEventMulticaster
	 */
	protected void initApplicationEventMulticaster() {
		ConfigurableListableBeanFactory beanFactory = getBeanFactory();
		if (beanFactory.containsLocalBean(APPLICATION_EVENT_MULTICASTER_BEAN_NAME)) {
			this.applicationEventMulticaster =
					beanFactory.getBean(APPLICATION_EVENT_MULTICASTER_BEAN_NAME, ApplicationEventMulticaster.class);
			if (logger.isTraceEnabled()) {
				logger.trace("Using ApplicationEventMulticaster [" + this.applicationEventMulticaster + "]");
			}
		}
		else {
			this.applicationEventMulticaster = new SimpleApplicationEventMulticaster(beanFactory);
			beanFactory.registerSingleton(APPLICATION_EVENT_MULTICASTER_BEAN_NAME, this.applicationEventMulticaster);
			if (logger.isTraceEnabled()) {
				logger.trace("No '" + APPLICATION_EVENT_MULTICASTER_BEAN_NAME + "' bean, using " +
						"[" + this.applicationEventMulticaster.getClass().getSimpleName() + "]");
			}
		}
	}

	/**
	 * Initialize the LifecycleProcessor.
	 * Uses DefaultLifecycleProcessor if none defined in the context.
	 * @see org.springframework.context.support.DefaultLifecycleProcessor
	 *
	 * 初始化生命周期处理器，并设置到Spring容器中(LifecycleProcessor) 调用生命周期处理器的onRefresh方法，
	 * 这个方法会找出Spring容器中实现了SmartLifecycle接口的类并进行start方法的调用 发布ContextRefreshedEvent事件告知对应的ApplicationListener进行响应的操作
	 *
	 * 调用LiveBeansView的registerApplicationContext方法：如果设置了JMX相关的属性，则就调用该方法
	 * 发布EmbeddedServletContainerInitializedEvent事件告知对应的ApplicationListener进行响应的操作
	 */
	protected void initLifecycleProcessor() {
		ConfigurableListableBeanFactory beanFactory = getBeanFactory();
		// 如果工厂里已经存在LifecycleProcessor，那就拿出来，把值放上去this.lifecycleProcessor
		if (beanFactory.containsLocalBean(LIFECYCLE_PROCESSOR_BEAN_NAME)) {
			this.lifecycleProcessor =
					beanFactory.getBean(LIFECYCLE_PROCESSOR_BEAN_NAME, LifecycleProcessor.class);
			if (logger.isTraceEnabled()) {
				logger.trace("Using LifecycleProcessor [" + this.lifecycleProcessor + "]");
			}
		}
		else {
			// 一般情况下，都会注册上这个默认的处理器DefaultLifecycleProcessor
			DefaultLifecycleProcessor defaultProcessor = new DefaultLifecycleProcessor();
			defaultProcessor.setBeanFactory(beanFactory);
			this.lifecycleProcessor = defaultProcessor;
			// 直接注册成单例Bean进去容器里
			beanFactory.registerSingleton(LIFECYCLE_PROCESSOR_BEAN_NAME, this.lifecycleProcessor);
			if (logger.isTraceEnabled()) {
				logger.trace("No '" + LIFECYCLE_PROCESSOR_BEAN_NAME + "' bean, using " +
						"[" + this.lifecycleProcessor.getClass().getSimpleName() + "]");
			}
		}
	}

	/**
	 * Template method which can be overridden to add context-specific refresh work.
	 * Called on initialization of special beans, before instantiation of singletons.
	 * <p>This implementation is empty.
	 * @throws BeansException in case of errors
	 * @see #refresh()
	 */
	protected void onRefresh() throws BeansException {
		// For subclasses: do nothing by default.
	}

	/**
	 * Add beans that implement ApplicationListener as listeners.
	 * Doesn't affect other listeners, which can be added without being beans.
	 */
	protected void registerListeners() {
		// Register statically specified listeners first.
		// 这一步和手动注册BeanDefinitionRegistryPostProcessor一样，可以自己通过set手动注册监听器  然后是最新执行的（显然此处我们无自己set）
		for (ApplicationListener<?> listener : getApplicationListeners()) {
			// 把手动注册的监听器绑定到广播器
			getApplicationEventMulticaster().addApplicationListener(listener);
		}

		// Do not initialize FactoryBeans here: We need to leave all regular beans
		// uninitialized to let post-processors apply to them!
		// 取到容器里面的所有的监听器的名称，绑定到广播器  后面会广播出去这些事件的
		// 同时提醒大伙注意：此处并没有说到ApplicationListenerDetector这个东东，下文会分解
		String[] listenerBeanNames = getBeanNamesForType(ApplicationListener.class, true, false);
		for (String listenerBeanName : listenerBeanNames) {
			getApplicationEventMulticaster().addApplicationListenerBean(listenerBeanName);
		}

		// Publish early application events now that we finally have a multicaster...
		// 这一步需要注意了：如果存在早期应用事件，这里就直接发布了(同时就把earlyApplicationEvents该字段置为null)
		Set<ApplicationEvent> earlyEventsToProcess = this.earlyApplicationEvents;
		this.earlyApplicationEvents = null;
		if (!CollectionUtils.isEmpty(earlyEventsToProcess)) {
			for (ApplicationEvent earlyEvent : earlyEventsToProcess) {
				getApplicationEventMulticaster().multicastEvent(earlyEvent);
			}
		}
	}

	/**
	 * Finish the initialization of this context's bean factory,
	 * initializing all remaining singleton beans.
	 */
	protected void finishBeanFactoryInitialization(ConfigurableListableBeanFactory beanFactory) {
		// Initialize conversion service for this context.
		// 初始化上下文的转换服务，ConversionService是一个类型转换接口
		if (beanFactory.containsBean(CONVERSION_SERVICE_BEAN_NAME) &&
				beanFactory.isTypeMatch(CONVERSION_SERVICE_BEAN_NAME, ConversionService.class)) {
			beanFactory.setConversionService(
					beanFactory.getBean(CONVERSION_SERVICE_BEAN_NAME, ConversionService.class));
		}

		// Register a default embedded value resolver if no BeanFactoryPostProcessor
		// (such as a PropertySourcesPlaceholderConfigurer bean) registered any before:
		// at this point, primarily for resolution in annotation attribute values.
		// 设置一个内置的值处理器（若没有的话），该处理器作用有点像一个PropertyPlaceholderConfigurer bean
		if (!beanFactory.hasEmbeddedValueResolver()) {
			beanFactory.addEmbeddedValueResolver(strVal -> getEnvironment().resolvePlaceholders(strVal));
		}

		// Initialize LoadTimeWeaverAware beans early to allow for registering their transformers early.
		// 注意此处已经调用了getBean方法，初始化LoadTimeWeaverAware Bean
		// getBean()方法的详细，下面会详细分解
		// LoadTimeWeaverAware是类加载时织入的意思
		String[] weaverAwareNames = beanFactory.getBeanNamesForType(LoadTimeWeaverAware.class, false, false);
		for (String weaverAwareName : weaverAwareNames) {
			getBean(weaverAwareName);
		}

		// Stop using the temporary ClassLoader for type matching.
		// 停止使用临时的类加载器
		beanFactory.setTempClassLoader(null);

		// Allow for caching all bean definition metadata, not expecting further changes.
		// 缓存（冻结）所有的bean definition的名称数据，不期望以后会改变，因此bean定义的添加修改，最好在BeanDefinitionRegistryPostProcessor中完成
		beanFactory.freezeConfiguration();

		// Instantiate all remaining (non-lazy-init) singletons.
		// 这个就是最重要的方法：会把留下来的Bean们  不是lazy懒加载的bean都实例化掉
		//  bean真正实例化的时刻到了
		beanFactory.preInstantiateSingletons();
	}

	/**
	 * Finish the refresh of this context, invoking the LifecycleProcessor's
	 * onRefresh() method and publishing the
	 * {@link org.springframework.context.event.ContextRefreshedEvent}.
	 */
	protected void finishRefresh() {
		// Clear context-level resource caches (such as ASM metadata from scanning).
		// 这个是Spring5.0之后才有的方法
		// 表示清除一些resourceCaches,如doc说的  清楚context级别的资源缓存，比如ASM的元数据
		clearResourceCaches();

		// Initialize lifecycle processor for this context.
		// 初始化所有的LifecycleProcessor
		initLifecycleProcessor();

		// Propagate refresh to lifecycle processor first.
		// 上面注册好的处理器，这里就拿出来，调用它的onRefresh方法了
		getLifecycleProcessor().onRefresh();

		// Publish the final event.
		// 发布容器刷新的事件：
		publishEvent(new ContextRefreshedEvent(this));

		// Participate in LiveBeansView MBean, if active.
		// 和MBeanServer和MBean有关的。相当于把当前容器上下文，注册到MBeanServer里面去。
		// 这样子，MBeanServer持久了容器的引用，就可以拿到容器的所有内容了，也就让Spring支持到了MBean的相关功能
		LiveBeansView.registerApplicationContext(this);
	}

	/**
	 * Cancel this context's refresh attempt, resetting the {@code active} flag
	 * after an exception got thrown.
	 * @param ex the exception that led to the cancellation
	 */
	protected void cancelRefresh(BeansException ex) {
		this.active.set(false);
	}

	/**
	 * Reset Spring's common reflection metadata caches, in particular the
	 * {@link ReflectionUtils}, {@link AnnotationUtils}, {@link ResolvableType}
	 * and {@link CachedIntrospectionResults} caches.
	 * @since 4.2
	 * @see ReflectionUtils#clearCache()
	 * @see AnnotationUtils#clearCache()
	 * @see ResolvableType#clearCache()
	 * @see CachedIntrospectionResults#clearClassLoader(ClassLoader)
	 */
	protected void resetCommonCaches() {
		ReflectionUtils.clearCache();
		AnnotationUtils.clearCache();
		ResolvableType.clearCache();
		CachedIntrospectionResults.clearClassLoader(getClassLoader());
	}


	/**
	 * Register a shutdown hook {@linkplain Thread#getName() named}
	 * {@code SpringContextShutdownHook} with the JVM runtime, closing this
	 * context on JVM shutdown unless it has already been closed at that time.
	 * <p>Delegates to {@code doClose()} for the actual closing procedure.
	 * @see Runtime#addShutdownHook
	 * @see ConfigurableApplicationContext#SHUTDOWN_HOOK_THREAD_NAME
	 * @see #close()
	 * @see #doClose()
	 */
	@Override
	public void registerShutdownHook() {
		if (this.shutdownHook == null) {
			// No shutdown hook registered yet.
			this.shutdownHook = new Thread(SHUTDOWN_HOOK_THREAD_NAME) {
				@Override
				public void run() {
					synchronized (startupShutdownMonitor) {
						doClose();
					}
				}
			};
			Runtime.getRuntime().addShutdownHook(this.shutdownHook);
		}
	}

	/**
	 * Callback for destruction of this instance, originally attached
	 * to a {@code DisposableBean} implementation (not anymore in 5.0).
	 * <p>The {@link #close()} method is the native way to shut down
	 * an ApplicationContext, which this method simply delegates to.
	 * @deprecated as of Spring Framework 5.0, in favor of {@link #close()}
	 */
	@Deprecated
	public void destroy() {
		close();
	}

	/**
	 * Close this application context, destroying all beans in its bean factory.
	 * <p>Delegates to {@code doClose()} for the actual closing procedure.
	 * Also removes a JVM shutdown hook, if registered, as it's not needed anymore.
	 * @see #doClose()
	 * @see #registerShutdownHook()
	 */
	@Override
	public void close() {
		synchronized (this.startupShutdownMonitor) {
			doClose();
			// If we registered a JVM shutdown hook, we don't need it anymore now:
			// We've already explicitly closed the context.
			if (this.shutdownHook != null) {
				try {
					Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
				}
				catch (IllegalStateException ex) {
					// ignore - VM is already shutting down
				}
			}
		}
	}

	/**
	 * Actually performs context closing: publishes a ContextClosedEvent and
	 * destroys the singletons in the bean factory of this application context.
	 * <p>Called by both {@code close()} and a JVM shutdown hook, if any.
	 * @see org.springframework.context.event.ContextClosedEvent
	 * @see #destroyBeans()
	 * @see #close()
	 * @see #registerShutdownHook()
	 */
	protected void doClose() {
		// Check whether an actual close attempt is necessary...
		if (this.active.get() && this.closed.compareAndSet(false, true)) {
			if (logger.isDebugEnabled()) {
				logger.debug("Closing " + this);
			}

			LiveBeansView.unregisterApplicationContext(this);

			try {
				// Publish shutdown event.
				publishEvent(new ContextClosedEvent(this));
			}
			catch (Throwable ex) {
				logger.warn("Exception thrown from ApplicationListener handling ContextClosedEvent", ex);
			}

			// Stop all Lifecycle beans, to avoid delays during individual destruction.
			if (this.lifecycleProcessor != null) {
				try {
					this.lifecycleProcessor.onClose();
				}
				catch (Throwable ex) {
					logger.warn("Exception thrown from LifecycleProcessor on context close", ex);
				}
			}

			// Destroy all cached singletons in the context's BeanFactory.
			destroyBeans();

			// Close the state of this context itself.
			closeBeanFactory();

			// Let subclasses do some final clean-up if they wish...
			onClose();

			// Reset local application listeners to pre-refresh state.
			if (this.earlyApplicationListeners != null) {
				this.applicationListeners.clear();
				this.applicationListeners.addAll(this.earlyApplicationListeners);
			}

			// Switch to inactive.
			this.active.set(false);
		}
	}

	/**
	 * Template method for destroying all beans that this context manages.
	 * The default implementation destroy all cached singletons in this context,
	 * invoking {@code DisposableBean.destroy()} and/or the specified
	 * "destroy-method".
	 * <p>Can be overridden to add context-specific bean destruction steps
	 * right before or right after standard singleton destruction,
	 * while the context's BeanFactory is still active.
	 * @see #getBeanFactory()
	 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory#destroySingletons()
	 */
	protected void destroyBeans() {
		getBeanFactory().destroySingletons();
	}

	/**
	 * Template method which can be overridden to add context-specific shutdown work.
	 * The default implementation is empty.
	 * <p>Called at the end of {@link #doClose}'s shutdown procedure, after
	 * this context's BeanFactory has been closed. If custom shutdown logic
	 * needs to execute while the BeanFactory is still active, override
	 * the {@link #destroyBeans()} method instead.
	 */
	protected void onClose() {
		// For subclasses: do nothing by default.
	}

	@Override
	public boolean isActive() {
		return this.active.get();
	}

	/**
	 * Assert that this context's BeanFactory is currently active,
	 * throwing an {@link IllegalStateException} if it isn't.
	 * <p>Invoked by all {@link BeanFactory} delegation methods that depend
	 * on an active context, i.e. in particular all bean accessor methods.
	 * <p>The default implementation checks the {@link #isActive() 'active'} status
	 * of this context overall. May be overridden for more specific checks, or for a
	 * no-op if {@link #getBeanFactory()} itself throws an exception in such a case.
	 */
	protected void assertBeanFactoryActive() {
		if (!this.active.get()) {
			if (this.closed.get()) {
				throw new IllegalStateException(getDisplayName() + " has been closed already");
			}
			else {
				throw new IllegalStateException(getDisplayName() + " has not been refreshed yet");
			}
		}
	}


	//---------------------------------------------------------------------
	// Implementation of BeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public Object getBean(String name) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(name);
	}

	@Override
	public <T> T getBean(String name, Class<T> requiredType) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(name, requiredType);
	}

	@Override
	public Object getBean(String name, Object... args) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(name, args);
	}

	@Override
	public <T> T getBean(Class<T> requiredType) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(requiredType);
	}

	@Override
	public <T> T getBean(Class<T> requiredType, Object... args) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(requiredType, args);
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanProvider(requiredType);
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(ResolvableType requiredType) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanProvider(requiredType);
	}

	@Override
	public boolean containsBean(String name) {
		return getBeanFactory().containsBean(name);
	}

	@Override
	public boolean isSingleton(String name) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().isSingleton(name);
	}

	@Override
	public boolean isPrototype(String name) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().isPrototype(name);
	}

	@Override
	public boolean isTypeMatch(String name, ResolvableType typeToMatch) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().isTypeMatch(name, typeToMatch);
	}

	@Override
	public boolean isTypeMatch(String name, Class<?> typeToMatch) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().isTypeMatch(name, typeToMatch);
	}

	@Override
	@Nullable
	public Class<?> getType(String name) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().getType(name);
	}

	@Override
	@Nullable
	public Class<?> getType(String name, boolean allowFactoryBeanInit) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().getType(name, allowFactoryBeanInit);
	}

	@Override
	public String[] getAliases(String name) {
		return getBeanFactory().getAliases(name);
	}


	//---------------------------------------------------------------------
	// Implementation of ListableBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public boolean containsBeanDefinition(String beanName) {
		return getBeanFactory().containsBeanDefinition(beanName);
	}

	@Override
	public int getBeanDefinitionCount() {
		return getBeanFactory().getBeanDefinitionCount();
	}

	@Override
	public String[] getBeanDefinitionNames() {
		return getBeanFactory().getBeanDefinitionNames();
	}

	@Override
	public String[] getBeanNamesForType(ResolvableType type) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type);
	}

	@Override
	public String[] getBeanNamesForType(ResolvableType type, boolean includeNonSingletons, boolean allowEagerInit) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type, includeNonSingletons, allowEagerInit);
	}

	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type);
	}

	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type, boolean includeNonSingletons, boolean allowEagerInit) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type, includeNonSingletons, allowEagerInit);
	}

	@Override
	public <T> Map<String, T> getBeansOfType(@Nullable Class<T> type) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBeansOfType(type);
	}

	@Override
	public <T> Map<String, T> getBeansOfType(@Nullable Class<T> type, boolean includeNonSingletons, boolean allowEagerInit)
			throws BeansException {

		assertBeanFactoryActive();
		return getBeanFactory().getBeansOfType(type, includeNonSingletons, allowEagerInit);
	}

	@Override
	public String[] getBeanNamesForAnnotation(Class<? extends Annotation> annotationType) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForAnnotation(annotationType);
	}

	@Override
	public Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType)
			throws BeansException {

		assertBeanFactoryActive();
		return getBeanFactory().getBeansWithAnnotation(annotationType);
	}

	@Override
	@Nullable
	public <A extends Annotation> A findAnnotationOnBean(String beanName, Class<A> annotationType)
			throws NoSuchBeanDefinitionException {

		assertBeanFactoryActive();
		return getBeanFactory().findAnnotationOnBean(beanName, annotationType);
	}


	//---------------------------------------------------------------------
	// Implementation of HierarchicalBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	@Nullable
	public BeanFactory getParentBeanFactory() {
		return getParent();
	}

	@Override
	public boolean containsLocalBean(String name) {
		return getBeanFactory().containsLocalBean(name);
	}

	/**
	 * Return the internal bean factory of the parent context if it implements
	 * ConfigurableApplicationContext; else, return the parent context itself.
	 * @see org.springframework.context.ConfigurableApplicationContext#getBeanFactory
	 */
	@Nullable
	protected BeanFactory getInternalParentBeanFactory() {
		//找到父的，若存在就返回 若存在父容器就存在父的BeanFactory
		return (getParent() instanceof ConfigurableApplicationContext ?
				((ConfigurableApplicationContext) getParent()).getBeanFactory() : getParent());
	}


	//---------------------------------------------------------------------
	// Implementation of MessageSource interface
	//---------------------------------------------------------------------

	@Override
	public String getMessage(String code, @Nullable Object[] args, @Nullable String defaultMessage, Locale locale) {
		return getMessageSource().getMessage(code, args, defaultMessage, locale);
	}

	@Override
	public String getMessage(String code, @Nullable Object[] args, Locale locale) throws NoSuchMessageException {
		return getMessageSource().getMessage(code, args, locale);
	}

	@Override
	public String getMessage(MessageSourceResolvable resolvable, Locale locale) throws NoSuchMessageException {
		return getMessageSource().getMessage(resolvable, locale);
	}

	/**
	 * Return the internal MessageSource used by the context.
	 * @return the internal MessageSource (never {@code null})
	 * @throws IllegalStateException if the context has not been initialized yet
	 */
	private MessageSource getMessageSource() throws IllegalStateException {
		if (this.messageSource == null) {
			throw new IllegalStateException("MessageSource not initialized - " +
					"call 'refresh' before accessing messages via the context: " + this);
		}
		return this.messageSource;
	}

	/**
	 * Return the internal message source of the parent context if it is an
	 * AbstractApplicationContext too; else, return the parent context itself.
	 */
	@Nullable
	protected MessageSource getInternalParentMessageSource() {
		return (getParent() instanceof AbstractApplicationContext ?
				((AbstractApplicationContext) getParent()).messageSource : getParent());
	}


	//---------------------------------------------------------------------
	// Implementation of ResourcePatternResolver interface
	//---------------------------------------------------------------------

	@Override
	public Resource[] getResources(String locationPattern) throws IOException {
		return this.resourcePatternResolver.getResources(locationPattern);
	}


	//---------------------------------------------------------------------
	// Implementation of Lifecycle interface
	//---------------------------------------------------------------------

	@Override
	public void start() {
		getLifecycleProcessor().start();
		publishEvent(new ContextStartedEvent(this));
	}

	@Override
	public void stop() {
		getLifecycleProcessor().stop();
		publishEvent(new ContextStoppedEvent(this));
	}

	@Override
	public boolean isRunning() {
		return (this.lifecycleProcessor != null && this.lifecycleProcessor.isRunning());
	}


	//---------------------------------------------------------------------
	// Abstract methods that must be implemented by subclasses
	//---------------------------------------------------------------------

	/**
	 * Subclasses must implement this method to perform the actual configuration load.
	 * The method is invoked by {@link #refresh()} before any other initialization work.
	 * <p>A subclass will either create a new bean factory and hold a reference to it,
	 * or return a single BeanFactory instance that it holds. In the latter case, it will
	 * usually throw an IllegalStateException if refreshing the context more than once.
	 * @throws BeansException if initialization of the bean factory failed
	 * @throws IllegalStateException if already initialized and multiple refresh
	 * attempts are not supported
	 */
	protected abstract void refreshBeanFactory() throws BeansException, IllegalStateException;

	/**
	 * Subclasses must implement this method to release their internal bean factory.
	 * This method gets invoked by {@link #close()} after all other shutdown work.
	 * <p>Should never throw an exception but rather log shutdown failures.
	 */
	protected abstract void closeBeanFactory();

	/**
	 * Subclasses must return their internal bean factory here. They should implement the
	 * lookup efficiently, so that it can be called repeatedly without a performance penalty.
	 * <p>Note: Subclasses should check whether the context is still active before
	 * returning the internal bean factory. The internal factory should generally be
	 * considered unavailable once the context has been closed.
	 * @return this application context's internal bean factory (never {@code null})
	 * @throws IllegalStateException if the context does not hold an internal bean factory yet
	 * (usually if {@link #refresh()} has never been called) or if the context has been
	 * closed already
	 * @see #refreshBeanFactory()
	 * @see #closeBeanFactory()
	 */
	@Override
	public abstract ConfigurableListableBeanFactory getBeanFactory() throws IllegalStateException;


	/**
	 * Return information about this context.
	 */
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(getDisplayName());
		sb.append(", started on ").append(new Date(getStartupDate()));
		ApplicationContext parent = getParent();
		if (parent != null) {
			sb.append(", parent: ").append(parent.getDisplayName());
		}
		return sb.toString();
	}

}
