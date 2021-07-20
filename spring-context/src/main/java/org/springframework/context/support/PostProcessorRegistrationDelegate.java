/*
 * Copyright 2002-2020 the original author or authors.
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.lang.Nullable;

/**
 * Delegate for AbstractApplicationContext's post-processor handling.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 4.0
 */
final class PostProcessorRegistrationDelegate {

	private PostProcessorRegistrationDelegate() {
	}

	/**
	 * 1、执行了BeanDefinitionRegistryPostProcessor（此处只有ConfigurationClassPostProcessor）
	 * 2、执行了BeanFactoryPostProcessor
	 * 3、完成了@Configuration配置文件的解析，并且把扫描到的、配置的Bean定义信息都加载进容器里
	 * 4、Full模式下，完成了对@Configuration配置文件的加强，使得管理Bean依赖关系更加的方便了
	 */
	public static void invokeBeanFactoryPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanFactoryPostProcessor> beanFactoryPostProcessors) {

		// Invoke BeanDefinitionRegistryPostProcessors first, if any.
		// 这个doc说明很清楚：不管怎么样，先执行BeanDefinitionRegistryPostProcessors
		// 需要注意的是BeanDefinitionRegistryPostProcessors 为 BeanFactoryPostProcessor 的子接口
		// 它新增了方法：void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry)
		// BeanFactoryPostProcessor 的方法为;void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException;
		// 所以BeanDefinitionRegistryPostProcessors，它可以我们介入，改变Bean的一些定义信息
		Set<String> processedBeans = new HashSet<>();

		// 只有此beanFactory 是BeanDefinitionRegistry  才能执行BeanDefinitionRegistryPostProcessor，才能修改Bean的定义嘛~
		if (beanFactory instanceof BeanDefinitionRegistry) {
			BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
			// 此处安放了两个容器，一个装载普通的BeanFactoryPostProcessor
			// 另外一个装载和Bean定义有关的 BeanDefinitionRegistryPostProcessor
			List<BeanFactoryPostProcessor> regularPostProcessors = new ArrayList<>();
			List<BeanDefinitionRegistryPostProcessor> registryProcessors = new ArrayList<>();

			// 这里是我们自己的set进去的，若没set，这里就是空(若是Spring容器里的，下面会处理，见下面)
			// 从此处可以看出，我们手动set进去的，最最最最优先执行的
			for (BeanFactoryPostProcessor postProcessor : beanFactoryPostProcessors) {
				if (postProcessor instanceof BeanDefinitionRegistryPostProcessor) {
					BeanDefinitionRegistryPostProcessor registryProcessor =
							(BeanDefinitionRegistryPostProcessor) postProcessor;
					// 这里执行post方法，然后然后吧它缓冲起来了，放在了registryProcessors里
					registryProcessor.postProcessBeanDefinitionRegistry(registry);
					registryProcessors.add(registryProcessor);
				}
				else {
					// 缓冲起来常规的处理器
					regularPostProcessors.add(postProcessor);
				}
			}

			// Do not initialize FactoryBeans here: We need to leave all regular beans
			// uninitialized to let the bean factory post-processors apply to them!
			// Separate between BeanDefinitionRegistryPostProcessors that implement
			// PriorityOrdered, Ordered, and the rest.
			// 接下来，就是去执行Spring容器里面的一些PostProcessor了。他们顺序doc里也写得很清楚：
			// 先执行实现了PriorityOrdered接口的，然后是Ordered接口的，最后执行剩下的
			List<BeanDefinitionRegistryPostProcessor> currentRegistryProcessors = new ArrayList<>();

			// First, invoke the BeanDefinitionRegistryPostProcessors that implement PriorityOrdered.
			// 先从容器中拿出来所有的BeanDefinitionRegistryPostProcessor 然后先执行PriorityOrdered
			// 本例中有一个这个类型的处理器：ConfigurationClassPostProcessor（显然是处理@Configuration这种Bean的）
			// 至于这个Bean是什么时候注册进去的，前面有。
			// 在loadBeanDefinitions()初始化AnnotatedBeanDefinitionReader的时候
			// 调用的AnnotationConfigUtils.registerAnnotationConfigProcessors(this.registry)方法的时候，注册了6个Bean
			String[] postProcessorNames =
					beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			for (String ppName : postProcessorNames) {
				if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
					// processedBeans也顺带保存了一份，保存的是bean的Name哦~
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					processedBeans.add(ppName);
				}
			}
			// 排序
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			// 此处缓冲起来（需要注意的是，是排序后，再放进去的 这样是最好的）
			registryProcessors.addAll(currentRegistryProcessors);
			// 这个方法很简单，就是吧currentRegistryProcessors里面所有的处理器for循环一个个的执行掉(本处只有ConfigurationClassPostProcessor)
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
			// 此处把当前持有的执行对象给清空了，需要注意。以方便装载后续执行的处理器们
			currentRegistryProcessors.clear();

			// Next, invoke the BeanDefinitionRegistryPostProcessors that implement Ordered.
			// 此处逻辑完全同上  处理实现Order接口的RegistryProcessors
			postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			for (String ppName : postProcessorNames) {
				if (!processedBeans.contains(ppName) && beanFactory.isTypeMatch(ppName, Ordered.class)) {
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					processedBeans.add(ppName);
				}
			}
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			registryProcessors.addAll(currentRegistryProcessors);
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
			currentRegistryProcessors.clear();

			// Finally, invoke all other BeanDefinitionRegistryPostProcessors until no further ones appear.
			// 最后执行，两个排序接口都没有实现的BeanDefinitionRegistryPostProcessor们，并且也缓存起来
			boolean reiterate = true;
			while (reiterate) {
				reiterate = false;
				postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
				for (String ppName : postProcessorNames) {
					if (!processedBeans.contains(ppName)) {
						currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
						processedBeans.add(ppName);
						reiterate = true;
					}
				}
				sortPostProcessors(currentRegistryProcessors, beanFactory);
				registryProcessors.addAll(currentRegistryProcessors);
				invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
				currentRegistryProcessors.clear();
			}

			// Now, invoke the postProcessBeanFactory callback of all processors handled so far.
			// 现在，这里很明显：去执行BeanDefinitionRegistryPostProcessor的postProcessBeanFactory方法
			// 以及 顶层接口BeanFactoryPostProcessor的postProcessBeanFactory方法
			// 我们当前环境regularPostProcessors长度为0.registryProcessors有一个解析@Configuration的处理器
			invokeBeanFactoryPostProcessors(registryProcessors, beanFactory);
			invokeBeanFactoryPostProcessors(regularPostProcessors, beanFactory);
		}

		else {
			// Invoke factory processors registered with the context instance.
			// 若是普通的Bean工厂，就直接执行set进来的后置处理器即可（因为容器里就没有其它Bean定义了）
			invokeBeanFactoryPostProcessors(beanFactoryPostProcessors, beanFactory);
		}

		// Do not initialize FactoryBeans here: We need to leave all regular beans
		// uninitialized to let the bean factory post-processors apply to them!
		// 下面就是开始执行BeanFactoryPostProcessor 基本也是按照上面的顺序来执行的
		// 上面9个Bean，我们知道 也就ConfigurationClassPostProcessor是实现了此接口的。因此本环境下，只有它了，并且它在上面还已经执行了
		String[] postProcessorNames =
				beanFactory.getBeanNamesForType(BeanFactoryPostProcessor.class, true, false);

		// Separate between BeanFactoryPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		List<BeanFactoryPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		List<String> orderedPostProcessorNames = new ArrayList<>();
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		for (String ppName : postProcessorNames) {
			// 这里面注意，已经执行过的后置处理器，就不要再执行了
			if (processedBeans.contains(ppName)) {
				// skip - already processed in first phase above
			}
			else if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				priorityOrderedPostProcessors.add(beanFactory.getBean(ppName, BeanFactoryPostProcessor.class));
			}
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, invoke the BeanFactoryPostProcessors that implement PriorityOrdered.
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		invokeBeanFactoryPostProcessors(priorityOrderedPostProcessors, beanFactory);

		// Next, invoke the BeanFactoryPostProcessors that implement Ordered.
		List<BeanFactoryPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String postProcessorName : orderedPostProcessorNames) {
			orderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		sortPostProcessors(orderedPostProcessors, beanFactory);
		invokeBeanFactoryPostProcessors(orderedPostProcessors, beanFactory);

		// Finally, invoke all other BeanFactoryPostProcessors.
		List<BeanFactoryPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String postProcessorName : nonOrderedPostProcessorNames) {
			nonOrderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		invokeBeanFactoryPostProcessors(nonOrderedPostProcessors, beanFactory);

		// Clear cached merged bean definitions since the post-processors might have
		// modified the original metadata, e.g. replacing placeholders in values...
		beanFactory.clearMetadataCache();
	}

	public static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, AbstractApplicationContext applicationContext) {

		// 从所与Bean定义中提取出BeanPostProcessor类型的Bean，显然，最初的6个bean，有三个是BeanPostProcessor：
		String[] postProcessorNames = beanFactory.getBeanNamesForType(BeanPostProcessor.class, true, false);

		// Register BeanPostProcessorChecker that logs an info message when
		// a bean is created during BeanPostProcessor instantiation, i.e. when
		// a bean is not eligible for getting processed by all BeanPostProcessors.
		// 此处有点意思了，向beanFactory又add了一个BeanPostProcessorChecker，并且此事后总数设置为了getBeanPostProcessorCount和addBeanPostProcessor的总和（+1表示自己）
		int beanProcessorTargetCount = beanFactory.getBeanPostProcessorCount() + 1 + postProcessorNames.length;
		// 此处注意：参数beanPostProcessorTargetCount表示的是处理器的总数，总数（包含两个位置离的，用于后面的校验）
		beanFactory.addBeanPostProcessor(new BeanPostProcessorChecker(beanFactory, beanProcessorTargetCount));

		// Separate between BeanPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		// 先按优先级，归类了BeanPostProcessor
		List<BeanPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		List<BeanPostProcessor> internalPostProcessors = new ArrayList<>();
		List<String> orderedPostProcessorNames = new ArrayList<>();
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		for (String ppName : postProcessorNames) {
			if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
				priorityOrderedPostProcessors.add(pp);
				// MergedBeanDefinitionPostProcessor则是在合并处理Bean定义的时候的回调。这个东东按我的理解也基本是框架内部使用的，用户不用管
				if (pp instanceof MergedBeanDefinitionPostProcessor) {
					internalPostProcessors.add(pp);
				}
			}
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, register the BeanPostProcessors that implement PriorityOrdered.
		// 排序后注册到容器中
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, priorityOrderedPostProcessors);

		// Next, register the BeanPostProcessors that implement Ordered.
		List<BeanPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String ppName : orderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			orderedPostProcessors.add(pp);
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		// 排序后注册到容器中
		sortPostProcessors(orderedPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, orderedPostProcessors);

		// Now, register all regular BeanPostProcessors.
		List<BeanPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String ppName : nonOrderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			nonOrderedPostProcessors.add(pp);
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		// 注册到容器中
		registerBeanPostProcessors(beanFactory, nonOrderedPostProcessors);

		// Finally, re-register all internal BeanPostProcessors.
		// 排序后注册到容器中
		sortPostProcessors(internalPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, internalPostProcessors);

		// Re-register post-processor for detecting inner beans as ApplicationListeners,
		// moving it to the end of the processor chain (for picking up proxies etc).
		// 最后此处需要注意的是：Spring还给我们注册了一个Bean的后置处理器：ApplicationListenerDetector  它的作用：用来检查所有得ApplicationListener
		// 有的人就想问了：之前不是注册过了吗，怎么这里又注册一次呢？其实上面的doc里面说得很清楚：
		// Re-register重新注册这个后置处理器。把它移动到处理器连条的最后面，最后执行（小技巧是：先remove，然后执行add操作~~~ 自己可以点进addBeanPostProcessor源码可以看到这个小技巧）
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(applicationContext));
	}

	private static void sortPostProcessors(List<?> postProcessors, ConfigurableListableBeanFactory beanFactory) {
		// Nothing to sort?
		if (postProcessors.size() <= 1) {
			return;
		}
		Comparator<Object> comparatorToUse = null;
		if (beanFactory instanceof DefaultListableBeanFactory) {
			comparatorToUse = ((DefaultListableBeanFactory) beanFactory).getDependencyComparator();
		}
		if (comparatorToUse == null) {
			comparatorToUse = OrderComparator.INSTANCE;
		}
		postProcessors.sort(comparatorToUse);
	}

	/**
	 * Invoke the given BeanDefinitionRegistryPostProcessor beans.
	 */
	private static void invokeBeanDefinitionRegistryPostProcessors(
			Collection<? extends BeanDefinitionRegistryPostProcessor> postProcessors, BeanDefinitionRegistry registry) {

		for (BeanDefinitionRegistryPostProcessor postProcessor : postProcessors) {
			postProcessor.postProcessBeanDefinitionRegistry(registry);
		}
	}

	/**
	 * Invoke the given BeanFactoryPostProcessor beans.
	 */
	private static void invokeBeanFactoryPostProcessors(
			Collection<? extends BeanFactoryPostProcessor> postProcessors, ConfigurableListableBeanFactory beanFactory) {

		for (BeanFactoryPostProcessor postProcessor : postProcessors) {
			postProcessor.postProcessBeanFactory(beanFactory);
		}
	}

	/**
	 * Register the given BeanPostProcessor beans.
	 */
	private static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanPostProcessor> postProcessors) {
		// 把类型是BeanPostProcessor的Bean，注册到beanFactory里面去
		for (BeanPostProcessor postProcessor : postProcessors) {
			beanFactory.addBeanPostProcessor(postProcessor);
		}
	}


	/**
	 * BeanPostProcessor that logs an info message when a bean is created during
	 * BeanPostProcessor instantiation, i.e. when a bean is not eligible for
	 * getting processed by all BeanPostProcessors.
	 */
	private static final class BeanPostProcessorChecker implements BeanPostProcessor {

		private static final Log logger = LogFactory.getLog(BeanPostProcessorChecker.class);

		private final ConfigurableListableBeanFactory beanFactory;

		private final int beanPostProcessorTargetCount;

		public BeanPostProcessorChecker(ConfigurableListableBeanFactory beanFactory, int beanPostProcessorTargetCount) {
			this.beanFactory = beanFactory;
			this.beanPostProcessorTargetCount = beanPostProcessorTargetCount;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			return bean;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if (!(bean instanceof BeanPostProcessor) && !isInfrastructureBean(beanName) &&
					this.beanFactory.getBeanPostProcessorCount() < this.beanPostProcessorTargetCount) {
				if (logger.isInfoEnabled()) {
					logger.info("Bean '" + beanName + "' of type [" + bean.getClass().getName() +
							"] is not eligible for getting processed by all BeanPostProcessors " +
							"(for example: not eligible for auto-proxying)");
				}
			}
			return bean;
		}

		private boolean isInfrastructureBean(@Nullable String beanName) {
			if (beanName != null && this.beanFactory.containsBeanDefinition(beanName)) {
				BeanDefinition bd = this.beanFactory.getBeanDefinition(beanName);
				return (bd.getRole() == RootBeanDefinition.ROLE_INFRASTRUCTURE);
			}
			return false;
		}
	}

}
