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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessorAdapter;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.beans.factory.parsing.FailFastProblemReporter;
import org.springframework.beans.factory.parsing.PassThroughSourceExtractor;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.beans.factory.parsing.SourceExtractor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ConfigurationClassEnhancer.EnhancedConfiguration;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * {@link BeanFactoryPostProcessor} used for bootstrapping processing of
 * {@link Configuration @Configuration} classes.
 *
 * <p>Registered by default when using {@code <context:annotation-config/>} or
 * {@code <context:component-scan/>}. Otherwise, may be declared manually as
 * with any other {@link BeanFactoryPostProcessor}.
 *
 * <p>This post processor is priority-ordered as it is important that any
 * {@link Bean @Bean} methods declared in {@code @Configuration} classes have
 * their corresponding bean definitions registered before any other
 * {@code BeanFactoryPostProcessor} executes.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @author Sam Brannen
 * @since 3.0
 *
 * 此类是一个后置处理器的类，主要功能是参与BeanFactory的建造，主要功能如下
 *   1、解析加了@Configuration的配置类
 *   2、解析@ComponentScan扫描的包
 *   3、解析@ComponentScans扫描的包
 *   4、解析@Import注解
 */
public class ConfigurationClassPostProcessor implements BeanDefinitionRegistryPostProcessor,
		PriorityOrdered, ResourceLoaderAware, BeanClassLoaderAware, EnvironmentAware {

	/**
	 * A {@code BeanNameGenerator} using fully qualified class names as default bean names.
	 * <p>This default for configuration-level import purposes may be overridden through
	 * {@link #setBeanNameGenerator}. Note that the default for component scanning purposes
	 * is a plain {@link AnnotationBeanNameGenerator#INSTANCE}, unless overridden through
	 * {@link #setBeanNameGenerator} with a unified user-level bean name generator.
	 * @since 5.2
	 * @see #setBeanNameGenerator
	 *
	 * 使用类的全限定名作为bean的默认生成策略
	 */
	public static final AnnotationBeanNameGenerator IMPORT_BEAN_NAME_GENERATOR =
			new FullyQualifiedAnnotationBeanNameGenerator();

	private static final String IMPORT_REGISTRY_BEAN_NAME =
			ConfigurationClassPostProcessor.class.getName() + ".importRegistry";


	private final Log logger = LogFactory.getLog(getClass());

	private SourceExtractor sourceExtractor = new PassThroughSourceExtractor();

	private ProblemReporter problemReporter = new FailFastProblemReporter();

	@Nullable
	private Environment environment;

	private ResourceLoader resourceLoader = new DefaultResourceLoader();

	@Nullable
	private ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader();

	private MetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory();

	private boolean setMetadataReaderFactoryCalled = false;

	private final Set<Integer> registriesPostProcessed = new HashSet<>();

	private final Set<Integer> factoriesPostProcessed = new HashSet<>();

	@Nullable
	private ConfigurationClassBeanDefinitionReader reader;

	// 是否使用本地xml配置的beanNameGenerator生成器
	private boolean localBeanNameGeneratorSet = false;

	/* Using short class names as default bean names by default. */
	// 使用短类名作为bean名称生成策略
	private BeanNameGenerator componentScanBeanNameGenerator = AnnotationBeanNameGenerator.INSTANCE;

	/* Using fully qualified class names as default bean names by default. */
	// 使用类的全限定名作为bean默认生成策略
	private BeanNameGenerator importBeanNameGenerator = IMPORT_BEAN_NAME_GENERATOR;


	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;  // within PriorityOrdered
	}

	/**
	 * Set the {@link SourceExtractor} to use for generated bean definitions
	 * that correspond to {@link Bean} factory methods.
	 */
	public void setSourceExtractor(@Nullable SourceExtractor sourceExtractor) {
		this.sourceExtractor = (sourceExtractor != null ? sourceExtractor : new PassThroughSourceExtractor());
	}

	/**
	 * Set the {@link ProblemReporter} to use.
	 * <p>Used to register any problems detected with {@link Configuration} or {@link Bean}
	 * declarations. For instance, an @Bean method marked as {@code final} is illegal
	 * and would be reported as a problem. Defaults to {@link FailFastProblemReporter}.
	 */
	public void setProblemReporter(@Nullable ProblemReporter problemReporter) {
		this.problemReporter = (problemReporter != null ? problemReporter : new FailFastProblemReporter());
	}

	/**
	 * Set the {@link MetadataReaderFactory} to use.
	 * <p>Default is a {@link CachingMetadataReaderFactory} for the specified
	 * {@linkplain #setBeanClassLoader bean class loader}.
	 */
	public void setMetadataReaderFactory(MetadataReaderFactory metadataReaderFactory) {
		Assert.notNull(metadataReaderFactory, "MetadataReaderFactory must not be null");
		this.metadataReaderFactory = metadataReaderFactory;
		this.setMetadataReaderFactoryCalled = true;
	}

	/**
	 * Set the {@link BeanNameGenerator} to be used when triggering component scanning
	 * from {@link Configuration} classes and when registering {@link Import}'ed
	 * configuration classes. The default is a standard {@link AnnotationBeanNameGenerator}
	 * for scanned components (compatible with the default in {@link ClassPathBeanDefinitionScanner})
	 * and a variant thereof for imported configuration classes (using unique fully-qualified
	 * class names instead of standard component overriding).
	 * <p>Note that this strategy does <em>not</em> apply to {@link Bean} methods.
	 * <p>This setter is typically only appropriate when configuring the post-processor as a
	 * standalone bean definition in XML, e.g. not using the dedicated {@code AnnotationConfig*}
	 * application contexts or the {@code <context:annotation-config>} element. Any bean name
	 * generator specified against the application context will take precedence over any set here.
	 * @since 3.1.1
	 * @see AnnotationConfigApplicationContext#setBeanNameGenerator(BeanNameGenerator)
	 * @see AnnotationConfigUtils#CONFIGURATION_BEAN_NAME_GENERATOR
	 */
	public void setBeanNameGenerator(BeanNameGenerator beanNameGenerator) {
		Assert.notNull(beanNameGenerator, "BeanNameGenerator must not be null");
		this.localBeanNameGeneratorSet = true;
		this.componentScanBeanNameGenerator = beanNameGenerator;
		this.importBeanNameGenerator = beanNameGenerator;
	}

	@Override
	public void setEnvironment(Environment environment) {
		Assert.notNull(environment, "Environment must not be null");
		this.environment = environment;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		Assert.notNull(resourceLoader, "ResourceLoader must not be null");
		this.resourceLoader = resourceLoader;
		if (!this.setMetadataReaderFactoryCalled) {
			this.metadataReaderFactory = new CachingMetadataReaderFactory(resourceLoader);
		}
	}

	@Override
	public void setBeanClassLoader(ClassLoader beanClassLoader) {
		this.beanClassLoader = beanClassLoader;
		if (!this.setMetadataReaderFactoryCalled) {
			this.metadataReaderFactory = new CachingMetadataReaderFactory(beanClassLoader);
		}
	}


	/**
	 * Derive further bean definitions from the configuration classes in the registry.
	 *
	 * 1、使用工具ConfigurationClassParser尝试发现所有的配置(@Configuration)类
	 * 2、使用工具ConfigurationClassBeanDefinitionReader注册所发现的配置类中所有的bean定义
	 * 3、结束执行的条件是所有配置类都被发现和处理,相应的bean定义注册到容器（内部有递归）
	 */
	@Override
	public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
		//根据BeanDefinitionRegistry,生成registryId 是全局唯一的。
		int registryId = System.identityHashCode(registry);
		// 判断，如果这个registryId 已经被执行过了，就不能够再执行了，否则抛出异常
		if (this.registriesPostProcessed.contains(registryId)) {
			throw new IllegalStateException(
					"postProcessBeanDefinitionRegistry already called on this post-processor against " + registry);
		}
		// 已经执行过的registry  防止重复执行
		if (this.factoriesPostProcessed.contains(registryId)) {
			throw new IllegalStateException(
					"postProcessBeanFactory already called on this post-processor against " + registry);
		}
		this.registriesPostProcessed.add(registryId);

		// 调用processConfigBeanDefinitions 进行Bean定义的加载.代码如下
		processConfigBeanDefinitions(registry);
	}

	/**
	 * Prepare the Configuration classes for servicing bean requests at runtime
	 * by replacing them with CGLIB-enhanced subclasses.
	 *
	 * 这里就是增强配置类，添加一个 ImportAwareBeanPostProcessor，这个类用来处理 ImportAware 接口实现
	 */
	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		int factoryId = System.identityHashCode(beanFactory);
		if (this.factoriesPostProcessed.contains(factoryId)) {
			throw new IllegalStateException(
					"postProcessBeanFactory already called on this post-processor against " + beanFactory);
		}
		this.factoriesPostProcessed.add(factoryId);
		// 这一步的意思是：如果processConfigBeanDefinitions没被执行过（不支持hook的时候不会执行）
		// 这里会补充去执行processConfigBeanDefinitions这个方法
		if (!this.registriesPostProcessed.contains(factoryId)) {
			// BeanDefinitionRegistryPostProcessor hook apparently not supported...
			// Simply call processConfigurationClasses lazily at this point then.
			processConfigBeanDefinitions((BeanDefinitionRegistry) beanFactory);
		}

		// 这个是核心方法~~~~~~~~~~~~~~
		enhanceConfigurationClasses(beanFactory);
		// 添加一个后置处理器。ImportAwareBeanPostProcessor它是一个静态内部类
		// 记住：它实现了SmartInstantiationAwareBeanPostProcessor这个接口就可以了。后面会有作用的
		//InstantiationAwareBeanPostProcessor是BeanPostProcessor的子接口，可以在Bean生命周期的另外两个时期提供扩展的回调接口
		//即实例化Bean之前（调用postProcessBeforeInstantiation方法）和实例化Bean之后（调用postProcessAfterInstantiation方法）
		beanFactory.addBeanPostProcessor(new ImportAwareBeanPostProcessor(beanFactory));
	}

	/**
	 * Build and validate a configuration model based on the registry of
	 * {@link Configuration} classes.
	 */
	// 解释一下：我们配置类是什么时候注册进去的呢？？？(此处只讲注解驱动的Spring和SpringBoot)
	// 注解驱动的Spring为：自己在`WebApplicationInitializer`里面自己手动指定进去的
	// SpringBoot为它自己的main引导类里去加载进来的，后面详说SpringBoot部分
	public void processConfigBeanDefinitions(BeanDefinitionRegistry registry) {
		// 配置类Bean定义
		List<BeanDefinitionHolder> configCandidates = new ArrayList<>();
		// 获取已经注册的bean名称
		String[] candidateNames = registry.getBeanDefinitionNames();

		// 遍历所有要处理的beanDefinition的名称,筛选对应的beanDefinition（被注解修饰的配置类）
		for (String beanName : candidateNames) {
			// 获取指定名称的BeanDefinition对象
			BeanDefinition beanDef = registry.getBeanDefinition(beanName);
			// 这个判断很有意思~~~ 如果你的beanDef现在就已经确定了是full或者lite，说明你肯定已经被解析过了，，所以再来的话输出个debug即可（其实我觉得输出warn也行~~~）
			if (beanDef.getAttribute(ConfigurationClassUtils.CONFIGURATION_CLASS_ATTRIBUTE) != null) {
				if (logger.isDebugEnabled()) {
					logger.debug("Bean definition has already been processed as a configuration class: " + beanDef);
				}
			}
			// 检查是否是@Configuration的Class,如果是就标记下属性：full 或者lite。beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_FULL)
			// 加入到configCandidates里保存配置文件类的定义
			// 如果Configuration配置proxyBeanMethods代理为true则为full
			// 如果加了@Bean、@Component、@ComponentScan、@Import、@ImportResource注解，则设置为lite
			// 如果配置类上被@Order注解标注，则设置BeanDefinition的order属性值
			else if (ConfigurationClassUtils.checkConfigurationClassCandidate(beanDef, this.metadataReaderFactory)) {
				configCandidates.add(new BeanDefinitionHolder(beanDef, beanName));
			}
		}

		// Return immediately if no @Configuration classes were found
		// 如果一个配置文件类都没找到，现在就不需要再继续下去了
		if (configCandidates.isEmpty()) {
			return;
		}

		// Sort by previously determined @Order value, if applicable
		// 把配置文件们：按照@Order注解进行排序  这个意思是，我们@Configuration注解的配置文件是支持order排序的。（备注：普通bean不行的~~~）
		configCandidates.sort((bd1, bd2) -> {
			int i1 = ConfigurationClassUtils.getOrder(bd1.getBeanDefinition());
			int i2 = ConfigurationClassUtils.getOrder(bd2.getBeanDefinition());
			return Integer.compare(i1, i2);
		});

		// Detect any custom bean name generation strategy supplied through the enclosing application context
		// 尝试着给Bean扫描方式，以及import方法的BeanNameGenerator赋值(若我们都没指定，那就是默认的AnnotationBeanNameGenerator：扫描为首字母小写，import为全类名)
		SingletonBeanRegistry sbr = null;
		if (registry instanceof SingletonBeanRegistry) {
			sbr = (SingletonBeanRegistry) registry;
			// 判断是否有自定义的beanName生成器
			if (!this.localBeanNameGeneratorSet) {
				// 获取自定义的beanName生成器
				BeanNameGenerator generator = (BeanNameGenerator) sbr.getSingleton(
						AnnotationConfigUtils.CONFIGURATION_BEAN_NAME_GENERATOR);
				// 如果有自定义的命名生成策略
				if (generator != null) {
					//设置组件扫描的beanName生成策略
					this.componentScanBeanNameGenerator = generator;
					// 设置import bean name生成策略
					this.importBeanNameGenerator = generator;
				}
			}
		}

		// web环境，这里都设置了StandardServletEnvironment
		// 一般来说到此处，env环境不可能为null了~~~ 此处做一个容错处理~~~
		if (this.environment == null) {
			this.environment = new StandardEnvironment();
		}

		// Parse each @Configuration class
		// 这是重点：真正解析@Configuration类的，其实是ConfigurationClassParser 这个解析器来做的
		// parser 后面用于解析每一个配置类~~~~
		ConfigurationClassParser parser = new ConfigurationClassParser(
				this.metadataReaderFactory, this.problemReporter, this.environment,
				this.resourceLoader, this.componentScanBeanNameGenerator, registry);

		Set<BeanDefinitionHolder> candidates = new LinkedHashSet<>(configCandidates);
		// 装载已经处理过的配置类
		Set<ConfigurationClass> alreadyParsed = new HashSet<>(configCandidates.size());
		do {
			/**
			 * 核心方法：具体详解如下
			 * 解析带有@Controller、@Import、@ImportResource、@ComponentScan、@ComponentScans、@Bean的BeanDefinition
			 * 只是将配置类进行解析，解析完的结果存放在ConfigurationClass对象中，在
			 * @see ConfigurationClassParser#configurationClasses 中存储
			 *
			 * 在这个步骤前，配置类的bean定义信息是已经注册好了的
			 * 在这个步骤中，bean定义信息可能会新注册，如：ComponentScan扫描到了组件（@Component），会直接进行注册
			 * 在这个步骤后，还可能存在未注册的bean定义，如：
			 * 配置类中定义了@Bean的类或方法(@see org.springframework.context.annotation.ConfigurationClass#beanMethods)，
			 * 配置类中存在内部类等等
			 *
			 * 因此，解析完之后，需要提供配置类，给
			 * @see ConfigurationClassBeanDefinitionReader#loadBeanDefinitions(java.util.Set) 进行处理
			 */
			parser.parse(candidates);
			// 校验 配置类不能使final的，因为需要使用CGLIB生成代理对象，见postProcessBeanFactory方法
			parser.validate();

			// 此处就拿出了我们已经处理好的所有配置类们（该配置文件下的所有组件们~~~~）
			Set<ConfigurationClass> configClasses = new LinkedHashSet<>(parser.getConfigurationClasses());
			configClasses.removeAll(alreadyParsed);

			// Read the model and create bean definitions based on its content
			// 如果Reader为null，那就实例化ConfigurationClassBeanDefinitionReader来加载Bean，并加入到alreadyParsed中,用于去重（避免譬如@ComponentScan直接互扫）
			if (this.reader == null) {
				this.reader = new ConfigurationClassBeanDefinitionReader(
						registry, this.sourceExtractor, this.resourceLoader, this.environment,
						this.importBeanNameGenerator, parser.getImportRegistry());
			}
			/**
			 * 加载配置文件里面（上面parser解析完的配置类）的@Bean/@Import们，在此处将配置类中包含的bean定义信息注册到容器中
			 * 这个方法是非常重要的，因为它决定了向容器注册Bean定义信息的顺序问题~~~
			 */
			this.reader.loadBeanDefinitions(configClasses);
			alreadyParsed.addAll(configClasses);

			candidates.clear();
			// 这里判断registry.getBeanDefinitionCount() > candidateNames.length的目的是为了知道reader.loadBeanDefinitions(configClasses)这一步有没有向BeanDefinitionMap中添加新的BeanDefinition
			// 实际上就是看配置类(例如AppConfig类会向BeanDefinitionMap中添加bean)
			// 如果有，registry.getBeanDefinitionCount()就会大于candidateNames.length
			// 这样就需要再次遍历新加入的BeanDefinition，并判断这些bean是否已经被解析过了，如果未解析，需要重新进行解析
			// 这里的AppConfig类向容器中添加的bean，实际上在parser.parse()这一步已经全部被解析了
			if (registry.getBeanDefinitionCount() > candidateNames.length) {
				String[] newCandidateNames = registry.getBeanDefinitionNames();
				Set<String> oldCandidateNames = new HashSet<>(Arrays.asList(candidateNames));
				Set<String> alreadyParsedClasses = new HashSet<>();
				for (ConfigurationClass configurationClass : alreadyParsed) {
					alreadyParsedClasses.add(configurationClass.getMetadata().getClassName());
				}
				for (String candidateName : newCandidateNames) {
					// 如果有未解析的类，则将其添加到candidates中，这样candidates不为空，就会进入到下一次的while的循环中
					// 这一步挺有意思：若老的oldCandidateNames不包含。也就是说你是新进来的候选的Bean定义们，那就进一步的进行一个处理
					// 比如这里的DelegatingWebMvcConfiguration，他就是新进的，因此它继续往下走
					// 这个@Import进来的配置类最终会被ConfigurationClassPostProcessor这个后置处理器的postProcessBeanFactory 方法，进行处理和cglib增强
					if (!oldCandidateNames.contains(candidateName)) {
						BeanDefinition bd = registry.getBeanDefinition(candidateName);
						if (ConfigurationClassUtils.checkConfigurationClassCandidate(bd, this.metadataReaderFactory) &&
								!alreadyParsedClasses.contains(bd.getBeanClassName())) {
							candidates.add(new BeanDefinitionHolder(bd, candidateName));
						}
					}
				}
				candidateNames = newCandidateNames;
			}
		}
		while (!candidates.isEmpty());

		// Register the ImportRegistry as a bean in order to support ImportAware @Configuration classes
		//如果SingletonBeanRegistry 不包含org.springframework.context.annotation.ConfigurationClassPostProcessor.importRegistry,
		//则注册一个,bean 为 ImportRegistry. 一般都会进行注册的
		if (sbr != null && !sbr.containsSingleton(IMPORT_REGISTRY_BEAN_NAME)) {
			sbr.registerSingleton(IMPORT_REGISTRY_BEAN_NAME, parser.getImportRegistry());
		}

		// 清除缓存 元数据缓存
		if (this.metadataReaderFactory instanceof CachingMetadataReaderFactory) {
			// Clear cache in externally provided MetadataReaderFactory; this is a no-op
			// for a shared cache since it'll be cleared by the ApplicationContext.
			((CachingMetadataReaderFactory) this.metadataReaderFactory).clearCache();
		}
	}

	/**
	 * Post-processes a BeanFactory in search of Configuration class BeanDefinitions;
	 * any candidates are then enhanced by a {@link ConfigurationClassEnhancer}.
	 * Candidate status is determined by BeanDefinition attribute metadata.
	 * @see ConfigurationClassEnhancer
	 *
	 * 可能有人会问，Spring为何要用cglib增强配置文件@Configuration呢？
	 * 其实上面提到的Full和Lite的区别已经能够有所答案了：
	 * 我们通过源码发现：只有full模式的才会去增强，然后增强带来的好处是：Spring可以更好的管理Bean的依赖关系了。比如@Bean之间方法之间的调用，我们发现，其实是去Spring容器里去找Bean了，而并不是再生成了一个实例。（它的缺点是使用了代理，带来的性能影响完全可以忽略）
	 *
	 * 其实这些可以通过我们自己书写代码来避免，但是Spring为了让他的自动化识别来得更加强大，所以采用代理技术来接管这些配置Bean的依赖，可谓对开发者十分的友好
	 *
	 * 源码也指出了：Liter模式是不会采用代理的，因此它的Bean依赖关系程序员自己去把控吧。建议：不要使用Lite模式，会带来不少莫名其妙的坑
	 */
	public void enhanceConfigurationClasses(ConfigurableListableBeanFactory beanFactory) {
		Map<String, AbstractBeanDefinition> configBeanDefs = new LinkedHashMap<>();
		// 拿到所有的Bean名称
		for (String beanName : beanFactory.getBeanDefinitionNames()) {
			BeanDefinition beanDef = beanFactory.getBeanDefinition(beanName);
			Object configClassAttr = beanDef.getAttribute(ConfigurationClassUtils.CONFIGURATION_CLASS_ATTRIBUTE);
			AnnotationMetadata annotationMetadata = null;
			MethodMetadata methodMetadata = null;
			if (beanDef instanceof AnnotatedBeanDefinition) {
				AnnotatedBeanDefinition annotatedBeanDefinition = (AnnotatedBeanDefinition) beanDef;
				annotationMetadata = annotatedBeanDefinition.getMetadata();
				methodMetadata = annotatedBeanDefinition.getFactoryMethodMetadata();
			}
			if ((configClassAttr != null || methodMetadata != null) && beanDef instanceof AbstractBeanDefinition) {
				// Configuration class (full or lite) or a configuration-derived @Bean method
				// -> eagerly resolve bean class at this point, unless it's a 'lite' configuration
				// or component class without @Bean methods.
				AbstractBeanDefinition abd = (AbstractBeanDefinition) beanDef;
				if (!abd.hasBeanClass()) {
					boolean liteConfigurationCandidateWithoutBeanMethods =
							(ConfigurationClassUtils.CONFIGURATION_CLASS_LITE.equals(configClassAttr) &&
								annotationMetadata != null && !ConfigurationClassUtils.hasBeanMethods(annotationMetadata));
					if (!liteConfigurationCandidateWithoutBeanMethods) {
						try {
							abd.resolveBeanClass(this.beanClassLoader);
						}
						catch (Throwable ex) {
							throw new IllegalStateException(
									"Cannot load configuration class: " + beanDef.getBeanClassName(), ex);
						}
					}
				}
			}
			// 显然，能进来这个条件的
			// 从此处也可以看出，如果是lite版的@Configuration，是不会增强的
			if (ConfigurationClassUtils.CONFIGURATION_CLASS_FULL.equals(configClassAttr)) {
				if (!(beanDef instanceof AbstractBeanDefinition)) {
					throw new BeanDefinitionStoreException("Cannot enhance @Configuration bean definition '" +
							beanName + "' since it is not stored in an AbstractBeanDefinition subclass");
				}
				else if (logger.isInfoEnabled() && beanFactory.containsSingleton(beanName)) {
					logger.info("Cannot enhance @Configuration bean definition '" + beanName +
							"' since its singleton instance has been created too early. The typical cause " +
							"is a non-static @Bean method with a BeanDefinitionRegistryPostProcessor " +
							"return type: Consider declaring such methods as 'static'.");
				}
				configBeanDefs.put(beanName, (AbstractBeanDefinition) beanDef);
			}
		}
		// 装载着等待被加强的一些配置类们
		if (configBeanDefs.isEmpty()) {
			// nothing to enhance -> return immediately
			return;
		}

		ConfigurationClassEnhancer enhancer = new ConfigurationClassEnhancer();
		for (Map.Entry<String, AbstractBeanDefinition> entry : configBeanDefs.entrySet()) {
			AbstractBeanDefinition beanDef = entry.getValue();
			// If a @Configuration class gets proxied, always proxy the target class
			beanDef.setAttribute(AutoProxyUtils.PRESERVE_TARGET_CLASS_ATTRIBUTE, Boolean.TRUE);
			// Set enhanced subclass of the user-specified bean class
			//  拿到原始的类
			Class<?> configClass = beanDef.getBeanClass();
			// 对此类进行cglib增强
			Class<?> enhancedClass = enhancer.enhance(configClass, this.beanClassLoader);
			if (configClass != enhancedClass) {
				if (logger.isTraceEnabled()) {
					logger.trace(String.format("Replacing bean definition '%s' existing class '%s' with " +
							"enhanced class '%s'", entry.getKey(), configClass.getName(), enhancedClass.getName()));
				}
				// 把增强后的类配置类set进去。
				// 所以我们getBean()配置类，拿出来的是配置类的代理对象  是被CGLIB代理过的
				beanDef.setBeanClass(enhancedClass);
			}
		}
	}


	private static class ImportAwareBeanPostProcessor extends InstantiationAwareBeanPostProcessorAdapter {

		private final BeanFactory beanFactory;

		public ImportAwareBeanPostProcessor(BeanFactory beanFactory) {
			this.beanFactory = beanFactory;
		}

		@Override
		public PropertyValues postProcessProperties(@Nullable PropertyValues pvs, Object bean, String beanName) {
			// Inject the BeanFactory before AutowiredAnnotationBeanPostProcessor's
			// postProcessProperties method attempts to autowire other configuration beans.
			if (bean instanceof EnhancedConfiguration) {
				((EnhancedConfiguration) bean).setBeanFactory(this.beanFactory);
			}
			return pvs;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			if (bean instanceof ImportAware) {
				ImportRegistry ir = this.beanFactory.getBean(IMPORT_REGISTRY_BEAN_NAME, ImportRegistry.class);
				AnnotationMetadata importingClass = ir.getImportingClassFor(ClassUtils.getUserClass(bean).getName());
				if (importingClass != null) {
					((ImportAware) bean).setImportMetadata(importingClass);
				}
			}
			return bean;
		}
	}

}
