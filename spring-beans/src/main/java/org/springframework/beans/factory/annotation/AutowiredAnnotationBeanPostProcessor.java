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

package org.springframework.beans.factory.annotation;

import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessorAdapter;
import org.springframework.beans.factory.support.LookupOverride;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor BeanPostProcessor}
 * implementation that autowires annotated fields, setter methods, and arbitrary
 * config methods. Such members to be injected are detected through annotations:
 * by default, Spring's {@link Autowired @Autowired} and {@link Value @Value}
 * annotations.
 *
 * <p>Also supports JSR-330's {@link javax.inject.Inject @Inject} annotation,
 * if available, as a direct alternative to Spring's own {@code @Autowired}.
 *
 * <h3>Autowired Constructors</h3>
 * <p>Only one constructor of any given bean class may declare this annotation with
 * the 'required' attribute set to {@code true}, indicating <i>the</i> constructor
 * to autowire when used as a Spring bean. Furthermore, if the 'required' attribute
 * is set to {@code true}, only a single constructor may be annotated with
 * {@code @Autowired}. If multiple <i>non-required</i> constructors declare the
 * annotation, they will be considered as candidates for autowiring. The constructor
 * with the greatest number of dependencies that can be satisfied by matching beans
 * in the Spring container will be chosen. If none of the candidates can be satisfied,
 * then a primary/default constructor (if present) will be used. If a class only
 * declares a single constructor to begin with, it will always be used, even if not
 * annotated. An annotated constructor does not have to be public.
 *
 * <h3>Autowired Fields</h3>
 * <p>Fields are injected right after construction of a bean, before any
 * config methods are invoked. Such a config field does not have to be public.
 *
 * <h3>Autowired Methods</h3>
 * <p>Config methods may have an arbitrary name and any number of arguments; each of
 * those arguments will be autowired with a matching bean in the Spring container.
 * Bean property setter methods are effectively just a special case of such a
 * general config method. Config methods do not have to be public.
 *
 * <h3>Annotation Config vs. XML Config</h3>
 * <p>A default {@code AutowiredAnnotationBeanPostProcessor} will be registered
 * by the "context:annotation-config" and "context:component-scan" XML tags.
 * Remove or turn off the default annotation configuration there if you intend
 * to specify a custom {@code AutowiredAnnotationBeanPostProcessor} bean definition.
 *
 * <p><b>NOTE:</b> Annotation injection will be performed <i>before</i> XML injection;
 * thus the latter configuration will override the former for properties wired through
 * both approaches.
 *
 * <h3>{@literal @}Lookup Methods</h3>
 * <p>In addition to regular injection points as discussed above, this post-processor
 * also handles Spring's {@link Lookup @Lookup} annotation which identifies lookup
 * methods to be replaced by the container at runtime. This is essentially a type-safe
 * version of {@code getBean(Class, args)} and {@code getBean(String, args)}.
 * See {@link Lookup @Lookup's javadoc} for details.
 *
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @author Stephane Nicoll
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @since 2.5
 * @see #setAutowiredAnnotationType
 * @see Autowired
 * @see Value
 */
public class AutowiredAnnotationBeanPostProcessor extends InstantiationAwareBeanPostProcessorAdapter
		implements MergedBeanDefinitionPostProcessor, PriorityOrdered, BeanFactoryAware {

	protected final Log logger = LogFactory.getLog(getClass());

	private final Set<Class<? extends Annotation>> autowiredAnnotationTypes = new LinkedHashSet<>(4);

	private String requiredParameterName = "required";

	private boolean requiredParameterValue = true;

	private int order = Ordered.LOWEST_PRECEDENCE - 2;

	@Nullable
	private ConfigurableListableBeanFactory beanFactory;

	private final Set<String> lookupMethodsChecked = Collections.newSetFromMap(new ConcurrentHashMap<>(256));

	private final Map<Class<?>, Constructor<?>[]> candidateConstructorsCache = new ConcurrentHashMap<>(256);

	private final Map<String, InjectionMetadata> injectionMetadataCache = new ConcurrentHashMap<>(256);


	/**
	 * Create a new {@code AutowiredAnnotationBeanPostProcessor} for Spring's
	 * standard {@link Autowired @Autowired} and {@link Value @Value} annotations.
	 * <p>Also supports JSR-330's {@link javax.inject.Inject @Inject} annotation,
	 * if available.
	 */
	@SuppressWarnings("unchecked")
	public AutowiredAnnotationBeanPostProcessor() {
		this.autowiredAnnotationTypes.add(Autowired.class);
		this.autowiredAnnotationTypes.add(Value.class);
		try {
			this.autowiredAnnotationTypes.add((Class<? extends Annotation>)
					ClassUtils.forName("javax.inject.Inject", AutowiredAnnotationBeanPostProcessor.class.getClassLoader()));
			logger.trace("JSR-330 'javax.inject.Inject' annotation found and supported for autowiring");
		}
		catch (ClassNotFoundException ex) {
			// JSR-330 API not available - simply skip.
		}
	}


	/**
	 * Set the 'autowired' annotation type, to be used on constructors, fields,
	 * setter methods, and arbitrary config methods.
	 * <p>The default autowired annotation types are the Spring-provided
	 * {@link Autowired @Autowired} and {@link Value @Value} annotations as well
	 * as JSR-330's {@link javax.inject.Inject @Inject} annotation, if available.
	 * <p>This setter property exists so that developers can provide their own
	 * (non-Spring-specific) annotation type to indicate that a member is supposed
	 * to be autowired.
	 */
	public void setAutowiredAnnotationType(Class<? extends Annotation> autowiredAnnotationType) {
		Assert.notNull(autowiredAnnotationType, "'autowiredAnnotationType' must not be null");
		this.autowiredAnnotationTypes.clear();
		this.autowiredAnnotationTypes.add(autowiredAnnotationType);
	}

	/**
	 * Set the 'autowired' annotation types, to be used on constructors, fields,
	 * setter methods, and arbitrary config methods.
	 * <p>The default autowired annotation types are the Spring-provided
	 * {@link Autowired @Autowired} and {@link Value @Value} annotations as well
	 * as JSR-330's {@link javax.inject.Inject @Inject} annotation, if available.
	 * <p>This setter property exists so that developers can provide their own
	 * (non-Spring-specific) annotation types to indicate that a member is supposed
	 * to be autowired.
	 */
	public void setAutowiredAnnotationTypes(Set<Class<? extends Annotation>> autowiredAnnotationTypes) {
		Assert.notEmpty(autowiredAnnotationTypes, "'autowiredAnnotationTypes' must not be empty");
		this.autowiredAnnotationTypes.clear();
		this.autowiredAnnotationTypes.addAll(autowiredAnnotationTypes);
	}

	/**
	 * Set the name of an attribute of the annotation that specifies whether it is required.
	 * @see #setRequiredParameterValue(boolean)
	 */
	public void setRequiredParameterName(String requiredParameterName) {
		this.requiredParameterName = requiredParameterName;
	}

	/**
	 * Set the boolean value that marks a dependency as required.
	 * <p>For example if using 'required=true' (the default), this value should be
	 * {@code true}; but if using 'optional=false', this value should be {@code false}.
	 * @see #setRequiredParameterName(String)
	 */
	public void setRequiredParameterValue(boolean requiredParameterValue) {
		this.requiredParameterValue = requiredParameterValue;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		if (!(beanFactory instanceof ConfigurableListableBeanFactory)) {
			throw new IllegalArgumentException(
					"AutowiredAnnotationBeanPostProcessor requires a ConfigurableListableBeanFactory: " + beanFactory);
		}
		this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
	}


	@Override
	public void postProcessMergedBeanDefinition(RootBeanDefinition beanDefinition, Class<?> beanType, String beanName) {
		// findAutowiringMetadata完成了对需要注入属性的筛选工作，将筛选通过的bean信息缓存到injectionMetadataCache 中，表示当前加载的bean需要注入的bean属性
		InjectionMetadata metadata = findAutowiringMetadata(beanName, beanType, null);
		// InjectionMetadata#checkConfigMembers 这里个人认为是做了一个注入对象的缓存
		// 后面执行postProcessProperties & postProcessPropertyValues时使用（个人认为）
		metadata.checkConfigMembers(beanDefinition);
	}

	@Override
	public void resetBeanDefinition(String beanName) {
		this.lookupMethodsChecked.remove(beanName);
		this.injectionMetadataCache.remove(beanName);
	}

	/**
	 * 检测出候选的构造器们（也就是我们常说的：构造器注入）
	 * 检测Bean的构造器，可以检测出多个候选构造器，再有相应的策略决定使用哪一个。它将将自动扫描通过@Autowired/@Value注解的构造器从而可以完成构造器注入
	 *
	 * 返回标有@Autowired、@Inject注解，以及无参构造器的构造器们
	 */
	@Override
	@Nullable
	public Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, final String beanName)
			throws BeanCreationException {

		// Let's check for lookup methods here...
		// 在这里首先处理了@Lookup注解
		// 判断是否已经解析过 。lookupMethodsChecked 作为一个缓存集合，保存已经处理过的bean
		if (!this.lookupMethodsChecked.contains(beanName)) {
			if (AnnotationUtils.isCandidateClass(beanClass, Lookup.class)) {
				try {
					Class<?> targetClass = beanClass;
					do {
						// 遍历bean中的每一个方法
						ReflectionUtils.doWithLocalMethods(targetClass, method -> {
							// 判断 方法是否被 @Lookup 修饰
							Lookup lookup = method.getAnnotation(Lookup.class);
							if (lookup != null) {
								Assert.state(this.beanFactory != null, "No BeanFactory available");
								// 如果被@Lookup 修饰，则封装后保存到RootBeanDefinition 的methodOverrides 属性中，
								// 在 SimpleInstantiationStrategy#instantiate(RootBeanDefinition, String, BeanFactory) 进行了cglib的动态代理。
								LookupOverride override = new LookupOverride(method, lookup.value());
								try {
									RootBeanDefinition mbd = (RootBeanDefinition)
											this.beanFactory.getMergedBeanDefinition(beanName);
									mbd.getMethodOverrides().addOverride(override);
								}
								catch (NoSuchBeanDefinitionException ex) {
									throw new BeanCreationException(beanName,
											"Cannot apply @Lookup to beans without corresponding bean definition");
								}
							}
						});
						targetClass = targetClass.getSuperclass();
					}
					while (targetClass != null && targetClass != Object.class);

				}
				catch (IllegalStateException ex) {
					throw new BeanCreationException(beanName, "Lookup method resolution failed", ex);
				}
			}
			// 将已经解析好的beanName 添加到缓存中
			this.lookupMethodsChecked.add(beanName);
		}

		// Quick check on the concurrent map first, with minimal locking.
		// 先从缓存里去看，有没有解析过此类的构造函数~~~
		// 对每个类的构造函数只解析一次，解析完会存储结果，以备下次复用
		Constructor<?>[] candidateConstructors = this.candidateConstructorsCache.get(beanClass);
		if (candidateConstructors == null) {
			// Fully synchronized resolution now...
			synchronized (this.candidateConstructorsCache) {
				// 为了线程安全，这里继续校验一次
				candidateConstructors = this.candidateConstructorsCache.get(beanClass);
				if (candidateConstructors == null) {
					Constructor<?>[] rawCandidates;
					try {
						// 通过反射获取，拿到此Class所有的构造函数们（一般的类都只有一个空的构造函数）  当然我们也可以写多个
						rawCandidates = beanClass.getDeclaredConstructors();
					}
					catch (Throwable ex) {
						throw new BeanCreationException(beanName,
								"Resolution of declared constructors on bean Class [" + beanClass.getName() +
								"] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
					}
					// 标注有@Autowired注解的构造函数候选者
					List<Constructor<?>> candidates = new ArrayList<>(rawCandidates.length);
					// @Autowired(required = true) 的构造函数,有且只能有一个
					Constructor<?> requiredConstructor = null;
					// 默认的无参构造函数
					Constructor<?> defaultConstructor = null;
					// 针对 Kotlin 语言的构造函数，不太明白，一般为null
					Constructor<?> primaryConstructor = BeanUtils.findPrimaryConstructor(beanClass);
					int nonSyntheticConstructors = 0;
					// 遍历处理每个构造器~~~~~~~~~~~~~~~~~~
					for (Constructor<?> candidate : rawCandidates) {
						// 构造函数是否是非合成，一般我们自己创建的都是非合成的。Java在编译过程中可能会出现一些合成的构造函数
						if (!candidate.isSynthetic()) {
							nonSyntheticConstructors++;
						}
						else if (primaryConstructor != null) {
							continue;
						}
						// 遍历autowiredAnnotationTypes 集合，判断当前构造函数是否被 autowiredAnnotationTypes集合中的注解修饰,若未被修饰，则返回null
						// autowiredAnnotationTypes 集合中的注解在一开始就说了是 @Autowired、@Value 和 @Inject 三个。
						// 找到构造器里有@Autowaired或者@Value注解的直接信息们
						MergedAnnotation<?> ann = findAutowiredAnnotation(candidate);
						if (ann == null) {
							// 此方法的目的是拿到目标类：比如若是被cglib代理过的，那就拿到父类（因为cglib是通过子类的形式加强的）
							Class<?> userClass = ClassUtils.getUserClass(beanClass);
							// 如果这里不相等，肯定是通过 cglib的代理类，这里的userClass 就是原始类，再次判断构造函数是否包含指定注解
							// 说明确实是被CGLIB代理过的，那就再解析一次  看看父类是否有@Autowaired这种构造器
							if (userClass != beanClass) {
								try {
									Constructor<?> superCtor =
											userClass.getDeclaredConstructor(candidate.getParameterTypes());
									ann = findAutowiredAnnotation(superCtor);
								}
								catch (NoSuchMethodException ex) {
									// Simply proceed, no equivalent superclass constructor found...
								}
							}
						}
						// 这里是存在注解标注的这种构造器的
						if (ann != null) {
							// 如果已经找到了必须装配的构造函数(requiredConstructor  != null)，那么当前这个就是多余的，则抛出异常
							// 这个判断很有必要，表示要求的构造器最多只能有一个
							//画外音：@Autowired标注的构造器数量最多只能有一个（当然，required=true的只能有一个，=false的可以有多个）
							if (requiredConstructor != null) {
								throw new BeanCreationException(beanName,
										"Invalid autowire-marked constructor: " + candidate +
										". Found constructor with 'required' Autowired annotation already: " +
										requiredConstructor);
							}
							//获取autowire注解中required属性值
							// 确定是否是必须的，@Autowired 和@Inject 默认为true。@Autowired 可以通过 required 修改
							boolean required = determineRequiredStatus(ann);
							// 只有是true，就往下走（默认值为true），
							// 如果@Autowired(required = false)，则是没用的，并且有多个，会报错。
							if (required) {
								// 如果当前构造函数为必须注入，但是候选列表不为空，则说明已经有构造函数适配，则抛出异常。
								// 就是只要有required = true的构造函数就不允许存在其他可注入的构造函数
								if (!candidates.isEmpty()) {
									throw new BeanCreationException(beanName,
											"Invalid autowire-marked constructors: " + candidates +
											". Found constructor with 'required' Autowired annotation: " +
											candidate);
								}
								// 这样子，这个构造器就是必须的了，记录下来
								requiredConstructor = candidate;
							}
							// 把所有标注有@Autowired注解的构造器（不论required=true/false），记录下来，作为候选的构造器
							candidates.add(candidate);
						}
						// 这个就重要了，处理精妙
						// 若该构造器没有被标注@Autowired注解，但是它是无参构造器，那就当然候选的构造器（当然是以标注了@Autowired的为准）
						else if (candidate.getParameterCount() == 0) {
							// 这里注意：虽然把默认的构造函数记录下来了，但是并没有加进candidates里
							defaultConstructor = candidate;
						}
					}
					// 若能找到候选的构造器,这里注意，如果仅仅只有一个构造器的情况（没有标注@Autowired注解），这个亏胡原始股fakse，下面的elseif会处理的。。。。
					if (!candidates.isEmpty()) {
						// Add default constructor to list of optional constructors, as fallback.
						// 这个是candidates里面有值了，并且还没有requiredConstructor （相当于标注了注解@Autowired，但是required=false）   的情况下，会吧默认的构造函数加进candidates
						if (requiredConstructor == null) {
							if (defaultConstructor != null) {
								candidates.add(defaultConstructor);
							}
							//如果没有默认的无参构造函数，且有@Autowired（required = false）的构造函数，则发出警告信
							else if (candidates.size() == 1 && logger.isInfoEnabled()) {
								logger.info("Inconsistent constructor declaration on bean with name '" + beanName +
										"': single autowire-marked constructor flagged as optional - " +
										"this constructor is effectively required since there is no " +
										"default constructor to fall back to: " + candidates.get(0));
							}
						}
						candidateConstructors = candidates.toArray(new Constructor<?>[0]);
					}
					// 这个意思是：有且仅有一个构造器，并且该构造器的参数大于0个，那就是我们要找的构造器了
					// 这种情况，也是平时我们使用得比较多的情况
					else if (rawCandidates.length == 1 && rawCandidates[0].getParameterCount() > 0) {
						candidateConstructors = new Constructor<?>[] {rawCandidates[0]};
					}
					// 处理primaryConstructor以及nonSyntheticConstructors    兼容Kotlin一般都用不到
					else if (nonSyntheticConstructors == 2 && primaryConstructor != null &&
							defaultConstructor != null && !primaryConstructor.equals(defaultConstructor)) {
						candidateConstructors = new Constructor<?>[] {primaryConstructor, defaultConstructor};
					}
					else if (nonSyntheticConstructors == 1 && primaryConstructor != null) {
						candidateConstructors = new Constructor<?>[] {primaryConstructor};
					}
					// 啥构造器都没找到，那就是空数组
					else {
						candidateConstructors = new Constructor<?>[0];
					}
					this.candidateConstructorsCache.put(beanClass, candidateConstructors);
				}
			}
		}
		// 若有多个构造函数，但是没有一个标记了@Autowired,此处不会报错，但是返回null，交给后面的策略处理
		return (candidateConstructors.length > 0 ? candidateConstructors : null);
	}

	@Override
	public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
		// 找到Bean组件的InjectionMetadata，里面包含AutowiredFieldElement（主要处理类），
		// 这里的metadata在org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor.postProcessMergedBeanDefinition中就已经解析好了
		InjectionMetadata metadata = findAutowiringMetadata(beanName, bean.getClass(), pvs);
		try {
			// 通过InjectionMetadata，进行注入
			metadata.inject(bean, beanName, pvs);
		}
		catch (BeanCreationException ex) {
			throw ex;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(beanName, "Injection of autowired dependencies failed", ex);
		}
		return pvs;
	}

	@Deprecated
	@Override
	public PropertyValues postProcessPropertyValues(
			PropertyValues pvs, PropertyDescriptor[] pds, Object bean, String beanName) {

		return postProcessProperties(pvs, bean, beanName);
	}

	/**
	 * 'Native' processing method for direct calls with an arbitrary target instance,
	 * resolving all of its fields and methods which are annotated with one of the
	 * configured 'autowired' annotation types.
	 * @param bean the target instance to process
	 * @throws BeanCreationException if autowiring failed
	 * @see #setAutowiredAnnotationTypes(Set)
	 */
	public void processInjection(Object bean) throws BeanCreationException {
		Class<?> clazz = bean.getClass();
		InjectionMetadata metadata = findAutowiringMetadata(clazz.getName(), clazz, null);
		try {
			metadata.inject(bean, null, null);
		}
		catch (BeanCreationException ex) {
			throw ex;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(
					"Injection of autowired dependencies failed for class [" + clazz + "]", ex);
		}
	}


	/**
	 * findAutowiringMetadata完成了对需要注入属性的筛选工作，将筛选通过的bean信息缓存到injectionMetadataCache 中，表示当前加载的bean需要注入的bean属性
	 */
	private InjectionMetadata findAutowiringMetadata(String beanName, Class<?> clazz, @Nullable PropertyValues pvs) {
		// Fall back to class name as cache key, for backwards compatibility with custom callers.
		String cacheKey = (StringUtils.hasLength(beanName) ? beanName : clazz.getName());
		// Quick check on the concurrent map first, with minimal locking.
		// 从缓存中获取metadata
		InjectionMetadata metadata = this.injectionMetadataCache.get(cacheKey);
		// 如果需要刷新  ： metadata == null || metadata.needsRefresh(clazz)
		if (InjectionMetadata.needsRefresh(metadata, clazz)) {
			synchronized (this.injectionMetadataCache) {
				metadata = this.injectionMetadataCache.get(cacheKey);
				if (InjectionMetadata.needsRefresh(metadata, clazz)) {
					if (metadata != null) {
						metadata.clear(pvs);
					}
					// 创建 metadata(核心方法)
					metadata = buildAutowiringMetadata(clazz);
					this.injectionMetadataCache.put(cacheKey, metadata);
				}
			}
		}
		return metadata;
	}

	/**
	 * 核心的筛选功能方法
	 * @return 返回类的属性、注解等等用于依赖注入的元数据
	 *
	 *  遍历当前bean中的所有属性和方法，如果包含指定属性，则保存起来。属性保存的类型是AutowiredFieldElement, 方法保存的类型是 AutowiredMethodElement。
	 *
	 * 注： AutowiredFieldElement 和 AutowiredMethodElement 是 AutowiredAnnotationBeanPostProcessor 的内部类
	 */
	private InjectionMetadata buildAutowiringMetadata(final Class<?> clazz) {
		// 确定给定的类是否适合携带指定的注释，比如一些注释只能用在方法或者类上
		if (!AnnotationUtils.isCandidateClass(clazz, this.autowiredAnnotationTypes)) {
			return InjectionMetadata.EMPTY;
		}

		List<InjectionMetadata.InjectedElement> elements = new ArrayList<>();
		Class<?> targetClass = clazz;

		do {
			final List<InjectionMetadata.InjectedElement> currElements = new ArrayList<>();

			// 遍历类中的每个属性，判断属性是否包含指定的属性(通过 findAutowiredAnnotation 方法)
			// 如果存在则保存，这里注意，属性保存的类型是 AutowiredFieldElement
			ReflectionUtils.doWithLocalFields(targetClass, field -> {
				// 找到注解信息
				MergedAnnotation<?> ann = findAutowiredAnnotation(field);
				if (ann != null) {
					if (Modifier.isStatic(field.getModifiers())) {
						if (logger.isInfoEnabled()) {
							logger.info("Autowired annotation is not supported on static fields: " + field);
						}
						return;
					}
					// 注解的required属性
					boolean required = determineRequiredStatus(ann);
					currElements.add(new AutowiredFieldElement(field, required));
				}
			});

			// 遍历类中的每个方法，判断属性是否包含指定的属性(通过 findAutowiredAnnotation 方法)
			// 如果存在则保存，这里注意，方法保存的类型是 AutowiredMethodElement
			ReflectionUtils.doWithLocalMethods(targetClass, method -> {
				Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(method);
				if (!BridgeMethodResolver.isVisibilityBridgeMethodPair(method, bridgedMethod)) {
					return;
				}
				MergedAnnotation<?> ann = findAutowiredAnnotation(bridgedMethod);
				if (ann != null && method.equals(ClassUtils.getMostSpecificMethod(method, clazz))) {
					if (Modifier.isStatic(method.getModifiers())) {
						if (logger.isInfoEnabled()) {
							logger.info("Autowired annotation is not supported on static methods: " + method);
						}
						return;
					}
					if (method.getParameterCount() == 0) {
						if (logger.isInfoEnabled()) {
							logger.info("Autowired annotation should only be used on methods with parameters: " +
									method);
						}
					}
					boolean required = determineRequiredStatus(ann);
					PropertyDescriptor pd = BeanUtils.findPropertyForMethod(bridgedMethod, clazz);
					currElements.add(new AutowiredMethodElement(method, required, pd));
				}
			});

			elements.addAll(0, currElements);
			targetClass = targetClass.getSuperclass();
		}
		while (targetClass != null && targetClass != Object.class);

		// 封装返回
		return InjectionMetadata.forElements(elements, clazz);
	}

	@Nullable
	private MergedAnnotation<?> findAutowiredAnnotation(AccessibleObject ao) {
		MergedAnnotations annotations = MergedAnnotations.from(ao);
		for (Class<? extends Annotation> type : this.autowiredAnnotationTypes) {
			MergedAnnotation<?> annotation = annotations.get(type);
			if (annotation.isPresent()) {
				return annotation;
			}
		}
		return null;
	}

	/**
	 * Determine if the annotated field or method requires its dependency.
	 * <p>A 'required' dependency means that autowiring should fail when no beans
	 * are found. Otherwise, the autowiring process will simply bypass the field
	 * or method when no beans are found.
	 * @param ann the Autowired annotation
	 * @return whether the annotation indicates that a dependency is required
	 */
	@SuppressWarnings({"deprecation", "cast"})
	protected boolean determineRequiredStatus(MergedAnnotation<?> ann) {
		// The following (AnnotationAttributes) cast is required on JDK 9+.
		return determineRequiredStatus((AnnotationAttributes)
				ann.asMap(mergedAnnotation -> new AnnotationAttributes(mergedAnnotation.getType())));
	}

	/**
	 * Determine if the annotated field or method requires its dependency.
	 * <p>A 'required' dependency means that autowiring should fail when no beans
	 * are found. Otherwise, the autowiring process will simply bypass the field
	 * or method when no beans are found.
	 * @param ann the Autowired annotation
	 * @return whether the annotation indicates that a dependency is required
	 * @deprecated since 5.2, in favor of {@link #determineRequiredStatus(MergedAnnotation)}
	 */
	@Deprecated
	protected boolean determineRequiredStatus(AnnotationAttributes ann) {
		return (!ann.containsKey(this.requiredParameterName) ||
				this.requiredParameterValue == ann.getBoolean(this.requiredParameterName));
	}

	/**
	 * Obtain all beans of the given type as autowire candidates.
	 * @param type the type of the bean
	 * @return the target beans, or an empty Collection if no bean of this type is found
	 * @throws BeansException if bean retrieval failed
	 */
	protected <T> Map<String, T> findAutowireCandidates(Class<T> type) throws BeansException {
		if (this.beanFactory == null) {
			throw new IllegalStateException("No BeanFactory configured - " +
					"override the getBeanOfType method or specify the 'beanFactory' property");
		}
		return BeanFactoryUtils.beansOfTypeIncludingAncestors(this.beanFactory, type);
	}

	/**
	 * Register the specified bean as dependent on the autowired beans.
	 */
	private void registerDependentBeans(@Nullable String beanName, Set<String> autowiredBeanNames) {
		if (beanName != null) {
			for (String autowiredBeanName : autowiredBeanNames) {
				if (this.beanFactory != null && this.beanFactory.containsBean(autowiredBeanName)) {
					this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
				}
				if (logger.isTraceEnabled()) {
					logger.trace("Autowiring by type from bean name '" + beanName +
							"' to bean named '" + autowiredBeanName + "'");
				}
			}
		}
	}

	/**
	 * Resolve the specified cached method argument or field value.
	 */
	@Nullable
	private Object resolvedCachedArgument(@Nullable String beanName, @Nullable Object cachedArgument) {
		if (cachedArgument instanceof DependencyDescriptor) {
			DependencyDescriptor descriptor = (DependencyDescriptor) cachedArgument;
			Assert.state(this.beanFactory != null, "No BeanFactory available");
			// 把当前bean所依赖的这个Bean解析出来（从Spring容器里面拿，或者别的地方获取吧~~~）
			return this.beanFactory.resolveDependency(descriptor, beanName, null, null);
		}
		else {
			return cachedArgument;
		}
	}


	/**
	 * Class representing injection information about an annotated field.
	 */
	private class AutowiredFieldElement extends InjectionMetadata.InjectedElement {

		private final boolean required;

		private volatile boolean cached;

		@Nullable
		private volatile Object cachedFieldValue;

		public AutowiredFieldElement(Field field, boolean required) {
			super(field, null);
			this.required = required;
		}

		// 每个属性列的依赖注入
		// 这段代码虽然长，其实核心逻辑还并不在这里，而是在beanFactory Bean工厂的resolveDependency处理依赖实现里
		@Override
		protected void inject(Object bean, @Nullable String beanName, @Nullable PropertyValues pvs) throws Throwable {
			// 拿到这个字段名
			Field field = (Field) this.member;
			Object value;
			// 大多数情况下，这里都是false
			if (this.cached) {
				try {
					value = resolvedCachedArgument(beanName, this.cachedFieldValue);
				}
				catch (NoSuchBeanDefinitionException ex) {
					// Unexpected removal of target bean for cached argument -> re-resolve
					value = resolveFieldValue(field, bean, beanName);
				}
			}
			else {
				value = resolveFieldValue(field, bean, beanName);
			}
			if (value != null) {
				// 给该对象的该字段赋值。注意这里makeAccessible设置为true了，所以即使你的字段是private的也木有关系哦~~~
				ReflectionUtils.makeAccessible(field);
				field.set(bean, value);
			}
		}

		@Nullable
		private Object resolveFieldValue(Field field, Object bean, @Nullable String beanName) {
			// 把field和required属性，包装成desc描述类
			DependencyDescriptor desc = new DependencyDescriptor(field, this.required);
			desc.setContainingClass(bean.getClass());
			// 装载注入的名称，最最最后会被注册（缓存）起来
			Set<String> autowiredBeanNames = new LinkedHashSet<>(1);
			Assert.state(beanFactory != null, "No BeanFactory available");
			// 转换器，没有手动注册，默认都是SimpleTypeConverter===============
			TypeConverter typeConverter = beanFactory.getTypeConverter();
			Object value;
			try {
				// 把desc传进去，里面还有注解信息、元信息等等，这个方法是根据注解信息寻找到依赖的Bean的核心逻辑
				// 备注：此部分处理依赖逻辑交给Bean工厂，其实也没毛病。毕竟它处理的相当于是Field
				// 那么接下里，重点分析resolveDependency这个方法

				// 把当前bean所依赖的这个Bean解析出来（从Spring容器里面拿，或者别的地方获取吧~~~）
				value = beanFactory.resolveDependency(desc, beanName, autowiredBeanNames, typeConverter);
			}
			catch (BeansException ex) {
				throw new UnsatisfiedDependencyException(null, beanName, new InjectionPoint(field), ex);
			}
			synchronized (this) {
				if (!this.cached) {
					Object cachedFieldValue = null;
					// 如果已经拿到了这个Bean实例，
					if (value != null || this.required) {
						cachedFieldValue = desc;
						// 把该Bean的依赖关系再注册一次
						registerDependentBeans(beanName, autowiredBeanNames);
						// 因为是按照类型注入，所以肯定只能指导一个这个的依赖Bean，否则上面解析就已经报错了
						if (autowiredBeanNames.size() == 1) {
							String autowiredBeanName = autowiredBeanNames.iterator().next();
							// 这里是来处理放置缓存的字段值
							if (beanFactory.containsBean(autowiredBeanName) &&
									beanFactory.isTypeMatch(autowiredBeanName, field.getType())) {
								cachedFieldValue = new ShortcutDependencyDescriptor(
										desc, autowiredBeanName, field.getType());
							}
						}
					}
					this.cachedFieldValue = cachedFieldValue;
					this.cached = true;
				}
			}
			return value;
		}
	}


	/**
	 * Class representing injection information about an annotated method.
	 */
	private class AutowiredMethodElement extends InjectionMetadata.InjectedElement {

		private final boolean required;

		private volatile boolean cached;

		@Nullable
		private volatile Object[] cachedMethodArguments;

		public AutowiredMethodElement(Method method, boolean required, @Nullable PropertyDescriptor pd) {
			super(method, pd);
			this.required = required;
		}

		@Override
		protected void inject(Object bean, @Nullable String beanName, @Nullable PropertyValues pvs) throws Throwable {
			if (checkPropertySkipping(pvs)) {
				return;
			}
			Method method = (Method) this.member;
			Object[] arguments;
			if (this.cached) {
				try {
					arguments = resolveCachedArguments(beanName);
				}
				catch (NoSuchBeanDefinitionException ex) {
					// Unexpected removal of target bean for cached argument -> re-resolve
					arguments = resolveMethodArguments(method, bean, beanName);
				}
			}
			else {
				arguments = resolveMethodArguments(method, bean, beanName);
			}
			if (arguments != null) {
				try {
					ReflectionUtils.makeAccessible(method);
					method.invoke(bean, arguments);
				}
				catch (InvocationTargetException ex) {
					throw ex.getTargetException();
				}
			}
		}

		@Nullable
		private Object[] resolveCachedArguments(@Nullable String beanName) {
			Object[] cachedMethodArguments = this.cachedMethodArguments;
			if (cachedMethodArguments == null) {
				return null;
			}
			Object[] arguments = new Object[cachedMethodArguments.length];
			for (int i = 0; i < arguments.length; i++) {
				arguments[i] = resolvedCachedArgument(beanName, cachedMethodArguments[i]);
			}
			return arguments;
		}

		@Nullable
		private Object[] resolveMethodArguments(Method method, Object bean, @Nullable String beanName) {
			int argumentCount = method.getParameterCount();
			Object[] arguments = new Object[argumentCount];
			DependencyDescriptor[] descriptors = new DependencyDescriptor[argumentCount];
			Set<String> autowiredBeans = new LinkedHashSet<>(argumentCount);
			Assert.state(beanFactory != null, "No BeanFactory available");
			TypeConverter typeConverter = beanFactory.getTypeConverter();
			for (int i = 0; i < arguments.length; i++) {
				MethodParameter methodParam = new MethodParameter(method, i);
				DependencyDescriptor currDesc = new DependencyDescriptor(methodParam, this.required);
				currDesc.setContainingClass(bean.getClass());
				descriptors[i] = currDesc;
				try {
					Object arg = beanFactory.resolveDependency(currDesc, beanName, autowiredBeans, typeConverter);
					if (arg == null && !this.required) {
						arguments = null;
						break;
					}
					arguments[i] = arg;
				}
				catch (BeansException ex) {
					throw new UnsatisfiedDependencyException(null, beanName, new InjectionPoint(methodParam), ex);
				}
			}
			synchronized (this) {
				if (!this.cached) {
					if (arguments != null) {
						DependencyDescriptor[] cachedMethodArguments = Arrays.copyOf(descriptors, arguments.length);
						registerDependentBeans(beanName, autowiredBeans);
						if (autowiredBeans.size() == argumentCount) {
							Iterator<String> it = autowiredBeans.iterator();
							Class<?>[] paramTypes = method.getParameterTypes();
							for (int i = 0; i < paramTypes.length; i++) {
								String autowiredBeanName = it.next();
								if (beanFactory.containsBean(autowiredBeanName) &&
										beanFactory.isTypeMatch(autowiredBeanName, paramTypes[i])) {
									cachedMethodArguments[i] = new ShortcutDependencyDescriptor(
											descriptors[i], autowiredBeanName, paramTypes[i]);
								}
							}
						}
						this.cachedMethodArguments = cachedMethodArguments;
					}
					else {
						this.cachedMethodArguments = null;
					}
					this.cached = true;
				}
			}
			return arguments;
		}
	}


	/**
	 * DependencyDescriptor variant with a pre-resolved target bean name.
	 */
	@SuppressWarnings("serial")
	private static class ShortcutDependencyDescriptor extends DependencyDescriptor {

		private final String shortcut;

		private final Class<?> requiredType;

		public ShortcutDependencyDescriptor(DependencyDescriptor original, String shortcut, Class<?> requiredType) {
			super(original);
			this.shortcut = shortcut;
			this.requiredType = requiredType;
		}

		@Override
		public Object resolveShortcut(BeanFactory beanFactory) {
			return beanFactory.getBean(this.shortcut, this.requiredType);
		}
	}

}
